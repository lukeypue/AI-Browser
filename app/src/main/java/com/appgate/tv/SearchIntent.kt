package com.appgate.tv

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class ParsedSearch(
    val raw: String,
    val coreQuery: String,
    val maxPrice: Int?,
    val maxMileage: Int?,
    val requiredTerms: List<String>
)

object SearchIntentParser {
    private val mileageRegex = Regex("""(?i)\b(?:under|below|less\s+than|max(?:imum)?|up\s+to)\s*([\d,.]+)\s*([kK]?)\s*(?:miles?|mi)\b""")
    private val deepRegex = Regex("""(?i)\b(?:with|must\s+have|including)\s+(?:a\s+|an\s+)?(.+?)\s*$""")
    private val priceRegex = Regex("""(?i)\b(?:under|below|less\s+than|max(?:imum)?(?:\s+price)?|up\s+to)\s*\$?\s*([\d,.]+)\s*([kK]?)\s*(?:dollars?|bucks?)?\b""")

    fun parse(input: String): ParsedSearch {
        val raw = input.trim()
        var working = raw

        val mileage = mileageRegex.find(working)?.let { parseAmount(it.groupValues[1], it.groupValues[2]) }
        working = mileageRegex.replace(working, " ")

        val required = deepRegex.find(working)?.groupValues?.getOrNull(1)?.trim()?.trimEnd('.', ',', ';')
        working = deepRegex.replace(working, " ")

        val price = priceRegex.find(working)?.let { parseAmount(it.groupValues[1], it.groupValues[2]) }
        working = priceRegex.replace(working, " ")

        val core = working
            .replace(Regex("""(?i)^\s*(?:please\s+)?(?:find|search\s+for|look\s+for|show\s+me)\s+(?:me\s+)?(?:a\s+|an\s+|the\s+)?"""), "")
            .replace(Regex("""(?i)\b(?:and|for)\s+(?:dollars?|bucks?)\b"""), " ")
            .replace(Regex("""(?i)\b(?:dollars?|bucks?)\b"""), " ")
            .replace(Regex("""(?i)\s+and\s*$"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', ',', ';', '-')
            .ifBlank { raw }

        return ParsedSearch(
            raw = raw,
            coreQuery = core,
            maxPrice = price,
            maxMileage = mileage,
            requiredTerms = listOfNotNull(required?.takeIf { it.isNotBlank() })
        )
    }

    private fun parseAmount(number: String, suffix: String): Int {
        val base = number.replace(",", "").toDoubleOrNull() ?: 0.0
        return (base * if (suffix.equals("k", true)) 1000.0 else 1.0).toInt()
    }
}

object SearchMatcher {
    private val moneyRegex = Regex("""\$\s*([\d,]+(?:\.\d{1,2})?)""")
    private val milesRegex = Regex("""(?i)([\d,]+)\s*(?:miles?|mi)\b""")
    private val tokenRegex = Regex("""[a-z0-9]+(?:\.[0-9]+)?""")
    private val stopWords = setOf("a", "an", "the", "with", "and", "or", "for", "of", "to", "find", "search", "show", "me")

    fun summaryCouldMatch(summary: String, parsed: ParsedSearch): Boolean {
        val lower = summary.lowercase()
        val coreTokens = tokens(parsed.coreQuery).filterNot { it in stopWords || it.length < 2 }
        if (coreTokens.isNotEmpty() && !coreTokens.all { lower.contains(it) }) return false

        parsed.maxPrice?.let { ceiling ->
            val prices = moneyRegex.findAll(summary).mapNotNull { match ->
                val after = summary.substring(match.range.last + 1, minOf(summary.length, match.range.last + 16)).lowercase()
                if (after.contains("/mo") || after.contains("/month") || after.contains("per mo")) null
                else match.groupValues[1].replace(",", "").toDoubleOrNull()?.toInt()
            }.toList()
            if (prices.isEmpty()) return false
            if (prices.minOrNull()!! > ceiling) return false
        }

        parsed.maxMileage?.let { ceiling ->
            val miles = milesRegex.find(summary)?.groupValues?.getOrNull(1)?.replace(",", "")?.toIntOrNull()
            if (miles == null) return false
            if (miles > ceiling) return false
        }
        return true
    }

    fun deepTextMatches(text: String, parsed: ParsedSearch): Boolean {
        if (parsed.requiredTerms.isEmpty()) return true
        val haystack = tokens(text).toSet()
        return parsed.requiredTerms.all { term ->
            val needed = tokens(term).filterNot { it in stopWords }
            needed.isNotEmpty() && needed.all { it in haystack }
        }
    }

    private fun tokens(value: String): List<String> = tokenRegex.findAll(value.lowercase()).map { it.value }.toList()
}

object SearchUrlBuilder {
    private val vehicleMakes = listOf(
        "Acura", "Audi", "BMW", "Buick", "Cadillac", "Chevrolet", "Chevy", "Chrysler", "Dodge",
        "Ford", "GMC", "Honda", "Hyundai", "Infiniti", "Jeep", "Kia", "Land Rover", "Lexus",
        "Lincoln", "Mazda", "Mercedes-Benz", "Mitsubishi", "Nissan", "Porsche", "Ram", "Subaru",
        "Tesla", "Toyota", "Volkswagen", "Volvo"
    )

    fun build(sourceKey: String, template: String, parsed: ParsedSearch): String {
        if (sourceKey == "ksl_cars") return buildKslCars(parsed)

        val encoded = encode(parsed.coreQuery)
        var url = template.replace("{q}", encoded)
        when (sourceKey) {
            "ebay" -> parsed.maxPrice?.let { url += "&_udhi=$it" }
            "craigslist" -> parsed.maxPrice?.let { url += "&max_price=$it" }
            "facebook_marketplace" -> parsed.maxPrice?.let { url += "&maxPrice=$it" }
            "autotrader" -> parsed.maxPrice?.let { url += "&maxPrice=$it" }
            "cars_com" -> parsed.maxPrice?.let { url += "&list_price_max=$it" }
        }
        return url
    }

    private fun buildKslCars(parsed: ParsedSearch): String {
        val query = parsed.coreQuery.trim()
        val make = vehicleMakes.firstOrNull { query.startsWith(it, ignoreCase = true) }
        var url = if (make != null) {
            val model = query.substring(make.length).trim().trim(',', '-', ' ')
            if (model.isNotBlank()) {
                "https://cars.ksl.com/search/make/${encodePath(make)}/model/${encodePath(titleCaseWords(model))}"
            } else {
                "https://cars.ksl.com/search/make/${encodePath(make)}"
            }
        } else {
            "https://cars.ksl.com/search/keyword/${encodePath(query)}"
        }
        parsed.maxPrice?.let { url += "/priceFrom/0/priceTo/$it" }
        parsed.maxMileage?.let { url += "/mileageFrom/0/mileageTo/$it" }
        return url
    }

    private fun titleCaseWords(value: String): String = value.split(Regex("""\s+""")).joinToString(" ") { word ->
        if (word.isBlank()) word else word.lowercase().replaceFirstChar { c -> c.uppercase() }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
    private fun encodePath(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%2B")
}
