package com.appgate.brain.lab

import com.appgate.brain.engine.*
import com.appgate.brain.json.*
import com.appgate.brain.memory.*
import com.appgate.brain.model.*
import com.appgate.brain.perception.SpsParser
import com.appgate.brain.planner.*
import java.io.File
import java.security.MessageDigest

/**
 * Pure-JVM, synthetic-only acquisition screen. Never reads running Android memory,
 * contacts a model, changes curriculum, or imports skills into the app.
 */
class FrontierScreen {
    private val now = 1_800_000_000_000L
    private val config = EngineConfig(pacingOverrideMs = 0, plannerCooldownMs = 0,
        ambiguousRecheckMs = 0, idleSleepMs = 0, maxConsecutiveFailures = 8,
        maxDecisionsWithoutProgress = 40)

    private fun flag(row: JsonObject, key: String): Boolean =
        (row[key] as? JsonBool)?.value ?: error("missing or invalid boolean: " + key)
    private fun count(row: JsonObject, key: String): Int {
        val value = (row[key] as? JsonNumber)?.value ?: error("missing or invalid counter: " + key)
        require(value.isFinite() && value >= 0 && value <= 1_000_000 && value % 1 == 0.0)
        return value.toInt()
    }
    private data class Trial(val row: JsonObject, val snapshot: Map<String, String>)
    private fun snapshot(store: BrainStorage) = store.keys("").filterNot { it.startsWith("ledger/") }
        .associateWith { store.read(it)!! }.toSortedMap()
    private fun fork(directory: File, saved: Map<String, String>) = FileBrainStorage(directory).also { store ->
        require(store.keys("").isEmpty()) { "experiment output must be fresh" }
        saved.forEach { (key, value) -> store.write(key, value) }
    }

    /** The fixture oracle uses committed internal state, never the parser's inferred outcome. */
    private class SearchGate(val host: String, val variant: Int, val gated: Boolean = true,
        val noOp: Boolean = false, val human: Boolean = false, val submitNoOp: Boolean = false) : Renderer {
        var selected = false; private set
        var draft = ""; private set
        var committed = ""; private set
        private var mode = ""
        private var route = "/"
        val commands = mutableListOf<String>()
        private fun id(role: String) = "fixture-${variant}-${role}"
        override fun currentUrl() = "https://${host}${route}"
        override fun observe(timeoutMs: Long): String {
            fun control(key: String, tag: String, name: String) = JsonObject().put("id", id(key))
                .put("tag", tag).put("name", name).put("visible", true).put("enabled", true)
                .put("sameSite", true).put("region", if (variant % 2 == 0) "header" else "main")
                .put("bbox", JsonArray().add(10).add(30 + variant * 5).add(240).add(36))
            val controls = mutableListOf(
                control("query", "input", if (variant % 2 == 0) "Search inventory" else "Search listings")
                    .put("type", "search").put("value", draft).put("inForm", true).put("formHasSearch", true),
                control("submit", "button", "Search").put("submitType", true).put("inForm", true).put("formHasSearch", true),
                control("scope", if (variant % 2 == 0) "button" else "div", "Browse inventory")
                    .put("role", "tab").put("selected", selected)
            )
            if (variant % 3 == 1) controls.reverse()
            val items = JsonArray()
            if (committed.isNotBlank()) repeat(3) { i ->
                val key = "result-${digest(committed).take(12)}-${i}"
                controls += control("item-${i}", "a", "${committed} item ${i}")
                    .put("href", "https://${host}/item/${i}").put("itemKey", key).put("inCard", true)
                    .put("cardHasPrice", true).put("listSize", 3)
                items.add(JsonObject().put("key", key).put("title", "${committed} item ${i}")
                    .put("price", "$100").put("text", "${committed} $100")
                    .put("href", "https://${host}/item/${i}").put("aff", id("item-${i}")))
            }
            val signals = JsonObject().put("hasMain", true).put("textLength", 1200)
                .put("resultsHint", committed.isNotBlank()).put("priceCount", if (committed.isBlank()) 0 else 3)
                .put("passwordFields", if (human) 1 else 0).put("authPath", human)
            return JsonObject().put("v", 3).put("url", if (human) "https://${host}/login" else currentUrl())
                .put("host", host).put("title", if (human) "" else "Synthetic inventory")
                .put("readyState", "complete").put("viewport", JsonObject().put("w", 480).put("h", 800).put("scrollY", 0).put("scrollH", 800))
                .put("settle", JsonObject().put("state", "IDLE"))
                .put("signals", signals).put("regions", JsonArray()
                    .add(JsonObject().put("id", "header").put("role", "HEADER"))
                    .add(JsonObject().put("id", "main").put("role", "MAIN")))
                .put("elements", if (human) JsonArray() else JsonArray(controls))
                .put("items", items).put("detailText", "").put("scripts", JsonArray()).toString()
        }
        override fun act(command: JsonObject, timeoutMs: Long): RendererResult {
            if (mode != "TRAIN" || human) return RendererResult(false, "synthetic interlock")
            val target = command.optString("id")
            val kind = command.optString("cmd")
            commands += kind + ":" + when (target) { id("scope") -> "TAB"; id("query") -> "SEARCH_BOX"; id("submit") -> "SUBMIT"; else -> "OTHER" }
            when {
                target == id("scope") && kind == "click" -> if (!noOp) { selected = true; route = "/inventory" }
                target == id("query") && kind == "type" -> {
                    draft = command.optString("text")
                    if (command.optBoolean("submit") && (!gated || selected)) commit()
                }
                target == id("submit") && kind == "click" -> if (!gated || selected) commit()
                else -> return RendererResult(false, "unsupported synthetic action")
            }
            return RendererResult(true)
        }
        private fun commit() {
            if (submitNoOp) return
            committed = draft
            route = "/search?q=" + java.net.URLEncoder.encode(committed, "UTF-8")
        }
        override fun navigate(url: String, timeoutMs: Long): RendererResult {
            if (url == currentUrl()) return RendererResult(true)
            return RendererResult(false, "no synthetic navigation shortcut")
        }
        override fun back(timeoutMs: Long) = RendererResult(false)
        override fun waitSettle(timeoutMs: Long) = RendererResult(true)
        override fun isAlive() = true
        override fun recover() = RendererResult(true)
        override fun setNetworkMode(mode: String, allowlist: List<String>, commitEndpoints: List<String>): RendererResult {
            this.mode = mode; return RendererResult(mode == "TRAIN")
        }
        fun oracle(query: String) = committed == query && (!gated || selected)
    }

    private class RecordedTeacher(private val response: String?, private val capture: File? = null) : PlannerClient {
        var calls = 0; private set
        var requestSha256: String? = null; private set
        override val describe = if (capture != null) "synthetic-request-capture" else "recorded-synthetic-teacher"
        override fun complete(instructions: String, input: String, schemaName: String, schema: JsonObject, maxOutputTokens: Int): String {
            check(++calls <= 1) { "one teacher request per trial" }
            val request = JsonObject().put("environment", "SYNTHETIC_ONLY")
                .put("instructions", instructions).put("input", Json.parseObject(input))
                .put("schema_name", schemaName).put("schema", schema).put("max_output_tokens", maxOutputTokens).toString()
            requestSha256 = digest(request)
            capture?.writeText(request)
            return response ?: throw PlannerRefused("synthetic request captured; no response supplied")
        }
    }

    private fun trial(directory: File, saved: Map<String, String>, label: String, variant: Int,
        query: String, response: String? = null, capture: File? = null, removeSkills: Boolean = false,
        gated: Boolean = true, noOp: Boolean = false, human: Boolean = false, host: String = "${label}.sim.invalid",
        intent: GoalIntent = GoalIntent.LEARN_SITE, submitNoOp: Boolean = false): Trial {
        val store = fork(directory, saved)
        val memory = Memory(store) { now }
        if (removeSkills) memory.skills.all().filter { it.origin != SkillOrigin.BUILTIN }.forEach { memory.skills.remove(it.id) }
        val renderer = SearchGate(host, variant, gated, noOp, human, submitNoOp)
        val observed = SpsParser().parse(renderer.observe(1000), now)
        val observationGate = if (human) observed.isHumanOnly else observed.affordances.count { it.role == Role.TAB && it.visible && it.enabled && it.sameSite } == 1 &&
            observed.has(Role.SEARCH_BOX) && observed.has(Role.SUBMIT) && !observed.isHumanOnly
        val beforeLearned = memory.skills.all().filter { it.origin == SkillOrigin.COMPILED }.associate { it.id to it.stat(host).successes }
        val teacher = if (response != null || capture != null) RecordedTeacher(response, capture ?: File(directory.parentFile, "exchanges/" + directory.name + "-request.json").also { it.parentFile.mkdirs() }) else null
        val goal = Goal("frontier-${label}", intent, "synthetic search", query, emptyList(),
            budget = Budget(itemsInspected = 1, actions = 16, llmCalls = if (teacher == null) 0 else 1, wallMs = 8000))
        val task = TaskLedger(goal.id, goal, host, renderer.currentUrl()).apply { if (intent == GoalIntent.LEARN_SITE) lesson = "search" }
        val result = BrainEngine(renderer, memory, { teacher?.let { Planner(it) } }, object : EngineEvents {}, config) { now }.runTask(task, EngineMode.TRAIN)
        val oracle = renderer.oracle(query)
        val verified = "search" in result.successfulSkills
        val learned = memory.skills.all().filter { it.origin == SkillOrigin.COMPILED }
        val row = JsonObject().put("case_id", label).put("observation_gate", observationGate)
            .put("status", result.status.name).put("actions", result.actions).put("teacher_calls", teacher?.calls ?: 0)
            .put("oracle_success", oracle).put("verified_search", verified)
            .put("compiled_step_verified", result.steps.any { it.source.startsWith("skill:compiled_") && it.status == VerifyStatus.VERIFIED })
            .put("compiled_procedure_verified", learned.any { it.id in beforeLearned && it.stat(host).successes > beforeLearned.getValue(it.id) })
            .put("learned_host_successes", learned.sumOf { it.stat(host).successes })
            .put("learned_host_failures", learned.sumOf { it.stat(host).failures })
            .put("teacher_request_sha256", teacher?.requestSha256)
            .put("candidate_program_sha256", response?.let { digest(it) })
            .put("false_verified", verified && !oracle).put("commands", renderer.commands.size)
            .put("prerequisite_selected", renderer.selected).put("draft_present", renderer.draft.isNotBlank())
            .put("compiled_skills_after", memory.skills.all().count { it.origin == SkillOrigin.COMPILED })
        // Values and task-local notes stay out of the report and frozen procedural evidence.
        store.keys("ledger/").forEach(store::delete)
        return Trial(row, snapshot(store))
    }

    fun capture(directory: File, initialSaved: Map<String, String> = emptyMap()): JsonObject {
        require(!directory.exists() || directory.listFiles().isNullOrEmpty()) { "fresh experiment directory required" }
        directory.mkdirs()
        val out = trial(File(directory, "capture-memory"), initialSaved, "capture", 0, TRAIN_QUERY,
            capture = File(directory, "teacher-request.json"), host = TRAIN_HOST)
        return JsonObject().put("initial_memory_sha256", memoryHash(initialSaved)).put("source", "REAL_ENGINE_REQUEST").put("captured", File(directory, "teacher-request.json").exists())
            .put("trial", out.row)
    }

    fun run(directory: File): JsonObject = run(directory, referenceProgram(), "SCRIPTED_READINESS")
    fun run(directory: File, response: String, teacherKind: String, initialSaved: Map<String, String> = emptyMap()): JsonObject {
        require(teacherKind in setOf("SCRIPTED_READINESS", "RECORDED_EXTERNAL_CANDIDATE"))
        require(!directory.exists() || directory.listFiles().isNullOrEmpty()) { "fresh experiment directory required" }
        directory.mkdirs()
        val cold = trial(File(directory, "cold"), emptyMap(), "cold", 0, TRAIN_QUERY, host = TRAIN_HOST)
        val training = trial(File(directory, "training"), initialSaved, "training", 0, TRAIN_QUERY, response, host = TRAIN_HOST)
        val reinforce = trial(File(directory, "reinforce"), training.snapshot, "reinforce", 1, TRAIN_QUERY, host = TRAIN_HOST)
        val frozen = reinforce.snapshot
        val frozenStore = fork(File(directory, "frozen"), frozen)
        val skills = Memory(frozenStore) { now }.skills.all().filter { it.origin == SkillOrigin.COMPILED }
        val evidence = evaluate(directory, frozen)
        val solved = trial(File(directory, "solved-control"), emptyMap(), "solved-control", 2, "control-bike", gated = false)
        return evidence.put("schema_version", 1).put("environment", "PURE_JVM_SYNTHETIC_WIRE")
            .put("real_world_validated", false).put("teacher_kind", teacherKind)
            .put("training_teacher_calls", count(training.row, "teacher_calls"))
            .put("teacher_request_sha256", training.row.optStringOrNull("teacher_request_sha256"))
            .put("teacher_request_artifact", "exchanges/training-request.json")
            .put("candidate_program_sha256", training.row.optStringOrNull("candidate_program_sha256"))
            .put("compiled_procedures", JsonArray(skills.map { it.toJson() }))
            .put("training_oracle_success", flag(training.row, "oracle_success") && flag(training.row, "verified_search"))
            .put("cold_unsolved", !flag(cold.row, "oracle_success"))
            .put("compiled_skills", skills.size).put("initial_memory_sha256", memoryHash(initialSaved))
            .put("query_literals_absent", skills.none { TRAIN_QUERY in it.toJson().toString() })
            .put("frozen_memory_unchanged", frozen == snapshot(frozenStore))
            .put("solved_control_success", flag(solved.row, "oracle_success"))
            .put("cold", cold.row).put("training", training.row).put("reinforce", reinforce.row)
            .put("skill_body_hashes", JsonArray(skills.map { JsonString(digest(JsonArray(it.body.map { step -> step.toJson() }).toString())) }))
    }

    /** Every row gets an independent durable fork; evaluation never updates training memory. */
    fun evaluate(directory: File, frozen: Map<String, String>): JsonObject {
        val retained = (0..7).map { i -> trial(File(directory, "retained-${i}"), frozen, "heldout-${i}", i + 2, "heldout-bike-${i}") }
        val cold = (0..7).map { i -> trial(File(directory, "baseline-${i}"), emptyMap(), "heldout-${i}", i + 2, "heldout-bike-${i}") }
        val ablated = (0..7).map { i -> trial(File(directory, "ablated-${i}"), frozen, "heldout-${i}", i + 2, "heldout-bike-${i}", removeSkills = true) }
        val negative = trial(File(directory, "no-op"), frozen, "no-op", 5, "negative-bike", noOp = true)
        val human = trial(File(directory, "human"), frozen, "human", 6, "negative-bike", human = true)
        val ordinaryRetained = (0..7).map { i -> trial(File(directory, "ordinary-retained-${i}"), frozen, "ordinary-${i}", i + 2, "ordinary-bike-${i}", intent = GoalIntent.FIND_LISTINGS) }
        val ordinaryCold = (0..7).map { i -> trial(File(directory, "ordinary-cold-${i}"), emptyMap(), "ordinary-${i}", i + 2, "ordinary-bike-${i}", intent = GoalIntent.FIND_LISTINGS) }
        val ordinaryAblated = (0..7).map { i -> trial(File(directory, "ordinary-ablated-${i}"), frozen, "ordinary-${i}", i + 2, "ordinary-bike-${i}", intent = GoalIntent.FIND_LISTINGS, removeSkills = true) }
        val draft = trial(File(directory, "draft-without-submit"), emptyMap(), "draft-without-submit", 3, "draft-bike", response = referenceProgram(), submitNoOp = true)
        val all = retained + cold + ablated + ordinaryRetained + ordinaryCold + ordinaryAblated + listOf(negative, human)
        fun successes(rows: List<Trial>) = rows.count { flag(it.row, "oracle_success") && flag(it.row, "verified_search") }
        return JsonObject().put("retained_successes", successes(retained)).put("cold_successes", successes(cold))
            .put("ablation_successes", successes(ablated)).put("evaluation_teacher_calls", all.sumOf { count(it.row, "teacher_calls") })
            .put("false_verified_claims", (all + draft).count { flag(it.row, "false_verified") })
            .put("negative_oracle_successes", if (flag(negative.row, "oracle_success")) 1 else 0)
            .put("negative_verified_searches", if (flag(negative.row, "verified_search")) 1 else 0)
            .put("human_actions", count(human.row, "actions")).put("human_teacher_calls", count(human.row, "teacher_calls"))
            .put("all_observation_gates_passed", (all + draft).all { flag(it.row, "observation_gate") })
            .put("ordinary_outcome_scope", "VERIFIED_SEARCH_ONLY")
            .put("ordinary_retained_successes", successes(ordinaryRetained))
            .put("ordinary_cold_successes", successes(ordinaryCold))
            .put("ordinary_ablation_successes", successes(ordinaryAblated))
            .put("ordinary_retained", JsonArray(ordinaryRetained.map { it.row }))
            .put("ordinary_cold", JsonArray(ordinaryCold.map { it.row }))
            .put("ordinary_ablated", JsonArray(ordinaryAblated.map { it.row }))
            .put("draft_negative_kind", "SCRIPTED_NEGATIVE_STUB")
            .put("draft_prerequisite_selected", flag(draft.row, "prerequisite_selected"))
            .put("draft_present", flag(draft.row, "draft_present"))
            .put("draft_oracle_success", flag(draft.row, "oracle_success"))
            .put("draft_verified_search", flag(draft.row, "verified_search"))
            .put("draft_compiled_skills", count(draft.row, "compiled_skills_after"))
            .put("frozen_memory_sha256", memoryHash(frozen))
            .put("procedure_hashes", JsonArray(procedureHashes(frozen).map { JsonString(it) }))
            .put("retained", JsonArray(retained.map { it.row })).put("cold_rows", JsonArray(cold.map { it.row }))
            .put("ablated", JsonArray(ablated.map { it.row })).put("negative", negative.row).put("human", human.row)
    }

    companion object {
        fun memoryHash(saved: Map<String, String>) = digest(saved.toSortedMap().entries.joinToString("\n") { it.key + "\u0000" + it.value })
        fun procedureHashes(saved: Map<String, String>): List<String> {
            val store = InMemoryStorage().also { st -> saved.forEach { (k, v) -> st.write(k, v) } }
            return Memory(store) { 1_800_000_000_000L }.skills.all().filter { it.origin == SkillOrigin.COMPILED }.sortedBy { it.id }.map { skill ->
                digest(JsonObject().put("body", JsonArray(skill.body.map { it.toJson() }))
                    .put("post", JsonArray(skill.post.map { it.toJson() }))
                    .put("pre", JsonArray(skill.pre.map { it.toJson() })).putStrings("params", skill.params).toString())
            }
        }
        const val TRAIN_QUERY = "training-canary-bike"
        const val TRAIN_HOST = "training-frontier.sim.invalid"
        fun digest(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
        fun referenceProgram(): String {
            fun step(kind: String, role: String, arg: String?, expect: List<String>) = JsonObject()
                .put("kind", kind).put("role", role).put("facet_key", null as String?).put("arg", arg)
                .put("name_hint", null as String?).put("optional", false).put("submit", false).putStrings("expect", expect)
            return JsonObject().put("steps", JsonArray()
                .add(step("CLICK", "TAB", null, listOf("URL_CHANGED")))
                .add(step("TYPE", "SEARCH_BOX", "\$query", emptyList()))
                .add(step("CLICK", "SUBMIT", null, listOf("RESULTS_CHANGED", "PAGE_IS_RESULTS"))))
                .put("confidence", 0.95).put("needs_human", null as String?).put("give_up", false)
                .put("rationale", "scripted readiness reference").put("vocabulary", JsonArray()).toString()
        }
    }
}
