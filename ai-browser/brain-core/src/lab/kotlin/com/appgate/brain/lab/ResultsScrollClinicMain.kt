package com.appgate.brain.lab
import com.appgate.brain.engine.*
import com.appgate.brain.json.*
import com.appgate.brain.memory.*
import com.appgate.brain.model.*
import com.appgate.brain.perception.SpsParser
import com.appgate.brain.skills.BuiltinSkills
import java.io.File

/** Generated Chromium-only scroll lesson; no model, personal profile, or production memory. */
fun main(args:Array<String>) {
 require(args.size==2 && args[0] in setOf("training","evaluation"))
 val dir=File(args[1]);require(dir.canonicalPath.startsWith(File(System.getProperty("java.io.tmpdir")).canonicalPath+"/results-scroll-"))
 val memory=Memory(FileBrainStorage(dir));val renderer=BrowserClinicRenderer()
 val before=SpsParser().parse(renderer.observe(9000));val site=memory.site(before.host);Curriculum.ensure(site)
 val marker=File(dir,"legacy-root-key.txt")
 if(args[0]=="training" && !before.isHumanOnly) {
  require(!marker.exists())
  val old=FailedStrategies.key(before.copy(scrollContext=null),"scroll_results",BuiltinSkills.scrollResults.body)
  FailedStrategies.record(site,old,false,System.currentTimeMillis());memory.saveSite(site);marker.writeText(old)
 }
 val key=if(marker.exists())marker.readText().also{require(it.matches(Regex("[0-9a-f]{16}")))} else null
 val beforeDone=site.curriculum.single{it.id=="scroll_results"}.done
 val oldCount=key?.let{site.strategyFailures[it]?.count}?:0
 val goal=Goal("synthetic-scroll-"+System.nanoTime(),GoalIntent.LEARN_SITE,"synthetic scroll","synthetic",emptyList(),budget=Budget(actions=8,llmCalls=0,wallMs=15000))
 val ledger=TaskLedger(goal.id,goal,before.host,renderer.currentUrl()).apply{lesson="scroll_results"}
 val config=EngineConfig(pacingOverrideMs=0,plannerCooldownMs=0,ambiguousRecheckMs=0,idleSleepMs=0,maxConsecutiveFailures=4,maxDecisionsWithoutProgress=20)
 val out=BrainEngine(renderer,memory,{null},object:EngineEvents{},config).runTask(ledger,EngineMode.TRAIN)
 val restored=Memory(FileBrainStorage(dir)).site(before.host);Curriculum.ensure(restored)
 val done=restored.curriculum.single{it.id=="scroll_results"}.done
 println(JsonObject().put("kind","DONE").put("status",out.status.name).put("actions",out.actions)
  .put("teacher_calls",out.llmCalls).put("verified_scroll","scroll_results" in out.successfulSkills)
  .put("lesson_before",beforeDone).put("lesson_after_reload",done).put("new_lesson",!beforeDone && done)
  .put("old_root_hold_before",oldCount).put("old_root_hold_after_reload",key?.let{restored.strategyFailures[it]?.count}?:0).toString())
}
