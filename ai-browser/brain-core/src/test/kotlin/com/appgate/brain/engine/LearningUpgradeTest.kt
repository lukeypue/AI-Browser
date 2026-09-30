package com.appgate.brain.engine

import com.appgate.brain.goal.GoalParser
import com.appgate.brain.json.*
import com.appgate.brain.memory.*
import com.appgate.brain.model.*
import com.appgate.brain.profile.SiteProfile
import com.appgate.brain.perception.SpsParser
import com.appgate.brain.planner.*
import com.appgate.brain.skills.SkillCompiler
import com.appgate.brain.test.FakeSite
import org.junit.Assert.*
import org.junit.Test

class LearningUpgradeTest {
    private fun planner(reply: String) = Planner(object : PlannerClient {
        override val describe = "fixture"
        override fun complete(instructions: String, input: String, schemaName: String, schema: JsonObject, maxOutputTokens: Int) = reply
    })
    private fun ledger() = TaskLedger("regression", GoalParser.parse("Ford Expedition under 8000 under 150000 miles"), "fake.market", "https://fake.market/")

    @Test fun partialStepsDoNotTeachWholeSkillsOrPenalizeUnattemptedLessons() {
        val site = SiteModel("fake.market")
        val task = ledger()
        task.status = TaskStatus.FAILED
        task.record(LedgerStep(1000, TaskPhase.CONSTRAIN, "s", "open drawer", VerifyStatus.VERIFIED, "opened", "skill:constrain_numeric"))
        Curriculum.recordAttempt(site, task)
        assertFalse(site.curriculum.first { it.id == "constrain_numeric" }.done)
        assertEquals(0, site.curriculum.first { it.id == "search" }.attempts)
        assertEquals(0, site.curriculum.first { it.id == "search" }.blocked)
    }

    @Test fun priceAndMileageKeepIndependentParametersAndExpectations() {
        val steps = listOf(
            Step(StepKind.SET_RANGE, Role.FACET, "price_max", "8000", expect = listOf(Postcondition.ValueIs("price_max", "8000"))),
            Step(StepKind.SET_RANGE, Role.FACET, "mileage_max", "150000", expect = listOf(Postcondition.ConstraintApplied("mileage_max", "150000"))))
        val result = SkillCompiler.abstractSteps(steps, ledger())
        assertEquals(listOf("\$price_max", "\$mileage_max"), result.steps.map { it.arg })
        assertEquals(Postcondition.ValueIs("price_max", "\$price_max"), result.steps[0].expect.single())
        assertEquals(Postcondition.ConstraintApplied("mileage_max", "\$mileage_max"), result.steps[1].expect.single())
    }

    @Test fun incompleteOrEmptyProgramsCannotBecomeSuccessfulSkills() {
        val memory = Memory(InMemoryStorage())
        val steps = listOf(Step(StepKind.WAIT))
        assertNull(SkillCompiler.compileFromPlanner(memory, ledger(), steps, 1000))
    }

    @Test fun scriptBundleChurnDoesNotInvalidateSemanticBindings() {
        val fake = FakeSite()
        val raw = Json.parseObject(fake.observe(1000))
        raw.putStrings("scripts", listOf("/bundle-abc.js"))
        val before = SpsParser().parse(raw.toString(), 1000)
        raw.putStrings("scripts", listOf("/bundle-def.js", "/ad-random.js"))
        val after = SpsParser().parse(raw.toString(), 1001)
        assertEquals(before.siteVersion, after.siteVersion)
    }

    @Test fun missingTargetIsAnAccountedFailureAndCannotCompleteSuccessfully() {
        val fake = FakeSite().apply { dialogShown = false }
        val task = ledger().copy(goal = ledger().goal.copy(intent = GoalIntent.CUSTOM, budget = Budget(actions = 4, wallMs = 5000)))
        task.currentProgram = listOf(Step(StepKind.CLICK, Role.FACET_APPLY))
        task.programSource = "planner"
        val out = BrainEngine(fake, Memory(InMemoryStorage()), { null }, object : EngineEvents {},
            EngineConfig(pacingOverrideMs = 0, ambiguousRecheckMs = 0)).runTask(task)
        assertTrue(out.steps.any { it.status == VerifyStatus.FAILED && it.evidence.contains("no_target") })
        assertTrue(out.consecutiveFailures > 0)
        assertNotEquals(TaskStatus.DONE, out.status)
    }

    @Test fun foreignObservationsNeverBecomeSiteMemory() {
        val foreign = FakeSite("other.market").apply { dialogShown = false }
        val renderer = object : Renderer by foreign {
            override fun navigate(url: String, timeoutMs: Long): RendererResult { foreign.url = "https://other.market/"; return RendererResult(true) }
        }
        val memory = Memory(InMemoryStorage())
        val out = BrainEngine(renderer, memory, { null }, object : EngineEvents {}, EngineConfig(pacingOverrideMs = 0)).runTask(ledger())
        assertNotEquals(TaskStatus.DONE, out.status)
        assertTrue(memory.site("fake.market").pageTypesSeen.isEmpty())
        assertTrue(out.verdicts.isEmpty())
    }

    @Test fun plannerCanOpenDrawerThenUseAControlRevealedLater() {
        val page = SpsParser().parse(FakeSite().observe(1000))
        val steps = planner("{}").parseProgram("""{"steps":[{"kind":"CLICK","role":"FACET_OPEN","expect":["DIALOG_OPENED"]},{"kind":"SET_RANGE","role":"FACET","facet_key":"price_max","arg":"8000","expect":["CONSTRAINT_APPLIED"]}]}""", page).steps
        assertEquals(2, steps.size)
        assertEquals(Role.FACET, steps.last().role)
    }

    @Test fun plannerRejectsWholeProgramContainingForbiddenSteps() {
        val page = SpsParser().parse(FakeSite().observe(1000))
        val steps = planner("{}").parseProgram("""{"steps":[{"kind":"TYPE","role":"SEARCH_BOX","arg":"x","expect":["RESULTS_CHANGED"]},{"kind":"CLICK","role":"SEND","expect":["DIALOG_CLOSED"]}]}""", page).steps
        assertTrue(steps.isEmpty())
    }

    @Test fun missingExpectedItemCannotGroundToAnUnrelatedListing() {
        val fake = FakeSite().apply { dialogShown = false }
        fake.navigate("https://fake.market/search?q=Ford", 1000)
        val sps = SpsParser().parse(fake.observe(1000))
        assertTrue(StepGrounder(null).ground(Step(StepKind.CLICK, Role.RESULT_ITEM, arg = "missing-item"), emptyMap(), sps, emptySet()) is GroundingOutcome.Missing)
    }

    @Test fun oneHumanOnlySiteDoesNotStopOtherTrainingSites() {
        val fake = FakeSite().apply { dialogShown = false }
        val renderer = object : Renderer by fake {
            override fun navigate(url: String, timeoutMs: Long): RendererResult {
                fake.loginWall = url.contains("/login")
                return fake.navigate(url, timeoutMs)
            }
        }
        val memory = Memory(InMemoryStorage())
        val events = object : EngineEvents {}
        val profile = com.appgate.brain.profile.SiteProfile("login", "Login", listOf(fake.host), "https://${fake.host}/login", trainingQueries = listOf("Ford"))
        val next = profile.copy(key = "next", hosts = listOf("next.market"), startUrl = "https://next.market/")
        val session = LearningSession(BrainEngine(renderer, memory, { null }, events, EngineConfig(pacingOverrideMs = 0)), memory, events, listOf(profile, next), perSiteChunkMs = 20)
        session.run(maxSites = 2)
        assertTrue("second site must be visited", fake.log.any { it.contains("next.market") })
    }

    @Test fun resourceExhaustionIsNeverReportedAsDone() {
        val fake = FakeSite().apply { dialogShown = false }
        val task = ledger().copy(goal = ledger().goal.copy(budget = Budget(actions = 0)))
        val out = BrainEngine(fake, Memory(InMemoryStorage()), { null }, object : EngineEvents {}, EngineConfig(pacingOverrideMs = 0)).runTask(task)
        assertEquals(TaskStatus.BUDGET_EXHAUSTED, out.status)
    }

    @Test fun verifiedCompiledProcedureSurvivesReloadAndRunsWithoutPlanner() {
        val storage = InMemoryStorage()
        val memory = Memory(storage)
        val fake = FakeSite().apply { dialogShown = false }
        val goal = ledger().goal.copy(intent = GoalIntent.CUSTOM, budget = Budget(actions = 6, llmCalls = 1, wallMs = 10000))
        val plan = planner("""{"steps":[{"kind":"TYPE","role":"SEARCH_BOX","arg":"Ford Expedition","submit":true,"expect":["RESULTS_CHANGED"]}],"confidence":0.9}""")
        val config = EngineConfig(pacingOverrideMs = 0, plannerCooldownMs = 0, ambiguousRecheckMs = 0)
        val cold = BrainEngine(fake, memory, { plan }, object : EngineEvents {}, config).runTask(ledger().copy(goal = goal))
        val compiled = memory.skills.all().filter { it.origin == SkillOrigin.COMPILED }
        assertTrue("cold verified program should be compiled", compiled.isNotEmpty())
        assertTrue(compiled.all { !it.toJson().toString().contains("Expedition") })
        val reloaded = Memory(storage)
        fake.query = ""; fake.url = "https://fake.market/"
        val warm = BrainEngine(fake, reloaded, { null }, object : EngineEvents {}, config).runTask(ledger().copy(id = "warm", goal = goal.copy(query = "Toyota Sequoia")))
        assertTrue(warm.steps.any { it.source.startsWith("skill:compiled_") && it.status == VerifyStatus.VERIFIED })
        assertEquals(0, warm.llmCalls)
        assertTrue(cold.llmCalls > warm.llmCalls)
        val wrongPage = SpsParser().parse(fake.observe(1000)).copy(pageType = PageType.DETAIL)
        assertNull(reloaded.skills.reusable(wrongPage, "custom", mapOf("query" to "x"), ledger()))
    }

    @Test fun repeatedVerifiedStateIsNotNewProgress() {
        val task = ledger()
        assertTrue(ProgressSupervisor.evidence(task, "navigation-cycle"))
        task.decisionsWithoutProgress = 10
        assertFalse(ProgressSupervisor.evidence(task, "navigation-cycle"))
        assertEquals(10, task.decisionsWithoutProgress)
    }

    @Test fun finiteVisitLimitCanReturnWhileAllLessonsAwaitReview() {
        val fake = FakeSite(); val memory = Memory(InMemoryStorage()); val site = memory.site(fake.host)
        Curriculum.ensure(site)
        site.curriculum.forEach { Curriculum.markSkillVerified(site, it.id, 1000) }
        val events = object : EngineEvents {}
        val profile = com.appgate.brain.profile.SiteProfile("fake", "Fake", listOf(fake.host), "https://${fake.host}/")
        val session = LearningSession(BrainEngine(fake, memory, { null }, events), memory, events, listOf(profile), clock = { 1001 })
        session.run(maxSites = 1)
        assertEquals(1, session.siteIndex)
        assertTrue(fake.log.isEmpty())
        assertFalse(Curriculum.reviewDue(site, 1001))
        assertTrue(Curriculum.reviewDue(site, 1001 + 24 * 60 * 60_000L))
    }
    @Test fun learningGoalAllowsOnlyOneRemoteRepairBeforeRotation() {
        val site = SiteModel("fake.market")
        val profile = SiteProfile("fake", "Fake", listOf(site.host), "https://fake.market/",
            trainingQueries = listOf("Ford Expedition"))
        val goal = Curriculum.nextGoal(site, profile, 0, null, "constrain_numeric")
        assertEquals(1, goal.budget.llmCalls)
        assertTrue(goal.budget.actions <= 16)
        assertTrue(goal.budget.wallMs <= 3 * 60_000L)
    }

}
