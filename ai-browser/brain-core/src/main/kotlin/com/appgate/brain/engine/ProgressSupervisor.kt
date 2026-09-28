package com.appgate.brain.engine

import com.appgate.brain.model.*
import com.appgate.brain.util.Hashing

/** Bounded task-local evidence. Heartbeats and repeated successful cycles are not progress. */
object ProgressSupervisor {
    fun evidence(ledger: TaskLedger, key: String): Boolean {
        if (ledger.progressKeys.size >= 256 || !ledger.progressKeys.add(Hashing.short(key))) return false
        ledger.decisionsWithoutProgress = 0
        return true
    }

    fun permit(ledger: TaskLedger, sps: SemanticPageState, step: Step, limit: Int = 2): Boolean {
        val key = Hashing.short("${sps.hash}|${step.toJson()}")
        val count = ledger.actionStates[key] ?: 0
        ledger.actionStates[key] = count + 1
        return count < limit
    }

    fun terminal(ledger: TaskLedger, config: EngineConfig, elapsedMs: Long): String? = when {
        ledger.actions >= ledger.goal.budget.actions -> "action budget reached"
        elapsedMs >= ledger.goal.budget.wallMs -> "time budget reached"
        ledger.decisions >= (ledger.goal.budget.actions * 4).coerceIn(20, 1000) -> "decision budget reached"
        ledger.consecutiveFailures >= config.maxConsecutiveFailures -> "repeated failures"
        ledger.decisionsWithoutProgress >= config.maxDecisionsWithoutProgress -> "no new verified progress"
        else -> null
    }
}
