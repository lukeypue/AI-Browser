package com.appgate.brain.goal

import com.appgate.brain.model.ConstraintClass
import com.appgate.brain.model.ConstraintOp
import com.appgate.brain.model.ConstraintSource
import com.appgate.brain.model.GoalIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalParserTest {
    @Test
    fun expeditionQueryIsFullyTyped() {
        val g = GoalParser.parse("Find a Ford Expedition under 8k under 150k miles with a 3.73 axle")
        assertEquals(GoalIntent.FIND_LISTINGS, g.intent)
        assertEquals("vehicles", g.category)
        assertEquals("Ford", g.constraint("make")?.value)
        assertEquals("Expedition", g.constraint("model")?.value)
        val price = g.constraints.first { it.key == "price" }
        assertEquals(ConstraintOp.LTE, price.op)
        assertEquals("8000", price.value)
        val mileage = g.constraints.first { it.key == "mileage" }
        assertEquals("150000", mileage.value)
        val axle = g.constraints.first { it.key == "axle_ratio" }
        assertEquals(ConstraintClass.RARE, axle.cls)
        assertTrue(ConstraintSource.TEXT_EVIDENCE in axle.sources)
        assertTrue(axle.patterns.isNotEmpty())
        assertTrue(axle.synonyms.any { it.contains("gears") })
        assertTrue("query keeps make/model: '${g.query}'", g.query.contains("Ford Expedition"))
        assertNull(g.constraint("keyword"))
    }

    @Test
    fun housingQueryUsesBedsAndPrice() {
        val g = GoalParser.parse("3 bedroom house under $300,000 in Provo")
        assertEquals("housing", g.category)
        assertEquals("3", g.constraint("bedrooms")?.value)
        assertEquals("300000", g.constraint("price")?.value)
        assertEquals("Provo", g.constraint("location")?.value)
        assertNotNull(g.constraint("keyword"))
    }

    @Test
    fun exclusionsAndPreferencesAreParsed() {
        val g = GoalParser.parse("Toyota Tacoma 2015 or newer under 25000 without lift kit, prefer newer")
        assertEquals("2015", g.constraints.first { it.key == "year" && it.op == ConstraintOp.GTE }.value)
        assertEquals("25000", g.constraint("price")?.value)
        assertTrue(g.constraints.any { it.op == ConstraintOp.NOT_CONTAINS && it.value.contains("lift") })
        assertTrue(g.soft.any { it.key == "year" && it.preferMax })
    }

    @Test
    fun contradictionProducesWarningNotEmptySearch() {
        val g = GoalParser.parse("2023 or newer Honda Civic under 5000")
        assertTrue(g.warnings.isNotEmpty())
        assertEquals("Honda", g.constraint("make")?.value)
    }

    @Test
    fun messageIntentIsDetected() {
        val g = GoalParser.parse("message seller saying \"Is this still available?\"")
        assertEquals(GoalIntent.PREPARE_MESSAGE, g.intent)
        assertEquals("Is this still available?", g.messageDraft)
    }

    @Test
    fun plainShoppingQuery() {
        val g = GoalParser.parse("cordless drill under $60")
        assertEquals("general", g.category)
        assertEquals("60", g.constraint("price")?.value)
        assertTrue(g.query.lowercase().contains("cordless drill"))
        assertEquals("cordless drill", g.constraint("keyword")?.value)
    }

    @Test
    fun goalRoundTripsThroughJson() {
        val g = GoalParser.parse("Ford Expedition under 8k under 150k miles with a 3.73 axle")
        val back = com.appgate.brain.model.Goal.fromJson(com.appgate.brain.json.Json.parseObject(g.toJson().toString()))
        assertEquals(g.constraints.map { it.describe() }, back.constraints.map { it.describe() })
        assertEquals(g.query, back.query)
    }
}
