package com.appgate.brain.verify

import com.appgate.brain.model.Action
import com.appgate.brain.model.ActionKind
import com.appgate.brain.model.Affordance
import com.appgate.brain.model.AffordanceRef
import com.appgate.brain.model.Collection
import com.appgate.brain.model.PageType
import com.appgate.brain.model.Postcondition
import com.appgate.brain.model.Role
import com.appgate.brain.model.SemanticPageState
import com.appgate.brain.model.Settle
import com.appgate.brain.model.VerifyStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifierTest {
    private fun sps(page: PageType = PageType.RESULTS, keys: List<String> = emptyList(), dialog: Boolean = false, url: String = "https://x.test/search?q=a",
                    constraints: Map<String, String> = emptyMap(), roles: List<Role> = emptyList(), settle: Settle = Settle.IDLE, textLength: Int = 1000, scrollY: Int = 0) =
        SemanticPageState(
            host = "x.test", url = url, urlPattern = url, title = "t", pageType = page, pageTypeConfidence = 0.9, regions = emptyList(),
            affordances = roles.mapIndexed { i, r -> Affordance(id = "a$i", role = r) }, collections = if (keys.isEmpty()) emptyList() else listOf(Collection("results", keys, keys.size)),
            constraintsActive = constraints, settle = settle, challenge = false, authWall = false, dialogOpen = dialog, hash = "h" + keys.joinToString() + page + constraints + dialog,
            textLength = textLength, viewportHeight = 800, scrollHeight = 2000, scrollY = scrollY
        )

    @Test
    fun clickedIsNeverAnOutcome() {
        val a = Action(ActionKind.CLICK, AffordanceRef(Role.PAGE_NEXT), expect = listOf(Postcondition.NewResults))
        val before = sps(keys = listOf("k1", "k2"))
        val sameKeys = sps(keys = listOf("k2", "k1"))
        val r = Verifier.verify(a, before, sameKeys)
        assertEquals(VerifyStatus.FAILED, r.status)
        val fresh = sps(keys = listOf("k3", "k4"))
        assertEquals(VerifyStatus.VERIFIED, Verifier.verify(a, before, fresh).status)
    }

    @Test
    fun reRenderWithSameItemsIsNotProgress() {
        val a = Action(ActionKind.CLICK, AffordanceRef(Role.SORT), expect = listOf(Postcondition.ResultsChanged))
        val before = sps(keys = listOf("k1", "k2", "k3", "k4", "k5", "k6", "k7", "k8", "k9", "k10"))
        val after = sps(keys = listOf("k1", "k2", "k3", "k4", "k5", "k6", "k7", "k8", "k9", "k10"))
        assertEquals(VerifyStatus.FAILED, Verifier.verify(a, before, after).status)
        val reordered = sps(keys = listOf("k10", "k9", "k8", "k7", "k6", "k5", "k4", "k3", "k2", "k1"))
        // Same set, different order: results did not change semantically; a sort is verified through the URL instead.
        assertEquals(VerifyStatus.FAILED, Verifier.verify(a, before, reordered).status)
        val sorted = Action(ActionKind.CLICK, AffordanceRef(Role.SORT), expect = listOf(Postcondition.anyOf(Postcondition.ResultsChanged, Postcondition.UrlQueryHas("sort"))))
        assertEquals(VerifyStatus.VERIFIED, Verifier.verify(sorted, before, reordered.copy(url = "https://x.test/search?q=a&sort=price")).status)
    }

    @Test
    fun ambiguousWhileLoading() {
        val a = Action(ActionKind.CLICK, AffordanceRef(Role.PAGE_NEXT), expect = listOf(Postcondition.NewResults))
        val before = sps(keys = listOf("k1"))
        val loading = sps(keys = listOf("k1"), settle = Settle.BUSY)
        assertEquals(VerifyStatus.AMBIGUOUS, Verifier.verify(a, before, loading).status)
    }

    @Test
    fun humanNeededWinsOverEverything() {
        val a = Action(ActionKind.CLICK, AffordanceRef(Role.RESULT_ITEM), expect = listOf(Postcondition.DetailMatches(null)))
        val before = sps(keys = listOf("k1"))
        val wall = before.copy(pageType = PageType.AUTH_WALL, authWall = true)
        assertEquals(VerifyStatus.HUMAN_NEEDED, Verifier.verify(a, before, wall).status)
    }

    @Test
    fun endOfResultsIsAVerifiedState() {
        val a = Action(ActionKind.SCROLL, expect = listOf(Postcondition.anyOf(Postcondition.NewResults, Postcondition.EndOfResults)))
        val before = sps(keys = listOf("k1", "k2"), scrollY = 0)
        val bottom = sps(keys = listOf("k1", "k2"), scrollY = 1300)
        val r = Verifier.verify(a, before, bottom, visited = setOf("k1", "k2"))
        assertEquals(VerifyStatus.VERIFIED, r.status)
        assertTrue(r.evidence.any { it.contains("end of results") })
        val withNext = bottom.copy(affordances = listOf(Affordance(id = "n", role = Role.PAGE_NEXT)))
        assertEquals(VerifyStatus.FAILED, Verifier.verify(a, before, withNext, visited = setOf("k1", "k2")).status)
    }

    @Test
    fun constraintAppliedNeedsTheKeyToChange() {
        val a = Action(ActionKind.SELECT, AffordanceRef(Role.FACET, "price_max"), text = "8000", expect = listOf(Postcondition.ConstraintApplied("price_max", "8000")))
        val before = sps(constraints = mapOf("query" to "ford"))
        val after = sps(constraints = mapOf("query" to "ford", "price_max" to "$8,000"))
        assertEquals(VerifyStatus.VERIFIED, Verifier.verify(a, before, after).status)
        val wrong = sps(constraints = mapOf("query" to "ford", "price_max" to "$5,000"))
        assertEquals(VerifyStatus.FAILED, Verifier.verify(a, before, wrong).status)
    }

    @Test
    fun dialogAndTextPredicates() {
        val close = Action(ActionKind.DISMISS, AffordanceRef(Role.CLOSE), expect = listOf(Postcondition.DialogClosed))
        assertEquals(VerifyStatus.VERIFIED, Verifier.verify(close, sps(dialog = true), sps(dialog = false)).status)
        val expand = Action(ActionKind.CLICK, AffordanceRef(Role.EXPAND_TEXT), expect = listOf(Postcondition.TextExpanded))
        assertEquals(VerifyStatus.VERIFIED, Verifier.verify(expand, sps(page = PageType.DETAIL, textLength = 500), sps(page = PageType.DETAIL, textLength = 900)).status)
        assertEquals(VerifyStatus.FAILED, Verifier.verify(expand, sps(page = PageType.DETAIL, textLength = 500), sps(page = PageType.DETAIL, textLength = 510)).status)
    }
}
