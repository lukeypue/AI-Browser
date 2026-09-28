package com.appgate.brain.perception

import com.appgate.brain.model.PageType
import com.appgate.brain.model.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawerPageTest {
    @Test
    fun filterDrawerIsAFacetPanelWithTypedFacets() {
        val sps = SpsParser().parse(com.appgate.brain.test.Fixtures.observation("drawer_page"))
        assertTrue(sps.dialogOpen)
        assertEquals(PageType.FACET_PANEL, sps.pageType)
        val min = sps.facet("price_min"); val max = sps.facet("price_max")
        assertNotNull("Min price text input", min); assertNotNull("Max price text input", max)
        assertEquals("numeric_min", min!!.facetKind); assertEquals("numeric_max", max!!.facetKind)
        assertNotNull(sps.facet("year_min"))
        assertTrue(sps.has(Role.FACET_APPLY))
        assertTrue(sps.has(Role.FACET_CLEAR))
        assertTrue(sps.byRole(Role.CLOSE).any { it.regionRole == com.appgate.brain.model.RegionRole.DIALOG })
        assertTrue(sps.has(Role.FACET_OPEN))
        assertTrue(sps.has(Role.SORT))
        assertEquals(4, sps.resultKeys.size)
        assertTrue(sps.byRole(Role.FACET).any { it.facetKey == "body_style" && it.facetKind == "toggle" })
        assertEquals("Ford Expedition", sps.constraintsActive["query"])
        val item = sps.results!!.items.first { it.title.startsWith("2009") }
        assertEquals(6800, item.price)
        assertEquals(155000, item.mileage)
    }
}
