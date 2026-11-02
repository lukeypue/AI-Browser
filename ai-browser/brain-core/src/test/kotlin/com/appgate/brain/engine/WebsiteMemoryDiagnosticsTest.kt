package com.appgate.brain.engine

import com.appgate.brain.diagnostics.*
import com.appgate.brain.json.*
import com.appgate.brain.memory.*
import com.appgate.brain.model.*
import com.appgate.brain.test.FakeSite
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class WebsiteMemoryDiagnosticsTest {
 private val now=1_800_000_000_000L
 private fun run(memory:Memory, reject:Boolean=false):List<JsonObject> {
  val fake=FakeSite().apply{dialogShown=false;navigate("https://fake.market/search?q=Ford",1000)}
  val events=mutableListOf<JsonObject>()
  val renderer=object:Renderer by fake {
   override fun observe(timeoutMs:Long):String {
    val raw=Json.parseObject(fake.observe(timeoutMs));val items=raw.optArray("items")!!.objects().take(1)
    val key=items.firstOrNull()?.optString("key")
    raw.put("items",JsonArray(items));raw.put("elements",JsonArray(raw.optArray("elements")!!.objects().filter{!it.has("itemKey")||it.optString("itemKey")==key}))
    return raw.toString()
   }
   override fun act(command:JsonObject,timeoutMs:Long):RendererResult {
    if(reject && command.optString("cmd")=="click")return RendererResult(false,"synthetic rejection")
    return fake.act(command,timeoutMs)
   }
  }
  val goal=Goal("probe",GoalIntent.LEARN_SITE,"practice opening","Ford",emptyList(),budget=Budget(actions=4,llmCalls=0,wallMs=10000))
  BrainEngine(renderer,memory,{null},object:EngineEvents{
   override fun diagnostic(host:String,kind:String,data:JsonObject){if(kind=="memory_checkpoint")events+=data}
  },EngineConfig(pacingOverrideMs=0,plannerCooldownMs=0,ambiguousRecheckMs=0,idleSleepMs=0)){now}
   .runTask(TaskLedger("probe",goal,fake.host,fake.url).apply{lesson="expand_description"})
  return events
 }
 @Test fun fileReloadShowsExistingFailureMemoryInsteadOfCallingItNew() {
  val dir=Files.createTempDirectory("memory-proof").toFile()
  try {
   val first=run(Memory(FileBrainStorage(dir)){now},true)
   val snapshot=first.first{it.optString("code")=="MEMORY_SNAPSHOT"}
   assertEquals(1,snapshot.optInt("memory_missing"));assertEquals(0,snapshot.optInt("memory_loaded"))
   val saved=first.first{it.optString("code")=="STRATEGY_FAILURE_SAVED"}
   assertEquals("open_item",saved.optString("capability"));assertEquals("expand_description",saved.optString("lesson"))
   assertEquals(1,saved.optInt("before_present"));assertEquals(1,saved.optInt("program_present"));assertEquals(1,saved.optInt("source_eligible"));assertEquals(0,saved.optInt("outcome_blocked"))
   assertEquals(0,saved.optInt("strategy_prior_failures"));assertEquals(1,saved.optInt("strategy_current_failures"));assertTrue(saved.optInt("memory_save_acks")>0)
   val second=run(Memory(FileBrainStorage(dir)){now+300000},true)
   val loaded=second.first{it.optString("code")=="MEMORY_SNAPSHOT"}
   assertEquals(1,loaded.optInt("memory_loaded"));assertTrue(loaded.optInt("memory_loaded_failures")>0)
   assertTrue(second.any{it.optString("code")=="STRATEGY_HELD"&&it.optInt("strategy_prior_failures")>0})
   assertFalse("a held exact procedure must not execute and fail again",second.any{it.optString("code")=="STRATEGY_FAILURE_SAVED"})
  } finally {dir.deleteRecursively()}
 }
 @Test fun unreadableFileIsNotMisreportedAsNoSavedMemory() {
  val dir=Files.createTempDirectory("memory-read-failure").toFile()
  try {
   java.io.File(dir,"site%2ffake.market.json").mkdir()
   val memory=Memory(FileBrainStorage(dir)){now};memory.site("fake.market")
   val snapshot=memory.diagnosticSnapshot("fake.market")
   assertEquals(1,snapshot.optInt("memory_read_failed"));assertEquals(0,snapshot.optInt("memory_missing"))
  } finally {dir.deleteRecursively()}
 }
 @Test fun takingADiagnosticDoesNotLoadOrMigrateUncachedWebsiteMemory() {
  val storage=InMemoryStorage()
  val model=SiteModel("fake.market")
  val binding=Binding("fake.market",PageType.RESULTS,Role.RESULT_ITEM,null,"v1",FeatureVec.EMPTY,listOf("synthetic private label"),BetaStat(),1L)
  model.bindings[binding.key]=binding;storage.write("site/fake.market",model.toJson().toString())
  val before=storage.snapshot()
  assertTrue(Memory(storage){now}.diagnosticSnapshot("fake.market").keys.isEmpty())
  assertEquals("diagnostic reads must not trigger migration writes",before,storage.snapshot())
 }
 @Test fun corruptSiteFallbackIsVisibleWithoutExportingSavedText() {
  val storage=InMemoryStorage().apply{write("site/fake.market","private malformed data")}
  val snapshot=run(Memory(storage){now}).firstOrNull{it.optString("code")=="MEMORY_SNAPSHOT"}
  assertNotNull("silent fallback must become observable",snapshot)
  assertEquals(1,snapshot!!.optInt("memory_invalid"));assertEquals(0,snapshot.optInt("memory_missing"));assertEquals(0,snapshot.optInt("memory_loaded"))
  assertFalse(snapshot.toString().contains("private"))
 }
 @Test fun verifiedOutcomesAreSavedWithoutInventingFailureHolds() {
  val memory=Memory(InMemoryStorage()){now};val events=run(memory)
  assertTrue(events.any{it.optString("code")=="STRATEGY_SUCCESS_SAVED"})
  assertTrue(events.filter{it.optString("code")=="STRATEGY_SUCCESS_SAVED"}.all{it.optInt("strategy_current_failures")==0&&it.optInt("outcome_verified")==1})
  assertEquals(1,memory.site("fake.market").curriculum.count{it.id=="open_item"&&it.done})
  assertTrue(memory.site("fake.market").strategyFailures.isEmpty())
 }
 @Test fun phoneExportKeepsOnlyBoundedMemoryEvidence() {
  val context=PhoneTestContext("00000000-0000-4000-8000-000000000001",1000,100000,0)
  val x=Json.obj("code" to "STRATEGY_FAILURE_SAVED","capability" to "open_item","lesson" to "expand_description","page_type" to "DIALOG","memory_loaded" to 1,"memory_strategy_holds" to 4,"strategy_prior_failures" to 0,"strategy_current_failures" to 1,"before_results" to 1,"after_dialog" to 1,"dialog_detail_controls" to 0,"private_url" to "private content","strategy_key" to "private identifier")
  val e=Json.obj("kind" to "memory_checkpoint","host" to "offerup.com","elapsed" to 1500,"test_id" to context.id,"boot" to 0,"x" to x)
  val report=PhoneReportExporter.export(context,listOf(e),2000,101000,0,false,"a".repeat(40),965)
  val row=report.optArray("events")!!.objects().single()
  assertEquals("STRATEGY_FAILURE_SAVED",row.optString("diagnostic_code"));assertEquals("OFFERUP",row.optString("site"))
  assertEquals(1,row.optObject("counters")!!.optInt("memory_loaded"));assertEquals(1,row.optObject("counters")!!.optInt("after_dialog"))
  assertEquals(4,row.optObject("counters")!!.optInt("memory_strategy_holds"));assertFalse(report.toString().contains("private"))
 }
 @Test fun invalidMemoryCountersCannotLeakStringsOrOutOfRangeValues() {
  val context=PhoneTestContext("00000000-0000-4000-8000-000000000001",1000,100000,0)
  for(value in listOf<JsonValue>(JsonNumber(-1.0),JsonNumber(1000001.0),JsonNumber(0.5),JsonBool(true),JsonString("private"))){
   val e=Json.obj("kind" to "memory_checkpoint","elapsed" to 1500,"test_id" to context.id,"boot" to 0,"x" to JsonObject().put("memory_loaded",value))
   val report=PhoneReportExporter.export(context,listOf(e),2000,101000,0,false,"a".repeat(40),965)
   assertFalse(report.optArray("events")!!.objects().single().optObject("counters")!!.has("memory_loaded"))
  }
 }
}
