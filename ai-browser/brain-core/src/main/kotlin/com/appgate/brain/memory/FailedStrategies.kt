package com.appgate.brain.memory

import com.appgate.brain.model.*
import com.appgate.brain.perception.Vocabulary
import com.appgate.brain.util.Hashing

/** Bounded, content-free circuit breaker for overnight practice across task ledgers.
 * A changed control layout or a different procedure can be tried immediately. A repeated
 * failure history survives site retries; the site's thirty-second scheduler is unchanged.
 * Two failures hold the unchanged procedure for six hours, unless verification clears it.
 */
object FailedStrategies {
    private const val WINDOW_MS = 6 * 60 * 60_000L
    private const val LIMIT = 2
    private const val MAX_RECORDS = 64

    fun facet(key: String?): String? = key?.takeIf { it.removeSuffix("_min").removeSuffix("_max") in Vocabulary.facetLexicon }

    /** Only controls for the failing operation can reopen its cooldown. Listings,
     * filter option counts and unrelated header controls routinely change on live pages.
     * This signature remains content-free and ignores result identities/counts. */
    fun controlShape(sps: SemanticPageState, capability: String): String {
        val roles = when (capability) {
            "search" -> setOf(Role.SEARCH_BOX, Role.SUBMIT)
            "open_item", "go_back" -> setOf(Role.RESULT_ITEM)
            "expand_description" -> setOf(Role.EXPAND_TEXT, Role.RESULT_ITEM)
            "next_page" -> setOf(Role.PAGE_NEXT)
            "load_more" -> setOf(Role.LOAD_MORE)
            "scroll_results" -> setOf(Role.RESULT_ITEM, Role.LOAD_MORE, Role.PAGE_NEXT)
            "sort_results" -> setOf(Role.SORT)
            "dismiss_dialog" -> setOf(Role.CLOSE)
            "constrain_numeric", "select_facet", "open_filters", "open_facet", "apply_filters" -> setOf(Role.FACET, Role.FACET_OPEN, Role.FACET_APPLY, Role.CLOSE)
            else -> null
        }
        return sps.affordances.filter { it.visible && (roles == null || it.role in roles) }.map {
            "${it.role}:${facet(it.facetKey)}:${it.facetKind}:${it.tag}:${it.enabled}:${it.regionRole}"
        }.distinct().sorted().joinToString("|")
    }

    fun key(sps: SemanticPageState, capability: String, steps: List<Step>? = null, params: Map<String, String> = emptyMap()): String {
        val controls = controlShape(sps, capability)
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
