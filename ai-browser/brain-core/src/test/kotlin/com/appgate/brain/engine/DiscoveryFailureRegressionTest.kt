package com.appgate.brain.engine

import com.appgate.brain.json.*
import com.appgate.brain.memory.*
import com.appgate.brain.model.*
import com.appgate.brain.perception.SpsParser
import com.appgate.brain.planner.*
import com.appgate.brain.test.FakeSite
import org.junit.Assert.*
import org.junit.Test

class DiscoveryFailureRegressionTest {
    @Test fun heldDrawerDiscoveryDoesNotPreemptAnEligibleTeacherRepair() {
        val now = 1_800_000_000_000L
        val fake = FakeSite().apply { dialogShown = false; navigate("https://fake.market/search?q=Ford", 1000) }
        var actions = 0
        val renderer = object : Renderer by fake {
            override fun observe(timeoutMs: Long): String {
                val raw = Json.parseObject(fake.observe(timeoutMs))
                raw.put("elements", JsonArray(raw.optArray("elements")!!.objects().filter { it.optString("name") != "Price to" } +
                    JsonObject().put("id", "filters").put("tag", "button").put("name", "Filters")
                        .put("visible", true).put("enabled", true).put("sameSite", true)))
                return raw.toString()
            }
            override fun act(command: JsonObject, timeoutMs: Long): RendererResult { actions++; return fake.act(command, timeoutMs) }
        }
        val memory = Memory(InMemoryStorage()) { now }
        val task = TaskLedger("held-drawer", Goal("g", GoalIntent.LEARN_SITE, "practice price", "Ford",
            listOf(Constraint("price", ConstraintOp.LTE, "8000")), budget = Budget(actions = 10, llmCalls = 1, wallMs = 10_000)), fake.host, fake.url)
            .apply { lesson = "constrain_numeric"; repairReason = "retry failed numeric procedure"; programCapability = "constrain_numeric" }
        val page = SpsParser().parse(renderer.observe(1000))
        val plan = LocalRecoveryPlanner.propose(task, page, memory.site(fake.host), "constrain_numeric")!!
        assertFalse(plan.completesCapability)
        val key = FailedStrategies.key(page, "open_filters", plan.steps, plan.params)
        repeat(2) { FailedStrategies.record(memory.site(fake.host), key, false, now) }
        var calls = 0
        val teacher = Planner(object : PlannerClient {
            override val describe = "test teacher"
            override fun complete(instructions: String, input: String, schemaName: String, schema: JsonObject, maxOutputTokens: Int): String {
                calls++
                return """{"steps":[],"giveUp":true,"confidence":1.0,"rationale":"no safe alternative"}"""
            }
        })
        val out = BrainEngine(renderer, memory, { teacher }, object : EngineEvents {},
            EngineConfig(pacingOverrideMs = 0, plannerCooldownMs = 0, ambiguousRecheckMs = 0, idleSleepMs = 0)) { now }.runTask(task)
        assertEquals("held open_filters must not swallow the bounded teacher opportunity", 1, calls)
        assertEquals(0, actions)
        assertTrue(out.successfulSkills.isEmpty())
        assertTrue(memory.site(fake.host).curriculum.none { it.done })
    }
}
