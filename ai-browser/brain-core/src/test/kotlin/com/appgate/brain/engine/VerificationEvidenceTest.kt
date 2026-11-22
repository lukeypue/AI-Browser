package com.appgate.brain.engine
import com.appgate.brain.json.*
import com.appgate.brain.memory.*
import com.appgate.brain.model.*
import com.appgate.brain.test.FakeSite
import com.appgate.brain.diagnostics.PhoneReportExporter
import org.junit.Assert.*
import org.junit.Test
class VerificationEvidenceTest {
 private fun run(steps:List<Step>,capability:String="open_item",noop:Boolean=false):List<JsonObject>{
  val fake=FakeSite().apply{dialogShown=false;navigate("https://fake.market/search?q=Ford",1000)}
  val renderer=object:Renderer by fake { override fun act(command:JsonObject,timeoutMs:Long)=if(noop)RendererResult(true)else fake.act(command,timeoutMs) }
  val rows=mutableListOf<JsonObject>()
  val ledger=TaskLedger("evidence",Goal("evidence",GoalIntent.LEARN_SITE,"synthetic","Ford",emptyList(),budget=Budget(actions=4,llmCalls=0,wallMs=3000)),fake.host,fake.url).apply{
   lesson=capability;currentProgram=steps;programSource="planner";programCapability=capability;programPost=steps.last().expect
  }
  BrainEngine(renderer,Memory(InMemoryStorage()),{null},object:EngineEvents{override fun diagnostic(host:String,kind:String,data:JsonObject){if(kind=="action_outcome")rows+=PhoneReportExporter.projectEvent(kind,data)!!}},EngineConfig(pacingOverrideMs=0,plannerCooldownMs=0,ambiguousRecheckMs=0,idleSleepMs=0)).runTask(ledger)
  return rows
 }
 @Test fun optionalWaitIsDistinguishedFromVerifiedItemClick(){
  val rows=run(listOf(Step(StepKind.WAIT,optional=true,arg="0"),Step(StepKind.CLICK,Role.RESULT_ITEM,arg="k1",expect=listOf(Postcondition.DetailMatches("k1")))))
  val wait=rows.first();assertEquals("WAIT",wait.optString("action_kind"));assertEquals("NONE",wait.optString("target_class"));assertEquals("NO_EXPECTATIONS",wait.optString("verification_reason"));assertEquals(1,wait.optObject("counters")!!.optInt("optional_step"))
  val click=rows.first{it.optString("status")=="VERIFIED"};assertEquals("CLICK",click.optString("action_kind"));assertEquals("ITEM",click.optString("target_class"));assertEquals("DETAIL_MATCHES",click.optString("primary_expectation"));assertEquals(1,click.optObject("counters")!!.optInt("satisfied_checks"))
 }
 @Test fun unchangedClickExplainsUnmetChecksWithoutClaimingLearning(){
  val rows=run(listOf(Step(StepKind.CLICK,Role.RESULT_ITEM,arg="k1",expect=listOf(Postcondition.DetailMatches("k1")))),noop=true)
  val e=rows.first();assertEquals("NO_SEMANTIC_CHANGE",e.optString("verification_reason"));assertEquals(1,e.optObject("counters")!!.optInt("unmet_checks"));assertEquals(0,e.optObject("counters")!!.optInt("semantic_changed"));assertEquals(2,e.optObject("counters")!!.optInt("observation_rechecks"))
 }
 @Test fun onlyClosedEnumsAndBoundedEvidenceAreProjected(){
  val x=Json.obj("action_kind" to "CLICK","target_class" to "TEXT_EXPANDER","primary_expectation" to "TEXT_EXPANDED","verification_reason" to "EXPECTATION_UNMET","proposal_rejection" to "WRONG_TARGET","expected_checks" to 1,"unmet_checks" to 1,"observation_rechecks" to 2,"optional_step" to 1,"text_expansion_verified" to 0,"private" to "secret")
  val e=PhoneReportExporter.projectEvent("action_outcome",x)!!;assertEquals("WRONG_TARGET",e.optString("proposal_rejection"));assertEquals(1,e.optObject("counters")!!.optInt("expected_checks"));assertFalse(e.toString().contains("secret"))
  for(k in listOf("action_kind","target_class","primary_expectation","verification_reason","proposal_rejection")){assertEquals("UNKNOWN",PhoneReportExporter.projectEvent("action_outcome",Json.obj(k to "private text"))!!.optString(k))}
  for(k in listOf("optional_step","observation_read_failed","text_expansion_verified","semantic_changed","route_changed")){assertFalse(PhoneReportExporter.projectEvent("action_outcome",Json.obj(k to 2))!!.optObject("counters")!!.has(k))}
 }
 @Test fun disappearedExpansionControlWithoutTextGrowthDoesNotEarnCredit(){
  val fake=FakeSite().apply{dialogShown=false;navigate("https://fake.market/item/1",1000)};var clicked=false
  val renderer=object:Renderer by fake{
   override fun observe(timeoutMs:Long):String{val raw=Json.parseObject(fake.observe(timeoutMs));if(!clicked)raw.optArray("elements")!!.add(Json.obj("id" to "expand","tag" to "button","name" to "Show more","visible" to true,"enabled" to true,"region" to "r2","bbox" to Json.arr(listOf(0,100,300,30))));return raw.toString()}
   override fun act(command:JsonObject,timeoutMs:Long):RendererResult{clicked=true;return RendererResult(true)}
  }
  val memory=Memory(InMemoryStorage());val rows=mutableListOf<JsonObject>()
  val steps=listOf(Step(StepKind.CLICK,Role.EXPAND_TEXT,expect=listOf(Postcondition.TextExpanded)))
  val ledger=TaskLedger("expansion",Goal("expansion",GoalIntent.LEARN_SITE,"synthetic","Ford",emptyList(),budget=Budget(actions=2,llmCalls=0,wallMs=3000)),fake.host,fake.url).apply{lesson="expand_description";currentProgram=steps;programSource="planner";programCapability="expand_description";programPost=listOf(Postcondition.TextExpanded)}
  BrainEngine(renderer,memory,{null},object:EngineEvents{override fun diagnostic(host:String,kind:String,data:JsonObject){if(kind=="action_outcome")rows+=PhoneReportExporter.projectEvent(kind,data)!!}},EngineConfig(pacingOverrideMs=0,plannerCooldownMs=0,ambiguousRecheckMs=0,idleSleepMs=0)).runTask(ledger)
  val e=rows.first{it.optString("action_kind")=="CLICK"};assertEquals("TEXT_EXPANDER",e.optString("target_class"));assertEquals("TEXT_EXPANDED",e.optString("primary_expectation"));assertEquals("FAILED",e.optString("status"));assertEquals(1,e.optObject("counters")!!.optInt("target_controls_before"));assertEquals(0,e.optObject("counters")!!.optInt("target_controls_after"));assertEquals(1,e.optObject("counters")!!.optInt("unmet_checks"));assertEquals(0,e.optObject("counters")!!.optInt("text_expansion_verified"));assertFalse("expand_description" in ledger.successfulSkills)
 }
}
