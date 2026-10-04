package com.appgate.brain.lab

import com.appgate.brain.goal.GoalParser
import com.appgate.brain.model.Budget
import com.appgate.brain.model.Goal

enum class SimulationFault { NONE, STALE_ONCE, LOADING_ONCE, NOOP_SEARCH, BLANK_ALWAYS, ERROR_ALWAYS, AUTH, LOADING_ALWAYS }

/** Seed and truth exist only in the environment and scorer, never in observations. */
data class Scenario(
    val seed: Int,
    val level: Int = 1,
    val host: String = "practice.sim.invalid",
    val twoStepSearch: Boolean = false,
    val missingMileage: Boolean = false,
    val missingRareEvidence: Boolean = false,
    val fault: SimulationFault = SimulationFault.NONE,
    val emptyCatalog: Boolean = false
) {
    init { require(level in 1..9); require(host.endsWith(".sim.invalid")) }
    val id: String get() = "$host/$seed/$level/$fault/$twoStepSearch/$missingMileage/$missingRareEvidence/$emptyCatalog"
    val query: String get() = if (seed % 2 == 0) "Toyota Sequoia" else "Ford Expedition"
    fun goal(): Goal = GoalParser.parse("$query under 8000 under 150000 miles with a 3.73 axle",
        Budget(itemsInspected = 8, actions = 60, llmCalls = 0, wallMs = 5000))
    val permanentFailure: Boolean get() = fault in setOf(SimulationFault.NOOP_SEARCH,
        SimulationFault.BLANK_ALWAYS, SimulationFault.ERROR_ALWAYS, SimulationFault.LOADING_ALWAYS)
    val recoverable: Boolean get() = fault in setOf(SimulationFault.STALE_ONCE, SimulationFault.LOADING_ONCE)
}

data class SimListing(val key: String, val vehicle: String, val price: Int, val mileage: Int, val axle: String?)
