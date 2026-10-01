package com.appgate.brain.planner

import com.appgate.brain.json.*
import org.junit.Assert.*
import org.junit.Test

class TemporaryTeacherTest {
    private val now = 1_800_000_000_000L
    private fun apply(config: PlannerConfig, until: Long) = TemporaryTeacher.apply(config, until, now)
    @Test fun optedInTrialIsTemporaryAndDoesNotMutateSavedConfig() {
        val saved = PlannerConfig(apiKey = "test-placeholder", model = "my-original-model")
        val boosted = apply(saved, now + 86_400_000L)
        assertEquals("gpt-5.4-2026-03-05", boosted.resolvedModel)
        assertEquals("low", boosted.reasoningEffort)
        assertEquals(90_000, boosted.readTimeoutMs)
        assertEquals("my-original-model", saved.model)
        assertEquals(saved, apply(saved, now))
        assertEquals(saved, apply(saved, 0L))
        assertEquals(saved, apply(saved, now + 86_400_001L))
    }
    @Test fun customEndpointsAndOtherProvidersAreNeverOverridden() {
        for (provider in PlannerProvider.values().filter { it != PlannerProvider.OPENAI }) {
            val config = PlannerConfig(provider, "test-placeholder", "chosen")
            assertEquals(config, apply(config, now + 1000L))
        }
        val custom = PlannerConfig(apiKey = "test-placeholder", endpoint = "https://custom.example/responses")
        assertEquals(custom, apply(custom, now + 1000L))
    }
    @Test fun reasoningTrialReservesAndSendsItsLargerTokenCeilingWithoutPaidRequests() {
        var captured = JsonObject(); var reserved = 0
        val http = object : HttpTransport() {
            override fun postJson(url: String, body: String, headers: Map<String, String>, connectTimeoutMs: Int, readTimeoutMs: Int): String {
                captured = Json.parseObject(body)
                return """{"output_text":"{}"}"""
            }
        }
        val observer = object : PlannerRequestObserver {
            override fun beforeRequest(provider: PlannerProvider, model: String, maxOutputTokens: Int) { reserved = maxOutputTokens }
            override fun onUsage(usage: PlannerUsage) {}
        }
        val config = apply(PlannerConfig(apiKey = "test-placeholder"), now + 1000L)
        OpenAiResponsesClient(config, http, observer).complete("system", "input", "test", JsonObject(), 1500)
        assertEquals(4000, captured.optInt("max_output_tokens"))
        assertEquals(4000, reserved)
        assertEquals("low", captured.optObject("reasoning")?.optString("effort"))
        assertFalse(captured.optBoolean("store", true))
    }
}
