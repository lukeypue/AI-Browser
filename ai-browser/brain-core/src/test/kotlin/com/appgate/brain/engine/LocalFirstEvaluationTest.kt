package com.appgate.brain.engine

import com.appgate.brain.goal.GoalParser
import com.appgate.brain.json.Json
import com.appgate.brain.json.JsonArray
import com.appgate.brain.json.JsonObject
import com.appgate.brain.memory.InMemoryStorage
import com.appgate.brain.memory.Memory
import com.appgate.brain.model.*
import com.appgate.brain.planner.Planner
import com.appgate.brain.planner.PlannerClient
import com.appgate.brain.test.FakeSite
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Held-out semantic scenarios. Synthetic outcomes and counts are not live-site benchmarks. */
class LocalFirstEvaluationTest {
    private val now = 1_800_000_000_000L
    private val config = EngineConfig(pacingOverrideMs = 0, plannerCooldownMs = 0, ambiguousRecheckMs = 0, idleSleepMs = 0)

    @Test fun availableLearningMicrotasksFinishWithVerifiedOutcomesAndNoTeacher() {
        val rows = mutableListOf<Pair<TaskLedger, TeacherSpy>>()
        for (lesson in listOf("search", "open_item", "next_page")) {
            val fake = FakeSite("available-${lesson.replace('_', '-')}.market").apply { dialogShown = false }
            val renderer = EvaluationRenderer(fake)
            val teacher = TeacherSpy()
            val start = if (lesson == "search") fake.url else "https://${fake.host}/search?q=Ford+Expedition"
            // Detail practice must not ask a model to answer an arbitrary training text constraint.
            val task = learning("available_$lesson", fake.host, start, lesson, "Ford Expedition with a 3.73 axle")
            val out = engine(renderer, Memory(InMemoryStorage()) { now }, teacher).runTask(task)
            record("available_$lesson", out, teacher, "verified_microtask")
            rows += out to teacher
        }
        for ((out, teacher) in rows) {
            assertEquals(out.lesson, TaskStatus.DONE, out.status)
            assertTrue(out.lesson, out.lesson in out.successfulSkills)
            assertEquals("${out.lesson}: ledger model calls", 0, out.llmCalls)
            assertEquals("${out.lesson}: actual transport calls", 0, teacher.calls)
            assertTrue("${out.lesson}: bounded microtask, actions=${out.actions}", out.actions in 1..2)
        }
    }

    @Test fun absentPaginationRemainsPartialWithoutActionsOrTeacherRequests() {
        val fake = FakeSite("single-page-heldout.market").apply { dialogShown = false }
        val renderer = EvaluationRenderer(fake)
        val teacher = TeacherSpy()
        val task = learning("absent_next", fake.host, "https://${fake.host}/search?q=Toyota+Sequoia", "next_page", "Toyota Sequoia")
        val memory = Memory(InMemoryStorage()) { now }
        val out = engine(renderer, memory, teacher).runTask(task)
        record("absent_next", out, teacher, "unavailable_partial")
        assertEquals(TaskStatus.PARTIAL, out.status)
        assertEquals(0, out.actions)
        assertEquals(0, out.llmCalls)
        assertEquals(0, teacher.calls)
        assertFalse(out.successfulSkills.contains("next_page"))
        assertFalse(memory.site(fake.host).curriculum.firstOrNull { it.id == "next_page" }?.done == true)
    }

    @Test fun verifiedSourceProcedureTransfersToCompatibleHeldOutHostButNotMissingEntry() {
        val storage = InMemoryStorage()
        val memory = Memory(storage) { now }
        val source = FakeSite("practice-source.market").apply { dialogShown = false }
        // A supplied teacher program is rehearsed through the real engine/verifier three times.
        // No success counts are seeded and no paid transport is used to set up the evaluation.
        repeat(3) { index ->
            val teacher = TeacherSpy()
            val task = learning("source_$index", source.host, "https://${source.host}/", "search", "Ford Expedition")
            task.currentProgram = listOf(Step(StepKind.TYPE, Role.SEARCH_BOX, arg = task.goal.query,
                submit = true, expect = listOf(Postcondition.ResultsChanged)))
            task.programSource = "planner"
            task.programCapability = "search"
            task.programPost = listOf(Postcondition.ResultsChanged)
            val out = engine(EvaluationRenderer(source), memory, teacher).runTask(task)
            record("source_practice_${index + 1}", out, teacher, "supplied_program_verified")
            assertTrue("source rehearsal $index must verify search", "search" in out.successfulSkills)
        }
        val compiled = memory.skills.all().single { it.origin == SkillOrigin.COMPILED && "capability:search" in it.tags }
        assertEquals(3.0, compiled.stat(source.host).successes, 0.0)

        val target = FakeSite("compatible-heldout.market").apply { dialogShown = false }
        val teacher = TeacherSpy()
        val reloaded = Memory(storage) { now }
        val out = engine(EvaluationRenderer(target), reloaded, teacher).runTask(
            learning("heldout_transfer", target.host, target.url, "search", "Toyota Sequoia"))
        record("compatible_transfer", out, teacher, "verified_transfer")

        val incompatible = FakeSite("missing-entry-heldout.market").apply { dialogShown = false }
        val unavailableTeacher = TeacherSpy()
        val rejected = engine(EvaluationRenderer(incompatible, omitSearch = true), reloaded, unavailableTeacher).runTask(
            learning("incompatible_transfer", incompatible.host, "https://${incompatible.host}/item/6", "search", "Toyota Sequoia"))
        record("incompatible_transfer", rejected, unavailableTeacher, "rejected_transfer")

        assertEquals(TaskStatus.DONE, out.status)
        assertEquals(0, out.llmCalls)
        assertEquals(0, teacher.calls)
        assertTrue("the held-out run must execute the learned procedure", out.steps.any {
            it.source == "skill:${compiled.id}" && it.status == VerifyStatus.VERIFIED
        })
        assertEquals(1.0, reloaded.skills.get(compiled.id)!!.stat(target.host).successes, 0.0)

        assertEquals(TaskStatus.PARTIAL, rejected.status)
        assertEquals(0, rejected.actions)
        assertEquals(0, unavailableTeacher.calls)
        assertFalse(rejected.steps.any { it.source == "skill:${compiled.id}" })
        assertEquals(0.0, reloaded.skills.get(compiled.id)!!.stat(incompatible.host).n, 0.0)
    }

    @Test fun changedControlsAreRegroundedBeforeRetryAndRecoveryNeedsNoTeacher() {
        val fake = FakeSite("fresh-control-heldout.market").apply { dialogShown = false }
        val renderer = EvaluationRenderer(fake, replaceAfterFirstSearch = true)
        val teacher = TeacherSpy()
        val out = engine(renderer, Memory(InMemoryStorage()) { now }, teacher).runTask(
            learning("fresh_target_recovery", fake.host, fake.url, "search", "Ford Expedition"))
        record("fresh_target_recovery", out, teacher, "verified_fresh_target_recovery", renderer.staleTargets)
        assertEquals(TaskStatus.DONE, out.status)
        assertEquals(0, out.llmCalls)
        assertEquals(0, teacher.calls)
        assertTrue("a failed typed outcome is preserved", out.steps.any { it.status == VerifyStatus.FAILED })
        assertTrue("recovery verifies search", "search" in out.successfulSkills)
        assertEquals("only the initial control and freshly observed replacement may be acted on",
            listOf("initial_search", "replacement_search"), renderer.searchTargets)
        assertEquals(0, renderer.staleTargets)
    }

    private fun learning(id: String, host: String, start: String, lesson: String, query: String): TaskLedger =
        TaskLedger(id, GoalParser.parse(query, Budget(actions = 20, itemsInspected = 3, llmCalls = 3, wallMs = 10_000))
            .copy(intent = GoalIntent.LEARN_SITE), host, start).apply { this.lesson = lesson }

    private fun engine(renderer: Renderer, memory: Memory, teacher: TeacherSpy) =
        BrainEngine(renderer, memory, { teacher.planner }, object : EngineEvents {}, config) { now }

    private class TeacherSpy : PlannerClient {
        var calls = 0
        override val describe = "synthetic teacher spy"
        override fun complete(instructions: String, input: String, schemaName: String, schema: JsonObject, maxOutputTokens: Int): String {
            calls++
            return "{}"
        }
        val planner = Planner(this)
    }

    /** Different host/control IDs and optional document replacement; no parser or verifier stubs. */
    private class EvaluationRenderer(
        private val fake: FakeSite,
        private val replaceAfterFirstSearch: Boolean = false,
        private val omitSearch: Boolean = false
    ) : Renderer by fake {
        private var replaced = false
        private var mapping = emptyMap<String, String>()
        val searchTargets = mutableListOf<String>()
        var staleTargets = 0

        override fun observe(timeoutMs: Long): String {
            val raw = Json.parseObject(fake.observe(timeoutMs))
            val ids = linkedMapOf<String, String>()
            val elements = raw.optArray("elements")!!.objects().mapNotNull { original ->
                if (omitSearch && (original.optString("type") == "search" || original.optString("name") == "Search")) return@mapNotNull null
                val element = Json.parseObject(original.toString())
                val old = element.optString("id")
                val id = if (element.optString("type") == "search") {
                    if (replaced) "replacement_search" else "initial_search"
                } else "${if (replaced) "new" else "heldout"}_$old"
                ids[id] = old
                element.put("id", id)
                if (element.optString("type") == "search") element.put("name", "Find listings")
                element
            }.toMutableList()
            if (replaceAfterFirstSearch && !replaced) {
                val search = elements.first { it.optString("type") == "search" }
                elements += Json.parseObject(search.toString()).put("id", "obsolete_alternate")
                ids["obsolete_alternate"] = ids.getValue("initial_search")
            }
            raw.put("elements", JsonArray(elements))
            raw.optArray("items")?.objects()?.forEach { item ->
                val old = item.optString("aff")
                item.put("aff", ids.entries.firstOrNull { it.value == old }?.key ?: old)
            }
            raw.put("documentId", if (replaced) "replacement-document" else "initial-document")
            mapping = ids
            return raw.toString()
        }

        override fun act(command: JsonObject, timeoutMs: Long): RendererResult {
            val id = command.optString("id")
            if (command.optString("cmd") == "type") searchTargets += id
            if (replaceAfterFirstSearch && !replaced && command.optString("cmd") == "type") {
                replaced = true
                return RendererResult(true, "control changed without verified search results")
            }
            val translated = Json.parseObject(command.toString())
            if (id.isNotBlank()) {
                val old = mapping[id]
                if (old == null) { staleTargets++; return RendererResult(false, "STALE_TARGET") }
                translated.put("id", old)
            }
            return fake.act(translated, timeoutMs)
        }
    }

    private fun record(name: String, task: TaskLedger, teacher: TeacherSpy, expected: String, staleTargets: Int? = null) {
        reports[name] = JsonObject().put("scenario", name).put("expected_outcome", expected)
            .put("status", task.status.name).put("actions", task.actions).put("ledger_llm_calls", task.llmCalls)
            .put("actual_teacher_requests", teacher.calls).put("verified_steps", task.steps.count { it.status == VerifyStatus.VERIFIED })
            .put("failed_steps", task.steps.count { it.status == VerifyStatus.FAILED })
            .put("compiled_procedure_verified", task.steps.any { it.source.startsWith("skill:compiled_") && it.status == VerifyStatus.VERIFIED })
            .put("stale_targets_acted_on", staleTargets)
            .putStrings("verified_capabilities", task.successfulSkills.sorted())
        val file = File(System.getProperty("brain.localfirst.report", "build/reports/brain/local-first.json"))
        file.parentFile.mkdirs()
        file.writeText(JsonObject().put("environment", "synthetic held-out hosts and changed control IDs; real engine and verifier; no network or paid models")
            .put("source_setup", "supplied abstract search program, three engine-verified source rehearsals")
            .put("runs", JsonArray(reports.toSortedMap().values.toList())).toString())
    }

    companion object {
        private val reports = linkedMapOf<String, JsonObject>()
    }
}
