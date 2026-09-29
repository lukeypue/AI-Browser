package com.appgate.brain.planner

import com.appgate.brain.json.Json
import com.appgate.brain.json.JsonArray
import com.appgate.brain.json.JsonObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** Transport for redacted structured summaries. Every complete call makes at most one HTTP request. */
interface PlannerClient {
    fun complete(instructions: String, input: String, schemaName: String, schema: JsonObject, maxOutputTokens: Int = 1200): String
    val describe: String
}

/** Provider bodies may contain submitted text or credentials; they must never become an exception. */
class PlannerHttpException(val code: Int, @Suppress("UNUSED_PARAMETER") message: String = "HTTP $code") : IOException("HTTP $code")

enum class PlannerProvider { OPENAI, ANTHROPIC, OPENAI_COMPATIBLE, GROQ, GEMINI }

data class PlannerConfig(
    val provider: PlannerProvider = PlannerProvider.OPENAI,
    val apiKey: String,
    val model: String = "",
    val endpoint: String = "",
    val reasoningEffort: String = "",
    val connectTimeoutMs: Int = 15_000,
    val readTimeoutMs: Int = 45_000
) {
    val resolvedModel: String get() = model.ifBlank {
        when (provider) {
            PlannerProvider.OPENAI, PlannerProvider.OPENAI_COMPATIBLE -> "gpt-5.4-mini-2026-03-17"
            PlannerProvider.ANTHROPIC -> "claude-haiku-4-5-20251001"
            PlannerProvider.GROQ -> "openai/gpt-oss-20b"
            PlannerProvider.GEMINI -> "gemini-3.5-flash-lite"
        }
    }
    /** Endpoint is the complete operation URL; custom values are preserved. */
    val resolvedEndpoint: String get() = endpoint.ifBlank {
        when (provider) {
            PlannerProvider.OPENAI -> "https://api.openai.com/v1/responses"
            PlannerProvider.OPENAI_COMPATIBLE -> "https://api.openai.com/v1/chat/completions"
            PlannerProvider.ANTHROPIC -> "https://api.anthropic.com/v1/messages"
            PlannerProvider.GROQ -> "https://api.groq.com/openai/v1/chat/completions"
            PlannerProvider.GEMINI -> "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"
        }
    }
}

/** Strict Responses API; never changes models or retries after a failure. */
class OpenAiResponsesClient(
    private val config: PlannerConfig,
    private val http: HttpTransport = HttpTransport(),
    private val observer: PlannerRequestObserver? = null
) : PlannerClient {
    override val describe: String get() = "openai/${config.resolvedModel}"

    override fun complete(instructions: String, input: String, schemaName: String, schema: JsonObject, maxOutputTokens: Int): String {
        require(maxOutputTokens > 0) { "Output token limit must be positive" }
        val model = config.resolvedModel
        val body = JsonObject()
            .put("model", model)
            .put("store", false)
            .put("instructions", instructions)
            .put("input", input)
            .put("max_output_tokens", maxOutputTokens)
            .put("text", JsonObject().put("format", JsonObject()
                .put("type", "json_schema").put("name", schemaName).put("strict", true).put("schema", schema)))
        // Older explicitly selected models may not support `none`. Only our pinned default gets it automatically.
        val effort = config.reasoningEffort.ifBlank { if (model == "gpt-5.4-mini-2026-03-17") "none" else "" }
        if (effort.isNotBlank() && !model.startsWith("gpt-4")) {
            body.put("reasoning", JsonObject().put("effort", effort))
        }
        val json = request(config, http, observer, body, maxOutputTokens, mapOf("Authorization" to "Bearer ${config.apiKey}"))
        reportUsage(config, observer, json, UsageShape.RESPONSES)
        if (json.has("error") || json.optString("status") in setOf("incomplete", "failed", "cancelled")) throw IOException("Planner response did not complete")
        val text = json.optStringOrNull("output_text") ?: json.optArray("output")?.objects()
            ?.flatMap { it.optArray("content")?.objects().orEmpty() }
            ?.filter { it.optString("type") == "output_text" }
            ?.joinToString("") { it.optString("text") }.orEmpty()
        return validateJsonObject(text)
    }
}

/** OpenAI-compatible Chat Completions, also used by Groq and Gemini presets. */
class OpenAiChatCompletionsClient(
    private val config: PlannerConfig,
    private val http: HttpTransport = HttpTransport(),
    private val observer: PlannerRequestObserver? = null
) : PlannerClient {
    override val describe: String get() = "${config.provider.name.lowercase(Locale.ROOT)}/${config.resolvedModel}"

    override fun complete(instructions: String, input: String, schemaName: String, schema: JsonObject, maxOutputTokens: Int): String {
        require(maxOutputTokens > 0) { "Output token limit must be positive" }
        val model = config.resolvedModel
        val body = JsonObject()
            .put("model", model)
            .put("stream", false)
            .put("messages", JsonArray()
                .add(JsonObject().put("role", "system").put("content", instructions))
                .add(JsonObject().put("role", "user").put("content", input)))
            .put("response_format", JsonObject().put("type", "json_schema").put("json_schema", JsonObject()
                .put("name", schemaName).put("strict", true).put("schema", schema)))
        val tokenField = if (config.provider == PlannerProvider.GROQ ||
            (config.provider != PlannerProvider.GEMINI && (model.startsWith("gpt-5") || model.startsWith("gpt-6") || model.matches(Regex("o[1-9].*"))))) {
            "max_completion_tokens"
        } else "max_tokens"
        body.put(tokenField, maxOutputTokens)
        // Provider-specific reasoning flags are deliberately omitted from the portable chat contract.
        val json = request(config, http, observer, body, maxOutputTokens, mapOf("Authorization" to "Bearer ${config.apiKey}"))
        reportUsage(config, observer, json, UsageShape.CHAT)
        val choice = json.optArray("choices")?.objects()?.firstOrNull() ?: throw IOException("Missing planner choice")
        val message = choice.optObject("message") ?: throw IOException("Missing planner message")
        if (choice.optString("finish_reason") != "stop" || message.has("refusal")) throw IOException("Planner response did not complete")
        return validateJsonObject(message.optString("content"))
    }
}

/** Anthropic Messages protocol, retaining support for explicitly chosen models. */
class AnthropicMessagesClient(
    private val config: PlannerConfig,
    private val http: HttpTransport = HttpTransport(),
    private val observer: PlannerRequestObserver? = null
) : PlannerClient {
    override val describe: String get() = "anthropic/${config.resolvedModel}"

    override fun complete(instructions: String, input: String, schemaName: String, schema: JsonObject, maxOutputTokens: Int): String {
        require(maxOutputTokens > 0) { "Output token limit must be positive" }
        val system = instructions + "\n\nRespond with exactly one JSON object matching this JSON schema and nothing else:\n" + schema.toString()
        val body = JsonObject()
            .put("model", config.resolvedModel)
            .put("max_tokens", maxOutputTokens)
            .put("system", system)
            .put("messages", JsonArray().add(JsonObject().put("role", "user").put("content", input)))
        val json = request(config, http, observer, body, maxOutputTokens,
            mapOf("x-api-key" to config.apiKey, "anthropic-version" to "2023-06-01"))
        reportUsage(config, observer, json, UsageShape.ANTHROPIC)
        if (json.optString("stop_reason") !in setOf("", "end_turn", "stop_sequence")) throw IOException("Planner response did not complete")
        val text = json.optArray("content")?.objects()?.filter { it.optString("type") == "text" }
            ?.joinToString("") { it.optString("text") }.orEmpty()
        val start = text.indexOf('{'); val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) throw IOException("No JSON object in planner output")
        return validateJsonObject(text.substring(start, end + 1))
    }
}

private fun request(
    config: PlannerConfig, http: HttpTransport, observer: PlannerRequestObserver?, body: JsonObject,
    maxOutputTokens: Int, headers: Map<String, String>
): JsonObject {
    val requestBody = body.toString()
    // This sits outside the transport error handler: an allowance rejection is not a network failure.
    observer?.beforeRequest(config.provider, config.resolvedModel, maxOutputTokens)
    val response = try {
        http.postJson(config.resolvedEndpoint, requestBody, headers, config.connectTimeoutMs, config.readTimeoutMs)
    } catch (error: PlannerHttpException) {
        throw PlannerHttpException(error.code)
    } catch (_: IOException) {
        throw IOException("Planner transport failed")
    }
    return try { Json.parseObject(response) } catch (_: RuntimeException) { throw IOException("Invalid planner response") }
}

private fun validateJsonObject(text: String): String {
    if (text.isBlank()) throw IOException("Empty planner output")
    try { Json.parseObject(text) } catch (_: RuntimeException) { throw IOException("Invalid planner JSON output") }
    return text
}

private enum class UsageShape { RESPONSES, CHAT, ANTHROPIC }

private fun reportUsage(config: PlannerConfig, observer: PlannerRequestObserver?, response: JsonObject, shape: UsageShape) {
    if (observer == null) return
    val usage = response.optObject("usage") ?: return
    fun count(json: JsonObject?, key: String): Long {
        val number = json?.get(key)?.asDoubleOrNull() ?: return 0
        return if (number.isFinite() && number >= 0) number.toLong() else 0
    }
    val input: Long
    val output: Long
    val cached: Long
    val reasoning: Long
    when (shape) {
        UsageShape.RESPONSES -> {
            input = count(usage, "input_tokens")
            output = count(usage, "output_tokens")
            cached = count(usage.optObject("input_tokens_details"), "cached_tokens")
            reasoning = count(usage.optObject("output_tokens_details"), "reasoning_tokens")
        }
        UsageShape.CHAT -> {
            input = count(usage, "prompt_tokens")
            output = count(usage, "completion_tokens")
            cached = count(usage.optObject("prompt_tokens_details"), "cached_tokens")
            reasoning = count(usage.optObject("completion_tokens_details"), "reasoning_tokens")
        }
        UsageShape.ANTHROPIC -> {
            cached = count(usage, "cache_read_input_tokens")
            input = listOf(count(usage, "input_tokens"), count(usage, "cache_creation_input_tokens"), cached)
                .fold(0L) { total, value -> if (Long.MAX_VALUE - total < value) Long.MAX_VALUE else total + value }
            output = count(usage, "output_tokens")
            reasoning = 0 // Anthropic does not break out reasoning tokens in this usage shape.
        }
    }
    // Use the configured identifier, not untrusted free text returned in response metadata.
    observer.onUsage(PlannerUsage(config.provider, config.resolvedModel, input, output, cached.coerceAtMost(input), reasoning.coerceAtMost(output)))
}

/** Replaceable HTTP boundary. No redirects or automatic application-level retries. */
open class HttpTransport {
    open fun postJson(url: String, body: String, headers: Map<String, String>, connectTimeoutMs: Int, readTimeoutMs: Int): String {
        val connection = try { URL(url).openConnection() as? HttpURLConnection }
            catch (_: Exception) { throw IOException("Invalid planner endpoint") }
            ?: throw IOException("Invalid planner endpoint")
        try {
            val bytes = body.toByteArray(Charsets.UTF_8)
            connection.apply {
                instanceFollowRedirects = false
                requestMethod = "POST"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                doOutput = true
                // Streaming prevents HttpURLConnection from replaying a buffered POST on redirect/auth retry.
                setFixedLengthStreamingMode(bytes.size)
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                headers.forEach { (key, value) -> setRequestProperty(key, value) }
            }
            connection.outputStream.use { it.write(bytes) }
            val code = connection.responseCode
            if (code !in 200..299) throw PlannerHttpException(code)
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (error: PlannerHttpException) {
            throw error
        } catch (_: IOException) {
            throw IOException("Planner transport failed")
        } catch (_: IllegalArgumentException) {
            throw IOException("Invalid planner transport configuration")
        } finally {
            connection.disconnect()
        }
    }
}

object PlannerClients {
    fun create(config: PlannerConfig, observer: PlannerRequestObserver? = null, http: HttpTransport = HttpTransport()): PlannerClient = when (config.provider) {
        PlannerProvider.OPENAI -> OpenAiResponsesClient(config, http, observer)
        PlannerProvider.ANTHROPIC -> AnthropicMessagesClient(config, http, observer)
        PlannerProvider.OPENAI_COMPATIBLE, PlannerProvider.GROQ, PlannerProvider.GEMINI -> OpenAiChatCompletionsClient(config, http, observer)
    }
}
