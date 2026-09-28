package com.appgate.brain.planner

import com.appgate.brain.json.Json
import com.appgate.brain.json.JsonArray
import com.appgate.brain.json.JsonObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Transport to an external reasoning model. The brain sends only redacted, structured
 * summaries and receives JSON conforming to a schema. Transport-level concerns (keys,
 * model names, retries, fallback models) live here; reasoning lives in [Planner].
 */
interface PlannerClient {
    /** Returns the model's JSON object text for [schema]. Throws on transport failure. */
    fun complete(instructions: String, input: String, schemaName: String, schema: JsonObject, maxOutputTokens: Int = 1200): String
    val describe: String
}

class PlannerHttpException(val code: Int, message: String) : IOException(message)

enum class PlannerProvider { OPENAI, ANTHROPIC, OPENAI_COMPATIBLE }

data class PlannerConfig(
    val provider: PlannerProvider = PlannerProvider.OPENAI,
    val apiKey: String,
    val model: String = "",
    val endpoint: String = "",
    val reasoningEffort: String = "low",
    val connectTimeoutMs: Int = 15_000,
    val readTimeoutMs: Int = 45_000
) {
    val resolvedModel: String get() = model.ifBlank {
        when (provider) {
            PlannerProvider.OPENAI, PlannerProvider.OPENAI_COMPATIBLE -> "gpt-5.5"
            PlannerProvider.ANTHROPIC -> "claude-sonnet-5"
        }
    }
    val resolvedEndpoint: String get() = endpoint.ifBlank {
        when (provider) {
            PlannerProvider.OPENAI, PlannerProvider.OPENAI_COMPATIBLE -> "https://api.openai.com/v1/responses"
            PlannerProvider.ANTHROPIC -> "https://api.anthropic.com/v1/messages"
        }
    }
}

/** OpenAI Responses API with strict JSON-schema output; falls back through a small model list on 404/400 model errors. */
class OpenAiResponsesClient(private val config: PlannerConfig, private val http: HttpTransport = HttpTransport()) : PlannerClient {
    private val fallbackModels = listOf("gpt-5.5", "gpt-5", "gpt-5-mini", "gpt-4.1")
    @Volatile private var activeModel: String = config.resolvedModel

    override val describe: String get() = "openai/${activeModel}"

    override fun complete(instructions: String, input: String, schemaName: String, schema: JsonObject, maxOutputTokens: Int): String {
        val candidates = (listOf(activeModel) + fallbackModels).distinct()
        var lastError: Exception? = null
        for (model in candidates) {
            val body = JsonObject()
                .put("model", model)
                .put("store", false)
                .put("instructions", instructions)
                .put("input", input)
                .put("max_output_tokens", maxOutputTokens)
                .put("text", JsonObject().put("format", JsonObject()
                    .put("type", "json_schema").put("name", schemaName).put("strict", true).put("schema", schema)))
            if (config.reasoningEffort.isNotBlank() && !model.startsWith("gpt-4")) body.put("reasoning", JsonObject().put("effort", config.reasoningEffort))
            try {
                val response = http.postJson(config.resolvedEndpoint, body.toString(), mapOf("Authorization" to "Bearer ${config.apiKey}"), config.connectTimeoutMs, config.readTimeoutMs)
                val json = Json.parseObject(response)
                val text = json.optStringOrNull("output_text") ?: extractOutputText(json)
                if (text.isBlank()) throw IOException("empty model output")
                activeModel = model
                return text
            } catch (e: PlannerHttpException) {
                lastError = e
                val modelProblem = (e.code == 404 || e.code == 400) && (e.message?.contains("model", true) == true)
                if (!modelProblem) throw e
            }
        }
        throw lastError ?: IOException("no model available")
    }

    private fun extractOutputText(response: JsonObject): String {
        val output = response.optArray("output") ?: return ""
        for (item in output.objects()) {
            val content = item.optArray("content") ?: continue
            for (part in content.objects()) {
                if (part.optString("type") == "output_text") {
                    val t = part.optString("text")
                    if (t.isNotBlank()) return t
                }
            }
        }
        // refusal or error shapes
        return ""
    }
}

/** Anthropic Messages API; asks for a single JSON object and extracts it. */
class AnthropicMessagesClient(private val config: PlannerConfig, private val http: HttpTransport = HttpTransport()) : PlannerClient {
    override val describe: String get() = "anthropic/${config.resolvedModel}"

    override fun complete(instructions: String, input: String, schemaName: String, schema: JsonObject, maxOutputTokens: Int): String {
        val system = instructions + "\n\nRespond with exactly one JSON object matching this JSON schema and nothing else:\n" + schema.toString()
        val body = JsonObject()
            .put("model", config.resolvedModel)
            .put("max_tokens", maxOutputTokens)
            .put("system", system)
            .put("messages", JsonArray().add(JsonObject().put("role", "user").put("content", input)))
        val response = http.postJson(config.resolvedEndpoint, body.toString(),
            mapOf("x-api-key" to config.apiKey, "anthropic-version" to "2023-06-01"), config.connectTimeoutMs, config.readTimeoutMs)
        val json = Json.parseObject(response)
        val text = json.optArray("content")?.objects()?.firstOrNull { it.optString("type") == "text" }?.optString("text").orEmpty()
        return extractJsonObject(text)
    }

    private fun extractJsonObject(text: String): String {
        val start = text.indexOf('{'); val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) throw IOException("no JSON object in model output")
        return text.substring(start, end + 1)
    }
}

class HttpTransport {
    fun postJson(url: String, body: String, headers: Map<String, String>, connectTimeoutMs: Int, readTimeoutMs: Int): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw PlannerHttpException(code, "HTTP $code: ${text.take(600)}")
            return text
        } finally {
            connection.disconnect()
        }
    }
}

object PlannerClients {
    fun create(config: PlannerConfig): PlannerClient = when (config.provider) {
        PlannerProvider.OPENAI, PlannerProvider.OPENAI_COMPATIBLE -> OpenAiResponsesClient(config)
        PlannerProvider.ANTHROPIC -> AnthropicMessagesClient(config)
    }
}
