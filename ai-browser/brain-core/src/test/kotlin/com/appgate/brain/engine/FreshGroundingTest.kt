package com.appgate.brain.engine

import com.appgate.brain.model.*
import org.junit.Assert.*
import org.junit.Test

class FreshGroundingTest {
    private fun page(vararg controls: Affordance) = SemanticPageState("fixture.market", "https://fixture.market/", "/", "", PageType.RESULTS, 1.0,
        emptyList(), controls.toList(), emptyList(), emptyMap(), Settle.IDLE, false, false, false, "fresh")

    @Test fun excludedTargetIsNotReusedAndFreshAttributesArePreserved() {
        val first = Affordance("tried", Role.FACET_APPLY, name = "Apply", tag = "button")
        val fresh = Affordance("replacement", Role.FACET_APPLY, "condition", name = "Apply filters", tag = "button", effect = EffectClass.COMMIT_EXTERNAL, itemKey = "current")
        val result = StepGrounder(null).ground(Step(StepKind.CLICK, Role.FACET_APPLY), emptyMap(), page(first, fresh), emptySet(), excludedIds = setOf("tried")) as GroundingOutcome.Ready
        assertEquals("replacement", result.grounded.action.target!!.affordanceId)
        assertEquals("condition", result.grounded.action.target!!.facetKey)
        assertEquals("current", result.grounded.action.target!!.itemKey)
        assertEquals(EffectClass.COMMIT_EXTERNAL, result.grounded.action.effect)
        assertTrue(result.grounded.alternates.none { it.affordance.id == "tried" })
    }

    @Test fun disappearanceReturnsMissingRatherThanOldCandidate() {
        val sps = page(Affordance("tried", Role.FACET_APPLY, tag = "button"))
        assertTrue(StepGrounder(null).ground(Step(StepKind.CLICK, Role.FACET_APPLY), emptyMap(), sps, emptySet(), excludedIds = setOf("tried")) is GroundingOutcome.Missing)
    }

    @Test fun submitAndDismissCannotDowngradeCurrentCommitClassification() {
        val query = Affordance("query", Role.SEARCH_BOX, tag = "input", effect = EffectClass.COMMIT_EXTERNAL)
        val type = StepGrounder(null).ground(Step(StepKind.TYPE, Role.SEARCH_BOX, arg = "bicycle", submit = true), emptyMap(), page(query), emptySet()) as GroundingOutcome.Ready
        assertEquals(EffectClass.COMMIT_EXTERNAL, type.grounded.action.effect)
        val close = Affordance("close", Role.CLOSE, tag = "button", effect = EffectClass.COMMIT_EXTERNAL)
        val dismiss = StepGrounder(null).ground(Step(StepKind.DISMISS, Role.CLOSE), emptyMap(), page(close), emptySet()) as GroundingOutcome.Ready
        assertEquals(EffectClass.COMMIT_EXTERNAL, dismiss.grounded.action.effect)
    }

    @Test fun strictGroundingRejectsOppositeBoundAndFallbackRole() {
        val minimum = Affordance("min", Role.FACET, "price_min", tag = "input", inputType = "number")
        val step = Step(StepKind.SET_RANGE, Role.FACET, "price_max", "8000")
        assertTrue(StepGrounder(null).ground(step, emptyMap(), page(minimum), emptySet(), strict = true) is GroundingOutcome.Missing)
        val generic = Affordance("button", Role.GENERIC_BUTTON, name = "Apply", tag = "button", roleScore = 1.0)
        assertTrue(StepGrounder(null).ground(Step(StepKind.CLICK, Role.FACET_APPLY, nameHint = "Apply"), emptyMap(), page(generic), emptySet(), strict = true) is GroundingOutcome.Missing)
    }

    @Test fun strictGroundingRequiresResolvedFacetAndExactItemIdentity() {
        val field = Affordance("max", Role.FACET, "price_max", tag = "input")
        assertTrue(StepGrounder(null).ground(Step(StepKind.SET_RANGE, Role.FACET, "\$missing", "8000"), emptyMap(), page(field), emptySet(), strict = true) is GroundingOutcome.Missing)
        val result = Affordance("item", Role.RESULT_ITEM, tag = "a", itemKey = "wanted")
        val sps = page(result).copy(collections = listOf(Collection("results", listOf("wanted"), 1)))
        assertTrue(StepGrounder(null).ground(Step(StepKind.CLICK, Role.RESULT_ITEM), emptyMap(), sps, emptySet(), strict = true) is GroundingOutcome.Missing)
        val ready = StepGrounder(null).ground(Step(StepKind.CLICK, Role.RESULT_ITEM, arg = "wanted"), emptyMap(), sps, emptySet(), strict = true) as GroundingOutcome.Ready
        assertEquals("wanted", ready.grounded.action.target!!.itemKey)
    }

    @Test fun strictGroundingRejectsFreshUnsafeOrOffsiteTargets() {
        val apply = Affordance("apply", Role.FACET_APPLY, tag = "button")
        val step = Step(StepKind.CLICK, Role.FACET_APPLY)
        assertTrue(StepGrounder(null).ground(step, emptyMap(), page(apply.copy(effect = EffectClass.COMMIT_EXTERNAL)), emptySet(), strict = true) is GroundingOutcome.Missing)
        assertTrue(StepGrounder(null).ground(step, emptyMap(), page(apply.copy(sameSite = false)), emptySet(), strict = true) is GroundingOutcome.Missing)
    }
}
