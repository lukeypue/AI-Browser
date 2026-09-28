package com.appgate.brain.goal

import com.appgate.brain.model.ItemSummary
import com.appgate.brain.model.ResultTier
import com.appgate.brain.model.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConstraintEvaluatorTest {
    private val goal = GoalParser.parse("Ford Expedition under 8000 under 150000 miles with a 3.73 axle")

    private fun card(title: String, price: Int?, mileage: Int?, text: String = "") =
        ItemSummary(key = title.hashCode().toString(), affordanceId = null, title = title, price = price, mileage = mileage, year = null, hrefPath = "/listing/1", snippet = text)

    @Test
    fun cardVerdictsUseStructuredFieldsAndLeaveRareUnknown() {
        val v = ConstraintEvaluator.fromCard(goal, card("2008 Ford Expedition Eddie Bauer", 7500, 142000), "cars.ksl.com")
        assertEquals(Verdict.SAT, v.perConstraint["price"])
        assertEquals(Verdict.SAT, v.perConstraint["mileage"])
        assertEquals(Verdict.SAT, v.perConstraint["make"])
        assertEquals(Verdict.SAT, v.perConstraint["model"])
        assertEquals(Verdict.UNKNOWN, v.perConstraint["axle_ratio"])
        assertEquals(ResultTier.PARTIAL, v.tier(goal))
        assertTrue(ConstraintEvaluator.needsDetail(goal, v))
    }

    @Test
    fun priceViolationIsNearMissWithinTenPercent() {
        val v = ConstraintEvaluator.fromCard(goal, card("2011 Ford Expedition XLT", 8400, 120000), "cars.ksl.com")
        assertEquals(Verdict.VIOLATED, v.perConstraint["price"])
        assertEquals(ResultTier.NEAR_MISS, v.tier(goal))
        assertTrue(ConstraintEvaluator.isNearMiss(goal, v))
        assertFalse(ConstraintEvaluator.needsDetail(goal, v))
        val far = ConstraintEvaluator.fromCard(goal, card("2011 Ford Expedition XLT", 12000, 120000), "cars.ksl.com")
        assertFalse(ConstraintEvaluator.isNearMiss(goal, far))
    }

    @Test
    fun detailTextProvesRareConstraintWithEvidence() {
        val v0 = ConstraintEvaluator.fromCard(goal, card("2012 Ford Expedition King Ranch", 7950, 121500), "cars.ksl.com")
        val v1 = ConstraintEvaluator.withDetail(goal, v0, "Well maintained. New tires, 3.73 gears, tow package, heated leather seats. Clean title.", "https://cars.ksl.com/listing/1004")
        assertEquals(Verdict.SAT, v1.perConstraint["axle_ratio"])
        assertTrue(v1.evidence.any { it.key == "axle_ratio" && it.span.contains("3.73") })
        assertEquals(ResultTier.VERIFIED, v1.tier(goal))
        assertTrue(v1.inspectedDetail)
    }

    @Test
    fun missingMentionStaysUnknownNotViolated() {
        val v0 = ConstraintEvaluator.fromCard(goal, card("2012 Ford Expedition King Ranch", 7950, 121500), "cars.ksl.com")
        val v1 = ConstraintEvaluator.withDetail(goal, v0, "Great truck, runs strong, cold AC, clean title.", null)
        assertEquals(Verdict.UNKNOWN, v1.perConstraint["axle_ratio"])
        assertEquals(ResultTier.PARTIAL, v1.tier(goal))
    }

    @Test
    fun summaryProducesThreeListsAndNotes() {
        val a = ConstraintEvaluator.withDetail(goal, ConstraintEvaluator.fromCard(goal, card("2012 Ford Expedition King Ranch", 7950, 121500), "h"), "3.73 axle ratio, tow package", null)
        val b = ConstraintEvaluator.withDetail(goal, ConstraintEvaluator.fromCard(goal, card("2008 Ford Expedition", 7500, 142000), "h"), "runs great", null)
        val c = ConstraintEvaluator.fromCard(goal, card("2011 Ford Expedition XLT", 8400, 120000), "h")
        val d = ConstraintEvaluator.fromCard(goal, card("2005 Chevy Tahoe", 3000, 210000), "h")
        val r = ConstraintEvaluator.summarize(goal, listOf(a, b, c, d), 2, emptyList(), "DONE")
        assertEquals(1, r.verified.size)
        assertEquals(1, r.partial.size)
        assertEquals(1, r.nearMiss.size)
        assertTrue(r.notes.any { it.contains("not mentioned in 1 of 2") })
    }

    @Test
    fun exclusionViolatesWhenPresent() {
        val g = GoalParser.parse("Toyota Tacoma without lift kit")
        val v = ConstraintEvaluator.withDetail(g, ConstraintEvaluator.fromCard(g, card("2015 Toyota Tacoma TRD", 20000, 90000), "h"), "6 inch lift kit installed, 35s", null)
        assertTrue(v.perConstraint.entries.any { it.key.startsWith("exclude_") && it.value == Verdict.VIOLATED })
    }
}
