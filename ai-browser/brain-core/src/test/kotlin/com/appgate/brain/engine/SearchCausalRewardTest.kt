package com.appgate.brain.engine

import com.appgate.brain.memory.*
import com.appgate.brain.model.*
import com.appgate.brain.test.SearchFormSite
import org.junit.Assert.*
import org.junit.Test

class SearchCausalRewardTest {
    private val now = 1_800_000_000_000L
    private fun run(site: SearchFormSite, query: String, memory: Memory = Memory(InMemoryStorage()) { now }): Pair<TaskLedger, Memory> {
        val goal = Goal("causal", GoalIntent.LEARN_SITE, "synthetic search", query, emptyList(), budget = Budget(actions = 12, llmCalls = 0, wallMs = 10000))
        val ledger = TaskLedger("causal", goal, site.site.host, site.currentUrl()).apply { lesson = "search"; lastCheckpointUrl = site.currentUrl() }
        val config = EngineConfig(pacingOverrideMs = 0, plannerCooldownMs = 0, ambiguousRecheckMs = 0, idleSleepMs = 0)
        return BrainEngine(site, memory, { null }, object : EngineEvents {}, config) { now }.runTask(ledger, EngineMode.TRAIN) to memory
    }
    @Test fun legacyBuiltinCannotRewardDraftTypingWithUnchangedResults() {
        val site = SearchFormSite(submitWorks = false)
        site.navigate("https://fake.market/search?q=Toyota", 1000)
        val (out, memory) = run(site, "Ford")
        assertEquals("Ford", site.draft)
        assertEquals("Toyota", site.site.query)
        assertTrue("neither draft input nor staying on RESULTS proves a submitted search", "search" !in out.successfulSkills)
        assertNotEquals(TaskStatus.DONE, out.status)
        assertTrue(memory.site(site.site.host).curriculum.none { it.id == "search" && it.done })
    }

    @Test fun validUnchangedRefreshPreservesEligibilityForALaterDifferentQuery() {
        val site = SearchFormSite(enterWorks = true)
        site.navigate("https://fake.market/search?q=Ford", 1000)
        val (refresh, memory) = run(site, "Ford")
        assertTrue("unchanged results are not new learning", "search" !in refresh.successfulSkills)
        assertTrue("no new evidence does not prove a broken search", memory.site(site.site.host).strategyFailures.isEmpty())
        val (changed, _) = run(site, "Toyota", memory)
        assertEquals(TaskStatus.DONE, changed.status)
        assertTrue("a genuinely different later search remains usable", "search" in changed.successfulSkills)
        assertEquals("Toyota", site.site.query)
    }

    @Test fun identicalResultsCannotCreateNewSearchMastery() {
        val site = SearchFormSite(enterWorks = true)
        site.navigate("https://fake.market/search?q=Ford", 1000)
        val (out, _) = run(site, "Ford")
        assertTrue("a no-change refresh provides no new causal search evidence", "search" !in out.successfulSkills)
        assertNotEquals(TaskStatus.DONE, out.status)
    }

    @Test fun unrelatedVerifiedSubmitCannotPromoteASatisfiedSearchPrerequisite() {
        val site = SearchFormSite()
        site.navigate("https://fake.market/search?q=Toyota", 1000)
        site.draft = "Ford"
        val parser = com.appgate.brain.perception.SpsParser()
        val before = parser.parse(site.observe(1000))
        site.site.navigate("https://fake.market/search?q=Ford", 1000)
        val after = parser.parse(site.observe(1000))
        val memory = Memory(InMemoryStorage()) { now }
        val ledger = TaskLedger("weak-submit", Goal("weak", GoalIntent.LEARN_SITE, "synthetic search", "Ford", emptyList()), site.site.host, site.currentUrl())
        ledger.currentProgram = listOf(
            Step(StepKind.TYPE, Role.SEARCH_BOX, arg = "Ford", submit = false, expect = listOf(Postcondition.ValueIs("query", "Ford"))),
            Step(StepKind.CLICK, Role.TAB, expect = listOf(Postcondition.ResultsChanged)),
            Step(StepKind.CLICK, Role.SUBMIT, expect = listOf(Postcondition.UrlChanged)))
        ledger.programCapability = "search"; ledger.programSource = "local"; ledger.programPage = before.pageType
        ledger.programPost = listOf(Postcondition.PageTypeIs(PageType.RESULTS))
        ledger.programVerifiedSteps += listOf(1, 2)
        val engine = BrainEngine(site, memory, { null }, object : EngineEvents {})
        // Model adversarial evidence: TAB changed listings; Submit only changed the URL.
        engine.javaClass.getDeclaredField("programBefore").apply { isAccessible = true }.set(engine, before)
        @Suppress("UNCHECKED_CAST")
        val satisfied = engine.javaClass.getDeclaredField("programSatisfiedSteps").apply { isAccessible = true }.get(engine) as MutableSet<Int>
        satisfied += 0
        engine.javaClass.getDeclaredMethod("finishProgram", TaskLedger::class.java, SemanticPageState::class.java, SiteModel::class.java, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(engine, ledger, after, memory.site(site.site.host), true)
        assertFalse("an unrelated Submit transition cannot make the prior TAB a search submission", ledger.programCompleted)
        assertTrue(ledger.successfulSkills.isEmpty())
        assertTrue(memory.skills.all().none { it.origin == SkillOrigin.COMPILED })
    }

    @Test fun ignoredAlternativeIsRememberedAfterBrokenEnterHasFilledDraft() {
        val storage = InMemoryStorage()
        repeat(2) { index ->
            val form = SearchFormSite(submitWorks = false)
            form.navigate("https://fake.market/search?q=Toyota", 1000)
            val memory = Memory(storage) { now }
            val (out, _) = run(form, "Ford", memory)
            assertTrue("failed submission must not earn search credit", out.successfulSkills.isEmpty())
            assertEquals("Toyota", form.site.query)
            assertEquals(if (index == 0) 1 else 0, form.enterAttempts)
            assertEquals("persisted failure must stop retrying the same ignored Search button", if (index == 0) 1 else 0, form.buttonAttempts)
            val page = com.appgate.brain.perception.SpsParser().parse(form.observe(1000), now)
            val alternative = listOf(
                Step(StepKind.TYPE, Role.SEARCH_BOX, arg = "\$query", submit = false, expect = listOf(Postcondition.ValueIs("query", "\$query"))),
                Step(StepKind.CLICK, Role.SUBMIT, expect = listOf(Postcondition.ResultsChanged, Postcondition.PageTypeIs(PageType.RESULTS))))
            val key = FailedStrategies.key(page, "search", alternative, mapOf("query" to "Ford"))
            assertFalse("a changed draft is not an unchanged committed-query refresh", FailedStrategies.allowed(memory.site(form.site.host), key, now))
        }
    }
}
