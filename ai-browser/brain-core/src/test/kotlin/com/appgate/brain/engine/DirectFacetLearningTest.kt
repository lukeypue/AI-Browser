package com.appgate.brain.engine

import com.appgate.brain.json.*
import com.appgate.brain.memory.*
import com.appgate.brain.model.*
import com.appgate.brain.perception.SpsParser
import com.appgate.brain.profile.SiteProfile
import com.appgate.brain.test.FakeSite
import org.junit.Assert.*
import org.junit.Test

class DirectFacetLearningTest {
    private val now = 1_800_000_000_000L
    private val config = EngineConfig(pacingOverrideMs=0,plannerCooldownMs=0,ambiguousRecheckMs=0,idleSleepMs=0)
    private class DirectSite(val numeric: Boolean=false) : Renderer {
        val fake=FakeSite().apply { dialogShown=false; navigate("https://fake.market/search?q=Ford",1000) }
        val first get()=if(numeric) "price_max" else "condition"
        val second get()=if(numeric) "mileage_max" else "make"
        val commands=mutableListOf<String>()
        var selected=false
        override fun observe(timeoutMs:Long):String {
            val raw=Json.parseObject(fake.observe(timeoutMs))
            val els=raw.optArray("elements")!!.objects().filter { it.optString("tag")!="select" && it.optString("type")!="number" }.toMutableList()
            for((key,name) in if(numeric) listOf("price_max" to "Price max","mileage_max" to "Mileage max") else listOf("condition" to "Condition","make" to "Make")) {
                val e=JsonObject().put("id",key).put("tag",if(numeric) "input" else "select").put("name",name)
                    .put("visible",true).put("enabled",true).put("sameSite",true).put("region","r1")
                    .put("value",if(selected && key==second) if(numeric) "150000" else "Ford" else if(numeric) "" else "Any")
                if(numeric)e.put("type","number") else e.put("choices",if(key==first) JsonArray().add("Any").add("New") else JsonArray().add("Any").add("Ford"))
                els+=e
            }
            raw.put("elements",JsonArray(els));return raw.toString()
        }
        override fun act(command:JsonObject,timeoutMs:Long):RendererResult=when(command.optString("id")) {
            first->{commands+=first;RendererResult(false,"synthetic rejection")}
            second->{commands+=second;selected=true;fake.url+="&$second="+if(numeric) "150000" else "Ford";RendererResult(true)}
            else->fake.act(command,timeoutMs)
        }
        override fun navigate(url:String,timeoutMs:Long)=fake.navigate(url,timeoutMs)
        override fun back(timeoutMs:Long)=fake.back(timeoutMs)
        override fun waitSettle(timeoutMs:Long)=fake.waitSettle(timeoutMs)
        override fun currentUrl()=fake.currentUrl()
        override fun setNetworkMode(mode:String,allowlist:List<String>,commitEndpoints:List<String>)=fake.setNetworkMode(mode,allowlist,commitEndpoints)
        override fun recover()=fake.recover()
        override fun isAlive()=true
    }
    private fun trial(numeric:Boolean) {
        val renderer=DirectSite(numeric);val page=SpsParser().parse(renderer.observe(1000),now)
        val lesson=if(numeric) "constrain_numeric" else "select_facet"
        val storage=InMemoryStorage();val seed=Memory(storage) { now }
        val first=LearningOpportunities.target(page,lesson)!!;assertEquals(renderer.first,first.params["key"])
        val hold=FailedStrategies.key(page,lesson,seed.skills.get(lesson)!!.body,first.params)
        FailedStrategies.record(seed.site(page.host),hold,false,now);seed.saveSite(seed.site(page.host))
        val memory=Memory(storage) { now };val profile=SiteProfile("fake","Fake",listOf(page.host),"https://fake.market/")
        val engine=BrainEngine(renderer,memory,{null},object:EngineEvents{},config) { now }
        assertTrue("held first direct facet must not hide free sibling",engine.learningLessonAvailable(profile,page,lesson,allowTeacher=false))
        val goal=Goal("direct-$lesson",GoalIntent.LEARN_SITE,"synthetic direct facet","Ford",emptyList(),budget=Budget(actions=8,llmCalls=0,wallMs=10000))
        val ledger=TaskLedger(goal.id,goal,page.host,page.url).apply { this.lesson=lesson;lastCheckpointUrl=page.url }
        val result=engine.runTask(ledger,EngineMode.TRAIN)
        assertEquals(result.notes.toString(),TaskStatus.DONE,result.status)
        assertEquals(listOf(renderer.second),renderer.commands)
        assertTrue(renderer.selected);assertTrue(lesson in result.successfulSkills);assertEquals(0,result.llmCalls)
        assertFalse(FailedStrategies.allowed(Memory(storage) { now }.site(page.host),hold,now))
    }
    @Test fun heldDirectChoiceAllowsSiblingWithDifferentKey()=trial(false)
    @Test fun heldDirectNumericAllowsSiblingWithDifferentKey()=trial(true)

    @Test fun directChoiceEnumerationPreservesPrimaryFamilyAndSafety() {
        val page=SpsParser().parse(DirectSite().observe(1000),now)
        val choice=page.affordances.first { it.role==Role.FACET && it.facetKey=="condition" }
        fun facet(id:String,key:String)=choice.copy(id=id,facetKey=key)
        val p=page.copy(affordances=listOf(facet("numeric","price").copy(inputType="number",facetKind="numeric_max",choices=emptyList()),
            facet("offsite","make").copy(sameSite=false),facet("commit","make").copy(effect=EffectClass.COMMIT_EXTERNAL),
            facet("condition","condition"),facet("condition-copy","condition"),facet("make","make")))
        val targets=LearningOpportunities.targets(p,"select_facet")
        assertEquals(listOf("condition","make"),targets.map { it.params["key"] })
        assertEquals(LearningOpportunities.target(p,"select_facet"),targets.first())
        for(unsafe in listOf(p.copy(authWall=true),p.copy(challenge=true),p.copy(settle=Settle.BUSY)))
            assertTrue(LearningOpportunities.targets(unsafe,"select_facet").isEmpty())
    }
    @Test fun directNumericEnumerationDeduplicatesBeforeTheEightKeyBound() {
        val page=SpsParser().parse(DirectSite(true).observe(1000),now)
        val prototype=page.affordances.first { it.role==Role.FACET && it.facetKey=="price_max" }
        val keys=listOf("price","mileage","year","distance","bedrooms","bathrooms").flatMap { listOf(it+"_min",it+"_max") }
        val facets=keys.mapIndexed { i,key->prototype.copy(id="number-$i",facetKey=key) }
        val p=page.copy(affordances=List(12) { facets.first().copy(id="duplicate-$it") }+facets)
        val targets=LearningOpportunities.targets(p,"constrain_numeric")
        assertEquals(keys.take(8),targets.map { it.params["key"] })
        assertEquals(LearningOpportunities.target(p,"constrain_numeric"),targets.first())
    }
    @Test fun numericChoiceWithNoDifferentValidNumberIsUnavailable() {
        val page=SpsParser().parse(DirectSite(true).observe(1000),now)
        val prototype=page.affordances.first { it.role==Role.FACET }
        val p=page.copy(affordances=listOf(prototype.copy(facetKey="price_max",facetKind="choice",inputType="select",value="0",choices=listOf("Any","0","0.0"))))
        assertTrue(LearningOpportunities.targets(p,"constrain_numeric").isEmpty())
    }
}

