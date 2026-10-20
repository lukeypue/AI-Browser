package com.appgate.brain.planner

import com.appgate.brain.json.Json
import com.appgate.brain.json.JsonObject
import com.appgate.brain.model.*
import org.junit.Assert.*
import org.junit.Test

class PlannerCurrentStateTest {
    private class Capture : PlannerClient {
        val requests = mutableListOf<JsonObject>()
        val prompts = mutableListOf<String>()
        override val describe = "capture-only"
        override fun complete(instructions: String, input: String, schemaName: String, schema: JsonObject, maxOutputTokens: Int): String {
            requests += Json.parseObject(input)
            prompts += instructions
            return """{"steps":[],"confidence":0,"needs_human":null,"give_up":true,"rationale":"fixture","vocabulary":[]}"""
        }
    }
    private val goal = Goal("fixture", GoalIntent.FIND_LISTINGS, "find bicycle", "bicycle", emptyList())
    private val ledger = TaskLedger("fixture", goal, "fixture.market", "https://fixture.market/search")
    private fun page(selected: Boolean) = SemanticPageState(
        "fixture.market", "https://fixture.market/search", "/search", "", PageType.SEARCH,
        1.0, emptyList(), listOf(Affordance("tab", Role.TAB, name = "Listings", selected = selected),
            Affordance("query", Role.SEARCH_BOX), Affordance("hidden", Role.TAB, visible = false)),
        emptyList(), emptyMap(), Settle.IDLE, false, false, false, "fixture")
    private fun request(client: Capture, state: SemanticPageState) =
        Planner(client).proposeProgram(goal, state, ledger, emptyList(), listOf("search:FAILED"), emptySet(), "search")
    private fun controls(request: JsonObject) = request.optObject("page")!!.optArray("affordances")!!.objects()

    @Test fun unselectedControlHasAnExplicitBooleanInTheActualPlannerRequest() {
        val client = Capture()
        request(client, page(false))
        val tab = controls(client.requests.single()).single { it.optString("id") == "tab" }
        assertTrue("omission cannot distinguish false state in the model request", tab.has("selected"))
        assertEquals(false, tab["selected"]?.asBooleanOrNull())
    }

    @Test fun eachRequestReflectsTheCurrentSelectionAfterARendererStateChange() {
        val client = Capture()
        ledger.record(LedgerStep(1, TaskPhase.SEARCH, "prior-renderer", "TAB", VerifyStatus.VERIFIED, "", "planner"))
        request(client, page(true))
        request(client, page(false))
        val selected = client.requests.map { input -> controls(input).single { it.optString("id") == "tab" }["selected"]?.asBooleanOrNull() }
        assertEquals(listOf(true, false), selected)
        assertEquals("TAB -> VERIFIED", client.requests.last().optObject("task")!!.optArray("recent_steps")!![0].asStringOrNull())
        assertEquals("search:FAILED", client.requests.last().optObject("task")!!.optArray("failures_here")!![0].asStringOrNull())
    }

    @Test fun hiddenControlsStayAbsentAndSelectionDoesNotAddExtraPageData() {
        val client = Capture()
        request(client, page(false))
        val controls = controls(client.requests.single())
        assertEquals(listOf("tab", "query"), controls.map { it.optString("id") })
        assertFalse(client.requests.single().toString().contains("https://"))
        assertFalse(controls.single { it.optString("id") == "query" }.has("selected"))
    }

    @Test fun authenticationStatesNeverReachThePlannerTransport() {
        val client = Capture()
        try {
            request(client, page(false).copy(pageType = PageType.AUTH_WALL, authWall = true))
            fail("expected refusal")
        } catch (_: PlannerRefused) { }
        assertTrue(client.requests.isEmpty())
    }
    @Test fun demonstrationBeforeAndAfterKeepTheirOwnTabSelection() {
        val client = Capture()
        Planner(client).explainDemonstration(com.appgate.brain.json.JsonArray(), page(false), page(true))
        val input = client.requests.single()
        assertTrue(client.prompts.single().contains("selected:false"))
        assertTrue(client.prompts.single().contains("no positive selection signal"))
        val before = input.optObject("before")!!.optArray("affordances")!!.objects().single { it.optString("id") == "tab" }
        val after = input.optObject("after")!!.optArray("affordances")!!.objects().single { it.optString("id") == "tab" }
        assertEquals(false, before["selected"]?.asBooleanOrNull())
        assertEquals(true, after["selected"]?.asBooleanOrNull())
    }

    @Test fun explicitFalseStatesKeepTheExistingAffordanceLimit() {
        val state = page(false).copy(affordances = (1..100).map { Affordance("tab$it", Role.TAB) })
        val controls = state.summary().optArray("affordances")!!.objects()
        assertEquals(60, controls.size)
        assertTrue(controls.all { it["selected"]?.asBooleanOrNull() == false })
    }

    @Test fun existingPositiveSelectionSignalsOnOtherRolesArePreserved() {
        val state = page(false).copy(affordances = listOf(Affordance("choice", Role.FACET, selected = true)))
        val control = state.summary().optArray("affordances")!!.objects().single()
        assertEquals(true, control["selected"]?.asBooleanOrNull())
    }

}
