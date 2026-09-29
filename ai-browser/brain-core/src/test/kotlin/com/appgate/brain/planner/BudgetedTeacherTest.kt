package com.appgate.brain.planner

import com.appgate.brain.json.JsonObject
import com.appgate.brain.memory.*
import org.junit.Assert.*
import org.junit.Test

class BudgetedTeacherTest {
    @Test fun localOnlyBlocksRealTransportEvenWithConfiguredClient() {
        var calls = 0
        val http = object : HttpTransport() {
            override fun postJson(url: String, body: String, headers: Map<String,String>, connectTimeoutMs: Int, readTimeoutMs: Int): String {
                calls++; return "{}"
            }
        }
        val budget = TeacherBudget(InMemoryStorage())
        val client = PlannerClients.create(PlannerConfig(apiKey = "fixture"), BudgetedTeacher(budget, { false }), http)
        assertTrue(runCatching { client.complete("test", "{}", "test", JsonObject()) }.exceptionOrNull() is TeacherBudgetExceeded)
        assertEquals(0, calls)
        assertEquals(0, budget.snapshot().requests24h)
    }

    @Test fun failuresReserveAllowanceAndUsageIsRecordedForIncompleteOutput() {
        val store = InMemoryStorage()
        val budget = TeacherBudget(store) { 100_000L }
        val http = object : HttpTransport() {
            override fun postJson(url: String, body: String, headers: Map<String,String>, connectTimeoutMs: Int, readTimeoutMs: Int) =
                """{"status":"incomplete","usage":{"input_tokens":300,"output_tokens":100}}"""
        }
        val client = PlannerClients.create(PlannerConfig(apiKey = "fixture"), BudgetedTeacher(budget, { true }), http)
        assertTrue(runCatching { client.complete("test", "{}", "test", JsonObject()) }.isFailure)
        val restored = TeacherBudget(store) { 100_000L }.snapshot()
        assertEquals(1, restored.requests24h)
        assertEquals(300L, restored.inputTokens)
        assertEquals(100L, restored.outputTokens)
    }

    @Test fun telemetryFailureCannotPreventTransportOrDiscardReportedUsage() {
        var calls = 0
        var notifications = 0
        val budget = TeacherBudget(InMemoryStorage()) { 100_000L }
        val http = object : HttpTransport() {
            override fun postJson(url: String, body: String, headers: Map<String,String>, connectTimeoutMs: Int, readTimeoutMs: Int): String {
                calls++
                return """{"output_text":"{}","usage":{"input_tokens":300,"output_tokens":100}}"""
            }
        }
        val observer = BudgetedTeacher(budget, { true }) {
            notifications++
            throw IllegalStateException("display callback failed")
        }
        val client = PlannerClients.create(PlannerConfig(apiKey = "fixture"), observer, http)
        assertEquals("{}", client.complete("test", "{}", "test", JsonObject()))
        assertEquals(1, calls)
        assertEquals(2, notifications)
        val usage = budget.snapshot()
        assertEquals(1, usage.requests24h)
        assertEquals(1, usage.reportedRequests)
        assertEquals(300L, usage.inputTokens)
        assertEquals(100L, usage.outputTokens)
    }

}
