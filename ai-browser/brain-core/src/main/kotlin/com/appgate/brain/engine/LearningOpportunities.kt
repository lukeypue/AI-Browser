package com.appgate.brain.engine

import com.appgate.brain.model.*
import com.appgate.brain.util.Hashing

/** Values live only in the current task. Persistent opportunities contain roles and shapes. */
data class LearningTarget(
    val skillId: String,
    val params: Map<String, String> = emptyMap(),
    val constraints: List<Constraint> = emptyList()
)

object LearningOpportunities {
    private val choiceKeys = setOf("make", "model", "condition", "fuel", "transmission", "body_style", "drivetrain", "color")
    private val numericKeys = setOf("price", "mileage", "year", "distance", "bedrooms", "bathrooms")
    private val unsafe = setOf(Role.LOGIN, Role.ACCOUNT, Role.COMPOSER_INPUT, Role.MESSAGE_SELLER, Role.ATTACH)
    private fun controls(page: SemanticPageState) = page.affordances.filter {
        it.visible && it.enabled && it.sameSite && !it.isCommit && !it.role.isCommit && it.role !in unsafe
    }
    private fun canonical(key: String?): String? = key?.takeIf { it.removeSuffix("_min").removeSuffix("_max") in choiceKeys + numericKeys }
    private fun alternative(a: Affordance): String? = a.choices.firstOrNull {
        it.isNotBlank() && !it.equals(a.value, true) && it.lowercase() !in setOf("all", "any", "select", "choose", "none")
    }
    private fun numeric(value: String?): String? = value?.replace(",", "")?.replace("$", "")?.trim()?.toDoubleOrNull()
        ?.takeIf { it.isFinite() && it >= 0 }?.let { if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString() }

    fun target(page: SemanticPageState, lesson: String): LearningTarget? {
        if (page.isHumanOnly || page.settle != Settle.IDLE || page.pageType in setOf(PageType.ERROR, PageType.MESSAGES, PageType.PROFILE)) return null
        val controls = controls(page)
        fun has(role: Role) = controls.any { it.role == role }
        if (page.dialogOpen && page.pageType != PageType.FACET_PANEL && has(Role.CLOSE)) return LearningTarget("dismiss_dialog")
        // Search is a prerequisite only while getting to a results context, never repeated on results.
        if (lesson != "dismiss_dialog" && page.pageType in setOf(PageType.HOME, PageType.SEARCH, PageType.UNKNOWN) && has(Role.SEARCH_BOX))
            return LearningTarget("search")
        val results = page.pageType == PageType.RESULTS
        val facets = results || page.pageType == PageType.FACET_PANEL
        fun openItem(): LearningTarget? = if (!results) null else controls.firstOrNull { it.role == Role.RESULT_ITEM && it.itemKey != null && it.itemKey in page.resultKeys }
            ?.let { LearningTarget("open_item", mapOf("item" to it.itemKey!!)) }
        return when (lesson) {
            "search" -> if (has(Role.SEARCH_BOX)) LearningTarget("search") else null
            "next_page" -> if (results && has(Role.PAGE_NEXT)) LearningTarget(lesson) else null
            "load_more" -> if (results && has(Role.LOAD_MORE)) LearningTarget(lesson) else null
            "scroll_results" -> if (results && page.resultKeys.isNotEmpty() && page.scrollHeight > page.scrollY + page.viewportHeight + 200) LearningTarget(lesson) else null
            "sort_results" -> if (!results) null else controls.firstOrNull { it.role == Role.SORT && alternative(it) != null }
                ?.let { LearningTarget(lesson, mapOf("order" to alternative(it)!!)) }
            "select_facet" -> if (!facets) null else {
                val facet = controls.firstOrNull { it.role == Role.FACET && it.facetKey in choiceKeys && alternative(it) != null }
                if (facet != null) {
                    val key = facet.facetKey!!; val value = alternative(facet)!!
                    LearningTarget(lesson, mapOf("key" to key, "value" to value), listOf(Constraint(key, ConstraintOp.EQ, value,
                        sources = setOf(ConstraintSource.FILTERABLE))))
                } else if (page.pageType != PageType.FACET_PANEL && has(Role.FACET_OPEN)) LearningTarget("open_filters") else null
            }
            "constrain_numeric" -> if (!facets) null else {
                val facet = controls.firstOrNull { it.role == Role.FACET && canonical(it.facetKey)?.removeSuffix("_min")?.removeSuffix("_max") in numericKeys &&
                    (it.facetKind in setOf("numeric_min", "numeric_max", "range") || it.inputType == "number" ||
                        (it.facetKind == "choice" && it.choices.any { choice -> numeric(choice) != null && numeric(choice) != numeric(it.value) })) }
                if (facet != null) {
                    val base = facet.facetKey!!.removeSuffix("_min").removeSuffix("_max")
                    val minimum = facet.facetKey.endsWith("_min") || facet.facetKind == "numeric_min" ||
                        (base == "year" && !facet.facetKey.endsWith("_max") && facet.facetKind != "numeric_max")
                    val key = base + if (minimum) "_min" else "_max"
                    val defaults = when (base) { "mileage" -> listOf("150000", "100000"); "year" -> listOf("2015", "2018"); "distance" -> listOf("50", "100");
                        "bedrooms", "bathrooms" -> listOf("2", "3"); else -> listOf("8000", "10000") }
                    val value = if (facet.facetKind == "choice") facet.choices.mapNotNull { numeric(it) }.first { it != numeric(facet.value) }
                        else defaults.first { it != numeric(facet.value) }
                    LearningTarget(lesson, mapOf("key" to key, "value" to value), listOf(Constraint(base, if (minimum) ConstraintOp.GTE else ConstraintOp.LTE,
                        value, sources = setOf(ConstraintSource.FILTERABLE))))
                } else if (page.pageType != PageType.FACET_PANEL && has(Role.FACET_OPEN)) LearningTarget("open_filters") else null
            }
            "open_item" -> openItem()
            "expand_description" -> if (page.pageType == PageType.DETAIL && has(Role.EXPAND_TEXT)) LearningTarget(lesson) else openItem()
            "go_back" -> if (page.pageType == PageType.DETAIL) LearningTarget(lesson) else openItem()
            "dismiss_dialog" -> null
            else -> null
        }
    }

    /** Observations from unrelated page contexts cannot erase a lesson seen elsewhere. */
    fun observe(site: SiteModel, page: SemanticPageState, now: Long) {
        if (page.isHumanOnly || page.settle != Settle.IDLE || page.host.removePrefix("www.") != site.host.removePrefix("www.")) return
        val relevant = when (page.pageType) {
            PageType.HOME, PageType.SEARCH, PageType.UNKNOWN -> setOf("search", "dismiss_dialog")
            PageType.RESULTS -> setOf("search", "constrain_numeric", "select_facet", "next_page", "load_more", "scroll_results", "sort_results", "open_item", "dismiss_dialog") +
                site.curriculum.filter { it.id in setOf("expand_description", "go_back") && it.observedAt == 0L }.map { it.id }
            PageType.FACET_PANEL -> setOf("constrain_numeric", "select_facet")
            PageType.DETAIL -> setOf("expand_description", "go_back", "dismiss_dialog")
            PageType.DIALOG -> setOf("dismiss_dialog")
            else -> emptySet()
        }
        if (relevant.isEmpty()) return
        site.learningObservedAt = now
        val safe = controls(page)
        site.learningFacetKeys += safe.filter { it.role == Role.FACET || it.role == Role.FACET_OPEN }.mapNotNull { canonical(it.facetKey) }
        for (item in site.curriculum.filter { it.id in relevant }) {
            val target = target(page, item.id)
            // A dialog prerequisite is not evidence of the hidden page's other capabilities.
            if (target?.skillId == "dismiss_dialog" && item.id != "dismiss_dialog") continue
            val shape = Hashing.short("${page.pageType}|${page.dialogOpen}|${page.resultKeys.isNotEmpty()}|" + safe.map {
                "${it.role}:${canonical(it.facetKey)}:${it.facetKind}:${it.tag}:${it.choices.size}"
            }.distinct().sorted().joinToString("|"))
            // A normal results -> drawer -> results cycle is not a repaired layout.
            val changedLayout = item.observedPage == page.pageType.name && item.opportunityShape.isNotBlank() && item.opportunityShape != shape
            val newlyExposedTarget = item.opportunity == "ABSENT" && target?.skillId == item.id
            if (changedLayout || newlyExposedTarget) item.retryAt = 0L
            item.opportunityShape = shape
            item.observedPage = page.pageType.name
            item.observedAt = now
            item.opportunity = if (target != null) "AVAILABLE" else "ABSENT"
        }
    }
}
