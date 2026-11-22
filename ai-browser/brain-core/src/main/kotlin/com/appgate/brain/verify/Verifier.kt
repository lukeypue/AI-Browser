package com.appgate.brain.verify

import com.appgate.brain.model.Action
import com.appgate.brain.model.ActionKind
import com.appgate.brain.model.PageType
import com.appgate.brain.model.Postcondition
import com.appgate.brain.model.Role
import com.appgate.brain.model.SemanticPageState
import com.appgate.brain.model.ScrollContext
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
        fun unconfirmed(p:Postcondition):Boolean=when(p) {
            Postcondition.ScrolledDown,Postcondition.EndOfResults->true
            Postcondition.NewResults,Postcondition.ResultsChanged->action.kind==ActionKind.SCROLL
            is Postcondition.AnyOf->p.alternatives.any(::unconfirmed)
            else->false
        }
        if(ScrollContext.delta(before,after)==null && unsatisfied.all(::unconfirmed)) {
            evidence += "scroll surface unavailable or changed; verdict ambiguous"
            return VerifierResult(VerifyStatus.AMBIGUOUS,evidence,satisfied,unsatisfied)
        }
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
        is Postcondition.ResultOrderChanged -> {
            val a = before.results?.itemKeys.orEmpty(); val b = after.results?.itemKeys.orEmpty()
            val ok = before.host.lowercase().removePrefix("www.") == after.host.lowercase().removePrefix("www.") &&
                before.pageType == PageType.RESULTS && after.pageType == PageType.RESULTS &&
                before.settle == Settle.IDLE && after.settle == Settle.IDLE &&
                a.size >= 2 && a.size == b.size && a.toSet().size == a.size && b.toSet().size == b.size &&
                a.toSet() == b.toSet() && a != b
            if (ok) evidence += "observed result order changed (${b.size} items)"
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
            val scroll = after.scrollContext
            val atBottom = (ScrollContext.delta(before,after)!=null) && if(scroll==null)
                after.scrollHeight <= 0 || after.scrollY + after.viewportHeight >= after.scrollHeight - 80
                else scroll.y + scroll.height >= scroll.extent - 80
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
            val beforeY=before.scrollContext?.y?:before.scrollY
            val afterY=after.scrollContext?.y?:after.scrollY
            val delta=ScrollContext.delta(before,after)
            val ok = (delta?.let{it>100L}==true) || after.resultKeys.any { it !in before.resultKeys }
            if (ok) evidence += if(delta!=null) "scrolled ($beforeY -> $afterY)" else "new result keys after scrolling"
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
            val ok = after.byRole(p.role).any { p.matchesFacet(it.facetKey) } &&
                (if (p.facetKey == null) before.byRole(p.role).isEmpty() else
                    after.byRole(p.role).count { p.matchesFacet(it.facetKey) } > before.byRole(p.role).count { p.matchesFacet(it.facetKey) })
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

    /** Extra identity proof for an item whose outcome required deferred reads.
     * A page type alone, or an unrelated blank-title detail, is not its target.
     * Compare only local redacted observations; no identity is added to diagnostics.
     */
    fun deferredItemMatches(action: Action, before: SemanticPageState, after: SemanticPageState): Boolean {
        if (after.pageType != PageType.DETAIL || before.host.removePrefix("www.") != after.host.removePrefix("www.")) return false
        val key = action.target?.itemKey ?: return false
        val item = before.results?.items?.singleOrNull { it.key == key } ?: return false
        fun normalized(text: String) = Text.clean(text).lowercase(java.util.Locale.ROOT)
            .replace(Regex("(?<=\\S)(?=[$€£])"), " ").replace(Regex("\\s+"), " ").trim()
        val expected = normalized(item.title).takeIf { it.length >= 4 } ?: return false
        fun namesTarget(text: String) = (" " + normalized(text) + " ").contains(" $expected ")
        if (!namesTarget(after.title) && !namesTarget(after.detailText.take(600))) return false
        fun path(url: String?) = url?.let { runCatching { java.net.URI(it).path.trimEnd('/') }.getOrNull() }
        val observedPath = path(after.url)
        val requestedPath = path(item.hrefPath)
        val items = before.results?.items.orEmpty()
        if (items.any { other -> other.key != key && path(other.hrefPath) != requestedPath &&
                observedPath != null && path(other.hrefPath) == observedPath }) return false
        val duplicatedTitle = items.count { normalized(it.title) == expected } > 1
        val uniqueSelectedRoute = observedPath != null && observedPath != path(before.url) &&
            observedPath == requestedPath && items.count { path(it.hrefPath) == requestedPath } == 1
        return !duplicatedTitle || uniqueSelectedRoute
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
        // A partial label is not evidence that the requested value was applied.
        return Text.clean(a) == Text.clean(e)
    }

    /** Default expectations for an action when its author gave none (used by exploration). */
    internal fun isScrollPostcondition(p:Postcondition):Boolean=when(p) {
        Postcondition.ScrolledDown,Postcondition.EndOfResults,Postcondition.NewResults,Postcondition.ResultsChanged->true
        is Postcondition.AnyOf->p.alternatives.isNotEmpty() && p.alternatives.all(::isScrollPostcondition)
        else->false
    }

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
                Role.SORT -> listOf(Postcondition.anyOf(Postcondition.ResultsChanged, Postcondition.ResultOrderChanged))
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
