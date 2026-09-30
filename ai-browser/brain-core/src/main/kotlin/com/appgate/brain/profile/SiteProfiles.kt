package com.appgate.brain.profile

import com.appgate.brain.json.JsonObject
import com.appgate.brain.model.Constraint
import com.appgate.brain.model.ConstraintOp
import com.appgate.brain.model.Goal
import java.net.URLEncoder

/**
 * A site profile is *data* the general engine consumes — the replacement for code-based
 * "mini brains". It seeds a site's facet vocabulary, URL templates and pacing; everything
 * else is learned. Profiles can be extended at runtime (planner proposals are stored in
 * the site model, never here).
 */
data class SiteProfile(
    val key: String,
    val name: String,
    val hosts: List<String>,
    val startUrl: String,
    val loginUrl: String? = null,
    val searchUrl: String? = null,                    // {q} placeholder; optional {price_max} {mileage_max} {year_min} placeholders
    val facetVocabulary: Map<String, String> = emptyMap(),
    val quirks: Set<String> = emptySet(),
    val minActionIntervalMs: Long = 1200L,
    val categories: Set<String> = setOf("general"),
    val requiresLogin: Boolean = false,
    val trainingQueries: List<String> = emptyList(),
    val navigationHosts: List<String> = emptyList() // Content pages reached from results; not profile aliases.
) {
    fun accepts(host: String): Boolean {
        val h = host.lowercase().removePrefix("www.")
        return hosts.any { s -> h == s || h.endsWith(".$s") }
    }

    /** Build a direct search URL when the profile knows one; the brain verifies the result like any action. */
    fun searchUrlFor(goal: Goal): String? {
        val template = searchUrl ?: return null
        var url = template.replace("{q}", encode(goal.query))
        val placeholders = Regex("\\{([a-z_]+)\\}").findAll(url).map { it.groupValues[1] }.toList()
        for (p in placeholders) {
            val value = when (p) {
                "price_max" -> goal.constraints.firstOrNull { it.key == "price" && it.op == ConstraintOp.LTE }?.value
                "price_min" -> goal.constraints.firstOrNull { it.key == "price" && it.op == ConstraintOp.GTE }?.value
                "mileage_max" -> goal.constraints.firstOrNull { it.key == "mileage" && it.op == ConstraintOp.LTE }?.value
                "year_min" -> goal.constraints.firstOrNull { it.key == "year" && it.op == ConstraintOp.GTE }?.value
                "year_max" -> goal.constraints.firstOrNull { it.key == "year" && it.op == ConstraintOp.LTE }?.value
                "make" -> goal.constraints.firstOrNull { it.key == "make" }?.value
                "model" -> goal.constraints.firstOrNull { it.key == "model" }?.value
                else -> null
            }
            url = url.replace("{$p}", value?.let { encode(it) } ?: "")
        }
        // Remove dangling "/segment/" pairs and empty query params left by missing values.
        url = url.replace(Regex("/(priceFrom|priceTo|mileageFrom|mileageTo|yearFrom|yearTo|make|model)/(?=/|$)"), "/")
        url = url.replace(Regex("[?&][a-zA-Z_]+=(?=&|$)"), "").replace(Regex("\\?&"), "?").trimEnd('?', '&')
        return url
    }

    fun toJson(): JsonObject = JsonObject().put("key", key).put("name", name).putStrings("hosts", hosts).put("start", startUrl).put("login", loginUrl).put("search", searchUrl)

    private fun encode(v: String): String = URLEncoder.encode(v, "UTF-8").replace("+", "%20")
}

object SiteProfiles {
    private val vehicleQueries = listOf("Ford Expedition", "Toyota Tacoma", "Honda CR-V", "Chevrolet Tahoe", "Subaru Outback", "Jeep Wrangler", "hybrid SUV", "pickup truck")
    private val shoppingQueries = listOf("cordless drill", "mountain bike", "patio furniture", "camping tent", "kayak", "snowblower", "dining table", "road bike")

    val kslClassifieds = SiteProfile(
        key = "ksl_classifieds", name = "KSL Classifieds",
        hosts = listOf("classifieds.ksl.com"),
        startUrl = "https://classifieds.ksl.com/",
        loginUrl = "https://www.ksl.com/",
        searchUrl = "https://classifieds.ksl.com/search/keyword/{q}/priceFrom/{price_min}/priceTo/{price_max}",
        facetVocabulary = mapOf("price from" to "price", "price to" to "price", "zip code" to "zip", "search radius" to "distance", "sort by" to "sort"),
        quirks = setOf("path_encoded_facets", "select_facets"),
        categories = setOf("general", "shopping", "housing", "vehicles"),
        trainingQueries = shoppingQueries
    )

    val kslCars = SiteProfile(
        key = "ksl_cars", name = "KSL Cars",
        hosts = listOf("cars.ksl.com"),
        startUrl = "https://cars.ksl.com/",
        loginUrl = "https://www.ksl.com/",
        searchUrl = "https://cars.ksl.com/search/keyword/{q}/priceFrom/{price_min}/priceTo/{price_max}/mileageFrom/0/mileageTo/{mileage_max}/yearFrom/{year_min}",
        facetVocabulary = mapOf("price from" to "price", "price to" to "price", "mileage from" to "mileage", "mileage to" to "mileage", "year from" to "year", "year to" to "year", "make" to "make", "model" to "model", "trim" to "trim", "body" to "body_style", "sort by" to "sort"),
        quirks = setOf("path_encoded_facets", "select_facets", "make_model_cascade"),
        categories = setOf("vehicles"),
        trainingQueries = vehicleQueries,
        navigationHosts = emptyList()
    )

    val facebookMarketplace = SiteProfile(
        key = "facebook_marketplace", name = "Facebook Marketplace",
        hosts = listOf("facebook.com", "m.facebook.com"),
        startUrl = "https://www.facebook.com/marketplace/",
        loginUrl = "https://www.facebook.com/login/",
        searchUrl = "https://www.facebook.com/marketplace/search/?query={q}&maxPrice={price_max}&minPrice={price_min}&exact=false",
        facetVocabulary = mapOf("min" to "price", "max" to "price", "price range" to "price", "mileage" to "mileage", "year" to "year", "make" to "make", "model" to "model", "vehicle type" to "body_style", "sort by" to "sort", "radius" to "distance", "location" to "location"),
        quirks = setOf("infinite_scroll", "login_required", "drawer_facets", "human_pacing", "no_autonomous_training_without_test_account"),
        minActionIntervalMs = 2500L,
        categories = setOf("general", "shopping", "vehicles", "housing"),
        requiresLogin = true,
        trainingQueries = shoppingQueries
    )

    val offerUp = SiteProfile(
        key = "offerup", name = "OfferUp",
        hosts = listOf("offerup.com"),
        startUrl = "https://offerup.com/",
        loginUrl = "https://offerup.com/accounts/login/",
        searchUrl = "https://offerup.com/search?q={q}&price_max={price_max}&price_min={price_min}",
        facetVocabulary = mapOf("min price" to "price", "max price" to "price", "price" to "price", "distance" to "distance", "sort" to "sort", "condition" to "condition", "year" to "year", "mileage" to "mileage", "make" to "make", "model" to "model"),
        quirks = setOf("infinite_scroll", "drawer_facets", "human_pacing"),
        minActionIntervalMs = 2000L,
        categories = setOf("general", "shopping", "vehicles"),
        trainingQueries = shoppingQueries
    )

    val craigslist = SiteProfile(
        key = "craigslist", name = "Craigslist",
        hosts = listOf("craigslist.org"),
        startUrl = "https://www.craigslist.org/",
        searchUrl = "https://www.craigslist.org/search/sss?query={q}&max_price={price_max}&min_price={price_min}",
        facetVocabulary = mapOf("min" to "price", "max" to "price", "miles from" to "distance", "auto miles" to "mileage", "model year" to "year", "make and model" to "make"),
        quirks = setOf("held_out_candidate"),
        categories = setOf("general", "shopping", "vehicles", "housing", "jobs"),
        trainingQueries = shoppingQueries
    )

    val ebay = SiteProfile(
        key = "ebay", name = "eBay",
        hosts = listOf("ebay.com"),
        startUrl = "https://www.ebay.com/",
        searchUrl = "https://www.ebay.com/sch/i.html?_nkw={q}&_udhi={price_max}&_udlo={price_min}",
        facetVocabulary = mapOf("price" to "price", "condition" to "condition", "buying format" to "category", "sort" to "sort", "item location" to "location"),
        quirks = setOf("held_out_candidate"),
        categories = setOf("general", "shopping"),
        trainingQueries = shoppingQueries
    )

    val autotrader = SiteProfile(
        key = "autotrader", name = "AutoTrader",
        hosts = listOf("autotrader.com"),
        startUrl = "https://www.autotrader.com/",
        searchUrl = "https://www.autotrader.com/cars-for-sale/all-cars?searchRadius=100&maxPrice={price_max}&maxMileage={mileage_max}&startYear={year_min}&keywordPhrases={q}",
        facetVocabulary = mapOf("price" to "price", "mileage" to "mileage", "year" to "year", "make" to "make", "model" to "model", "trim" to "trim", "drive type" to "drivetrain"),
        quirks = setOf("held_out_candidate"),
        categories = setOf("vehicles"),
        trainingQueries = vehicleQueries
    )

    val carsCom = SiteProfile(
        key = "cars_com", name = "Cars.com",
        hosts = listOf("cars.com"),
        startUrl = "https://www.cars.com/",
        searchUrl = "https://www.cars.com/shopping/results/?keyword={q}&list_price_max={price_max}&mileage_max={mileage_max}&year_min={year_min}&stock_type=used",
        facetVocabulary = mapOf("price" to "price", "mileage" to "mileage", "year" to "year", "make" to "make", "model" to "model"),
        quirks = setOf("held_out_candidate"),
        categories = setOf("vehicles"),
        trainingQueries = vehicleQueries
    )

    val all: List<SiteProfile> = listOf(kslClassifieds, kslCars, facebookMarketplace, offerUp, craigslist, ebay, autotrader, carsCom)

    /** The four training sites the overnight learner rotates through. */
    val training: List<SiteProfile> = listOf(kslClassifieds, kslCars, offerUp, facebookMarketplace)

    fun forHost(host: String): SiteProfile? = all.firstOrNull { it.accepts(host) }
    fun forKey(key: String): SiteProfile? = all.firstOrNull { it.key == key }

    /** Generic fallback for an unseen site: no templates, no vocabulary, default pacing. */
    fun generic(host: String): SiteProfile = SiteProfile(
        key = "generic:" + host.lowercase(),
        name = host,
        hosts = listOf(host.lowercase().removePrefix("www.")),
        startUrl = "https://$host/",
        trainingQueries = shoppingQueries
    )

    fun profilesForCategory(category: String?): List<SiteProfile> = all.filter { category == null || category in it.categories }

    /** Which profiles a goal should run on, in priority order (KSL first: no login, deterministic). */
    fun sourcesFor(goal: Goal, preferred: List<String> = emptyList()): List<SiteProfile> {
        val cat = goal.category ?: "general"
        val ordered = when (cat) {
            "vehicles" -> listOf(kslCars, offerUp, facebookMarketplace, craigslist, autotrader, carsCom)
            "housing" -> listOf(kslClassifieds, facebookMarketplace, craigslist)
            "jobs" -> listOf(craigslist, kslClassifieds)
            else -> listOf(kslClassifieds, offerUp, facebookMarketplace, craigslist, ebay)
        }
        val preferredProfiles = preferred.mapNotNull { forKey(it) }
        return (preferredProfiles + ordered).distinctBy { it.key }
    }
}
