package com.appgate.brain.lab

import com.appgate.brain.engine.*
import com.appgate.brain.json.*
import com.appgate.brain.memory.*
import com.appgate.brain.model.*
import com.appgate.brain.perception.SpsParser
import com.appgate.brain.util.Hashing
import java.security.MessageDigest

data class SimulationRun(val scenario: Scenario, val market: SimulationMarket, val task: TaskLedger,
    val score: EpisodeScore, val actualTeacherRequests: Int = 0, val teacherDemonstrations: Int = 0) {
    fun toJson(): JsonObject = score.toJson().put("scenario", scenario.id).put("level", scenario.level)
        .put("status", task.status.name).put("terminal_reason", task.terminalReason)
        .put("teacher_requests", actualTeacherRequests).put("teacher_demonstrations", teacherDemonstrations)
        .put("external_api_cost_usd", 0).put("external_input_tokens", 0).put("external_output_tokens", 0)
        .put("compiled_procedure_used", task.steps.any { it.source.startsWith("skill:compiled_") && it.status == VerifyStatus.VERIFIED })
}

class SimulationTrainer {
    private val config = EngineConfig(pacingOverrideMs = 0, plannerCooldownMs = 0,
        ambiguousRecheckMs = 0, idleSleepMs = 0)
    private fun storage(snapshot: Map<String, String>) = InMemoryStorage().apply { snapshot.forEach { (k,v) -> write(k,v) } }

    /** Every evaluation case receives its own writable fork. Evaluation cannot change the source snapshot. */
    fun episode(scenario: Scenario, snapshot: Map<String, String>): SimulationRun = run(scenario, storage(snapshot), false)
    fun evaluate(scenarios: List<Scenario>, snapshot: Map<String, String>): List<SimulationRun> = scenarios.map { episode(it, snapshot) }

    fun train(scenarios: List<Scenario>): Map<String, String> = training(scenarios).first
    private fun training(scenarios: List<Scenario>): Pair<Map<String, String>, List<SimulationRun>> {
        val store = InMemoryStorage()
        val rows = scenarios.map { scenario ->
            val run = run(scenario, store, true)
            // Ledgers include task-local values. Only synthetic procedural and site evidence survives between episodes.
            store.keys("ledger/").forEach(store::delete)
            run
        }
        return store.snapshot().toMap() to rows
    }

    private fun progressiveTraining(count: Int): Triple<Map<String, String>, List<SimulationRun>, Int> {
        val store = InMemoryStorage()
        val curriculum = SimulationCurriculum()
        val rows = (1..count).map { index ->
            val level = curriculum.level
            val scenario = Scenario(-10000 - index, level, twoStepSearch = true,
                missingMileage = level >= 5, missingRareEvidence = level >= 5,
                fault = if (level >= 8) SimulationFault.STALE_ONCE else if (level >= 6) SimulationFault.LOADING_ONCE else SimulationFault.NONE)
            val row = run(scenario, store, true)
            curriculum.record(row.market.appliedQuery == scenario.query && "search" in row.task.successfulSkills,
                row.market.unsafeActions, row.score.falseVerifiedClaims)
            store.keys("ledger/").forEach(store::delete)
            row
        }
        return Triple(store.snapshot().toMap(), rows, curriculum.level)
    }

    private fun run(scenario: Scenario, store: InMemoryStorage, training: Boolean): SimulationRun {
        val market = SimulationMarket(scenario)
        val memory = Memory(store) { NOW }
        val goal = scenario.goal().let { if (training) it.copy(intent = GoalIntent.LEARN_SITE) else it }
        val task = TaskLedger(Hashing.short(scenario.id), goal, scenario.host, market.currentUrl())
        var demos = 0
        if (training) {
            task.lesson = "search"
            // Teacher receives only the visible semantic page and requested query. No oracle/fault state.
            val observation = SpsParser().parse(market.observe(1000))
            val alreadyLearned = memory.skills.all().any { it.origin == SkillOrigin.COMPILED &&
                "capability:search" in it.tags && it.stat(scenario.host).successes >= 2 }
            if (!alreadyLearned && !observation.isHumanOnly && !observation.dialogOpen && observation.has(Role.SEARCH_BOX) && observation.has(Role.SUBMIT)) {
                task.currentProgram = listOf(
                    Step(StepKind.TYPE, Role.SEARCH_BOX, arg = goal.query, submit = false,
                        expect = listOf(Postcondition.ValueIs("query", goal.query))),
                    Step(StepKind.CLICK, Role.SUBMIT, expect = listOf(Postcondition.PageTypeIs(PageType.RESULTS))))
                task.programSource = "planner"
                task.programCapability = "search"
                task.programPost = listOf(Postcondition.PageTypeIs(PageType.RESULTS))
                demos = 1
            }
        }
        val engine = BrainEngine(market, memory, { null }, object : EngineEvents {}, config) { NOW }
        val out = engine.runTask(task)
        return SimulationRun(scenario, market, out, EpisodeScorer.score(scenario, market, out), teacherDemonstrations = demos)
    }

    fun experiment(trainingEpisodes: Int = 1200): JsonObject {
        require(trainingEpisodes in 10..10000)
        // Known developer-owned fixtures, hidden from the student/training loop; not a secure external benchmark.
        val fixed = evaluationCases("practice.sim.invalid", 1000)
        val unseen = evaluationCases("unseen.sim.invalid", 2000)
        val baseline = evaluate(fixed, emptyMap())
        val unseenBaseline = evaluate(unseen, emptyMap())
        val (snapshot, training, achievedLevel) = progressiveTraining(trainingEpisodes)
        val trainingCases = training.map { it.scenario }
        require((fixed + unseen).none { e -> trainingCases.any { it.id == e.id || it.seed == e.seed } })
        val after = evaluate(fixed, snapshot)
        val transfer = evaluate(unseen, snapshot)
        fun manifest(cases: List<Scenario>) = MessageDigest.getInstance("SHA-256")
            .digest(cases.joinToString("\n") { it.id }.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return JsonObject().put("schema_version", 1).put("environment", "offline stateful marketplace wire format; real Kotlin engine/parser/verifier; no browser or external model")
            .put("learning", "verified parameterized procedural memory; no foundation-model weight training")
            .put("evaluation_isolation", "each case starts from a fresh fork of the frozen pre/post memory; no evaluation writes enter training")
            .put("real_world_validated", false).put("synthetic_memory_imported_to_android", false)
            .put("manifest", JsonObject().put("fixed_sha256", manifest(fixed)).put("unseen_sha256", manifest(unseen))
                .put("training_sha256", manifest(trainingCases)).put("training_episodes", trainingCases.size))
            .put("baseline", summary(baseline)).put("after_training", summary(after))
            .put("unseen_baseline", summary(unseenBaseline)).put("unseen_after_training", summary(transfer))
            .put("training_teacher_demonstrations", training.sumOf { it.teacherDemonstrations })
            .put("search_curriculum_level", achievedLevel)
            .put("curriculum_scope", "search skill only; levels are synthetic stress buckets, not proof of marketplace mastery")
            .put("training_successful_search_episodes", training.count { it.market.appliedQuery == it.scenario.query && "search" in it.task.successfulSkills })
            .put("training_levels", JsonArray(training.map { Json.wrap(it.scenario.level) }))
            .put("compiled_skills", Memory(storage(snapshot)) { NOW }.skills.all().count { it.origin == SkillOrigin.COMPILED })
            .put("fixed_pairs", JsonArray(baseline.zip(after).map { (a,b) -> JsonObject().put("before", a.toJson()).put("after", b.toJson()) }))
            .put("unseen_pairs", JsonArray(unseenBaseline.zip(transfer).map { (a,b) -> JsonObject().put("before", a.toJson()).put("after", b.toJson()) }))
    }

    companion object {
        const val NOW = 1_800_000_000_000L
        fun evaluationCases(host: String, seedOffset: Int): List<Scenario> = (1..9).flatMap { level ->
            listOf(Scenario(seedOffset + level * 10 + 1, level, host, missingMileage = level >= 5,
                    missingRareEvidence = level >= 5, fault = if (level >= 6) SimulationFault.STALE_ONCE else SimulationFault.NONE),
                Scenario(seedOffset + level * 10 + 2, level, host, twoStepSearch = true,
                    missingMileage = level >= 5, fault = if (level >= 8) SimulationFault.LOADING_ONCE else SimulationFault.NONE))
        } + listOf(SimulationFault.NOOP_SEARCH, SimulationFault.BLANK_ALWAYS, SimulationFault.ERROR_ALWAYS,
            SimulationFault.AUTH, SimulationFault.LOADING_ALWAYS).map { Scenario(seedOffset + 200 + it.ordinal, 8, host, fault = it) } +
            Scenario(seedOffset + 300, 5, host, emptyCatalog = true)

        fun summary(rows: List<SimulationRun>): JsonObject {
            val recovery = rows.filter { it.score.recoveredFault }
            val tasks = rows.filter { !it.scenario.permanentFailure && it.scenario.fault != SimulationFault.AUTH && !it.scenario.emptyCatalog }
            return JsonObject().put("episodes", rows.size).put("oracle_success_rate", rows.count { it.score.success }.toDouble() / rows.size.coerceAtLeast(1))
                .put("search_tasks", tasks.size).put("search_task_success_rate", tasks.count { it.score.success }.toDouble() / tasks.size.coerceAtLeast(1))
                .put("average_actions", rows.map { it.task.actions }.average()).put("average_renderer_commands", rows.map { it.market.rendererCommands }.average())
                .put("stuck_rate", rows.count { it.score.stuck }.toDouble() / rows.size.coerceAtLeast(1))
                .put("recovery_trials", recovery.size).put("recovery_rate", if (recovery.isEmpty()) null else recovery.count { it.score.recoverySuccess }.toDouble() / recovery.size)
                .put("false_verified_claims", rows.sumOf { it.score.falseVerifiedClaims }).put("hard_violations", rows.sumOf { it.score.hardViolations })
                .put("rare_classification_errors", rows.sumOf { it.score.rareClassificationErrors })
                .put("partial_result_recall", if (tasks.isEmpty()) null else tasks.sumOf { it.score.returnedMatches }.toDouble() / tasks.sumOf { it.score.expectedMatches }.coerceAtLeast(1))
                .put("unsafe_actions", rows.sumOf { it.score.unsafeActions }).put("repeated_actions", rows.sumOf { it.score.repeatedActions })
                .put("teacher_requests", rows.sumOf { it.actualTeacherRequests }).put("external_api_cost_usd", 0)
        }
    }
}
