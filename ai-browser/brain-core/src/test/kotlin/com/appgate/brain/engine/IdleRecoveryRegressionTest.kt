package com.appgate.brain.engine

import com.appgate.brain.json.*
import com.appgate.brain.memory.*
import com.appgate.brain.model.*
import com.appgate.brain.profile.SiteProfile
import com.appgate.brain.test.FakeSite
import org.junit.Assert.*
import org.junit.Test

class IdleRecoveryRegressionTest {
    private val initial = 1_800_000_000_000L
    private val config = EngineConfig(pacingOverrideMs = 0, plannerCooldownMs = 0, ambiguousRecheckMs = 0, idleSleepMs = 0)
    private fun profile(fake: FakeSite) = SiteProfile("fake", "Fake", listOf(fake.host), "https://${fake.host}/",
        searchUrl = "https://${fake.host}/search?q={q}", trainingQueries = listOf("Ford"))

    @Test fun failedDialogGetsOneSafeResetAtNextDueProbeWithoutLessonCredit() {
        val fake = FakeSite()
        val memory = Memory(InMemoryStorage()) { initial }
        val site = memory.site(fake.host)
        site.lastLearningStatus = "FAILED: recently failed approaches; retry after page change or cooldown"
        var navigations = 0
        val renderer = object : Renderer by fake {
            override fun navigate(url: String, timeoutMs: Long): RendererResult {
                navigations++; fake.dialogShown = false; return fake.navigate(url, timeoutMs)
            }
        }
        val diagnostics = mutableListOf<String>()
        val events = object : EngineEvents { override fun diagnostic(host: String, kind: String, data: JsonObject) { diagnostics += kind } }
        val engine = BrainEngine(renderer, memory, { error("reset must not call teacher") }, events, config) { initial }
        assertEquals(PageType.RESULTS, engine.probeLearning(profile(fake))?.pageType)
        engine.probeLearning(profile(fake))
        assertEquals("reset once, then use recovered controls", 1, navigations)
        assertTrue(diagnostics.contains("learning_recovery"))
        assertTrue(site.curriculum.none { it.done })
    }

    @Test fun failedDialogCannotResetBeforeCooldownOrAcrossHumanBoundary() {
        for (state in listOf("cooldown", "auth", "challenge", "hold")) {
            val fake = FakeSite()
            val memory = Memory(InMemoryStorage()) { initial }
            val site = memory.site(fake.host)
            site.lastLearningStatus = "FAILED: recently failed approaches; retry after page change or cooldown"
            if (state == "cooldown") site.learningBlockedUntil = initial + 30_000
            if (state == "hold") site.learningNeedsHuman = true
            var navigations = 0
            val renderer = object : Renderer by fake {
                override fun observe(timeoutMs: Long): String {
                    val raw = Json.parseObject(fake.observe(timeoutMs))
                    raw.optObject("signals")!!.put("passwordFields", if (state == "auth") 1 else 0)
                        .put("challengeWidget", state == "challenge").put("sparse", state == "auth")
                    return raw.toString()
                }
                override fun navigate(url: String, timeoutMs: Long): RendererResult { navigations++; return fake.navigate(url, timeoutMs) }
            }
            BrainEngine(renderer, memory, { null }, object : EngineEvents {}, config) { initial }.probeLearning(profile(fake))
            assertEquals(state, 0, navigations)
        }
    }

    @Test fun perpetuallyBusySearchResetsAfterOneMinuteButNotImmediately() {
        val fake = FakeSite().apply { dialogShown = false; navigate("https://fake.market/search?q=Ford", 1000) }
        var now = initial
        var navigations = 0
        val memory = Memory(InMemoryStorage()) { now }
        val renderer = object : Renderer by fake {
            override fun observe(timeoutMs: Long): String = Json.parseObject(fake.observe(timeoutMs))
                .put("settle", JsonObject().put("state", if (navigations == 0) "BUSY" else "IDLE")).toString()
            override fun navigate(url: String, timeoutMs: Long): RendererResult { navigations++; return fake.navigate(url, timeoutMs) }
        }
        val engine = BrainEngine(renderer, memory, { null }, object : EngineEvents {}, config) { now }
        engine.probeLearning(profile(fake)); assertEquals(0, navigations)
        now += 59_999; engine.probeLearning(profile(fake)); assertEquals(0, navigations)
        now++; assertNotNull(engine.probeLearning(profile(fake))); assertEquals(1, navigations)
        assertTrue(memory.site(fake.host).curriculum.none { it.done })
    }

    @Test fun noActionFailureYieldsAfterOneTaskEvenWhenOtherLessonsShareFailedPrerequisite() {
        val fake = FakeSite()
        val memory = Memory(InMemoryStorage()) { initial }
        var tasks = 0
        val events = object : EngineEvents { override fun finished(ledger: TaskLedger, result: TaskResult?) { tasks++ } }
        val engine = BrainEngine(fake, memory, { null }, events, config.copy(maxDecisionsWithoutProgress = 0)) { initial }
        LearningSession(engine, memory, events, listOf(profile(fake)), clock = { initial }).run(maxSites = 1)
        assertEquals("do not launch three empty tasks for the same dialog", 1, tasks)
        assertEquals(initial + Curriculum.RETRY_MS, memory.site(fake.host).learningBlockedUntil)
    }

    @Test fun missingPaginationVariantIsExplainedWithoutAwardingItsLesson() {
        val site = SiteModel("fake.market"); Curriculum.ensure(site)
        site.curriculum.filter { it.id != "next_page" }.forEach { it.completedAt = initial - 1 }
        site.curriculum.single { it.id == "next_page" }.opportunity = "ABSENT"
        assertTrue(Curriculum.progress(site).contains("10/11"))
        assertTrue(Curriculum.progress(site).contains("not present"))
        assertFalse(Curriculum.allLessonsComplete(site))
    }
}
