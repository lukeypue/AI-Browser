package com.appgate.brain.engine

import com.appgate.brain.model.Budget
import com.appgate.brain.model.Constraint
import com.appgate.brain.model.ConstraintClass
import com.appgate.brain.model.ConstraintOp
import com.appgate.brain.model.ConstraintSource
import com.appgate.brain.model.CurriculumItem
import com.appgate.brain.model.Goal
import com.appgate.brain.model.GoalIntent
import com.appgate.brain.model.SiteModel
import com.appgate.brain.model.TaskLedger
import com.appgate.brain.profile.SiteProfile
import com.appgate.brain.util.Hashing

/**
 * A curriculum replaces rotation timers: each site has an ordered list of goals with
 * completion predicates, and the learner rotates when the curriculum is complete or three
 * goals in a row are blocked. Items are keyed by the skill whose verified success completes them.
 */
object Curriculum {
    private val items = listOf(
        "search" to "Search verifies (query typed, results change)",
        "constrain_numeric" to "A numeric facet (price/mileage/year) applies and verifies",
        "select_facet" to "A choice facet (make/model/condition) applies and verifies",
        "scroll_results" to "Infinite scroll or scrolling reveals new results",
        "next_page" to "Pagination reveals a new page of results",
        "load_more" to "Load-more reveals new results",
        "sort_results" to "Sorting changes result order",
        "open_item" to "A result opens to its detail page",
        "expand_description" to "A truncated description expands",
        "go_back" to "Back returns to results",
        "dismiss_dialog" to "A blocking dialog is dismissed"
    )

    fun ensure(site: SiteModel) {
        val known = site.curriculum.map { it.id }.toSet()
        items.forEach { (id, desc) -> if (id !in known) site.curriculum += CurriculumItem(id, desc) }
    }

    fun markSkillVerified(site: SiteModel, skillId: String, now: Long) {
        ensure(site)
        site.curriculum.firstOrNull { it.id == skillId }?.let { it.completedAt = now }
    }

    fun isComplete(site: SiteModel): Boolean {
        ensure(site)
        val core = setOf("search", "open_item")
        val coreDone = site.curriculum.filter { it.id in core }.all { it.done }
        val anyPaginate = site.curriculum.filter { it.id in setOf("scroll_results", "next_page", "load_more") }.any { it.done }
        val anyFacet = site.curriculum.filter { it.id in setOf("constrain_numeric", "select_facet") }.any { it.done }
        return coreDone && anyPaginate && anyFacet
    }

    fun consecutiveBlocked(site: SiteModel): Int = site.curriculum.filter { !it.done }.take(3).count { it.blocked >= 2 }

    fun nextLesson(site: SiteModel): String {
        ensure(site)
        val order = listOf("search", "constrain_numeric", "open_item", "next_page", "load_more", "scroll_results", "select_facet", "sort_results", "expand_description", "go_back", "dismiss_dialog")
        if (isComplete(site)) return site.curriculum.filter { it.done }.minByOrNull { it.completedAt }?.id ?: "search"
        return site.curriculum.filter { !it.done }.minWithOrNull(compareBy<CurriculumItem> { it.blocked + it.unavailable }.thenBy { order.indexOf(it.id) })?.id
            ?: order[site.lessonOrdinal % order.size]
    }

    fun reviewDue(site: SiteModel, now: Long): Boolean = !isComplete(site) ||
        now - (site.curriculum.filter { it.done }.maxOfOrNull { it.completedAt } ?: 0) >= 24 * 60 * 60_000L

    /** Next training goal for a site: a small FIND_LISTINGS task whose steps exercise the unfinished items. */
    fun nextGoal(site: SiteModel, profile: SiteProfile, ordinal: Int): Goal {
        ensure(site)
        val queries = profile.trainingQueries.ifEmpty { listOf("mountain bike", "coffee table", "cordless drill") }
        val query = queries[ordinal % queries.size]
        val constraints = mutableListOf<Constraint>()
        val lesson = nextLesson(site)
        val wantNumeric = lesson == "constrain_numeric"
        if (wantNumeric) constraints += Constraint("price", ConstraintOp.LTE, listOf("8000", "10000", "5000")[ordinal % 3], sources = setOf(ConstraintSource.FILTERABLE, ConstraintSource.CARD_CHECK))
        // Combine independent filters only after both basic search and numeric lessons verify.
        if ("vehicles" in profile.categories && isComplete(site) && ordinal % 3 == 1) constraints += Constraint("mileage", ConstraintOp.LTE, "150000", sources = setOf(ConstraintSource.FILTERABLE, ConstraintSource.CARD_CHECK))
        if (lesson == "select_facet") constraints += Constraint("condition", ConstraintOp.EQ, "used", sources = setOf(ConstraintSource.FILTERABLE, ConstraintSource.CARD_CHECK))
        constraints += Constraint("keyword", ConstraintOp.CONTAINS, query, sources = setOf(ConstraintSource.CARD_CHECK), synonyms = query.lowercase().split(' '))
        val wantDetail = lesson in setOf("open_item", "expand_description", "go_back")
        val inspect = if (wantDetail) 2 else 0
        if (wantDetail) {
            // A text-evidence constraint forces the detail phase (open item, expand, read, back) to be exercised.
            constraints += Constraint("feature_learning_detail", ConstraintOp.CONTAINS, "condition described", cls = ConstraintClass.RARE,
                sources = setOf(ConstraintSource.TEXT_EVIDENCE, ConstraintSource.DETAIL_CHECK), synonyms = listOf("condition"))
        }
        return Goal(
            id = Hashing.short("learn|${site.host}|$ordinal|${System.nanoTime()}"),
            intent = GoalIntent.LEARN_SITE,
            rawText = "learn: $query",
            query = query,
            constraints = constraints,
            budget = Budget(itemsInspected = inspect.coerceAtLeast(1), llmCalls = 4, actions = 40, wallMs = 8 * 60_000L),
            category = profile.categories.firstOrNull()
        )
    }

    fun recordAttempt(site: SiteModel, ledger: TaskLedger) {
        ensure(site)
        val verifiedSkills = ledger.successfulSkills
        if (ledger.done && ledger.lesson.isNotBlank() && ledger.lesson !in ledger.attemptedSkills) {
            site.curriculum.firstOrNull { it.id == ledger.lesson && !it.done }?.let { it.unavailable++ }
        }
        site.curriculum.forEach { item ->
            if (item.done || item.id !in ledger.attemptedSkills) return@forEach
            item.attempts++
            if (item.id in verifiedSkills) item.completedAt = ledger.updatedAt.takeIf { it > 0 } ?: System.currentTimeMillis()
            else item.blocked++
        }
    }

    fun progress(site: SiteModel): String {
        ensure(site)
        val done = site.curriculum.count { it.done }
        return "$done/${site.curriculum.size} curriculum goals verified"
    }
}
