package com.appgate.brain.engine

import com.appgate.brain.goal.GoalParser
import com.appgate.brain.json.Json
import com.appgate.brain.json.JsonObject
import com.appgate.brain.memory.InMemoryStorage
import com.appgate.brain.memory.Memory
import com.appgate.brain.model.*
import com.appgate.brain.test.FakeSite
import org.junit.Assert.*
import org.junit.Test

class StrictProgramGroundingTest {
    @Test fun learnedProgramDoesNotUseOppositeBoundInALaterStep() {
        runScenario("skill:compiled_guarded", changeAfterFirstFacet = false)
    }

    @Test fun learnedRetryDoesNotUseOppositeBoundAfterDocumentChanges() {
        runScenario("skill:compiled_guarded", changeAfterFirstFacet = true)
    }

    @Test fun localRecipeAlsoKeepsItsExactFacetDuringExecution() {
        runScenario("local", changeAfterFirstFacet = false)
    }

    @Test fun resumedProgramRemainsStrictIfItsSkillRecordWasRetired() {
        runScenario("skill:compiled_guarded", changeAfterFirstFacet = false, storeSkill = false)
    }

    private fun runScenario(source: String, changeAfterFirstFacet: Boolean, storeSkill: Boolean = true) {
        val fake = FakeSite().apply { dialogShown = false }
        var replaced = !changeAfterFirstFacet
        val commands = mutableListOf<JsonObject>()
        val renderer = object : Renderer by fake {
            override fun observe(timeoutMs: Long): String {
                val raw = Json.parseObject(fake.observe(timeoutMs))
                if (replaced) raw.optArray("elements")!!.objects().firstOrNull { it.optString("name") == "Price to" }
                    ?.put("name", "Price from")?.put("id", "wrong-min")
                return raw.toString()
            }
            override fun act(command: JsonObject, timeoutMs: Long): RendererResult {
                commands += command
                if (command.optString("cmd") in setOf("select", "set_range")) {
                    replaced = true
                    // An accepted action with no verified result forces the fresh retry path.
                    return RendererResult(true)
                }
                return fake.act(command, timeoutMs)
            }
        }
        val goal = GoalParser.parse("Ford Expedition under 8000", Budget(actions = 4, llmCalls = 0, wallMs = 10000)).copy(intent = GoalIntent.CUSTOM)
        val steps = listOf(
            Step(StepKind.TYPE, Role.SEARCH_BOX, arg = goal.query, submit = true, expect = listOf(Postcondition.ResultsChanged)),
            Step(StepKind.SET_RANGE, Role.FACET, "price_max", "8000", expect = listOf(Postcondition.ConstraintApplied("price_max", "8000")))
        )
        val post = listOf(Postcondition.ConstraintApplied("price_max", "8000"))
        val memory = Memory(InMemoryStorage())
        if (storeSkill) memory.skills.put(Skill("compiled_guarded", 2, "verified fixture", emptyList(), emptyList(), steps, post,
            SkillOrigin.COMPILED, tags = setOf("verified_v2", "capability:custom")))
        val ledger = TaskLedger("strict", goal, fake.host, fake.url).apply {
            currentProgram = steps
            programSource = source
            programCapability = "custom"
            programPost = post
        }
        val out = BrainEngine(renderer, memory, { null }, object : EngineEvents {},
            EngineConfig(pacingOverrideMs = 0, plannerCooldownMs = 0, ambiguousRecheckMs = 0, maxConsecutiveFailures = 1)).runTask(ledger)
        assertFalse("opposite bound must never be acted on", commands.any { it.optString("id") == "wrong-min" })
        assertEquals(if (changeAfterFirstFacet) 1 else 0, commands.count { it.optString("cmd") in setOf("select", "set_range") })
        assertFalse(out.successfulSkills.contains("custom"))
        assertEquals(0, out.llmCalls)
    }
}
