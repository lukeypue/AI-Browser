package com.appgate.brain.lab

import org.junit.Assert.*
import org.junit.Test
import com.appgate.brain.json.JsonObject
import com.appgate.brain.model.Role
import com.appgate.brain.perception.SpsParser

class PracticeCityTest {
    @Test fun experimentIncludesSeparateExpandedCityExamsWithoutReplacingTheOriginalExam() {
        val report = SimulationTrainer().experiment(10)
        assertNotNull("The larger city needs its own reproducible exam manifest", report.optObject("city_manifest"))
        assertEquals(24, report.optObject("baseline")!!.optInt("episodes"))
        assertEquals(144, report.optObject("city_baseline")!!.optInt("episodes"))
        assertEquals(144, report.optObject("city_after_training")!!.optInt("episodes"))
        assertEquals(false, report.optBoolean("real_world_validated"))
        assertEquals(false, report.optBoolean("synthetic_memory_imported_to_android"))
    }
    @Test fun cityHasReproducibleDistinctHostsQueriesLayoutsAndCatalogSizes() {
        val cases = SimulationTrainer.cityCases()
        assertEquals(144, cases.size)
        assertEquals(144, cases.map { it.id }.toSet().size)
        assertEquals(8, cases.map { it.host }.toSet().size)
        assertEquals(4, cases.map { it.query }.toSet().size)
        assertEquals(setOf(32, 64, 96, 128), cases.map { it.catalogSize }.toSet())
        assertEquals(setOf(1, 2, 3, 4), cases.map { it.pageSize }.toSet())
        assertEquals(SimulationPagination.entries.toSet(), cases.map { it.pagination }.toSet())
        assertEquals(cases, SimulationTrainer.cityCases())
        assertTrue(cases.all { it.seed > 0 && it.host.startsWith("exam-district-") })
    }

    @Test fun largerCatalogChangesTheEnvironmentWithoutInventingExtraRequiredMatches() {
        val scenario = Scenario(37, catalogSize = 128)
        val market = SimulationMarket(scenario)
        assertEquals(128, market.catalog.size)
        assertEquals(128, market.catalog.map { it.key }.toSet().size)
        assertEquals(4, market.catalog.count { it.vehicle == scenario.query && it.price <= 8000 && it.mileage <= 150000 })
        val observation = market.observe(1000)
        assertFalse(observation.contains("catalogSize"))
        assertFalse(observation.contains("scenario"))
    }

    @Test fun paginationVariantsProduceRealNewItemEvidence() {
        for (mode in SimulationPagination.entries) {
            val scenario = Scenario(52, pageSize = 1, pagination = mode)
            val market = SimulationMarket(scenario)
            market.navigate("https://${scenario.host}/search?q=Toyota+Sequoia&price_max=8000&mileage_max=150000", 1000)
            val before = SpsParser().parse(market.observe(1000))
            val role = if (mode == SimulationPagination.LOAD_MORE) Role.LOAD_MORE else Role.PAGE_NEXT
            val target = before.affordances.first { it.role == role }
            assertTrue(market.act(JsonObject().put("cmd", "click").put("id", target.id), 1000).ok)
            val after = SpsParser().parse(market.observe(1000))
            assertTrue((after.resultKeys - before.resultKeys).isNotEmpty())
            if (mode == SimulationPagination.LOAD_MORE) assertTrue(after.resultKeys.containsAll(before.resultKeys))
            else assertTrue(after.resultKeys.intersect(before.resultKeys).isEmpty())
        }
    }
}
