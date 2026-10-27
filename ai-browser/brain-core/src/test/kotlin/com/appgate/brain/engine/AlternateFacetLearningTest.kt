package com.appgate.brain.engine

import com.appgate.brain.json.*
import com.appgate.brain.memory.*
import com.appgate.brain.model.*
import com.appgate.brain.perception.SpsParser
import com.appgate.brain.profile.SiteProfile
import com.appgate.brain.test.FakeSite
import org.junit.Assert.*
import org.junit.Test

class AlternateFacetLearningTest {
    private val now = 1_800_000_000_000L
    private val config = EngineConfig(pacingOverrideMs = 0, plannerCooldownMs = 0, ambiguousRecheckMs = 0, idleSleepMs = 0)
    private class FacetSite(private val rejectSelection: Boolean = false) : Renderer {
        val fake = FakeSite().apply { dialogShown = false; navigate("https://fake.market/search?q=Ford", 1000) }
        var opened = false
        var selected = false
        val clicked = mutableListOf<String>()
        override fun observe(timeoutMs: Long): String {
            val raw = Json.parseObject(fake.observe(timeoutMs))
            val elements = raw.optArray("elements")!!.objects().filter { it.optString("tag") != "select" }.toMutableList()
            fun control(id: String, tag: String, name: String) = JsonObject().put("id", id).put("tag", tag).put("name", name)
                .put("visible", true).put("enabled", true).put("sameSite", true).put("region", "r1")
            elements += control("condition-header", "button", "Condition Expand").put("expanded", false)
            elements += control("make-header", "button", "Make Expand").put("expanded", opened)
            if (opened) elements += control("make-select", "select", "Make").put("value", if (selected) "Ford" else "Any")
                .put("choices", JsonArray().add("Any").add("Ford"))
            raw.put("elements", JsonArray(elements)); return raw.toString()
        }
        override fun act(command: JsonObject, timeoutMs: Long): RendererResult = when (command.optString("id")) {
            "condition-header" -> { clicked += "condition"; RendererResult(false, "synthetic held control") }
            "make-header" -> { clicked += "make"; opened = true; RendererResult(true) }
            "make-select" -> if (rejectSelection) RendererResult(false, "synthetic rejected selection")
                else { selected = true; fake.url += "&make=Ford"; RendererResult(true) }
            else -> fake.act(command, timeoutMs)
        }
        override fun navigate(url: String, timeoutMs: Long) = fake.navigate(url, timeoutMs)
        override fun back(timeoutMs: Long) = fake.back(timeoutMs)
        override fun waitSettle(timeoutMs: Long) = fake.waitSettle(timeoutMs)
        override fun currentUrl() = fake.currentUrl()
        override fun setNetworkMode(mode: String, allowlist: List<String>, commitEndpoints: List<String>) = fake.setNetworkMode(mode, allowlist, commitEndpoints)
        override fun recover() = fake.recover()
        override fun isAlive() = true
    }
    private fun held(memory: Memory, page: SemanticPageState): String {
        val target = LearningOpportunities.target(page, "select_facet")!!
        assertEquals("condition", target.params["key"])
        val key = FailedStrategies.key(page, "open_facet", memory.skills.get("open_facet")!!.body, target.params)
        FailedStrategies.record(memory.site(page.host), key, false, now); memory.saveSite(memory.site(page.host))
        return key
    }
    @Test fun retainedFirstFacetHoldDoesNotHideAnotherNamedOpener() {
        val renderer = FacetSite(); val page = SpsParser().parse(renderer.observe(1000), now)
        val storage = InMemoryStorage(); val key = held(Memory(storage) { now }, page)
        val memory = Memory(storage) { now }
        val engine = BrainEngine(renderer, memory, { null }, object : EngineEvents {}, config) { now }
        val profile = SiteProfile("fake", "Fake", listOf(page.host), "https://fake.market/")
        assertTrue("another named choice workspace remains eligible", engine.learningLessonAvailable(profile, page, "select_facet", allowTeacher = false))
        assertFalse(FailedStrategies.allowed(memory.site(page.host), key, now))
    }
    @Test fun actualLearningUsesSecondOpenerAndVerifiesItsOwnFacet() {
        val renderer = FacetSite(); val page = SpsParser().parse(renderer.observe(1000), now)
        val storage = InMemoryStorage(); val key = held(Memory(storage) { now }, page)
        val memory = Memory(storage) { now }
        val goal = Goal("alternate-facet", GoalIntent.LEARN_SITE, "synthetic facet lesson", "Ford", emptyList(), budget = Budget(actions = 8, llmCalls = 0, wallMs = 10000))
        val ledger = TaskLedger(goal.id, goal, page.host, page.url).apply { lesson = "select_facet"; lastCheckpointUrl = page.url }
        val out = BrainEngine(renderer, memory, { null }, object : EngineEvents {}, config) { now }.runTask(ledger, EngineMode.TRAIN)
        assertEquals(out.notes.toString(), TaskStatus.DONE, out.status)
        assertEquals(listOf("make"), renderer.clicked)
        assertTrue(renderer.selected)
        assertTrue("select_facet" in out.successfulSkills)
        assertEquals(0, out.llmCalls)
        assertFalse(FailedStrategies.allowed(memory.site(page.host), key, now))
    }
    @Test fun numericAlternativesStayInTheirFamilyAndAreBoundedAfterDeduplication() {
        val renderer = FacetSite(); val page = SpsParser().parse(renderer.observe(1000), now)
        val prototype = page.affordances.first { it.role == Role.FACET_OPEN }
        val keys = listOf("price", "mileage", "year", "distance", "bedrooms", "bathrooms").flatMap { listOf(it+"_min", it+"_max") }
        val safe = keys.mapIndexed { i,key -> prototype.copy(id="numeric-$i", facetKey=key, name=key, features=FeatureVec.EMPTY) }
        val choice = prototype.copy(id="choice", facetKey="condition", features=FeatureVec.EMPTY)
        val unsafe = listOf(prototype.copy(id="hidden", facetKey="price", visible=false),
            prototype.copy(id="offsite", facetKey="mileage", sameSite=false),
            prototype.copy(id="commit", facetKey="year", effect=EffectClass.COMMIT_EXTERNAL))
        val targets = LearningOpportunities.targets(page.copy(affordances=unsafe+choice+List(12) { safe.first().copy(id="duplicate-$it") }+safe), "constrain_numeric")
        assertEquals(keys.take(8), targets.map { it.params["key"] })
        assertTrue(targets.all { it.skillId == "open_facet" && it.constraints.isEmpty() })
    }
    @Test fun choiceAlternativesExcludeNumericUnsafeAndDuplicateKeys() {
        val renderer = FacetSite(); val page = SpsParser().parse(renderer.observe(1000), now)
        val prototype = page.affordances.first { it.role == Role.FACET_OPEN }
        fun opener(id:String,key:String)=prototype.copy(id=id,facetKey=key,features=FeatureVec.EMPTY)
        val p=page.copy(affordances=listOf(opener("price","price"),opener("condition","condition"),
            opener("condition-alias","condition"),opener("make-hidden","make").copy(visible=false),opener("make","make")))
        assertEquals(listOf("condition","make"),LearningOpportunities.targets(p,"select_facet").map { it.params["key"] })
        for (unsafe in listOf(p.copy(authWall=true),p.copy(challenge=true),p.copy(settle=Settle.BUSY)))
            assertTrue(LearningOpportunities.targets(unsafe,"select_facet").isEmpty())
    }
    @Test fun allHeldOpenersRemainUnavailableWithoutTeacherAndKeepEveryHold() {
        val renderer=FacetSite();val page=SpsParser().parse(renderer.observe(1000),now)
        val storage=InMemoryStorage();val memory=Memory(storage) { now };val site=memory.site(page.host)
        val skill=memory.skills.get("open_facet")!!
        for(key in listOf("condition","make"))
            FailedStrategies.record(site,FailedStrategies.key(page,"open_facet",skill.body,mapOf("key" to key)),false,now)
        memory.saveSite(site)
        val restored=Memory(storage) { now };val before=restored.site(page.host).strategyFailures.toMap()
        val profile=SiteProfile("fake","Fake",listOf(page.host),"https://fake.market/")
        assertFalse(BrainEngine(renderer,restored,{ null },object:EngineEvents {},config) { now }
            .learningLessonAvailable(profile,page,"select_facet",allowTeacher=false))
        assertEquals(before,restored.site(page.host).strategyFailures)
        assertTrue(renderer.clicked.isEmpty())
        assertTrue(restored.site(page.host).curriculum.none { it.done })
    }
    @Test fun aSuccessfulAlternativeOpenerNeverAwardsAnUnverifiedParentLesson() {
        val renderer=FacetSite(rejectSelection=true);val page=SpsParser().parse(renderer.observe(1000),now)
        val storage=InMemoryStorage();val key=held(Memory(storage) { now },page)
        val memory=Memory(storage) { now }
        val goal=Goal("rejected-facet",GoalIntent.LEARN_SITE,"synthetic rejected choice","Ford",emptyList(),budget=Budget(actions=8,llmCalls=0,wallMs=10000))
        val ledger=TaskLedger(goal.id,goal,page.host,page.url).apply { lesson="select_facet";lastCheckpointUrl=page.url }
        val out=BrainEngine(renderer,memory,{ null },object:EngineEvents {},config) { now }.runTask(ledger,EngineMode.TRAIN)
        assertEquals(listOf("make"),renderer.clicked)
        assertTrue("open_facet" in out.successfulSkills)
        assertFalse("select_facet" in out.successfulSkills)
        assertNotEquals(TaskStatus.DONE,out.status)
        assertEquals(0,out.llmCalls)
        val restored=Memory(storage) { now }
        assertFalse(restored.site(page.host).curriculum.single { it.id=="select_facet" }.done)
        assertFalse(FailedStrategies.allowed(restored.site(page.host),key,now))
        assertTrue("new selection failure must not replace the original opener failure",restored.site(page.host).strategyFailures.size>1)
    }
    @Test fun anUnavailableFamilyNeverBorrowsAnUnrelatedOpener() {
        val page=SpsParser().parse(FacetSite().observe(1000),now)
        val prototype=page.affordances.first { it.role==Role.FACET_OPEN }
        fun only(key:String)=page.copy(affordances=listOf(prototype.copy(facetKey=key,features=FeatureVec.EMPTY)))
        assertTrue(LearningOpportunities.targets(only("condition"),"constrain_numeric").isEmpty())
        assertTrue(LearningOpportunities.targets(only("price"),"select_facet").isEmpty())
    }
}

