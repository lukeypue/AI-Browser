package com.appgate.brain.lab

import com.appgate.brain.engine.*
import com.appgate.brain.json.*
import com.appgate.brain.memory.*
import com.appgate.brain.model.*
import com.appgate.brain.perception.SpsParser
import java.io.File

/** City acquisition gate: real engine, synthetic wire renderer and durable storage reload.
 * No browser, external teacher, running-app memory or personal data is involved. */
class FacetLearningClinic {
    private val now=1_800_000_000_000L
    private val config=EngineConfig(pacingOverrideMs=0,plannerCooldownMs=0,ambiguousRecheckMs=0,idleSleepMs=0)
    private class Site(val numeric:Boolean,val works:Boolean=true,val human:Boolean=false,val sideEffect:Boolean=false):Renderer {
        val market=SimulationMarket(Scenario(8124,host="facet-clinic.sim.invalid",fault=if(human) SimulationFault.AUTH else SimulationFault.NONE))
            .apply { navigate("https://facet-clinic.sim.invalid/search?q=",1000) }
        val first get()=if(numeric) "price_max" else "condition"
        val second get()=if(numeric) "mileage_max" else "make"
        val commands=mutableListOf<String>()
        val applied get()=if(numeric) market.appliedFilters["mileage_max"]=="150000" else market.appliedFilters["make"]=="Ford"
        override fun observe(timeoutMs:Long):String {
            val raw=Json.parseObject(market.observe(timeoutMs));if(human)return raw.toString()
            val els=raw.optArray("elements")!!.objects().filter { it.optString("tag")!="select" && it.optString("type")!="number" }.toMutableList()
            for((key,name) in if(numeric) listOf("price_max" to "Price max","mileage_max" to "Mileage max") else listOf("condition" to "Condition","make" to "Make")) {
                val e=JsonObject().put("id",key).put("tag",if(numeric) "input" else "select").put("name",name)
                    .put("visible",true).put("enabled",true).put("sameSite",true).put("region","filters")
                    .put("value",if(applied && key==second) if(numeric) "150000" else "Ford" else if(numeric) "" else "Any")
                if(numeric)e.put("type","number") else e.put("choices",if(key==first) JsonArray().add("Any").add("New") else JsonArray().add("Any").add("Ford"))
                els+=e
            }
            raw.put("elements",JsonArray(els));return raw.toString()
        }
        override fun act(command:JsonObject,timeoutMs:Long):RendererResult {
            val id=command.optString("id")
            if(id==first || id==second) {
                commands+=id
                if(id==first && sideEffect)return market.navigate("https://facet-clinic.sim.invalid/search?q=&make=Ford",timeoutMs)
                if(id==second && works) {
                    val value=command.optString("text").ifBlank { command.optString("option").ifBlank { command.optString("value") } }
                    if(value!=if(numeric) "150000" else "Ford")return RendererResult(false,"synthetic unexpected option")
                    return market.navigate(if(numeric) "https://facet-clinic.sim.invalid/search?q=&mileage_max=150000"
                        else "https://facet-clinic.sim.invalid/search?q=&make=Ford",timeoutMs)
                }
                return RendererResult(true) // Deliberate no-op: verifier must reject it.
            }
            return market.act(command,timeoutMs)
        }
        override fun currentUrl()=market.currentUrl()
        override fun navigate(url:String,timeoutMs:Long)=market.navigate(url,timeoutMs)
        override fun back(timeoutMs:Long)=market.back(timeoutMs)
        override fun waitSettle(timeoutMs:Long)=market.waitSettle(timeoutMs)
        override fun setNetworkMode(mode:String,allowlist:List<String>,commitEndpoints:List<String>)=market.setNetworkMode(mode,allowlist,commitEndpoints)
        override fun recover()=market.recover()
        override fun isAlive()=true
    }
    private fun run(site:Site,memory:Memory,lesson:String,id:String):TaskLedger {
        val goal=Goal(id,GoalIntent.LEARN_SITE,"synthetic direct facet lesson","Ford",emptyList(),budget=Budget(actions=8,llmCalls=0,wallMs=10000))
        val ledger=TaskLedger(id,goal,"facet-clinic.sim.invalid",site.currentUrl()).apply { this.lesson=lesson;lastCheckpointUrl=site.currentUrl() }
        return BrainEngine(site,memory,{null},object:EngineEvents{},config) { now }.runTask(ledger,EngineMode.TRAIN)
    }
    private fun snapshot(store:BrainStorage)=store.keys("").associateWith { store.read(it)!! }.toSortedMap()
    private fun trial(root:File,id:String,numeric:Boolean=false,works:Boolean=true,human:Boolean=false,sideEffect:Boolean=false):JsonObject {
        val directory=File(root,id);require(!directory.exists());val store=FileBrainStorage(directory)
        val original=Site(numeric,works,human,sideEffect);val page=SpsParser().parse(original.observe(1000),now)
        val lesson=if(numeric) "constrain_numeric" else "select_facet";val memory=Memory(store) { now }
        val primary=LearningOpportunities.target(page,lesson)
        val hold=primary?.let { FailedStrategies.key(page,lesson,memory.skills.get(lesson)!!.body,it.params) }
        val secondHold=LearningOpportunities.targets(page,lesson).firstOrNull { it.params["key"]==original.second }
            ?.let { FailedStrategies.key(page,lesson,memory.skills.get(lesson)!!.body,it.params) }
        val acquisition=run(original,memory,lesson,"acquire-$id")
        val reload=Memory(FileBrainStorage(directory)) { now }
        val failureRecorded=hold!=null && !FailedStrategies.allowed(reload.site(page.host),hold,now)
        val savedLesson=reload.site(page.host).curriculum.any { it.id==lesson && it.done }
        val frozen=snapshot(store)
        val replayDirectory=File(root,id+"-replay");val replayStore=FileBrainStorage(replayDirectory)
        frozen.forEach { (k,v)->replayStore.write(k,v) }
        val repeated=Site(numeric,works,human,sideEffect)
        val replay=run(repeated,Memory(replayStore) { now },lesson,"replay-$id")
        val finalMemory=Memory(FileBrainStorage(replayDirectory)) { now }
        val verified=finalMemory.site(page.host).curriculum.any { it.id==lesson && it.done }
        val held=hold!=null && !FailedStrategies.allowed(finalMemory.site(page.host),hold,now)
        // Causal check: copy all durable synthetic memory, remove only this known
        // failure, and observe the previously failed control being attempted again.
        var ablatedAttempts=0;var ablatedStatus="NOT_RUN";var ablatedHoldRestored=false;var ablatedCalls=0
        if(works && !human && hold!=null) {
            val ablated=FileBrainStorage(File(root,id+"-failure-ablated"))
            frozen.forEach { (k,v)->ablated.write(k,v) }
            val m=Memory(ablated) { now };val model=m.site(page.host)
            model.strategyFailures.remove(hold);m.saveSite(model)
            val fixture=Site(numeric);val result=run(fixture,m,lesson,"ablate-$id")
            ablatedStatus=result.status.name;ablatedCalls=result.llmCalls
            ablatedHoldRestored=!FailedStrategies.allowed(Memory(ablated) { now }.site(page.host),hold,now)
            ablatedAttempts=fixture.commands.count { it==fixture.first }
        }
        return JsonObject().put("case_id",id).put("acquisition_status",acquisition.status.name).put("replay_status",replay.status.name)
            .put("replay_verified",replay.status==TaskStatus.DONE && lesson in replay.successfulSkills)
            .put("failure_recorded",failureRecorded).put("acquisition_first_attempts",original.commands.count { it==original.first })
            .put("replay_first_attempts",repeated.commands.count { it==repeated.first })
            .put("acquisition_second_attempts",original.commands.count { it==original.second })
            .put("replay_second_attempts",repeated.commands.count { it==repeated.second })
            .put("second_hold_retained",secondHold!=null && !FailedStrategies.allowed(finalMemory.site(page.host),secondHold,now))
            .put("acquisition_physical_constraint_applied",original.applied)
            .put("physical_constraint_applied",repeated.applied).put("verified_lesson",verified)
            .put("hold_retained_after_reload",held).put("lesson_retained_after_reload",verified)
            .put("lesson_retained_before_replay",savedLesson)
            .put("new_lessons_total",if(verified) 1 else 0).put("teacher_calls",acquisition.llmCalls+replay.llmCalls+ablatedCalls)
            .put("acquisition_actions",acquisition.actions).put("replay_actions",replay.actions)
            .put("actions",acquisition.actions+replay.actions).put("ablated_first_attempts",ablatedAttempts)
            .put("ablated_status",ablatedStatus).put("ablated_hold_restored",ablatedHoldRestored)
            .put("ablation_source_unchanged",frozen==snapshot(store))
    }
    fun experiment(root:File):JsonObject {
        require(root.isDirectory && root.listFiles()!!.isEmpty()) { "fresh synthetic clinic directory required" }
        return JsonObject().put("schema_version",1).put("source","JVM_SYNTHETIC_ENGINE")
            .put("real_world_validated",false).put("storage_scope","FILE_RELOAD_SAME_JVM")
            .put("cases",JsonArray(listOf(trial(root,"choice"),trial(root,"numeric",numeric=true),
                trial(root,"no-op",works=false),trial(root,"side-effect",works=false,sideEffect=true),trial(root,"human",human=true))))
    }
}

