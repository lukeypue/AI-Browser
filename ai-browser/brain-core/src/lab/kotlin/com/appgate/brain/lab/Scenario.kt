package com.appgate.brain.lab

import com.appgate.brain.goal.GoalParser
import com.appgate.brain.model.Budget
import com.appgate.brain.model.Goal

enum class SimulationFault { NONE, STALE_ONCE, LOADING_ONCE, NOOP_SEARCH, BLANK_ALWAYS, ERROR_ALWAYS, AUTH, LOADING_ALWAYS }

/** Seed and truth exist only in the environment and scorer, never in observations. */
enum class SimulationPagination { NEXT_PAGE, LOAD_MORE }

data class Scenario(
    val seed: Int,
    val level: Int = 1,
    val host: String = "practice.sim.invalid",
    val twoStepSearch: Boolean = false,
    val missingMileage: Boolean = false,
    val missingRareEvidence: Boolean = false,
    val fault: SimulationFault = SimulationFault.NONE,
    val emptyCatalog: Boolean = false,
    val catalogSize: Int = 8,
    val pageSize: Int = 2,
    val pagination: SimulationPagination = SimulationPagination.NEXT_PAGE,
    val queryVariant: Int = 0
) {
    init { require(level in 1..9); require(host.endsWith(".sim.invalid")); require(catalogSize in 8..128); require(pageSize in 1..8); require(queryVariant in 0..3) }
    val id: String get() = "$host/$seed/$level/$fault/$twoStepSearch/$missingMileage/$missingRareEvidence/$emptyCatalog/$catalogSize/$pageSize/$pagination/$queryVariant"
    val query: String get() = listOf("Toyota Sequoia", "Ford Expedition", "Chevrolet Tahoe", "Nissan Armada")[Math.floorMod(seed, 2) + (queryVariant % 2) * 2]
    fun goal(): Goal = GoalParser.parse("$query under 8000 under 150000 miles with a 3.73 axle",
        Budget(itemsInspected = 8, actions = 60, llmCalls = 0, wallMs = 5000))
    val permanentFailure: Boolean get() = fault in setOf(SimulationFault.NOOP_SEARCH,
        SimulationFault.BLANK_ALWAYS, SimulationFault.ERROR_ALWAYS, SimulationFault.LOADING_ALWAYS)
    val recoverable: Boolean get() = fault in setOf(SimulationFault.STALE_ONCE, SimulationFault.LOADING_ONCE)
}

data class SimListing(val key: String, val vehicle: String, val price: Int, val mileage: Int, val axle: String?)
