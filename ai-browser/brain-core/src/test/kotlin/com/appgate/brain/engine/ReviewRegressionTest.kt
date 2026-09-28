package com.appgate.brain.engine

import com.appgate.brain.goal.GoalParser
import com.appgate.brain.json.*
import com.appgate.brain.memory.*
import com.appgate.brain.model.*
import com.appgate.brain.perception.SpsParser
import com.appgate.brain.planner.*
import com.appgate.brain.skills.SkillCompiler
import com.appgate.brain.test.FakeSite
import org.junit.Assert.*
import org.junit.Test

class ReviewRegressionTest {
    private val config = EngineConfig(pacingOverrideMs = 0, plannerCooldownMs = 0, ambiguousRecheckMs = 0)

    @Test fun laterFilterCannotEraseEarlierFilterAndStillTeachSuccess() {
        val memory = Memory(InMemoryStorage()); val fake = FakeSite().apply { dialogShown = false }
        val goal = GoalParser.parse("Ford Expedition under 8000 under 150000 miles")
        val steps = listOf(
            Step(StepKind.SET_RANGE, Role.FACET, "price_max", "8000", expect = listOf(Postcondition.ValueIs("price_max", "8000"))),
            Step(StepKind.SET_RANGE, Role.FACET, "mileage_max", "150000", expect = listOf(Postcondition.ValueIs("mileage_max", "150000"))))
        val ledger = TaskLedger("two-filters", goal, fake.host, fake.url)
        ledger.currentProgram = steps; ledger.programSource = "planner"; ledger.programPage = PageType.RESULTS
        ledger.programCapability = "constrain_numeric"; ledger.programPost = steps.last().expect; ledger.programVerifiedSteps += listOf(0, 1)
        val before = SpsParser().parse(fake.observe(1000)).copy(pageType = PageType.RESULTS, hash = "before", constraintsActive = emptyMap())
        val after = before.copy(hash = "after", constraintsActive = mapOf("mileage_max" to "150000"))
        val engine = BrainEngine(fake, memory, { null }, object : EngineEvents {})
        // All steps verified earlier, but the second control removed the first effect.
        engine.javaClass.getDeclaredField("programBefore").apply { isAccessible = true }.set(engine, before)
        engine.javaClass.getDeclaredMethod("finishProgram", TaskLedger::class.java, SemanticPageState::class.java, SiteModel::class.java, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(engine, ledger, after, memory.site(fake.host), true)
        assertFalse(ledger.programCompleted)
        assertFalse("constrain_numeric" in ledger.successfulSkills)
        assertTrue(memory.skills.all().none { it.origin == SkillOrigin.COMPILED })
    }

    @Test fun consolidationPreservesEachHostsVerifiedProcedure() {
        val storage = InMemoryStorage(); val memory = Memory(storage)
        val hosts = listOf("fake.market", "other.market")
        hosts.forEach { host ->
            val page = SpsParser().parse(FakeSite(host).apply { dialogShown = false }.observe(1000))
            val ledger = TaskLedger("teach-$host", GoalParser.parse("Ford Expedition"), host, "https://$host/")
            val steps = listOf(Step(StepKind.TYPE, Role.SEARCH_BOX, arg = ledger.goal.query, submit = true, expect = listOf(Postcondition.ResultsChanged)))
            ledger.programPage = page.pageType; ledger.programCapability = "search"; ledger.programPost = steps.single().expect
            ledger.programCompleted = true; ledger.programVerifiedSteps += 0
            assertNotNull(SkillCompiler.compileFromPlanner(memory, ledger, steps, 1000))
        }
        val reloaded = Memory(storage)
        Consolidation(reloaded).run(hosts.first(), 1001)
        assertEquals(2, reloaded.skills.all().count { it.origin == SkillOrigin.COMPILED })
        hosts.forEach { host ->
            val page = SpsParser().parse(FakeSite(host).apply { dialogShown = false }.observe(1000))
            assertNotNull(reloaded.skills.reusable(page, "search", mapOf("query" to "Toyota"), TaskLedger("reuse", GoalParser.parse("Toyota"), host, page.url)))
        }
    }

    @Test fun absentSelectedLessonYieldsWithoutPenalizingUnrelatedSkills() {
        val site = SiteModel("fake.market")
        Curriculum.markSkillVerified(site, "search", 1000)
        assertEquals("constrain_numeric", Curriculum.nextLesson(site))
        val ledger = TaskLedger("unavailable", GoalParser.parse("Ford"), site.host, "https://${site.host}/")
        ledger.lesson = "constrain_numeric"; ledger.status = TaskStatus.DONE
        ledger.attemptedSkills += "search"; ledger.successfulSkills += "search"
        Curriculum.recordAttempt(site, ledger)
        val reloaded = SiteModel.fromJson(site.toJson())
        assertEquals("open_item", Curriculum.nextLesson(reloaded))
        assertEquals(0, reloaded.curriculum.first { it.id == "open_item" }.attempts)
        assertEquals(0, reloaded.curriculum.first { it.id == "open_item" }.blocked)
    }

    @Test fun failedDeterministicControlGetsBoundedPlannerRepair() {
        val fake = FakeSite().apply { dialogShown = false }
        val renderer = object : Renderer by fake {
            override fun act(command: JsonObject, timeoutMs: Long) = RendererResult(false, "control did not work")
        }
        var calls = 0
        val planner = Planner(object : PlannerClient {
            override val describe = "repair fixture"
            override fun complete(instructions: String, input: String, schemaName: String, schema: JsonObject, maxOutputTokens: Int): String { calls++; return "{}" }
        })
        val goal = GoalParser.parse("Ford Expedition", Budget(itemsInspected = 25, actions = 40, llmCalls = 3, wallMs = 10000)).copy(constraints = emptyList())
        val out = BrainEngine(renderer, Memory(InMemoryStorage()), { planner }, object : EngineEvents {}, config)
            .runTask(TaskLedger("repair", goal, fake.host, "https://${fake.host}/search?q=Ford+Expedition"))
        assertTrue("configured AI must receive the deterministic failure", calls > 0)
        assertTrue(calls <= 3)
        assertTrue(out.actions <= 40)
        assertNotEquals(TaskStatus.DONE, out.status)
    }

    @Test fun verifiedTextExpansionTeachesEvenWhenStructuralHashStaysTheSame() {
        val fake = FakeSite().apply { dialogShown = false }
        var expanded = false
        val renderer = object : Renderer by fake {
            override fun observe(timeoutMs: Long): String {
                val raw = Json.parseObject(fake.observe(timeoutMs))
                raw.optArray("elements")!!.add(JsonObject().put("id", "expand").put("tag", "button").put("name", "Show more").put("visible", true).put("enabled", true).put("region", "r2"))
                if (expanded) {
                    raw.put("detailText", raw.optString("detailText") + " Extra detailed information.".repeat(30))
                    raw.optObject("signals")!!.put("textLength", 3000)
                }
                return raw.toString()
            }
            override fun act(command: JsonObject, timeoutMs: Long): RendererResult {
                if (command.optString("id") == "expand") { expanded = true; return RendererResult(true) }
                return fake.act(command, timeoutMs)
            }
        }
        val memory = Memory(InMemoryStorage())
        val goal = GoalParser.parse("inspect item").copy(intent = GoalIntent.INSPECT_ITEM, targetUrl = "https://${fake.host}/item/1")
        val out = BrainEngine(renderer, memory, { null }, object : EngineEvents {}, config).runTask(TaskLedger("expand", goal, fake.host, goal.targetUrl!!))
        assertTrue(out.steps.any { it.status == VerifyStatus.VERIFIED && it.source == "skill:expand_description" })
        assertTrue(out.successfulSkills.contains("expand_description"))
    }

    @Test fun workingPlannerRepairBecomesReusableProcedure() {
        val fake = FakeSite().apply { dialogShown = false }
        val renderer = object : Renderer by fake {
            override fun observe(timeoutMs: Long): String {
                val raw = Json.parseObject(fake.observe(timeoutMs))
                raw.optArray("elements")!!.add(JsonObject().put("id", "broken-more").put("tag", "button").put("name", "Load more").put("visible", true).put("enabled", true).put("region", "r2"))
                return raw.toString()
            }
            override fun act(command: JsonObject, timeoutMs: Long): RendererResult =
                if (command.optString("id") == "broken-more") RendererResult(false, "button rejected") else fake.act(command, timeoutMs)
        }
        val memory = Memory(InMemoryStorage())
        val planner = Planner(object : PlannerClient {
            override val describe = "alternate control"
            override fun complete(instructions: String, input: String, schemaName: String, schema: JsonObject, maxOutputTokens: Int) =
                """{"steps":[{"kind":"CLICK","role":"PAGE_NEXT","expect":["NEW_RESULTS"]}],"confidence":0.9}"""
        })
        val goal = GoalParser.parse("Ford Expedition", Budget(itemsInspected = 6, actions = 30, llmCalls = 2, wallMs = 10000)).copy(constraints = emptyList())
        val out = BrainEngine(renderer, memory, { planner }, object : EngineEvents {}, config)
            .runTask(TaskLedger("repair-success", goal, fake.host, "https://${fake.host}/search?q=Ford+Expedition"))
        assertEquals(TaskStatus.DONE, out.status)
        assertTrue(out.successfulSkills.contains("load_more"))
        assertTrue(memory.skills.all().any { it.origin == SkillOrigin.COMPILED && "capability:load_more" in it.tags })
    }
}
