package com.appgate.brain.diagnostics

import com.appgate.brain.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class PhoneDiagnosticsTest {
 @Test fun interruptedReportWithoutStartingBuildCannotClaimCurrentBuild() {
  val legacy = PhoneTestContext("12345678-1234-4234-8234-123456789abc",1000,100000,0)
  val oldEvent = Json.obj("kind" to "step","elapsed" to 1500,"test_id" to legacy.id,"boot" to 0,"build" to "7.2.19-site-brain")
  assertThrows(IllegalArgumentException::class.java) {
   PhoneReportExporter.export(legacy,listOf(oldEvent),2000,101000,0,true,"b".repeat(40),106)
  }
 }
 private val context = PhoneTestContext("12345678-1234-4234-8234-123456789abc", 1000, 100000, 0, appRevision="a".repeat(40), versionCode=70000)
 @Test fun interruptedRecoveryKeepsStartingBuildIdentity() {
  val p=PhoneReportExporter.export(context,emptyList(),2000,101000,0,true,"b".repeat(40),70001)
  assertEquals("a".repeat(40),p.optString("app_revision"))
  assertEquals(70000,p.optInt("version_code"))
  assertTrue(p.optObject("flags")!!.optBoolean("interrupted"))
 }
 @Test fun changedBuildWithoutInterruptionCannotBeLabeledCompleted() {
  assertThrows(IllegalArgumentException::class.java) {
   PhoneReportExporter.export(context,emptyList(),2000,101000,0,false,"b".repeat(40),70001)
  }
 }
 @Test fun malformedStartingBuildFailsClosed() {
  for (invalid in listOf(context.copy(appRevision=null),context.copy(versionCode=null),context.copy(appRevision="private text"),context.copy(appRevision="a".repeat(39)),context.copy(versionCode=-1))) {
   assertThrows(IllegalArgumentException::class.java) {
    PhoneReportExporter.export(invalid,emptyList(),2000,101000,0,true,"b".repeat(40),70001)
   }
  }
 }
 @Test fun legacyUninterruptedCompletionUsesCurrentProcessIdentity() {
  val legacy=context.copy(appRevision=null,versionCode=null)
  val p=PhoneReportExporter.export(legacy,emptyList(),2000,101000,0,false,"b".repeat(40),70001)
  assertEquals("b".repeat(40),p.optString("app_revision"));assertEquals(70001,p.optInt("version_code"))
  assertFalse(p.optObject("flags")!!.optBoolean("interrupted"))
 }
 @Test fun changedBuildCrossBootRecoveryKeepsStartingIdentity() {
  val oldEvent=Json.obj("kind" to "step","elapsed" to 1800,"test_id" to context.id,"boot" to 0)
  val p=PhoneReportExporter.export(context,listOf(oldEvent),100,101000,0,true,"b".repeat(40),70001,currentBoot=1)
  assertEquals("a".repeat(40),p.optString("app_revision"));assertEquals(70000,p.optInt("version_code"))
  assertEquals(800,p.optLong("elapsed_ms"));assertTrue(p.optObject("flags")!!.optBoolean("interrupted"));assertTrue(p.optObject("flags")!!.optBoolean("clock_changed"))
 }
 private fun export(events:List<JsonObject>, now:Long=2000, wall:Long=101000, trimmed:Int=0, interrupted:Boolean=false) = PhoneReportExporter.export(context,events.map{if(!it.has("test_id"))it.put("test_id",context.id);if(!it.has("boot"))it.put("boot",0);it},now,wall,trimmed,interrupted,"a".repeat(40),70000)
 @Test fun supportedKslCarsHostKeepsAllowlistedKslLabel() {
  for (host in listOf("cars.ksl.com", "CARS.KSL.COM", "classifieds.ksl.com")) {
   val report=export(listOf(Json.obj("kind" to "action_outcome", "host" to host, "elapsed" to 1500)))
   assertEquals(host,"KSL",report.optArray("events")!!.objects().single().optString("site"))
   assertFalse(report.toString().contains(host))
  }
 }
 @Test fun kslCarsLookalikeHostsRemainPrivateAndUnknown() {
  for (host in listOf("cars.ksl.com.private.invalid", "private.cars.ksl.com", "private.invalid")) {
   val report=export(listOf(Json.obj("kind" to "action_outcome", "host" to host, "elapsed" to 1500)))
   assertEquals(host,"UNKNOWN",report.optArray("events")!!.objects().single().optString("site"))
   assertFalse(report.toString().contains(host))
  }
 }
 @Test fun personalFieldsAndUnknownCodesNeverEscape() {
  val event=Json.obj("kind" to "step","host" to "classifieds.ksl.com","elapsed" to 1500,"detail" to "secret query", "x" to Json.obj("status" to "VERIFIED","phase" to "SEARCH","url" to "secret-url","items" to 3))
  val p=export(listOf(event,Json.obj("kind" to "secret query","elapsed" to 1600)))
  assertFalse(p.toString().contains("secret"));assertEquals(1,p.optArray("events")!!.size)
  assertEquals("KSL",p.optArray("events")!!.objects()[0].optString("site"))
 }
 @Test fun arbitraryHostsEnumsAndExtrasBecomeUnknownOrAreDropped() {
  val p=export(listOf(Json.obj("kind" to "step","host" to "myname.example/path","elapsed" to 1500,"x" to Json.obj("phase" to "private text","status" to "private text","items" to -1))))
  val e=p.optArray("events")!!.objects()[0];assertEquals("UNKNOWN",e.optString("site"));assertEquals("UNKNOWN",e.optString("phase"));assertFalse(e.optObject("counters")!!.has("items"))
 }
 @Test fun elapsedIsMonotonicAndWallClockChangesAreFlagged() {
  val p=export(emptyList(),now=2000,wall=1)
  assertEquals(1000,p.optLong("elapsed_ms"));assertTrue(p.optObject("flags")!!.optBoolean("clock_changed"))
 }
 @Test fun trimmingAndInterruptionAreExplicit() {
  val p=export(emptyList(),trimmed=1,interrupted=true)
  assertTrue(p.optObject("flags")!!.optBoolean("trimmed"));assertTrue(p.optObject("flags")!!.optBoolean("interrupted"))
 }
 @Test fun onlyCurrentTestEventsAreIncludedInMonotonicOrder() {
  val p=export(listOf(Json.obj("kind" to "step","elapsed" to 900),Json.obj("kind" to "step","elapsed" to 1800),Json.obj("kind" to "step","elapsed" to 1500),Json.obj("kind" to "step","elapsed" to 2200)))
  assertEquals(listOf(500L,800L),p.optArray("events")!!.objects().map{it.optLong("elapsed_ms")})
 }
 @Test fun snapshotsWaitForAllQueuedWrites() {
  val writer=SerialDiagnosticWriter();val list=mutableListOf<Int>()
  try {repeat(100){i->writer.append{list.add(i)}}; assertEquals((0..99).toList(),writer.snapshot{list.toList()}.get(3,TimeUnit.SECONDS))}finally{writer.close()}
 }
 @Test fun snapshotExceptionsAreVisible() {
  val writer=SerialDiagnosticWriter()
  try {val f=writer.snapshot<Int>{error("write failed")};try{f.get();fail()}catch(_:java.util.concurrent.ExecutionException){}}finally{writer.close()}
 }
 @Test fun queueBoundsAndRetryAgeAreEnforced() {
  assertTrue(PhoneQueuePolicy.canAdd(9,9*1048576L,1048576))
  assertFalse(PhoneQueuePolicy.canAdd(10,0,1));assertFalse(PhoneQueuePolicy.canAdd(0,0,1048577));assertFalse(PhoneQueuePolicy.canAdd(9,10*1048576L,1))
  assertFalse(PhoneQueuePolicy.canRetry(7*86400000L));assertFalse(PhoneQueuePolicy.canRetry(-1));assertTrue(PhoneQueuePolicy.canRetry(6*86400000L))
 }
 @Test fun canonicalHashIgnoresObjectInsertionOrder() {
  val a=Json.obj("b" to 2,"a" to 1);val b=Json.obj("a" to 1,"b" to 2)
  assertEquals("{\"a\":1,\"b\":2}",PhoneReportExporter.canonical(a));assertEquals(PhoneReportExporter.sha256(a),PhoneReportExporter.sha256(b))
 }
 @Test fun eventsFromOtherTestsAndBootsAreExcluded() {
  val p=export(listOf(Json.obj("kind" to "step","elapsed" to 1500,"test_id" to "another-test"),Json.obj("kind" to "step","elapsed" to 1500,"boot" to 9)))
  assertEquals(0,p.optArray("events")!!.size)
 }
 @Test fun crossBootInterruptionUsesLastEvidenceNotNewBootUptime() {
  val events=listOf(Json.obj("kind" to "step","elapsed" to 1800,"test_id" to context.id,"boot" to 0))
  val p=PhoneReportExporter.export(context,events,100,101000,0,true,"a".repeat(40),70000,currentBoot=1)
  assertEquals(800,p.optLong("elapsed_ms"));assertEquals(1,p.optArray("events")!!.size)
  assertTrue(p.optObject("flags")!!.optBoolean("clock_changed"));assertTrue(p.optObject("flags")!!.optBoolean("interrupted"))
 }
}
