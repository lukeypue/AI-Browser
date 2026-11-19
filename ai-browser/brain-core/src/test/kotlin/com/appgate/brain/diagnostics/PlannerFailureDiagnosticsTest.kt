package com.appgate.brain.diagnostics
import com.appgate.brain.engine.*
import com.appgate.brain.json.*
import com.appgate.brain.memory.*
import com.appgate.brain.model.*
import com.appgate.brain.planner.*
import com.appgate.brain.test.FakeSite
import org.junit.Assert.*
import org.junit.Test
class PlannerFailureDiagnosticsTest {
 @Test fun actualEngineReportsSafeHttpFailureCodeInsteadOfPrivateExceptionText() {
  for((status,category) in listOf(401 to "AUTHENTICATION",403 to "PERMISSION",429 to "RATE_LIMIT",503 to "SERVER",400 to "HTTP_OTHER")) {
   val fake=FakeSite().apply{dialogShown=false};val rows=mutableListOf<JsonObject>()
   val client=object:PlannerClient { override val describe="private-model-endpoint"
    override fun complete(instructions:String,input:String,schemaName:String,schema:JsonObject,maxOutputTokens:Int):String=throw PlannerHttpException(status,"private-token-body") }
   val events=object:EngineEvents { override fun diagnostic(host:String,kind:String,data:JsonObject){if(kind=="planner_outcome")rows+=data} }
   val goal=Goal("custom",GoalIntent.CUSTOM,"synthetic","",emptyList(),budget=Budget(actions=2,llmCalls=1))
   BrainEngine(fake,Memory(InMemoryStorage()),{Planner(client)},events,EngineConfig(pacingOverrideMs=0,plannerCooldownMs=0,ambiguousRecheckMs=0,idleSleepMs=0)).runTask(TaskLedger("custom",goal,fake.host,fake.url))
   val e=PhoneReportExporter.projectEvent("planner_outcome",rows.single{it.optString("planner_outcome")=="ERROR"})!!
   assertEquals(category,e.optString("planner_failure"));assertEquals(status,e.optObject("counters")!!.optInt("http_status"))
   assertTrue(e.optObject("counters")!!.has("planner_elapsed_ms"));assertEquals("UNKNOWN",e.optString("planner_provider"));assertFalse(e.toString().contains("private"))
  }
 }
 @Test fun wholeTestFailureCountsFreezeBeforeLateCallbacks() {
  val run=TimedLearningTest(60,1000)
  repeat(31){run.diagnostic("planner_outcome",Json.obj("planner_outcome" to "ERROR","planner_failure" to "AUTHENTICATION"))}
  run.diagnostic("planner_outcome",Json.obj("planner_outcome" to "UNAVAILABLE"))
  val summary=run.finish(3601000,"deadline")
  assertEquals(31,summary.plannerFailureCounts["planner_auth_errors"]);assertEquals(31,summary.plannerErrors)
  run.diagnostic("planner_outcome",Json.obj("planner_outcome" to "ERROR","planner_failure" to "AUTHENTICATION"))
  assertEquals(summary,run.finish(3602000,"stopped"))
 }
 @Test fun projectionRejectsFreeTextAndMalformedHttpStatus() {
  val e=PhoneReportExporter.projectEvent("planner_outcome",Json.obj("planner_failure" to "private-token","planner_provider" to "private-url","http_status" to 999,"planner_elapsed_ms" to -1,"body" to "private-body"))!!
  assertEquals("UNKNOWN",e.optString("planner_failure"));assertEquals("UNKNOWN",e.optString("planner_provider"));assertFalse(e.optObject("counters")!!.has("http_status"));assertFalse(e.toString().contains("private"))
 }
 @Test fun endSummaryRetainsCategoryTotalsWhenEarlierTraceIsGone() {
  val c=PhoneTestContext("11111111-1111-4111-8111-111111111111",1000,1700000000000,0)
  val end=Json.obj("kind" to "learning_test_end","elapsed" to 2000,"test_id" to c.id,"boot" to 0,"x" to Json.obj("planner_auth_errors" to 31,"planner_rate_errors" to 2,"reason" to "deadline"))
  val r=PhoneReportExporter.export(c,listOf(end),2000,1700000001000,1,false,"a".repeat(40),70)
  java.io.File("build/reports/planner-failure-phone-fixture.json").apply{parentFile.mkdirs();writeText(PhoneReportExporter.canonical(r))}
  assertTrue(r.optObject("flags")!!.optBoolean("trimmed"));assertEquals(31,r.optObject("summary")!!.optInt("planner_auth_errors"));assertEquals(2,r.optObject("summary")!!.optInt("planner_rate_errors"))
 }
 @Test fun cityKeepsFirstPlannerFailureEvenOutsideOrdinaryWindow() {
  val d=com.appgate.brain.lab.CityDiagnostics()
  repeat(8){d.diagnostic("private","task_lifecycle",Json.obj("task_sequence" to it))}
  d.diagnostic("private","planner_outcome",Json.obj("planner_outcome" to "ERROR","planner_failure" to "AUTHENTICATION","http_status" to 401))
  repeat(8){d.diagnostic("private","task_lifecycle",Json.obj("task_sequence" to it+20))}
  val trace=d.toJson().optArray("trace")!!.objects()
  assertTrue(trace.size<=6);assertTrue(trace.any{it.optString("planner_failure")=="AUTHENTICATION"})
 }
}
