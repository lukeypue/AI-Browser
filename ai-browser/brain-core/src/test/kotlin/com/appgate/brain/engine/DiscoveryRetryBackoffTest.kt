package com.appgate.brain.engine

import com.appgate.brain.memory.*
import com.appgate.brain.model.*
import com.appgate.brain.perception.SpsParser
import com.appgate.brain.profile.SiteProfile
import com.appgate.brain.test.FakeSite
import org.junit.Assert.*
import org.junit.Test

class DiscoveryRetryBackoffTest {
    private val initial = 1_800_000_000_000L
    private val profile = SiteProfile("fake", "Fake", listOf("fake.market"), "https://fake.market/")
    private fun top() = SpsParser().parse(FakeSite().apply {
        dialogShown = false; navigate("https://fake.market/search?q=Ford", 1000)
    }.observe(1000), initial).copy(affordances = emptyList(), viewportHeight = 400, scrollHeight = 2000, scrollY = 0, settle = Settle.IDLE)
    private fun attempt(site: SiteModel, now: Long, status: TaskStatus = TaskStatus.PARTIAL, verified: Boolean = true) {
        val ledger = TaskLedger("discovery", Curriculum.nextGoal(site, profile, 0, top(), "next_page"), site.host, top().url).apply {
            lesson = "next_page"; this.status = status; attemptedSkills += "seek_pagination"
            if (verified) successfulSkills += "seek_pagination"
        }
        Curriculum.recordAttempt(site, ledger, now)
    }
    private fun site() = SiteModel("fake.market").apply {
        Curriculum.ensure(this)
        curriculum.filter { it.id !in setOf("next_page", "load_more") }.forEach { it.completedAt = initial - 1 }
    }

    @Test fun repeatedIncompleteDiscoveryWaitsLongerAcrossMemoryReloadAndRetryMigration() {
        var site = site(); var now = initial
        val delays = listOf(30_000L, 60_000L, 120_000L, 240_000L, 300_000L, 300_000L)
        for (delay in delays) {
            Curriculum.observe(site, top(), now)
            assertTrue(Curriculum.nextLesson(site, now, top()).isNotBlank())
            attempt(site, now)
            site = SiteModel.fromJson(site.toJson())
            Curriculum.migrateRetries(site, now + 30_000L)
            val changed = top().copy(title = "Unrelated listing churn", affordances = listOf(Affordance("unrelated", Role.TAB)))
            Curriculum.observe(site, changed, now + delay - 1)
            assertEquals("both discovery lessons remain held until their shared retry", "", Curriculum.nextLesson(site, now + delay - 1, changed))
            assertTrue(Curriculum.nextLesson(site, now + delay, changed).isNotBlank())
            assertTrue(site.curriculum.filter { it.id in setOf("next_page", "load_more") }.none { it.done })
            now += delay
        }
    }

    @Test fun realPaginationControlsReopenImmediatelyDuringDiscoveryDelay() {
        for ((role, lesson) in listOf(Role.PAGE_NEXT to "next_page", Role.LOAD_MORE to "load_more")) {
            val site = site(); attempt(site, initial); attempt(site, initial + 30_000L)
            val controls = top().copy(affordances = listOf(Affordance("live", role, tag = "button")))
            Curriculum.observe(site, controls, initial + 30_001L)
            assertEquals(lesson, Curriculum.nextLesson(site, initial + 30_001L, controls))
            assertFalse(site.curriculum.single { it.id == lesson }.done)
        }
    }

    @Test fun humanBudgetAndUnverifiedDiscoveryDoNotIncreaseDiscoveryBackoff() {
        for ((status, verified) in listOf(TaskStatus.NEED_HUMAN to true, TaskStatus.BUDGET_EXHAUSTED to true, TaskStatus.PAUSED to true, TaskStatus.PARTIAL to false)) {
            val site = site()
            attempt(site, initial, status, verified)
            attempt(site, initial + 30_000L)
            Curriculum.observe(site, top(), initial + 60_000L)
            assertTrue("excluded outcomes must not lengthen the first retry", Curriculum.nextLesson(site, initial + 60_000L, top()).isNotBlank())
        }
    }
    @Test fun busyFooterDoesNotEraseUnfinishedDiscoveryHistory() {
        val site = site(); attempt(site, initial); attempt(site, initial + 30_000L)
        val footer = top().copy(scrollY = 1600, settle = Settle.BUSY,
            affordances = listOf(Affordance("late", Role.PAGE_NEXT, tag = "button")))
        Curriculum.observe(site, footer, initial + 30_001L)
        val restored = SiteModel.fromJson(site.toJson())
        Curriculum.migrateRetries(restored, initial + 60_000L)
        Curriculum.observe(restored, top(), initial + 60_000L)
        assertEquals("", Curriculum.nextLesson(restored, initial + 60_000L, top()))
        assertEquals("pagination_discovery_cooldown", Curriculum.unavailableReason(restored, initial + 60_000L, top()))
        assertTrue(SemanticDiagnostics.learningRecheck(restored, top(), initial + 60_000L).optArray("pending_states")!!.objects().all { it.optBoolean("cooling") })
        assertTrue(restored.strategyFailures.isEmpty())
    }

    @Test fun unsafeOrUnrelatedControlsCannotClearTheDiscoveryDelay() {
        val site = site(); attempt(site, initial); attempt(site, initial + 30_000L)
        for (control in listOf(Affordance("disabled", Role.PAGE_NEXT, enabled = false),
            Affordance("offsite", Role.LOAD_MORE, sameSite = false),
            Affordance("commit", Role.LOAD_MORE, effect = EffectClass.COMMIT_EXTERNAL))) {
            val page = top().copy(affordances = listOf(control))
            Curriculum.observe(site, page, initial + 60_000L)
            assertEquals("", Curriculum.nextLesson(site, initial + 60_000L, page))
        }
    }

    @Test fun attemptingActualPaginationDoesNotAddDiscoveryDelay() {
        val site = site()
        repeat(2) { index ->
            val at = initial + index * 30_000L
            val ledger = TaskLedger("actual", Curriculum.nextGoal(site, profile, 0, top(), "next_page"), site.host, top().url).apply {
                lesson = "next_page"; status = TaskStatus.PARTIAL
                attemptedSkills += listOf("seek_pagination", "next_page"); successfulSkills += "seek_pagination"
            }
            Curriculum.recordAttempt(site, ledger, at)
        }
        Curriculum.observe(site, top(), initial + 60_000L)
        assertTrue(Curriculum.nextLesson(site, initial + 60_000L, top()).isNotBlank())
    }

}
