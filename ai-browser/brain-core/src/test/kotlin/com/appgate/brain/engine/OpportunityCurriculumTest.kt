package com.appgate.brain.engine

import com.appgate.brain.json.Json
import com.appgate.brain.model.*
import com.appgate.brain.memory.*
import com.appgate.brain.json.JsonObject
import com.appgate.brain.perception.SpsParser
import com.appgate.brain.profile.SiteProfile
import com.appgate.brain.test.FakeSite
import org.junit.Assert.*
import org.junit.Test

class OpportunityCurriculumTest {
    private val now = 1_800_000_000_000L
    private fun siteOnly(vararg unfinished: String) = SiteModel("fake.market").also { site ->
        Curriculum.ensure(site)
        site.curriculum.filter { it.id !in unfinished }.forEach { it.completedAt = now - 1 }
    }
    private fun profile() = SiteProfile("fake", "Fake", listOf("fake.market"), "https://fake.market/", trainingQueries = listOf("fixture query"))
    private fun results() = SpsParser().parse(FakeSite().apply {
        dialogShown = false
        navigate("https://fake.market/search?q=Ford", 1000)
    }.observe(1000), now).copy(settle = Settle.IDLE)

    private fun observe(site: SiteModel, page: SemanticPageState, at: Long = now) = Curriculum.observe(site, page, at)

    @Test fun strandedResultsProbeTriesAnotherPracticeQueryWithoutCreatingALesson() {
        val fake = FakeSite().apply { dialogShown = false; navigate("https://fake.market/search?q=old", 1000) }
        val memory = Memory(InMemoryStorage()) { now }
        val site = memory.site(fake.host)
        Curriculum.ensure(site)
        site.curriculum.filter { it.id != "next_page" }.forEach { it.completedAt = now - 1 }
        val raw = fake.observe(1000)
        val empty = Json.parseObject(raw).put("elements", com.appgate.brain.json.JsonArray()).put("items", com.appgate.brain.json.JsonArray()).toString()
        var navigations = 0
        val renderer = object : Renderer by fake {
            override fun observe(timeoutMs: Long): String = if (navigations == 0) empty else fake.observe(timeoutMs)
            override fun navigate(url: String, timeoutMs: Long): RendererResult { navigations++; return fake.navigate(url, timeoutMs) }
        }
        val engine = BrainEngine(renderer, memory, { null }, object : EngineEvents {}, config = EngineConfig(pacingOverrideMs = 0)) { now }
        val p = profile().copy(searchUrl = "https://fake.market/search?q={q}", trainingQueries = listOf("fresh", "different"))
        assertNotNull(engine.probeLearning(p))
        assertEquals(1, navigations)
        assertFalse(fake.currentUrl().contains("q=old"))
        assertEquals(0, site.lessonOrdinal)
        assertFalse(site.curriculum.single { it.id == "next_page" }.done)
    }

    @Test fun observedResultsWithoutNextDoNotScheduleAnotherGenericTask() {
        val site = siteOnly("next_page")
        observe(site, results().copy(affordances = emptyList()))
        assertEquals("missing pagination waits for an opportunity", "", Curriculum.nextLesson(site))
        assertFalse(site.curriculum.single { it.id == "next_page" }.done)
    }

    @Test fun detailPracticeDoesNotInventAnEvidenceQuestionOrUnrelatedFilters() {
        val site = siteOnly("open_item")
        val goal = Curriculum.nextGoal(site, profile().copy(categories = setOf("vehicles")), 1)
        assertTrue("navigation practice must not ask for synthetic rare evidence", goal.rare.isEmpty())
        assertTrue("opening one item does not need filtering", goal.constraints.isEmpty())
    }

    @Test fun observationsRoundTripWithoutNamesValuesOrItemKeys() {
        val site = siteOnly("select_facet")
        val page = results().copy(affordances = listOf(Affordance("private-control", Role.FACET, "make", "choice",
            name = "Private Person", value = "Private Selection", choices = listOf("Private Option"))))
        observe(site, page)
        val serialized = site.toJson().toString()
        assertFalse(serialized, serialized.contains("Private") || serialized.contains("private-control"))
        assertTrue("persist canonical facet keys without their values", site.toJson().optStrings("learning_facets").contains("make"))
        val restored = SiteModel.fromJson(Json.parseObject(serialized))
        assertEquals("select_facet", Curriculum.nextLesson(restored))
        assertTrue(restored.curriculum.single { it.id == "search" }.done)
    }

    @Test fun actualAuthAndUnsettledPagesDoNotEraseKnownOpportunities() {
        val site = siteOnly("next_page")
        val page = results().copy(affordances = listOf(Affordance("next", Role.PAGE_NEXT)))
        observe(site, page)
        observe(site, page.copy(pageType = PageType.AUTH_WALL, affordances = emptyList()), now + 1)
        observe(site, page.copy(settle = Settle.BUSY, affordances = emptyList()), now + 2)
        assertEquals("next_page", Curriculum.nextLesson(site))
    }

    @Test fun choiceLessonTargetsTheExposedFacetAndKeepsItsValueTransient() {
        val site = siteOnly("select_facet")
        val page = results().copy(affordances = listOf(Affordance("make-control", Role.FACET, "make", "choice",
            value = "Current Make", choices = listOf("Current Make", "Different Make"))))
        observe(site, page)
        val target = LearningOpportunities.target(page, "select_facet")!!
        assertEquals("select_facet", target.skillId)
        assertEquals(mapOf("key" to "make", "value" to "Different Make"), target.params)
        val goal = Curriculum.nextGoal(site, profile(), 1, page, "select_facet")
        assertEquals(listOf("make"), goal.constraints.map { it.key })
        assertEquals("Different Make", goal.constraints.single().value)
        assertFalse(site.toJson().toString().contains("Different Make"))
    }

    @Test fun failedUnchangedLessonWaitsButNewControlLayoutReenablesIt() {
        val site = siteOnly("next_page")
        val page = results().copy(affordances = listOf(Affordance("next", Role.PAGE_NEXT)))
        observe(site, page)
        val goal = Curriculum.nextGoal(site, profile(), 0, page, "next_page")
        val failed = TaskLedger("failed", goal, site.host, page.url).apply {
            lesson = "next_page"; status = TaskStatus.FAILED; attemptedSkills += "next_page"
        }
        Curriculum.recordAttempt(site, failed, now)
        observe(site, page.copy(title = "Unrelated changed listing title"), now + 1)
        assertEquals("", Curriculum.nextLesson(site, now + 1))
        val restored = SiteModel.fromJson(Json.parseObject(site.toJson().toString()))
        assertEquals("", Curriculum.nextLesson(restored, now + 1))
        observe(restored, page.copy(affordances = listOf(Affordance("new-next", Role.PAGE_NEXT, tag = "button"))), now + 2)
        assertEquals("next_page", Curriculum.nextLesson(restored, now + 2))
    }

    @Test fun absentPaginationGetsMinuteObservationsWithoutGenericTaskChurn() {
        var time = now
        val fake = FakeSite().apply { dialogShown = false; navigate("https://fake.market/search?q=Ford", 1000); page = 2 }
        val raw = fake.observe(1000)
        val page = SpsParser().parse(raw, now)
        val memory = Memory(InMemoryStorage()) { time }
        val site = memory.site(fake.host)
        Curriculum.ensure(site)
        site.curriculum.filter { it.id != "next_page" }.forEach { it.completedAt = now - 1 }
        observe(site, page)
        var observations = 0; var finished = 0
        val renderer = object : Renderer by fake {
            override fun observe(timeoutMs: Long): String { observations++; return raw }
        }
        val events = object : EngineEvents { override fun finished(ledger: TaskLedger, result: TaskResult?) { finished++ } }
        val config = EngineConfig(pacingOverrideMs = 0, plannerCooldownMs = 0, ambiguousRecheckMs = 0, idleSleepMs = 0)
        val engine = BrainEngine(renderer, memory, { null }, events, config) { time }
        val session = LearningSession(engine, memory, events, listOf(profile()), clock = { time })
        repeat(60) { session.run(maxSites = 1); time += 60_000L }
        assertEquals("an absent control must not launch generic learning tasks", 0, finished)
        assertEquals(0, site.lessonOrdinal)
        assertTrue("every minute still observes the site", observations >= 60)
        assertFalse(site.curriculum.single { it.id == "next_page" }.done)
    }

    @Test fun reopeningTheSameFilterDrawerDoesNotResetItsFailureDelay() {
        val site = siteOnly("select_facet")
        val page = results().copy(affordances = listOf(Affordance("filters", Role.FACET_OPEN)))
        observe(site, page)
        observe(site, page.copy(pageType = PageType.FACET_PANEL, affordances = emptyList()), now + 1)
        val failed = TaskLedger("empty-drawer", Curriculum.nextGoal(site, profile(), 0, page, "select_facet"), site.host, page.url).apply {
            lesson = "select_facet"; status = TaskStatus.PARTIAL; attemptedSkills += "open_filters"
        }
        Curriculum.recordAttempt(site, failed, now + 2)
        observe(site, page, now + 3)
        assertEquals("changing only drawer context cannot erase the delay", "", Curriculum.nextLesson(site, now + 3))
    }

    @Test fun numericPracticePreservesTheObservedBoundAndChangesItsCurrentValue() {
        val page = results().copy(affordances = listOf(Affordance("year-upper", Role.FACET, "year_max", "numeric_max", value = "2015")))
        val target = LearningOpportunities.target(page, "constrain_numeric")!!
        assertEquals("year_max", target.params["key"])
        assertEquals(ConstraintOp.LTE, target.constraints.single().op)
        assertNotEquals("a lesson must exercise a different bound", "2015", target.params["value"])
    }

    @Test fun numericChoiceControlUsesAnExposedDifferentValue() {
        val page = results().copy(affordances = listOf(Affordance("price-upper", Role.FACET, "price_max", "choice",
            value = "$5,000", choices = listOf("Any", "$5,000", "$8,000"))))
        val target = LearningOpportunities.target(page, "constrain_numeric")
        assertNotNull("numeric selects are valid numeric practice", target)
        assertEquals("price_max", target!!.params["key"])
        assertEquals("8000", target.params["value"])
    }

    @Test fun emptyUnrecognizedPageWaitsForAnObservedOpportunity() {
        val site = SiteModel("fake.market")
        val page = results().copy(pageType = PageType.UNKNOWN, affordances = emptyList(), collections = emptyList())
        observe(site, page)
        assertEquals("an empty observation must not schedule a guessed lesson", "", Curriculum.nextLesson(site, now))
        assertEquals(0, site.curriculum.count { it.done })
    }
    @Test fun unrelatedFilterChurnDoesNotResetFailedListingLessonCooldown() {
        val site = siteOnly("open_item")
        val page = results()
        observe(site, page)
        val lesson = site.curriculum.single { it.id == "open_item" }
        lesson.retryAt = now + 15 * 60_000L
        val changed = page.copy(affordances = page.affordances + Affordance("unrelated", Role.FACET_OPEN, "transmission", tag = "button"))
        observe(site, changed, now + 1)
        assertEquals(now + 15 * 60_000L, lesson.retryAt)
        assertEquals("", Curriculum.nextLesson(site, now + 1, changed))
    }

    @Test fun unrelatedFilterChurnDoesNotReopenFailedListingTeacherRepair() {
        val site = siteOnly("open_item")
        val page = results()
        val steps = listOf(Step(StepKind.CLICK, Role.RESULT_ITEM, arg = "\$item"))
        val key = FailedStrategies.key(page, "open_item", steps)
        repeat(2) { FailedStrategies.record(site, key, false, now) }
        val changed = page.copy(affordances = page.affordances + Affordance("unrelated", Role.FACET_OPEN, "transmission", tag = "button"))
        assertFalse(FailedStrategies.allowed(site, FailedStrategies.key(changed, "open_item", steps), now + 1))
    }

    @Test fun optionCountChurnDoesNotReopenFailedFilterTeacherRepair() {
        val site = siteOnly("select_facet")
        val control = Affordance("make", Role.FACET, "make", "choice", tag = "select", choices = listOf("Ford", "Toyota"))
        val page = results().copy(affordances = listOf(control))
        val steps = listOf(Step(StepKind.SELECT, Role.FACET, facetKey = "make", arg = "Ford"))
        val key = FailedStrategies.key(page, "select_facet", steps)
        repeat(2) { FailedStrategies.record(site, key, false, now) }
        val changed = page.copy(affordances = listOf(control.copy(choices = control.choices + "Honda")))
        assertFalse(FailedStrategies.allowed(site, FailedStrategies.key(changed, "select_facet", steps), now + 1))
    }

    @Test fun absentDetailLessonCanUseALiveListingPrerequisiteAfterRestart() {
        val site = siteOnly("expand_description")
        val lesson = site.curriculum.single { it.id == "expand_description" }
        lesson.opportunity = "ABSENT"; lesson.observedAt = now - 10
        lesson.observedPage = "DETAIL"; site.learningObservedAt = now - 10
        val restored = SiteModel.fromJson(Json.parseObject(site.toJson().toString()))
        val page = results()
        observe(restored, page)
        assertEquals("expand_description", Curriculum.nextLesson(restored, now, page))
        assertEquals("open_item", LearningOpportunities.target(page, "expand_description")!!.skillId)
        assertFalse(restored.curriculum.single { it.id == "expand_description" }.done)
        restored.curriculum.single { it.id == "expand_description" }.retryAt = now + 60_000
        assertEquals("", Curriculum.nextLesson(restored, now, page))
    }

    @Test fun detailProbeKeepsAnUnfinishedDescriptionLessonOnItsCurrentPage() {
        val fake = FakeSite().apply { dialogShown = false; navigate("https://fake.market/item/1", 1000) }
        val memory = Memory(InMemoryStorage()) { now }
        val site = memory.site(fake.host)
        Curriculum.ensure(site)
        site.curriculum.filter { it.id != "expand_description" }.forEach { it.completedAt = now - 1 }
        var navigations = 0
        val renderer = object : Renderer by fake {
            override fun observe(timeoutMs: Long): String {
                val raw = Json.parseObject(fake.observe(timeoutMs))
                raw.optArray("elements")!!.add(JsonObject().put("id", "expand").put("tag", "button")
                    .put("name", "Read more").put("expanded", false).put("visible", true).put("enabled", true).put("sameSite", true))
                return raw.toString()
            }
            override fun navigate(url: String, timeoutMs: Long): RendererResult { navigations++; return fake.navigate(url, timeoutMs) }
        }
        val engine = BrainEngine(renderer, memory, { null }, object : EngineEvents {}, config = EngineConfig(pacingOverrideMs = 0)) { now }
        val observed = engine.probeLearning(profile().copy(searchUrl = "https://fake.market/search?q={q}", trainingQueries = listOf("Ford")))!!
        assertEquals(PageType.DETAIL, observed.pageType)
        assertEquals(0, navigations)
        assertEquals("expand_description", Curriculum.nextLesson(site, now, observed))
        assertFalse(site.curriculum.single { it.id == "expand_description" }.done)
        site.curriculum.single { it.id == "go_back" }.completedAt = 0
        site.curriculum.single { it.id == "expand_description" }.retryAt = now + 60_000
        val backProbe = engine.probeLearning(profile().copy(searchUrl = "https://fake.market/search?q={q}", trainingQueries = listOf("Ford")))!!
        assertEquals("a cooling description cannot strand back practice on detail", PageType.RESULTS, backProbe.pageType)
        assertEquals("go_back", Curriculum.nextLesson(site, now, backProbe))
    }

    @Test fun backLessonReestablishesResultsAndVerifiesItsReturn() {
        val fake = FakeSite().apply { dialogShown = false; navigate("https://fake.market/item/1", 1000) }
        val memory = Memory(InMemoryStorage()) { now }
        val site = memory.site(fake.host)
        Curriculum.ensure(site)
        site.curriculum.filter { it.id != "go_back" }.forEach { it.completedAt = now - 1 }
        val events = object : EngineEvents {}
        val engine = BrainEngine(fake, memory, { null }, events, config = EngineConfig(pacingOverrideMs = 0, ambiguousRecheckMs = 0)) { now }
        val p = profile().copy(searchUrl = "https://fake.market/search?q={q}", trainingQueries = listOf("Ford"), minActionIntervalMs = 0)
        LearningSession(engine, memory, events, listOf(p), clock = { now }).run(maxSites = 1)
        assertTrue("back verifies from a known results checkpoint", site.curriculum.single { it.id == "go_back" }.done)
    }

}
