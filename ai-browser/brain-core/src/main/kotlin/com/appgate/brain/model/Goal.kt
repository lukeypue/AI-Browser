package com.appgate.brain.model

import com.appgate.brain.json.JsonArray
import com.appgate.brain.json.JsonObject

enum class GoalIntent { FIND_LISTINGS, INSPECT_ITEM, PREPARE_MESSAGE, LEARN_SITE, CUSTOM }

enum class ConstraintClass { HARD, SOFT, RARE }

enum class ConstraintOp { EQ, LTE, GTE, CONTAINS, NOT_CONTAINS, BETWEEN }

/**
 * Where a constraint can be satisfied. FILTERABLE means a site facet can enforce it; the
 * *_CHECK sources mean the brain must read the card / detail text; TEXT_EVIDENCE means it
 * is a rare, unstructured fact ("3.73 gears") that only a description can prove.
 */
enum class ConstraintSource { FILTERABLE, CARD_CHECK, DETAIL_CHECK, TEXT_EVIDENCE }

data class Constraint(
    val key: String,                     // canonical key: make, model, price, mileage, year, axle_ratio, keyword ...
    val op: ConstraintOp,
    val value: String,
    val value2: String? = null,          // BETWEEN upper bound
    val cls: ConstraintClass = ConstraintClass.HARD,
    val sources: Set<ConstraintSource> = setOf(ConstraintSource.FILTERABLE, ConstraintSource.CARD_CHECK),
    val synonyms: List<String> = emptyList(),
    val patterns: List<String> = emptyList()   // regexes for TEXT_EVIDENCE
) {
    val numericValue: Double? get() = value.replace(",", "").replace("$", "").trim().toDoubleOrNull()

    fun describe(): String = when (op) {
        ConstraintOp.EQ -> "$key = $value"
        ConstraintOp.LTE -> "$key ≤ $value"
        ConstraintOp.GTE -> "$key ≥ $value"
        ConstraintOp.CONTAINS -> "$key contains '$value'"
        ConstraintOp.NOT_CONTAINS -> "$key excludes '$value'"
        ConstraintOp.BETWEEN -> "$key in $value..$value2"
    }

    fun toJson(): JsonObject = JsonObject().put("key", key).put("op", op.name).put("value", value).put("value2", value2)
        .put("cls", cls.name).putStrings("sources", sources.map { it.name }).putStrings("synonyms", synonyms).putStrings("patterns", patterns)

    companion object {
        fun fromJson(o: JsonObject): Constraint = Constraint(
            key = o.optString("key"),
            op = ConstraintOp.values().firstOrNull { it.name == o.optString("op") } ?: ConstraintOp.EQ,
            value = o.optString("value"),
            value2 = o.optStringOrNull("value2"),
            cls = ConstraintClass.values().firstOrNull { it.name == o.optString("cls") } ?: ConstraintClass.HARD,
            sources = o.optStrings("sources").mapNotNull { s -> ConstraintSource.values().firstOrNull { it.name == s } }.toSet(),
            synonyms = o.optStrings("synonyms"),
            patterns = o.optStrings("patterns")
        )
    }
}

data class SoftPreference(val key: String, val preferMax: Boolean, val weight: Double)

data class Budget(
    val itemsInspected: Int = 40,
    val llmCalls: Int = 20,
    val actions: Int = 150,
    val wallMs: Long = 15 * 60_000L
) {
    fun toJson(): JsonObject = JsonObject().put("items", itemsInspected).put("llm", llmCalls).put("actions", actions).put("wall_ms", wallMs)
    companion object {
        fun fromJson(o: JsonObject?): Budget = if (o == null) Budget() else Budget(
            o.optInt("items", 40), o.optInt("llm", 20), o.optInt("actions", 150), o.optLong("wall_ms", 15 * 60_000L)
        )
    }
}

data class Goal(
    val id: String,
    val intent: GoalIntent,
    val rawText: String,
    val query: String,                   // the free-text search phrase (e.g. "Ford Expedition")
    val constraints: List<Constraint>,
    val soft: List<SoftPreference> = emptyList(),
    val budget: Budget = Budget(),
    val category: String? = null,        // vehicles | general | housing | jobs
    val messageDraft: String? = null,
    val warnings: List<String> = emptyList(),
    val targetUrl: String? = null        // INSPECT_ITEM / PREPARE_MESSAGE: the listing to open
) {
    val hard: List<Constraint> get() = constraints.filter { it.cls == ConstraintClass.HARD }
    val rare: List<Constraint> get() = constraints.filter { it.cls == ConstraintClass.RARE }
    val filterable: List<Constraint> get() = constraints.filter { ConstraintSource.FILTERABLE in it.sources }
    fun constraint(key: String): Constraint? = constraints.firstOrNull { it.key == key }

    fun describe(): String = buildString {
        append(intent.name.lowercase().replace('_', ' ')).append(": ").append(query)
        if (constraints.isNotEmpty()) append(" [").append(constraints.joinToString("; ") { it.describe() }).append("]")
    }

    fun toJson(): JsonObject = JsonObject()
        .put("id", id).put("intent", intent.name).put("raw", rawText).put("query", query)
        .put("constraints", JsonArray(constraints.map { it.toJson() }))
        .put("soft", JsonArray(soft.map { JsonObject().put("key", it.key).put("max", it.preferMax).put("w", it.weight) }))
        .put("budget", budget.toJson()).put("category", category).put("message", messageDraft).putStrings("warnings", warnings)
        .put("target_url", targetUrl)

    companion object {
        fun fromJson(o: JsonObject): Goal = Goal(
            id = o.optString("id"),
            intent = GoalIntent.values().firstOrNull { it.name == o.optString("intent") } ?: GoalIntent.FIND_LISTINGS,
            rawText = o.optString("raw"),
            query = o.optString("query"),
            constraints = o.optArray("constraints")?.objects()?.map { Constraint.fromJson(it) } ?: emptyList(),
            soft = o.optArray("soft")?.objects()?.map { SoftPreference(it.optString("key"), it.optBoolean("max"), it.optDouble("w", 0.5)) } ?: emptyList(),
            budget = Budget.fromJson(o.optObject("budget")),
            category = o.optStringOrNull("category"),
            messageDraft = o.optStringOrNull("message"),
            warnings = o.optStrings("warnings"),
            targetUrl = o.optStringOrNull("target_url")
        )
    }
}

enum class Verdict { SAT, VIOLATED, UNKNOWN }

data class Evidence(val key: String, val span: String, val method: String, val urlPattern: String = "")

/** Per-item constraint verdicts with the evidence that produced them; UNKNOWN is first-class. */
data class ItemVerdict(
    val itemKey: String,
    val title: String,
    val hrefPath: String?,
    val url: String?,
    val price: Int?,
    val mileage: Int?,
    val year: Int?,
    val perConstraint: Map<String, Verdict>,
    val evidence: List<Evidence>,
    val inspectedDetail: Boolean,
    val softScore: Double = 0.0
) {
    fun count(cls: ConstraintClass, goal: Goal, v: Verdict): Int =
        goal.constraints.filter { it.cls == cls }.count { perConstraint[it.key] == v }

    fun hardViolations(goal: Goal) = count(ConstraintClass.HARD, goal, Verdict.VIOLATED)
    fun hardUnknown(goal: Goal) = count(ConstraintClass.HARD, goal, Verdict.UNKNOWN)
    fun rareSatisfied(goal: Goal) = count(ConstraintClass.RARE, goal, Verdict.SAT)
    fun rareViolated(goal: Goal) = count(ConstraintClass.RARE, goal, Verdict.VIOLATED)

    fun tier(goal: Goal): ResultTier = when {
        hardViolations(goal) > 0 -> ResultTier.NEAR_MISS
        goal.rare.isNotEmpty() && rareSatisfied(goal) == goal.rare.size && hardUnknown(goal) == 0 -> ResultTier.VERIFIED
        goal.rare.isEmpty() && hardUnknown(goal) == 0 -> ResultTier.VERIFIED
        else -> ResultTier.PARTIAL
    }

    fun toJson(): JsonObject = JsonObject().put("key", itemKey).put("title", title).put("path", hrefPath).put("url", url)
        .put("price", price).put("mileage", mileage).put("year", year)
        .put("verdicts", JsonObject().also { j -> perConstraint.forEach { (k, v) -> j.put(k, v.name) } })
        .put("evidence", JsonArray(evidence.map { JsonObject().put("key", it.key).put("span", it.span).put("method", it.method) }))
        .put("inspected", inspectedDetail).put("soft", softScore)
}

enum class ResultTier { VERIFIED, PARTIAL, NEAR_MISS }

data class TaskResult(
    val goal: Goal,
    val verified: List<ItemVerdict>,
    val partial: List<ItemVerdict>,
    val nearMiss: List<ItemVerdict>,
    val inspected: Int,
    val notes: List<String>,
    val status: String
) {
    val total: Int get() = verified.size + partial.size + nearMiss.size
}
