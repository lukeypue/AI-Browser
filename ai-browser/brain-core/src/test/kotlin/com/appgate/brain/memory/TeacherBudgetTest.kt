package com.appgate.brain.memory

import org.junit.Assert.*
import org.junit.Test

class TeacherBudgetTest {
    @Test fun hourlyReservationsSurviveRestartAndFailedRequests() {
        val store = InMemoryStorage()
        var now = 100_000L
        val budget = TeacherBudget(store) { now }
        repeat(30) { budget.reserve("OPENAI", "test-model", 1200) }
        assertEquals(30, TeacherBudget(store) { now }.snapshot().requestsHour)
        assertFalse(TeacherBudget(store) { now }.snapshot().allowed)
        assertTrue(runCatching { TeacherBudget(store) { now }.reserve("OPENAI", "test-model", 1200) }.exceptionOrNull() is TeacherBudgetExceeded)
        now += 3_600_000L
        assertTrue(budget.snapshot().allowed)
        assertEquals(30, budget.snapshot().requests24h)
    }

    @Test fun oneHundredTwentyRequestsBlockAcrossFreshInstancesAndClockRollback() {
        val store = InMemoryStorage()
        var now = 100_000L
        repeat(4) {
            repeat(30) { TeacherBudget(store) { now }.reserve("OPENAI", "test-model", 1200) }
            now += 3_600_000L
        }
        val budget = TeacherBudget(store) { now }
        assertEquals(120, budget.snapshot().requests24h)
        assertFalse(budget.snapshot().allowed)
        now = 1L
        assertFalse(TeacherBudget(store) { now }.snapshot().allowed)
        now = 86_500_001L
        assertTrue(budget.snapshot().allowed)
    }

    @Test fun reportedTokensAttachToReservationWithoutPageData() {
        val store = InMemoryStorage()
        val budget = TeacherBudget(store) { 100_000L }
        val id = budget.reserve("GROQ", "openai/gpt-oss-20b", 1200)
        budget.recordUsage(id, 340L, 50L, 100L, 12L)
        budget.recordUsage(id, 340L, 50L, 100L, 12L)
        val snapshot = TeacherBudget(store) { 100_000L }.snapshot()
        assertEquals(340L, snapshot.inputTokens)
        assertEquals(50L, snapshot.outputTokens)
        assertEquals(1, snapshot.reportedRequests)
        assertEquals("GROQ/openai/gpt-oss-20b", snapshot.lastModel)
        assertFalse(store.snapshot().values.joinToString().contains("query"))
    }

    @Test fun corruptBudgetFailsClosedAndRejectedReservationsDoNotCount() {
        val store = InMemoryStorage()
        store.write("teacher/usage", "not-json")
        assertFalse(TeacherBudget(store) { 100_000L }.snapshot().allowed)
        assertTrue(runCatching { TeacherBudget(store) { 100_000L }.reserve("OPENAI", "model", 1200) }.exceptionOrNull() is TeacherBudgetExceeded)
    }
    @Test fun unrestrictedReservationsBypassBothCapsButKeepDurableAccounting() {
        val store = InMemoryStorage()
        val budget = TeacherBudget(store) { 100_000L }
        repeat(150) { budget.reserve("OPENAI", "test-model", 1200, unrestricted = true) }
        val usage = TeacherBudget(store) { 100_000L }.snapshot(unrestricted = true)
        assertEquals(150, usage.requests24h)
        assertEquals(150, usage.requestsHour)
        assertTrue(usage.allowed)
        assertEquals(0L, usage.nextAllowedAt)
        assertFalse(budget.snapshot().allowed)
        assertTrue(runCatching { budget.reserve("OPENAI", "model", 1200) }.exceptionOrNull() is TeacherBudgetExceeded)
    }

    @Test fun unrestrictedCannotDiscardOrIgnoreCorruptUsageStorage() {
        val store = InMemoryStorage()
        store.write("teacher/usage", "not-json")
        val budget = TeacherBudget(store) { 100_000L }
        assertFalse(budget.snapshot(unrestricted = true).allowed)
        assertTrue(runCatching { budget.reserve("OPENAI", "model", 1200, unrestricted = true) }.exceptionOrNull() is TeacherBudgetExceeded)
    }

}
