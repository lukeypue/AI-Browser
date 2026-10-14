package com.appgate.brain.lab

import com.appgate.brain.model.Role
import com.appgate.brain.model.TaskPhase
import com.appgate.brain.model.TaskStatus
import com.appgate.brain.engine.PolicyDecision
import com.appgate.brain.engine.TaskPolicy
import com.appgate.brain.model.SiteModel
import com.appgate.brain.model.TaskLedger
import com.appgate.brain.profile.SiteProfiles
import com.appgate.brain.perception.SpsParser
import org.junit.Assert.*
import org.junit.Test

class PaginationCoverageTest {
    @Test fun nextPageKeepsCollectingEligibleCardsWithinTheExistingBudget() {
        val trainer = SimulationTrainer()
        val memory = trainer.train((1..3).map { Scenario(-500 - it, twoStepSearch = true) })
        val scenario = SimulationTrainer.cityCases().single { it.seed == 30050 }
        val run = trainer.episode(scenario, memory)
        val eligible = run.market.catalog.filter { it.vehicle == scenario.query && it.price <= 8000 && it.mileage <= 150000 }
        val filtered = run.market.catalog.filter { it.vehicle == scenario.query && it.price <= 8000 }
        val missed = eligible.filter { it.key !in run.task.verdicts }.map { filtered.indexOf(it) + 1 }
        // The engine finishes on a detail page. Inspect its last collection checkpoint,
        // rather than mistaking the lack of pagination on a detail page for exhaustion.
        run.market.navigate(run.task.resultsUrl, 1000)
        val remainingNext = SpsParser().parse(run.market.observe(1000)).has(Role.PAGE_NEXT)
        println("seed30050: pagesVisited=${run.task.pagesVisited}, itemsInspected=${run.task.itemsInspected}, remainingNext=$remainingNext, missedMatchPages=$missed, actions=${run.task.actions}, status=${run.task.status}, score=${run.score.toJson()}")
        assertTrue("Missing eligible cards despite remaining pagination: $missed; ${run.score.toJson()}", run.score.success)
        assertTrue(run.task.actions <= scenario.goal().budget.actions)
        assertEquals(0, run.score.hardViolations)
        assertEquals(0, run.score.falseVerifiedClaims)
        assertEquals(0, run.score.unsafeActions)
    }

    @Test fun paginationStillStopsAtTheExplicitActionBudget() {
        val scenario = SimulationTrainer.cityCases().single { it.seed == 30050 }
        val run = SimulationTrainer().episode(scenario.copy(twoStepSearch = false), emptyMap())
        run.market.navigate(run.task.resultsUrl, 1000)
        val page = SpsParser().parse(run.market.observe(1000))
        val goal = scenario.goal()
        val ledger = run.task.copy(goal = goal).apply {
            phase = TaskPhase.COLLECT
            actions = goal.budget.actions
        }
        val decision = TaskPolicy(SiteProfiles.generic(scenario.host), SiteModel(scenario.host)).decide(goal, page, ledger)
        assertEquals(TaskStatus.BUDGET_EXHAUSTED, (decision as PolicyDecision.Finish).status)
    }

    @Test fun choosingNextPageDoesNotEraseObservedNoProgress() {
        val scenario = Scenario(36, pageSize = 1)
        val market = SimulationMarket(scenario)
        market.navigate("https://${scenario.host}/search?q=Toyota+Sequoia", 1000)
        val page = SpsParser().parse(market.observe(1000))
        assertTrue(page.has(Role.PAGE_NEXT))
        val goal = scenario.goal().copy(query = "", constraints = emptyList())
        val ledger = TaskLedger("no-progress", goal, scenario.host, market.currentUrl()).apply {
            phase = TaskPhase.COLLECT
            scrollRoundsWithoutNew = 1
        }
        val decision = TaskPolicy(SiteProfiles.generic(scenario.host), SiteModel(scenario.host)).decide(goal, page, ledger)
        assertEquals("next_page", (decision as PolicyDecision.RunSkill).skillId)
        assertEquals("Only observed new result keys may clear no-progress history", 1, ledger.scrollRoundsWithoutNew)
        ledger.scrollRoundsWithoutNew = 2
        val stopped = TaskPolicy(SiteProfiles.generic(scenario.host), SiteModel(scenario.host)).decide(goal, page, ledger)
        assertEquals(TaskStatus.PARTIAL, (stopped as PolicyDecision.Finish).status)
    }
}
