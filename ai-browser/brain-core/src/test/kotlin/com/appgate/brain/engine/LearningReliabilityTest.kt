package com.appgate.brain.engine

import com.appgate.brain.goal.GoalParser
import com.appgate.brain.memory.*
import com.appgate.brain.model.*
import com.appgate.brain.perception.SpsParser
import com.appgate.brain.profile.SiteProfiles
import com.appgate.brain.profile.SiteProfile
import com.appgate.brain.json.JsonObject
import com.appgate.brain.planner.Planner
import com.appgate.brain.planner.PlannerClient
import com.appgate.brain.test.FakeSite
import org.junit.Assert.*
import org.junit.Test

class LearningReliabilityTest {
    @Test fun explicitStartRechecksAnOldModelReviewRequest() {
        val fake = FakeSite().apply { dialogShown = false }
        val memory = Memory(InMemoryStorage())
        val site = memory.site(fake.host)
        site.learningNeedsHuman = true
        site.lastLearningStatus = "NEED_HUMAN: Probably needs login because I cannot find results"
        val events = object : EngineEvents {}
        val config = EngineConfig(pacingOverrideMs = 0, plannerCooldownMs = 0, ambiguousRecheckMs = 0)
        val profile = SiteProfile("fake", "Fake", listOf(fake.host), "https://${fake.host}/", trainingQueries = listOf("Ford Expedition"))
        LearningSession(BrainEngine(fake, memory, { null }, events, config), memory, events, listOf(profile), perSiteChunkMs = 100).run(maxSites = 1)
        assertTrue("Start must re-observe a stale review request", fake.log.any { it.startsWith("navigate ") })
        assertFalse(site.learningNeedsHuman)
    }

    @Test fun dismissTargetsTheDialogRatherThanBackgroundCloseButtons() {
        val page = SpsParser().parse(FakeSite().observe(1000))
        val close = page.byRole(Role.CLOSE).first()
        val outside = close.copy(id = "background", regionRole = RegionRole.MAIN, features = FeatureVec.EMPTY, roleScore = 1.0)
        val inside = close.copy(id = "popup", regionRole = RegionRole.DIALOG, roleScore = 0.5)
        val outcome = StepGrounder(null).ground(Step(StepKind.DISMISS, Role.CLOSE), emptyMap(),
            page.copy(dialogOpen = true, affordances = listOf(outside, inside)), emptySet()) as GroundingOutcome.Ready
        assertEquals("popup", outcome.grounded.action.target?.affordanceId)
    }

    @Test fun plannerGuessAboutLoginDoesNotPermanentlyHoldAnOrdinaryPage() {
        val fake = FakeSite().apply { dialogShown = false }
        val planner = Planner(object : PlannerClient {
            override val describe = "mistaken login guess"
            override fun complete(instructions: String, input: String, schemaName: String, schema: JsonObject, maxOutputTokens: Int) =
                """{"needs_human":"Probably needs login because I cannot find results","steps":[],"confidence":0.8}"""
        })
        val goal = GoalParser.parse("learn page").copy(intent = GoalIntent.CUSTOM)
        val out = BrainEngine(fake, Memory(InMemoryStorage()), { planner }, object : EngineEvents {}, EngineConfig(pacingOverrideMs = 0))
            .runTask(TaskLedger("model-guess", goal, fake.host, fake.url))
        assertNotEquals("a model inference is not an observed login wall", TaskStatus.NEED_HUMAN, out.status)
        assertTrue(out.done)
        assertFalse(out.humanReason.contains("login"))
    }

    @Test fun newLearningTaskRemembersFailedSearchAndPlannerAttemptsButEventuallyRetries() {
        val fake = FakeSite().apply { dialogShown = false }
        val renderer = object : Renderer by fake {
            override fun act(command: JsonObject, timeoutMs: Long) = RendererResult(false, "control rejected")
        }
        var now = 1000L
        var calls = 0
        val storage = InMemoryStorage()
        val planner = Planner(object : PlannerClient {
            override val describe = "no usable repair"
            override fun complete(instructions: String, input: String, schemaName: String, schema: JsonObject, maxOutputTokens: Int): String { calls++; return "{}" }
        })
        fun run(id: String): TaskLedger {
            val goal = GoalParser.parse("Ford Expedition", Budget(actions = 30, llmCalls = 4, wallMs = 10000))
                .copy(intent = GoalIntent.LEARN_SITE, constraints = emptyList())
            val engine = BrainEngine(renderer, Memory(storage) { now }, { planner }, object : EngineEvents {},
                EngineConfig(pacingOverrideMs = 0, plannerCooldownMs = 0, ambiguousRecheckMs = 0), { now })
            return engine.runTask(TaskLedger(id, goal, fake.host, "https://${fake.host}/").apply { lesson = "search" })
        }
        val first = run("first"); val firstCalls = calls
        assertTrue(firstCalls > 0)
        now += 60_000L
        val second = run("second")
        assertTrue("a fresh ledger must not reset failed AI repairs", calls - firstCalls < firstCalls)
        assertTrue("a fresh ledger must avoid the same broken control", second.actions < first.actions)
        assertNotEquals(TaskStatus.DONE, second.status)
        val beforeRetry = calls
        now += 6 * 60_000L
        run("later")
        assertTrue("failures must expire so repaired pages can recover", calls > beforeRetry)
    }

    @Test fun reachingLastPageKeepsUnchangedVerifiedSearchBinding() {
        val fake = FakeSite().apply { dialogShown = false }
        fake.navigate("https://fake.market/search?q=Ford", 1000)
        val first = SpsParser().parse(fake.observe(1000))
        fake.page = 2
        val last = SpsParser().parse(fake.observe(1000))
        assertTrue(first.has(Role.PAGE_NEXT)); assertFalse(last.has(Role.PAGE_NEXT))
        val search = first.byRole(Role.SEARCH_BOX).single()
        val memory = Memory(InMemoryStorage()) { 1000L }
        val site = memory.site(fake.host).apply { siteVersion = first.siteVersion }
        val binding = Binding(fake.host, first.pageType, Role.SEARCH_BOX, null, first.siteVersion,
            search.features, listOf(search.name), BetaStat(successes = 10.0), 1L)
        site.bindings[binding.key] = binding
        val engine = BrainEngine(fake, memory, { null }, object : EngineEvents {})
        engine.javaClass.getDeclaredMethod("trackSiteVersion", SiteModel::class.java, SemanticPageState::class.java)
            .apply { isAccessible = true }.invoke(engine, site, last)
        Consolidation(memory).run(fake.host, 1000L)
        assertNotNull("pagination must not discard a working search control", site.binding(first.pageType, Role.SEARCH_BOX, null))
    }

    @Test fun failedActionCannotReenableQuarantinedBinding() {
        val memory = Memory(InMemoryStorage()) { 1000L }
        val binding = Binding("fake.market", PageType.RESULTS, Role.CLOSE, null, "semantic:old",
            FeatureVec.EMPTY, emptyList(), BetaStat(successes = 10.0), 1L, shadowed = true)
        memory.site(binding.host).bindings[binding.key] = binding
        memory.recordBinding(binding.host, binding.copy(siteVersion = "semantic:new"), false)
        assertNull(memory.site(binding.host).binding(binding.pageType, binding.role, null))
        memory.recordBinding(binding.host, binding.copy(siteVersion = "semantic:new"), true)
        assertNotNull(memory.site(binding.host).binding(binding.pageType, binding.role, null))
    }

    @Test fun unrecognizedEmptyResultsCannotFinishAsInspectionComplete() {
        val fake = FakeSite().apply { dialogShown = false }
        fake.navigate("https://fake.market/search?q=NoSuchVehicle", 1000)
        val page = SpsParser().parse(fake.observe(1000))
        val goal = GoalParser.parse("NoSuchVehicle")
        val ledger = TaskLedger("empty", goal, fake.host, fake.url).apply { phase = TaskPhase.COLLECT; scrollRoundsWithoutNew = 2 }
        val decision = TaskPolicy(SiteProfiles.generic(fake.host), SiteModel(fake.host)).decide(goal, page, ledger)
        assertTrue(decision is PolicyDecision.Finish)
        assertEquals(TaskStatus.PARTIAL, (decision as PolicyDecision.Finish).status)
        assertTrue(decision.reason.contains("no listings"))
    }
}
