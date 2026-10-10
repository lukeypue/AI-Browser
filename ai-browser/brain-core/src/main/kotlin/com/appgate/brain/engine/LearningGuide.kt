package com.appgate.brain.engine

import com.appgate.brain.model.*

/** Task-local explanation for the person watching. Never written to diagnostics or memory. */
data class LearningGuide(
    val ledgerId: String,
    val host: String,
    val goal: String,
    val attempt: String,
    val pass: String,
    val outcome: String
) {
    fun text(): String = "$host\nTrying to learn: $goal\nNext / last action: $attempt\nStep passes when: $pass\nLast result: $outcome\nA passed step is progress; the complete procedure must also verify before the lesson is learned."

    companion object {
        private fun label(value: String) = value.lowercase().replace('_', ' ')
        fun snapshot(ledger: TaskLedger, step: Step? = null, status: VerifyStatus? = null, reason: String = ""): LearningGuide {
            val goal = when (ledger.lesson) {
                "open_filters" -> "Reveal filter controls"
                "open_facet" -> "Reveal the requested filter control"
                "apply_filters" -> "Apply the selected filters"
                "search" -> "Search and get changed results"
                "constrain_numeric" -> "Apply a numeric filter (price, mileage, year, or another available number)"
                "select_facet" -> "Apply an available choice filter"
                "scroll_results" -> "Reveal more results by scrolling"
                "next_page" -> "Move to the next page of results"
                "load_more" -> "Load more results"
                "sort_results" -> "Change the order of results"
                "open_item" -> "Open a result's detail page"
                "expand_description" -> "Expand a description"
                "go_back" -> "Return to results"
                "dismiss_dialog" -> "Close a blocking dialog"
                else -> "Find an available lesson"
            } + ledger.learningConstraints.takeIf { it.isNotEmpty() }?.joinToString(prefix = ": ") { it.describe() }.orEmpty()
            val attempt = step?.let { s ->
                listOfNotNull(label(s.kind.name), s.facetKey?.let(::label) ?: s.role?.name?.let(::label), s.arg?.takeIf { !it.startsWith('$') }?.let { "to $it" }).joinToString(" ")
            } ?: "Inspect the page and choose a procedure"
            val checks = step?.expect ?: ledger.programPost
            val pass = if (checks.isEmpty()) "An explicit check has not supplied a pass condition yet." else checks.joinToString(" AND ") { condition(it) }
            val outcome = when (status) {
                VerifyStatus.VERIFIED -> "Passed this step: its expected change was observed."
                VerifyStatus.AMBIGUOUS -> "Not verified yet: the page or evidence was inconclusive. No success credit."
                VerifyStatus.HUMAN_NEEDED -> "Needs you: sign-in or a verification challenge blocks the attempt."
                VerifyStatus.FAILED -> when {
                    reason.contains("no_target") || reason.contains("missing") -> "Could not find the required control on the observed page."
                    reason.contains("repeat_state") -> "Stopped repeating an action that was not making progress."
                    reason.contains("outside_task_host") -> "The proposed action would leave the allowed site."
                    reason.contains("timeout", true) -> "The browser did not respond in time."
                    reason.contains("blocked", true) -> "The action was blocked; the expected change was not checked."
                    else -> "Did not verify the expected change. Clicking alone does not count as a pass."
                }
                null -> "Not checked yet."
            }
            return LearningGuide(ledger.id, ledger.host, goal, attempt, pass, outcome)
        }

        fun condition(p: Postcondition): String = when (p) {
            Postcondition.ResultsChanged -> "the observed result list changes"
            Postcondition.NewResults -> "new result items appear"
            Postcondition.EndOfResults -> "the observed list reaches its end with no next/load-more control"
            Postcondition.TextExpanded -> "more description text becomes visible"
            Postcondition.DialogClosed -> "the blocking dialog closes"
            Postcondition.DialogOpened -> "a dialog opens"
            Postcondition.UrlChanged -> "the page address changes"
            Postcondition.ComposerReady -> "the message editor and send control appear"
            Postcondition.ScrolledDown -> "the page moves down"
            is Postcondition.PageTypeIs -> "the page is recognized as ${label(p.pageType.name)}"
            is Postcondition.ConstraintApplied -> "${label(p.key)} is observed as applied${p.value?.let { " with value $it" }.orEmpty()}"
            is Postcondition.DetailMatches -> "the matching item's detail page is observed"
            is Postcondition.UrlQueryHas -> "the page address includes ${label(p.key)}"
            is Postcondition.RoleAppeared -> "a ${label(p.role.name)} control${p.facetKey?.let { " for ${label(it)}" }.orEmpty()} appears"
            is Postcondition.ValueIs -> "${label(p.facetKey)} shows ${p.value}"
            is Postcondition.AnyOf -> "(" + p.alternatives.joinToString(" OR ") { condition(it) } + ")"
        }
    }
}
