package com.appgate.brain.engine

import com.appgate.brain.model.Budget
import com.appgate.brain.model.Constraint
import com.appgate.brain.model.ConstraintOp
import com.appgate.brain.model.ConstraintSource
import com.appgate.brain.model.CurriculumItem
import com.appgate.brain.model.Goal
import com.appgate.brain.model.GoalIntent
import com.appgate.brain.model.SiteModel
import com.appgate.brain.model.SemanticPageState
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

    /** Basic search readiness is not completion of every available lesson. */
    fun allLessonsComplete(site: SiteModel): Boolean {
        ensure(site)
        return site.curriculum.all { it.done }
    }

    /** Empty means recheck later; a live page also guards against stale opportunities. */
    fun nextLesson(site: SiteModel, now: Long = System.currentTimeMillis(), page: SemanticPageState? = null): String {
        ensure(site)
        val order = listOf("search", "constrain_numeric", "open_item", "next_page", "load_more", "scroll_results", "select_facet", "sort_results", "expand_description", "go_back", "dismiss_dialog")
        val eligible = site.curriculum.filter {
            it.retryAt <= now && (it.opportunity == "AVAILABLE" || (site.learningObservedAt == 0L && it.opportunity == "UNKNOWN")) &&
                (page == null || LearningOpportunities.target(page, it.id) != null)
        }
        if (allLessonsComplete(site)) return eligible.minByOrNull { it.completedAt }?.id.orEmpty()
        return eligible.filter { !it.done }.minWithOrNull(compareBy<CurriculumItem> { it.blocked + it.unavailable }.thenBy { order.indexOf(it.id) })?.id.orEmpty()
    }

    /**
     * Why no lesson is available right now. Typed so an hour of "available=false" rechecks can be
     * diagnosed from the log alone (review finding, September 30 2026): distinguish a cooldown
     * from an absent control, a busy page, a wrong page type, or a missing observation.
     */
    fun unavailableReason(site: SiteModel, now: Long, page: SemanticPageState?): String {
        ensure(site)
        if (page == null) return "probe_unavailable"
        if (page.isHumanOnly) return "human_hold"
        if (page.settle != com.appgate.brain.model.Settle.IDLE) return "page_busy"
        val pending = site.curriculum.filter { !it.done }
        if (pending.isEmpty()) return "all_lessons_complete"
        val cooling = pending.filter { it.retryAt > now }
        if (cooling.size == pending.size) return "cooldown:" + ((cooling.minOf { it.retryAt } - now + 59_999L) / 60_000L) + "m"
        val absent = pending.filter { it.retryAt <= now && LearningOpportunities.target(page, it.id) == null }
        if (absent.size == pending.count { it.retryAt <= now }) return "no_target_on_${page.pageType.name.lowercase()}"
        return "not_observed_available"
    }

    /** Earliest moment any unfinished lesson leaves its cooldown; 0 when one is already eligible. */
    fun earliestRetryAt(site: SiteModel, now: Long): Long {
        ensure(site)
        val pending = site.curriculum.filter { !it.done }
        if (pending.isEmpty() || pending.any { it.retryAt <= now }) return 0L
        return pending.minOf { it.retryAt }
    }

    fun nextReviewAt(site: SiteModel): Long = if (!allLessonsComplete(site)) 0L else
        (site.curriculum.maxOfOrNull { it.completedAt } ?: 0L) + 24 * 60 * 60_000L

    fun reviewDue(site: SiteModel, now: Long): Boolean = now >= nextReviewAt(site)

    /** Record structural opportunities only; option values stay in the live task. */
    fun observe(site: SiteModel, sps: SemanticPageState, now: Long) {
        ensure(site)
        LearningOpportunities.observe(site, sps, now)
    }

    /** A lesson names one verified capability. Its query is only a search prerequisite. */
    fun nextGoal(site: SiteModel, profile: SiteProfile, ordinal: Int, page: SemanticPageState? = null,
                 lesson: String = nextLesson(site)): Goal {
        ensure(site)
        val queries = profile.trainingQueries.ifEmpty { listOf("mountain bike", "coffee table", "cordless drill") }
        val query = queries[Math.floorMod(ordinal, queries.size)]
        val live = page?.let { LearningOpportunities.target(it, lesson) }
        val constraints = live?.constraints.orEmpty().ifEmpty {
            // Old callers without a live observation retain numeric practice compatibility.
            if (page == null && lesson == "constrain_numeric") listOf(Constraint("price", ConstraintOp.LTE, "8000",
                sources = setOf(ConstraintSource.FILTERABLE))) else emptyList()
        }
        return Goal(
            id = Hashing.short("learn|${site.host}|$ordinal|${System.nanoTime()}"),
            intent = GoalIntent.LEARN_SITE,
            rawText = "learn: $query",
            query = query,
            constraints = constraints,
            // One remote repair per lesson is enough. A failed repair is persisted by
            // FailedStrategies and the learner rotates/re-observes instead of spending
            // several teacher calls against the same control state.
            budget = Budget(itemsInspected = 1, llmCalls = 1, actions = 16, wallMs = 3 * 60_000L),
            category = profile.categories.firstOrNull()
        )
    }

    fun recordAttempt(site: SiteModel, ledger: TaskLedger, now: Long = System.currentTimeMillis()) {
        ensure(site)
        val verifiedSkills = ledger.successfulSkills
        if (ledger.done && ledger.lesson.isNotBlank() && ledger.lesson !in ledger.attemptedSkills) {
            site.curriculum.firstOrNull { it.id == ledger.lesson && !it.done }?.let { it.unavailable++; it.retryAt = now + retryDelay(it.unavailable) }
        }
        site.curriculum.forEach { item ->
            if (item.done || item.id !in ledger.attemptedSkills) return@forEach
            item.attempts++
            if (item.id in verifiedSkills) { item.completedAt = ledger.updatedAt.takeIf { it > 0 } ?: now; item.retryAt = 0L }
            else { item.blocked++; item.retryAt = now + retryDelay(item.blocked) }
        }
    }

    private fun retryDelay(attempts: Int): Long = listOf(60_000L, 5 * 60_000L, 15 * 60_000L, 60 * 60_000L)[(attempts - 1).coerceIn(0, 3)]

    fun progress(site: SiteModel): String {
        ensure(site)
        val done = site.curriculum.count { it.done }
        return "$done/${site.curriculum.size} curriculum goals verified"
    }
}
