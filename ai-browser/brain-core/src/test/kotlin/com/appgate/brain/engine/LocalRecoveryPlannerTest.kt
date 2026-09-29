package com.appgate.brain.engine

import com.appgate.brain.model.*
import org.junit.Assert.*
import org.junit.Test

class LocalRecoveryPlannerTest {
    private val host = "fixture.market"
    private fun ledger(vararg constraints: Constraint) = TaskLedger("repair", Goal("g", GoalIntent.FIND_LISTINGS, "find bicycle", "bicycle", constraints.toList()), host, "https://$host/")
    private fun page(vararg controls: Affordance, type: PageType = PageType.RESULTS, dialog: Boolean = false) =
        SemanticPageState(host, "https://$host/search", "/search", "", type, 1.0, emptyList(), controls.toList(), emptyList(), emptyMap(), Settle.IDLE, false, false, dialog, "fixture")
    private fun propose(task: TaskLedger, sps: SemanticPageState, capability: String) = LocalRecoveryPlanner.propose(task, sps, SiteModel(host), capability)
    private fun price(value: String = "8000") = Constraint("price", ConstraintOp.LTE, value)
    private fun numeric(id: String = "price") = Affordance(id, Role.FACET, "price_max", "numeric_max", "Maximum price", "input", inputType = "number")
    private fun choice(options: List<String>) = Affordance("condition", Role.FACET, "condition", "choice", "Condition", "select", choices = options)

    @Test fun exactNumericFieldProducesBoundedValueAndApplyPlan() {
        val plan = propose(ledger(price()), page(numeric(), Affordance("apply", Role.FACET_APPLY, name = "Apply", tag = "button"), type = PageType.FACET_PANEL, dialog = true), "constrain_numeric")!!
        assertEquals(listOf(StepKind.SET_RANGE, StepKind.CLICK), plan.steps.map { it.kind })
        assertEquals(mapOf("key" to "price_max", "value" to "8000"), plan.params)
        assertTrue(plan.postconditions.contains(Postcondition.ConstraintApplied("\$key", "\$value")))
        assertTrue(plan.postconditions.contains(Postcondition.DialogClosed))
        assertTrue(plan.postconditions.contains(Postcondition.PageTypeIs(PageType.RESULTS)))
        assertTrue(plan.completesCapability)
    }

    @Test fun exactLiveChoiceIsRequiredRatherThanNearestUnrelatedValue() {
        val task = ledger(Constraint("condition", ConstraintOp.EQ, "used"))
        assertNull(propose(task, page(choice(listOf("new", "certified used"))), "select_facet"))
        val sps = page(choice(listOf("new", " Used ")))
        val plan = propose(task, sps, "select_facet")!!
        val grounded = StepGrounder(null).ground(plan.steps.first(), plan.params, sps, emptySet()) as GroundingOutcome.Ready
        assertEquals(" Used ", grounded.grounded.action.text)
        assertEquals("used", plan.params["value"])
    }

    @Test fun numericSelectRequiresTheRequestedBoundToBeOffered() {
        val select = numeric().copy(tag = "select", choices = listOf("5000", "10000"))
        assertNull(propose(ledger(price()), page(select), "constrain_numeric"))
        assertNotNull(propose(ledger(price()), page(select.copy(choices = listOf("5000", "8,000"))), "constrain_numeric"))
    }

    @Test fun anUnseenDrawerChoiceOnlyProducesDiscoveryNotClaimedCompletion() {
        val task = ledger(Constraint("condition", ConstraintOp.EQ, "used"))
        val sps = page(Affordance("filters", Role.FACET_OPEN, name = "Filters", tag = "button"))
        val plan = propose(task, sps, "select_facet")!!
        assertEquals(1, plan.steps.size)
        assertEquals(Role.FACET_OPEN, plan.steps.single().role)
        assertFalse(plan.completesCapability)
        assertTrue(plan.postconditions.none { it is Postcondition.ConstraintApplied })
    }

    @Test fun ambiguousUnsafeAndUnrelatedOpenersAreNotExplored() {
        val opener = Affordance("one", Role.FACET_OPEN, name = "Filters", tag = "button")
        val task = ledger(price())
        assertNull(propose(task, page(opener, opener.copy(id = "two")), "constrain_numeric"))
        assertNull(propose(task, page(opener.copy(effect = EffectClass.COMMIT_EXTERNAL)), "constrain_numeric"))
        assertNull(propose(task, page(opener.copy(role = Role.GENERIC_BUTTON)), "constrain_numeric"))
        assertNull(propose(task, page(opener.copy(facetKey = "mileage")), "constrain_numeric"))
    }

    @Test fun aPendingExactValueCanBeAppliedWithoutTypingAgain() {
        val sps = page(numeric().copy(value = "8000"), Affordance("apply", Role.FACET_APPLY, tag = "button"), type = PageType.FACET_PANEL, dialog = true)
        val plan = propose(ledger(price()), sps, "apply_filters")!!
        assertEquals(listOf(Role.FACET_APPLY), plan.steps.map { it.role })
        assertTrue(plan.postconditions.contains(Postcondition.ConstraintApplied("\$key", "\$value")))
        assertNull(propose(ledger(price()), sps.copy(affordances = sps.affordances.map { if (it.role == Role.FACET) it.copy(value = "10000") else it }), "apply_filters"))
    }

    @Test fun unsafeOrAmbiguousFacetAndApplyControlsAreRejected() {
        val field = numeric()
        val apply = Affordance("apply", Role.FACET_APPLY, tag = "button")
        val task = ledger(price())
        assertNull(propose(task, page(field, field.copy(id = "duplicate")), "constrain_numeric"))
        assertNull(propose(task, page(field.copy(effect = EffectClass.COMMIT_EXTERNAL)), "constrain_numeric"))
        assertNull(propose(task, page(field, apply, apply.copy(id = "duplicate")), "constrain_numeric"))
        assertNull(propose(task, page(field, apply.copy(effect = EffectClass.COMMIT_EXTERNAL)), "constrain_numeric"))
    }

    @Test fun searchUsesOnlyAUniqueSafeLiveSearchBoxAndGoalQuery() {
        val search = Affordance("query", Role.SEARCH_BOX, tag = "input")
        val task = ledger()
        val plan = propose(task, page(search, type = PageType.HOME), "search")!!
        assertEquals("bicycle", plan.params["query"])
        assertEquals(Role.SEARCH_BOX, plan.steps.single().role)
        assertTrue(plan.steps.single().submit)
        assertNull(propose(task, page(search, search.copy(id = "another")), "search"))
        assertNull(propose(task, page(search.copy(effect = EffectClass.COMMIT_EXTERNAL)), "search"))
        assertNull(propose(task, page(search, type = PageType.CHALLENGE), "search"))
        assertNull(propose(task, page(search).copy(host = "foreign.market"), "search"))
    }

    @Test fun sortUsesTheExplicitGoalAndRejectsUnknownOrders() {
        val task = ledger(Constraint("sort", ConstraintOp.EQ, "Price ascending"))
        val sort = Affordance("sort", Role.SORT, tag = "select", choices = listOf("Newest", "Price ascending"))
        val plan = propose(task, page(sort), "sort_results")!!
        assertEquals("Price ascending", plan.params["order"])
        assertNull(propose(task, page(sort.copy(choices = listOf("Newest"))), "sort_results"))
        assertNull(propose(ledger(), page(sort), "sort_results"))
    }

    @Test fun proposalDoesNotMutateLedgerOrSite() {
        val task = ledger(price())
        val site = SiteModel(host)
        val beforeTask = task.toJson().toString()
        val beforeSite = site.toJson().toString()
        LocalRecoveryPlanner.propose(task, page(numeric()), site, "constrain_numeric")
        assertEquals(beforeTask, task.toJson().toString())
        assertEquals(beforeSite, site.toJson().toString())
    }

    @Test fun ordinaryWwwAliasRetainsLocalRecoveryWhileOtherHostsAreRejected() {
        val task = ledger(price())
        val sps = page(numeric()).copy(host = "www.$host", url = "https://www.$host/search")
        assertNotNull(propose(task, sps, "constrain_numeric"))
        assertNull(propose(task, sps.copy(host = "other.$host"), "constrain_numeric"))
    }
}
