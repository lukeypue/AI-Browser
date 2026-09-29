package com.appgate.brain.memory

import com.appgate.brain.model.*
import com.appgate.brain.perception.Vocabulary
import com.appgate.brain.util.Hashing

/** Short-lived, content-free circuit breaker for overnight practice across task ledgers.
 * A changed control layout or a different procedure can be tried immediately. A repeated
 * failure is retried after five minutes; the site's one-minute scheduler is unchanged.
 */
object FailedStrategies {
    private const val WINDOW_MS = 5 * 60_000L
    private const val LIMIT = 2
    private const val MAX_RECORDS = 64

    fun facet(key: String?): String? = key?.takeIf { it.removeSuffix("_min").removeSuffix("_max") in Vocabulary.facetLexicon }

    fun key(sps: SemanticPageState, capability: String, steps: List<Step>? = null, params: Map<String, String> = emptyMap()): String {
        val controls = sps.affordances.filter { it.visible && it.role != Role.RESULT_ITEM }.map {
            "${it.role}:${facet(it.facetKey)}:${it.tag}:${it.enabled}:${it.regionRole}"
        }.distinct().sorted().joinToString("|")
        val procedure = steps?.joinToString("|") {
            val f = it.facetKey?.let { k -> if (k.startsWith("$")) params[k.drop(1)] else k }
            "${it.kind}:${it.role}:${facet(f)}:${it.submit}:${it.optional}"
        } ?: "planner"
        // Never include names, input values, queries, URLs, listing keys or page text.
        return Hashing.short("${sps.pageType}|${sps.dialogOpen}|${sps.resultKeys.isNotEmpty()}|$controls|$capability|$procedure")
    }

    fun allowed(site: SiteModel, key: String, now: Long): Boolean {
        prune(site, now)
        return (site.strategyFailures[key]?.count ?: 0) < LIMIT
    }

    fun record(site: SiteModel, key: String, success: Boolean, now: Long) {
        prune(site, now)
        if (success) site.strategyFailures.remove(key)
        else site.strategyFailures[key] = StrategyFailure(key, (site.strategyFailures[key]?.count ?: 0) + 1, now)
        if (site.strategyFailures.size > MAX_RECORDS) {
            site.strategyFailures.values.sortedBy { it.at }.take(site.strategyFailures.size - MAX_RECORDS).forEach { site.strategyFailures.remove(it.key) }
        }
    }

    private fun prune(site: SiteModel, now: Long) {
        site.strategyFailures.entries.removeAll { now < it.value.at || now - it.value.at >= WINDOW_MS }
    }
}
