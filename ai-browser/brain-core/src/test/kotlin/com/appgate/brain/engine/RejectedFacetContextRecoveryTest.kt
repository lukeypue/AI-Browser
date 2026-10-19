package com.appgate.brain.engine

import com.appgate.brain.json.*
import com.appgate.brain.memory.*
import com.appgate.brain.model.*
import com.appgate.brain.perception.SpsParser
import com.appgate.brain.test.FakeSite
import org.junit.Assert.*
import org.junit.Test

/** Real engine, grounding, verifier and persisted memory; only the synthetic page is scripted. */
class RejectedFacetContextRecoveryTest {
 private class Fixture : Renderer {
  val fake=FakeSite().apply { dialogShown=false; navigate("https://fake.market/search?q=Ford",1000) }
  var choices=listOf("Any","Good")
  var selected="Any"
  var reject=true
  var selects=0
  val traces=mutableListOf<Pair<String,VerifyStatus?>>()
  // Native select applies immediately; two Apply controls make the separate composite repair ambiguous.
  var applied=false
  private var renderer=object:Renderer by fake {
   override fun observe(timeoutMs:Long):String {
    val page=Json.parseObject(fake.observe(timeoutMs));val elements=page.optArray("elements")!!
    elements.add(JsonObject().put("id","condition-choice").put("tag","select").put("name","Condition").put("visible",true).put("enabled",true).put("region","r1").put("value",selected).put("choices",JsonArray().also { a -> choices.forEach { a.add(it) } }).put("bbox",JsonArray().add(0).add(200).add(300).add(30)))
    elements.add(JsonObject().put("id","apply-choice").put("tag","button").put("name","Apply filters").put("visible",true).put("enabled",true).put("region","r1").put("bbox",JsonArray().add(0).add(240).add(300).add(30)))
    elements.add(JsonObject().put("id","apply-choice-alternate").put("tag","button").put("name","Apply choices").put("visible",true).put("enabled",true).put("region","r1").put("bbox",JsonArray().add(0).add(300).add(300).add(30)))
    if(selected=="Good")page.put("url",fake.url+"&condition=Good")
    return page.toString()
   }
   override fun act(command:JsonObject,timeoutMs:Long):RendererResult {
    if(command.optString("cmd")=="select" && command.optString("id")=="condition-choice") {
     selects++
     if(reject)return RendererResult(false,"option not found: synthetic condition")
     if(command.optString("option")!="Good")return RendererResult(false,"unexpected synthetic option")
     selected="Good";return RendererResult(true,"selected")
    }
    if(command.optString("cmd")=="click" && command.optString("id").startsWith("apply-choice")) { applied=true;return RendererResult(true,"applied") }
    return fake.act(command,timeoutMs)
   }
  }
  override fun observe(timeoutMs:Long)=renderer.observe(timeoutMs)
  override fun act(command:JsonObject,timeoutMs:Long)=renderer.act(command,timeoutMs)
  override fun navigate(url:String,timeoutMs:Long)=renderer.navigate(url,timeoutMs)
  override fun back(timeoutMs:Long)=renderer.back(timeoutMs)
  override fun waitSettle(timeoutMs:Long)=renderer.waitSettle(timeoutMs)
  override fun currentUrl()=renderer.currentUrl()
  override fun setNetworkMode(mode:String,allowlist:List<String>,commitEndpoints:List<String>)=renderer.setNetworkMode(mode,allowlist,commitEndpoints)
  override fun recover()=renderer.recover()
  override fun isAlive()=true
 }
 private fun run(f:Fixture,storage:BrainStorage,ordinal:Int):TaskLedger {
  val now=1_800_000_000_000L+ordinal*1000
  val memory=Memory(storage){now}
  val goal=Goal("context$ordinal",GoalIntent.LEARN_SITE,"synthetic facet","Ford",listOf(Constraint("condition",ConstraintOp.EQ,"Good")),budget=Budget(actions=12,llmCalls=0,wallMs=10000))
  val ledger=TaskLedger(goal.id,goal,f.fake.host,f.fake.url).apply { lesson="select_facet";lastCheckpointUrl=f.fake.url }
  return BrainEngine(f,memory,{null},object:EngineEvents{override fun step(ledger:TaskLedger,description:String,status:VerifyStatus?){f.traces+=ledger.programSource to status}},EngineConfig(pacingOverrideMs=0,plannerCooldownMs=0,ambiguousRecheckMs=0,idleSleepMs=0)){now}.runTask(ledger,EngineMode.TRAIN)
 }
 @Test fun changedOptionsReopenRejectedProgramAfterMemoryReload() {
  val storage=InMemoryStorage();val f=Fixture()
  assertEquals("fixture retains two distinct apply controls",2,SpsParser().parse(f.observe(1000),1_800_000_000_000L).byRole(Role.FACET_APPLY).size)
  assertFalse("select_facet" in run(f,storage,0).successfulSkills)
  assertEquals(1,f.selects)
  f.choices=listOf("Any","Good","Excellent");f.reject=false
  val recovered=run(f,storage,1)
  assertEquals("changed native options permit exactly one newly verified select",2,f.selects)
  assertTrue("recovery requires the real verifier and complete procedure", "select_facet" in recovered.successfulSkills)
  assertTrue("the same builtin procedure must verify",f.traces.any { it.first=="skill:select_facet" && it.second==VerifyStatus.VERIFIED })
  assertEquals(0,recovered.llmCalls)
  assertEquals("Good",f.selected)
 }
 @Test fun unchangedOptionsRemainHeldAfterMemoryReloadEvenIfRendererWouldAccept() {
  val storage=InMemoryStorage();val f=Fixture();run(f,storage,0);f.reject=false
  assertFalse("select_facet" in run(f,storage,1).successfulSkills)
  assertEquals(1,f.selects);assertFalse(f.applied)
 }
 @Test fun aNewlyChangedButStillRejectedOptionIsHeldAgainAfterReload() {
  val storage=InMemoryStorage();val f=Fixture();run(f,storage,0)
  f.choices=listOf("Any","Good","Excellent")
  run(f,storage,1)
  assertEquals("a genuine context change permits one new attempt",2,f.selects)
  run(f,storage,2)
  assertEquals("the new rejection must also survive reload",2,f.selects)
 }
 @Test fun legacyWholeProgramHoldIsNeverClearedToAllowAChangedOption() {
  val storage=InMemoryStorage();val f=Fixture()
  val memory=Memory(storage){1_800_000_000_000L};val page=SpsParser().parse(f.observe(1000),1_800_000_000_000L)
  val builtin=memory.skills.get("select_facet")!!
  val key=FailedStrategies.key(page,"select_facet",builtin.body,mapOf("key" to "condition","value" to "Good"))
  FailedStrategies.record(memory.site(f.fake.host),key,false,1_800_000_000_000L);memory.saveSite(memory.site(f.fake.host))
  f.choices=listOf("Any","Good","Excellent");f.reject=false
  run(f,storage,1)
  assertFalse("legacy hold must still block the same builtin",f.traces.any { it.first=="skill:select_facet" })
  assertFalse("opaque legacy failures must be retained",FailedStrategies.allowed(Memory(storage).site(f.fake.host),key,Long.MAX_VALUE))
 }
}
