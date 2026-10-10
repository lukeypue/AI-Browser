package com.appgate.brain.engine

import com.appgate.brain.json.*
import com.appgate.brain.memory.*
import com.appgate.brain.model.*
import com.appgate.brain.perception.SpsParser
import com.appgate.brain.profile.SiteProfile
import com.appgate.brain.test.FakeSite
import org.junit.Assert.*
import org.junit.Test

class LearningStallRegressionTest {
    private val now = 1_800_000_000_000L
    private fun results() = SpsParser().parse(FakeSite().apply {
        dialogShown = false; navigate("https://fake.market/search?q=Ford", 1000)
    }.observe(1000), now).copy(settle = Settle.IDLE)

    @Test fun oldFailuresDoNotHideClearLiveSearchOrCloseControls() {
        for (role in listOf(Role.SEARCH_BOX, Role.CLOSE)) {
            val site = SiteModel("fake.market")
            repeat(20) { site.recordFailure(PageType.RESULTS, role, null, "old failed procedure", now - 1000) }
            val control = Affordance("live", role, name = if (role == Role.CLOSE) "Close" else "Search", roleScore = .95,
                tag = if (role == Role.CLOSE) "button" else "input")
            val page = results().copy(affordances = listOf(control), dialogOpen = role == Role.CLOSE)
            val step = if (role == Role.CLOSE) Step(StepKind.DISMISS, role) else Step(StepKind.TYPE, role, arg = "Ford")
            assertTrue("a clear $role must stay groundable after old failures", StepGrounder(site).ground(step, emptyMap(), page, emptySet()) is GroundingOutcome.Ready)
            assertEquals(20, site.failureCount(PageType.RESULTS, role, null))
        }
    }

    @Test fun oldFailuresStillRejectWeakAndFallbackControls() {
        val site = SiteModel("fake.market")
        repeat(20) { site.recordFailure(PageType.RESULTS, Role.CLOSE, null, "old failed procedure", now - 1000) }
        for (control in listOf(Affordance("weak", Role.CLOSE, roleScore = .5),
            Affordance("fallback", Role.GENERIC_BUTTON, roleScore = .95))) {
            assertTrue(StepGrounder(site).ground(Step(StepKind.DISMISS, Role.CLOSE), emptyMap(),
                results().copy(affordances = listOf(control), dialogOpen = true), emptySet()) is GroundingOutcome.Missing)
        }
    }

    @Test fun footerNextControlCanBeGrounded() {
        val page = results().copy(affordances = listOf(Affordance("next", Role.PAGE_NEXT, name = "Next page", roleScore = .95,
            tag = "a", regionRole = RegionRole.FOOTER)))
        assertTrue(StepGrounder(null).ground(Step(StepKind.CLICK, Role.PAGE_NEXT), emptyMap(), page, emptySet()) is GroundingOutcome.Ready)
    }

    @Test fun paginationCooldownSurvivesScrollAndUnrelatedFilterChanges() {
        val site = SiteModel("fake.market"); Curriculum.ensure(site)
        val top = results().copy(affordances = emptyList(), viewportHeight = 800, scrollHeight = 4000)
        Curriculum.observe(site, top, now)
        val lesson = site.curriculum.single { it.id == "next_page" }; lesson.retryAt = now + 60_000
        val bottom = top.copy(scrollY = 3200, affordances = listOf(Affordance("price", Role.FACET, "price", "numeric_max", tag = "input")))
        Curriculum.observe(site, bottom, now + 1)
        assertEquals(now + 60_000, lesson.retryAt)
        Curriculum.observe(site, top, now + 2)
        assertEquals("resetting the viewport must not restart an exhausted search", now + 60_000, lesson.retryAt)
        Curriculum.observe(site, bottom.copy(affordances = listOf(Affordance("next", Role.PAGE_NEXT))), now + 3)
        assertEquals("a newly found real next button should reopen the lesson", 0L, lesson.retryAt)
        assertFalse(lesson.done)
    }

    private fun probePanel(sameUrl: Boolean = false, cooling: Boolean = false, dialog: Boolean = false, live: Boolean = false, auth: Boolean = false): Pair<SemanticPageState?, Int> {
        val fake = FakeSite().apply { dialogShown = false; navigate(if (sameUrl) "https://fake.market/search?q=mountain+bike" else "https://fake.market/panel", 1000) }
        val memory = Memory(InMemoryStorage()) { now }; val site = memory.site(fake.host); Curriculum.ensure(site)
        site.curriculum.filter { it.id != if (live) "constrain_numeric" else "next_page" }.forEach { it.completedAt = now - 1 }
        if (cooling) site.curriculum.filter { !it.done }.forEach { it.retryAt = now + 60_000 }
        val raw = Json.parseObject(fake.observe(1000)).put("items", JsonArray())
            .put("elements", JsonArray(listOf("price", "distance", "year").map { key ->
                JsonObject().put("id", key).put("tag", "button").put("name", key).put("expanded", false).put("visible", true).put("enabled", true)
            })).put("signals", JsonObject().put("dialog", dialog).put("passwordFields", if (auth) 1 else 0).put("sparse", auth)).toString()
        if (!auth) assertEquals(PageType.FACET_PANEL, SpsParser().parse(raw).pageType)
        var navigations = 0
        val renderer = object : Renderer by fake {
            override fun observe(timeoutMs: Long) = if (navigations == 0) raw else fake.observe(timeoutMs)
            override fun navigate(url: String, timeoutMs: Long): RendererResult { navigations++; return fake.navigate(url, timeoutMs) }
        }
        val engine = BrainEngine(renderer, memory, { null }, object : EngineEvents {}, EngineConfig(pacingOverrideMs = 0)) { now }
        val profile = SiteProfile("fake", "Fake", listOf(fake.host), "https://fake.market/", searchUrl = "https://fake.market/search?q={q}")
        val observed = engine.probeLearning(profile)
        assertFalse(site.curriculum.single { it.id == if (live) "constrain_numeric" else "next_page" }.done)
        assertEquals(0, site.lessonOrdinal)
        return observed to navigations
    }

    @Test fun strandedNonDialogFilterPanelReturnsToSafeSearch() {
        val (page, navigations) = probePanel()
        assertEquals(PageType.RESULTS, page?.pageType); assertEquals(1, navigations)
    }
    @Test fun strandedPanelCanReloadItsExistingSearchUrl() {
        val (page, navigations) = probePanel(sameUrl = true)
        assertEquals(PageType.RESULTS, page?.pageType); assertEquals(1, navigations)
    }
    @Test fun coolingPanelDoesNotNavigate() {
        val (page, navigations) = probePanel(cooling = true)
        assertEquals(PageType.FACET_PANEL, page?.pageType); assertEquals(0, navigations)
    }
    @Test fun liveFilterLessonDoesNotNavigateAway() {
        val (page, navigations) = probePanel(live = true)
        assertEquals(PageType.FACET_PANEL, page?.pageType); assertEquals(0, navigations)
    }
    @Test fun dialogAndAuthPanelsDoNotTriggerRecoveryNavigation() {
        assertEquals(0, probePanel(dialog = true).second)
        val (page, navigations) = probePanel(auth = true)
        assertNull(page); assertEquals(0, navigations)
    }
}
