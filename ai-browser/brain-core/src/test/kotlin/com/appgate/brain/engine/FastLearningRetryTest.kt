package com.appgate.brain.engine

import com.appgate.brain.memory.*
import com.appgate.brain.json.*
import com.appgate.brain.profile.SiteProfile
import com.appgate.brain.model.*
import com.appgate.brain.perception.SpsParser
import com.appgate.brain.test.FakeSite
import org.junit.Assert.*
import org.junit.Test

class FastLearningRetryTest {
    private val now = 1_800_000_000_000L
    private fun results() = SpsParser().parse(FakeSite().apply {
        dialogShown = false; navigate("https://fake.market/search?q=Ford", 1000)
    }.observe(1000), now).copy(settle = Settle.IDLE)

    @Test fun missingNextDoesNotSeekPastAnAvailableLoadMoreControl() {
        val page = results().copy(affordances = listOf(Affordance("more", Role.LOAD_MORE)), viewportHeight = 400, scrollHeight = 3000)
        assertNull(LearningOpportunities.target(page, "next_page"))
        assertEquals("load_more", LearningOpportunities.target(page, "load_more")?.skillId)
    }
    @Test fun missingLoadMoreDoesNotSeekPastARealNextButton() {
        val page = results().copy(affordances = listOf(Affordance("next", Role.PAGE_NEXT)), viewportHeight = 400, scrollHeight = 3000)
        assertNull(LearningOpportunities.target(page, "load_more"))
        assertEquals("next_page", LearningOpportunities.target(page, "next_page")?.skillId)
    }
    @Test fun closeScoreFloorDoesNotAdmitWeakAmbiguousOffsiteOrCommitControls() {
        val site = SiteModel("fake.market"); repeat(20) { site.recordFailure(PageType.RESULTS, Role.CLOSE, null, "old", now) }
        val close = Affordance("close", Role.CLOSE, tag = "button", roleScore = .7)
        for (controls in listOf(listOf(close.copy(roleScore = .5)), listOf(close, close.copy(id = "other")),
            listOf(close.copy(sameSite = false)), listOf(close.copy(effect = EffectClass.COMMIT_EXTERNAL)))) {
            val page = results().copy(dialogOpen = true, affordances = controls)
            assertTrue(StepGrounder(site).ground(Step(StepKind.DISMISS, Role.CLOSE), emptyMap(), page, emptySet()) is GroundingOutcome.Missing)
        }
    }

    @Test fun ordinaryLessonFailureRetriesInThirtySecondsEvenAfterManyFailures() {
        val site = SiteModel("fake.market"); Curriculum.ensure(site)
        val ledger = TaskLedger("retry", Goal("goal", GoalIntent.LEARN_SITE, "practice", "Ford", emptyList()), site.host, "https://fake.market/")
        ledger.lesson = "next_page"; ledger.attemptedSkills += "next_page"; ledger.status = TaskStatus.PARTIAL
        repeat(8) {
            Curriculum.recordAttempt(site, ledger, now)
            assertEquals(now + 30_000, site.curriculum.single { it.id == "next_page" }.retryAt)
        }
    }
    @Test fun identicalFailedProcedureRemainsBlockedUntilThirtySecondRetry() {
        val site = SiteModel("fake.market"); val key = FailedStrategies.key(results(), "search")
        repeat(2) { FailedStrategies.record(site, key, false, now) }
        assertFalse(FailedStrategies.allowed(site, key, now + 29_999))
        assertTrue(FailedStrategies.allowed(site, key, now + 30_000))
    }
    @Test fun absentNextLessonDoesNotPreventVerifiedLoadMorePractice() {
        val fake = FakeSite().apply { dialogShown = false; navigate("https://fake.market/search?q=Ford", 1000) }
        val memory = Memory(InMemoryStorage()) { now }; val site = memory.site(fake.host); Curriculum.ensure(site)
        site.curriculum.filter { it.id != "next_page" }.forEach { it.completedAt = now - 1 }
        site.curriculum.single { it.id == "load_more" }.completedAt = now - 1000
        val renderer = object : Renderer by fake {
            override fun observe(timeoutMs: Long): String {
                val raw = Json.parseObject(fake.observe(timeoutMs))
                raw.optArray("elements")?.objects()?.filter { it.optString("name") == "Next page" }?.forEach {
                    it.put("name", "Load more").put("rel", "")
                }
                return raw.toString()
            }
        }
        val outcomes = mutableListOf<TaskLedger>()
        val events = object : EngineEvents { override fun finished(ledger: TaskLedger, result: TaskResult?) { outcomes += ledger } }
        val engine = BrainEngine(renderer, memory, { null }, events, EngineConfig(pacingOverrideMs = 0, ambiguousRecheckMs = 0)) { now }
        val profile = SiteProfile("fake", "Fake", listOf(fake.host), "https://fake.market/", searchUrl = "https://fake.market/search?q={q}", trainingQueries = listOf("Ford"))
        LearningSession(engine, memory, events, listOf(profile), clock = { now }).run(maxSites = 1)
        assertTrue("available verified capability should be rehearsed", outcomes.any { "load_more" in it.successfulSkills })
        assertEquals("one rehearsal yields to other sites", 1, outcomes.size)
        assertFalse(site.curriculum.single { it.id == "next_page" }.done)
        assertEquals(10, site.curriculum.count { it.done })
    }

    @Test fun completedSiteFailedReviewRetainsDailyHold() {
        val fake = FakeSite().apply { dialogShown = false }
        val memory = Memory(InMemoryStorage()) { now }; val site = memory.site(fake.host); Curriculum.ensure(site)
        site.curriculum.forEach { it.completedAt = now - 24 * 60 * 60_000 }
        val events = object : EngineEvents {}
        val engine = BrainEngine(fake, memory, { null }, events, EngineConfig(pacingOverrideMs = 0, maxDecisionsWithoutProgress = 0)) { now }
        LearningSession(engine, memory, events, listOf(SiteProfile("fake", "Fake", listOf(fake.host), "https://fake.market/")), clock = { now }).run(maxSites = 1)
        assertEquals(now + 24 * 60 * 60_000, site.learningBlockedUntil)
    }

    @Test fun savedHourLongDelaysMigrateWithoutErasingLearning() {
        val site = SiteModel("fake.market"); Curriculum.ensure(site)
        site.curriculum.first().completedAt = now - 1
        site.curriculum.last().retryAt = now + 3_600_000
        site.learningBlockedUntil = now + 3_600_000
        Curriculum.migrateRetries(site, now)
        assertEquals(now + 30_000, site.learningBlockedUntil)
        assertEquals(now + 30_000, site.curriculum.last().retryAt)
        assertTrue(site.curriculum.first().done)
    }
    @Test fun retryMigrationPreservesHumanChallengeAndCompletedReviewHolds() {
        for (mode in 0..2) {
            val site = SiteModel("fake.market"); Curriculum.ensure(site)
            if (mode == 0) site.learningNeedsHuman = true
            if (mode == 1) site.challengesToday = 3
            if (mode == 2) site.curriculum.forEach { it.completedAt = now - 1 }
            site.learningBlockedUntil = now + 3_600_000
            Curriculum.migrateRetries(site, now)
            assertEquals(now + 3_600_000, site.learningBlockedUntil)
        }
    }
    @Test fun rehearsalNeverActsOnDialogBusyOrHumanPages() {
        val site = SiteModel("fake.market"); Curriculum.ensure(site)
        site.curriculum.filter { it.id != "next_page" }.forEach { it.completedAt = now - 1 }
        val page = results().copy(affordances = listOf(Affordance("more", Role.LOAD_MORE)))
        assertEquals("load_more", Curriculum.practiceLesson(site, now, page))
        for (unsafe in listOf(page.copy(dialogOpen = true), page.copy(settle = Settle.BUSY), page.copy(authWall = true)))
            assertEquals("", Curriculum.practiceLesson(site, now, unsafe))
    }

    @Test fun uniqueRecognizedDialogCloseRemainsUsableAfterOldFailures() {
        val site = SiteModel("fake.market"); repeat(20) { site.recordFailure(PageType.RESULTS, Role.CLOSE, null, "old", now) }
        val page = results().copy(dialogOpen = true, affordances = listOf(Affordance("close", Role.CLOSE, tag = "button", roleScore = .7)))
        assertTrue(StepGrounder(site).ground(Step(StepKind.DISMISS, Role.CLOSE), emptyMap(), page, emptySet()) is GroundingOutcome.Ready)
    }
}
