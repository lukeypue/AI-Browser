package com.appgate.brain.engine

import com.appgate.brain.json.*
import com.appgate.brain.memory.*
import com.appgate.brain.model.*
import com.appgate.brain.planner.*
import com.appgate.brain.perception.QueryKeys
import com.appgate.brain.test.FakeSite
import org.junit.Assert.*
import org.junit.Test

class DiscoveredLearningTargetTest {
    private val now = 1_800_000_000_000L
    private val config = EngineConfig(pacingOverrideMs = 0, plannerCooldownMs = 0, ambiguousRecheckMs = 0, idleSleepMs = 0)
    private fun ledger(renderer: DrawerRenderer, id: String) = TaskLedger(id,
        Goal(id, GoalIntent.LEARN_SITE, "numeric practice", "Ford Expedition", emptyList(),
            budget = Budget(actions = 20, llmCalls = 2, wallMs = 10_000)), renderer.fake.host, renderer.fake.url).apply { lesson = "constrain_numeric" }

    @Test fun revealedNumericTargetRepairsLocallyAndReplaysWithoutSavingItsLiteral() {
        val storage = InMemoryStorage(); val memory = Memory(storage) { now }
        val cold = DrawerRenderer(rejectFirstSetter = true)
        val teacher = Teacher()
        val out = BrainEngine(cold, memory, { teacher.planner }, object : EngineEvents {}, config) { now }.runTask(ledger(cold, "cold"))
        assertEquals(out.notes.joinToString(" | ") + out.steps.joinToString { "${it.source}:${it.action}:${it.status}:${it.evidence}" }, TaskStatus.DONE, out.status)
        assertTrue("constrain_numeric" in out.successfulSkills)
        assertEquals("late discovered constraints must reach local repair", 0, teacher.calls)
        assertTrue(out.goal.constraints.isEmpty())
        assertTrue(out.steps.any { it.source == "local" && it.status == VerifyStatus.VERIFIED })
        val skill = memory.skills.all().single { it.origin == SkillOrigin.COMPILED && "capability:constrain_numeric" in it.tags }
        assertTrue(skill.params.contains("price_max"))
        assertFalse("concrete practice values cannot enter procedural memory", skill.toJson().toString().contains("\"8000\""))
        assertTrue(skill.body.any { it.arg == "\$price_max" })

        val warm = DrawerRenderer(drawer = true, pending = "8000")
        val repeated = BrainEngine(warm, Memory(storage) { now }, { teacher.planner }, object : EngineEvents {}, config) { now }.runTask(ledger(warm, "warm"))
        assertEquals(TaskStatus.DONE, repeated.status)
        assertEquals(0, teacher.calls)
        assertEquals(listOf("10000"), warm.valuesSet)
        assertTrue(repeated.steps.any { it.source == "skill:${skill.id}" && it.status == VerifyStatus.VERIFIED })
    }

    @Test fun resumedDrawerDiscoveryResolvesTheLiveConstraintBeforeLocalContinuation() {
        val renderer = DrawerRenderer()
        val initial = ledger(renderer, "resume")
        val opened = listOf(Postcondition.DialogOpened)
        initial.currentProgram = listOf(Step(StepKind.CLICK, Role.FACET_OPEN, expect = opened))
        initial.programSource = "local"; initial.programCapability = "open_filters"; initial.programPost = opened
        initial.constraintAttempts["__discovery:constrain_numeric"] = 1
        val restored = TaskLedger.fromJson(Json.parseObject(initial.toJson().toString()))
        val teacher = Teacher(); val memory = Memory(InMemoryStorage()) { now }
        val out = BrainEngine(renderer, memory, { teacher.planner }, object : EngineEvents {}, config) { now }.runTask(restored)
        assertEquals(out.notes.joinToString(" | ") + out.steps.joinToString { "${it.source}:${it.action}:${it.status}:${it.evidence}" }, TaskStatus.DONE, out.status)
        assertEquals(0, teacher.calls)
        assertTrue("constrain_numeric" in out.successfulSkills)
        assertTrue(memory.skills.all().any { it.origin == SkillOrigin.COMPILED && "capability:constrain_numeric" in it.tags })
    }

    @Test fun canonicalFilterQueryKeysRemainRecognizedAfterTheDrawerCloses() {
        for (key in listOf("price_min", "price_max", "mileage_min", "mileage_max", "year_min", "year_max")) {
            assertEquals(key, QueryKeys.canonical(key))
        }
        assertNull(QueryKeys.canonical("arbitrary_private_field"))
    }

    private class Teacher : PlannerClient {
        var calls = 0
        override val describe = "counted test teacher"
        override fun complete(instructions: String, input: String, schemaName: String, schema: JsonObject, maxOutputTokens: Int): String { calls++; return "{}" }
        val planner = Planner(this)
    }

    private class DrawerRenderer(
        private var drawer: Boolean = false,
        private var pending: String = "",
        private val rejectFirstSetter: Boolean = false,
        val fake: FakeSite = FakeSite("drawer-practice.market").apply {
            dialogShown = false; navigate("https://drawer-practice.market/search?q=Ford+Expedition", 1000)
        }
    ) : Renderer by fake {
        private var rejected = false
        val valuesSet = mutableListOf<String>()
        override fun navigate(url: String, timeoutMs: Long) = fake.navigate(url, timeoutMs)
        override fun observe(timeoutMs: Long): String {
            val raw = Json.parseObject(fake.observe(timeoutMs))
            val elements = raw.optArray("elements")!!.objects().filter { it.optString("name") != "Price to" }.toMutableList()
            fun control(id: String, tag: String, name: String) = JsonObject().put("id", id).put("tag", tag).put("name", name)
                .put("visible", true).put("enabled", true).put("sameSite", true).put("region", if (drawer) "r3" else "r1")
            if (drawer) {
                elements += control("price-field", "input", "Max price").put("type", "number").put("value", pending).put("inDialog", true)
                elements += control("apply-filters", "button", "Apply filters").put("inDialog", true)
            } else elements += control("open-filters", "button", "Filters")
            raw.put("elements", JsonArray(elements))
            raw.optObject("signals")!!.put("dialog", drawer).put("dialogCoverage", if (drawer) 0.6 else 0.0)
            return raw.toString()
        }
        override fun act(command: JsonObject, timeoutMs: Long): RendererResult {
            return when (command.optString("id")) {
                "open-filters" -> { drawer = true; RendererResult(true) }
                "price-field" -> {
                    if (rejectFirstSetter && !rejected) { rejected = true; RendererResult(false, "one rejected setter") }
                    else { pending = command.optString("value").ifBlank { command.optString("text") }; valuesSet += pending; RendererResult(true) }
                }
                "apply-filters" -> {
                    fake.priceMax = pending.toIntOrNull(); fake.url = "https://${fake.host}/search?q=Ford+Expedition&price_max=$pending"
                    drawer = false; RendererResult(true)
                }
                else -> fake.act(command, timeoutMs)
            }
        }
    }
}
