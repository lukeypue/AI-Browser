package com.appgate.brain.goal

import com.appgate.brain.model.Budget
import com.appgate.brain.model.Constraint
import com.appgate.brain.model.ConstraintClass
import com.appgate.brain.model.ConstraintOp
import com.appgate.brain.model.ConstraintSource
import com.appgate.brain.model.Goal
import com.appgate.brain.model.GoalIntent
import com.appgate.brain.model.SoftPreference
import com.appgate.brain.util.Hashing
import com.appgate.brain.util.Text

/**
 * Turns a plain-English request into a typed [Goal]: hard constraints the site can filter,
 * card/detail checks, rare text-evidence constraints, soft preferences and a budget.
 * Deterministic; the planner is only consulted for phrases this parser cannot type.
 */
object GoalParser {
    private val makes = listOf(
        "Acura", "Alfa Romeo", "Audi", "BMW", "Buick", "Cadillac", "Chevrolet", "Chevy", "Chrysler", "Dodge", "Fiat", "Ford", "Genesis",
        "GMC", "Honda", "Hyundai", "Infiniti", "Jaguar", "Jeep", "Kia", "Land Rover", "Lexus", "Lincoln", "Mazda", "Mercedes-Benz", "Mercedes",
        "Mini", "Mitsubishi", "Nissan", "Polestar", "Pontiac", "Porsche", "Ram", "Rivian", "Saturn", "Scion", "Subaru", "Suzuki", "Tesla",
        "Toyota", "Volkswagen", "VW", "Volvo"
    )
    private val vehicleWords = setOf("car", "cars", "truck", "trucks", "suv", "suvs", "vehicle", "vehicles", "pickup", "van", "sedan", "coupe", "minivan", "4x4", "awd", "4wd", "mileage", "miles", "axle", "diesel", "hybrid", "motorcycle", "atv", "rv", "trailer", "boat")
    private val housingWords = setOf("house", "home", "apartment", "condo", "townhouse", "rent", "rental", "bedroom", "bedrooms", "bd", "bath", "sqft", "acre", "acres", "lot", "duplex", "cabin", "studio", "lease")
    private val jobWords = setOf("job", "jobs", "hiring", "career", "position", "salary", "hourly", "part time", "part-time", "full time", "full-time", "remote work")

    private val priceRegex = Regex("(?i)\\b(?:under|below|less\\s+than|max(?:imum)?(?:\\s+price)?|up\\s+to|no\\s+more\\s+than|budget(?:\\s+of)?|<=?)\\s*\\$?\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([kK]?)(?:\\s*(?:dollars?|bucks?|usd))?\\b(?!\\s*(?:miles?|mi\\b|k\\s*miles))")
    private val priceMinRegex = Regex("(?i)\\b(?:over|above|more\\s+than|at\\s+least|min(?:imum)?(?:\\s+price)?|starting\\s+at|>=?)\\s*\\$?\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([kK]?)(?:\\s*(?:dollars?|bucks?))?\\b(?!\\s*(?:miles?|mi\\b))")
    private val priceRangeRegex = Regex("(?i)\\$?\\s*([0-9][0-9,]*)\\s*([kK]?)\\s*(?:-|to|–)\\s*\\$?\\s*([0-9][0-9,]*)\\s*([kK]?)(?:\\s*(?:dollars?|bucks?))?(?!\\s*(?:miles?|mi\\b))")
    private val dollarRegex = Regex("(?i)\\$\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([kK]?)")
    private val mileageRegex = Regex("(?i)\\b(?:under|below|less\\s+than|max(?:imum)?|up\\s+to|no\\s+more\\s+than)\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([kK]?)\\s*(?:miles?|mi\\b|k\\s*miles?)")
    private val mileageAltRegex = Regex("(?i)\\b([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([kK]?)\\s*(?:miles?|mi\\b)\\s*(?:or\\s+less|max(?:imum)?|or\\s+under|or\\s+fewer)")
    private val yearMinRegex = Regex("(?i)\\b(20[0-4][0-9]|19[5-9][0-9])\\s*(?:or\\s+newer|\\+|and\\s+up|or\\s+later|and\\s+newer)")
    private val yearMaxRegex = Regex("(?i)\\b(20[0-4][0-9]|19[5-9][0-9])\\s*(?:or\\s+older|and\\s+down|or\\s+earlier)")
    private val yearRangeRegex = Regex("(?i)\\b(20[0-4][0-9]|19[5-9][0-9])\\s*(?:-|to|–|through)\\s*(20[0-4][0-9]|19[5-9][0-9])\\b")
    private val yearSingle = Regex("\\b(20[0-4][0-9]|19[5-9][0-9])\\b")
    private val newerThan = Regex("(?i)\\b(?:newer\\s+than|after)\\s+(20[0-4][0-9]|19[5-9][0-9])")
    private val rareRegex = Regex("(?i)\\b(?:with|must\\s+have|has|having|including|that\\s+has|equipped\\s+with|comes\\s+with)\\s+(?:a\\s+|an\\s+|the\\s+)?(.+?)(?=\\s+(?:under|below|less than|within|near|in|prefer|ideally|for|and\\s+(?:under|below|less)|without|no\\s)|\\s*[,;]|\\.(?:\\s|$)|\\s*$)")
    private val excludeRegex = Regex("(?i)\\b(?:without|exclude|excluding|except|no)\\s+(?:a\\s+|an\\s+|any\\s+)?([a-z0-9][a-z0-9 .-]{1,30}?)(?=\\s+(?:under|below|with|within|near|in|prefer|and|or)\\b|\\s*[,;]|\\.(?:\\s|$)|\\s*$)")
    private val preferRegex = Regex("(?i)\\b(?:prefer(?:ably|red)?|ideally|bonus\\s+if|nice\\s+to\\s+have|would\\s+like)\\s+(.+?)(?=,|;|\\.|$)")
    private val distanceRegex = Regex("(?i)\\bwithin\\s+([0-9]{1,3})\\s*(?:miles?|mi)\\b")
    private val nearRegex = Regex("(?i)\\b(?:near|around|in|close\\s+to)\\s+([A-Z][a-zA-Z.]+(?:\\s+[A-Z][a-zA-Z.]+){0,2}(?:,\\s*[A-Z]{2})?)\\b")
    private val zipRegex = Regex("\\b([0-9]{5})\\b(?!\\s*(?:miles|mi|k|dollars))")
    private val bedsRegex = Regex("(?i)\\b([0-9])\\s*(?:\\+\\s*)?(?:bed(?:room)?s?|bd|br)\\b")
    private val bathsRegex = Regex("(?i)\\b([0-9](?:\\.5)?)\\s*(?:\\+\\s*)?(?:bath(?:room)?s?|ba)\\b")
    private val messageRegex = Regex("(?i)\\b(?:message|contact|ask)\\s+(?:the\\s+)?seller\\b(?:\\s+(?:saying|with|:)\\s*[\"“]?(.+?)[\"”]?)?$")
    private val leadIn = Regex("(?i)^\\s*(?:please\\s+)?(?:can\\s+you\\s+)?(?:find|search\\s+for|look\\s+for|show\\s+me|i\\s+want|i\\s+need|i'm\\s+looking\\s+for|looking\\s+for|get\\s+me)\\s+(?:me\\s+)?(?:a\\s+|an\\s+|the\\s+|some\\s+)?")
    private val stop = setOf("a", "an", "the", "for", "with", "without", "and", "or", "under", "below", "over", "above", "to", "in", "near", "within", "used", "please", "me", "find", "want", "of", "that", "is", "are", "some", "any", "good", "cheap", "nice", "great", "prefer", "no", "not", "only", "must", "have", "has")

    fun parse(input: String, budget: Budget = Budget()): Goal {
        val raw = input.trim()
        val warnings = mutableListOf<String>()
        var working = raw
        val constraints = mutableListOf<Constraint>()
        val soft = mutableListOf<SoftPreference>()
        var intent = GoalIntent.FIND_LISTINGS
        var messageDraft: String? = null

        messageRegex.find(working)?.let { m ->
            intent = GoalIntent.PREPARE_MESSAGE
            messageDraft = m.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
            working = messageRegex.replace(working, " ")
        }

        // Mileage before price so "under 150k miles" is never read as a price.
        (mileageRegex.find(working) ?: mileageAltRegex.find(working))?.let { m ->
            val v = amount(m.groupValues[1], m.groupValues[2])
            if (v != null) constraints += Constraint("mileage", ConstraintOp.LTE, v.toString(), cls = ConstraintClass.HARD,
                sources = setOf(ConstraintSource.FILTERABLE, ConstraintSource.CARD_CHECK, ConstraintSource.DETAIL_CHECK))
            working = working.replace(m.value, " ")
        }

        // Rare / text evidence ("with a 3.73 axle", "must have sunroof").
        rareRegex.findAll(working).toList().forEach { m ->
            val phrase = m.groupValues[1].trim().trimEnd('.', ',', ';').replace(Regex("(?i)\\s+(?:and|or)$"), "")
            if (phrase.isNotBlank() && phrase.length <= 60 && !phrase.matches(Regex("(?i)\\d+\\s*(k)?\\s*(miles?|mi)"))) {
                constraints += rareConstraint(phrase)
                working = working.replace(m.value, " ")
            }
        }
        excludeRegex.findAll(working).toList().forEach { m ->
            val phrase = m.groupValues[1].trim()
            if (phrase.isNotBlank() && phrase.length <= 40) {
                constraints += Constraint("exclude_${slug(phrase)}", ConstraintOp.NOT_CONTAINS, phrase, cls = ConstraintClass.HARD,
                    sources = setOf(ConstraintSource.CARD_CHECK, ConstraintSource.DETAIL_CHECK), synonyms = listOf(phrase))
                working = working.replace(m.value, " ")
            }
        }
        preferRegex.findAll(working).toList().forEach { m ->
            val phrase = m.groupValues[1].trim()
            if (phrase.isNotBlank()) {
                val lower = phrase.lowercase()
                when {
                    lower.contains("newer") || lower.contains("recent") -> soft += SoftPreference("year", true, 0.5)
                    lower.contains("lower mile") || lower.contains("fewer mile") || lower.contains("low mile") -> soft += SoftPreference("mileage", false, 0.5)
                    lower.contains("cheap") || lower.contains("lower price") -> soft += SoftPreference("price", false, 0.5)
                    lower.contains("close") || lower.contains("near") -> soft += SoftPreference("distance", false, 0.3)
                    else -> constraints += rareConstraint(phrase, ConstraintClass.SOFT)
                }
                working = working.replace(m.value, " ")
            }
        }

        // Price
        priceRangeRegex.find(working)?.let { m ->
            val lo = amount(m.groupValues[1], m.groupValues[2]); val hi = amount(m.groupValues[3], m.groupValues[4])
            if (lo != null && hi != null && hi > lo && hi >= 100 && !isYear(lo) && !isYear(hi)) {
                constraints += Constraint("price", ConstraintOp.GTE, lo.toString())
                constraints += Constraint("price", ConstraintOp.LTE, hi.toString())
                working = working.replace(m.value, " ")
            }
        }
        priceRegex.find(working)?.let { m ->
            val v = amount(m.groupValues[1], m.groupValues[2])
            if (v != null && constraints.none { it.key == "price" && it.op == ConstraintOp.LTE }) {
                constraints += Constraint("price", ConstraintOp.LTE, v.toString(), sources = setOf(ConstraintSource.FILTERABLE, ConstraintSource.CARD_CHECK, ConstraintSource.DETAIL_CHECK))
            }
            working = working.replace(m.value, " ")
        }
        priceMinRegex.find(working)?.let { m ->
            val v = amount(m.groupValues[1], m.groupValues[2])
            if (v != null && !isYear(v)) constraints += Constraint("price", ConstraintOp.GTE, v.toString())
            working = working.replace(m.value, " ")
        }
        if (constraints.none { it.key == "price" }) {
            dollarRegex.find(working)?.let { m ->
                val v = amount(m.groupValues[1], m.groupValues[2])
                if (v != null) constraints += Constraint("price", ConstraintOp.LTE, v.toString(), sources = setOf(ConstraintSource.FILTERABLE, ConstraintSource.CARD_CHECK, ConstraintSource.DETAIL_CHECK))
                working = working.replace(m.value, " ")
            }
        }

        // Year
        yearRangeRegex.find(working)?.let { m ->
            constraints += Constraint("year", ConstraintOp.GTE, m.groupValues[1])
            constraints += Constraint("year", ConstraintOp.LTE, m.groupValues[2])
            working = working.replace(m.value, " ")
        }
        (yearMinRegex.find(working) ?: newerThan.find(working))?.let { m ->
            constraints += Constraint("year", ConstraintOp.GTE, m.groupValues[1])
            working = working.replace(m.value, " ")
        }
        yearMaxRegex.find(working)?.let { m ->
            constraints += Constraint("year", ConstraintOp.LTE, m.groupValues[1])
            working = working.replace(m.value, " ")
        }

        // Location / distance
        distanceRegex.find(working)?.let { m ->
            constraints += Constraint("distance", ConstraintOp.LTE, m.groupValues[1], cls = ConstraintClass.SOFT, sources = setOf(ConstraintSource.FILTERABLE))
            working = working.replace(m.value, " ")
        }
        zipRegex.find(working)?.let { m ->
            constraints += Constraint("zip", ConstraintOp.EQ, m.groupValues[1], cls = ConstraintClass.SOFT, sources = setOf(ConstraintSource.FILTERABLE))
            working = working.replace(m.value, " ")
        }
        nearRegex.find(working)?.let { m ->
            val place = m.groupValues[1].trim()
            if (place.isNotBlank() && place.lowercase() !in setOf("good", "great", "stock")) {
                constraints += Constraint("location", ConstraintOp.EQ, place, cls = ConstraintClass.SOFT, sources = setOf(ConstraintSource.FILTERABLE))
                working = working.replace(m.value, " ")
            }
        }

        // Housing
        bedsRegex.find(working)?.let { m ->
            constraints += Constraint("bedrooms", ConstraintOp.GTE, m.groupValues[1], sources = setOf(ConstraintSource.FILTERABLE, ConstraintSource.CARD_CHECK))
            working = working.replace(m.value, " ")
        }
        bathsRegex.find(working)?.let { m ->
            constraints += Constraint("bathrooms", ConstraintOp.GTE, m.groupValues[1], sources = setOf(ConstraintSource.FILTERABLE, ConstraintSource.CARD_CHECK))
            working = working.replace(m.value, " ")
        }

        // Core query
        var core = leadIn.replace(working, "")
            .replace(Regex("(?i)\\b(?:dollars?|bucks?|usd)\\b"), " ")
            .replace(Regex("(?i)\\b(?:for\\s+sale|used|pre-owned)\\b"), " ")
            .replace(Regex("\\s+(?:and|or|,|;)\\s*$"), " ")
            .replace(Regex("[,;]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim(' ', ',', ';', '-', '.')
        if (core.isBlank()) core = raw

        // Vehicle make/model
        val category: String
        val lowerRaw = raw.lowercase()
        val make = makes.firstOrNull { m -> Regex("(?i)\\b" + Regex.escape(m) + "\\b").containsMatchIn(core) }
        if (make != null) {
            category = "vehicles"
            val canonicalMake = when (make.lowercase()) { "chevy" -> "Chevrolet"; "vw" -> "Volkswagen"; "mercedes" -> "Mercedes-Benz"; else -> make }
            constraints += Constraint("make", ConstraintOp.EQ, canonicalMake, sources = setOf(ConstraintSource.FILTERABLE, ConstraintSource.CARD_CHECK), synonyms = listOf(make))
            val afterMake = Regex("(?i)\\b" + Regex.escape(make) + "\\b\\s*([A-Za-z0-9-]+(?:\\s+[A-Za-z0-9-]+)?)?").find(core)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            val modelTokens = afterMake.split(' ').filter { it.isNotBlank() && it.lowercase() !in stop && !yearSingle.matches(it) && !vehicleWords.contains(it.lowercase()) }
            if (modelTokens.isNotEmpty()) {
                val model = modelTokens.take(2).joinToString(" ")
                constraints += Constraint("model", ConstraintOp.EQ, model, sources = setOf(ConstraintSource.FILTERABLE, ConstraintSource.CARD_CHECK))
            }
            yearSingle.find(core)?.let { y ->
                if (constraints.none { it.key == "year" }) constraints += Constraint("year", ConstraintOp.EQ, y.value, sources = setOf(ConstraintSource.FILTERABLE, ConstraintSource.CARD_CHECK))
            }
        } else {
            category = when {
                vehicleWords.any { w -> Regex("\\b" + Regex.escape(w) + "\\b").containsMatchIn(lowerRaw) } -> "vehicles"
                housingWords.any { w -> Regex("\\b" + Regex.escape(w) + "\\b").containsMatchIn(lowerRaw) } -> "housing"
                jobWords.any { w -> Regex("\\b" + Regex.escape(w) + "\\b").containsMatchIn(lowerRaw) } -> "jobs"
                else -> "general"
            }
        }

        // Keyword constraint: the core query words must appear on the card (guards against loose site search).
        val keywordTokens = Text.tokens(core).filter { it !in stop && it.length > 1 && !isYearToken(it) }
        if (keywordTokens.isNotEmpty() && constraints.none { it.key == "make" }) {
            constraints += Constraint("keyword", ConstraintOp.CONTAINS, keywordTokens.joinToString(" "), cls = ConstraintClass.HARD,
                sources = setOf(ConstraintSource.CARD_CHECK, ConstraintSource.DETAIL_CHECK), synonyms = keywordTokens)
        }

        // Contradiction checks
        val pMin = constraints.firstOrNull { it.key == "price" && it.op == ConstraintOp.GTE }?.numericValue
        val pMax = constraints.firstOrNull { it.key == "price" && it.op == ConstraintOp.LTE }?.numericValue
        if (pMin != null && pMax != null && pMin > pMax) warnings += "Minimum price is above maximum price; ignoring the minimum."
        val yMin = constraints.firstOrNull { it.key == "year" && it.op == ConstraintOp.GTE }?.numericValue
        if (yMin != null && pMax != null && yMin >= 2022 && pMax < 10_000) warnings += "A ${yMin.toInt()}+ vehicle under \$${Text.formatNumber(pMax.toInt())} is unlikely; expect few or no verified matches."
        val cleaned = if (pMin != null && pMax != null && pMin > pMax) constraints.filterNot { it.key == "price" && it.op == ConstraintOp.GTE } else constraints

        val id = Hashing.short(raw + System.nanoTime())
        return Goal(
            id = id,
            intent = intent,
            rawText = raw,
            query = core,
            constraints = cleaned.distinctBy { it.key + it.op + it.value },
            soft = soft,
            budget = budget,
            category = category,
            messageDraft = messageDraft,
            warnings = warnings
        )
    }

    private fun rareConstraint(phrase: String, cls: ConstraintClass = ConstraintClass.RARE): Constraint {
        val p = phrase.trim()
        val patterns = mutableListOf<String>()
        val synonyms = mutableListOf(p)
        val ratio = Regex("([0-9])[.:]([0-9]{2})").find(p)
        if (ratio != null) {
            val a = ratio.groupValues[1]; val b = ratio.groupValues[2]
            patterns += "\\b$a[.:]$b\\b"
            patterns += "\\b$a$b\\b(?=.*(?:gear|axle|rear|ratio|diff))"
            synonyms += listOf("$a.$b gears", "$a.$b axle", "$a.$b rear end", "$a$b rear end", "$a.$b ratio")
        }
        val lower = p.lowercase()
        when {
            lower.contains("sunroof") || lower.contains("moonroof") -> synonyms += listOf("sunroof", "moonroof", "moon roof", "sun roof")
            lower.contains("tow") -> synonyms += listOf("tow package", "towing package", "tow pkg", "trailer hitch", "hitch")
            lower.contains("leather") -> synonyms += listOf("leather seats", "leather interior")
            lower.contains("4x4") || lower.contains("4wd") -> synonyms += listOf("4x4", "4wd", "four wheel drive", "4-wheel drive")
            lower.contains("awd") -> synonyms += listOf("awd", "all wheel drive", "all-wheel drive")
            lower.contains("third row") || lower.contains("3rd row") -> synonyms += listOf("third row", "3rd row", "7 passenger", "8 passenger", "seats 7", "seats 8")
            lower.contains("heated") -> synonyms += listOf("heated seats", "heated leather")
            lower.contains("remote start") -> synonyms += listOf("remote start", "remote starter")
            lower.contains("navigation") || lower.contains("nav") -> synonyms += listOf("navigation", "nav system", "gps")
            lower.contains("lift") -> synonyms += listOf("lift kit", "lifted")
        }
        return Constraint(
            key = if (ratio != null) "axle_ratio" else "feature_" + slug(p),
            op = ConstraintOp.CONTAINS,
            value = p,
            cls = cls,
            sources = setOf(ConstraintSource.TEXT_EVIDENCE, ConstraintSource.DETAIL_CHECK),
            synonyms = synonyms.distinct(),
            patterns = patterns
        )
    }

    private fun slug(text: String): String = text.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(24)

    private fun amount(number: String, suffix: String): Int? {
        val base = number.replace(",", "").toDoubleOrNull() ?: return null
        return (base * if (suffix.equals("k", true)) 1000.0 else 1.0).toInt()
    }

    private fun isYear(v: Int): Boolean = v in 1950..2049
    private fun isYearToken(t: String): Boolean = t.length == 4 && t.toIntOrNull()?.let { it in 1950..2049 } == true
}
