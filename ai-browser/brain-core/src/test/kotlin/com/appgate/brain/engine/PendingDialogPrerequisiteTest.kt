package com.appgate.brain.engine

import com.appgate.brain.goal.GoalParser
import com.appgate.brain.json.*
import com.appgate.brain.memory.*
import com.appgate.brain.model.*
import com.appgate.brain.perception.SpsParser
import com.appgate.brain.test.FakeSite
import org.junit.Assert.*
import org.junit.Test

/** Synthetic regression: phone AFTER-page aggregates alone do not establish this sequence. */
class PendingDialogPrerequisiteTest {
 private fun fixture():Pair<FakeSite,Renderer> {
  val fake=FakeSite().apply{url="https://$host/search?q=";query="";dialogShown=true}
  val renderer=object:Renderer by fake {
   override fun observe(timeoutMs:Long):String {
    val raw=Json.parseObject(fake.observe(timeoutMs))
    // One visible background listing survives a separately observed ordinary overlay.
    raw.put("items",JsonArray(raw.optArray("items")!!.toList().take(1)))
    raw.put("elements",JsonArray(raw.optArray("elements")!!.objects().filter{!it.has("itemKey")||it.optString("itemKey")=="k1"}))
    return raw.toString()
   }
  }
  return fake to renderer
 }
 @Test fun resumedListingProgramMustDismissOrdinaryDialogBeforeClickingBackgroundListing() {
  val(fake,renderer)=fixture()
  val before=SpsParser().parse(renderer.observe(1000),1000)
  assertEquals(PageType.DIALOG,before.pageType)
  assertTrue(before.dialogOpen);assertFalse(before.isHumanOnly)
  assertTrue(before.has(Role.CLOSE));assertFalse(LearningOpportunities.dialogHoldsFilters(before))
  assertEquals("dismiss_dialog",LearningOpportunities.target(before,"expand_description")!!.skillId)
  val goal=GoalParser.parse("synthetic vehicle",Budget(actions=3,wallMs=2000)).copy(intent=GoalIntent.LEARN_SITE)
  val ledger=TaskLedger("synthetic-pending-modal",goal,fake.host,fake.url).apply{
   lesson="expand_description";actions=1;lastCheckpointUrl=fake.url
   currentProgram=listOf(Step(StepKind.CLICK,Role.RESULT_ITEM,arg="k1",expect=listOf(Postcondition.DetailMatches(null))))
   programCapability="open_item";programSource="skill:open_item";programPost=listOf(Postcondition.PageTypeIs(PageType.DETAIL))
  }
  BrainEngine(renderer,Memory(InMemoryStorage()),{null},object:EngineEvents{},EngineConfig(pacingOverrideMs=0L,ambiguousRecheckMs=0L,plannerCooldownMs=0L)).runTask(ledger)
  val first=fake.log.firstOrNull{it.startsWith("act ")}.orEmpty()
  assertTrue("expected dismissal first; actual synthetic trace="+fake.log.joinToString(" | "),first.startsWith("act dismiss "))
 }
 @Test fun unrelatedControlAndCloserCountChurnDoesNotReopenSameExplicitTargetFailure() {
  val(_,renderer)=fixture()
  val page=SpsParser().parse(renderer.observe(1000),1000)
  val steps=listOf(Step(StepKind.CLICK,Role.RESULT_ITEM,arg="k1"))
  val key=FailedStrategies.key(page,"open_item",steps,mapOf("item" to "k1"))
  val changed=page.copy(affordances=page.affordances+(1..36).map{Affordance("unrelated-$it",Role.GENERIC_BUTTON,tag="button")}+Affordance("extra-close",Role.CLOSE,tag="button",regionRole=RegionRole.DIALOG))
  assertEquals(key,FailedStrategies.key(changed,"open_item",steps,mapOf("item" to "k1")))
  val storage=InMemoryStorage();val memory=Memory(storage)
  FailedStrategies.record(memory.site(page.host),key,false,1000);memory.saveSite(memory.site(page.host))
  assertFalse(FailedStrategies.allowed(Memory(storage).site(page.host),FailedStrategies.key(changed,"open_item",steps,mapOf("item" to "k1")),2000))
 }

 private fun resumed(fake:FakeSite,step:Step=Step(StepKind.CLICK,Role.RESULT_ITEM,arg="k1",expect=listOf(Postcondition.DetailMatches(null))),budget:Int=2)=
  TaskLedger("synthetic-guard",GoalParser.parse("synthetic vehicle",Budget(actions=budget,wallMs=2000)).copy(intent=GoalIntent.LEARN_SITE),fake.host,fake.url).apply{
   lesson="expand_description";actions=1;lastCheckpointUrl=fake.url;currentProgram=listOf(step)
   programCapability="open_item";programSource="skill:open_item";programPost=listOf(Postcondition.PageTypeIs(PageType.DETAIL))
  }
 private fun engine(renderer:Renderer,memory:Memory=Memory(InMemoryStorage()))=
  BrainEngine(renderer,memory,{null},object:EngineEvents{},EngineConfig(pacingOverrideMs=0L,ambiguousRecheckMs=0L,plannerCooldownMs=0L))
 @Test fun noOverlayDoesNotInsertDismissal() {
  val(fake,renderer)=fixture();fake.dialogShown=false
  engine(renderer).runTask(resumed(fake))
  assertTrue(fake.log.first{it.startsWith("act ")}.startsWith("act click "))
 }
 @Test fun currentDismissalProgramIsNotReplacedWithAPrerequisite() {
  val(fake,renderer)=fixture()
  val ledger=resumed(fake,Step(StepKind.DISMISS,Role.CLOSE,expect=listOf(Postcondition.DialogClosed)))
  engine(renderer).runTask(ledger)
  assertTrue(fake.log.first{it.startsWith("act ")}.startsWith("act dismiss "))
  assertTrue(ledger.steps.none{it.source=="prerequisite:dismiss_dialog"})
 }
 @Test fun detailModalAndMessageComposerAreNotDismissedBeforeBack() {
  for(composer in listOf(false,true)){
   val(fake,renderer)=fixture();fake.url="https://"+fake.host+"/item/1";fake.composerOpen=composer
   val page=SpsParser().parse(renderer.observe(1000),1000)
   assertEquals(if(composer)PageType.MESSAGES else PageType.DETAIL,page.pageType)
   val ledger=resumed(fake,Step(StepKind.BACK))
   engine(renderer).runTask(ledger)
   assertTrue(fake.log.contains("back"));assertTrue(ledger.steps.none{it.source=="prerequisite:dismiss_dialog"})
  }
 }
 @Test fun filterWorkspaceIsNotDismissedBeforeItsProgramStep() {
  val(fake,base)=fixture()
  val renderer=object:Renderer by base{
   override fun observe(timeoutMs:Long):String {
    val raw=Json.parseObject(base.observe(timeoutMs))
    val elements=raw.optArray("elements")!!
    elements.objects().filter{it.optString("tag")=="select"}.forEach{it.put("region","r3").put("inDialog",true)}
    elements.add(Json.obj("id" to "make-dialog","tag" to "select","name" to "Make","visible" to true,"enabled" to true,"region" to "r3","inDialog" to true,"choices" to listOf("Ford","Toyota")))
    return raw.toString()
   }
  }
  val page=SpsParser().parse(renderer.observe(1000),1000)
  assertEquals(PageType.FACET_PANEL,page.pageType);assertTrue(LearningOpportunities.dialogHoldsFilters(page))
  val ledger=resumed(fake,Step(StepKind.SCROLL))
  engine(renderer).runTask(ledger)
  assertTrue(fake.log.first{it.startsWith("act ")}.startsWith("act scroll "))
  assertTrue(ledger.steps.none{it.source=="prerequisite:dismiss_dialog"})
 }
 @Test fun humanOnlyPagesDoNotTriggerDismissal() {
  for(challenge in listOf(false,true)){
   val(fake,base)=fixture();fake.loginWall=!challenge
   val renderer=object:Renderer by base{
    override fun observe(timeoutMs:Long):String {
     val raw=Json.parseObject(base.observe(timeoutMs))
     if(challenge)raw.optObject("signals")!!.put("challengeWidget",true)
     return raw.toString()
    }
   }
   val ledger=resumed(fake)
   engine(renderer).runTask(ledger)
   assertEquals(TaskStatus.NEED_HUMAN,ledger.status);assertFalse(fake.log.any{it.startsWith("act ")})
  }
 }
 @Test fun failedClosureStopsOncePreservesProgramAndHoldsOnlyDismissal() {
  val(fake,base)=fixture();val memory=Memory(InMemoryStorage());var attempts=0
  val ledger=resumed(fake,budget=10);val original=ledger.currentProgram;val originalPost=ledger.programPost
  val page=SpsParser().parse(base.observe(1000),1000)
  val renderer=object:Renderer by base{
   override fun act(command:JsonObject,timeoutMs:Long):RendererResult {
    if(command.optString("cmd")=="dismiss"){
     attempts++;assertEquals(original,ledger.currentProgram);assertEquals(0,ledger.cursor)
     assertEquals("skill:open_item",ledger.programSource);assertEquals(originalPost,ledger.programPost)
     return RendererResult(true,"synthetic overlay did not close")
    }
    return base.act(command,timeoutMs)
   }
  }
  engine(renderer,memory).runTask(ledger)
  assertEquals(1,attempts);assertEquals(TaskStatus.PARTIAL,ledger.status)
  assertEquals(original,ledger.currentProgram);assertEquals(0,ledger.cursor);assertEquals(originalPost,ledger.programPost)
  assertTrue(ledger.programVerifiedSteps.isEmpty());assertTrue(ledger.successfulSkills.isEmpty())
  assertEquals(2,ledger.actions)
  val close=listOf(Step(StepKind.DISMISS,Role.CLOSE,expect=listOf(Postcondition.DialogClosed)))
  assertFalse(FailedStrategies.allowed(memory.site(fake.host),FailedStrategies.key(page,"dismiss_dialog",close),2000))
  assertTrue(FailedStrategies.allowed(memory.site(fake.host),FailedStrategies.key(page,"open_item",original),2000))
  engine(renderer,memory).runTask(resumed(fake,budget=10))
  assertEquals("unchanged failed close must not be replayed",1,attempts)
 }
 @Test fun exhaustedOriginalActionBudgetDoesNotDismiss() {
  val(fake,renderer)=fixture();val ledger=resumed(fake,budget=1)
  engine(renderer).runTask(ledger)
  assertFalse(fake.log.any{it.startsWith("act ")});assertEquals(TaskStatus.BUDGET_EXHAUSTED,ledger.status)
 }

 @Test fun humanBoundaryObservedAfterDismissalStopsEvenIfNextReadWouldClear() {
  for(challenge in listOf(false,true)){
   val(fake,base)=fixture();val memory=Memory(InMemoryStorage());var nextBoundary=false;var humanSignals=0
   val ledger=resumed(fake,budget=5);val original=ledger.currentProgram
   val renderer=object:Renderer by base{
    override fun act(command:JsonObject,timeoutMs:Long):RendererResult {
     val result=base.act(command,timeoutMs)
     if(command.optString("cmd")=="dismiss")nextBoundary=true
     return result
    }
    override fun observe(timeoutMs:Long):String {
     if(!nextBoundary)return base.observe(timeoutMs)
     nextBoundary=false;fake.loginWall=!challenge
     val raw=Json.parseObject(base.observe(timeoutMs));fake.loginWall=false
     if(challenge)raw.optObject("signals")!!.put("challengeWidget",true)
     return raw.toString()
    }
   }
   val events=object:EngineEvents{override fun needHuman(ledger:TaskLedger,reason:String,url:String){humanSignals++}}
   BrainEngine(renderer,memory,{null},events,EngineConfig(pacingOverrideMs=0L,ambiguousRecheckMs=0L,plannerCooldownMs=0L)).runTask(ledger)
   assertEquals("the already observed boundary must not be lost on a later read",TaskStatus.NEED_HUMAN,ledger.status)
   assertEquals(1,humanSignals);assertEquals(if(challenge)1 else 0,memory.site(fake.host).challengesToday)
   assertEquals(original,ledger.currentProgram);assertEquals(0,ledger.cursor);assertTrue(ledger.programVerifiedSteps.isEmpty())
   assertFalse(fake.log.any{it.startsWith("act click ")})
  }
 }
 @Test fun offsiteAfterDismissalStopsWithoutRunningOrQuarantiningOriginalProgram() {
  val(fake,base)=fixture();var offsite=false;val memory=Memory(InMemoryStorage())
  val ledger=resumed(fake,budget=5);val original=ledger.currentProgram
  val renderer=object:Renderer by base{
   override fun act(command:JsonObject,timeoutMs:Long):RendererResult {
    val result=base.act(command,timeoutMs);if(command.optString("cmd")=="dismiss")offsite=true;return result
   }
   override fun observe(timeoutMs:Long):String {
    val raw=Json.parseObject(base.observe(timeoutMs))
    if(offsite)raw.put("host","other.fixture.invalid").put("url","https://other.fixture.invalid/")
    return raw.toString()
   }
  }
  engine(renderer,memory).runTask(ledger)
  assertEquals(TaskStatus.FAILED,ledger.status);assertEquals("left task site during training",ledger.terminalReason)
  assertEquals(original,ledger.currentProgram);assertFalse(fake.log.any{it.startsWith("act click ")})
  assertTrue(memory.site(fake.host).strategyFailures.isEmpty());assertTrue(ledger.successfulSkills.isEmpty())
 }
}
