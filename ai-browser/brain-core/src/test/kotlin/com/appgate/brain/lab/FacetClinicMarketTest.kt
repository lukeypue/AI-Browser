package com.appgate.brain.lab

import com.appgate.brain.perception.SpsParser
import org.junit.Assert.*
import org.junit.Test

class FacetClinicMarketTest {
    @Test fun choiceFilterCommitsACatalogSubsetWithoutChangingSearchQuery() {
        val market=SimulationMarket(Scenario(8124,host="facet-clinic.sim.invalid"))
        market.navigate("https://facet-clinic.sim.invalid/search?q=",1000)
        val before=SpsParser().parse(market.observe(1000))
        market.navigate("https://facet-clinic.sim.invalid/search?q=&make=Ford",1000)
        val after=SpsParser().parse(market.observe(1000))
        assertEquals("",market.appliedQuery)
        assertEquals("Ford",market.appliedFilters["make"])
        assertEquals(setOf("k3"),after.resultKeys)
        assertNotEquals(before.resultKeys,after.resultKeys)
        market.back(1000)
        assertEquals(setOf("k3"),SpsParser().parse(market.observe(1000)).resultKeys)
    }
}

