package com.appgate.brain.lab
import com.appgate.brain.engine.*
import com.appgate.brain.json.*
import com.appgate.brain.memory.*
import com.appgate.brain.model.*
import com.appgate.brain.planner.*

/** Synthetic engine controls only. Fake clients never open a network connection. No reward or training. */
object PlannerDiagnosticClinic {
 fun run():JsonObject {
  val cases=listOf("authentication","permission","rate_limit","server","timeout","invalid_response","unknown","returned")
  val rows=cases.mapIndexed{index,kind->
   val scenario=Scenario(90700+index,1,host="planner-clinic.sim.invalid")
   val market=SimulationMarket(scenario);var calls=0;var final:JsonObject?=null
   val started=System.nanoTime()
   val authenticationState=InMemoryStorage()
   val client=object:PlannerClient {
    override val describe="synthetic"
    override fun complete(instructions:String,input:String,schemaName:String,schema:JsonObject,maxOutputTokens:Int):String {
     calls++
     when(kind) {
      "authentication"->throw PlannerHttpException(401,"private-body")
      "permission"->throw PlannerHttpException(403,"private-body")
      "rate_limit"->throw PlannerHttpException(429,"private-body")
      "server"->throw PlannerHttpException(503,"private-body")
      "timeout"->throw java.net.SocketTimeoutException("private-url")
      "invalid_response"->throw PlannerDiagnosticException(PlannerFailure.INVALID_RESPONSE,"private-output")
      "unknown"->throw IllegalStateException("private-extra")
     }
     return """{"steps":[],"confidence":0.9,"give_up":true,"rationale":"synthetic"}"""
    }
   }
   val activeClient=if(kind=="authentication")AuthenticationGuardedClient.create(PlannerAuthenticationHold(authenticationState)){client}!!else client
   val events=object:EngineEvents {
    override fun diagnostic(host:String,kind:String,data:JsonObject) {
     if(kind=="planner_outcome" && data.optString("planner_outcome") in setOf("ERROR","GIVE_UP")) {
      final=com.appgate.brain.diagnostics.PhoneReportExporter.projectEvent(kind,data)?.put("elapsed_ms",((System.nanoTime()-started)/1_000_000).coerceIn(0,86_400_000))
     }
    }
   }
   val goal=scenario.goal().copy(intent=GoalIntent.CUSTOM,budget=Budget(actions=2,llmCalls=1,wallMs=10000))
   val task=TaskLedger("planner-clinic-$index",goal,scenario.host,market.currentUrl())
   BrainEngine(market,Memory(InMemoryStorage()){SimulationTrainer.NOW},{Planner(activeClient)},events,EngineConfig(pacingOverrideMs=0,plannerCooldownMs=0,ambiguousRecheckMs=0,idleSleepMs=0)){SimulationTrainer.NOW}.runTask(task,EngineMode.TRAIN)
   val retryVerified=kind!="authentication" || authenticationRetryHeld(client,authenticationState)
   val expectedFailure=when(kind){"authentication"->"AUTHENTICATION";"permission"->"PERMISSION";"rate_limit"->"RATE_LIMIT";"server"->"SERVER";"timeout"->"TIMEOUT";"invalid_response"->"INVALID_RESPONSE";"unknown"->"UNKNOWN";else->""}
   val expectedStatus=when(kind){"authentication"->401;"permission"->403;"rate_limit"->429;"server"->503;else->0}
   val result=final?:JsonObject()
   val observed=calls==1 && result.optString("planner_outcome")==if(kind=="returned")"GIVE_UP" else "ERROR"
   val matched=retryVerified && observed && result.optString("planner_failure")==expectedFailure && (result.optObject("counters")?.optInt("http_status")?:0)==expectedStatus
   JsonObject().put("case_id",kind).put("client_calls",calls).put("diagnostic_expectation_met",matched).put("planner_result",final)
  }
  return JsonObject().put("schema_version",1).put("synthetic_only",true).put("external_requests",0).put("cases",JsonArray(rows))
 }
 /** Fake-client control: a fresh state reader must suppress two repeats after the first rejection. */
 fun authenticationRetryHeld(client:PlannerClient,storage:BrainStorage):Boolean {
  val reloaded=PlannerAuthenticationHold(storage)
  val guarded=AuthenticationGuardedClient.create(reloaded){client}!!
  val attempts=List(2){runCatching{guarded.complete("synthetic","{}","test",JsonObject())}.exceptionOrNull() is PlannerRefused}
  return reloaded.status()==PlannerConnectionStatus.AUTHENTICATION_REJECTED && attempts.all{it}
 }

}
