package com.appgate.brain.planner

import com.appgate.brain.json.Json
import com.appgate.brain.json.JsonObject
import com.sun.net.httpserver.HttpServer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

class PlannerClientTest {
    private val schema = Json.parseObject("""{"type":"object","properties":{"action":{"type":"string"}},"required":["action"],"additionalProperties":false}""")

    @Test fun blankModelUsesPinnedEconomicalModelOnWire() = withServer { server, url ->
        var request = JsonObject()
        server.createContext("/plan") { exchange ->
            request = Json.parseObject(exchange.requestBody.bufferedReader().readText())
            respond(exchange, 200, """{"output_text":"{\"action\":\"wait\"}"}""")
        }
        val result = OpenAiResponsesClient(PlannerConfig(apiKey = "test-placeholder", endpoint = "$url/plan"))
            .complete("system", "input", "plan", schema)
        assertEquals("wait", Json.parseObject(result).optString("action"))
        assertEquals("gpt-5.4-mini-2026-03-17", request.optString("model"))
        assertEquals("none", request.optObject("reasoning")?.optString("effort"))
        assertFalse(request.optBoolean("store", true))
        assertTrue(request.optObject("text")?.optObject("format")?.optBoolean("strict") == true)
    }

    @Test fun explicitModelFailureDoesNotSwitchOrRetry() = withServer { server, url ->
        val models = CopyOnWriteArrayList<String>()
        server.createContext("/plan") { exchange ->
            models += Json.parseObject(exchange.requestBody.bufferedReader().readText()).optString("model")
            respond(exchange, 400, "model not supported; fixture-private-response")
        }
        val client = OpenAiResponsesClient(PlannerConfig(apiKey = "test-placeholder", model = "chosen-custom", endpoint = "$url/plan"))
        val error = expectIo { client.complete("system", "input", "plan", schema) }
        assertEquals(listOf("chosen-custom"), models.toList())
        assertEquals("openai/chosen-custom", client.describe)
        assertFalse(error.message.orEmpty().contains("fixture-private-response"))
    }

    @Test fun compatibleProviderUsesChatCompletionsRequestAndResult() = withServer { server, url ->
        var request = JsonObject()
        server.createContext("/chat") { exchange ->
            request = Json.parseObject(exchange.requestBody.bufferedReader().readText())
            respond(exchange, 200, """{"choices":[{"index":0,"message":{"role":"assistant","content":"{\"action\":\"wait\"}"},"finish_reason":"stop"}]}""")
        }
        val client = PlannerClients.create(PlannerConfig(PlannerProvider.OPENAI_COMPATIBLE, "test-placeholder", "custom-small", "$url/chat"))
        assertEquals("wait", Json.parseObject(client.complete("system", "input", "plan", schema)).optString("action"))
        assertEquals("system", request.optArray("messages")?.objects()?.first()?.optString("content"))
        assertEquals("json_schema", request.optObject("response_format")?.optString("type"))
        assertTrue(request.optObject("response_format")?.optObject("json_schema")?.optBoolean("strict") == true)
        assertFalse(request.has("input"))
        assertFalse(request.has("reasoning_effort"))
    }

    @Test fun httpErrorsNeverIncludeProviderBody() = withServer { server, url ->
        server.createContext("/error") { exchange -> respond(exchange, 401, "fixture-sensitive-body") }
        val error = expectIo { HttpTransport().postJson("$url/error", "{}", emptyMap(), 1000, 1000) }
        assertEquals(401, (error as PlannerHttpException).code)
        assertFalse(error.message.orEmpty().contains("fixture-sensitive-body"))
    }

    @Test fun redirectsAreRejectedBeforeAuthorizationCanReachAnotherPath() = withServer { server, url ->
        var sinkCalls = 0
        server.createContext("/redirect") { exchange ->
            exchange.responseHeaders.add("Location", "$url/sink")
            respond(exchange, 307, "redirect-body")
        }
        server.createContext("/sink") { exchange -> sinkCalls++; respond(exchange, 200, "{}") }
        val error = expectIo { HttpTransport().postJson("$url/redirect", "{}", mapOf("Authorization" to "Bearer test-placeholder"), 1000, 1000) }
        assertEquals(307, (error as PlannerHttpException).code)
        assertEquals(0, sinkCalls)
    }

    @Test fun responseUsageIsReportedBeforeRejectingTruncatedOutput() {
        val transport = FakeTransport("""{"model":"gpt-5.4-mini-2026-03-17","status":"incomplete","output_text":"{\"action\":\"wait\"}","usage":{"input_tokens":123,"output_tokens":45,"input_tokens_details":{"cached_tokens":100},"output_tokens_details":{"reasoning_tokens":20}}}""")
        val observer = RecordingObserver()
        val client = PlannerClients.create(PlannerConfig(apiKey = "test-placeholder"), observer, transport)
        expectIo { client.complete("system", "input", "plan", schema, 777) }
        assertEquals(listOf("before", "usage"), observer.events)
        assertEquals(777, observer.maxOutputTokens)
        assertEquals(PlannerUsage(PlannerProvider.OPENAI, "gpt-5.4-mini-2026-03-17", 123, 45, 100, 20), observer.usage.single())
        assertEquals(1, transport.requests.size)
    }

    @Test fun priorExplicitOpenAiModelsDoNotReceiveUnsupportedAutomaticReasoning() {
        for (model in listOf("gpt-5", "gpt-5-mini", "o3")) {
            val transport = FakeTransport("""{"output_text":"{\"action\":\"wait\"}"}""")
            val client = PlannerClients.create(PlannerConfig(apiKey = "test-placeholder", model = model), http = transport)
            client.complete("system", "input", "plan", schema)
            val request = Json.parseObject(transport.requests.single().second)
            assertEquals(model, request.optString("model"))
            assertFalse("Unconfigured effort must not be sent to $model", request.has("reasoning"))
        }
    }

    @Test fun explicitlyConfiguredReasoningEffortRemainsUnchanged() {
        val transport = FakeTransport("""{"output_text":"{\"action\":\"wait\"}"}""")
        val client = PlannerClients.create(PlannerConfig(apiKey = "test-placeholder", model = "o3", reasoningEffort = "low"), http = transport)
        client.complete("system", "input", "plan", schema)
        val request = Json.parseObject(transport.requests.single().second)
        assertEquals("o3", request.optString("model"))
        assertEquals("low", request.optObject("reasoning")?.optString("effort"))
    }

    @Test fun groqPresetUsesStrictChatSchemaAndReportedTokens() {
        val transport = FakeTransport(chatResponse("stop", "\"action\":\"wait\""))
        val observer = RecordingObserver()
        val client = PlannerClients.create(PlannerConfig(PlannerProvider.GROQ, "test-placeholder"), observer, transport)
        assertEquals("wait", Json.parseObject(client.complete("system", "input", "plan", schema, 321)).optString("action"))
        val request = transport.requests.single()
        assertEquals("https://api.groq.com/openai/v1/chat/completions", request.first)
        val json = Json.parseObject(request.second)
        assertEquals("openai/gpt-oss-20b", json.optString("model"))
        assertEquals(321, json.optInt("max_completion_tokens"))
        assertFalse(json.optBoolean("stream", true))
        assertFalse(json.has("tools"))
        assertFalse(json.has("reasoning_effort"))
        assertTrue(json.optObject("response_format")?.optObject("json_schema")?.optBoolean("strict") == true)
        assertEquals(PlannerUsage(PlannerProvider.GROQ, "openai/gpt-oss-20b", 90, 12, 30, 4), observer.usage.single())
    }

    @Test fun geminiPresetUsesNewAccountModelAndCompatibleEndpoint() {
        val transport = FakeTransport(chatResponse("stop", "\"action\":\"wait\""))
        val client = PlannerClients.create(PlannerConfig(PlannerProvider.GEMINI, "test-placeholder"), http = transport)
        client.complete("system", "input", "plan", schema)
        val (url, body) = transport.requests.single()
        assertEquals("https://generativelanguage.googleapis.com/v1beta/openai/chat/completions", url)
        val json = Json.parseObject(body)
        assertEquals("gemini-3.5-flash-lite", json.optString("model"))
        assertEquals(1200, json.optInt("max_tokens"))
        assertFalse(json.has("reasoning_effort"))
    }

    @Test fun deniedBudgetNeverReachesTransportAndPropagatesUnchanged() {
        val denied = IllegalStateException("budget exhausted")
        val transport = FakeTransport("{}")
        val observer = object : PlannerRequestObserver {
            override fun beforeRequest(provider: PlannerProvider, model: String, maxOutputTokens: Int) { throw denied }
        }
        val client = PlannerClients.create(PlannerConfig(apiKey = "test-placeholder"), observer, transport)
        try { client.complete("system", "input", "plan", schema); fail("Expected gate failure") }
        catch (actual: IllegalStateException) { assertSame(denied, actual) }
        assertTrue(transport.requests.isEmpty())
    }

    @Test fun failedActualRequestIsGatedOnceAndNeverRetried() {
        val observer = RecordingObserver()
        val transport = object : HttpTransport() {
            var calls = 0
            override fun postJson(url: String, body: String, headers: Map<String, String>, connectTimeoutMs: Int, readTimeoutMs: Int): String {
                calls++
                throw PlannerHttpException(404, "model fixture-private-response")
            }
        }
        val client = PlannerClients.create(PlannerConfig(apiKey = "test-placeholder", model = "chosen-model"), observer, transport)
        val error = expectIo { client.complete("system", "input", "plan", schema) }
        assertEquals(1, transport.calls)
        assertEquals(listOf("before"), observer.events)
        assertEquals("HTTP 404", error.message)
    }

    @Test fun anthropicKeepsExplicitModelAndAccountsAllInputCategories() {
        val transport = FakeTransport("""{"id":"msg_fixture","type":"message","role":"assistant","model":"custom-haiku","content":[{"type":"text","text":"{\"action\":\"wait\"}"}],"stop_reason":"end_turn","usage":{"input_tokens":10,"cache_creation_input_tokens":20,"cache_read_input_tokens":30,"output_tokens":5}}""")
        val observer = RecordingObserver()
        val client = PlannerClients.create(PlannerConfig(PlannerProvider.ANTHROPIC, "test-placeholder", "custom-haiku"), observer, transport)
        assertEquals("wait", Json.parseObject(client.complete("system", "input", "plan", schema)).optString("action"))
        assertEquals("https://api.anthropic.com/v1/messages", transport.requests.single().first)
        assertEquals("custom-haiku", Json.parseObject(transport.requests.single().second).optString("model"))
        assertEquals(PlannerUsage(PlannerProvider.ANTHROPIC, "custom-haiku", 60, 5, 30, 0), observer.usage.single())
    }

    @Test fun chatRefusalAndTruncationCannotReturnPartialPlansButUsageRemains() {
        for (finish in listOf("length", "content_filter", "tool_calls")) {
            val observer = RecordingObserver()
            val transport = FakeTransport(chatResponse(finish, "\"action\":\"wait\""))
            val client = PlannerClients.create(PlannerConfig(PlannerProvider.OPENAI_COMPATIBLE, "test-placeholder"), observer, transport)
            expectIo { client.complete("system", "input", "plan", schema) }
            assertEquals(1, observer.usage.size)
        }
    }

    @Test fun malformedProviderJsonProducesContentFreeException() {
        val transport = FakeTransport("fixture-private-response")
        val observer = RecordingObserver()
        val client = PlannerClients.create(PlannerConfig(apiKey = "test-placeholder"), observer, transport)
        val error = expectIo { client.complete("system", "input", "plan", schema) }
        assertEquals("Invalid planner response", error.message)
        assertNull(error.cause)
        assertEquals(listOf("before"), observer.events)
    }

    @Test fun absentUsageDoesNotInventReportedZeroTokens() {
        val transport = FakeTransport("""{"output":[{"type":"message","content":[{"type":"output_text","text":"{\"action\":\"wait\"}"}]}]}""")
        val observer = RecordingObserver()
        val client = PlannerClients.create(PlannerConfig(apiKey = "test-placeholder"), observer, transport)
        assertEquals("wait", Json.parseObject(client.complete("system", "input", "plan", schema)).optString("action"))
        assertEquals(listOf("before"), observer.events)
    }

    private fun chatResponse(finish: String, objectFields: String): String = Json.obj(
        "choices" to listOf(Json.obj("index" to 0, "message" to Json.obj("role" to "assistant", "content" to "{$objectFields}"), "finish_reason" to finish)),
        "usage" to Json.obj("prompt_tokens" to 90, "completion_tokens" to 12, "total_tokens" to 102,
            "prompt_tokens_details" to Json.obj("cached_tokens" to 30), "completion_tokens_details" to Json.obj("reasoning_tokens" to 4))
    ).toString()

    private class FakeTransport(private val response: String) : HttpTransport() {
        val requests = mutableListOf<Pair<String, String>>()
        override fun postJson(url: String, body: String, headers: Map<String, String>, connectTimeoutMs: Int, readTimeoutMs: Int): String {
            requests += url to body
            return response
        }
    }

    private class RecordingObserver : PlannerRequestObserver {
        val events = mutableListOf<String>()
        val usage = mutableListOf<PlannerUsage>()
        var maxOutputTokens: Int = 0
        override fun beforeRequest(provider: PlannerProvider, model: String, maxOutputTokens: Int) {
            events += "before"
            this.maxOutputTokens = maxOutputTokens
        }
        override fun onUsage(usage: PlannerUsage) { events += "usage"; this.usage += usage }
    }

    private fun expectIo(block: () -> Unit): IOException {
        try { block(); fail("Expected IOException") } catch (error: IOException) { return error }
        throw AssertionError("Expected IOException")
    }

    private fun withServer(block: (HttpServer, String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        try { block(server, "http://127.0.0.1:${server.address.port}") } finally { server.stop(0) }
    }

    private fun respond(exchange: com.sun.net.httpserver.HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
