package com.appgate.brain.model

import com.appgate.brain.json.JsonArray
import com.appgate.brain.json.JsonObject

enum class TaskStatus { PENDING, RUNNING, NEED_HUMAN, NEED_GRANT, DONE, FAILED, ABANDONED, PAUSED, PARTIAL, BUDGET_EXHAUSTED }

enum class TaskPhase { START, SEARCH, CONSTRAIN, COLLECT, INSPECT, PREPARE_MESSAGE, PREVIEW, DONE }

/** One recorded step for the task's own history (never long-term memory). */
data class LedgerStep(
    val at: Long,
    val phase: TaskPhase,
    val spsHashBefore: String,
    val action: String,
    val status: VerifyStatus?,
    val evidence: String,
    val source: String                   // skill:<id> | planner | recovery | human
) {
    fun toJson(): JsonObject = JsonObject().put("at", at).put("phase", phase.name).put("sps", spsHashBefore).put("action", action)
        .put("status", status?.name).put("evidence", evidence.take(300)).put("source", source)

    companion object {
        fun fromJson(o: JsonObject) = LedgerStep(
            o.optLong("at"), TaskPhase.values().firstOrNull { it.name == o.optString("phase") } ?: TaskPhase.START,
            o.optString("sps"), o.optString("action"),
            o.optStringOrNull("status")?.let { s -> VerifyStatus.values().firstOrNull { it.name == s } },
            o.optString("evidence"), o.optString("source")
        )
    }
}

/**
 * The task ledger survives renderer death and process death. It is persisted before every
 * action and after every verification, so a crash at any instant loses at most one step.
 */
data class TaskLedger(
    val id: String,
    val goal: Goal,
    val host: String,
    val startUrl: String,
    var status: TaskStatus = TaskStatus.PENDING,
    var phase: TaskPhase = TaskPhase.START,
    var cursor: Int = 0,
    var actions: Int = 0,
    var llmCalls: Int = 0,
    var plannerCallsOnSameState: Int = 0,
    var lastSpsHash: String = "",
    var consecutiveFailures: Int = 0,
    var startedAt: Long = 0L,
    var updatedAt: Long = 0L,
    var lastCheckpointUrl: String = "",
    var humanReason: String = "",
    val steps: MutableList<LedgerStep> = mutableListOf(),
    val visited: MutableSet<String> = linkedSetOf(),
    val verdicts: MutableMap<String, ItemVerdict> = linkedMapOf(),
    val grants: MutableList<Grant> = mutableListOf(),
    val appliedConstraints: MutableSet<String> = linkedSetOf(),
    val notes: MutableList<String> = mutableListOf(),
    var previewText: String = "",
    var previewHash: String = "",
    var currentProgram: List<Step> = emptyList(),
    var programSource: String = "",
    var scrollRoundsWithoutNew: Int = 0,
    var pagesVisited: Int = 0,
    var inspectQueue: MutableList<String> = mutableListOf(),
    val constraintAttempts: MutableMap<String, Int> = linkedMapOf(),
    var usedSearchUrl: Boolean = false,
    var searchAttempts: Int = 0,
    var resultsUrl: String = "",
    var currentItem: String? = null,
    var itemsInspected: Int = 0,
    var plannerFailures: Int = 0,
    var timeouts: Int = 0,
    var decisions: Int = 0,
    var decisionsWithoutProgress: Int = 0,
    var elapsedMs: Long = 0L,
    var terminalReason: String = "",
    var lastPlannerStateHash: String = "",
    val progressKeys: MutableSet<String> = linkedSetOf(),
    val actionStates: MutableMap<String, Int> = linkedMapOf(),
    val attemptedSkills: MutableSet<String> = linkedSetOf(),
    val successfulSkills: MutableSet<String> = linkedSetOf(),
    var programPage: PageType? = null,
    var programCapability: String = "",
    var programPost: List<Postcondition> = emptyList(),
    val programVerifiedSteps: MutableSet<Int> = linkedSetOf(),
    var programCompleted: Boolean = false,
    var lesson: String = "",
    var repairReason: String = ""
) {
    /** One late-discovered practice target. Values are task-local and never serialized. */
    var learningConstraints: List<Constraint> = emptyList()
        set(value) {
            field = if (goal.intent == GoalIntent.LEARN_SITE) value.mapNotNull { learningConstraint(it.key, it.op, it.value) }.take(1) else emptyList()
        }

    /** Recover an already-bound resumed intent from typed expectations, never from page text. */
    val effectiveGoal: Goal get() {
        if (goal.intent != GoalIntent.LEARN_SITE) return goal
        fun fromPost(post: Postcondition, depth: Int = 0): List<Constraint> = when {
            depth > 2 -> emptyList()
            post is Postcondition.AnyOf -> post.alternatives.take(8).flatMap { fromPost(it, depth + 1) }
            post is Postcondition.ConstraintApplied && post.value != null -> listOfNotNull(learningConstraint(post.key, null, post.value))
            post is Postcondition.ValueIs -> listOfNotNull(learningConstraint(post.facetKey, null, post.value))
            else -> emptyList()
        }
        val bindings = learningConstraints.ifEmpty { programPost.take(8).flatMap { fromPost(it) }.distinct().take(1) }
        return if (bindings.isEmpty()) goal else goal.copy(constraints = goal.constraints.filterNot { original ->
            bindings.any { it.key == original.key && it.op == original.op }
        } + bindings)
    }

    val done: Boolean get() = status in setOf(TaskStatus.DONE, TaskStatus.FAILED, TaskStatus.ABANDONED, TaskStatus.PARTIAL, TaskStatus.BUDGET_EXHAUSTED)
    val blocked: Boolean get() = status == TaskStatus.NEED_HUMAN || status == TaskStatus.NEED_GRANT || status == TaskStatus.PAUSED

    fun record(step: LedgerStep) {
        steps += step
        while (steps.size > 400) steps.removeAt(0)
        updatedAt = step.at
    }

    fun note(text: String) {
        if (notes.size < 60) notes += text
    }

    fun toJson(): JsonObject = JsonObject()
        .put("id", id).put("goal", goal.toJson()).put("host", host).put("start_url", startUrl)
        .put("status", status.name).put("phase", phase.name).put("cursor", cursor).put("actions", actions)
        .put("llm", llmCalls).put("planner_same", plannerCallsOnSameState).put("last_sps", lastSpsHash)
        .put("fails", consecutiveFailures).put("started", startedAt).put("updated", updatedAt)
        .put("checkpoint", lastCheckpointUrl).put("human_reason", humanReason)
        .put("steps", JsonArray(steps.map { it.toJson() }))
        .putStrings("visited", visited)
        .put("verdicts", JsonArray(verdicts.values.map { it.toJson() }))
        .put("grants", JsonArray(grants.map { it.toJson() }))
        .putStrings("applied", appliedConstraints)
        .putStrings("notes", notes)
        .put("preview", previewText).put("preview_hash", previewHash)
        .put("program", JsonArray(currentProgram.map { it.toJson() })).put("program_source", programSource)
        .put("scroll_no_new", scrollRoundsWithoutNew).put("pages", pagesVisited)
        .putStrings("inspect_queue", inspectQueue)
        .put("attempts", JsonObject().also { j -> constraintAttempts.forEach { (k, v) -> j.put(k, v) } })
        .put("used_search_url", usedSearchUrl).put("search_attempts", searchAttempts).put("results_url", resultsUrl)
        .put("current_item", currentItem).put("inspected", itemsInspected).put("planner_failures", plannerFailures).put("timeouts", timeouts)
        .put("decisions", decisions).put("no_progress", decisionsWithoutProgress).put("elapsed_ms", elapsedMs)
        .put("terminal_reason", terminalReason).put("planner_state", lastPlannerStateHash)
        .putStrings("progress_keys", progressKeys).putStrings("attempted_skills", attemptedSkills).putStrings("successful_skills", successfulSkills)
        .put("action_states", JsonObject().also { j -> actionStates.forEach { (k, v) -> j.put(k, v) } })
        .put("program_page", programPage?.name).put("program_capability", programCapability)
        .put("program_post", JsonArray(programPost.map { it.toJson() }))
        .putStrings("program_verified", programVerifiedSteps.map { it.toString() }).put("program_completed", programCompleted).put("lesson", lesson)
        .put("repair_reason", repairReason)

    companion object {
        private val learningNumeric = setOf("price", "mileage", "year", "distance", "bedrooms", "bathrooms")
        private val learningChoice = setOf("make", "model", "condition", "fuel", "transmission", "body_style", "drivetrain", "color", "sort")

        private fun learningConstraint(key: String, operation: ConstraintOp?, value: String): Constraint? {
            if (value.isBlank() || value.length > 120 || value.startsWith("$")) return null
            val base = key.removeSuffix("_min").removeSuffix("_max")
            val op = operation ?: when { key.endsWith("_min") -> ConstraintOp.GTE; key.endsWith("_max") -> ConstraintOp.LTE; else -> ConstraintOp.EQ }
            if (base in learningNumeric) {
                if (op !in setOf(ConstraintOp.GTE, ConstraintOp.LTE) || value.replace(",", "").toDoubleOrNull()?.isFinite() != true) return null
            } else if (base !in learningChoice || key != base || op != ConstraintOp.EQ) return null
            return Constraint(base, op, value, sources = setOf(ConstraintSource.FILTERABLE))
        }

        fun fromJson(o: JsonObject): TaskLedger {
            val ledger = TaskLedger(
                id = o.optString("id"),
                goal = Goal.fromJson(o.optObject("goal") ?: JsonObject()),
                host = o.optString("host"),
                startUrl = o.optString("start_url")
            )
            ledger.status = TaskStatus.values().firstOrNull { it.name == o.optString("status") } ?: TaskStatus.PENDING
            ledger.phase = TaskPhase.values().firstOrNull { it.name == o.optString("phase") } ?: TaskPhase.START
            ledger.cursor = o.optInt("cursor")
            ledger.actions = o.optInt("actions")
            ledger.llmCalls = o.optInt("llm")
            ledger.plannerCallsOnSameState = o.optInt("planner_same")
            ledger.lastSpsHash = o.optString("last_sps")
            ledger.consecutiveFailures = o.optInt("fails")
            ledger.startedAt = o.optLong("started")
            ledger.updatedAt = o.optLong("updated")
            ledger.lastCheckpointUrl = o.optString("checkpoint")
            ledger.humanReason = o.optString("human_reason")
            o.optArray("steps")?.objects()?.forEach { ledger.steps += LedgerStep.fromJson(it) }
            ledger.visited += o.optStrings("visited")
            o.optArray("verdicts")?.objects()?.forEach { v ->
                val verdict = verdictFromJson(v)
                ledger.verdicts[verdict.itemKey] = verdict
            }
            o.optArray("grants")?.objects()?.forEach { g ->
                ledger.grants += Grant(g.optString("id"), g.optString("task"), g.optString("host"), Role.parse(g.optStringOrNull("role")),
                    g.optString("preview"), g.optLong("issued"), g.optLong("ttl", 120_000L), g.optBoolean("used"))
            }
            ledger.appliedConstraints += o.optStrings("applied")
            ledger.notes += o.optStrings("notes")
            ledger.previewText = o.optString("preview")
            ledger.previewHash = o.optString("preview_hash")
            ledger.currentProgram = o.optArray("program")?.objects()?.map { Step.fromJson(it) } ?: emptyList()
            ledger.programSource = o.optString("program_source")
            ledger.scrollRoundsWithoutNew = o.optInt("scroll_no_new")
            ledger.pagesVisited = o.optInt("pages")
            ledger.inspectQueue = o.optStrings("inspect_queue").toMutableList()
            o.optObject("attempts")?.entries()?.forEach { (k, v) -> ledger.constraintAttempts[k] = v.asDoubleOrNull()?.toInt() ?: 0 }
            ledger.usedSearchUrl = o.optBoolean("used_search_url")
            ledger.searchAttempts = o.optInt("search_attempts")
            ledger.resultsUrl = o.optString("results_url")
            ledger.currentItem = o.optStringOrNull("current_item")
            ledger.itemsInspected = o.optInt("inspected")
            ledger.plannerFailures = o.optInt("planner_failures")
            ledger.timeouts = o.optInt("timeouts")
            ledger.decisions = o.optInt("decisions")
            ledger.decisionsWithoutProgress = o.optInt("no_progress")
            ledger.elapsedMs = o.optLong("elapsed_ms")
            ledger.terminalReason = o.optString("terminal_reason")
            ledger.lastPlannerStateHash = o.optString("planner_state")
            ledger.progressKeys += o.optStrings("progress_keys")
            ledger.attemptedSkills += o.optStrings("attempted_skills")
            ledger.successfulSkills += o.optStrings("successful_skills")
            o.optObject("action_states")?.entries()?.forEach { (k, v) -> ledger.actionStates[k] = v.asDoubleOrNull()?.toInt() ?: 0 }
            ledger.programPage = o.optStringOrNull("program_page")?.let { PageType.parse(it) }
            ledger.programCapability = o.optString("program_capability")
            ledger.programPost = o.optArray("program_post")?.objects()?.mapNotNull { Postcondition.fromJson(it) }.orEmpty()
            ledger.programVerifiedSteps += o.optStrings("program_verified").mapNotNull { it.toIntOrNull() }
            ledger.programCompleted = o.optBoolean("program_completed")
            ledger.lesson = o.optString("lesson")
            ledger.repairReason = o.optString("repair_reason")
            return ledger
        }

        fun verdictFromJson(v: JsonObject): ItemVerdict = ItemVerdict(
            itemKey = v.optString("key"),
            title = v.optString("title"),
            hrefPath = v.optStringOrNull("path"),
            url = v.optStringOrNull("url"),
            price = if (v.has("price")) v.optInt("price") else null,
            mileage = if (v.has("mileage")) v.optInt("mileage") else null,
            year = if (v.has("year")) v.optInt("year") else null,
            perConstraint = v.optObject("verdicts")?.entries()?.mapNotNull { (k, x) ->
                x.asStringOrNull()?.let { s -> Verdict.values().firstOrNull { it.name == s } }?.let { k to it }
            }?.toMap() ?: emptyMap(),
            evidence = v.optArray("evidence")?.objects()?.map { Evidence(it.optString("key"), it.optString("span"), it.optString("method")) } ?: emptyList(),
            inspectedDetail = v.optBoolean("inspected"),
            softScore = v.optDouble("soft")
        )
    }
}
