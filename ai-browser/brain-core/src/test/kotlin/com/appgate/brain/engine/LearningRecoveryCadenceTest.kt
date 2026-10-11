package com.appgate.brain.engine

import com.appgate.brain.json.Json
import com.appgate.brain.json.JsonArray
import com.appgate.brain.json.JsonObject
import com.appgate.brain.memory.*
import com.appgate.brain.model.*
import com.appgate.brain.perception.SpsParser
import com.appgate.brain.planner.Planner
import com.appgate.brain.planner.PlannerClient
import com.appgate.brain.profile.SiteProfile
import com.appgate.brain.test.FakeSite
import org.junit.Assert.*
import org.junit.Test

class LearningRecoveryCadenceTest {
    private val initial = 1_800_000_000_000L
    private val config = EngineConfig(pacingOverrideMs = 0, plannerCooldownMs = 0, ambiguousRecheckMs = 0, idleSleepMs = 0)

    @Test fun heldSearchCanLearnADifferentTeacherProcedureAfterThirtySeconds() {
        val fake = FakeSite().apply { dialogShown = false }
        var now = initial
        val memory = Memory(InMemoryStorage()) { now }
        val site = memory.site(fake.host)
        val page = SpsParser().parse(fake.observe(1000), now)
        val broken = memory.skills.get("search")!!.body
        val procedureKey = FailedStrategies.key(page, "search", broken, mapOf("query" to "Ford"))
        val plannerKey = FailedStrategies.key(page, "search")
        val localKey = FailedStrategies.key(page, "search", listOf(Step(StepKind.TYPE, Role.SEARCH_BOX, arg = "Ford", submit = true)))
        repeat(2) {
            FailedStrategies.record(site, procedureKey, false, now)
            FailedStrategies.record(site, plannerKey, false, now)
            FailedStrategies.record(site, localKey, false, now)
        }
        memory.saveSite(site)
        var calls = 0
        val planner = Planner(object : PlannerClient {
            override val describe = "different search procedure"
            override fun complete(instructions: String, input: String, schemaName: String, schema: JsonObject, maxOutputTokens: Int): String {
                calls++
                return """{"steps":[{"kind":"CLICK","role":"SUBMIT","optional":true,"expect":["PAGE_IS_RESULTS"]},{"kind":"TYPE","role":"SEARCH_BOX","arg":"${'$'}query","submit":true,"expect":["RESULTS_CHANGED"]}],"confidence":0.95}"""
            }
        })
        val engine = BrainEngine(fake, memory, { planner }, object : EngineEvents {}, config) { now }
        fun run(id: String): TaskLedger {
            val goal = Goal(id, GoalIntent.LEARN_SITE, "learn search", "Toyota", emptyList(), budget = Budget(actions = 10, llmCalls = 1, wallMs = 10000))
            return engine.runTask(TaskLedger(id, goal, fake.host, fake.url).apply { lesson = "search"; lastCheckpointUrl = fake.url })
        }
        val held = run("before-cooldown")
        assertEquals(0, calls)
        assertTrue(held.successfulSkills.isEmpty())
        now += 30_000
        val repaired = run("after-cooldown")
        assertEquals("AI repair must not be held for six hours", 1, calls)
        assertTrue("a different procedure must verify before earning credit: ${repaired.status} ${repaired.terminalReason} ${repaired.notes}", "search" in repaired.successfulSkills)
        assertFalse("successful repair must not erase the different broken procedure", FailedStrategies.allowed(site, procedureKey, now))
    }

    @Test fun errorAtTheKnownSearchUrlGetsOneReloadWithoutLearningCredit() {
        val fake = FakeSite().apply { dialogShown = false; navigate("https://fake.market/search?q=Ford", 1000) }
        var navigations = 0
        val error = Json.parseObject(fake.observe(1000)).put("elements", JsonArray()).put("items", JsonArray())
        error.put("signals", JsonObject().put("errorHint", true).put("sparse", true))
        assertEquals(PageType.ERROR, SpsParser().parse(error.toString(), initial).pageType)
        val renderer = object : Renderer by fake {
            override fun observe(timeoutMs: Long) = if (navigations == 0) error.toString() else fake.observe(timeoutMs)
            override fun navigate(url: String, timeoutMs: Long): RendererResult { navigations++; return fake.navigate(url, timeoutMs) }
        }
        val memory = Memory(InMemoryStorage()) { initial }
        val engine = BrainEngine(renderer, memory, { error("probe must not call AI") }, object : EngineEvents {}, config) { initial }
        val profile = SiteProfile("fake", "Fake", listOf(fake.host), "https://fake.market/", searchUrl = fake.url, trainingQueries = listOf("Ford"))
        val observed = engine.probeLearning(profile)
        assertEquals("a retry must reload an error even at the same URL", 1, navigations)
        assertEquals(PageType.RESULTS, observed?.pageType)
        assertEquals(0, memory.site(fake.host).lessonOrdinal)
        assertTrue(memory.site(fake.host).curriculum.none { it.done })
    }
    @Test fun repairReservationKeepsHistoryAndRateLimitsFurtherCalls() {
        val storage = InMemoryStorage()
        val memory = Memory(storage) { initial }
        val site = memory.site("fake.market")
        repeat(2) { FailedStrategies.record(site, "abcdef0123456789", false, initial) }
        memory.saveSite(site)
        val restored = Memory(storage) { initial }.site(site.host)
        assertFalse(FailedStrategies.repairAllowed(restored, "abcdef0123456789", initial + 29_999))
        assertTrue(FailedStrategies.repairAllowed(restored, "abcdef0123456789", initial + 30_000))
        FailedStrategies.record(restored, "abcdef0123456789", false, initial + 30_000)
        assertEquals(3, restored.strategyFailures.getValue("abcdef0123456789").count)
        assertFalse(FailedStrategies.repairAllowed(restored, "abcdef0123456789", initial + 30_001))
        assertFalse(FailedStrategies.allowed(restored, "abcdef0123456789", initial + 60_000))
    }

    @Test fun errorRecoveryDoesNotNavigateAwayFromHumanOrBusyPages() {
        for (state in listOf("auth", "challenge", "busy", "dialog")) {
            val fake = FakeSite().apply { dialogShown = false; navigate("https://fake.market/search?q=Ford", 1000) }
            val raw = Json.parseObject(fake.observe(1000)).put("elements", JsonArray()).put("items", JsonArray())
            raw.put("signals", JsonObject().put("errorHint", true).put("sparse", true)
                .put("passwordFields", if (state == "auth") 1 else 0).put("challengeWidget", state == "challenge")
                .put("dialog", state == "dialog"))
            if (state == "busy") raw.put("settle", JsonObject().put("state", "BUSY"))
            val memory = Memory(InMemoryStorage()) { initial }
            var navigations = 0
            val renderer = object : Renderer by fake {
                override fun observe(timeoutMs: Long) = raw.toString()
                override fun navigate(url: String, timeoutMs: Long): RendererResult { navigations++; return fake.navigate(url, timeoutMs) }
            }
            val profile = SiteProfile("fake", "Fake", listOf(fake.host), "https://fake.market/", searchUrl = fake.url)
            BrainEngine(renderer, memory, { error("probe must not call AI") }, object : EngineEvents {}, config) { initial }.probeLearning(profile)
            assertEquals("must leave $state page untouched", 0, navigations)
            if (state in listOf("auth", "challenge")) assertTrue(memory.site(fake.host).learningNeedsHuman)
        }
    }

}
