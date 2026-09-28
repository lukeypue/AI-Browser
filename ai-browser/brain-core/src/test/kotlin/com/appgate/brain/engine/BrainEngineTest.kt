package com.appgate.brain.engine

import com.appgate.brain.goal.GoalParser
import com.appgate.brain.memory.InMemoryStorage
import com.appgate.brain.memory.Memory
import com.appgate.brain.model.Budget
import com.appgate.brain.model.GoalIntent
import com.appgate.brain.model.Role
import com.appgate.brain.model.TaskLedger
import com.appgate.brain.model.TaskStatus
import com.appgate.brain.model.Verdict
import com.appgate.brain.model.VerifyStatus
import com.appgate.brain.test.FakeSite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrainEngineTest {
    private class Recorder : EngineEvents {
        val statuses = mutableListOf<String>()
        val steps = mutableListOf<Pair<String, VerifyStatus?>>()
        var human: String? = null
        var grantPreview: String? = null
        override fun status(text: String) { statuses += text }
        override fun step(ledger: TaskLedger, description: String, status: VerifyStatus?) { steps += description to status }
        override fun needHuman(ledger: TaskLedger, reason: String, url: String) { human = reason }
        override fun needGrant(ledger: TaskLedger, previewText: String, previewHash: String) { grantPreview = previewText }
    }

    private fun engine(site: FakeSite, memory: Memory, events: Recorder) =
        BrainEngine(site, memory, { null }, events, EngineConfig(plannerCooldownMs = 0L, ambiguousRecheckMs = 10L, pacingOverrideMs = 0L))

    @Test
    fun findsVerifiesAndRanksListingsEndToEnd() {
        val site = FakeSite()
        val memory = Memory(InMemoryStorage())
        val events = Recorder()
        val goal = GoalParser.parse("Ford Expedition under 8000 under 150000 miles with a 3.73 axle", Budget(itemsInspected = 6, actions = 60, wallMs = 60_000))
        val ledger = TaskLedger("t1", goal, site.host, "https://${site.host}/")
        val out = engine(site, memory, events).runTask(ledger, EngineMode.ASSIST)

        assertEquals(TaskStatus.DONE, out.status)
        val result = engine(site, memory, events).resultFor(out)
        // Verified: 1 (7500, 142k, 3.73 gears) and 4 (7950, 121.5k, 3.73 axle ratio). 3 has 3.73 but 198k miles -> near miss? mileage violated by 32% -> excluded.
        val verifiedTitles = result.verified.map { it.title }
        assertTrue("verified should include listing 1: $verifiedTitles", verifiedTitles.any { it.startsWith("2008 Ford Expedition") })
        assertTrue("verified should include listing 4: $verifiedTitles", verifiedTitles.any { it.startsWith("2012 Ford Expedition King Ranch") })
        assertTrue("6 (150,500 miles) is a near miss", result.nearMiss.any { it.title.startsWith("2010 Ford Expedition EL") })
        assertTrue(result.verified.all { it.perConstraint["axle_ratio"] == Verdict.SAT })
        assertTrue(result.verified.all { it.evidence.any { e -> e.key == "axle_ratio" } })
        // Sequoia never appears: the search filtered it out.
        assertTrue(result.verified.none { it.title.contains("Sequoia") } && result.partial.none { it.title.contains("Sequoia") })
        // The dialog was dismissed, search verified, the price facet applied, details inspected.
        assertTrue(events.steps.any { it.first.contains("dismiss") && it.second == VerifyStatus.VERIFIED })
        assertTrue(events.steps.any { it.first.contains("SEARCH_BOX") && it.second == VerifyStatus.VERIFIED })
        assertTrue("price facet should have verified: ${events.steps}", events.steps.any { it.first.contains("FACET[price_max]") && it.second == VerifyStatus.VERIFIED })
        assertTrue(out.appliedConstraints.contains("price_max"))
        assertTrue(out.itemsInspected >= 2)
        // Memory learned: bindings with verified stats, a search URL template, curriculum progress.
        val model = memory.site(site.host)
        assertTrue(model.bindings.values.any { it.role == Role.SEARCH_BOX && it.stats.successes > 0 })
        assertTrue(model.bindings.values.any { it.role == Role.FACET && it.facetKey == "price_max" && it.stats.successes > 0 })
        assertNotNull(model.searchUrlTemplate)
        assertTrue(model.searchUrlTemplate!!.contains("{q}"))
        assertTrue(memory.skills.get("search")!!.stat(site.host).successes > 0)
        assertTrue(memory.episodes(site.host).isNotEmpty())
        // Privacy: no listing text in the site model or episodes.
        val json = model.toJson().toString()
        assertFalse(json.contains("Expedition"))
        assertTrue(memory.episodes(site.host).none { it.toJson().toString().contains("Expedition") })
    }

    @Test
    fun secondRunUsesLearnedSearchUrlAndFewerActions() {
        val site = FakeSite()
        val memory = Memory(InMemoryStorage())
        val goal = GoalParser.parse("Ford Expedition under 8000", Budget(itemsInspected = 3, actions = 60, wallMs = 60_000))
        val first = engine(site, memory, Recorder()).runTask(TaskLedger("a", goal, site.host, "https://${site.host}/"))
        site.url = "https://${site.host}/"; site.query = ""; site.priceMax = null; site.page = 1
        val secondEvents = Recorder()
        val second = engine(site, memory, secondEvents).runTask(TaskLedger("b", goal, site.host, "https://${site.host}/"))
        assertEquals(TaskStatus.DONE, second.status)
        assertTrue(second.usedSearchUrl)
        assertTrue("second run ${second.actions} should not exceed first ${first.actions}", second.actions <= first.actions)
    }

    @Test
    fun loginWallPausesForHumanAndResumes() {
        val site = FakeSite().apply { loginWall = true; dialogShown = false }
        val memory = Memory(InMemoryStorage())
        val events = Recorder()
        val goal = GoalParser.parse("Ford Expedition under 8000", Budget(itemsInspected = 2, actions = 30, wallMs = 60_000))
        val ledger = TaskLedger("t2", goal, site.host, "https://${site.host}/")
        val out = engine(site, memory, events).runTask(ledger)
        assertEquals(TaskStatus.NEED_HUMAN, out.status)
        assertNotNull(events.human)
        assertTrue(memory.loadLedger("t2") != null)
        // Human signs in, then resumes: the same ledger continues.
        site.loginWall = false
        val resumed = engine(site, memory, events).runTask(memory.loadLedger("t2")!!)
        assertEquals(TaskStatus.DONE, resumed.status)
        assertTrue(resumed.verdicts.isNotEmpty())
    }

    @Test
    fun messageRequiresGrantAndTrainModeNeverTypesIntoComposer() {
        val site = FakeSite().apply { dialogShown = false }
        val memory = Memory(InMemoryStorage())
        val events = Recorder()
        val goal = GoalParser.parse("message seller saying \"Is this still available?\"").copy(targetUrl = "https://${site.host}/item/4", budget = Budget(actions = 30, wallMs = 60_000))
        assertEquals(GoalIntent.PREPARE_MESSAGE, goal.intent)

        // TRAIN mode: composer typing is blocked by the executor interlock; nothing is sent.
        val trainLedger = TaskLedger("m0", goal, site.host, "https://${site.host}/")
        val trainOut = engine(site, memory, events).runTask(trainLedger, EngineMode.TRAIN)
        assertEquals(0, site.sent)
        assertTrue(trainOut.status != TaskStatus.NEED_GRANT)
        site.composerOpen = false; site.composerText = ""

        // ASSIST mode: prepare, preview, wait for grant.
        val ledger = TaskLedger("m1", goal, site.host, "https://${site.host}/")
        val eng = engine(site, memory, events)
        val out = eng.runTask(ledger, EngineMode.ASSIST)
        assertEquals(TaskStatus.NEED_GRANT, out.status)
        assertEquals("Is this still available?", events.grantPreview)
        assertEquals(0, site.sent)
        assertEquals("Is this still available?", site.composerText)

        // Grant -> commit executes exactly once.
        eng.grant(out)
        val committed = eng.runTask(out, EngineMode.ASSIST)
        assertEquals(TaskStatus.DONE, committed.status)
        assertEquals(1, site.sent)
        assertTrue(committed.grants.all { it.used })
    }

    @Test
    fun rendererTimeoutsAreRecordedAndRecovered() {
        val site = FakeSite().apply { dialogShown = false; observeTimeoutsToInject = 1 }
        val memory = Memory(InMemoryStorage())
        val events = Recorder()
        val goal = GoalParser.parse("Ford Expedition under 8000", Budget(itemsInspected = 2, actions = 40, wallMs = 60_000))
        val out = engine(site, memory, events).runTask(TaskLedger("t3", goal, site.host, "https://${site.host}/"))
        assertEquals(TaskStatus.DONE, out.status)
        assertTrue(out.timeouts >= 1)
        assertTrue(site.log.contains("recover"))
    }

    @Test
    fun ledgerSurvivesSerialization() {
        val site = FakeSite().apply { dialogShown = false }
        val memory = Memory(InMemoryStorage())
        val goal = GoalParser.parse("Ford Expedition under 8000 with a 3.73 axle", Budget(itemsInspected = 3, actions = 40, wallMs = 60_000))
        val out = engine(site, memory, Recorder()).runTask(TaskLedger("t4", goal, site.host, "https://${site.host}/"))
        val back = TaskLedger.fromJson(com.appgate.brain.json.Json.parseObject(out.toJson().toString()))
        assertEquals(out.status, back.status)
        assertEquals(out.verdicts.size, back.verdicts.size)
        assertEquals(out.steps.size, back.steps.size)
        assertEquals(out.appliedConstraints, back.appliedConstraints)
        assertEquals(out.verdicts.values.first().perConstraint, back.verdicts.values.first().perConstraint)
    }

    @Test
    fun curriculumLearningCompletesCoreGoals() {
        val site = FakeSite()
        val memory = Memory(InMemoryStorage())
        val events = Recorder()
        val profile = com.appgate.brain.profile.SiteProfile("fake", "Fake", listOf(site.host), "https://${site.host}/", trainingQueries = listOf("Ford Expedition"), minActionIntervalMs = 0L)
        val session = LearningSession(engine(site, memory, events), memory, events, listOf(profile), perSiteChunkMs = 30_000L)
        session.run(maxSites = 1)
        val model = memory.site(site.host)
        assertTrue(Curriculum.progress(model), model.curriculum.first { it.id == "search" }.done)
        assertTrue(model.curriculum.first { it.id == "open_item" }.done)
        assertTrue(model.curriculum.first { it.id == "constrain_numeric" }.done)
        assertEquals("TRAIN", site.networkMode)
        assertEquals(0, site.sent)
    }
}
