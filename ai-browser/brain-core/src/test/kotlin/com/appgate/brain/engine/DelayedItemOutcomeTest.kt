package com.appgate.brain.engine

import com.appgate.brain.goal.GoalParser
import com.appgate.brain.json.*
import com.appgate.brain.memory.*
import com.appgate.brain.model.*
import com.appgate.brain.test.FakeSite
import org.junit.Assert.*
import org.junit.Test

/** Synthetic delayed transition, not a reconstruction of private live site DOM. */
class DelayedItemOutcomeTest {
 private class Delayed(val snapshots:List<String>):Renderer {
  val fake=FakeSite().apply{url="https://$host/search?q=";dialogShown=false}
  val memory=Memory(InMemoryStorage())
  var reads=0;var clicks=0
  var onRead:((Int)->Unit)?=null
  val diagnostics=mutableListOf<JsonObject>()
  val timeouts=mutableListOf<Long>()
  override fun observe(timeoutMs:Long):String {
   val raw=Json.parseObject(fake.observe(timeoutMs))
   if(clicks>0){
    timeouts+=timeoutMs
    val state=snapshots.getOrElse(reads++){snapshots.last()}
    if(state=="TIMEOUT")throw RendererTimeout("synthetic recheck timeout")
    if(state=="INTERRUPTED")throw InterruptedException("synthetic recheck interrupted")
    if(state !in setOf("DETAIL","WRONG")){
     raw.optObject("signals")!!.put("detailHint",false).put("resultsHint",state=="RESULTS").put("dialog",state=="DIALOG").put("dialogCoverage",if(state=="DIALOG")1.0 else 0.0).put("h1","").put("priceCount",0)
     raw.put("elements",JsonArray()).put("items",JsonArray()).put("url","https://"+fake.host+"/search?q=")
     raw.optObject("settle")!!.put("state",if(state=="RESULTS")"BUSY" else "IDLE")
    }
    if(state=="WRONG")raw.put("title","").put("detailText","Industrial metal lathe with precision tooling and unrelated workshop equipment.").put("url","https://"+fake.host+"/item/987654")
    if(state=="HUMAN")raw.optObject("signals")!!.put("passwordFields",1).put("authPhrases",true).put("sparse",true)
    if(state=="CHALLENGE")raw.optObject("signals")!!.put("challengeWidget",true)
    if(state=="OFFSITE")raw.put("host","outside.invalid").put("url","https://outside.invalid/")
    onRead?.invoke(reads)
   }
   return raw.toString()
  }
  override fun act(command:JsonObject,timeoutMs:Long):RendererResult {clicks++;return fake.act(command,timeoutMs)}
  override fun navigate(url:String,timeoutMs:Long)=fake.navigate(url,timeoutMs)
  override fun currentUrl()=fake.currentUrl()
  override fun back(timeoutMs:Long)=fake.back(timeoutMs)
  override fun waitSettle(timeoutMs:Long)=fake.waitSettle(timeoutMs)
  override fun setNetworkMode(mode:String,allowlist:List<String>,commitEndpoints:List<String>)=fake.setNetworkMode(mode,allowlist,commitEndpoints)
  override fun recover()=fake.recover()
  override fun isAlive()=true
 }
 private fun run(states:List<String>,stopAfterRead:Boolean=false,wallMs:Long=3000L,callback:((Int)->Unit)?=null,pageTypeContract:Boolean=false):Pair<Delayed,TaskLedger>{
  val renderer=Delayed(states);val memory=renderer.memory
  val goal=GoalParser.parse("synthetic vehicle",Budget(actions=4,llmCalls=0,wallMs=wallMs)).copy(intent=GoalIntent.LEARN_SITE)
  val ledger=TaskLedger("delayed",goal,renderer.fake.host,renderer.fake.url).apply{lesson="open_item"}
  val engine=BrainEngine(renderer,memory,{null},object:EngineEvents{override fun diagnostic(host:String,kind:String,data:JsonObject){if(kind=="action_outcome")renderer.diagnostics+=com.appgate.brain.diagnostics.PhoneReportExporter.projectEvent(kind,data)!!}},EngineConfig(pacingOverrideMs=0,ambiguousRecheckMs=0,plannerCooldownMs=0))
  renderer.onRead=callback
  if(pageTypeContract){ledger.currentProgram=listOf(Step(StepKind.CLICK,Role.RESULT_ITEM,arg="k1",expect=listOf(Postcondition.PageTypeIs(PageType.DETAIL))));ledger.programSource="planner";ledger.programCapability="open_item";ledger.programPost=listOf(Postcondition.PageTypeIs(PageType.DETAIL))}
  if(stopAfterRead)renderer.onRead={if(it==1)engine.requestStop()}
  engine.runTask(ledger)
  return renderer to ledger
 }
 @Test fun settledLoadingDialogIsObservedBeforeCallingItemOpenAFailure(){
  val(r,l)=run(listOf("DIALOG","DETAIL"))
  assertEquals("must verify actual delayed detail without a second click",TaskStatus.DONE,l.status)
  assertTrue("open_item" in l.successfulSkills);assertEquals(1,r.clicks)
 }
 @Test fun busyResultsThenDialogThenDetailUsesOneClick(){
  val(r,l)=run(listOf("RESULTS","DIALOG","DETAIL"))
  assertEquals(TaskStatus.DONE,l.status);assertTrue("open_item" in l.successfulSkills);assertEquals(1,r.clicks)
 }
 @Test fun permanentDialogIsNotCreditedAndObservationIsBounded(){
  val(r,l)=run(listOf("DIALOG"))
  assertFalse("open_item" in l.successfulSkills);assertNotEquals(TaskStatus.DONE,l.status)
  assertEquals(1,r.clicks);assertTrue(r.reads<=4)
 }
 @Test fun humanBoundaryCannotBeErasedByALaterDetailObservation(){
  for(state in listOf("HUMAN","CHALLENGE"))for(prefix in listOf(emptyList(),listOf("DIALOG"))){
   val(r,l)=run(prefix+listOf(state,"DETAIL"))
   assertEquals("observed human boundary must survive later page changes",TaskStatus.NEED_HUMAN,l.status)
   assertTrue(l.successfulSkills.isEmpty());assertEquals(1,r.clicks);assertEquals(prefix.size+1,r.reads)
  }
 }
 @Test fun offsiteRecheckStopsWithoutAnotherAction(){
  val(r,l)=run(listOf("DIALOG","OFFSITE","DETAIL"))
  assertEquals(TaskStatus.FAILED,l.status);assertTrue(l.successfulSkills.isEmpty());assertEquals(1,r.clicks);assertEquals(2,r.reads)
 }
 @Test fun cancellationDoesNotReadOrActAgain(){
  val(r,l)=run(listOf("DIALOG","DETAIL"),stopAfterRead=true)
  assertTrue(l.successfulSkills.isEmpty());assertEquals(1,r.clicks);assertEquals(1,r.reads)
 }
 @Test fun unreadableRecheckDoesNotPersistAConcreteFailure(){
  val(r,l)=run(listOf("DIALOG","TIMEOUT"))
  assertTrue(l.successfulSkills.isEmpty());assertTrue("unknown delayed outcome must not blacklist the procedure",r.memory.site(r.fake.host).strategyFailures.isEmpty())
  val e=r.diagnostics.last();assertEquals("RECHECK_UNREADABLE",e.optString("verification_reason"));assertEquals("AMBIGUOUS",e.optString("status"));assertEquals(1,e.optObject("counters")!!.optInt("observation_read_failed"));assertEquals(1,e.optObject("counters")!!.optInt("observation_rechecks"));assertEquals(0,e.optObject("counters")!!.optInt("text_expansion_verified"))
 }
 @Test fun interruptionIsNotSwallowedByTheRecheck(){
  val(r,l)=run(listOf("DIALOG","INTERRUPTED"))
  assertEquals(TaskStatus.PAUSED,l.status);assertTrue(r.memory.site(r.fake.host).strategyFailures.isEmpty());assertEquals(1,r.clicks)
 }
 @Test fun delayedUnrelatedDetailDoesNotEarnItemCreditForEitherContract(){
  for(pageTypeContract in listOf(false,true)){
   val(r,l)=run(listOf("DIALOG","WRONG"),pageTypeContract=pageTypeContract)
   assertFalse("unrelated detail is not the requested item", "open_item" in l.successfulSkills)
   assertEquals(0,r.memory.site(r.fake.host).verifiedActions)
   val e=r.diagnostics.last();assertEquals("ITEM_IDENTITY_UNCONFIRMED",e.optString("verification_reason"));assertEquals("AMBIGUOUS",e.optString("status"));assertEquals(0,e.optObject("counters")!!.optInt("observation_read_failed"));assertEquals(0,e.optObject("counters")!!.optInt("text_expansion_verified"))
  }
 }
 @Test fun expiredObservationCannotChangeBindingOrActionStatistics(){
  val(r,l)=run(listOf("DIALOG","DETAIL"),wallMs=1000,callback={if(it==2)Thread.sleep(1100)})
  assertEquals(TaskStatus.BUDGET_EXHAUSTED,l.status);assertTrue(l.successfulSkills.isEmpty())
  assertEquals("expired result must not earn action credit",0,r.memory.site(r.fake.host).verifiedActions)
  assertTrue(r.memory.site(r.fake.host).bindings.isEmpty())
 }
 @Test fun recheckTimeoutIsCappedByRemainingTaskBudget(){
  val(r,_)=run(listOf("RESULTS","DIALOG","DETAIL"),wallMs=1000)
  assertTrue(r.timeouts.drop(1).isNotEmpty());assertTrue("rechecks must not request the ordinary9s timeout",r.timeouts.drop(1).take(2).all{it in 1..1000})
 }
 @Test fun unreadableResultsDoesNotRetryOrWriteNegativeLearning(){
  val(r,l)=run(listOf("RESULTS","TIMEOUT"))
  assertEquals("unknown outcome must not click another listing",1,r.clicks)
  val site=r.memory.site(r.fake.host)
  assertEquals(0,site.failedActions);assertTrue(site.bindings.isEmpty());assertTrue(site.strategyFailures.isEmpty());assertTrue(l.successfulSkills.isEmpty())
 }
 @Test fun nonItemAmbiguousObservationDoesNotUseItemTerminalPolicy(){
  val fake=FakeSite().apply{url="https://$host/search?q=";dialogShown=false}
  var active=false;var reads=0
  val renderer=object:Renderer by fake {
   override fun act(command:JsonObject,timeoutMs:Long):RendererResult {val result=fake.act(command,timeoutMs);active=true;return result}
   override fun observe(timeoutMs:Long):String {
    if(active && ++reads==2)throw RendererTimeout("synthetic non-item recheck")
    val raw=Json.parseObject(fake.observe(timeoutMs))
    if(active && reads==1){raw.optObject("settle")!!.put("state","BUSY");raw.put("items",JsonArray())}
    return raw.toString()
   }
  }
  val ledger=TaskLedger("non-item",GoalParser.parse("Ford",Budget(actions=2,llmCalls=0,wallMs=3000)).copy(intent=GoalIntent.LEARN_SITE),fake.host,fake.url).apply{
   lesson="search";currentProgram=listOf(Step(StepKind.TYPE,Role.SEARCH_BOX,arg="Ford",submit=true,expect=listOf(Postcondition.ResultsChanged)))
   programSource="planner";programCapability="search";programPost=listOf(Postcondition.ResultsChanged)
  }
  BrainEngine(renderer,Memory(InMemoryStorage()),{null},object:EngineEvents{},EngineConfig(pacingOverrideMs=0,ambiguousRecheckMs=0,plannerCooldownMs=0)).runTask(ledger)
  assertFalse("search retains its own policy",ledger.terminalReason.startsWith("item outcome"))
 }
}
