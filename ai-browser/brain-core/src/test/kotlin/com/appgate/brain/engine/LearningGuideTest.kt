package com.appgate.brain.engine

import com.appgate.brain.model.*
import org.junit.Assert.*
import org.junit.Test

class LearningGuideTest {
    private fun ledger() = TaskLedger("guide", Goal("goal", GoalIntent.LEARN_SITE, "", "cars", emptyList()), "example.com", "https://example.com").apply { lesson = "constrain_numeric" }
    @Test fun goalAndBoundExpectationAreVisibleBeforeAction() {
        val guide = LearningGuide.snapshot(ledger(), Step(StepKind.TYPE, Role.FACET, "price_max", "8000", expect = listOf(Postcondition.ValueIs("price_max", "8000"))))
        assertTrue(guide.attempt.contains("8000"))
        assertTrue(guide.pass.contains("8000"))
        assertTrue(guide.goal.contains("numeric"))
        assertEquals("Not checked yet.", guide.outcome)
    }
    @Test fun alternativeChecksRemainAlternatives() {
        val text = LearningGuide.condition(Postcondition.AnyOf(listOf(Postcondition.NewResults, Postcondition.EndOfResults)))
        assertTrue(text.contains(" OR "))
        assertTrue(text.contains("end"))
    }
    @Test fun ambiguousIsNotCalledAFailureOrPass() {
        val guide = LearningGuide.snapshot(ledger(), status = VerifyStatus.AMBIGUOUS)
        assertTrue(guide.outcome.startsWith("Not verified"))
        assertFalse(guide.outcome.startsWith("Passed"))
    }
    @Test fun missingTargetExplainsPerceptionLimit() {
        val guide = LearningGuide.snapshot(ledger(), status = VerifyStatus.FAILED, reason = "no_target")
        assertTrue(guide.outcome.contains("find"))
        assertFalse(guide.outcome.contains("learned"))
    }
    @Test fun verifiedActionDoesNotClaimLessonCompleted() {
        val guide = LearningGuide.snapshot(ledger(), status = VerifyStatus.VERIFIED)
        assertTrue(guide.outcome.contains("step"))
        assertFalse(guide.outcome.contains("lesson completed"))
    }
    @Test fun unspecifiedCheckIsExplicit() {
        val guide = LearningGuide.snapshot(ledger(), Step(StepKind.CLICK, Role.FACET_OPEN))
        assertTrue(guide.pass.contains("not supplied"))
    }
}
