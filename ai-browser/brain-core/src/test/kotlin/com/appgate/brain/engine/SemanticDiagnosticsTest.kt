package com.appgate.brain.engine

import com.appgate.brain.goal.GoalParser
import com.appgate.brain.memory.*
import com.appgate.brain.model.*
import com.appgate.brain.perception.SpsParser
import com.appgate.brain.test.FakeSite
import org.junit.Assert.*
import org.junit.Test

class SemanticDiagnosticsTest {
    @Test fun exportsFailureAndObservationCountsWithoutUserOrPageText() {
        val fake = FakeSite().apply { dialogShown = false }
        val page = SpsParser().parse(fake.observe(1000)).copy(url = "https://fake.market/search?q=private-query", title = "private-title", detailText = "private-description")
        val ledger = TaskLedger("id", GoalParser.parse("private-query"), fake.host, page.url).apply {
            programSource = "skill:private-source"; programCapability = "private-capability"
        }
        val data = SemanticDiagnostics.action(ledger, page, page.copy(url = "https://redirect.market/path?token=private-token", collections = emptyList()),
            Step(StepKind.TYPE, Role.SEARCH_BOX, facetKey = "private-facet", arg = "private-query", nameHint = "private-name"), VerifyStatus.FAILED, DiagnosticCode.UNEXPECTED_HOST)
        assertEquals("UNEXPECTED_HOST", data.optString("code"))
        assertEquals("SEARCH_BOX", data.optString("role"))
        assertEquals(page.resultKeys.size, data.optObject("before")!!.optInt("items"))
        assertEquals(0, data.optObject("after")!!.optInt("items"))
        assertEquals("redirect.market", data.optObject("after")!!.optString("host"))
        assertFalse(data.toString().contains("private-"))
        assertFalse(data.toString().contains("?"))
    }

    @Test fun failedStrategyMemoryAllowsDifferentProcedureAndChangedControlsAndStaysBounded() {
        val site = SiteModel("fake.market")
        val page = SpsParser().parse(FakeSite().observe(1000))
        val typed = listOf(Step(StepKind.TYPE, Role.SEARCH_BOX, arg = "private-query", submit = true))
        val key = FailedStrategies.key(page, "search", typed)
        repeat(2) { FailedStrategies.record(site, key, false, 1000) }
        val reloaded = SiteModel.fromJson(site.toJson())
        assertFalse(FailedStrategies.allowed(reloaded, key, 2000))
        assertTrue(FailedStrategies.allowed(reloaded, FailedStrategies.key(page, "search", listOf(Step(StepKind.CLICK, Role.SUBMIT))), 2000))
        val changed = page.copy(affordances = page.affordances.map { if (it.role == Role.SEARCH_BOX) it.copy(enabled = false) else it })
        assertTrue(FailedStrategies.allowed(reloaded, FailedStrategies.key(changed, "search", typed), 2000))
        assertFalse(reloaded.toJson().toString().contains("private-query"))
        repeat(100) { FailedStrategies.record(reloaded, "key-$it", false, 2000L + it) }
        assertTrue(reloaded.strategyFailures.size <= 64)
        FailedStrategies.record(reloaded, key, true, 3000)
        assertTrue(FailedStrategies.allowed(reloaded, key, 3001))
    }
    @Test fun idleRechecksExposeStructuralLessonEligibilityWithoutPageValues() {
        val fake = FakeSite().apply { dialogShown = false; navigate("https://fake.market/search?q=Ford", 1000); page = 2 }
        val memory = Memory(InMemoryStorage()) { 1000L }
        val site = memory.site(fake.host)
        Curriculum.ensure(site)
        site.curriculum.filter { it.id != "next_page" }.forEach { it.completedAt = 999 }
        val captured = mutableListOf<com.appgate.brain.json.JsonObject>()
        val events = object : EngineEvents {
            override fun diagnostic(host: String, kind: String, data: com.appgate.brain.json.JsonObject) {
                if (kind == "learning_recheck") captured += data
            }
        }
        val engine = BrainEngine(fake, memory, { null }, events, config = EngineConfig(pacingOverrideMs = 0)) { 1000L }
        val profile = com.appgate.brain.profile.SiteProfile("fake", "Fake", listOf(fake.host), "https://fake.market/", trainingQueries = listOf("private-query"))
        LearningSession(engine, memory, events, listOf(profile), clock = { 1000L }).run(maxSites = 1)
        val diagnostic = captured.single()
        val pending = diagnostic.optArray("pending_states")
        assertNotNull("diagnose the eligibility gate instead of only reporting available=false", pending)
        val lesson = pending!!.objects().single()
        assertEquals("next_page", lesson.optString("lesson"))
        assertEquals("ABSENT", lesson.optString("opportunity"))
        assertFalse(lesson.optBoolean("cooling"))
        assertNotNull(diagnostic.optObject("page"))
        assertFalse(diagnostic.toString().contains("private-query"))
        assertFalse(diagnostic.toString().contains("Ford"))
        assertFalse(diagnostic.toString().contains("https://"))
    }

}
