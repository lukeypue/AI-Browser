package com.appgate.brain.lab

import com.appgate.brain.engine.*
import com.appgate.brain.json.*
import com.appgate.brain.memory.*
import com.appgate.brain.model.*
import com.appgate.brain.perception.SpsParser
import com.appgate.brain.goal.GoalParser
import com.appgate.brain.util.Hashing
import java.net.URLEncoder
import com.appgate.brain.skills.BuiltinSkills

/** Uses production reasoning; only Renderer and independent scoring belong to the lab. */
class BrowserTrainer(private val renderer:BrowserRenderer,private val emit:(JsonObject)->Unit={}) {
    private fun episode(scenario:JsonObject,store:InMemoryStorage,training:Boolean):JsonObject {
        check(System.getenv("BRAIN_STOP_FILE")?.let { !java.io.File(it).exists() } ?: true) { "Practice stop requested; previous checkpoint preserved" }
        renderer.reset(scenario)
        val seed=scenario.optInt("seed")
        val query=if(seed%2==0)"Toyota Sequoia" else "Ford Expedition"
        val lesson=if(training)scenario.optString("lesson","search")else ""
        if(training&&lesson!="search") {
            val price=if(lesson=="constrain_numeric")"9500"else "8000"
            renderer.navigate("https://${scenario.optString("host")}/search?q=${URLEncoder.encode(query,"UTF-8")}&price_max=$price&mileage_max=150000",5000)
        }
        val goal=GoalParser.parse("$query under 8000 under 150000 miles with a 3.73 axle",
            Budget(itemsInspected=10,actions=45,llmCalls=0,wallMs=45000)).let { if(training)it.copy(intent=GoalIntent.LEARN_SITE)else it }
        val memory=Memory(store)
        val task=TaskLedger(Hashing.short(scenario.toString()),goal,scenario.optString("host"),renderer.currentUrl())
        var demonstrations=0
        var intendedItem:String?=null
        if(training){
            task.lesson=lesson
            var obs=SpsParser().parse(renderer.observe(5000))
            if(lesson=="constrain_numeric"&&!obs.isHumanOnly&&!obs.dialogOpen&&obs.settle==Settle.IDLE&&!obs.has(Role.FACET)){
                val opener=obs.affordances.firstOrNull { it.role==Role.FACET_OPEN&&it.visible&&it.enabled&&it.sameSite&&!it.isCommit&&it.effect in setOf(EffectClass.READ,EffectClass.NAVIGATE,EffectClass.MUTATE_LOCAL) }
                if(opener!=null){
                    check(renderer.act(JsonObject().put("cmd","click").put("id",opener.id),5000).ok) { "Filter workspace did not open" }
                    obs=SpsParser().parse(renderer.observe(5000))
                }
            }
            if(lesson=="constrain_numeric"&&!obs.isHumanOnly&&LearningOpportunities.dialogHoldsFilters(obs))task.lastCheckpointUrl=obs.url
            if(lesson=="open_item"){
                intendedItem=obs.collections.flatMap { it.items }.firstOrNull()?.key
                task.currentItem=intendedItem
            }
            val learned=memory.skills.reusable(obs,lesson,mapOf("query" to query,"key" to "price_max","value" to "8000","price_max" to "8000","mileage_max" to "150000","item" to intendedItem.orEmpty()),task)!=null
            if(!learned&&!obs.isHumanOnly&&(!obs.dialogOpen||LearningOpportunities.dialogHoldsFilters(obs))){
                val item=obs.collections.flatMap { it.items }.firstOrNull()?.key
                val steps=when(lesson){
                    "search"->if(obs.has(Role.SEARCH_BOX)&&obs.has(Role.SUBMIT))listOf(
                        Step(StepKind.TYPE,Role.SEARCH_BOX,arg=query,submit=false,expect=listOf(Postcondition.ValueIs("query",query))),
                        Step(StepKind.CLICK,Role.SUBMIT,expect=listOf(Postcondition.PageTypeIs(PageType.RESULTS))))else emptyList()
                    "constrain_numeric"->listOf(
                        Step(StepKind.CLICK,Role.FACET_OPEN,optional=true,expect=listOf(Postcondition.RoleAppeared(Role.FACET))),
                        Step(StepKind.SET_RANGE,Role.FACET,facetKey="price_max",arg="8000",submit=true,expect=listOf(Postcondition.ValueIs("price_max","8000"))),
                        Step(StepKind.CLICK,Role.FACET_APPLY,optional=true,expect=listOf(Postcondition.DialogClosed)))
                    "open_item"->if(item!=null)listOf(Step(StepKind.CLICK,Role.RESULT_ITEM,arg=item,expect=listOf(Postcondition.DetailMatches(item))))else emptyList()
                    "next_page"->if(obs.has(Role.PAGE_NEXT))BuiltinSkills.nextPage.body else emptyList()
                    else->emptyList()
                }
                if(steps.isNotEmpty()){
                    val post=when(lesson){
                        "search"->Postcondition.PageTypeIs(PageType.RESULTS)
                        "constrain_numeric"->Postcondition.ConstraintApplied("price_max","8000")
                        "open_item"->Postcondition.PageTypeIs(PageType.DETAIL)
                        else->Postcondition.NewResults
                    }
                    if(lesson=="open_item")task.currentItem=item
                    task.currentProgram=steps;task.programSource="planner";task.programCapability=lesson;task.programPost=listOf(post);demonstrations=1
                }
            }
        }
        val engine=BrainEngine(renderer,memory,{null},object:EngineEvents{},EngineConfig(pacingOverrideMs=0,
            plannerCooldownMs=0,ambiguousRecheckMs=0,idleSleepMs=0,maxConsecutiveFailures=3,maxDecisionsWithoutProgress=12))
        val result=engine.runTask(task)
        val truth=renderer.truth()
        val score=(if(training)BrowserObjective.scoreLesson(truth,result,intendedItem)else BrowserObjective.score(truth,result,scenario.optBoolean("missingMileage"),scenario.optString("fault","none")))
            .put("seed",seed).put("host",task.host).put("family",scenario.optString("family"))
            .put("fault",scenario.optString("fault","none")).put("teacher_demonstrations",demonstrations)
            .put("search_verified",truth.optString("appliedQuery")==query&&"search" in result.successfulSkills)
            .put("browser_version",truth.optString("browserVersion"))
            .put("reused_capabilities",JsonArray(result.steps.filter { it.source.startsWith("skill:compiled_")&&it.status==VerifyStatus.VERIFIED }
                .flatMap { memory.skills.get(it.source.removePrefix("skill:"))?.tags.orEmpty() }.filter { it.startsWith("capability:") }.map { it.removePrefix("capability:") }.distinct().map { JsonString(it) }))
        store.keys("ledger/").forEach(store::delete)
        return score
    }

    fun batch(input:Map<String,String>,seed:Int,trainingCount:Int=12,evaluationCount:Int=4):Pair<JsonObject,Map<String,String>> {
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
            val lesson=listOf("search","constrain_numeric","open_item","next_page")[(i/3)%4]
            emit(JsonObject().put("phase","Practicing $lesson").put("episode",i+1).put("total",trainingCount))
            episode(JsonObject().put("seed",-seed-i-1).put("host","practice.sim.invalid").put("family",if((seed/100)%2==0)"drawer"else "classic").put("twoStep",true).put("lesson",lesson),training,true)
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
            .put("training_episodes",rows.size).put("verified_search_practice",rows.count { it.optString("lesson")=="search"&&it.optBoolean("lesson_verified") })
            .put("verified_lesson_practice",rows.count { it.optBoolean("lesson_verified") })
            .put("lesson_counts",JsonObject().also { counts->rows.groupBy { it.optString("lesson") }.forEach { (lesson,lessons)->counts.put(lesson,lessons.count { it.optBoolean("lesson_verified") }) } })
            .put("lesson_reuse_counts",JsonObject().also { counts->rows.groupBy { it.optString("lesson") }.forEach { (lesson,lessons)->counts.put(lesson,lessons.count { lesson in it.optStrings("reused_capabilities") }) } })
            .put("compiled_capabilities",JsonArray(Memory(BrowserCheckpoint.fork(snapshot)).skills.all().filter { it.origin==SkillOrigin.COMPILED }.flatMap { it.tags.filter { tag->tag.startsWith("capability:") }.map { tag->tag.removePrefix("capability:") } }.distinct().map { JsonString(it) }))
            .put("teacher_demonstrations",rows.sumOf { it.optInt("teacher_demonstrations") })
            .put("compiled_skills",Memory(BrowserCheckpoint.fork(snapshot)).skills.all().count { it.origin==SkillOrigin.COMPILED })
            .put("held_out_cases",JsonArray(cases)).put("baseline_rows",JsonArray(before)).put("evaluation_rows",JsonArray(after))
            .put("training_rows",JsonArray(rows)).put("browser_version",after.first().optString("browser_version"))
            .put("eligible_for_review",after.all { it.optInt("unsafe_actions")==0&&it.optInt("false_verified_claims")==0&&it.optInt("hard_violations")==0 }&&after.count { it.optBoolean("success") }.toDouble()/after.size>=0.85)
        return report to snapshot
    }
}
