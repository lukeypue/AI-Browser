package com.appgate.brain.engine

import com.appgate.brain.json.*
import com.appgate.brain.memory.*
import com.appgate.brain.model.*
import com.appgate.brain.perception.SpsParser
import com.appgate.brain.planner.*
import com.appgate.brain.test.Fixtures
import com.appgate.brain.test.FakeSite
import org.junit.Assert.*
import org.junit.Test

class LiveTargetRegressionTest {
    private fun page() = SpsParser().parse(Fixtures.observation("results_page"))

    @Test fun locationSpanInsidePricedCardIsNotAListingTarget() {
        val raw = Fixtures.observation("results_page")
        raw.optArray("elements")!!.add(JsonObject().put("id", "location-span").put("tag", "span").put("role", "link")
            .put("name", "Example City, UT").put("visible", true).put("enabled", true).put("sameSite", true)
            .put("inCard", true).put("cardHasPrice", true).put("listSize", 5))
        val parsed = SpsParser().parse(raw)
        assertNotEquals(Role.RESULT_ITEM, parsed.affordances.single { it.id == "location-span" }.role)
        assertTrue(parsed.byRole(Role.RESULT_ITEM).isNotEmpty())
    }

    @Test fun resultsOpenTheNamedAccordionInsteadOfLocation() {
        val p = page(); val prototype = p.affordances.first()
        fun opener(id: String, key: String?) = prototype.copy(id = id, role = Role.FACET_OPEN,
            facetKey = key, name = key.orEmpty(), itemKey = null, regionRole = RegionRole.FILTERS)
        val controls = p.copy(affordances = listOf(opener("location", "location"), opener("price", "price"), opener("condition", "condition")))
        assertEquals(LearningTarget("open_facet", mapOf("key" to "price")), LearningOpportunities.target(controls, "constrain_numeric"))
        assertEquals(LearningTarget("open_facet", mapOf("key" to "condition")), LearningOpportunities.target(controls, "select_facet"))
        assertNull(LearningOpportunities.target(controls.copy(affordances = listOf(opener("location", "location"))), "select_facet"))
    }

    @Test fun facetAccordionAndComboboxExposeTheirRealChoiceRoles() {
        val raw = Fixtures.observation("results_page")
        raw.optArray("elements")!!.add(JsonObject().put("id", "condition-accordion").put("tag", "button")
            .put("name", "Condition Expand").put("expanded", false).put("visible", true).put("enabled", true))
        raw.optArray("elements")!!.add(JsonObject().put("id", "condition-combo").put("tag", "input").put("type", "text")
            .put("role", "combobox").put("name", "Condition").put("expanded", false).put("visible", true).put("enabled", true))
        raw.optArray("elements")!!.add(JsonObject().put("id", "condition-option").put("tag", "li").put("role", "option")
            .put("name", "New 120").put("near", "Condition").put("visible", true).put("enabled", true))
        val p = SpsParser().parse(raw)
        assertEquals(Role.FACET_OPEN, p.affordances.single { it.id == "condition-accordion" }.role)
        assertEquals(Role.FACET_OPEN, p.affordances.single { it.id == "condition-combo" }.role)
        val choice = p.affordances.single { it.id == "condition-option" }
        assertEquals(Role.FACET, choice.role)
        assertEquals("condition", choice.facetKey)
        assertEquals("select_facet", LearningOpportunities.target(p.copy(affordances = listOf(choice)), "select_facet")?.skillId)
    }

    @Test fun genericDrawerGroundingNeverSubstitutesLocation() {
        val p = page(); val base = p.affordances.first()
        val location = base.copy(id = "location", role = Role.FACET_OPEN, facetKey = "location", itemKey = null)
        assertTrue(StepGrounder(null).ground(Step(StepKind.CLICK, Role.FACET_OPEN), emptyMap(),
            p.copy(affordances = listOf(location)), emptySet(), strict = true) is GroundingOutcome.Missing)
    }

    @Test fun revealingASecondNamedOpenerIsVerifiedButAnUnchangedAccordionIsNot() {
        val p = page(); val base = p.affordances.first().copy(role = Role.FACET_OPEN, facetKey = "condition")
        val before = p.copy(affordances = listOf(base))
        val after = before.copy(affordances = listOf(base, base.copy(id = "new-combo")))
        val condition = Postcondition.RoleAppeared(Role.FACET_OPEN, "condition")
        val action = Action(ActionKind.CLICK)
        assertTrue(com.appgate.brain.verify.Verifier.holds(condition, action, before, after, emptySet(), mutableListOf()))
        assertFalse(com.appgate.brain.verify.Verifier.holds(condition, action, before, before, emptySet(), mutableListOf()))
    }

    @Test fun namedFacetGroundingUsesClosedComboboxInsteadOfAlreadyExpandedHeader() {
        val p = page(); val base = p.affordances.first().copy(role = Role.FACET_OPEN, facetKey = "condition", itemKey = null)
        val expanded = base.copy(id = "header", features = FeatureVec(mapOf("expanded" to 1.0)))
        val combo = base.copy(id = "combo", features = FeatureVec.EMPTY)
        val outcome = StepGrounder(null).ground(Step(StepKind.CLICK, Role.FACET_OPEN, facetKey = "condition"),
            emptyMap(), p.copy(affordances = listOf(expanded, combo)), emptySet(), strict = true) as GroundingOutcome.Ready
        assertEquals("combo", outcome.grounded.action.target?.affordanceId)
    }

    @Test fun unfinishedLessonCanReachResultsDespiteStaleAbsentOpportunity() {
        val p = page().copy(pageType = PageType.SEARCH)
        val site = SiteModel(p.host); Curriculum.ensure(site)
        site.learningObservedAt = 1L
        site.curriculum.forEach { it.completedAt = 1L; it.opportunity = "ABSENT" }
        val pending = site.curriculum.single { it.id == "select_facet" }.apply { completedAt = 0L }
        assertEquals("select_facet", Curriculum.nextLesson(site, 100L, p))
        pending.retryAt = 200L
        assertEquals("", Curriculum.nextLesson(site, 100L, p))
        assertEquals("ABSENT", pending.opportunity)
    }

    @Test fun teacherReceivesExactRepairCapabilityAndCannotExecuteUnrelatedSearch() {
        val fake = FakeSite().apply { dialogShown = false; navigate("https://fake.market/search?q=Ford", 1000) }
        var captured = JsonObject()
        val teacher = Planner(object : PlannerClient {
            override val describe = "fixture"
            override fun complete(instructions: String, input: String, schemaName: String, schema: JsonObject, maxOutputTokens: Int): String {
                captured = Json.parseObject(input)
                return """{"steps":[{"kind":"TYPE","role":"SEARCH_BOX","arg":"nonsense","submit":true,"expect":["RESULTS_CHANGED"]}],"confidence":0.99}"""
            }
        })
        val renderer = object : Renderer by fake {
            override fun act(command: JsonObject, timeoutMs: Long): RendererResult {
                if (command.optString("cmd") == "click") return RendererResult(false, "rejected listing click")
                return fake.act(command, timeoutMs)
            }
        }
        val goal = Goal("lesson", GoalIntent.LEARN_SITE, "practice opening", "Ford", emptyList(), budget = Budget(actions = 16, llmCalls = 1, wallMs = 10000))
        val out = BrainEngine(renderer, Memory(InMemoryStorage()) { 1000L }, { teacher }, object : EngineEvents {},
            EngineConfig(pacingOverrideMs = 0, plannerCooldownMs = 0, ambiguousRecheckMs = 0, idleSleepMs = 0)) { 1000L }
            .runTask(TaskLedger("lesson", goal, fake.host, fake.url).apply { lesson = "open_item" })
        assertEquals("open_item", captured.optObject("task")?.optString("requested_capability"))
        assertFalse(out.steps.any { it.source == "planner" && it.action.contains("SEARCH_BOX") })
        assertFalse("open_item" in out.successfulSkills)
    }

    @Test fun concreteQueryTeacherRepairExecutesAndVerifies() {
        val fake = FakeSite().apply { dialogShown = false }
        var teacherCalled = false
        val renderer = object : Renderer by fake {
            override fun act(command: JsonObject, timeoutMs: Long) = if (!teacherCalled) RendererResult(false, "fixture rejection") else fake.act(command, timeoutMs)
        }
        val teacher = Planner(object : PlannerClient {
            override val describe = "fixture"
            override fun complete(instructions: String, input: String, schemaName: String, schema: JsonObject, maxOutputTokens: Int): String {
                teacherCalled = true
                return """{"steps":[{"kind":"TYPE","role":"SEARCH_BOX","arg":"Ford","submit":true,"expect":["PAGE_IS_RESULTS"]}],"confidence":0.99}"""
            }
        })
        val goal = Goal("search", GoalIntent.LEARN_SITE, "practice", "Ford", emptyList(), budget = Budget(actions = 20, llmCalls = 1, wallMs = 10000))
        val out = BrainEngine(renderer, Memory(InMemoryStorage()) { 1000L }, { teacher }, object : EngineEvents {},
            EngineConfig(pacingOverrideMs = 0, plannerCooldownMs = 0, ambiguousRecheckMs = 0, idleSleepMs = 0)) { 1000L }
            .runTask(TaskLedger("search", goal, fake.host, fake.url).apply { lesson = "search" })
        assertTrue(teacherCalled)
        assertTrue(out.steps.any { it.source == "planner" && it.status == VerifyStatus.VERIFIED })
        assertEquals(TaskStatus.DONE, out.status)
    }

    @Test fun namedOpenerTeacherRepairCanContinueToVerifiedFacetSelection() {
        val fake = FakeSite().apply { dialogShown = false; navigate("https://fake.market/search?q=Ford", 1000) }
        var teacherCalled = false; var opened = false; var selected = false
        val renderer = object : Renderer by fake {
            override fun observe(timeoutMs: Long): String {
                val raw = Json.parseObject(fake.observe(timeoutMs))
                val elements = raw.optArray("elements")!!.objects().filter { it.optString("tag") != "select" }.toMutableList()
                fun control(id: String, tag: String, name: String) = JsonObject().put("id", id).put("tag", tag).put("name", name)
                    .put("visible", true).put("enabled", true).put("sameSite", true).put("region", "r1")
                elements += control("condition-header", "button", "Condition Expand").put("expanded", opened)
                if (opened) elements += control("condition-option", "li", "New").put("role", "option").put("near", "Condition").put("selected", selected)
                raw.put("elements", JsonArray(elements)); return raw.toString()
            }
            override fun act(command: JsonObject, timeoutMs: Long): RendererResult = when (command.optString("id")) {
                "condition-header" -> if (!teacherCalled) RendererResult(false, "fixture rejection") else { opened = true; RendererResult(true) }
                "condition-option" -> { selected = true; fake.url += "&condition=New"; RendererResult(true) }
                else -> fake.act(command, timeoutMs)
            }
        }
        val teacher = Planner(object : PlannerClient {
            override val describe = "fixture"
            override fun complete(instructions: String, input: String, schemaName: String, schema: JsonObject, maxOutputTokens: Int): String {
                assertEquals("open_facet", Json.parseObject(input).optObject("task")?.optString("requested_capability"))
                teacherCalled = true
                return """{"steps":[{"kind":"CLICK","role":"FACET_OPEN","facet_key":"condition","expect":["FACETS_APPEARED"]}],"confidence":0.99}"""
            }
        })
        val goal = Goal("facet", GoalIntent.LEARN_SITE, "practice", "Ford", emptyList(), budget = Budget(actions = 20, llmCalls = 1, wallMs = 10000))
        val out = BrainEngine(renderer, Memory(InMemoryStorage()) { 1000L }, { teacher }, object : EngineEvents {},
            EngineConfig(pacingOverrideMs = 0, plannerCooldownMs = 0, ambiguousRecheckMs = 0, idleSleepMs = 0)) { 1000L }
            .runTask(TaskLedger("facet", goal, fake.host, fake.url).apply { lesson = "select_facet" })
        assertTrue(teacherCalled)
        assertEquals(out.notes.joinToString(), TaskStatus.DONE, out.status)
        assertTrue("select_facet" in out.successfulSkills)
        assertTrue(out.steps.any { it.source == "planner" && it.status == VerifyStatus.VERIFIED })
    }

    @Test fun closingConditionOptionsRetainsTheAppliedPathConstraint() {
        val raw = Fixtures.observation("results_page")
        raw.put("url", "https://classifieds.ksl.com/search/newUsed/New")
        raw.optArray("elements")!!.add(JsonObject().put("id", "condition-closed").put("tag", "input").put("type", "text")
            .put("role", "combobox").put("name", "Condition").put("expanded", false).put("value", "")
            .put("visible", true).put("enabled", true))
        assertEquals("New", SpsParser().parse(raw).constraintsActive["condition"])
    }
}
