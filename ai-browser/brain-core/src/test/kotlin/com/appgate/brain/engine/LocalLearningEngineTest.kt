package com.appgate.brain.engine

import com.appgate.brain.goal.GoalParser
import com.appgate.brain.json.JsonObject
import com.appgate.brain.json.Json
import com.appgate.brain.json.JsonArray
import com.appgate.brain.memory.InMemoryStorage
import com.appgate.brain.memory.Memory
import com.appgate.brain.model.*
import com.appgate.brain.planner.Planner
import com.appgate.brain.planner.PlannerClient
import com.appgate.brain.profile.SiteProfile
import com.appgate.brain.test.FakeSite
import org.junit.Assert.*
import org.junit.Test

class LocalLearningEngineTest {
    private val config = EngineConfig(pacingOverrideMs = 0L, ambiguousRecheckMs = 0L, plannerCooldownMs = 0L)

    @Test fun unavailableLessonStopsBeforeGenericSearchOrTeacher() {
        val fake = FakeSite().apply { dialogShown = false }
        val renderer = object : Renderer by fake {
            override fun observe(timeoutMs: Long): String {
                val page = Json.parseObject(fake.observe(timeoutMs))
                page.put("elements", JsonArray(page.optArray("elements")!!.objects().filter { it.optString("name") != "Sort by" }))
                return page.toString()
            }
        }
        var calls = 0
        val planner = teacher { calls++ }
        val goal = GoalParser.parse("Ford Expedition", Budget(actions = 30, wallMs = 5000)).copy(intent = GoalIntent.LEARN_SITE)
        val ledger = TaskLedger("missing", goal, fake.host, "https://${fake.host}/search?q=Ford+Expedition")
        ledger.lesson = "sort_results"
        val out = BrainEngine(renderer, Memory(InMemoryStorage()), { planner }, object : EngineEvents {}, config).runTask(ledger)
        assertEquals(0, calls)
        assertEquals(0, out.actions)
        assertEquals(TaskStatus.PARTIAL, out.status)
        assertFalse(out.successfulSkills.contains("sort_results"))
    }

    @Test fun numericLessonStopsAsSoonAsItsFilterVerifies() {
        val fake = FakeSite().apply { dialogShown = false }
        var calls = 0
        val goal = GoalParser.parse("Ford Expedition under 8000", Budget(actions = 30, wallMs = 5000)).copy(intent = GoalIntent.LEARN_SITE)
        val ledger = TaskLedger("numeric", goal, fake.host, "https://${fake.host}/search?q=Ford+Expedition")
        ledger.lesson = "constrain_numeric"
        val out = BrainEngine(fake, Memory(InMemoryStorage()), { teacher { calls++ } }, object : EngineEvents {}, config).runTask(ledger)
        assertEquals(TaskStatus.DONE, out.status)
        assertTrue(out.successfulSkills.contains("constrain_numeric"))
        assertEquals(0, calls)
        assertTrue("focused lesson actions=${out.actions}", out.actions <= 3)
        assertEquals(0, out.itemsInspected)
    }

    @Test fun syntheticTrainingEvidenceNeverCallsTeacher() {
        val fake = FakeSite().apply { dialogShown = false }
        var calls = 0
        val goal = GoalParser.parse("Ford Expedition", Budget(actions = 30, itemsInspected = 1, wallMs = 5000)).copy(
            intent = GoalIntent.LEARN_SITE,
            constraints = listOf(Constraint("feature_learning_detail", ConstraintOp.CONTAINS, "impossible evidence phrase", cls = ConstraintClass.RARE,
                sources = setOf(ConstraintSource.TEXT_EVIDENCE, ConstraintSource.DETAIL_CHECK))))
        val ledger = TaskLedger("detail", goal, fake.host, "https://${fake.host}/search?q=Ford+Expedition")
        // Legacy/no named lesson still must never buy evidence extraction for synthetic goals.
        BrainEngine(fake, Memory(InMemoryStorage()), { teacher { calls++ } }, object : EngineEvents {}, config).runTask(ledger)
        assertTrue(ledger.itemsInspected > 0)
        assertEquals(0, calls)
    }

    private fun teacher(count: () -> Unit) = Planner(object : PlannerClient {
        override val describe = "test"
        override fun complete(instructions: String, input: String, schemaName: String, schema: JsonObject, maxOutputTokens: Int): String {
            count(); return "{}"
        }
    })
}
