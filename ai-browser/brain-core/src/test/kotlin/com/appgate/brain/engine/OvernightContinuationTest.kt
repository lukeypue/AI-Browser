package com.appgate.brain.engine

import com.appgate.brain.goal.GoalParser
import com.appgate.brain.memory.*
import com.appgate.brain.model.*
import com.appgate.brain.profile.SiteProfile
import com.appgate.brain.test.FakeSite
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class OvernightContinuationTest {
    private val config = EngineConfig(pacingOverrideMs = 0, plannerCooldownMs = 0, ambiguousRecheckMs = 0, idleSleepMs = 0)
    private fun profile(host: String) = SiteProfile("fake", "Fake", listOf(host), "https://$host/", trainingQueries = listOf("Ford Expedition"))
    private fun basicLessons(site: SiteModel) = listOf("search", "constrain_numeric", "open_item", "next_page").forEach { Curriculum.markSkillVerified(site, it, System.currentTimeMillis()) }

    @Test fun coreCompletionStillSchedulesUnfinishedLessons() {
        val site = SiteModel("fake.market"); basicLessons(site)
        assertTrue(Curriculum.isComplete(site))
        assertTrue("unfinished lessons must remain eligible", Curriculum.reviewDue(site, System.currentTimeMillis()))
        assertEquals("load_more", Curriculum.nextLesson(site))
    }

    @Test fun coolingSiteResumesAutomaticallyWhenItsDelayExpires() {
        val fake = FakeSite().apply { dialogShown = false }; val memory = Memory(InMemoryStorage())
        memory.site(fake.host).learningBlockedUntil = System.currentTimeMillis() + 200
        val events = object : EngineEvents {}
        val session = LearningSession(BrainEngine(fake, memory, { null }, events, config), memory, events, listOf(profile(fake.host)), perSiteChunkMs = 100)
        session.run(maxSites = 2)
        assertTrue("the session must wait, then visit the cooled site", fake.log.any { it.startsWith("navigate ") })
    }

    @Test fun humanOnlyWaitingStaysActiveAndCanBeStopped() {
        val fake = FakeSite(); val memory = Memory(InMemoryStorage())
        memory.site(fake.host).learningNeedsHuman = true
        val events = object : EngineEvents {}
        val session = LearningSession(BrainEngine(fake, memory, { null }, events, config), memory, events, listOf(profile(fake.host)))
        val error = AtomicReference<Throwable>()
        val worker = Thread { try { session.run() } catch (t: Throwable) { error.set(t) } }.apply { isDaemon = true }
        worker.start()
        try {
            worker.join(250)
            assertTrue("waiting for a person is not completed overnight learning", worker.isAlive)
            assertTrue("the watchdog must be able to distinguish idle waiting", session.isWaiting)
            assertTrue(fake.log.isEmpty())
        } finally { session.stop(); worker.join(2000) }
        assertFalse("Stop must interrupt idle waiting promptly", worker.isAlive)
        assertNull(error.get())
        assertFalse(session.isWaiting)
    }

    @Test fun completedLessonsWaitForTheirScheduledReviewThenResume() {
        val fake = FakeSite().apply { dialogShown = false }; val memory = Memory(InMemoryStorage())
        val site = memory.site(fake.host); Curriculum.ensure(site)
        val reviewAt = System.currentTimeMillis() + 200
        site.curriculum.forEach { Curriculum.markSkillVerified(site, it.id, reviewAt - 24 * 60 * 60_000L) }
        val events = object : EngineEvents {}
        val session = LearningSession(BrainEngine(fake, memory, { null }, events, config), memory, events, listOf(profile(fake.host)), perSiteChunkMs = 100)
        session.run(maxSites = 2)
        assertTrue(site.lessonOrdinal > 0)
        assertTrue("resumed work gets a new watchdog baseline without inventing progress", session.activeSince >= reviewAt)
    }

    @Test fun explicitStartRechecksAnOldSiteTransitionHold() {
        val fake = FakeSite().apply { dialogShown = false }; val memory = Memory(InMemoryStorage())
        val site = memory.site(fake.host)
        site.learningNeedsHuman = true
        site.lastLearningStatus = "NEED_HUMAN: unexpected host"
        val events = object : EngineEvents {}
        LearningSession(BrainEngine(fake, memory, { null }, events, config), memory, events, listOf(profile(fake.host)), perSiteChunkMs = 100).run(maxSites = 1)
        assertTrue("recheck the destination through the normal page safety checks", site.lessonOrdinal > 0)
        assertFalse(site.learningNeedsHuman)
    }

    @Test fun explicitStartRechecksSignInButNeverActsOnAnActualAuthWall() {
        val fake = FakeSite().apply { loginWall = true }; val memory = Memory(InMemoryStorage())
        val site = memory.site(fake.host)
        site.learningNeedsHuman = true
        site.lastLearningStatus = "NEED_HUMAN: This site needs you to sign in."
        val events = object : EngineEvents {}
        LearningSession(BrainEngine(fake, memory, { null }, events, config), memory, events, listOf(profile(fake.host))).run(maxSites = 1)
        assertTrue(site.learningNeedsHuman)
        assertTrue("the current page must be checked", site.lessonOrdinal > 0)
        assertTrue(fake.log.none { it.startsWith("act ") })
    }

    @Test fun legacyDayLongCoreDeferralDoesNotHideRemainingLessons() {
        val fake = FakeSite().apply { dialogShown = false }; val memory = Memory(InMemoryStorage())
        val site = memory.site(fake.host); basicLessons(site)
        site.learningBlockedUntil = System.currentTimeMillis() + 24 * 60 * 60_000L
        val events = object : EngineEvents {}
        val session = LearningSession(BrainEngine(fake, memory, { null }, events, config), memory, events, listOf(profile(fake.host)), perSiteChunkMs = 100)
        session.run(maxSites = 1)
        assertTrue("the 7.1.0 core-only deferral must not suppress unfinished lessons", site.lessonOrdinal > 0)
    }

    @Test fun previousSiteDuringNavigationDoesNotBecomeAReviewHold() {
        val fake = FakeSite().apply { dialogShown = false }
        val old = FakeSite("previous.market").apply { dialogShown = false }
        var observations = 0
        val renderer = object : Renderer by fake {
            override fun currentUrl() = if (observations < 3) old.url else fake.url
            override fun observe(timeoutMs: Long) = if (++observations <= 2) old.observe(timeoutMs) else fake.observe(timeoutMs)
        }
        val memory = Memory(InMemoryStorage())
        val goal = GoalParser.parse("Ford Expedition", Budget(actions = 8, itemsInspected = 1, wallMs = 10000)).copy(constraints = emptyList())
        val out = BrainEngine(renderer, memory, { null }, object : EngineEvents {}, config)
            .runTask(TaskLedger("handoff", goal, fake.host, "https://${fake.host}/search?q=Ford+Expedition"))
        assertNotEquals(TaskStatus.NEED_HUMAN, out.status)
        assertTrue("the destination must actually be observed", observations >= 3)
        assertTrue(memory.site(fake.host).pageTypesSeen.isNotEmpty())
    }

    @Test fun navigationThatNeverLeavesOldSiteEndsBoundedlyWithoutLearningFromIt() {
        val old = FakeSite("previous.market").apply { dialogShown = false }
        val renderer = object : Renderer by old {
            override fun navigate(url: String, timeoutMs: Long) = RendererResult(true, "load queued")
        }
        val memory = Memory(InMemoryStorage())
        val out = BrainEngine(renderer, memory, { null }, object : EngineEvents {}, config)
            .runTask(TaskLedger("never-landed", GoalParser.parse("Ford"), "fake.market", "https://fake.market/"))
        assertEquals(TaskStatus.FAILED, out.status)
        assertTrue(memory.site("fake.market").pageTypesSeen.isEmpty())
        assertTrue(out.verdicts.isEmpty())
        assertTrue(out.decisions <= 12)
    }

    @Test fun redirectToAnUnrelatedThirdSiteStillRequiresHumanReview() {
        val foreign = FakeSite("unrelated.market").apply { dialogShown = false }
        val renderer = object : Renderer by foreign {
            override fun currentUrl() = "https://previous.market/"
            override fun navigate(url: String, timeoutMs: Long) = RendererResult(true)
        }
        val memory = Memory(InMemoryStorage())
        val out = BrainEngine(renderer, memory, { null }, object : EngineEvents {}, config)
            .runTask(TaskLedger("redirect", GoalParser.parse("Ford"), "fake.market", "https://fake.market/"))
        assertEquals(TaskStatus.NEED_HUMAN, out.status)
        assertTrue(memory.site("fake.market").pageTypesSeen.isEmpty())
    }

    @Test fun redirectToADifferentPageOnThePreviousSiteStillRequiresReview() {
        val foreign = FakeSite("previous.market").apply { url = "https://previous.market/login"; dialogShown = false }
        val renderer = object : Renderer by foreign {
            override fun currentUrl() = "https://previous.market/"
            override fun navigate(url: String, timeoutMs: Long) = RendererResult(true)
        }
        val memory = Memory(InMemoryStorage())
        val out = BrainEngine(renderer, memory, { null }, object : EngineEvents {}, config)
            .runTask(TaskLedger("redirect-prior-host", GoalParser.parse("Ford"), "fake.market", "https://fake.market/"))
        assertEquals(TaskStatus.NEED_HUMAN, out.status)
        assertTrue(memory.site("fake.market").pageTypesSeen.isEmpty())
    }
}
