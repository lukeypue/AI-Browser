package com.appgate.brain.engine

import com.appgate.brain.goal.ConstraintEvaluator
import com.appgate.brain.model.Constraint
import com.appgate.brain.model.ConstraintOp
import com.appgate.brain.model.ConstraintSource
import com.appgate.brain.model.Goal
import com.appgate.brain.model.GoalIntent
import com.appgate.brain.model.PageType
import com.appgate.brain.model.Role
import com.appgate.brain.model.SemanticPageState
import com.appgate.brain.model.SiteModel
import com.appgate.brain.model.TaskLedger
import com.appgate.brain.model.TaskPhase
import com.appgate.brain.model.TaskStatus
import com.appgate.brain.model.Verdict
import com.appgate.brain.profile.SiteProfile
import com.appgate.brain.util.Text

sealed class PolicyDecision {
    data class RunSkill(val skillId: String, val params: Map<String, String>, val reason: String) : PolicyDecision()
    data class Navigate(val url: String, val reason: String) : PolicyDecision()
    data class AskPlanner(val reason: String) : PolicyDecision()
    data class Finish(val status: TaskStatus, val reason: String) : PolicyDecision()
    data class NeedHuman(val reason: String) : PolicyDecision()
    data class Grant(val reason: String) : PolicyDecision()
    data class EvaluateDetail(val itemKey: String) : PolicyDecision()
}

/**
 * The deterministic task policy: a small state machine over the ledger phase that turns a
 * goal + page state into "which skill next". It is cheap, auditable and cannot be talked
 * out of anything by page content. The planner is consulted only when it has nothing.
 */
class TaskPolicy(private val profile: SiteProfile, private val site: SiteModel) {

    fun decide(goal: Goal, sps: SemanticPageState, ledger: TaskLedger): PolicyDecision {
        if (sps.isHumanOnly) return PolicyDecision.NeedHuman(if (sps.challenge) "Human verification (CAPTCHA) required" else "Sign-in required")
        if (sps.pageType == PageType.ERROR) return recoverFromError(ledger)
        if (ledger.actions >= goal.budget.actions) return PolicyDecision.Finish(TaskStatus.BUDGET_EXHAUSTED, "action budget reached")
        if (goal.intent == GoalIntent.LEARN_SITE && sps.pageType == PageType.RESULTS && queryApplied(goal, sps) && (ledger.constraintAttempts["__lesson"] ?: 0) < 1) {
            val role = when (ledger.lesson) { "next_page" -> Role.PAGE_NEXT; "load_more" -> Role.LOAD_MORE; "sort_results" -> Role.SORT; "scroll_results" -> null; else -> Role.UNKNOWN }
            if (role != Role.UNKNOWN && (role == null || sps.has(role))) {
                ledger.constraintAttempts["__lesson"] = 1
                val params = if (role == Role.SORT) mapOf("order" to (sps.byRole(role).first().choices.firstOrNull { it.isNotBlank() && it != sps.byRole(role).first().value } ?: "price")) else emptyMap()
                return PolicyDecision.RunSkill(ledger.lesson, params, "practice ${ledger.lesson}")
            }
        }

        return when (goal.intent) {
            GoalIntent.FIND_LISTINGS, GoalIntent.LEARN_SITE -> findListings(goal, sps, ledger)
            GoalIntent.INSPECT_ITEM -> inspectItem(goal, sps, ledger)
            GoalIntent.PREPARE_MESSAGE -> prepareMessage(goal, sps, ledger)
            GoalIntent.CUSTOM -> PolicyDecision.AskPlanner("custom goal")
        }
    }

    private fun recoverFromError(ledger: TaskLedger): PolicyDecision {
        val target = ledger.resultsUrl.ifBlank { ledger.startUrl }
        return if (ledger.consecutiveFailures < 3) PolicyDecision.Navigate(target, "error page; returning to last good url") else PolicyDecision.Finish(TaskStatus.FAILED, "site keeps returning errors")
    }

    // ------------------------------------------------------------------ FIND_LISTINGS
    private fun findListings(goal: Goal, sps: SemanticPageState, ledger: TaskLedger): PolicyDecision {
        // A blocking dialog is handled before anything else, on every phase.
        if (sps.dialogOpen && sps.pageType != PageType.FACET_PANEL && !LearningOpportunities.dialogHoldsFilters(sps) && sps.has(Role.CLOSE) && ledger.phase != TaskPhase.INSPECT) {
            if ((ledger.constraintAttempts["__dismiss"] ?: 0) < 3) {
                ledger.constraintAttempts["__dismiss"] = (ledger.constraintAttempts["__dismiss"] ?: 0) + 1
                return PolicyDecision.RunSkill("dismiss_dialog", emptyMap(), "blocking dialog")
            }
        }
        when (ledger.phase) {
            TaskPhase.START, TaskPhase.SEARCH -> return searchPhase(goal, sps, ledger)
            TaskPhase.CONSTRAIN -> return constrainPhase(goal, sps, ledger)
            TaskPhase.COLLECT -> return collectPhase(goal, sps, ledger)
            TaskPhase.INSPECT -> return inspectPhase(goal, sps, ledger)
            TaskPhase.DONE -> return PolicyDecision.Finish(TaskStatus.DONE, "done")
            else -> return PolicyDecision.AskPlanner("unexpected phase ${ledger.phase}")
        }
    }

    private fun searchPhase(goal: Goal, sps: SemanticPageState, ledger: TaskLedger): PolicyDecision {
        if (queryApplied(goal, sps)) {
            ledger.phase = TaskPhase.CONSTRAIN
            ledger.resultsUrl = sps.url
            return constrainPhase(goal, sps, ledger)
        }
        // Fast path: a known search URL template (profile or learned) — verified like any action.
        val template = profile.searchUrlFor(goal) ?: site.searchUrlTemplate?.let { SiteProfile("x", "x", listOf(sps.host), sps.url, searchUrl = it).searchUrlFor(goal) }
        if (!ledger.usedSearchUrl && template != null && goal.query.isNotBlank() && !(goal.intent == GoalIntent.LEARN_SITE && ledger.lesson == "search")) {
            ledger.usedSearchUrl = true
            return PolicyDecision.Navigate(template, "direct search url")
        }
        if (ledger.searchAttempts >= 3) {
            return if (sps.pageType == PageType.RESULTS) { ledger.phase = TaskPhase.CONSTRAIN; constrainPhase(goal, sps, ledger) }
            else PolicyDecision.AskPlanner("search could not be verified after ${ledger.searchAttempts} attempts")
        }
        if (sps.has(Role.SEARCH_BOX)) {
            ledger.searchAttempts++
            return PolicyDecision.RunSkill("search", mapOf("query" to goal.query), "type query into search")
        }
        if (sps.pageType == PageType.RESULTS && sps.resultKeys.isNotEmpty()) {
            // We are on results without a visible search box (e.g. landed via category); accept and constrain.
            ledger.phase = TaskPhase.CONSTRAIN
            ledger.resultsUrl = sps.url
            return constrainPhase(goal, sps, ledger)
        }
        val category = sps.byRole(Role.CATEGORY_LINK).firstOrNull { link -> categoryMatches(goal, link.name) }
        if (category != null && ledger.searchAttempts < 2) {
            ledger.searchAttempts++
            return PolicyDecision.RunSkill("open_category", mapOf("name" to category.name), "open matching category")
        }
        if (sps.url != ledger.startUrl && ledger.searchAttempts < 2) {
            ledger.searchAttempts++
            return PolicyDecision.Navigate(ledger.startUrl, "return to start page to find search")
        }
        return PolicyDecision.AskPlanner("no search box or category found")
    }

    private fun constrainPhase(goal: Goal, sps: SemanticPageState, ledger: TaskLedger): PolicyDecision {
        if (sps.pageType == PageType.DETAIL) return PolicyDecision.RunSkill("go_back", emptyMap(), "back to results before constraining")
        for (c in filterableConstraints(goal)) {
            val facetKey = facetKeyFor(c) ?: continue
            if (constraintSatisfiedOnPage(c, facetKey, sps) || facetKey in ledger.appliedConstraints) continue
            val attempts = ledger.constraintAttempts[facetKey] ?: 0
            if (attempts >= 2) continue
            val hasFacet = sps.affordances.any { it.visible && (it.role == Role.FACET || it.role == Role.FACET_OPEN) && (it.facetKey == facetKey || it.facetKey == facetKey.removeSuffix("_max").removeSuffix("_min")) }
            val hasOpener = sps.has(Role.FACET_OPEN)
            // Once the filter sheet is observed, absence is evidence: do not reopen the
            // same sheet for an unavailable key. Card/detail checks keep the constraint.
            if (!hasFacet && (sps.pageType == PageType.FACET_PANEL || LearningOpportunities.dialogHoldsFilters(sps))) {
                ledger.constraintAttempts[facetKey] = 2
                continue
            }
            if (!hasFacet && !hasOpener) continue
            ledger.constraintAttempts[facetKey] = attempts + 1
            if (!hasFacet && hasOpener && attempts == 0) return PolicyDecision.RunSkill("open_filters", emptyMap(), "open filters for $facetKey")
            val value = constraintValue(c)
            return if (c.key in setOf("price", "mileage", "year", "distance", "bedrooms", "bathrooms") && (c.op == ConstraintOp.LTE || c.op == ConstraintOp.GTE))
                PolicyDecision.RunSkill("constrain_numeric", mapOf("key" to facetKey, "value" to value), "bound $facetKey to $value")
            else PolicyDecision.RunSkill("select_facet", mapOf("key" to facetKey, "value" to value), "select $facetKey = $value")
        }
        if (sps.pageType == PageType.FACET_PANEL || (sps.dialogOpen && sps.has(Role.FACET_APPLY))) {
            return PolicyDecision.RunSkill("apply_filters", emptyMap(), "apply pending filters")
        }
        ledger.phase = TaskPhase.COLLECT
        if (sps.pageType == PageType.RESULTS) ledger.resultsUrl = sps.url
        return collectPhase(goal, sps, ledger)
    }

    private fun collectPhase(goal: Goal, sps: SemanticPageState, ledger: TaskLedger): PolicyDecision {
        if (sps.pageType == PageType.DETAIL) return PolicyDecision.RunSkill("go_back", emptyMap(), "back to results")
        if (sps.pageType == PageType.FACET_PANEL && sps.has(Role.FACET_APPLY)) return PolicyDecision.RunSkill("apply_filters", emptyMap(), "close filters")
        if (sps.pageType == PageType.RESULTS) ledger.resultsUrl = sps.url
        val candidates = ledger.verdicts.values.count { it.hardViolations(goal) == 0 }
        val enough = candidates >= goal.budget.itemsInspected
        val exhausted = ledger.scrollRoundsWithoutNew >= 2 || ledger.pagesVisited >= 6
        if (!enough && !exhausted && sps.pageType == PageType.RESULTS) {
            if (sps.has(Role.LOAD_MORE)) return PolicyDecision.RunSkill("load_more", emptyMap(), "load more results")
            val scrollable = sps.scrollHeight > sps.scrollY + sps.viewportHeight + 200
            if (scrollable && ledger.scrollRoundsWithoutNew < 2) return PolicyDecision.RunSkill("scroll_results", emptyMap(), "scroll for more results")
            if (sps.has(Role.PAGE_NEXT) && ledger.pagesVisited < 6) { ledger.pagesVisited++; ledger.scrollRoundsWithoutNew = 0; return PolicyDecision.RunSkill("next_page", emptyMap(), "next page") }
            if (!scrollable && ledger.scrollRoundsWithoutNew == 0 && !sps.has(Role.PAGE_NEXT)) { ledger.scrollRoundsWithoutNew = 1; return PolicyDecision.RunSkill("scroll_results", emptyMap(), "scroll once to trigger lazy results") }
        }
        if (ledger.verdicts.isEmpty() && sps.pageType != PageType.RESULTS) {
            return if (ledger.resultsUrl.isNotBlank() && sps.url != ledger.resultsUrl && ledger.consecutiveFailures < 3) PolicyDecision.Navigate(ledger.resultsUrl, "return to results")
            else PolicyDecision.AskPlanner("no results collected")
        }
        if (ledger.verdicts.isEmpty()) return PolicyDecision.Finish(TaskStatus.PARTIAL, "no listings recognized; inspection could not start")
        ledger.phase = TaskPhase.INSPECT
        ledger.inspectQueue = ConstraintEvaluator.rank(goal, ledger.verdicts.values).filter { ConstraintEvaluator.needsDetail(goal, it) }.map { it.itemKey }.toMutableList()
        return inspectPhase(goal, sps, ledger)
    }

    private fun inspectPhase(goal: Goal, sps: SemanticPageState, ledger: TaskLedger): PolicyDecision {
        val current = ledger.currentItem
        if (sps.pageType == PageType.DETAIL && current != null) {
            val v = ledger.verdicts[current]
            if (v != null && !v.inspectedDetail) {
                if (sps.has(Role.EXPAND_TEXT) && (ledger.constraintAttempts["__expand:$current"] ?: 0) == 0) {
                    ledger.constraintAttempts["__expand:$current"] = 1
                    return PolicyDecision.RunSkill("expand_description", emptyMap(), "expand description")
                }
                return PolicyDecision.EvaluateDetail(current)
            }
            ledger.currentItem = null
            return PolicyDecision.RunSkill("go_back", emptyMap(), "back to results after inspecting")
        }
        val limit = goal.budget.itemsInspected.coerceAtMost(25)
        while (ledger.inspectQueue.isNotEmpty() && ledger.itemsInspected < limit) {
            val key = ledger.inspectQueue.first()
            val v = ledger.verdicts[key]
            if (v == null || v.inspectedDetail || !ConstraintEvaluator.needsDetail(goal, v)) { ledger.inspectQueue.removeAt(0); continue }
            val attempts = ledger.constraintAttempts["__open:$key"] ?: 0
            if (attempts >= 2) { ledger.inspectQueue.removeAt(0); continue }
            ledger.constraintAttempts["__open:$key"] = attempts + 1
            ledger.currentItem = key
            val visible = sps.results?.itemKeys?.contains(key) == true
            return if (visible && sps.pageType == PageType.RESULTS) PolicyDecision.RunSkill("open_item", mapOf("item" to key), "open listing for detail check")
            else if (v.url != null) PolicyDecision.Navigate(v.url, "open listing url for detail check")
            else { ledger.inspectQueue.removeAt(0); continue }
        }
        if (ledger.verdicts.isEmpty()) return PolicyDecision.Finish(TaskStatus.PARTIAL, "no listings recognized; inspection could not start")
        val unresolved = ledger.verdicts.values.any { !it.inspectedDetail && ConstraintEvaluator.needsDetail(goal, it) }
        if (unresolved && ledger.itemsInspected < limit) return PolicyDecision.Finish(TaskStatus.PARTIAL, "some listing details could not be inspected")
        ledger.phase = TaskPhase.DONE
        return PolicyDecision.Finish(TaskStatus.DONE, "inspection complete")
    }

    // ------------------------------------------------------------------ INSPECT_ITEM / PREPARE_MESSAGE
    private fun inspectItem(goal: Goal, sps: SemanticPageState, ledger: TaskLedger): PolicyDecision {
        val url = goal.targetUrl ?: return PolicyDecision.Finish(TaskStatus.FAILED, "no target url")
        if (sps.pageType != PageType.DETAIL && ledger.consecutiveFailures < 2 && sps.url != url) return PolicyDecision.Navigate(url, "open target listing")
        if (sps.has(Role.EXPAND_TEXT) && (ledger.constraintAttempts["__expand"] ?: 0) == 0) { ledger.constraintAttempts["__expand"] = 1; return PolicyDecision.RunSkill("expand_description", emptyMap(), "expand description") }
        return PolicyDecision.Finish(TaskStatus.DONE, "item inspected")
    }

    private fun prepareMessage(goal: Goal, sps: SemanticPageState, ledger: TaskLedger): PolicyDecision {
        val url = goal.targetUrl ?: return PolicyDecision.Finish(TaskStatus.FAILED, "no target url")
        val draft = goal.messageDraft?.takeIf { it.isNotBlank() } ?: return PolicyDecision.Finish(TaskStatus.FAILED, "no message text")
        when (ledger.phase) {
            TaskPhase.START, TaskPhase.SEARCH, TaskPhase.CONSTRAIN, TaskPhase.COLLECT, TaskPhase.INSPECT -> {
                if (sps.pageType != PageType.DETAIL && sps.pageType != PageType.MESSAGES && !sps.has(Role.MESSAGE_SELLER) && !sps.has(Role.COMPOSER_INPUT)) {
                    if (ledger.consecutiveFailures >= 2) return PolicyDecision.Finish(TaskStatus.FAILED, "could not open listing")
                    return PolicyDecision.Navigate(url, "open listing to message seller")
                }
                ledger.phase = TaskPhase.PREPARE_MESSAGE
                return prepareMessage(goal, sps, ledger)
            }
            TaskPhase.PREPARE_MESSAGE -> {
                if (sps.has(Role.COMPOSER_INPUT) && sps.has(Role.SEND)) {
                    val composer = sps.byRole(Role.COMPOSER_INPUT).first()
                    val typed = composer.value?.let { v -> v.isNotBlank() && draft.take(v.length).equals(v, true) } == true
                    if (typed) { ledger.phase = TaskPhase.PREVIEW; ledger.previewText = draft; return PolicyDecision.Grant("message ready for your approval") }
                }
                if ((ledger.constraintAttempts["__prepare"] ?: 0) >= 3) return PolicyDecision.AskPlanner("could not prepare message")
                ledger.constraintAttempts["__prepare"] = (ledger.constraintAttempts["__prepare"] ?: 0) + 1
                return PolicyDecision.RunSkill("prepare_message", mapOf("text" to draft), "open composer and fill message (not sent)")
            }
            TaskPhase.PREVIEW -> {
                val grant = ledger.grants.firstOrNull { !it.used && it.previewHash == ledger.previewHash }
                return if (grant != null) PolicyDecision.RunSkill("commit_send", emptyMap(), "send with user grant") else PolicyDecision.Grant("waiting for approval")
            }
            TaskPhase.DONE -> return PolicyDecision.Finish(TaskStatus.DONE, "message handled")
        }
    }

    // ------------------------------------------------------------------ helpers
    fun queryApplied(goal: Goal, sps: SemanticPageState): Boolean {
        // A filled search box is only draft evidence until the page actually shows results.
        if (sps.pageType != PageType.RESULTS) return false
        if (goal.query.isBlank()) return sps.pageType == PageType.RESULTS
        val q = Text.tokens(goal.query).filter { it.length > 1 }
        if (q.isEmpty()) return sps.pageType == PageType.RESULTS
        val active = sps.constraintsActive["query"]?.lowercase()
        if (active != null && q.count { it in active } >= (q.size * 0.6).coerceAtLeast(1.0)) return true
        val urlLower = java.net.URLDecoder.decode(sps.url, "UTF-8").lowercase()
        if (q.count { it in urlLower } >= (q.size * 0.6).coerceAtLeast(1.0) && sps.pageType == PageType.RESULTS) return true
        val items = sps.results?.items ?: return false
        if (items.size >= 3) {
            val matching = items.count { it -> q.count { t -> (it.title + " " + it.snippet).lowercase().contains(t) } >= (q.size * 0.5).coerceAtLeast(1.0) }
            return matching >= items.size * 0.5
        }
        return false
    }

    private fun categoryMatches(goal: Goal, name: String): Boolean {
        val n = name.lowercase()
        return when (goal.category) {
            "vehicles" -> listOf("car", "cars", "vehicle", "vehicles", "auto", "autos", "truck").any { n.contains(it) }
            "housing" -> listOf("housing", "rental", "rentals", "real estate", "homes", "apartments").any { n.contains(it) }
            "jobs" -> listOf("job", "jobs", "employment").any { n.contains(it) }
            else -> listOf("for sale", "general", "classifieds", "marketplace", "shop").any { n.contains(it) }
        }
    }

    private fun filterableConstraints(goal: Goal): List<Constraint> = goal.constraints
        .filter { ConstraintSource.FILTERABLE in it.sources }
        .sortedBy { c -> when (c.key) { "make" -> 0; "model" -> 1; "price" -> 2; "mileage" -> 3; "year" -> 4; else -> 9 } }

    fun facetKeyFor(c: Constraint): String? = when (c.key) {
        "price", "mileage", "year", "distance", "bedrooms", "bathrooms" -> when (c.op) {
            ConstraintOp.LTE -> "${c.key}_max"
            ConstraintOp.GTE -> "${c.key}_min"
            ConstraintOp.EQ -> if (c.key == "year") "year_min" else "${c.key}_max"
            else -> null
        }
        "make", "model", "trim", "zip", "location", "condition", "transmission", "drivetrain", "fuel", "color", "body_style" -> c.key
        else -> null
    }

    private fun constraintValue(c: Constraint): String = c.value

    private fun constraintSatisfiedOnPage(c: Constraint, facetKey: String, sps: SemanticPageState): Boolean {
        val active = sps.constraintsActive[facetKey] ?: sps.constraintsActive[c.key] ?: return false
        val target = c.numericValue
        val actual = Text.parseAmount(active)
        if (target != null && actual != null) {
            if (actual.toDouble() == target || (c.op == ConstraintOp.LTE && actual <= target) || (c.op == ConstraintOp.GTE && actual >= target)) return true
            // A choice facet can only offer its own steps; if the closest available option is applied, that is the best the site can do
            // (card checks enforce the exact bound afterwards).
            val facet = sps.facet(facetKey)
            if (facet != null && facet.choices.isNotEmpty()) {
                val closest = StepGrounder.closestNumericOption(facet.choices, c.value, if (facetKey.endsWith("_min")) "min" else "max")
                if (closest != null && Text.parseAmount(closest.replace(Regex("(?i)[^0-9k.,]"), "")) == actual) return true
            }
            return false
        }
        return active.lowercase().contains(c.value.lowercase()) || c.value.lowercase().contains(active.lowercase())
    }

    companion object {
        fun cardVerdictsMatter(goal: Goal, verdict: Map<String, Verdict>): Boolean = goal.hard.none { verdict[it.key] == Verdict.VIOLATED }
    }
}
