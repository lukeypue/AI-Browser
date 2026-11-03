package com.appgate.brain.lab
import com.appgate.brain.engine.*
import com.appgate.brain.json.*
import com.appgate.brain.memory.*
import com.appgate.brain.model.*
import java.net.URLEncoder

/** Unscored lesson-level diagnostic controls. Expected failures are not browser mastery. */
object LearningDiagnosticClinic {
 fun run():JsonObject {
  val rows=listOf("detail","unknown_after_click","noop_click","human").mapIndexed{index,kind->
   val scenario=Scenario(90600+index,1,host="lesson-clinic.sim.invalid",fault=if(kind=="human")SimulationFault.AUTH else SimulationFault.NONE)
   val market=SimulationMarket(scenario)
   market.navigate("https://${scenario.host}/search?q="+URLEncoder.encode(scenario.query,"UTF-8"),1000)
   var opaque=false
   val renderer=object:Renderer by market {
    override fun currentUrl():String=if(opaque)"https://${scenario.host}/opaque" else market.currentUrl()
    override fun observe(timeoutMs:Long):String {
     if(!opaque)return market.observe(timeoutMs)
     return JsonObject().put("v",3).put("url",currentUrl()).put("host",scenario.host).put("documentId","opaque-synthetic")
      .put("title","").put("detailText","").put("readyState","complete")
      .put("viewport",JsonObject().put("w",400).put("h",800).put("scrollY",0).put("scrollH",800))
      .put("settle",JsonObject().put("state","IDLE")).put("signals",JsonObject().put("hasMain",false).put("textLength",0).put("sparse",true))
      .put("regions",JsonArray()).put("elements",JsonArray()).put("items",JsonArray()).put("scripts",JsonArray()).toString()
    }
    override fun act(command:JsonObject,timeoutMs:Long):RendererResult {
     if(command.optString("cmd")=="click" && kind=="noop_click")return RendererResult(true)
     val result=market.act(command,timeoutMs)
     if(result.ok && command.optString("cmd")=="click" && kind=="unknown_after_click")opaque=true
     return result
    }
   }
   val memory=Memory(InMemoryStorage()){SimulationTrainer.NOW};val site=memory.site(scenario.host);Curriculum.ensure(site)
   site.curriculum.filter{it.id!="expand_description"}.forEach{it.completedAt=SimulationTrainer.NOW-1};memory.saveSite(site)
   val collector=CityDiagnostics()
   val goal=scenario.goal().copy(intent=GoalIntent.LEARN_SITE,budget=Budget(actions=8,llmCalls=0,wallMs=10000))
   val task=TaskLedger("clinic-$index",goal,scenario.host,renderer.currentUrl()).apply{lesson="expand_description";lastCheckpointUrl=renderer.currentUrl();resultsUrl=renderer.currentUrl()}
   val out=BrainEngine(renderer,memory,{null},collector,EngineConfig(pacingOverrideMs=0,plannerCooldownMs=0,ambiguousRecheckMs=0,idleSleepMs=0)){SimulationTrainer.NOW}.runTask(task,EngineMode.TRAIN)
   Curriculum.recordAttempt(site,out,SimulationTrainer.NOW);memory.saveSite(site)
   collector.diagnostic(site.host,"learning_schedule",SemanticDiagnostics.journey(out).put("status",out.status.name).put("lesson","expand_description")
    .put("prerequisite_only",if(out.attemptedSkills.isNotEmpty() && out.lesson !in out.attemptedSkills)1 else 0))
   val diagnostics=collector.toJson()
   val openings=diagnostics.optArray("trace")!!.objects().filter{it.optString("code")=="action_outcome" && it.optString("capability")=="open_item"}
   val observed=when(kind){"human"->out.status==TaskStatus.NEED_HUMAN && out.actions==0
    "detail"->openings.any{it.optString("status")=="VERIFIED" && it.optString("after_page_type")=="DETAIL"}
    else->openings.any{it.optString("status")=="FAILED" && it.optString("after_page_type")==if(kind=="unknown_after_click")"UNKNOWN" else "RESULTS"}}
   JsonObject().put("case_id",kind).put("lesson","expand_description").put("status",out.status.name).put("actions",out.actions)
    .put("diagnostic_expectation_met",observed).put("diagnostics",diagnostics)
  }
  return JsonObject().put("schema_version",1).put("real_world_validated",false).put("teacher_requests",0).put("cases",JsonArray(rows))
 }
}
