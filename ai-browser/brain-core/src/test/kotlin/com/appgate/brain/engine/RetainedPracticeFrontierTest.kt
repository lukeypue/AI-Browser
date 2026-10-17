package com.appgate.brain.engine

import com.appgate.brain.json.Json
import com.appgate.brain.memory.*
import com.appgate.brain.model.*
import com.appgate.brain.perception.SpsParser
import com.appgate.brain.profile.SiteProfile
import com.appgate.brain.skills.BuiltinSkills
import com.appgate.brain.test.FakeSite
import org.junit.Assert.*
import org.junit.Test

class RetainedPracticeFrontierTest {
    private val now = 1_800_000_000_000L
    private fun results(): SemanticPageState = SpsParser().parse(FakeSite().apply {
        dialogShown = false
        navigate("https://fake.market/search?q=Ford", 1000)
    }.observe(1000), now).copy(
        viewportHeight = 720, scrollHeight = 1500,
        affordances = listOf(
            Affordance("condition", Role.FACET, "condition", "choice", value = "Any", choices = listOf("Any", "Good", "Fair")),
            Affordance("price", Role.FACET, "price_max", "numeric_max", value = ""),
            Affordance("more", Role.LOAD_MORE)
        )
    )
    private fun saved(vararg pending: String): SiteModel = SiteModel("fake.market").also { site ->
        Curriculum.ensure(site)
        site.curriculum.filter { it.id !in pending }.forEach { it.completedAt = now - 600_000L }
    }.let { SiteModel.fromJson(Json.parseObject(it.toJson().toString())) }

    private fun heldChoice(site: SiteModel, page: SemanticPageState): String =
        FailedStrategies.key(page, "select_facet", BuiltinSkills.selectFacet.body, mapOf("key" to "condition", "value" to "Good")).also {
            FailedStrategies.record(site, it, false, now - 60_000L)
        }
    private fun gate(site: SiteModel, held: String): (String) -> Boolean =
        { id -> id != "select_facet" || FailedStrategies.allowed(site, held, now) }

    @Test fun persistedHeldUnfinishedTargetDoesNotStarveDueVerifiedPractice() {
        val page = results()
        val original = saved("select_facet", "next_page", "dismiss_dialog")
        val held = heldChoice(original, page)
        val site = SiteModel.fromJson(Json.parseObject(original.toJson().toString()))
        Curriculum.observe(site, page, now)
        val completions = site.curriculum.associate { it.id to it.completedAt }
        val retries = site.curriculum.associate { it.id to it.retryAt }
        assertEquals(8, site.curriculum.count { it.done })
        assertFalse(FailedStrategies.allowed(site, held, now))
        assertEquals("", Curriculum.nextLesson(site, now, page, gate(site, held)))
        assertEquals("constrain_numeric", Curriculum.practiceLesson(site, now, page, gate(site, held)))
        assertEquals(completions, site.curriculum.associate { it.id to it.completedAt })
        assertEquals(retries, site.curriculum.associate { it.id to it.retryAt })
        assertFalse("allocating another capability cannot clear a concrete failure", FailedStrategies.allowed(site, held, now + 1_800_000L))
    }

    @Test fun attemptableUnfinishedTargetKeepsPriorityOverDuePractice() {
        val site = saved("select_facet", "next_page", "dismiss_dialog")
        val page = results()
        Curriculum.observe(site, page, now)
        assertEquals("select_facet", Curriculum.nextLesson(site, now, page))
        assertEquals("", Curriculum.practiceLesson(site, now, page))
    }

    @Test fun coolingUnfinishedTargetDoesNotStarveDuePracticeOrLoseItsDelay() {
        val site = saved("select_facet", "next_page", "dismiss_dialog")
        val page = results()
        Curriculum.observe(site, page, now)
        site.curriculum.single { it.id == "select_facet" }.retryAt = now + 30_000L
        assertEquals("constrain_numeric", Curriculum.practiceLesson(site, now, page))
        assertEquals(now + 30_000L, site.curriculum.single { it.id == "select_facet" }.retryAt)
    }

    @Test fun paginationDiscoveryCooldownDoesNotStarveDuePracticeOrLoseItsPersistedHold() {
        val original = saved("next_page", "load_more", "dismiss_dialog")
        original.paginationDiscoveryRetryAt = now + 300_000L
        val site = SiteModel.fromJson(Json.parseObject(original.toJson().toString()))
        val page = results().copy(affordances = listOf(Affordance("price", Role.FACET, "price_max", "numeric_max")))
        Curriculum.observe(site, page, now)
        assertEquals("seek_pagination", LearningOpportunities.target(page, "next_page")!!.skillId)
        assertEquals("seek_pagination", LearningOpportunities.target(page, "load_more")!!.skillId)
        assertEquals("", Curriculum.nextLesson(site, now, page))
        assertEquals("constrain_numeric", Curriculum.practiceLesson(site, now, page))
        assertEquals(now + 300_000L, site.paginationDiscoveryRetryAt)
        assertFalse(site.curriculum.single { it.id == "next_page" }.done)
        assertFalse(site.curriculum.single { it.id == "load_more" }.done)
        val actualNext = page.copy(affordances = page.affordances + Affordance("next", Role.PAGE_NEXT))
        assertEquals("next_page", Curriculum.nextLesson(site, now, actualNext))
        assertEquals("a real unfinished control keeps priority despite an unrelated discovery hold", "", Curriculum.practiceLesson(site, now, actualNext))
        assertEquals(now + 300_000L, site.paginationDiscoveryRetryAt)
    }

    @Test fun absentTargetsAllowOldestUsablePracticeAndHeldCompletedTargetsStayHeld() {
        val site = saved("select_facet", "next_page", "dismiss_dialog")
        val page = results().copy(affordances = listOf(Affordance("price", Role.FACET, "price_max", "numeric_max"), Affordance("more", Role.LOAD_MORE)))
        Curriculum.observe(site, page, now)
        site.curriculum.single { it.id == "load_more" }.completedAt = now - 900_000L
        assertEquals("load_more", Curriculum.practiceLesson(site, now, page))
        assertEquals("constrain_numeric", Curriculum.practiceLesson(site, now, page) { it != "load_more" })
        assertEquals("", Curriculum.practiceLesson(site, now, page) { false })
    }

    @Test fun successfulPracticeUsesPersistedFiveMinuteCadenceAndFairOldestSelection() {
        val site = saved("select_facet", "next_page", "dismiss_dialog")
        val page = results().copy(affordances = listOf(Affordance("price", Role.FACET, "price_max", "numeric_max"), Affordance("more", Role.LOAD_MORE)))
        Curriculum.observe(site, page, now)
        site.curriculum.single { it.id == "load_more" }.completedAt = now - 900_000L
        assertEquals("load_more", Curriculum.practiceLesson(site, now, page))
        Curriculum.markSkillVerified(site, "load_more", now)
        val restored = SiteModel.fromJson(Json.parseObject(site.toJson().toString()))
        assertEquals("constrain_numeric", Curriculum.practiceLesson(restored, now + 1, page))
        assertEquals("", Curriculum.practiceLesson(restored, now + 299_999L, page) { it == "load_more" })
        assertEquals("load_more", Curriculum.practiceLesson(restored, now + 300_000L, page) { it == "load_more" })
        assertEquals("", Curriculum.practiceLesson(restored, now - 1, page) { it == "load_more" })
        assertEquals(8, restored.curriculum.count { it.done })
    }

    @Test fun heldFrontierNeverMakesAuthBusyDialogsOrMessagesPracticeable() {
        val site = saved("select_facet", "next_page", "dismiss_dialog")
        val page = results()
        val held = heldChoice(site, page)
        for (unsafe in listOf(page.copy(authWall = true), page.copy(challenge = true),
            page.copy(settle = Settle.BUSY), page.copy(dialogOpen = true), page.copy(pageType = PageType.MESSAGES))) {
            assertEquals("", Curriculum.practiceLesson(site, now, unsafe, gate(site, held)))
        }
    }

    @Test fun fullyCompletedCurriculumRetainsItsOrdinaryReviewPolicy() {
        val site = saved()
        val page = results()
        assertEquals("", Curriculum.practiceLesson(site, now, page))
        assertFalse(Curriculum.reviewDue(site, now))
    }

    @Test fun actualSessionReusesVerifiedPaginationWithoutClearingHeldStrategyOrAwardingNewLessons() {
        val fake = FakeSite().apply { dialogShown = false; navigate("https://fake.market/search?q=Ford", 1000) }
        val memory = Memory(InMemoryStorage()) { now }
        val site = memory.site(fake.host)
        Curriculum.ensure(site)
        site.curriculum.filter { it.id !in setOf("sort_results", "select_facet", "dismiss_dialog") }.forEach { it.completedAt = now - 600_000L }
        site.curriculum.single { it.id == "next_page" }.completedAt = now - 900_000L
        val profile = SiteProfile("fake", "Fake", listOf(fake.host), "https://fake.market/", trainingQueries = listOf("Ford", "Expedition"))
        val page = SpsParser().parse(fake.observe(1000), now)
        val target = LearningOpportunities.target(page, "sort_results")!!
        val params = mapOf("query" to "Ford") + target.params
        val builtinKey = FailedStrategies.key(page, "sort_results", BuiltinSkills.sortResults.body, params)
        FailedStrategies.record(site, builtinKey, false, now - 60_000L)
        val eligibility = TaskLedger("held-sort", Curriculum.nextGoal(site, profile, 0, page, "sort_results"), site.host, page.url).apply { lesson = "sort_results" }
        LocalRecoveryPlanner.propose(eligibility, page, site, "sort_results")?.let { local ->
            FailedStrategies.record(site, FailedStrategies.key(page, "sort_results", local.steps, params + local.params), false, now - 60_000L)
        }
        memory.saveSite(site)
        val beforeKeys = page.resultKeys
        val finished = mutableListOf<TaskLedger>()
        val events = object : EngineEvents { override fun finished(ledger: TaskLedger, result: TaskResult?) { finished += ledger } }
        val engine = BrainEngine(fake, memory, { null }, events,
            EngineConfig(pacingOverrideMs = 0, ambiguousRecheckMs = 0, idleSleepMs = 0)) { now }
        assertFalse("setup must be held by the actual engine gate", engine.learningLessonAvailable(profile, page, "sort_results"))
        LearningSession(engine, memory, events, listOf(profile), clock = { now }).run(maxSites = 1)
        assertEquals("one bounded rehearsal, not an idle visit", 1, finished.size)
        val out = finished.single()
        assertEquals("next_page", out.lesson)
        assertEquals(TaskStatus.DONE, out.status)
        assertTrue(out.actions > 0)
        assertTrue("only genuine complete program proof earns reuse", "next_page" in out.successfulSkills)
        assertTrue(out.steps.any { it.status == VerifyStatus.VERIFIED })
        assertTrue(SpsParser().parse(fake.observe(1000), now).resultKeys.any { it !in beforeKeys })
        assertEquals(8, memory.site(fake.host).curriculum.count { it.done })
        assertFalse(FailedStrategies.allowed(memory.site(fake.host), builtinKey, now))
        assertEquals("TRAIN", fake.networkMode)
    }
}
