package com.appgate.brain.engine

import com.appgate.brain.json.Json
import com.appgate.brain.json.JsonArray
import com.appgate.brain.json.JsonObject
import com.appgate.brain.memory.InMemoryStorage
import com.appgate.brain.memory.Memory
import com.appgate.brain.model.*
import com.appgate.brain.perception.SpsParser
import com.appgate.brain.profile.SiteProfiles
import com.appgate.brain.test.FakeSite
import org.junit.Assert.*
import org.junit.Test

/**
 * Independent regression reproductions written for the Claude review of the 7.2.0 source
 * (September 30 2026). Each test encodes the behaviour the master brief's P0 gates require.
 * Against the unmodified 7.2.0 tree the first four are EXPECTED TO FAIL: a failure here is
 * the reproduction of the defect, not a harness problem. They are meant to turn green when
 * the corresponding correction lands.
 *
 * Nothing here talks to a model or a live site. Both renderers are scripted.
 */
class ClaudeReviewRegressionTest {
    private val config = EngineConfig(pacingOverrideMs = 0L, ambiguousRecheckMs = 0L, idleSleepMs = 0L)

    /** Captures the engine's diagnostic stream the way BrainService would write it to ndjson. */
    private class Capture : EngineEvents {
        val actions = mutableListOf<JsonObject>()
        val progress = mutableListOf<String>()
        override fun diagnostic(host: String, kind: String, data: JsonObject) { if (kind == "action_outcome") actions += data }
        override fun progress(ledger: TaskLedger, reason: String) { progress += ledger.host }
    }

    // ------------------------------------------------------------------ P0-1 source identity

    /**
     * Log evidence: 41 actions in the 7.2.0 window expected cars.ksl.com while observing
     * classifieds.ksl.com. Root cause path: SiteProfiles.kslCars.navigationHosts admits
     * classifieds.ksl.com, BrainEngine.allowedUrl accepts navigationHosts, and probeLearning
     * reuses whatever page is open when allowedUrl accepts it.
     */
    @Test fun carsProbeNeverAdoptsAClassifiedsResultsPageAsItsOpportunity() {
        val classifieds = FakeSite(host = "classifieds.ksl.com").apply { dialogShown = false }
        classifieds.navigate("https://classifieds.ksl.com/search?q=Ford%20Expedition", 1000)
        val engine = BrainEngine(classifieds, Memory(InMemoryStorage()), { null }, Capture(), config)

        val page = engine.probeLearning(SiteProfiles.kslCars)

        // Correct behaviour: navigate to a Cars page first, or report nothing. A Classifieds
        // results page is a permitted navigation destination, not a Cars training source.
        assertTrue("Cars probe adopted a ${page?.host} page as a Cars opportunity",
            page == null || page.host.removePrefix("www.") == "cars.ksl.com")
    }

    /**
     * The same defect one layer up: LearningSession builds the Cars ledger from the probe
     * page, so a Cars lesson runs, verifies and earns curriculum credit on Classifieds.
     */
    @Test fun carsLessonEarnsNoVerifiedCreditWhileObservingClassifieds() {
        val classifieds = FakeSite(host = "classifieds.ksl.com").apply { dialogShown = false }
        classifieds.navigate("https://classifieds.ksl.com/search?q=Ford%20Expedition", 1000)
        val memory = Memory(InMemoryStorage())
        val events = Capture()
        val engine = BrainEngine(classifieds, memory, { null }, events, config)
        val cars = memory.site("cars.ksl.com")
        Curriculum.ensure(cars)

        val goal = Curriculum.nextGoal(cars, SiteProfiles.kslCars, 0, null, "open_item")
        // Exactly what LearningSession.run does with the probe page (Sessions.kt lines 159-163).
        val ledger = TaskLedger("learn-cars", goal, "cars.ksl.com", classifieds.url).apply {
            lesson = "open_item"; lastCheckpointUrl = classifieds.url; resultsUrl = classifieds.url
        }
        engine.runTask(ledger, EngineMode.TRAIN)

        val foreignVerified = events.actions.filter { a ->
            a.optString("code") == "VERIFIED" &&
                (a.optObject("before")?.optString("host") ?: "").removePrefix("www.") == "classifieds.ksl.com" &&
                a.optString("expected_host").removePrefix("www.") == "cars.ksl.com"
        }
        assertTrue("${foreignVerified.size} Cars action(s) were VERIFIED on a Classifieds page", foreignVerified.isEmpty())
        assertFalse("open_item was marked verified for cars.ksl.com from Classifieds evidence",
            memory.site("cars.ksl.com").curriculum.first { it.id == "open_item" }.done)
        assertTrue("verified_progress was credited to cars.ksl.com while on Classifieds", events.progress.none { it == "cars.ksl.com" })
    }

    // ------------------------------------------------------------------ P0-2 filter drawer treated as a dialog

    /**
     * A scripted marketplace whose Filters button opens a full-height drawer. The drawer holds
     * accordion buttons (Make, Model, Price), one Show results button and one Close button —
     * the shape of a mobile filter sheet. RoleClassifier tags the accordions FACET_OPEN, so
     * SpsParser.finalPageType counts fewer than two in-dialog FACET/FACET_APPLY controls and
     * keeps the page RESULTS with dialogOpen = true. LearningOpportunities.target then returns
     * dismiss_dialog before the select_facet lesson can act (LearningOpportunities.kt line 31).
     */
    private class DrawerSite : Renderer {
        val host = "drawer.market"
        var url = "https://$host/search?q=Ford"
        var drawerOpen = false
        var makeExpanded = false
        var make: String? = null
        val log = mutableListOf<String>()

        override fun observe(timeoutMs: Long): String { log += "observe drawer=$drawerOpen make=$make"; return render().toString() }
        override fun navigate(url: String, timeoutMs: Long): RendererResult { log += "navigate $url"; this.url = url; drawerOpen = false; return RendererResult(true) }
        override fun back(timeoutMs: Long): RendererResult { log += "back"; return RendererResult(true) }
        override fun act(command: JsonObject, timeoutMs: Long): RendererResult {
            val id = command.optString("id")
            val name = elements().firstOrNull { it.optString("id") == id }?.optString("name").orEmpty()
            log += "act ${command.optString("cmd")} '$name'"
            when {
                name == "Filters" -> drawerOpen = !drawerOpen
                name == "Close" || command.optString("cmd") == "dismiss" -> drawerOpen = false
                name == "Make" && drawerOpen -> makeExpanded = !makeExpanded
                name == "Ford" && makeExpanded -> make = "Ford"
                name == "Show results" && drawerOpen -> { drawerOpen = false; url = "https://$host/search?q=Ford" + (make?.let { "&make=$it" } ?: "") }
                command.optString("cmd") == "scroll" -> {}
                else -> return RendererResult(false, "nothing happened for '$name'")
            }
            return RendererResult(true, command.optString("cmd"))
        }
        override fun waitSettle(timeoutMs: Long): RendererResult = RendererResult(true, "IDLE")
        override fun currentUrl(): String = url
        override fun setNetworkMode(mode: String, allowlist: List<String>, commitEndpoints: List<String>): RendererResult = RendererResult(true)
        override fun recover(): RendererResult = RendererResult(true)
        override fun isAlive(): Boolean = true

        private fun elements(): List<JsonObject> = render().optArray("elements")!!.objects()

        private fun render(): JsonObject {
            val elements = JsonArray(); val items = JsonArray(); var i = 0
            fun el(tag: String, name: String, extra: Map<String, Any?> = emptyMap()): JsonObject {
                val o = JsonObject().put("id", "a${i++}").put("tag", tag).put("name", name).put("visible", true).put("enabled", true)
                    .put("bbox", JsonArray().add(0).add(i * 40).add(300).add(30)).put("region", extra["region"] as? String)
                extra.forEach { (k, v) -> if (k != "region") o.put(k, Json.wrap(v)) }
                return o
            }
            val regions = JsonArray()
                .add(JsonObject().put("id", "r0").put("role", "HEADER"))
                .add(JsonObject().put("id", "r2").put("role", "MAIN"))
                .add(JsonObject().put("id", "r3").put("role", "DIALOG"))
            elements.add(el("input", "Search", mapOf("type" to "search", "region" to "r0", "value" to "Ford", "formHasSearch" to true, "inForm" to true)))
            elements.add(el("button", "Filters", mapOf("region" to "r2", "expanded" to drawerOpen)))
            val listings = listOf("2008 Ford Expedition" to 7500, "2011 Ford Expedition XLT" to 9900, "2012 Ford Expedition King Ranch" to 7950)
            listings.forEachIndexed { n, (title, price) ->
                val key = "k$n"
                elements.add(el("a", title, mapOf("region" to "r2", "href" to "https://$host/item/$n", "sameSite" to true, "inCard" to true, "cardHasPrice" to true, "listSize" to 3, "itemKey" to key)))
                items.add(JsonObject().put("key", key).put("title", title).put("price", "$$price").put("href", "https://$host/item/$n").put("text", "$title $$price").put("aff", "a${i - 1}"))
            }
            val signals = JsonObject().put("textLength", 2400).put("hasMain", true).put("resultsHint", true).put("priceCount", 3)
            if (drawerOpen) {
                signals.put("dialog", true).put("dialogCoverage", 0.9)
                elements.add(el("button", "Close", mapOf("region" to "r3", "inDialog" to true)))
                elements.add(el("button", "Make", mapOf("region" to "r3", "inDialog" to true, "expanded" to makeExpanded)))
                if (makeExpanded) listOf("Ford", "Toyota").forEach { opt ->
                    elements.add(el("input", opt, mapOf("type" to "radio", "region" to "r3", "inDialog" to true, "near" to "Make", "selected" to (make == opt))))
                }
                elements.add(el("button", "Model", mapOf("region" to "r3", "inDialog" to true, "expanded" to false)))
                elements.add(el("button", "Price", mapOf("region" to "r3", "inDialog" to true, "expanded" to false)))
                elements.add(el("button", "Show results", mapOf("region" to "r3", "inDialog" to true)))
            }
            return JsonObject().put("v", 3).put("url", url).put("host", host).put("title", "Ford - results").put("readyState", "complete")
                .put("viewport", JsonObject().put("w", 400).put("h", 800).put("scrollY", 0).put("scrollH", 800))
                .put("settle", JsonObject().put("state", "IDLE")).put("signals", signals).put("regions", regions)
                .put("elements", elements).put("items", items).put("detailText", "").put("scripts", JsonArray())
        }
    }

    @Test fun selectFacetLessonDoesNotDismissTheDrawerItJustOpened() {
        val site = DrawerSite()
        val memory = Memory(InMemoryStorage())
        val events = Capture()
        val engine = BrainEngine(site, memory, { null }, events, config)
        val model = memory.site(site.host)
        Curriculum.ensure(model)
        model.curriculum.filter { it.id != "select_facet" }.forEach { it.completedAt = 1L }
        val page = engine.probeLearning(com.appgate.brain.profile.SiteProfile("drawer", "Drawer", listOf(site.host), "https://${site.host}/", minActionIntervalMs = 0L))
        assertNotNull(page)
        val goal = Curriculum.nextGoal(model, SiteProfiles.generic(site.host), 0, page, "select_facet")
        val ledger = TaskLedger("learn-drawer", goal, site.host, site.url).apply { lesson = "select_facet"; lastCheckpointUrl = site.url; resultsUrl = site.url }

        engine.runTask(ledger, EngineMode.TRAIN)

        val dismissedOwnDrawer = events.actions.any { a ->
            a.optString("capability") == "dismiss_dialog" && a.optString("lesson") == "select_facet"
        }
        assertFalse("the select_facet lesson dismissed the filter drawer it needs (open/close loop): ${site.log}", dismissedOwnDrawer)
        assertFalse("open_filters + dismiss_dialog were credited as verified skills for a filter lesson that applied nothing",
            "dismiss_dialog" in ledger.successfulSkills && "open_filters" in ledger.successfulSkills && "select_facet" !in ledger.successfulSkills)
    }

    /**
     * The positive form of the drawer test: with the sheet recognised as a filter panel, the
     * lesson expands the Make accordion, selects an option, applies, and verifies select_facet.
     * Expected to fail on 7.2.0 (the loop above) and pass once the correction lands.
     */
    @Test fun selectFacetLessonCompletesThroughAnAccordionDrawer() {
        val site = DrawerSite()
        val memory = Memory(InMemoryStorage())
        val engine = BrainEngine(site, memory, { null }, Capture(), config)
        val model = memory.site(site.host)
        Curriculum.ensure(model)
        model.curriculum.filter { it.id != "select_facet" }.forEach { it.completedAt = 1L }
        val page = engine.probeLearning(com.appgate.brain.profile.SiteProfile("drawer", "Drawer", listOf(site.host), "https://${site.host}/", minActionIntervalMs = 0L))
        val goal = Curriculum.nextGoal(model, SiteProfiles.generic(site.host), 0, page, "select_facet")
        val ledger = TaskLedger("learn-drawer-2", goal, site.host, site.url).apply { lesson = "select_facet"; lastCheckpointUrl = site.url; resultsUrl = site.url }
        engine.runTask(ledger, EngineMode.TRAIN)
        assertTrue("select_facet did not verify through the accordion drawer: ${site.log}", "select_facet" in ledger.successfulSkills)
        assertEquals("the applied filter must reach the results URL", "Ford", site.make)
        assertTrue("curriculum credit must follow verification", memory.site(site.host).curriculum.first { it.id == "select_facet" }.done)
    }

    @Test fun narrowAccordionDrawerRemainsAFilterWorkspace() {
        val site = DrawerSite()
        site.drawerOpen = true
        val raw = Json.parseObject(site.observe(1000))
        raw.optObject("signals")!!.put("dialogCoverage", 0.2)
        val page = SpsParser().parse(raw.toString())
        assertEquals(PageType.FACET_PANEL, page.pageType)
        assertEquals("open_facet", LearningOpportunities.target(page, "select_facet")?.skillId)
    }

    @Test fun facetOpeningRequiresTheRequestedFacetAndSurvivesSerialization() {
        val site = DrawerSite().apply { drawerOpen = true }
        val before = SpsParser().parse(site.observe(1000))
        val numeric = Affordance("price", Role.FACET, facetKey = "price_max", tag = "input")
        val withNumeric = before.copy(affordances = before.affordances + numeric)
        val expected = Postcondition.RoleAppeared(Role.FACET, "make")
        assertEquals(expected, Postcondition.fromJson(expected.toJson()))
        val action = Action(ActionKind.CLICK, expect = listOf(expected))
        val unrelated = withNumeric.copy(affordances = withNumeric.affordances + numeric.copy(id = "year", facetKey = "year_min"))
        assertEquals(VerifyStatus.FAILED, com.appgate.brain.verify.Verifier.verify(action, withNumeric, unrelated).status)
        val revealed = withNumeric.copy(affordances = withNumeric.affordances + numeric.copy(id = "make", facetKey = "make"))
        assertEquals(VerifyStatus.VERIFIED, com.appgate.brain.verify.Verifier.verify(action, withNumeric, revealed).status)
    }

    // ------------------------------------------------------------------ diagnostics truth: an already-passing invariant

    /** Sanity: on a single-host site the same lesson does earn credit, so the tests above fail for the right reason. */
    @Test fun sameHostOpenItemLessonStillEarnsCredit() {
        val fake = FakeSite().apply { dialogShown = false }
        fake.navigate("https://${fake.host}/search?q=Ford%20Expedition", 1000)
        val memory = Memory(InMemoryStorage())
        val events = Capture()
        val engine = BrainEngine(fake, memory, { null }, events, config)
        val model = memory.site(fake.host)
        Curriculum.ensure(model)
        val goal = Curriculum.nextGoal(model, SiteProfiles.generic(fake.host), 0, null, "open_item")
        val ledger = TaskLedger("learn-same", goal, fake.host, fake.url).apply { lesson = "open_item"; lastCheckpointUrl = fake.url; resultsUrl = fake.url }
        engine.runTask(ledger, EngineMode.TRAIN)
        assertTrue("open_item should verify on its own host", "open_item" in ledger.successfulSkills)
        assertTrue(events.progress.all { it == fake.host })
    }
}
