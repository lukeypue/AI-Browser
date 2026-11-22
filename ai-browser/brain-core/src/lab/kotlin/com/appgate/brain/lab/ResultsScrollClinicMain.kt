package com.appgate.brain.lab
import com.appgate.brain.engine.*
import com.appgate.brain.json.*
import com.appgate.brain.memory.*
import com.appgate.brain.model.*
import com.appgate.brain.perception.SpsParser
import com.appgate.brain.planner.*
import com.appgate.brain.skills.BuiltinSkills
import java.io.File

/** Generated Chromium-only scroll lesson; no model, personal profile, or production memory. */
fun main(args:Array<String>) {
 require(args.size in 2..3 && args[0] in setOf("training","evaluation"))
 val contract=args.getOrNull(2)?:"default";require(contract in setOf("default","new-results-only","unrelated-failure","load-more-scroll"))
 val dir=File(args[1]);require(dir.canonicalPath.startsWith(File(System.getProperty("java.io.tmpdir")).canonicalPath+"/results-scroll-"))
 val memory=Memory(FileBrainStorage(dir));val renderer=BrowserClinicRenderer()
 val before=SpsParser().parse(renderer.observe(9000));val site=memory.site(before.host);Curriculum.ensure(site)
 val marker=File(dir,"legacy-root-key.txt")
 val unrelated=contract=="unrelated-failure"
 val capability=if(contract=="load-more-scroll")"load_more" else "scroll_results"
 val experimentBody=when(contract){
  "new-results-only"->listOf(Step(StepKind.SCROLL,expect=listOf(Postcondition.NewResults)))
  "unrelated-failure"->listOf(Step(StepKind.SCROLL,optional=true),Step(StepKind.CLICK,Role.LOAD_MORE,expect=listOf(Postcondition.NewResults)))
  else->BuiltinSkills.scrollResults.body
 }
 if(!unrelated && contract!="default")memory.skills.put((if(capability=="load_more")BuiltinSkills.loadMore else BuiltinSkills.scrollResults).copy(body=experimentBody))
 if(args[0]=="training" && !before.isHumanOnly) {
  require(!marker.exists())
  val old=FailedStrategies.key(before.copy(scrollContext=null),capability,BuiltinSkills.scrollResults.body)
  FailedStrategies.record(site,old,false,System.currentTimeMillis());memory.saveSite(site);marker.writeText(old)
 }
 val key=if(marker.exists())marker.readText().also{require(it.matches(Regex("[0-9a-f]{16}")))} else null
 val beforeDone=site.curriculum.single{it.id==capability}.done
 val oldCount=key?.let{site.strategyFailures[it]?.count}?:0
 val selectedKey=FailedStrategies.key(before,if(unrelated)"custom" else capability,experimentBody)
 val goal=Goal("synthetic-scroll-"+System.nanoTime(),if(unrelated)GoalIntent.CUSTOM else GoalIntent.LEARN_SITE,"synthetic scroll","synthetic",emptyList(),budget=Budget(actions=8,llmCalls=if(unrelated)1 else 0,wallMs=15000))
 val ledger=TaskLedger(goal.id,goal,before.host,renderer.currentUrl()).apply{if(!unrelated)lesson=capability}
 val config=EngineConfig(pacingOverrideMs=0,plannerCooldownMs=0,ambiguousRecheckMs=0,idleSleepMs=0,maxConsecutiveFailures=4,maxDecisionsWithoutProgress=20)
 val stub=if(unrelated)Planner(object:PlannerClient {
  override val describe="SCRIPTED_NEGATIVE_STUB"
  override fun complete(instructions:String,input:String,schemaName:String,schema:JsonObject,maxOutputTokens:Int):String=
   """{"steps":[{"kind":"SCROLL","optional":true,"expect":[]},{"kind":"CLICK","role":"LOAD_MORE","expect":["NEW_RESULTS"]}],"confidence":0.99}"""
 }) else null
 val out=BrainEngine(renderer,memory,{stub},object:EngineEvents{},config).runTask(ledger,EngineMode.TRAIN)
 val restored=Memory(FileBrainStorage(dir)).site(before.host);Curriculum.ensure(restored)
 val done=restored.curriculum.single{it.id==capability}.done
 println(JsonObject().put("kind","DONE").put("status",out.status.name).put("actions",out.actions)
  .put("teacher_calls",out.llmCalls).put("planner_kind",if(unrelated)"SCRIPTED_NEGATIVE_STUB" else "NONE").put("capability",capability).put("verified_scroll",capability in out.successfulSkills)
  .put("lesson_before",beforeDone).put("lesson_after_reload",done).put("new_lesson",!beforeDone && done)
  .put("inner_failures_after_reload",restored.strategyFailures[selectedKey]?.count?:0).put("neutral_scroll_only",out.neutralScrollOnly)
  .put("old_root_hold_before",oldCount).put("old_root_hold_after_reload",key?.let{restored.strategyFailures[it]?.count}?:0).toString())
}
