package com.appgate.brain.diagnostics

import com.appgate.brain.json.*
import org.junit.Assert.*
import org.junit.Test

class LiveProgressTest {
 private val c=PhoneTestContext("12345678-1234-4234-8234-123456789abc",1000,100000,0,appRevision="a".repeat(40),versionCode=70000)
 private fun event(code:String, elapsed:Long=1500, x:JsonObject=JsonObject())=Json.obj("kind" to code,"elapsed" to elapsed,"test_id" to c.id,"boot" to 0,"host" to "facebook.com","x" to x)
 private fun export(e:List<JsonObject> = emptyList(),now:Long=2000,wall:Long=101000,trim:Int=0,boot:Int=0,finished:Boolean=false)=LiveProgressExporter.export(c,e,now,wall,trim,"a".repeat(40),70000,boot,finished)
 @Test fun exactLiveShapeAndMonotonicSequence() {
  val p=export(wall=0)
  assertEquals(setOf("schema_version","test_id","app_revision","version_code","started_at_utc","elapsed_ms","sequence","state","flags","counters","recent_events"),p.keys)
  assertEquals(1,p.optInt("schema_version"));assertEquals(c.id,p.optString("test_id"))
  assertEquals(1000,p.optLong("elapsed_ms"));assertEquals(2000,p.optLong("sequence"));assertEquals("RUNNING",p.optString("state"))
  assertTrue(p.optObject("flags")!!.optBoolean("clock_changed"))
  assertEquals(2001,export(finished=true).optInt("sequence"))
  assertEquals(172800001,export(now=Long.MAX_VALUE,finished=true).optInt("sequence"))
 }
 @Test fun countersComeFromTypedCurrentEventsAndNeverArbitraryExtras() {
  val e=listOf(
   event("action_outcome",x=Json.obj("status" to "VERIFIED")),
   event("action_outcome",x=Json.obj("status" to "FAILED")),
   event("task_finished"),
   event("learning_outcome",x=Json.obj("new_lessons" to 2,"teacher_attempts" to 3)),
   event("step",x=Json.obj("new_lessons" to 100,"teacher_attempts" to 100,"verified_actions" to 100)),
   event("action_outcome",x=Json.obj("status" to "VERIFIED")).put("test_id","other"),
   event("task_finished").put("boot",9),
   event("learning_outcome",x=Json.obj("new_lessons" to true,"teacher_attempts" to 1.5)))
  assertEquals(Json.obj("verified_actions" to 1,"failed_actions" to 1,"tasks" to 1,"new_lessons" to 2,"teacher_attempts" to 3),export(e).optObject("counters"))
 }
 @Test fun privacyAndNestedRepairEvidenceReuseStrictSchemaTwoEvents() {
  val p=export(listOf(event("learning_recheck",x=Json.obj("lesson" to "dismiss_dialog","capability" to "dismiss_dialog","reason" to "procedure_cooldown","secret" to "private query","page" to Json.obj("page_type" to "DIALOG","closers" to 2,"facet_openers" to 1))),event("private query")))
  val e=p.optArray("recent_events")!!.objects().single()
  assertEquals("dismiss_dialog",e.optString("lesson"));assertEquals("PROCEDURE_COOLDOWN",e.optString("recheck_reason"))
  assertEquals("DIALOG",e.optString("page_type"));assertEquals(2,e.optObject("counters")!!.optInt("closers"))
  assertFalse(p.toString().contains("private"));assertFalse(p.toString().contains("facebook.com"))
  assertEquals(setOf("code","site","page_type","status","phase","diagnostic_code","elapsed_ms","counters","lesson","capability","recheck_reason"),e.keys)
 }
 @Test fun totalsSurviveRecentEventTrimmingAndAreBounded() {
  val e=(1..100).map{event("learning_outcome",1000L+it,Json.obj("new_lessons" to 1000000,"teacher_attempts" to 1000000))}
  val p=export(e)
  assertEquals(32,p.optArray("recent_events")!!.size)
  assertEquals(1000000,p.optObject("counters")!!.optInt("new_lessons"))
  assertEquals(1000000,p.optObject("counters")!!.optInt("teacher_attempts"))
  assertTrue(p.optObject("flags")!!.optBoolean("trimmed"))
  assertTrue(PhoneReportExporter.canonical(p).toByteArray(Charsets.UTF_8).size<=32768)
 }
 @Test fun finalSummaryRestoresTrimmedActionAndTaskTotals() {
  val p=export(listOf(event("learning_test_end",x=Json.obj("verified_actions" to 23,"failed_actions" to 4,"tasks" to 9,"new_lessons" to 7,"teacher_attempts" to 11))),trim=1,finished=true)
  assertEquals(Json.obj("verified_actions" to 23,"failed_actions" to 4,"tasks" to 9,"new_lessons" to 0,"teacher_attempts" to 0),p.optObject("counters"))
  assertEquals("FINISHED",p.optString("state"))
 }
 @Test fun crossBootEndsInterruptedAtLastEvidenceAndKeepsStartingBuild() {
  val p=LiveProgressExporter.export(c,listOf(event("step",1800)),100,101000,0,"b".repeat(40),70001,1)
  assertEquals("FINISHED",p.optString("state"));assertEquals(800,p.optLong("elapsed_ms"));assertEquals(1601,p.optInt("sequence"))
  assertEquals("a".repeat(40),p.optString("app_revision"));assertEquals(70000,p.optInt("version_code"))
  assertTrue(p.optObject("flags")!!.optBoolean("interrupted"));assertTrue(p.optObject("flags")!!.optBoolean("clock_changed"))
 }
 @Test fun invalidIdentityAndChangedRunningBuildFailClosed() {
  assertThrows(IllegalArgumentException::class.java){LiveProgressExporter.export(c.copy(id="private"),emptyList(),2000,101000,0,"a".repeat(40),70000)}
  assertThrows(IllegalArgumentException::class.java){LiveProgressExporter.export(c,emptyList(),2000,101000,0,"b".repeat(40),70001)}
 }
 @Test fun earlierAndFutureEventsCannotContribute() {
  assertEquals(0,export(listOf(event("task_finished",999),event("task_finished",2001))).optObject("counters")!!.optInt("tasks"))
  assertEquals(0,export(now=500).optLong("elapsed_ms"))
 }

 @Test fun crossBootTerminalSequenceCannotRegressBelowPreviouslyEmittedHeartbeat() {
  val p=LiveProgressExporter.export(c,listOf(event("step",11_000)),100,101000,0,"a".repeat(40),70000,1,true,minimumElapsedMs=30_000)
  assertEquals(30_000,p.optLong("elapsed_ms"));assertEquals(60_001,p.optLong("sequence"))
  assertEquals("FINISHED",p.optString("state"))
  assertTrue(p.optObject("flags")!!.optBoolean("interrupted"))
  assertTrue(p.optObject("flags")!!.optBoolean("clock_changed"))
  assertEquals(10_000,p.optArray("recent_events")!!.objects().single().optLong("elapsed_ms"))
 }

 @Test fun syntheticWireFixtureUsesTypedCountersAndFinishedBootFloor() {
  val events=listOf(event("action_outcome",1500,Json.obj("status" to "VERIFIED")),event("task_finished",1600),
   event("learning_outcome",1700,Json.obj("lesson" to "dismiss_dialog","capability" to "dismiss_dialog","status" to "PARTIAL","new_lessons" to 2,"teacher_attempts" to 3,"page" to Json.obj("page_type" to "DIALOG","closers" to 1))))
  val p=LiveProgressExporter.export(c,events,100,101000,0,"a".repeat(40),70000,1,true,minimumElapsedMs=30_000)
  assertEquals(1,p.optObject("counters")!!.optInt("verified_actions"))
  assertEquals(2,p.optObject("counters")!!.optInt("new_lessons"))
  val output=java.io.File("build/reports/live-progress-fixture.json")
  output.parentFile.mkdirs();output.writeText(PhoneReportExporter.canonical(p),Charsets.UTF_8)
 }
}
