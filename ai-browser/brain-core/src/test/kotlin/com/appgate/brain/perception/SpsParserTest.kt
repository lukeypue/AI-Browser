package com.appgate.brain.perception

import com.appgate.brain.model.PageType
import com.appgate.brain.model.Role
import com.appgate.brain.model.SemanticPageState
import com.appgate.brain.test.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These tests run the Kotlin perception layer on observations produced by the real content
 * script in headless Chromium (see src/test/harness/extract.mjs), so extractor and parser are
 * checked together. Regenerate the .observation.json files after changing content.js.
 */
class SpsParserTest {
    private fun parse(name: String): SemanticPageState = SpsParser().parse(Fixtures.observation(name))

    @Test
    fun resultsPageIsClassifiedWithRolesAndItems() {
        val sps = parse("results_page")
        assertEquals(PageType.RESULTS, sps.pageType)
        assertEquals(5, sps.resultKeys.size)
        assertTrue(sps.has(Role.SEARCH_BOX))
        assertTrue(sps.has(Role.SUBMIT))
        assertTrue(sps.has(Role.PAGE_NEXT))
        assertTrue(sps.has(Role.LOAD_MORE))
        assertTrue(sps.has(Role.FACET_APPLY))
        assertTrue(sps.has(Role.FACET_CLEAR))
        assertTrue(sps.has(Role.SORT))
        assertTrue(sps.has(Role.LOGIN))
        assertTrue(sps.byRole(Role.CATEGORY_LINK).any { it.name == "Cars" })
        val priceMax = sps.facet("price_max")
        assertNotNull("price_max facet from 'Price to' select", priceMax)
        assertEquals("select", priceMax!!.tag)
        assertEquals("choice", priceMax.facetKind)
        assertTrue(priceMax.choices.contains("$8,000"))
        val priceMin = sps.facet("price_min")
        assertNotNull(priceMin)
        val mileage = sps.facet("mileage_max")
        assertNotNull("mileage_max from 'Max mileage' number input", mileage)
        assertEquals("numeric_max", mileage!!.facetKind)
        assertNotNull(sps.facet("make"))
        assertEquals("Ford Expedition", sps.constraintsActive["query"])
        val item = sps.results!!.items.first()
        assertEquals(7500, item.price)
        assertEquals(142000, item.mileage)
        assertEquals(2008, item.year)
        assertEquals("/listing/1001-2008-ford-expedition", item.hrefPath)
        // Footer links must never become navigation candidates.
        assertTrue(sps.affordances.filter { it.regionRole == com.appgate.brain.model.RegionRole.FOOTER }.none { it.role == Role.CATEGORY_LINK })
    }

    @Test
    fun detailPageExposesDetailTextWithPiiRedacted() {
        val sps = parse("detail_page")
        assertEquals(PageType.DETAIL, sps.pageType)
        assertTrue(sps.detailText.contains("3.73 gears"))
        assertFalse("phone must be redacted", sps.detailText.contains("801-555"))
        assertFalse("email must be redacted", sps.detailText.contains("example.com"))
        assertTrue(sps.has(Role.EXPAND_TEXT))
        assertTrue(sps.has(Role.MESSAGE_SELLER))
        assertTrue(sps.byRole(Role.SAVE).isNotEmpty())
        assertTrue(sps.byRole(Role.REPORT).all { it.isCommit })
        assertTrue(sps.byRole(Role.SAVE).all { it.isCommit })
        assertTrue(sps.byRole(Role.SHARE).none { it.isCommit })
    }

    @Test
    fun loginPageIsAnAuthWallWithNoNamesOrValues() {
        val sps = parse("login_page")
        assertEquals(PageType.AUTH_WALL, sps.pageType)
        assertTrue(sps.isHumanOnly)
        assertTrue(sps.affordances.all { it.name.isEmpty() && it.value == null })
        assertTrue(sps.title.isEmpty())
        assertTrue(sps.constraintsActive.isEmpty())
        val summary = sps.summary().toString()
        assertFalse(summary.contains("hunter2"))
        assertFalse(summary.contains("example.com"))
    }

    @Test
    fun dialogPageFlagsDialogAndCloseAffordance() {
        val sps = parse("dialog_page")
        assertTrue(sps.dialogOpen)
        assertTrue(sps.has(Role.CLOSE))
        assertTrue(sps.byRole(Role.CLOSE).any { it.name == "Not now" })
        assertEquals(4, sps.resultKeys.size)
        assertTrue(sps.has(Role.FACET_OPEN))
        assertTrue(sps.has(Role.SEARCH_BOX))
    }

    @Test
    fun hashIgnoresCosmeticChangesButNotConstraints() {
        val a = parse("results_page")
        val b = parse("results_page")
        assertEquals(a.hash, b.hash)
        val json = Fixtures.observationJson("results_page").replace("\"value\": \"Any\"", "\"value\": \"$8,000\"")
        val c = SpsParser().parse(json)
        assertTrue(c.constraintsActive.containsKey("price_max") || c.constraintsActive.containsKey("price_min"))
        assertTrue(a.hash != c.hash)
    }

    @Test
    fun urlPatternsStripValues() {
        assertEquals("classifieds.ksl.com/search/keyword/:id", UrlPatterns.urlPattern("https://classifieds.ksl.com/search/keyword/123456?x=1").substringBefore('?'))
        assertEquals("offerup.com/search?price_max,q", UrlPatterns.urlPattern("https://offerup.com/search?q=ford+expedition&price_max=8000"))
        assertEquals("/item/:id", UrlPatterns.pathTemplate("/item/98765432"))
        assertEquals("/listing/2008-ford-expedition-:id", UrlPatterns.pathTemplate("/listing/2008-ford-expedition-1001234"))
        assertTrue(UrlPatterns.sameSite("https://cars.ksl.com/x", "https://www.ksl.com/login"))
        assertFalse(UrlPatterns.sameSite("https://cars.ksl.com/x", "https://accounts.google.com/"))
    }

    @Test
    fun pathEncodedFacetsBecomeActiveConstraints() {
        val json = Fixtures.observationJson("results_page").replace("http://fixtures.test/results_page.html", "https://cars.ksl.com/search/keyword/ford/priceTo/8000/mileageTo/150000")
        val sps = SpsParser().parse(json)
        assertEquals("8000", sps.constraintsActive["price_max"])
        assertEquals("150000", sps.constraintsActive["mileage_max"])
    }
}
