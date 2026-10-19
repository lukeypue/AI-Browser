package com.appgate.brain.engine
import com.appgate.brain.json.*
import com.appgate.brain.memory.*
import com.appgate.brain.model.*
import com.appgate.brain.test.FakeSite
import org.junit.Assert.*
import org.junit.Test

/** Two programs cannot disguise the same rejected facet command as a new approach. */
class RejectedFacetReplayTest {
 private var wanted="Good"
 private var refusal="option not found: synthetic condition"
 private var timeout=false
 private var lastMemory:Memory?=null
 private var lastPage:SemanticPageState?=null
 @Test fun rejectedSelectIsNotReplayedByLocalRepairOrAfterMemoryReload() { runAttempts(expectHeld=true) }
 private fun runAttempts(expectHeld:Boolean) {
  val storage=InMemoryStorage();var selects=0
  repeat(2) { attempt ->
   val fake=FakeSite().apply { dialogShown=false; navigate("https://fake.market/search?q=Ford",1000) }
   val renderer=object:Renderer by fake {
    override fun observe(timeoutMs:Long):String {
     val page=Json.parseObject(fake.observe(timeoutMs));val elements=page.optArray("elements")!!
     elements.add(JsonObject().put("id","condition-choice").put("tag","select").put("name","Condition").put("visible",true).put("enabled",true).put("region","r1").put("value","Any").put("choices",JsonArray().add("Any").add(wanted)).put("bbox",JsonArray().add(0).add(200).add(300).add(30)))
     elements.add(JsonObject().put("id","apply-choice").put("tag","button").put("name","Apply filters").put("visible",true).put("enabled",true).put("region","r1").put("bbox",JsonArray().add(0).add(240).add(300).add(30)))
     return page.toString()
    }
    override fun act(command:JsonObject,timeoutMs:Long):RendererResult {
     if(command.optString("cmd")=="select" && command.optString("id")=="condition-choice") { selects++;if(timeout)throw RendererTimeout("synthetic timeout");return RendererResult(false,refusal) }
     return fake.act(command,timeoutMs)
    }
   }
   val memory=Memory(storage) { 1_800_000_000_000L+attempt*1000L }
   val goal=Goal("facet$attempt",GoalIntent.LEARN_SITE,"synthetic facet","Ford",listOf(Constraint("condition",ConstraintOp.EQ,wanted)),budget=Budget(actions=12,llmCalls=0,wallMs=10000))
   val ledger=TaskLedger(goal.id,goal,fake.host,fake.url).apply { lesson="select_facet";lastCheckpointUrl=fake.url }
   lastMemory=memory;lastPage=com.appgate.brain.perception.SpsParser().parse(renderer.observe(1000),1_800_000_000_000L)
   val out=BrainEngine(renderer,memory,{ error("teacher disabled") },object:EngineEvents {},EngineConfig(pacingOverrideMs=0,plannerCooldownMs=0,ambiguousRecheckMs=0,idleSleepMs=0)) { 1_800_000_000_000L+attempt*1000L }.runTask(ledger,EngineMode.TRAIN)
   if(expectHeld)assertEquals("same rejected command must not be retried under another program or after reload",1,selects)
   assertFalse("select_facet" in out.successfulSkills)
   assertTrue(memory.skills.all().none { it.origin==SkillOrigin.COMPILED })
  }
 }
 @Test fun temporaryAndSafetyRefusalsNeverBecomePermanentCommandHolds() {
  for(reason in listOf("TARGET_MISSING","TARGET_DISABLED","GUARD_BLOCKED","bridge temporarily unavailable")) {
   refusal=reason;runAttempts(expectHeld=false)
   assertNotHeld()
  }
 }
 @Test fun timeoutsNeverBecomePermanentCommandHolds() {
  timeout=true;runAttempts(expectHeld=false);assertNotHeld()
 }
 private fun assertNotHeld() {
  val page=lastPage!!;val control=page.byRole(Role.FACET).single { it.facetKey=="condition" }
  val action=Action(ActionKind.SELECT,AffordanceRef(Role.FACET,"condition",affordanceId=control.id),text="Good",effect=EffectClass.MUTATE_LOCAL)
  val key=FailedStrategies.rejectedFacetSelectKey(page,action)!!
  assertTrue(FailedStrategies.allowed(lastMemory!!.site(page.host),key,Long.MAX_VALUE))
 }
 @Test fun optionTextContainingAStaleCodeIsStillARejectedOption() {
  for(value in listOf("TARGET_MISSING","STALE_TARGET","STALE_DOCUMENT")) {
   wanted=value;refusal="option not found: $value";runAttempts(expectHeld=true)
  }
 }
 @Test fun persistentEngineFailureMemoryNeverContainsRendererOptionText() {
  wanted="PRIVATE_SYNTHETIC_OPTION_MARKER";refusal="option not found: $wanted"
  runAttempts(expectHeld=true)
  assertFalse("even a truncated private option must not persist",lastMemory!!.site(lastPage!!.host).toJson().toString().contains("PRIVATE_",ignoreCase=true))
 }
}
