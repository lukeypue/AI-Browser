package com.appgate.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchIntentTest {
    @Test
    fun parsesPriceAndDeepDescriptionRequirement() {
        val parsed = SearchIntentParser.parse("expedition under 8k with a 3.73 axle")
        assertEquals("expedition", parsed.coreQuery)
        assertEquals(8000, parsed.maxPrice)
        assertEquals(listOf("3.73 axle"), parsed.requiredTerms)
    }

    @Test
    fun parsesMileageAndDollarPrice() {
        val parsed = SearchIntentParser.parse("ford expedition under $8,000 under 150k miles with 3.73 axle")
        assertEquals(8000, parsed.maxPrice)
        assertEquals(150000, parsed.maxMileage)
        assertTrue(parsed.coreQuery.contains("ford expedition"))
    }

    @Test
    fun cleansNaturalLanguageBeforeSendingQueryToSites() {
        val parsed = SearchIntentParser.parse("find a Ford expedition, under 150k miles and under 8k dollars")
        assertEquals("Ford expedition", parsed.coreQuery)
        assertEquals(8000, parsed.maxPrice)
        assertEquals(150000, parsed.maxMileage)
    }

    @Test
    fun rejectsSummaryAboveHardPriceCeiling() {
        val parsed = SearchIntentParser.parse("expedition under 8k")
        assertTrue(SearchMatcher.summaryCouldMatch("2003 Ford Expedition Eddie Bauer 149,000 Miles $4,995", parsed))
        assertFalse(SearchMatcher.summaryCouldMatch("2027 Ford Expedition Tremor MSRP $86,660 $86,660", parsed))
    }

    @Test
    fun hardConstraintsRequireEvidenceOnCandidateCard() {
        val parsed = SearchIntentParser.parse("ford expedition under 8k under 150k miles")
        assertFalse(SearchMatcher.summaryCouldMatch("Ford Expedition vehicles near you. Browse listings and filters.", parsed))
        assertFalse(SearchMatcher.summaryCouldMatch("Ford Expedition $7,500. Browse listings and filters.", parsed))
        assertTrue(SearchMatcher.summaryCouldMatch("2003 Ford Expedition Eddie Bauer 149,000 Miles $7,500", parsed))
    }

    @Test
    fun deepTermsCanMatchWordsSeparatedInDescription() {
        val parsed = SearchIntentParser.parse("expedition under 8k with a 3.73 axle")
        assertTrue(SearchMatcher.deepTextMatches("Factory tow package. Rear axle ratio is 3.73 with limited slip.", parsed))
        assertFalse(SearchMatcher.deepTextMatches("Rear axle ratio 3.31. Clean interior.", parsed))
    }

    @Test
    fun kslUrlUsesMakeModelPathAndHardFilters() {
        val parsed = SearchIntentParser.parse("find a Ford expedition under 8k under 150k miles")
        val url = SearchUrlBuilder.build("ksl_cars", "https://cars.ksl.com/search/keyword/{q}", parsed)
        assertTrue(url.contains("/make/Ford/model/Expedition"))
        assertTrue(url.contains("priceTo/8000"))
        assertTrue(url.contains("mileageTo/150000"))
    }
}
