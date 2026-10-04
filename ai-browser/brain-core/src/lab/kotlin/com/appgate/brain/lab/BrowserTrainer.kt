package com.appgate.brain.lab

import com.appgate.brain.engine.*
import com.appgate.brain.json.*
import com.appgate.brain.memory.*
import com.appgate.brain.model.*
import com.appgate.brain.perception.SpsParser
import com.appgate.brain.goal.GoalParser
import com.appgate.brain.util.Hashing

/** Uses production reasoning; only Renderer and independent scoring belong to the lab. */
class BrowserTrainer(private val renderer:BrowserRenderer,private val emit:(JsonObject)->Unit={}) {
    private fun episode(scenario:JsonObject,store:InMemoryStorage,training:Boolean):JsonObject {
        check(System.getenv("BRAIN_STOP_FILE")?.let { !java.io.File(it).exists() } ?: true) { "Practice stop requested; previous checkpoint preserved" }
        renderer.reset(scenario)
        val seed=scenario.optInt("seed")
        val query=if(seed%2==0)"Toyota Sequoia" else "Ford Expedition"
        val goal=GoalParser.parse("$query under 8000 under 150000 miles with a 3.73 axle",
            Budget(itemsInspected=10,actions=45,llmCalls=0,wallMs=45000)).let { if(training)it.copy(intent=GoalIntent.LEARN_SITE)else it }
        val memory=Memory(store)
        val task=TaskLedger(Hashing.short(scenario.toString()),goal,scenario.optString("host"),renderer.currentUrl())
        var demonstrations=0
        if(training){
            task.lesson="search"
            val obs=SpsParser().parse(renderer.observe(5000))
            val learned=memory.skills.all().any { it.origin==SkillOrigin.COMPILED&&"capability:search" in it.tags&&it.stat(task.host).successes>=2 }
            if(!learned&&!obs.isHumanOnly&&!obs.dialogOpen&&obs.has(Role.SEARCH_BOX)&&obs.has(Role.SUBMIT)){
                task.currentProgram=listOf(
                    Step(StepKind.TYPE,Role.SEARCH_BOX,arg=query,submit=false,expect=listOf(Postcondition.ValueIs("query",query))),
                    Step(StepKind.CLICK,Role.SUBMIT,expect=listOf(Postcondition.PageTypeIs(PageType.RESULTS))))
                task.programSource="planner";task.programCapability="search";task.programPost=listOf(Postcondition.PageTypeIs(PageType.RESULTS));demonstrations=1
            }
        }
        val engine=BrainEngine(renderer,memory,{null},object:EngineEvents{},EngineConfig(pacingOverrideMs=0,
            plannerCooldownMs=0,ambiguousRecheckMs=0,idleSleepMs=0,maxConsecutiveFailures=3,maxDecisionsWithoutProgress=12))
        val result=engine.runTask(task)
        val truth=renderer.truth()
        val score=BrowserObjective.score(truth,result,scenario.optBoolean("missingMileage"),scenario.optString("fault","none"))
            .put("seed",seed).put("host",task.host).put("family",scenario.optString("family"))
            .put("fault",scenario.optString("fault","none")).put("teacher_demonstrations",demonstrations)
            .put("search_verified",truth.optString("appliedQuery")==query&&"search" in result.successfulSkills)
            .put("browser_version",truth.optString("browserVersion"))
        store.keys("ledger/").forEach(store::delete)
        return score
    }

    fun batch(input:Map<String,String>,seed:Int,trainingCount:Int=6,evaluationCount:Int=4):Pair<JsonObject,Map<String,String>> {
        require(trainingCount in 2..100&&evaluationCount in 2..12)
        val cases=(0 until evaluationCount).map { i->JsonObject().put("seed",seed+i).put("host","unseen.sim.invalid")
            .put("family","drawer").put("twoStep",true).put("missingMileage",i%2==1)
            .put("fault",when(i%4){1->"stale";2->"auth";3->"noop";else->"none"}) }
        fun evaluate(snapshot:Map<String,String>,phase:String)=cases.mapIndexed { i,s->
            emit(JsonObject().put("phase",phase).put("episode",i+1).put("total",cases.size))
            episode(s,BrowserCheckpoint.fork(snapshot),false)
        }
        val before=evaluate(input,"Checking before practice")
        val training=BrowserCheckpoint.fork(input)
        val rows=(0 until trainingCount).map { i->
            emit(JsonObject().put("phase","Practicing search").put("episode",i+1).put("total",trainingCount))
            episode(JsonObject().put("seed",-seed-i-1).put("host","practice.sim.invalid").put("family","classic").put("twoStep",true),training,true)
        }
        val snapshot=training.snapshot().toMap()
        val after=evaluate(snapshot,"Checking after practice")
        check(BrowserObjective.safeCheckpoint(rows,after)) { "Practice or evaluation safety check failed; training checkpoint was not replaced" }
        fun summary(list:List<JsonObject>):JsonObject=JsonObject().put("episodes",list.size)
            .put("success_rate",list.count { it.optBoolean("success") }.toDouble()/list.size.coerceAtLeast(1))
            .put("false_verified_claims",list.sumOf { it.optInt("false_verified_claims") })
            .put("hard_violations",list.sumOf { it.optInt("hard_violations") })
            .put("rare_classification_errors",list.sumOf { it.optInt("rare_classification_errors") })
            .put("unsafe_actions",list.sumOf { it.optInt("unsafe_actions") })
            .put("compiled_reuse",list.count { it.optBoolean("compiled_reuse") })
            .put("returned_matches",list.sumOf { it.optInt("returned_matches") })
            .put("expected_matches",list.filterNot { it.optBoolean("expected_failure_case") }.sumOf { it.optInt("expected_matches") })
        val report=JsonObject().put("schema",1).put("mode","real-browser synthetic practice")
            .put("real_world_validated",false).put("imported_to_phone",false).put("external_ai_calls",0).put("external_api_cost_usd",0)
            .put("seed",seed).put("baseline",summary(before)).put("after_training",summary(after))
            .put("training_episodes",rows.size).put("verified_search_practice",rows.count { it.optBoolean("search_verified") })
            .put("teacher_demonstrations",rows.sumOf { it.optInt("teacher_demonstrations") })
            .put("compiled_skills",Memory(BrowserCheckpoint.fork(snapshot)).skills.all().count { it.origin==SkillOrigin.COMPILED })
            .put("held_out_cases",JsonArray(cases)).put("baseline_rows",JsonArray(before)).put("evaluation_rows",JsonArray(after))
            .put("training_rows",JsonArray(rows)).put("browser_version",after.first().optString("browser_version"))
            .put("eligible_for_review",after.all { it.optInt("unsafe_actions")==0&&it.optInt("false_verified_claims")==0&&it.optInt("hard_violations")==0 }&&after.count { it.optBoolean("success") }.toDouble()/after.size>=0.85)
        return report to snapshot
    }
}
