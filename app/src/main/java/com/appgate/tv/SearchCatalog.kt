package com.appgate.tv

data class SearchSource(
    val name: String,
    val key: String,
    val categories: Set<String>,
    val template: String,
    val loginCommon: Boolean = false,
    val direct: Boolean = true
)

object SearchCatalog {
    fun all(): List<SearchSource> = listOf(
        SearchSource("KSL Classifieds", "ksl_classifieds", setOf("shopping", "local", "general"), "https://classifieds.ksl.com/search/keyword/{q}"),
        SearchSource("KSL Cars", "ksl_cars", setOf("vehicles"), "https://cars.ksl.com/search/keyword/{q}"),
        SearchSource("Facebook Marketplace", "facebook_marketplace", setOf("shopping", "vehicles", "local"), "https://www.facebook.com/marketplace/search/?query={q}", true),
        SearchSource("eBay", "ebay", setOf("shopping", "vehicles", "general"), "https://www.ebay.com/sch/i.html?_nkw={q}"),
        SearchSource("Craigslist", "craigslist", setOf("shopping", "vehicles", "local", "jobs", "realestate"), "https://www.craigslist.org/search/sss?query={q}"),
        SearchSource("OfferUp", "offerup", setOf("shopping", "vehicles", "local"), "https://offerup.com/search?q={q}"),
        SearchSource("AutoTrader", "autotrader", setOf("vehicles"), "https://www.autotrader.com/cars-for-sale/all-cars?keywordPhrases={q}"),
        SearchSource("Cars.com", "cars_com", setOf("vehicles"), "https://www.cars.com/shopping/results/?keyword={q}"),
        SearchSource("CarMax", "carmax", setOf("vehicles"), "https://www.carmax.com/cars?search={q}"),
        SearchSource("TrueCar", "truecar", setOf("vehicles"), "https://www.google.com/search?q=site%3Atruecar.com%20{q}", direct = false),
        SearchSource("CarGurus", "cargurus", setOf("vehicles"), "https://www.google.com/search?q=site%3Acargurus.com%20{q}", direct = false),
        SearchSource("Edmunds", "edmunds", setOf("vehicles"), "https://www.google.com/search?q=site%3Aedmunds.com%20{q}", direct = false),
        SearchSource("Autolist", "autolist", setOf("vehicles"), "https://www.google.com/search?q=site%3Aautolist.com%20{q}", direct = false),
        SearchSource("Hemmings", "hemmings", setOf("vehicles"), "https://www.google.com/search?q=site%3Ahemmings.com%20{q}", direct = false),
        SearchSource("Cars & Bids", "cars_bids", setOf("vehicles"), "https://www.google.com/search?q=site%3Acarsandbids.com%20{q}", direct = false),
        SearchSource("Bring a Trailer", "bringatrailer", setOf("vehicles"), "https://www.google.com/search?q=site%3Abringatrailer.com%20{q}", direct = false),
        SearchSource("Best Buy", "bestbuy", setOf("shopping"), "https://www.bestbuy.com/site/searchpage.jsp?id=pcat17071&st={q}"),
        SearchSource("Walmart", "walmart", setOf("shopping"), "https://www.walmart.com/search?q={q}"),
        SearchSource("Target", "target", setOf("shopping"), "https://www.target.com/s?searchTerm={q}"),
        SearchSource("Amazon", "amazon", setOf("shopping"), "https://www.amazon.com/s?k={q}"),
        SearchSource("Etsy", "etsy", setOf("shopping"), "https://www.etsy.com/search?q={q}"),
        SearchSource("Home Depot", "homedepot", setOf("shopping"), "https://www.homedepot.com/s/{q}"),
        SearchSource("Lowe's", "lowes", setOf("shopping"), "https://www.lowes.com/search?searchTerm={q}"),
        SearchSource("Newegg", "newegg", setOf("shopping"), "https://www.newegg.com/p/pl?d={q}"),
        SearchSource("Google", "google", setOf("web"), "https://www.google.com/search?q={q}"),
        SearchSource("Bing", "bing", setOf("web"), "https://www.bing.com/search?q={q}")
    )
}
