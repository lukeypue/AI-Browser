package com.appgate.brain.goal

import com.appgate.brain.model.Constraint
import com.appgate.brain.model.ConstraintClass
import com.appgate.brain.model.ConstraintOp
import com.appgate.brain.model.ConstraintSource
import com.appgate.brain.model.Evidence
import com.appgate.brain.model.Goal
import com.appgate.brain.model.ItemSummary
import com.appgate.brain.model.ItemVerdict
import com.appgate.brain.model.ResultTier
import com.appgate.brain.model.TaskResult
import com.appgate.brain.model.Verdict
import com.appgate.brain.perception.Redactor
import com.appgate.brain.util.Text

/**
 * Evaluates listings against a goal with explicit evidence. UNKNOWN is a first-class
 * verdict: a constraint the card could not prove stays UNKNOWN until the detail page is read,
 * and stays UNKNOWN (never VIOLATED) if the description simply does not mention it.
 */
object ConstraintEvaluator {

    fun fromCard(goal: Goal, item: ItemSummary, host: String): ItemVerdict {
        val text = item.title + " " + item.snippet
        val verdicts = LinkedHashMap<String, Verdict>()
        val evidence = ArrayList<Evidence>()
        for (c in goal.constraints) {
            val v = evaluate(c, text, item.price, item.mileage, item.year, detail = false, evidence)
            verdicts[c.key] = v
        }
        return ItemVerdict(
            itemKey = item.key,
            title = item.title,
            hrefPath = item.hrefPath,
            url = item.hrefPath?.let { if (it.startsWith("http")) it else "https://$host$it" },
            price = item.price,
            mileage = item.mileage,
            year = item.year,
            perConstraint = verdicts,
            evidence = evidence,
            inspectedDetail = false,
            softScore = softScore(goal, item.price, item.mileage, item.year)
        )
    }

    /** Re-evaluate with the detail page text; card verdicts that were SAT/VIOLATED on structured fields are kept. */
    fun withDetail(goal: Goal, previous: ItemVerdict, detailText: String, detailUrl: String?): ItemVerdict {
        val text = previous.title + " " + detailText
        val price = previous.price ?: Text.price(detailText)
        val mileage = previous.mileage ?: Text.mileage(detailText)
        val year = previous.year ?: Text.year(previous.title) ?: Text.year(detailText.take(300))
        val verdicts = LinkedHashMap<String, Verdict>(previous.perConstraint)
        val evidence = ArrayList<Evidence>(previous.evidence)
        for (c in goal.constraints) {
            val prior = previous.perConstraint[c.key]
            if (prior == Verdict.SAT && c.sources.none { it == ConstraintSource.TEXT_EVIDENCE }) continue
            val v = evaluate(c, text, price, mileage, year, detail = true, evidence)
            verdicts[c.key] = if (v == Verdict.UNKNOWN && prior == Verdict.VIOLATED) Verdict.VIOLATED else v
        }
        return previous.copy(
            url = detailUrl ?: previous.url,
            price = price, mileage = mileage, year = year,
            perConstraint = verdicts,
            evidence = evidence.distinctBy { it.key + it.span },
            inspectedDetail = true,
            softScore = softScore(goal, price, mileage, year)
        )
    }

    fun evaluate(c: Constraint, text: String, price: Int?, mileage: Int?, year: Int?, detail: Boolean, evidence: MutableList<Evidence>): Verdict {
        val lower = text.lowercase()
        if (c.key == "axle_ratio" && c.op == ConstraintOp.CONTAINS && detail) {
            val wanted = Regex("[0-9][.:][0-9]{2}").find(c.value)?.value?.replace(':', '.')
            val context = "(?:axle(?: ratio)?|gear(?:s| ratio)?|rear end|differential(?: ratio)?)"
            val ratio = "([0-9][.:][0-9]{2})"
            val matches = (Regex("\\b$ratio\\s+$context\\b").findAll(lower).map { it.groupValues[1] } +
                Regex("\\b$context\\s*[:=]?\\s*$ratio\\b").findAll(lower).map { it.groupValues[1] }).map { it.replace(':', '.') }.toSet()
            if (wanted != null && matches.isNotEmpty()) {
                if (matches.size > 1) return Verdict.UNKNOWN
                if (matches.single() != wanted) {
                    evidence += Evidence(c.key, window(text, lower.indexOf(matches.single()).coerceAtLeast(0)), "text")
                    return Verdict.VIOLATED
                }
            }
        }
        return when (c.key) {
            "price" -> numeric(c, price, "price", evidence)
            "mileage" -> numeric(c, mileage, "mileage", evidence)
            "year" -> numeric(c, year, "year", evidence)
            "make", "model", "keyword" -> {
                val needed = if (c.key == "keyword") c.synonyms.ifEmpty { Text.tokens(c.value) } else listOf(c.value) + c.synonyms
                val found = needed.any { Text.containsAll(lower, it.lowercase()) } || (c.key == "keyword" && Text.tokens(c.value).count { it in lower } >= Text.tokens(c.value).size * 0.7)
                if (found) { evidence += Evidence(c.key, span(lower, needed), "text"); Verdict.SAT }
                else if (c.key == "keyword" && !detail) Verdict.UNKNOWN
                else if (c.key == "model" && !detail) Verdict.UNKNOWN
                else if (detail) Verdict.VIOLATED else Verdict.UNKNOWN
            }
            else -> when (c.op) {
                ConstraintOp.NOT_CONTAINS -> {
                    val hit = (listOf(c.value) + c.synonyms).any { Text.containsAll(lower, it.lowercase()) }
                    if (hit) { evidence += Evidence(c.key, span(lower, listOf(c.value)), "text"); Verdict.VIOLATED } else if (detail) Verdict.SAT else Verdict.UNKNOWN
                }
                ConstraintOp.CONTAINS -> {
                    val byPattern = c.patterns.firstNotNullOfOrNull { p -> runCatching { Regex(p, RegexOption.IGNORE_CASE).find(text) }.getOrNull() }
                    val bySynonym = (listOf(c.value) + c.synonyms).firstOrNull { Text.containsAll(lower, it.lowercase()) }
                    when {
                        byPattern != null -> { evidence += Evidence(c.key, window(text, byPattern.range.first), "pattern"); Verdict.SAT }
                        bySynonym != null -> { evidence += Evidence(c.key, span(lower, listOf(bySynonym)), "synonym"); Verdict.SAT }
                        else -> Verdict.UNKNOWN   // absence of evidence is not evidence of absence
                    }
                }
                else -> Verdict.UNKNOWN
            }
        }
    }

    private fun numeric(c: Constraint, actual: Int?, label: String, evidence: MutableList<Evidence>): Verdict {
        val target = c.numericValue ?: return Verdict.UNKNOWN
        if (actual == null) return Verdict.UNKNOWN
        val ok = when (c.op) {
            ConstraintOp.LTE -> actual <= target
            ConstraintOp.GTE -> actual >= target
            ConstraintOp.EQ -> actual.toDouble() == target
            ConstraintOp.BETWEEN -> actual >= target && actual <= (c.value2?.toDoubleOrNull() ?: Double.MAX_VALUE)
            else -> true
        }
        evidence += Evidence(c.key, "$label=${Text.formatNumber(actual)}", "structured")
        return if (ok) Verdict.SAT else Verdict.VIOLATED
    }

    private fun span(lower: String, needles: List<String>): String {
        for (n in needles) {
            val idx = lower.indexOf(n.lowercase().substringBefore(' '))
            if (idx >= 0) return window(lower, idx)
        }
        return needles.firstOrNull().orEmpty().take(60)
    }

    private fun window(text: String, index: Int): String {
        val start = (index - 40).coerceAtLeast(0)
        val end = (index + 60).coerceAtMost(text.length)
        return Redactor.snippet(text.substring(start, end), 120)
    }

    fun softScore(goal: Goal, price: Int?, mileage: Int?, year: Int?): Double {
        var score = 0.0
        for (s in goal.soft) {
            val v = when (s.key) { "price" -> price?.toDouble(); "mileage" -> mileage?.toDouble(); "year" -> year?.toDouble(); else -> null } ?: continue
            val normalized = when (s.key) { "price" -> 1 - (v / 100_000).coerceIn(0.0, 1.0); "mileage" -> 1 - (v / 300_000).coerceIn(0.0, 1.0); "year" -> ((v - 1990) / 40).coerceIn(0.0, 1.0); else -> 0.5 }
            score += s.weight * (if (s.preferMax) normalized else 1 - normalized)
        }
        return score
    }

    /** Near-miss = exactly one HARD numeric constraint violated by less than 10 percent. */
    fun isNearMiss(goal: Goal, v: ItemVerdict): Boolean {
        val violated = goal.hard.filter { v.perConstraint[it.key] == Verdict.VIOLATED }
        if (violated.size != 1) return false
        val c = violated.first()
        val target = c.numericValue ?: return false
        val actual = when (c.key) { "price" -> v.price; "mileage" -> v.mileage; "year" -> v.year; else -> null }?.toDouble() ?: return false
        val slack = kotlin.math.abs(actual - target) / target
        return slack < 0.10
    }

    fun rank(goal: Goal, verdicts: Collection<ItemVerdict>): List<ItemVerdict> = verdicts.sortedWith(
        compareBy<ItemVerdict> { it.hardViolations(goal) }
            .thenByDescending { it.rareSatisfied(goal) }
            .thenBy { it.rareViolated(goal) }
            .thenBy { it.hardUnknown(goal) }
            .thenByDescending { it.softScore }
            .thenBy { it.price ?: Int.MAX_VALUE }
    )

    fun summarize(goal: Goal, verdicts: Collection<ItemVerdict>, inspected: Int, notes: List<String>, status: String): TaskResult {
        val ranked = rank(goal, verdicts)
        val verified = ranked.filter { it.tier(goal) == ResultTier.VERIFIED }
        val partial = ranked.filter { it.tier(goal) == ResultTier.PARTIAL }
        val near = ranked.filter { it.tier(goal) == ResultTier.NEAR_MISS && isNearMiss(goal, it) }
        val extraNotes = notes.toMutableList()
        for (rare in goal.rare) {
            val unknown = ranked.count { it.inspectedDetail && it.perConstraint[rare.key] == Verdict.UNKNOWN }
            val inspectedCount = ranked.count { it.inspectedDetail }
            if (inspectedCount > 0) extraNotes += "'${rare.value}' was not mentioned in $unknown of $inspectedCount descriptions inspected."
        }
        goal.hard.filter { ConstraintSource.FILTERABLE in it.sources }.forEach { c ->
            val unknown = ranked.count { it.perConstraint[c.key] == Verdict.UNKNOWN }
            if (unknown > 0 && unknown == ranked.size && ranked.isNotEmpty()) extraNotes += "${c.describe()} could not be verified from any listing card."
        }
        return TaskResult(goal, verified, partial, near, inspected, extraNotes, status)
    }

    fun needsDetail(goal: Goal, v: ItemVerdict): Boolean {
        if (v.inspectedDetail) return false
        if (v.hardViolations(goal) > 0) return false
        val rareUnknown = goal.rare.any { v.perConstraint[it.key] != Verdict.SAT }
        val hardUnknownDetail = goal.hard.any { v.perConstraint[it.key] == Verdict.UNKNOWN && ConstraintSource.DETAIL_CHECK in it.sources }
        return rareUnknown || hardUnknownDetail
    }

    fun cardConstraintsForClass(goal: Goal, cls: ConstraintClass): List<Constraint> = goal.constraints.filter { it.cls == cls }
}
