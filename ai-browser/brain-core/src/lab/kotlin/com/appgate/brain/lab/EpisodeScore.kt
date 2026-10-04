package com.appgate.brain.lab

import com.appgate.brain.json.JsonObject
import com.appgate.brain.model.*

data class EpisodeScore(
    val success: Boolean, val returnedMatches: Int, val expectedMatches: Int,
    val hardViolations: Int, val falseVerifiedClaims: Int, val rareClassificationErrors: Int, val unsafeActions: Int,
    val filterCorrect: Boolean, val recoveredFault: Boolean, val recoverySuccess: Boolean,
    val stuck: Boolean, val repeatedActions: Int, val actions: Int, val decisions: Int,
    val rendererCommands: Int
) {
    fun toJson(): JsonObject = JsonObject().put("success", success).put("returned_matches", returnedMatches)
        .put("expected_matches", expectedMatches).put("hard_violations", hardViolations)
        .put("false_verified_claims", falseVerifiedClaims).put("unsafe_actions", unsafeActions)
        .put("rare_classification_errors", rareClassificationErrors)
        .put("correct_filters", filterCorrect).put("recoverable_fault_injected", recoveredFault)
        .put("recovery_success", recoverySuccess).put("stuck", stuck).put("repeated_actions", repeatedActions)
        .put("actions", actions).put("decisions", decisions).put("renderer_commands", rendererCommands)
}

/** Uses catalog truth; never asks the engine/verifier whether its own answer was correct. */
object EpisodeScorer {
    fun score(scenario: Scenario, market: SimulationMarket, ledger: TaskLedger): EpisodeScore {
        val eligible = market.catalog.filter { it.vehicle == scenario.query && it.price <= 8000 && it.mileage <= 150000 }.map { it.key }.toSet()
        val offered = ledger.verdicts.values.filter { it.tier(ledger.goal) != ResultTier.NEAR_MISS }
        val returned = offered.map { it.itemKey }.toSet()
        val violations = offered.count { it.itemKey !in eligible }
        val falseClaims = ledger.verdicts.values.count { v ->
            val truth = market.catalog.firstOrNull { it.key == v.itemKey }
            truth == null || (v.perConstraint["axle_ratio"] == Verdict.SAT && truth.axle != "3.73") ||
                (v.perConstraint["price"] == Verdict.SAT && truth.price > 8000) ||
                (v.perConstraint["mileage"] == Verdict.SAT && truth.mileage > 150000)
        }
        val rareErrors = offered.count { v ->
            val truth = market.catalog.firstOrNull { it.key == v.itemKey }
            val permitted = when (truth?.axle) {
                null -> setOf(Verdict.UNKNOWN)
                "3.73" -> setOf(Verdict.SAT)
                // The existing text-evidence evaluator may conservatively leave a
                // different stated ratio unverified; it must never call it satisfied.
                else -> setOf(Verdict.UNKNOWN, Verdict.VIOLATED)
            }
            v.perConstraint["axle_ratio"] !in permitted
        }
        val filters = market.appliedFilters.all { (key, value) ->
            when (key) { "price_max" -> value == "8000"; "mileage_max" -> value == "150000"; else -> false }
        } && (scenario.permanentFailure || scenario.fault == SimulationFault.AUTH || scenario.emptyCatalog ||
            (market.appliedFilters["price_max"] == "8000" && (scenario.missingMileage || market.appliedFilters["mileage_max"] == "150000")))
        val bounded = (ledger.done || ledger.blocked) && ledger.actions <= ledger.goal.budget.actions &&
            ledger.decisions <= (ledger.goal.budget.actions * 4).coerceIn(20, 1000) && market.rendererCommands <= 160
        val terminalCorrect = when {
            scenario.fault == SimulationFault.AUTH -> ledger.status == TaskStatus.NEED_HUMAN && ledger.actions == 0
            scenario.permanentFailure -> ledger.status in setOf(TaskStatus.FAILED, TaskStatus.PARTIAL, TaskStatus.BUDGET_EXHAUSTED) && returned.isEmpty()
            scenario.emptyCatalog -> ledger.status == TaskStatus.PARTIAL && returned.isEmpty()
            else -> ledger.status == TaskStatus.DONE && eligible.isNotEmpty() && returned.containsAll(eligible) &&
                market.appliedQuery.equals(scenario.query, true)
        }
        val success = bounded && terminalCorrect && violations == 0 && falseClaims == 0 && rareErrors == 0 && market.unsafeActions == 0 && filters
        val recoverableInjected = scenario.recoverable && market.injectedFaults > 0
        val repetitions = ledger.steps.groupingBy { it.spsHashBefore + "|" + it.action }.eachCount().values.sumOf { (it - 1).coerceAtLeast(0) }
        return EpisodeScore(success, returned.count { it in eligible }, eligible.size, violations, falseClaims, rareErrors,
            market.unsafeActions, filters, recoverableInjected, recoverableInjected && success,
            !scenario.permanentFailure && scenario.fault != SimulationFault.AUTH && !scenario.emptyCatalog &&
                ledger.status in setOf(TaskStatus.FAILED, TaskStatus.PARTIAL, TaskStatus.BUDGET_EXHAUSTED),
            repetitions, ledger.actions, ledger.decisions, market.rendererCommands)
    }
}
