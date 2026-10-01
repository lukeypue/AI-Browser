package com.appgate.brain.verify

import com.appgate.brain.model.Action
import com.appgate.brain.model.ActionKind
import com.appgate.brain.model.PageType
import com.appgate.brain.model.Postcondition
import com.appgate.brain.model.Role
import com.appgate.brain.model.SemanticPageState
import com.appgate.brain.model.Settle
import com.appgate.brain.model.VerifierResult
import com.appgate.brain.model.VerifyStatus
import com.appgate.brain.perception.UrlPatterns
import com.appgate.brain.util.Text

/**
 * The verifier is the only component allowed to write success or failure into memory. It
 * evaluates typed postconditions on (s, a, s') deterministically and never trusts "clicked".
 */
object Verifier {

    fun verify(action: Action, before: SemanticPageState, after: SemanticPageState, visited: Set<String> = emptySet()): VerifierResult {
        if (after.isHumanOnly) {
            return VerifierResult(VerifyStatus.HUMAN_NEEDED, listOf(if (after.challenge) "challenge page reached" else "auth wall reached"))
        }
        if (action.expect.isEmpty()) {
            // An action without expectations can only be "ambiguous": nothing to verify against.
            val changed = before.hash != after.hash
            return VerifierResult(VerifyStatus.AMBIGUOUS, listOf(if (changed) "state changed but no postcondition declared" else "no change"))
        }
        val evidence = mutableListOf<String>()
        val satisfied = mutableListOf<Postcondition>()
        val unsatisfied = mutableListOf<Postcondition>()
        for (p in action.expect) {
            if (holds(p, action, before, after, visited, evidence)) satisfied += p else unsatisfied += p
        }
        if (unsatisfied.isEmpty()) return VerifierResult(VerifyStatus.VERIFIED, evidence, satisfied, unsatisfied)
        val stillLoading = after.settle != Settle.IDLE
        if (satisfied.isEmpty() && stillLoading) {
            evidence += "page not settled; verdict ambiguous"
            return VerifierResult(VerifyStatus.AMBIGUOUS, evidence, satisfied, unsatisfied)
        }
        if (before.hash != after.hash && satisfied.isEmpty()) evidence += "semantic state changed but no expected postcondition verified"
        if (before.hash == after.hash && satisfied.isEmpty()) evidence += "no semantic change"
        unsatisfied.forEach { evidence += "unmet: ${it.name}" }
        return VerifierResult(VerifyStatus.FAILED, evidence, satisfied, unsatisfied)
    }

    fun holds(p: Postcondition, action: Action, before: SemanticPageState, after: SemanticPageState, visited: Set<String>, evidence: MutableList<String>): Boolean = when (p) {
        is Postcondition.AnyOf -> p.alternatives.any { holds(it, action, before, after, visited, evidence) }
        is Postcondition.ResultsChanged -> {
            val a = before.resultKeys; val b = after.resultKeys
            val ok = b.isNotEmpty() && (a.isEmpty() || jaccard(a, b) < 0.9)
            if (ok) evidence += "results changed (${b.size} items, ${b.count { it !in a }} new keys)"
            ok
        }
        is Postcondition.NewResults -> {
            val fresh = after.resultKeys.filter { it !in before.resultKeys && it !in visited }
            val ok = fresh.isNotEmpty()
            if (ok) evidence += "${fresh.size} new result keys"
            ok
        }
        is Postcondition.EndOfResults -> {
            val noNew = after.resultKeys.isNotEmpty() && after.resultKeys.all { it in before.resultKeys || it in visited }
            val noMore = !after.has(Role.LOAD_MORE) && !after.has(Role.PAGE_NEXT)
            val atBottom = after.scrollHeight <= 0 || after.scrollY + after.viewportHeight >= after.scrollHeight - 80
            val ok = noNew && noMore && atBottom
            if (ok) evidence += "end of results (no new keys, no next/load-more, at bottom)"
            ok
        }
        is Postcondition.TextExpanded -> {
            val ok = after.textLength > before.textLength + 40 || after.detailText.length > before.detailText.length + 40
            if (ok) evidence += "text expanded by ${after.textLength - before.textLength} chars"
            ok
        }
        is Postcondition.DialogClosed -> {
            val ok = before.dialogOpen && !after.dialogOpen
            if (ok) evidence += "dialog closed"
            ok
        }
        is Postcondition.DialogOpened -> {
            val ok = !before.dialogOpen && after.dialogOpen
            if (ok) evidence += "dialog opened"
            ok
        }
        is Postcondition.UrlChanged -> {
            val ok = before.url != after.url
            if (ok) evidence += "url changed"
            ok
        }
        is Postcondition.ComposerReady -> {
            val ok = after.has(Role.COMPOSER_INPUT) && after.has(Role.SEND)
            if (ok) evidence += "composer and send present"
            ok
        }
        is Postcondition.ScrolledDown -> {
            val ok = after.scrollY > before.scrollY + 100 || after.resultKeys.any { it !in before.resultKeys }
            if (ok) evidence += "scrolled (${before.scrollY} -> ${after.scrollY})"
            ok
        }
        is Postcondition.PageTypeIs -> {
            val ok = after.pageType == p.pageType && (before.pageType != p.pageType || before.urlPattern != after.urlPattern || before.hash != after.hash)
            if (ok) evidence += "page type is ${p.pageType}"
            ok
        }
        is Postcondition.ConstraintApplied -> {
            val afterVal = after.constraintsActive[p.key]
            val beforeVal = before.constraintsActive[p.key]
            val present = afterVal != null && afterVal != beforeVal
            val valueOk = p.value == null || afterVal == null || valuesMatch(p.value, afterVal)
            val alsoResults = after.resultKeys.isNotEmpty() && jaccard(before.resultKeys, after.resultKeys) < 0.9
            val ok = (present && valueOk) || (afterVal != null && valueOk && alsoResults && beforeVal == null)
            if (ok) evidence += "constraint ${p.key}=${afterVal} applied"
            ok
        }
        is Postcondition.DetailMatches -> {
            val isDetail = after.pageType == PageType.DETAIL && (before.pageType != PageType.DETAIL || before.url != after.url)
            val item = p.itemKey?.let { k -> before.results?.items?.firstOrNull { it.key == k } }
            val titleOk = item == null || after.title.isBlank() || overlap(item.title, after.title + " " + after.detailText.take(600)) >= 0.4
            val ok = isDetail && titleOk
            if (ok) evidence += "detail page reached" + (if (item != null) " for expected item" else "")
            ok
        }
        is Postcondition.UrlQueryHas -> {
            val ok = UrlPatterns.queryValue(after.url, p.key) != null || after.urlPattern.contains("/${p.key}/")
            if (ok) evidence += "url has ${p.key}"
            ok
        }
        is Postcondition.RoleAppeared -> {
            val ok = after.byRole(p.role).any { p.facetKey == null || it.facetKey == p.facetKey } &&
                before.byRole(p.role).none { p.facetKey == null || it.facetKey == p.facetKey }
            if (ok) evidence += "${p.role} appeared"
            ok
        }
        is Postcondition.ValueIs -> {
            val v = after.facet(p.facetKey)?.value ?: after.constraintsActive[p.facetKey]
            val ok = v != null && valuesMatch(p.value, v)
            if (ok) evidence += "${p.facetKey} = $v"
            ok
        }
    }

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        val inter = a.count { it in b }
        val union = a.size + b.size - inter
        return if (union == 0) 1.0 else inter.toDouble() / union
    }

    private fun overlap(a: String, b: String): Double {
        val ta = Text.tokens(a).filter { it.length > 2 }.toSet()
        if (ta.isEmpty()) return 1.0
        val tb = Text.tokens(b).toSet()
        return ta.count { it in tb }.toDouble() / ta.size
    }

    fun valuesMatch(expected: String, actual: String): Boolean {
        val e = expected.trim().lowercase(); val a = actual.trim().lowercase()
        if (e == a) return true
        val en = Text.parseAmount(e); val an = Text.parseAmount(a)
        if (en != null && an != null) return en == an
        return a.contains(e) || e.contains(a) || Text.containsAll(a, e)
    }

    /** Default expectations for an action when its author gave none (used by exploration). */
    fun defaultExpectations(action: Action, before: SemanticPageState): List<Postcondition> {
        val role = action.target?.role
        return when (action.kind) {
            ActionKind.BACK -> listOf(Postcondition.UrlChanged)
            ActionKind.NAVIGATE -> listOf(Postcondition.UrlChanged)
            ActionKind.SCROLL -> listOf(Postcondition.anyOf(Postcondition.NewResults, Postcondition.ScrolledDown, Postcondition.EndOfResults))
            ActionKind.DISMISS -> listOf(Postcondition.DialogClosed)
            ActionKind.WAIT -> emptyList()
            else -> when (role) {
                Role.SEARCH_BOX -> if (action.submit) listOf(Postcondition.anyOf(Postcondition.PageTypeIs(PageType.RESULTS), Postcondition.ResultsChanged, Postcondition.UrlQueryHas("q"))) else listOf(Postcondition.ValueIs("query", action.text ?: ""))
                Role.SUBMIT -> listOf(Postcondition.anyOf(Postcondition.PageTypeIs(PageType.RESULTS), Postcondition.ResultsChanged))
                Role.FACET -> {
                    val key = action.target?.facetKey
                    if (key != null) listOf(Postcondition.anyOf(Postcondition.ConstraintApplied(key, action.text), Postcondition.ResultsChanged, Postcondition.ValueIs(key, action.text ?: "")))
                    else listOf(Postcondition.anyOf(Postcondition.ResultsChanged, Postcondition.DialogOpened))
                }
                Role.FACET_OPEN -> listOf(Postcondition.anyOf(Postcondition.DialogOpened, Postcondition.PageTypeIs(PageType.FACET_PANEL), Postcondition.RoleAppeared(Role.FACET), Postcondition.RoleAppeared(Role.FACET_APPLY)))
                Role.FACET_APPLY -> listOf(Postcondition.anyOf(Postcondition.ResultsChanged, Postcondition.DialogClosed, Postcondition.PageTypeIs(PageType.RESULTS)))
                Role.FACET_CLEAR -> listOf(Postcondition.anyOf(Postcondition.ResultsChanged, Postcondition.UrlChanged))
                Role.SORT -> listOf(Postcondition.anyOf(Postcondition.ResultsChanged, Postcondition.UrlQueryHas("sort"), Postcondition.DialogOpened))
                Role.PAGE_NEXT, Role.PAGE_PREV -> listOf(Postcondition.anyOf(Postcondition.NewResults, Postcondition.ResultsChanged))
                Role.LOAD_MORE -> listOf(Postcondition.anyOf(Postcondition.NewResults, Postcondition.EndOfResults))
                Role.RESULT_ITEM -> listOf(Postcondition.DetailMatches(action.target?.itemKey))
                Role.EXPAND_TEXT -> listOf(Postcondition.TextExpanded)
                Role.CATEGORY_LINK, Role.NAV_LINK, Role.GENERIC_LINK -> listOf(Postcondition.UrlChanged)
                Role.TAB -> listOf(Postcondition.anyOf(Postcondition.ResultsChanged, Postcondition.UrlChanged, Postcondition.TextExpanded))
                Role.CLOSE -> listOf(Postcondition.DialogClosed)
                Role.MESSAGE_SELLER -> listOf(Postcondition.anyOf(Postcondition.ComposerReady, Postcondition.DialogOpened, Postcondition.PageTypeIs(PageType.MESSAGES)))
                Role.COMPOSER_INPUT -> listOf(Postcondition.ComposerReady)
                Role.GENERIC_BUTTON -> listOf(Postcondition.anyOf(Postcondition.DialogOpened, Postcondition.DialogClosed, Postcondition.ResultsChanged, Postcondition.TextExpanded, Postcondition.UrlChanged))
                else -> listOf(Postcondition.UrlChanged)
            }
        }
    }
}
