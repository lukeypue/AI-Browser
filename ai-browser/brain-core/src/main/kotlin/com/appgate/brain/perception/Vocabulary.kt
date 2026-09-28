package com.appgate.brain.perception

/**
 * Canonical facet vocabulary shared by every site. A site profile may extend it with
 * site-specific labels; the planner may propose new entries, which are stored per host.
 *
 * Canonical keys are the language goals, skills, bindings and constraints all speak.
 */
object Vocabulary {
    /** canonical key -> phrases that identify it in a facet label */
    val facetLexicon: Map<String, List<String>> = linkedMapOf(
        "price" to listOf("price", "cost", "asking", "\$", "budget"),
        "mileage" to listOf("mileage", "miles", "odometer", "km"),
        "year" to listOf("year", "model year"),
        "make" to listOf("make", "manufacturer", "brand of vehicle"),
        "model" to listOf("model"),
        "trim" to listOf("trim", "trim level"),
        "body_style" to listOf("body style", "body type", "vehicle type", "body"),
        "condition" to listOf("condition", "new or used", "used", "new"),
        "transmission" to listOf("transmission", "automatic", "manual"),
        "drivetrain" to listOf("drivetrain", "drive type", "4wd", "awd", "4x4", "drive"),
        "fuel" to listOf("fuel", "fuel type", "hybrid", "electric", "diesel", "gas"),
        "color" to listOf("color", "colour", "exterior color"),
        "distance" to listOf("distance", "radius", "within", "miles from", "near me", "search radius"),
        "location" to listOf("location", "city", "area", "where"),
        "zip" to listOf("zip", "postal", "zip code"),
        "category" to listOf("category", "categories", "department", "section"),
        "bedrooms" to listOf("bedroom", "bedrooms", "beds", "bd"),
        "bathrooms" to listOf("bathroom", "bathrooms", "baths", "ba"),
        "sqft" to listOf("square feet", "sq ft", "sqft", "square footage"),
        "brand" to listOf("brand"),
        "size" to listOf("size"),
        "seller_type" to listOf("seller type", "dealer", "private seller", "owner", "for sale by"),
        "title_status" to listOf("title", "title status", "clean title", "salvage"),
        "sort" to listOf("sort", "sort by", "order by"),
        "keyword" to listOf("keyword", "keywords", "search within", "contains")
    )

    private val minWords = listOf("min", "minimum", "from", "low", "lowest", "at least", "over", "more than", "start", "floor", "≥")
    private val maxWords = listOf("max", "maximum", "to", "up to", "under", "high", "highest", "at most", "less than", "below", "ceiling", "limit", "≤")

    val numericKeys = setOf("price", "mileage", "year", "distance", "bedrooms", "bathrooms", "sqft")

    /** Words that make a control's purpose obvious even without a facet label. */
    val roleLexicon: Map<String, List<String>> = linkedMapOf(
        "search" to listOf("search", "what are you looking for", "find", "keyword", "look for", "search marketplace", "search classifieds"),
        "filter_open" to listOf("filter", "filters", "refine", "all filters", "more filters", "narrow", "advanced search"),
        "filter_apply" to listOf("apply", "show results", "see results", "update results", "view results", "done", "update", "show", "see all results"),
        "filter_clear" to listOf("clear all", "clear filters", "reset", "clear", "remove filters", "remove all"),
        "sort" to listOf("sort", "sort by", "newest", "oldest", "price: low", "price: high", "lowest price", "highest price", "relevance", "best match", "recently listed", "date listed"),
        "next" to listOf("next", "next page", "older", "›", "»", "→"),
        "prev" to listOf("previous", "prev", "newer", "‹", "«", "←"),
        "load_more" to listOf("load more", "show more results", "see more results", "more results", "view more", "see more listings", "show more listings"),
        "expand" to listOf("see more", "read more", "show more", "more details", "full description", "view details", "expand", "see full", "show full", "…more", "more"),
        "close" to listOf("close", "dismiss", "no thanks", "not now", "got it", "maybe later", "skip", "×", "x", "accept cookies", "accept all", "i agree", "okay", "ok"),
        "back" to listOf("back", "go back", "return"),
        "login" to listOf("log in", "login", "sign in", "signin", "sign up", "join", "register", "create account", "continue with google", "continue with facebook", "continue with apple"),
        "message" to listOf("message", "send message", "contact seller", "contact", "chat", "ask seller", "ask a question", "message seller", "is this available", "reply"),
        "composer" to listOf("write a message", "type a message", "your message", "message", "ask a question", "say hello"),
        "attach" to listOf("attach", "add photo", "add image", "upload", "camera"),
        "send" to listOf("send", "send message", "send now", "submit"),
        "buy" to listOf("buy", "buy now", "checkout", "check out", "purchase", "add to cart", "pay", "pay now", "place order", "order"),
        "bid" to listOf("bid", "make offer", "make an offer", "offer", "place bid", "negotiate"),
        "post" to listOf("post", "post listing", "publish", "sell", "sell an item", "sell something", "create listing", "list an item", "list item", "post ad", "place ad", "start selling"),
        "delete" to listOf("delete", "remove listing", "remove", "mark as sold"),
        "follow" to listOf("follow", "subscribe", "unfollow"),
        "save" to listOf("save", "favorite", "favourite", "add to favorites", "watch", "watchlist", "like", "heart", "add to wishlist", "save search", "get alerts", "create alert"),
        "report" to listOf("report", "flag", "report listing"),
        "share" to listOf("share"),
        "account" to listOf("account", "my account", "profile", "my profile", "settings", "my listings", "inbox", "notifications", "messages"),
        "category" to listOf("category", "categories", "cars", "vehicles", "trucks", "autos", "auto", "motorcycles", "for sale", "housing", "rentals", "real estate", "jobs", "services", "electronics", "furniture", "home", "garden", "sporting", "tools", "boats", "rvs", "trailers", "pets", "free stuff", "all categories", "browse", "classifieds", "marketplace"),
        "detail_hint" to listOf("listing", "item", "detail", "product", "vehicle", "ad", "post"),
        "footer_noise" to listOf("privacy", "terms", "help", "about", "careers", "advertise", "cookie", "accessibility", "contact us", "faq", "press", "blog", "download the app", "app store", "google play", "site map", "sitemap", "copyright", "©", "legal", "safety tips", "community guidelines")
    )

    fun canonicalFacet(label: String): String? {
        val t = normalize(label)
        if (t.isBlank()) return null
        var best: String? = null
        var bestLen = 0
        for ((key, phrases) in facetLexicon) {
            for (p in phrases) {
                if (p == "\$") { if (t.contains("$") && bestLen < 1) { best = key; bestLen = 1 }; continue }
                if (wordMatch(t, p) && p.length > bestLen) { best = key; bestLen = p.length }
            }
        }
        return best
    }

    /** Detect numeric bound direction from a label like "Max price" or "Mileage to". */
    fun numericDirection(label: String): String? {
        val t = normalize(label)
        val isMax = maxWords.any { wordMatch(t, it) }
        val isMin = minWords.any { wordMatch(t, it) }
        return when {
            isMax && !isMin -> "max"
            isMin && !isMax -> "min"
            isMax && isMin -> if (t.indexOfAny(maxWords.map { it }) < t.indexOfAny(minWords.map { it })) "max" else "min"
            else -> null
        }
    }

    fun matches(lexiconKey: String, label: String): Boolean {
        val t = normalize(label)
        if (t.isBlank()) return false
        return roleLexicon[lexiconKey].orEmpty().any { wordMatch(t, it) }
    }

    fun matchScore(lexiconKey: String, label: String): Double {
        val t = normalize(label)
        if (t.isBlank()) return 0.0
        var best = 0.0
        for (p in roleLexicon[lexiconKey].orEmpty()) {
            if (t == p) return 1.0
            if (wordMatch(t, p)) best = maxOf(best, 0.6 + 0.4 * (p.length.toDouble() / maxOf(t.length, 1)).coerceAtMost(1.0))
        }
        return best
    }

    fun normalize(value: String): String = value.lowercase().replace(Regex("[\\s_\\-]+"), " ").trim()

    /** Whole-word / phrase containment. Single characters (×, x) must match exactly. */
    fun wordMatch(text: String, phrase: String): Boolean {
        if (phrase.length <= 1) return text == phrase
        if (!text.contains(phrase)) return false
        val idx = text.indexOf(phrase)
        val before = if (idx == 0) ' ' else text[idx - 1]
        val afterIdx = idx + phrase.length
        val after = if (afterIdx >= text.length) ' ' else text[afterIdx]
        return !before.isLetterOrDigit() && !after.isLetterOrDigit()
    }
}
