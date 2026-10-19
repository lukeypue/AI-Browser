package com.appgate.brain.engine

import com.appgate.brain.json.Json
import com.appgate.brain.json.JsonObject
import com.appgate.brain.memory.*
import com.appgate.brain.model.*
import com.appgate.brain.perception.SpsParser
import com.appgate.brain.profile.SiteProfile
import com.appgate.brain.test.FakeSite
import org.junit.Assert.*
import org.junit.Test

/** The submit-key path fails; a distinct visible Search button remains usable. */
class SearchAlternativeLearningTest {
    private val now = 1_800_000_000_000L
    private val config = EngineConfig(pacingOverrideMs = 0, plannerCooldownMs = 0, ambiguousRecheckMs = 0, idleSleepMs = 0)
    private fun task(fake: FakeSite, id: String = "learn"): TaskLedger = TaskLedger(id,
        Goal(id, GoalIntent.LEARN_SITE, "synthetic search", "Ford", emptyList(), budget = Budget(actions = 12, llmCalls = 0, wallMs = 10000)),
        fake.host, fake.url).apply { lesson = "search"; lastCheckpointUrl = fake.url }
    private fun holdDirect(site: SiteModel, page: SemanticPageState, task: TaskLedger) {
        val direct = listOf(Step(StepKind.TYPE, Role.SEARCH_BOX, arg = "\$query", submit = true))
        FailedStrategies.record(site, FailedStrategies.key(page, "search", direct, mapOf("query" to task.goal.query)), false, now)
    }

    @Test fun rememberedSubmitFailureChoosesDistinctVisibleButtonProcedure() {
        val fake = FakeSite().apply { dialogShown = false }
        val page = SpsParser().parse(fake.observe(1000), now)
        val site = SiteModel(fake.host); val task = task(fake)
        holdDirect(site, page, task)
        val next = LocalRecoveryPlanner.propose(task, page, site, "search")!!
        assertEquals(listOf(StepKind.TYPE, StepKind.CLICK), next.steps.map { it.kind })
        assertFalse(next.steps.first().submit)
        assertEquals(Role.SUBMIT, next.steps.last().role)
        assertEquals(listOf(Postcondition.ValueIs("query", "\$query")), next.steps.first().expect)
    }

    @Test fun failedAlternativeStaysHeldAcrossReloadAndUnsafeButtonsAreNotTried() {
        val fake = FakeSite().apply { dialogShown = false }
        val page = SpsParser().parse(fake.observe(1000), now)
        val site = SiteModel(fake.host); val task = task(fake)
        holdDirect(site, page, task)
        val next = LocalRecoveryPlanner.propose(task, page, site, "search")!!
        FailedStrategies.record(site, FailedStrategies.key(page, "search", next.steps, next.params), false, now)
        val restored = SiteModel.fromJson(Json.parseObject(site.toJson().toString()))
        assertNull("neither failed procedure may be replayed", LocalRecoveryPlanner.propose(task, page, restored, "search"))
        val directOnly = SiteModel(fake.host); holdDirect(directOnly, page, task)
        for (unsafe in listOf(
            page.copy(affordances = page.affordances.filter { it.role != Role.SUBMIT }),
            page.copy(affordances = page.affordances.map { if (it.role == Role.SUBMIT) it.copy(effect = EffectClass.COMMIT_EXTERNAL) else it }),
            page.copy(affordances = page.affordances.map { if (it.role == Role.SUBMIT) it.copy(enabled = false) else it }),
            page.copy(affordances = page.affordances.map { if (it.role == Role.SUBMIT) it.copy(sameSite = false) else it }),
            page.copy(affordances = page.affordances + page.byRole(Role.SUBMIT).single().copy(id = "duplicate")),
            page.copy(authWall = true), page.copy(challenge = true), page.copy(dialogOpen = true), page.copy(settle = Settle.BUSY))) {
            // Relevant structural change can reopen the direct candidate; it must never expose an unsafe two-step candidate.
            val plan = LocalRecoveryPlanner.propose(task, unsafe, directOnly, "search")
            assertTrue(plan == null || plan.steps.none { it.role == Role.SUBMIT })
        }
    }


    @Test fun draftTypingAndIgnoredSubmitCannotEarnSearchCredit() {
        val fake = FakeSite().apply { dialogShown = false; navigate("https://fake.market/search?q=Toyota", 1000) }
        var draft = "Toyota"; var clicks = 0
        val renderer = object : Renderer by fake {
            override fun observe(timeoutMs: Long): String {
                val raw = Json.parseObject(fake.observe(timeoutMs))
                raw.optArray("elements")!!.objects().first { it.optString("type") == "search" }.put("value", draft)
                return raw.toString()
            }
            override fun act(command: JsonObject, timeoutMs: Long): RendererResult {
                if (command.optString("cmd") == "type") { draft = command.optString("text"); return RendererResult(true) }
                if (command.optString("cmd") == "click") { clicks++; return RendererResult(true, "ignored button") }
                return fake.act(command, timeoutMs)
            }
        }
        val storage = InMemoryStorage(); val memory = Memory(storage) { now }
        val page = SpsParser().parse(renderer.observe(1000), now)
        val site = memory.site(fake.host); val ledger = task(fake)
        val builtin = memory.skills.get("search")!!
        FailedStrategies.record(site, FailedStrategies.key(page, "search", builtin.body, mapOf("query" to "Ford")), false, now)
        holdDirect(site, page, ledger); memory.saveSite(site)
        val out = BrainEngine(renderer, memory, { null }, object : EngineEvents {}, config) { now }.runTask(ledger, EngineMode.TRAIN)
        assertEquals("the distinct candidate must actually be tried", 1, clicks)
        assertTrue("draft input is not submitted search evidence", "search" !in out.successfulSkills)
        assertNotEquals(TaskStatus.DONE, out.status)
        assertTrue(memory.skills.all().none { it.origin == SkillOrigin.COMPILED })
        assertEquals("Toyota", fake.query)
    }

    @Test fun realEngineLearnsAlternativeAndDoesNotRepeatBrokenSubmitAfterReload() {
        val storage = InMemoryStorage(); var brokenSubmits = 0
        val outcomes = mutableListOf<TaskLedger>()
        repeat(3) { index ->
            val fake = FakeSite().apply { dialogShown = false }
            val renderer = object : Renderer by fake {
                override fun act(command: JsonObject, timeoutMs: Long): RendererResult {
                    if (command.optString("cmd") == "type" && command.optBoolean("submit")) {
                        brokenSubmits++; return RendererResult(true, "synthetic ignored Enter")
                    }
                    return fake.act(command, timeoutMs)
                }
            }
            val memory = Memory(storage) { now + index * 1000L }
            val out = BrainEngine(renderer, memory, { error("teacher is disabled") }, object : EngineEvents {}, config) { now + index * 1000L }
                .runTask(task(fake, "attempt_$index"), EngineMode.TRAIN)
            outcomes += out
            assertEquals("a changed approach must produce actual search results", TaskStatus.DONE, out.status)
            assertTrue("search" in out.successfulSkills)
            assertEquals(0, out.llmCalls)
            assertEquals("failed Enter must never repeat after memory reload", 1, brokenSubmits)
        }
        val restored = Memory(storage) { now + 10_000L }
        assertTrue("verified alternatives must be retained as reusable procedures", restored.skills.all().any {
            it.origin == SkillOrigin.COMPILED && "capability:search" in it.tags && it.body.any { step -> step.role == Role.SUBMIT }
        })
        assertTrue("retained execution should avoid the initial failed work", outcomes.last().actions < outcomes.first().actions)
    }
}
