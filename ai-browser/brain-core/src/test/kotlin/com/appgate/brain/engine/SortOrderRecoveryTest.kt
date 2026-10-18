package com.appgate.brain.engine

import com.appgate.brain.json.JsonObject
import com.appgate.brain.memory.*
import com.appgate.brain.model.*
import com.appgate.brain.profile.SiteProfile
import com.appgate.brain.skills.BuiltinSkills
import com.appgate.brain.verify.Verifier
import org.junit.Assert.*
import org.junit.Test

class SortOrderRecoveryTest {
    private fun page(keys: List<String>, url: String = "https://fake.market/search?q=sample") = SemanticPageState(
        host="fake.market", url=url, urlPattern=url, title="", pageType=PageType.RESULTS,
        pageTypeConfidence=1.0, regions=emptyList(), affordances=listOf(Affordance("sort", Role.SORT,
            tag="select", value="Newest", choices=listOf("Newest", "Price ascending"))),
        collections=listOf(com.appgate.brain.model.Collection("results", keys, keys.size)),
        constraintsActive=emptyMap(), settle=Settle.IDLE, challenge=false, authWall=false,
        dialogOpen=false, hash=keys.joinToString("|"))
    private fun action() = Action(ActionKind.SELECT, AffordanceRef(Role.SORT), expect=BuiltinSkills.sortResults.body.single().expect)
    private fun order(): Postcondition {
        val parsed=Postcondition.fromJson(JsonObject().put("kind", "RESULT_ORDER_CHANGED"))
        assertNotNull("ordered results need a durable typed predicate", parsed)
        return parsed!!
    }
    @Test fun verifiedSortLessonSurvivesReloadAndRunsOnUnseenKeysWithoutTeacher() {
        val directory=java.nio.file.Files.createTempDirectory("sort-recovery").toFile()
        try {
            val now=1_800_000_000_000L
            val renderer=object : Renderer {
                var ascending=false
                var prefix="training"
                var selected="Newest"
                override fun currentUrl()="https://fake.market/search?q=sample"
                override fun navigate(url: String, timeoutMs: Long)=RendererResult(true,"navigated")
                override fun back(timeoutMs: Long)=RendererResult(false,"unused")
                override fun setNetworkMode(mode: String, allowlist: List<String>, commitEndpoints: List<String>)=RendererResult(true,"mode")
                override fun waitSettle(timeoutMs: Long)=RendererResult(true,"settled")
                override fun recover()=RendererResult(true,"recovered")
                override fun isAlive()=true
                override fun act(command: JsonObject, timeoutMs: Long): RendererResult {
                    if(command.optString("cmd")!="select") return RendererResult(false,"unexpected")
                    selected=command.optString("option");ascending=selected=="Price ascending"
                    return RendererResult(true,"selected")
                }
                override fun observe(timeoutMs: Long): String {
                    val ids=if(ascending) listOf("a","b","c") else listOf("c","a","b")
                    val elements=com.appgate.brain.json.JsonArray().add(JsonObject().put("id","sort").put("tag","select")
                        .put("name","Sort by").put("value",selected).put("choices",com.appgate.brain.json.JsonArray(listOf("Newest","Price ascending").map { com.appgate.brain.json.JsonString(it) })).put("region","main").put("visible",true).put("enabled",true))
                    val items=com.appgate.brain.json.JsonArray()
                    ids.forEachIndexed { i,id ->
                        elements.add(JsonObject().put("id","item$i").put("tag","a").put("name","Synthetic item $id").put("href","https://fake.market/item/$prefix$id")
                            .put("itemKey",prefix+id).put("inCard",true).put("cardHasPrice",true).put("sameSite",true).put("region","main"))
                        items.add(JsonObject().put("key",prefix+id).put("title","Synthetic item $id").put("price","100").put("href","https://fake.market/item/$prefix$id").put("aff","item$i"))
                    }
                    return JsonObject().put("v",3).put("url",currentUrl()).put("host","fake.market").put("title","Synthetic results").put("readyState","complete")
                        .put("regions",com.appgate.brain.json.JsonArray().add(JsonObject().put("id","main").put("role","MAIN"))).put("elements",elements).put("items",items)
                        .put("signals",JsonObject().put("resultsHint",true).put("priceCount",3)).put("settle",JsonObject().put("state","IDLE")).toString()
                }
            }
            val memory=Memory(FileBrainStorage(directory)) { now }
            val site=memory.site("fake.market");Curriculum.ensure(site)
            site.curriculum.filter { it.id !in setOf("sort_results","next_page","dismiss_dialog") }.forEach { it.completedAt=now }
            val observed=com.appgate.brain.perception.SpsParser().parse(renderer.observe(1000),now)
            val legacy=listOf(Step(StepKind.SELECT,Role.SORT,arg="\$order",expect=listOf(Postcondition.ResultsChanged)))
            val legacyKey=FailedStrategies.key(observed,"sort_results",legacy)
            FailedStrategies.record(site,legacyKey,false,now);memory.saveSite(site)
            val config=EngineConfig(pacingOverrideMs=0,ambiguousRecheckMs=0,idleSleepMs=0)
            fun run(current: Memory, id: String): TaskLedger {
                val profile=SiteProfile("fake","Fake",listOf("fake.market"),renderer.currentUrl())
                val page=com.appgate.brain.perception.SpsParser().parse(renderer.observe(1000),now)
                val goal=Curriculum.nextGoal(current.site("fake.market"),profile,0,page,"sort_results")
                val ledger=TaskLedger(id,goal,"fake.market",renderer.currentUrl()).apply { lesson="sort_results" }
                return BrainEngine(renderer,current,{ null },object:EngineEvents {},config) { now }.runTask(ledger)
            }
            val trained=run(memory,"train-sort")
            assertEquals(TaskStatus.DONE,trained.status)
            assertTrue(trained.successfulSkills.contains("sort_results"));assertEquals(0,trained.llmCalls)
            val restored=Memory(FileBrainStorage(directory)) { now }
            assertEquals(9,restored.site("fake.market").curriculum.count { it.done })
            assertFalse(FailedStrategies.allowed(restored.site("fake.market"),legacyKey,now))
            renderer.prefix="heldout";renderer.ascending=false;renderer.selected="Newest"
            val heldout=run(restored,"heldout-sort")
            assertEquals(TaskStatus.DONE,heldout.status);assertEquals(0,heldout.llmCalls)
            assertEquals(9,restored.site("fake.market").curriculum.count { it.done })
        } finally { directory.deleteRecursively() }
    }

    @Test fun realReorderWithoutUrlChangeVerifies() {
        assertEquals(VerifyStatus.VERIFIED, Verifier.verify(action(), page(listOf("a","b","c")), page(listOf("b","a","c"))).status)
    }
    @Test fun urlSortParameterWithoutPhysicalReorderCannotEarnCredit() {
        assertEquals(VerifyStatus.FAILED, Verifier.verify(action(), page(listOf("a","b")),
            page(listOf("a","b"), "https://fake.market/search?q=sample&sort=price")).status)
    }
    @Test fun orderPredicateRoundTripsAndRejectsUnrelatedCollectionChanges() {
        val p=order();assertEquals(p, Postcondition.fromJson(p.toJson()))
        val a=Action(ActionKind.SELECT, AffordanceRef(Role.SORT), expect=listOf(p))
        val before=page(listOf("a","b","c"))
        for (after in listOf(before, page(listOf("a","b","d")), page(listOf("a","b","b")),
            page(listOf("a")), page(listOf("c","b","a")).copy(host="other.market"),
            page(listOf("c","b","a")).copy(settle=Settle.BUSY))) {
            assertNotEquals("unrelated, duplicate, busy and cross-host observations are not ordered proof", VerifyStatus.VERIFIED, Verifier.verify(a,before,after).status)
        }
    }
    @Test fun correctedSortContractGetsOneNewAttemptWithoutErasingOldFailure() {
        val before=page(listOf("a","b","c"));val site=SiteModel(before.host)
        val old=listOf(Step(StepKind.SELECT,Role.SORT,arg="\$order",expect=listOf(Postcondition.ResultsChanged)))
        val oldKey=FailedStrategies.key(before,"sort_results",old)
        FailedStrategies.record(site,oldKey,false,1000)
        val newKey=FailedStrategies.key(before,"sort_results",BuiltinSkills.sortResults.body)
        assertNotEquals("corrected verification contract must differ from legacy failure",oldKey,newKey)
        assertTrue(FailedStrategies.allowed(site,newKey,1000))
        assertFalse(FailedStrategies.allowed(site,oldKey,1000))
        FailedStrategies.record(site,newKey,false,1000)
        assertFalse("a failed corrected contract stays held",FailedStrategies.allowed(site,newKey,100000))
        assertEquals(2,site.strategyFailures.size)
    }
    @Test fun sortLearningGoalCarriesExplicitLiveTargetIntoLocalRepair() {
        val before=page(listOf("a","b","c"));val site=SiteModel(before.host)
        val profile=SiteProfile("fake","Fake",listOf(before.host),before.url)
        val goal=Curriculum.nextGoal(site,profile,0,before,"sort_results")
        val ledger=TaskLedger("sort",goal,before.host,before.url).apply { lesson="sort_results" }
        val plan=LocalRecoveryPlanner.propose(ledger,before,site,"sort_results")
        assertNotNull("repair preflight must receive the chosen order, not an empty generic goal",plan)
        assertEquals("Price ascending",plan!!.params["order"])
        assertEquals(VerifyStatus.VERIFIED,Verifier.verify(Action(ActionKind.SELECT,AffordanceRef(Role.SORT),expect=plan.postconditions),
            before,page(listOf("b","c","a"))).status)
    }
}
