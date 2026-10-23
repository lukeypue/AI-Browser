package com.appgate.brain.engine

import com.appgate.brain.json.*
import com.appgate.brain.memory.*
import com.appgate.brain.model.*
import com.appgate.brain.skills.BuiltinSkills
import org.junit.Assert.*
import org.junit.Test

/** Independent CSS-geometry renderer. Does not import or modify the frozen City renderer. */
class PaginationScrollPrerequisiteTest {
    private val now = 1792756800000L
    private val host = "pagination-prerequisite.test"
    private class Market(val distance: Int, val role: Role = Role.PAGE_NEXT, val noop: Boolean = false,
        val human: Boolean = false, val geometry: Boolean = true, val drift: Boolean = false,
        val autoLoad: Boolean = false, val maxScroll: Int = 10000, val delayedLoad: Boolean = false,
        val staleClick: Boolean = false, val docDrift: Boolean = false) : Renderer {
        var y = 0; var generation = 0; var clicks = 0; var scrolls = 0; var unsafe = 0
        private var observationsAfterScroll = 0; private var refusedClick = false
        private val targetY get() = 500 + distance - y
        private val id get() = if (drift && y > 0 || refusedClick) "replacement" else "pagination"
        override fun currentUrl() = "https://pagination-prerequisite.test/search"
        override fun observe(timeoutMs: Long): String {
            if (delayedLoad && y > 0 && ++observationsAfterScroll == 2) generation++
            val items = JsonArray(); val elements = JsonArray()
            repeat(3) { i ->
                val key = "listing-${generation * 3 + i}"
                val href = "https://pagination-prerequisite.test/item/$key"
                elements.add(Json.obj("id" to key, "tag" to "a", "name" to "Synthetic listing", "href" to href,
                    "visible" to true, "enabled" to true, "sameSite" to true, "inCard" to true,
                    "cardHasPrice" to true, "listSize" to 3, "itemKey" to key, "bbox" to Json.arr(listOf(0,100,200,40))))
                items.add(Json.obj("key" to key,"title" to "Synthetic listing","price" to "$100","href" to href,"aff" to key))
            }
            val target = Json.obj("id" to id,"tag" to if (role == Role.PAGE_NEXT) "a" else "button",
                "name" to if (role == Role.PAGE_NEXT) "Next page" else "Load more results",
                "visible" to true,"enabled" to true,"sameSite" to true,"region" to "main")
            if (role == Role.PAGE_NEXT) target.put("rel","next").put("href",currentUrl()+"?page=${generation+1}")
            if (geometry) target.put("bbox",Json.arr(listOf(20,targetY,200,40)))
            elements.add(target)
            return Json.obj("v" to 3,"documentId" to if (docDrift && y > 0) "new-document" else "geometry-document",
                "url" to currentUrl(),"host" to "pagination-prerequisite.test","title" to "Synthetic market",
                "readyState" to "complete","settle" to Json.obj("state" to "IDLE"),
                "viewport" to Json.obj("w" to 480,"h" to 800,"scrollY" to y,"scrollH" to 16000),
                "signals" to Json.obj("hasMain" to true,"resultsHint" to true,"priceCount" to 3,"textLength" to 900,
                    "authPath" to human,"passwordFields" to if (human) 1 else 0),
                "regions" to JsonArray().add(Json.obj("id" to "main","role" to "MAIN")),
                "elements" to elements,"items" to items,"scripts" to JsonArray()).toString()
        }
        override fun act(command: JsonObject, timeoutMs: Long): RendererResult {
            if (human) { unsafe++; return RendererResult(false) }
            when(command.optString("cmd")) {
                "scroll" -> { scrolls++; observationsAfterScroll=0; if (!noop) { y = (y + command.optInt("dy")).coerceAtMost(maxScroll); if (autoLoad) generation++ } }
                "click" -> {
                    clicks++
                    if (staleClick && !refusedClick) { refusedClick=true;return RendererResult(false,"STALE_TARGET") }
                    if (!noop && command.optString("id") == id && targetY >= 0 && targetY + 40 <= 800) generation++
                }
                else -> return RendererResult(false)
            }
            return RendererResult(true)
        }
        override fun navigate(url: String, timeoutMs: Long) = RendererResult(url == currentUrl())
        override fun back(timeoutMs: Long) = RendererResult(false)
        override fun waitSettle(timeoutMs: Long) = RendererResult(true)
        override fun isAlive() = true
        override fun recover() = RendererResult(true)
        override fun setNetworkMode(mode: String, allowlist: List<String>, commitEndpoints: List<String>) = RendererResult(mode == "TRAIN")
    }
    private fun run(m: Market, store: BrainStorage, demo: Boolean = false, budget: Int = 8): TaskLedger {
        val memory = Memory(store) { now }
        val lesson = if (m.role == Role.PAGE_NEXT) "next_page" else "load_more"
        val goal = Goal("geometry-$lesson",GoalIntent.LEARN_SITE,"synthetic","synthetic",emptyList(),budget=Budget(actions=budget,llmCalls=0,wallMs=3000))
        val ledger = TaskLedger(goal.id,goal,host,m.currentUrl()).apply { this.lesson = lesson }
        if (demo) {
            val click = BuiltinSkills.byId(lesson)!!
            ledger.currentProgram = listOf(Step(StepKind.SCROLL,arg="500",expect=listOf(Postcondition.ScrolledDown))) + click.body
            ledger.programSource="planner";ledger.programCapability=lesson;ledger.programPost=click.post
        }
        return BrainEngine(m,memory,{null},object:EngineEvents{},EngineConfig(pacingOverrideMs=0,plannerCooldownMs=0,
            ambiguousRecheckMs=0,idleSleepMs=0,maxConsecutiveFailures=2,maxDecisionsWithoutProgress=16)){now}.runTask(ledger,EngineMode.TRAIN)
    }
    private fun trained(role: Role = Role.PAGE_NEXT): Map<String,String> {
        val store=InMemoryStorage();val m=Market(500,role)
        val out=run(m,store,true)
        assertEquals(1,m.generation);assertTrue(out.successfulSkills.isNotEmpty())
        return store.snapshot()
    }
    private fun fork(snapshot:Map<String,String>)=InMemoryStorage().also { s -> snapshot.forEach { (k,v)->s.write(k,v) } }

    @Test fun verifiedScrollLearnsOnlyItsObservedPaginationPrerequisite() {
        val s=fork(trained());val skill=Memory(s){now}.skills.all().single{it.origin==SkillOrigin.COMPILED}
        assertEquals("PAGE_NEXT",skill.body.first().toJson().optString("until_role"))
    }
    @Test fun reachableTargetDoesNotSpendItsOnlyActionScrolling() {
        val m=Market(0);val out=run(m,fork(trained()),budget=1)
        assertEquals(1,m.generation);assertEquals(0,m.scrolls);assertEquals(1,m.clicks)
        assertEquals(1,out.actions);assertEquals(0,out.llmCalls)
    }
    @Test fun learnedPrerequisiteSurvivesJsonReloadAndDifferentDistances() {
        for(role in listOf(Role.PAGE_NEXT,Role.LOAD_MORE)) {
            val frozen=trained(role)
            for(distance in listOf(0,500,1000,2500)) {
                val m=Market(distance,role);val out=run(m,fork(frozen))
                assertEquals("$role distance=$distance",1,m.generation)
                assertEquals(distance/500,m.scrolls);assertEquals(1,m.clicks)
                assertTrue(out.successfulSkills.isNotEmpty());assertEquals(0,out.llmCalls)
            }
        }
    }
    @Test fun skillRemovalLosesThePrerequisite() {
        val s=fork(trained());val mem=Memory(s){now};mem.skills.all().filter{it.origin==SkillOrigin.COMPILED}.forEach{mem.skills.remove(it.id)}
        val m=Market(1000);run(m,s);assertEquals(0,m.generation)
    }
    @Test fun scrollingThatLoadsResultsNeverAlsoClicksPagination() {
        val m=Market(1000,autoLoad=true);run(m,fork(trained()))
        assertEquals(1,m.generation);assertEquals(0,m.clicks);assertEquals(1,m.scrolls)
    }
    @Test fun missingGeometryAndTargetIdentityDriftStopWithoutClicks() {
        for(m in listOf(Market(1000,geometry=false),Market(1000,drift=true))) {
            run(m,fork(trained()));assertEquals(0,m.generation);assertEquals(0,m.clicks)
        }
    }
    @Test fun noProgressAndBudgetBoundCannotManufactureSuccess() {
        for(m in listOf(Market(2500,noop=true),Market(2500,maxScroll=500))) {
            val out=run(m,fork(trained()));assertEquals(0,m.generation);assertEquals(0,m.clicks)
            assertTrue(out.successfulSkills.isEmpty());assertTrue(m.scrolls<=2)
        }
        val m=Market(2500);val out=run(m,fork(trained()),budget=2)
        assertEquals(2,out.actions);assertEquals(0,m.clicks);assertTrue(out.successfulSkills.isEmpty())
    }
    @Test fun humanPageNeverActs() {
        val m=Market(1000,human=true);val out=run(m,fork(trained()))
        assertEquals(TaskStatus.NEED_HUMAN,out.status);assertEquals(0,out.actions);assertEquals(0,m.unsafe)
    }
    @Test fun compilerCannotBorrowADeclaredGuardWithoutObservedGeometry() {
        val store=InMemoryStorage();val memory=Memory(store){now}
        val goal=Goal("guard-proof",GoalIntent.LEARN_SITE,"synthetic","synthetic",emptyList())
        val ledger=TaskLedger(goal.id,goal,host,"https://$host/search").apply {
            programCompleted=true;programPage=PageType.RESULTS;programCapability="next_page"
            programPost=listOf(Postcondition.NewResults);programVerifiedSteps.addAll(listOf(0,1))
        }
        val body=listOf(Step(StepKind.SCROLL,arg="500",expect=listOf(Postcondition.ScrolledDown),untilRole=Role.PAGE_NEXT),
            Step(StepKind.CLICK,Role.PAGE_NEXT,expect=listOf(Postcondition.NewResults)))
        val skill=com.appgate.brain.skills.SkillCompiler.compileVerified(memory,ledger,body,now)!!
        assertNull("a declaration is not transition evidence",skill.body.first().untilRole)
    }
    @Test fun delayedResultsBetweenObservationsNeverAlsoClick() {
        val m=Market(500,delayedLoad=true);run(m,fork(trained()))
        assertEquals(1,m.generation);assertEquals(0,m.clicks)
    }
    @Test fun guardedClickRefusalCannotRetryAReplacement() {
        val m=Market(500,staleClick=true);run(m,fork(trained()))
        assertEquals(0,m.generation);assertEquals(1,m.clicks)
    }
    @Test fun replacedDocumentCannotContinueThePrerequisite() {
        val m=Market(500,docDrift=true);run(m,fork(trained()))
        assertEquals(0,m.generation);assertEquals(0,m.clicks)
    }
    @Test fun ordinaryScrollEndDoesNotQuarantineTheRetainedSkill() {
        val store=fork(trained());run(Market(2500,maxScroll=500),store)
        val skill=Memory(store){now}.skills.all().single{it.origin==SkillOrigin.COMPILED}
        assertEquals(0.0,skill.stat(host).failures,0.0)
        val m=Market(0);run(m,store);assertEquals(1,m.generation)
    }
}
