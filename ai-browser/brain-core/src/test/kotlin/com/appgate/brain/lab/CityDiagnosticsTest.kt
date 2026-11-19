package com.appgate.brain.lab
import org.junit.Assert.*
import org.junit.Test
class CityDiagnosticsTest {
 @Test fun realEngineEpisodeKeepsBoundedSanitizedTraceWithoutChangingScore() {
  val scenario=SimulationTrainer.evaluationCases("practice.sim.invalid",1000).first()
  val first=SimulationTrainer().episode(scenario,emptyMap())
  val market=SimulationMarket(scenario)
  val ledger=com.appgate.brain.model.TaskLedger("noop",scenario.goal(),scenario.host,market.currentUrl())
  com.appgate.brain.engine.BrainEngine(market,com.appgate.brain.memory.Memory(com.appgate.brain.memory.InMemoryStorage()){SimulationTrainer.NOW},{null},object:com.appgate.brain.engine.EngineEvents{},com.appgate.brain.engine.EngineConfig(pacingOverrideMs=0,plannerCooldownMs=0,ambiguousRecheckMs=0,idleSleepMs=0)){SimulationTrainer.NOW}.runTask(ledger)
  assertEquals("recording must not affect frozen scoring",EpisodeScorer.score(scenario,market,ledger).toJson(),first.score.toJson())
  val d=first.toJson().optObject("diagnostics");assertNotNull("City must retain actual engine events",d)
  assertTrue(d!!.optInt("events_seen")>0);assertTrue(d.optArray("trace")!!.size in 1..6)
  assertFalse(d.toString().contains("https://"));assertFalse(d.toString().contains("rationale"))
 }
 @Test fun separateLessonClinicDistinguishesUnknownAndNoopFailuresFromHumanAndDetailPaths() {
  val c=LearningDiagnosticClinic.run()
  assertEquals(false,c.optBoolean("real_world_validated",true))
  val rows=c.optArray("cases")!!.objects();assertEquals(listOf("detail","unknown_after_click","noop_click","human"),rows.map{it.optString("case_id")})
  assertTrue(rows.all{it.optBoolean("diagnostic_expectation_met") && it.optInt("actions")<=8})
  assertTrue(rows.single{it.optString("case_id")=="unknown_after_click"}.optObject("diagnostics")!!.optInt("action_failed")>0)
  assertEquals(0,rows.single{it.optString("case_id")=="human"}.optInt("actions"))
  assertFalse(c.toString().contains("https://"));assertFalse(c.toString().contains("2010"));assertFalse(c.toString().contains("Buy now"))
 }

 @Test fun pinnedItemOpeningSurvivesExtraEarlierAndLaterEvents() {
  val d=CityDiagnostics()
  repeat(5){d.diagnostic("private","task_lifecycle",com.appgate.brain.json.JsonObject().put("task_sequence",it+1))}
  d.diagnostic("private","action_outcome",com.appgate.brain.json.JsonObject().put("capability","open_item").put("status","FAILED").put("code","UNMET_EXPECTATION").put("after_page_type","UNKNOWN"))
  repeat(8){d.diagnostic("private","task_lifecycle",com.appgate.brain.json.JsonObject().put("task_sequence",it+10))}
  val out=d.toJson();val trace=out.optArray("trace")!!.objects()
  assertEquals(6,trace.size);assertEquals(14,out.optInt("events_seen"));assertEquals(8,out.optInt("events_dropped"));assertEquals(1,out.optInt("action_failed"))
  assertTrue("opening evidence must not depend on callback position",trace.any{it.optString("capability")=="open_item" && it.optString("status")=="FAILED"})
 }
 @Test fun ordinaryTraceKeepsExactFirstThreeLastThreeWithStreamingTotals() {
  val d=CityDiagnostics();repeat(10){d.diagnostic("private","task_lifecycle",com.appgate.brain.json.JsonObject().put("task_sequence",it+1))}
  val out=d.toJson();assertEquals(listOf(1,2,3,8,9,10),out.optArray("trace")!!.objects().map{it.optObject("counters")!!.optInt("task_sequence")})
  assertEquals(10,out.optInt("events_seen"));assertEquals(4,out.optInt("events_dropped"))
 }

 @Test fun equalEventBodiesAreNotMistakenForDuplicateCallbacks() {
  val d=CityDiagnostics();repeat(100){d.diagnostic("private","task_lifecycle",com.appgate.brain.json.JsonObject())}
  val out=d.toJson();assertEquals(6,out.optArray("trace")!!.size);assertEquals(94,out.optInt("events_dropped"))
 }
 @Test fun bothPinsSurviveWhenOneAlreadyOccupiesTheThirdFirstSlot() {
  for(openingFirst in listOf(true,false)) {
   val d=CityDiagnostics()
   fun opening(){d.diagnostic("private","action_outcome",com.appgate.brain.json.JsonObject().put("capability","open_item").put("status","FAILED"))}
   fun error(){d.diagnostic("private","planner_outcome",com.appgate.brain.json.JsonObject().put("planner_outcome","ERROR").put("planner_failure","AUTHENTICATION"))}
   repeat(2){d.diagnostic("private","task_lifecycle",com.appgate.brain.json.JsonObject().put("task_sequence",it+1))}
   if(openingFirst)opening() else error()
   repeat(5){d.diagnostic("private","task_lifecycle",com.appgate.brain.json.JsonObject().put("task_sequence",it+10))}
   if(openingFirst)error() else opening()
   repeat(5){d.diagnostic("private","task_lifecycle",com.appgate.brain.json.JsonObject().put("task_sequence",it+20))}
   val trace=d.toJson().optArray("trace")!!.objects()
   assertEquals(6,trace.size);assertTrue(trace.any{it.optString("capability")=="open_item"});assertTrue(trace.any{it.optString("planner_failure")=="AUTHENTICATION"})
  }
 }
 @Test fun terminalUnknownDoesNotCollapseDifferentFailedCauses() {
  val base=SimulationTrainer().episode(SimulationTrainer.evaluationCases("practice.sim.invalid",1000).first(),emptyMap())
  val rows=listOf("NO_TARGET","NO_TARGET","UNMET_EXPECTATION").mapIndexed{index,code->
   val d=CityDiagnostics();d.diagnostic("private","action_outcome",com.appgate.brain.json.JsonObject().put("code",code).put("status","FAILED"));d.diagnostic("private","task_lifecycle",com.appgate.brain.json.JsonObject().put("status","FAILED"))
   base.copy(scenario=base.scenario.copy(seed=99000+index),diagnostics=d.toJson())
  }
  assertEquals("sample different failure causes",3,CityDiagnostics.report(mapOf("baseline" to rows)).optArray("samples")!!.size)
 }
}
