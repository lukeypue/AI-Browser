package com.appgate.tv

import org.junit.Assert.assertTrue
import org.junit.Test

class SearchCatalogTest {
    @Test
    fun vehicleSearchHasBroadMajorSourceCoverage() {
        val vehicles = SearchCatalog.all().filter { "vehicles" in it.categories }
        val keys = vehicles.map { it.key }.toSet()
        assertTrue(vehicles.size >= 12)
        assertTrue("ksl_cars" in keys)
        assertTrue("facebook_marketplace" in keys)
        assertTrue("craigslist" in keys)
        assertTrue("ebay" in keys)
        assertTrue("autotrader" in keys)
        assertTrue("cars_com" in keys)
        assertTrue("carmax" in keys)
        assertTrue("offerup" in keys)
    }

    @Test
    fun marketplaceIsMarkedAsLoginCommon() {
        val marketplace = SearchCatalog.all().first { it.key == "facebook_marketplace" }
        assertTrue(marketplace.loginCommon)
    }
}
