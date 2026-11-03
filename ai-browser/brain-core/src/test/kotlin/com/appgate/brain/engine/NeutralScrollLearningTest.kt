package com.appgate.brain.engine

import com.appgate.brain.json.*
import com.appgate.brain.memory.*
import com.appgate.brain.model.*
import com.appgate.brain.perception.SpsParser
import com.appgate.brain.test.FakeSite
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class NeutralScrollLearningTest {
    private val initial = 1_800_000_000_000L
    private val config = EngineConfig(pacingOverrideMs=0, plannerCooldownMs=0, ambiguousRecheckMs=0, idleSleepMs=0)
    private class ScrollSite(val loads: Boolean = false, val rejects: Boolean = false, val lateDialogFailure: String? = null,
        val terminalEnd: Boolean = false, val lateObservation: String? = null) : Renderer {
        val fake = FakeSite().apply { dialogShown=false; navigate("https://fake.market/search?q=", 1000) }
        var y = 0
        private var movingObservations=0
        var observationFaultDelivered=false
            private set
        override fun observe(timeoutMs: Long): String {
            if(y>0 && movingObservations++>0 && lateObservation!=null && !observationFaultDelivered) {
                observationFaultDelivered=true
                if(lateObservation=="timeout") throw RendererTimeout("synthetic later observation timeout")
                throw IllegalStateException("synthetic later observation failure")
            }
            val raw = Json.parseObject(fake.observe(timeoutMs))
            raw.put("elements", JsonArray(raw.optArray("elements")!!.objects().filterNot {
                it.optString("rel")=="next" || lateDialogFailure=="missing" && it.optBoolean("inDialog")
            }))
            raw.put("viewport", JsonObject().put("w",400).put("h",400).put("scrollH",if(terminalEnd) 2000 else 100000).put("scrollY",y))
            return raw.toString()
        }
        override fun act(command: JsonObject, timeoutMs: Long): RendererResult {
            if (command.optString("cmd")=="scroll") {
                if (rejects) return RendererResult(false,"synthetic rejection")
                y += 900
                if(terminalEnd) y=y.coerceAtMost(1600)
                if (loads) fake.page=2
                if (lateDialogFailure!=null) fake.dialogShown=true
                return RendererResult(true)
            }
            if (fake.dialogShown && lateDialogFailure!=null) {
                if (lateDialogFailure=="timeout") throw RendererTimeout("synthetic dismissal timeout")
                return RendererResult(false,"synthetic dismissal rejection")
            }
            return fake.act(command,timeoutMs)
        }
        override fun navigate(url:String,timeoutMs:Long)=fake.navigate(url,timeoutMs)
        override fun back(timeoutMs:Long)=fake.back(timeoutMs)
        override fun currentUrl()=fake.currentUrl()
        override fun waitSettle(timeoutMs:Long)=fake.waitSettle(timeoutMs)
        override fun setNetworkMode(mode:String,allowlist:List<String>,commitEndpoints:List<String>)=fake.setNetworkMode(mode,allowlist,commitEndpoints)
        override fun recover()=fake.recover()
        override fun isAlive()=true
    }
    private fun seed(memory:Memory, now:Long) {
        val site=memory.site("fake.market"); Curriculum.ensure(site)
        site.curriculum.filter { it.id!="scroll_results" }.forEach { it.completedAt=now }
        memory.saveSite(site)
    }
    private fun attempt(memory:Memory, renderer:ScrollSite, now:Long):TaskLedger {
        val goal=Goal("scroll-$now",GoalIntent.LEARN_SITE,"synthetic scroll lesson","",emptyList(),budget=Budget(actions=8,llmCalls=0,wallMs=10000))
        val ledger=TaskLedger(goal.id,goal,"fake.market",renderer.currentUrl()).apply { lesson="scroll_results";lastCheckpointUrl=renderer.currentUrl() }
        val result=BrainEngine(renderer,memory,{null},object:EngineEvents{},config){now}.runTask(ledger,EngineMode.TRAIN)
        val restored=TaskLedger.fromJson(Json.parseObject(result.toJson().toString()))
        Curriculum.recordAttempt(memory.site("fake.market"),restored,now)
        memory.saveSite(memory.site("fake.market"))
        return result
    }
    @Test fun repeatedNeutralScrollingYieldsAcrossDurableReloadsWithoutFalseMastery() {
        val root=Files.createTempDirectory("synthetic-scroll-cadence-").toFile()
        try {
            seed(Memory(FileBrainStorage(root)){initial},initial)
            val admitted=mutableListOf<Int>()
            for (seconds in listOf(0,30,60,90,120,150)) {
                val now=initial+seconds*1000L;val memory=Memory(FileBrainStorage(root)){now};val site=memory.site("fake.market")
                val renderer=ScrollSite();val page=SpsParser().parse(renderer.observe(1000),now)
                Curriculum.observe(site,page,now);Curriculum.migrateRetries(site,now)
                if(Curriculum.nextLesson(site,now,page)=="scroll_results") {
                    admitted+=seconds
                    val out=attempt(memory,renderer,now)
                    assertEquals(TaskStatus.PARTIAL,out.status)
                    assertTrue(out.successfulSkills.isEmpty())
                }
                assertFalse(site.curriculum.single { it.id=="scroll_results" }.done)
                assertTrue(site.strategyFailures.isEmpty())
                memory.saveSite(site)
            }
            assertEquals("neutral attempts must not recur every 30 seconds after reload",listOf(0,30,90),admitted)
        } finally { root.deleteRecursively() }
    }
    @Test fun coolingScrollDoesNotHideNewPaginationAndGenuineScrollStillLearnsAtExpiry() {
        val storage=InMemoryStorage();val memory=Memory(storage){initial};seed(memory,initial)
        attempt(memory,ScrollSite(),initial);attempt(memory,ScrollSite(),initial+30000)
        val now=initial+60000;val restored=Memory(storage){now};val site=restored.site("fake.market")
        val page=SpsParser().parse(ScrollSite().observe(1000),now)
        Curriculum.observe(site,page,now);Curriculum.migrateRetries(site,now)
        assertEquals("",Curriculum.nextLesson(site,now,page))
        assertEquals(initial+90000,Curriculum.earliestRetryAt(site,now))
        assertTrue(Curriculum.unavailableReason(site,now,page).startsWith("cooldown"))
        site.curriculum.single { it.id=="next_page" }.completedAt=0
        val withNext=page.copy(affordances=page.affordances+Affordance("next-new",Role.PAGE_NEXT,tag="a",visible=true,enabled=true,sameSite=true))
        Curriculum.observe(site,withNext,now)
        assertEquals("next_page",Curriculum.nextLesson(site,now,withNext))
        site.curriculum.single { it.id=="next_page" }.completedAt=initial
        assertEquals("unrelated pagination exposure must not erase scroll history","",Curriculum.nextLesson(site,now,withNext))
        val result=attempt(restored,ScrollSite(loads=true),initial+90000)
        assertEquals(TaskStatus.DONE,result.status)
        assertTrue("scroll_results" in result.successfulSkills)
        assertTrue(site.curriculum.single { it.id=="scroll_results" }.done)
        assertFalse(site.curriculum.single { it.id=="scroll_results" }.toJson().has("no_progress_retry_at"))
        assertFalse(site.curriculum.single { it.id=="scroll_results" }.toJson().has("no_progress_retries"))
        assertTrue(site.strategyFailures.isEmpty())
    }
    @Test fun neutralRetryCadenceCapsAndDoesNotStarveVerifiedRehearsal() {
        val storage=InMemoryStorage();seed(Memory(storage){initial},initial)
        var now=initial
        for(delay in listOf(30000L,60000L,120000L,240000L,300000L,300000L)) {
            val memory=Memory(storage){now};val site=memory.site("fake.market")
            val page=SpsParser().parse(ScrollSite().observe(1000),now)
            Curriculum.observe(site,page,now);Curriculum.migrateRetries(site,now)
            assertEquals("scroll_results",Curriculum.nextLesson(site,now,page))
            attempt(memory,ScrollSite(),now)
            val restored=Memory(storage){now+delay-1};val saved=restored.site("fake.market")
            Curriculum.observe(saved,page,now+delay-1);Curriculum.migrateRetries(saved,now+delay-1)
            assertEquals("",Curriculum.nextLesson(saved,now+delay-1,page))
            assertEquals(now+delay,Curriculum.earliestRetryAt(saved,now+delay-1))
            if(now-initial>=300000L) {
                saved.curriculum.filter { it.id!="scroll_results" && it.id!="constrain_numeric" }.forEach { it.completedAt=now }
                assertEquals("constrain_numeric",Curriculum.practiceLesson(saved,now+1,page))
            }
            now+=delay
        }
    }
    @Test fun rejectedScrollAndHumanPagesDoNotCreateNeutralCooldowns() {
        for(human in listOf(false,true)) {
            val memory=Memory(InMemoryStorage()){initial};seed(memory,initial)
            val renderer=ScrollSite(rejects=true).apply { fake.loginWall=human }
            val result=attempt(memory,renderer,initial)
            val site=memory.site("fake.market")
            assertFalse(site.curriculum.single { it.id=="scroll_results" }.toJson().has("no_progress_retry_at"))
            assertTrue(result.successfulSkills.isEmpty())
            if(human) { assertEquals(TaskStatus.NEED_HUMAN,result.status);assertEquals(0,result.actions) }
            else assertTrue("a definite real failure retains its existing procedure hold",site.strategyFailures.isNotEmpty())
        }
    }
    @Test fun aNeutralScrollFollowedByAnUnresolvedDialogIsNotAnExclusivelyNeutralAttempt() {
        for(failure in listOf("reject","timeout","missing")) {
            val memory=Memory(InMemoryStorage()){initial};seed(memory,initial)
            val result=attempt(memory,ScrollSite(lateDialogFailure=failure),initial)
            assertEquals(failure,TaskStatus.PARTIAL,result.status)
            assertTrue(failure,result.actions>0)
            assertFalse(failure,memory.site("fake.market").curriculum.single { it.id=="scroll_results" }.toJson().has("no_progress_retry_at"))
            assertTrue(result.successfulSkills.isEmpty())
        }
    }
    @Test fun aRecoveredObservationTimeoutAfterNeutralMovementDoesNotCreateNeutralScheduling() {
        for(kind in listOf("timeout","exception")) {
            val memory=Memory(InMemoryStorage()){initial};seed(memory,initial)
            val renderer=ScrollSite(lateObservation=kind)
            val result=attempt(memory,renderer,initial)
            assertTrue("the actual recovery path must be exercised",renderer.observationFaultDelivered)
            if(kind=="timeout") assertTrue(result.timeouts>0)
            assertTrue(result.successfulSkills.isEmpty())
            assertFalse(memory.site("fake.market").curriculum.single { it.id=="scroll_results" }.toJson().has("no_progress_retry_at"))
        }
    }
    @Test fun ordinaryEndAndLayoutRotationKeepNeutralCadenceWithoutPoisoningAProcedure() {
        val storage=InMemoryStorage();val memory=Memory(storage){initial};seed(memory,initial)
        val result=attempt(memory,ScrollSite(terminalEnd=true),initial)
        assertEquals(TaskStatus.PARTIAL,result.status);assertTrue(result.successfulSkills.isEmpty())
        assertTrue(memory.site("fake.market").strategyFailures.isEmpty())
        val now=initial+1000;val restored=Memory(storage){now};val site=restored.site("fake.market")
        val page=SpsParser().parse(ScrollSite().observe(1000),now)
        val changed=page.copy(affordances=page.affordances+Affordance("new-filter",Role.FACET_OPEN,facetKey="make",tag="button"))
        Curriculum.observe(site,page.copy(pageType=PageType.FACET_PANEL),now)
        Curriculum.observe(site,changed,now);Curriculum.migrateRetries(site,now)
        assertEquals("",Curriculum.nextLesson(site,now,changed))
        assertEquals(initial+30000,Curriculum.earliestRetryAt(site,now))
    }
    @Test fun onlyNeutralCooldownChangesTheReasonWhenOtherPendingControlsAreAbsent() {
        val memory=Memory(InMemoryStorage()){initial};seed(memory,initial);val site=memory.site("fake.market")
        val page=SpsParser().parse(ScrollSite().observe(1000),initial).let { it.copy(affordances=it.affordances.filterNot { a->a.role==Role.SORT }) }
        site.curriculum.single { it.id=="sort_results" }.completedAt=0
        site.curriculum.single { it.id=="scroll_results" }.retryAt=initial+30000
        Curriculum.observe(site,page,initial)
        assertEquals("no_target_on_results",Curriculum.unavailableReason(site,initial,page))
        attempt(memory,ScrollSite(),initial)
        assertTrue(Curriculum.unavailableReason(site,initial,page).startsWith("cooldown"))
    }
    @Test fun clockRollbackCannotExtendTheSavedNeutralDeadlineBeyondFiveMinutes() {
        val storage=InMemoryStorage();val memory=Memory(storage){initial};seed(memory,initial)
        attempt(memory,ScrollSite(),initial);memory.saveSite(memory.site("fake.market"))
        val now=initial-3600000;val restored=Memory(storage){now};val site=restored.site("fake.market")
        Curriculum.migrateRetries(site,now);restored.saveSite(site)
        assertEquals(now+300000,Curriculum.earliestRetryAt(Memory(storage){now}.site("fake.market"),now))
    }
    @Test fun oldCurriculumAndLedgerSerializationRemainUnchangedWhenNoNeutralEvidenceExists() {
        val item=CurriculumItem("scroll_results","scroll")
        val json=item.toJson()
        assertFalse(json.has("no_progress_retries"));assertFalse(json.has("no_progress_retry_at"))
        assertEquals(json,CurriculumItem.fromJson(json).toJson())
        val legacy="""{"id":"scroll_results","desc":"scroll","done":0,"attempts":0,"blocked":0,"unavailable":0,"opportunity":"UNKNOWN","shape":"","observed_at":0,"retry_at":0,"observed_page":""}"""
        assertEquals(legacy,CurriculumItem.fromJson(Json.parseObject(legacy)).toJson().toString())
        val goal=Goal("legacy",GoalIntent.LEARN_SITE,"synthetic","",emptyList())
        val ledger=TaskLedger("legacy",goal,"fake.market","https://fake.market/")
        assertFalse(ledger.toJson().has("neutral_scroll_only"))
        assertEquals(ledger.toJson(),TaskLedger.fromJson(ledger.toJson()).toJson())
    }
    @Test fun clearingOnlyTheSavedNeutralDeadlineRestoresWastefulCityCadence() {
        val root=Files.createTempDirectory("synthetic-scroll-ablation-").toFile()
        try {
            val baseline=FileBrainStorage(java.io.File(root,"seed"))
            seed(Memory(baseline){initial},initial)
            val frozen=baseline.keys("").associateWith { baseline.read(it)!! }
            val selections=mutableListOf<Int>()
            val actions=mutableListOf<Int>()
            for(ablate in listOf(false,true)) {
                val store=FileBrainStorage(java.io.File(root,if(ablate) "ablated" else "retained"))
                frozen.forEach { (key,value)->store.write(key,value) }
                var selected=0;var spent=0
                for(seconds in 0..900 step 30) {
                    val now=initial+seconds*1000L;val memory=Memory(store){now};val site=memory.site("fake.market")
                    if(ablate) site.curriculum.single { it.id=="scroll_results" }.noProgressRetryAt=0L
                    val renderer=ScrollSite();val page=SpsParser().parse(renderer.observe(1000),now)
                    Curriculum.observe(site,page,now);Curriculum.migrateRetries(site,now)
                    if(Curriculum.nextLesson(site,now,page)=="scroll_results") {
                        selected++;spent+=attempt(memory,renderer,now).actions
                    }
                    assertFalse(site.curriculum.single { it.id=="scroll_results" }.done)
                    assertTrue(site.strategyFailures.isEmpty())
                    memory.saveSite(site)
                }
                selections+=selected;actions+=spent
            }
            assertEquals(listOf(6,31),selections)
            assertTrue("retained scheduling must spend fewer browser actions",actions[0]<actions[1])
            assertEquals("the frozen seed remains unchanged",frozen,baseline.keys("").associateWith { baseline.read(it)!! })
            println("SYNTHETIC_CITY_SCROLL_CADENCE retained_tasks=${selections[0]} ablated_tasks=${selections[1]} retained_actions=${actions[0]} ablated_actions=${actions[1]} new_lessons=0 failure_holds=0 teacher_calls=0 storage=FILE_RELOAD_SAME_JVM")
        } finally { root.deleteRecursively() }
    }
    @Test fun completedNeutralRehearsalBacksOffAcrossDurableReloadsWithoutErasingMastery() {
        val root=Files.createTempDirectory("synthetic-rehearsal-cadence-").toFile()
        try {
            val store=FileBrainStorage(root)
            val memory=Memory(store){initial};seed(memory,initial)
            val site=memory.site("fake.market")
            site.curriculum.single { it.id=="scroll_results" }.completedAt=initial-600000
            site.curriculum.single { it.id=="sort_results" }.completedAt=0
            memory.saveSite(site)
            val admitted=mutableListOf<Int>()
            for(seconds in listOf(0,30,60,90)) {
                val now=initial+seconds*1000L;val restored=Memory(store){now};val saved=restored.site("fake.market")
                val renderer=ScrollSite()
                val page=SpsParser().parse(renderer.observe(1000),now).let { it.copy(affordances=it.affordances.filterNot { a->a.role==Role.SORT }) }
                Curriculum.observe(saved,page,now);Curriculum.migrateRetries(saved,now)
                if(Curriculum.practiceLesson(saved,now,page)=="scroll_results") {
                    admitted+=seconds
                    val out=attempt(restored,renderer,now)
                    assertEquals(TaskStatus.PARTIAL,out.status)
                    assertTrue(out.successfulSkills.isEmpty())
                }
                assertEquals("neutral rehearsal retains prior mastery",initial-600000,saved.curriculum.single { it.id=="scroll_results" }.completedAt)
                assertFalse(saved.curriculum.single { it.id=="sort_results" }.done)
                assertTrue(saved.strategyFailures.isEmpty())
                restored.saveSite(saved)
            }
            assertEquals("completed neutral rehearsals must obey the same persisted backoff",listOf(0,30,90),admitted)
            val restored=Memory(store){initial+210000};val saved=restored.site("fake.market")
            val out=attempt(restored,ScrollSite(loads=true),initial+210000)
            assertEquals(TaskStatus.DONE,out.status)
            assertTrue("scroll_results" in out.successfulSkills)
            assertEquals(0L,saved.curriculum.single { it.id=="scroll_results" }.noProgressRetryAt)
            assertEquals(0,saved.curriculum.single { it.id=="scroll_results" }.noProgressRetries)
        } finally { root.deleteRecursively() }
    }
    @Test fun sessionDoesNotKeepRehearsingCompletedNeutralScrollAtEveryVisit() {
        val root=Files.createTempDirectory("synthetic-session-rehearsal-").toFile()
        try {
            val store=FileBrainStorage(root);val seeded=Memory(store){initial};seed(seeded,initial)
            val site=seeded.site("fake.market")
            site.curriculum.single { it.id=="scroll_results" }.completedAt=initial-600000
            site.curriculum.single { it.id=="sort_results" }.completedAt=0
            seeded.saveSite(site)
            val admitted=mutableListOf<Int>()
            for(seconds in listOf(0,30,60,90)) {
                val now=initial+seconds*1000L;val memory=Memory(store){now};val scrolling=ScrollSite()
                val renderer=object:Renderer by scrolling {
                    override fun observe(timeoutMs:Long):String {
                        val raw=Json.parseObject(scrolling.observe(timeoutMs))
                        raw.put("elements",JsonArray(raw.optArray("elements")!!.objects().filterNot { it.optString("name")=="Sort by" }))
                        return raw.toString()
                    }
                }
                val events=object:EngineEvents {
                    override fun finished(ledger:TaskLedger,result:TaskResult?) {
                        assertEquals("scroll_results",ledger.lesson)
                        assertTrue(ledger.successfulSkills.isEmpty())
                        admitted+=seconds
                    }
                }
                val engine=BrainEngine(renderer,memory,{null},events,config){now}
                val profile=com.appgate.brain.profile.SiteProfile("fake","Fake",listOf("fake.market"),scrolling.currentUrl(),trainingQueries=listOf("Ford"))
                LearningSession(engine,memory,events,listOf(profile),clock={now}).run(1)
            }
            assertEquals(listOf(0,30,90),admitted)
        } finally { root.deleteRecursively() }
    }
}
