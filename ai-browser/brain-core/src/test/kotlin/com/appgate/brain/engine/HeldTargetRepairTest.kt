package com.appgate.brain.engine
import com.appgate.brain.json.*
import com.appgate.brain.memory.*
import com.appgate.brain.model.*
import com.appgate.brain.perception.SpsParser
import com.appgate.brain.planner.*
import com.appgate.brain.skills.BuiltinSkills
import com.appgate.brain.test.FakeSite
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
class HeldTargetRepairTest {
 private val now=1_800_000_000_000L
 private val config=EngineConfig(pacingOverrideMs=0,plannerCooldownMs=0,ambiguousRecheckMs=0,idleSleepMs=0)
 private val repair="""{"steps":[{"kind":"WAIT","optional":true},{"kind":"CLICK","role":"RESULT_ITEM","arg":"${'$'}item","expect":["PAGE_IS_DETAIL"]}],"confidence":0.99}"""
 private fun scenario(answer:String, extraFirst:Boolean=false, onlyFirstHeld:Boolean=false, pageChange:String="", plannerBudget:Int=1, noHolds:Boolean=false, requireItemInstruction:Boolean=false, requireCompiled:Boolean=false, check:(TaskLedger,FakeSite,JsonObject,SiteModel,Map<String,StrategyFailure>)->Unit) {
  val dir=Files.createTempDirectory("held-target-repair-").toFile()
  try {
   val fake=FakeSite().apply{dialogShown=false;navigate("https://fake.market/search?q=Ford",1000)}
   val page=SpsParser().parse(fake.observe(1000));val store=FileBrainStorage(dir);val memory=Memory(store){now};val site=memory.site(fake.host)
   val keys=if(noHolds)emptyList() else if(onlyFirstHeld)listOf("k1") else listOf("k1","k2","k3")
   fun hold(item:String,body:List<Step>){val params=mapOf("item" to item);FailedStrategies.record(site,FailedStrategies.key(page,"open_item",body,params),false,now,FailedStrategies.approach(page,"open_item",body,params))}
   keys.forEach{hold(it,BuiltinSkills.openItem.body)}
   if(extraFirst)hold("k1",listOf(Step(StepKind.WAIT,arg="42",optional=true))+BuiltinSkills.openItem.body)
   if(extraFirst)hold("k2",listOf(Step(StepKind.WAIT,arg="84",optional=true))+BuiltinSkills.openItem.body)
   val before=site.strategyFailures.toMap();memory.saveSite(site)
   var capturedInput=JsonObject()
   var teacherCalled=false;var itemAttempted=false
   val renderer=object:Renderer by fake {
    override fun observe(timeoutMs:Long):String {
     if(itemAttempted)fake.log += "after-item-observation"
     val raw=Json.parseObject(fake.observe(timeoutMs))
     if(teacherCalled && pageChange in listOf("reorder","missing")) {
      val elements=raw.optArray("elements")!!.objects()
      raw.put("elements",JsonArray(if(pageChange=="missing")elements.filter{it.optString("itemKey")!="k1"} else elements.sortedBy{if(it.optString("itemKey")=="k2")0 else 1}))
      if(pageChange=="missing")raw.optArray("items")?.let{raw.put("items",JsonArray(it.objects().filter{row->row.optString("key")!="k1"}))}
     }
     return raw.toString()
    }
    override fun act(command:JsonObject,timeoutMs:Long):RendererResult {
     if(command.optString("cmd")=="click") {
      val element=Json.parseObject(fake.observe(timeoutMs)).optArray("elements")!!.objects().firstOrNull{it.optString("id")==command.optString("id")}
      val key=element?.optString("itemKey").orEmpty()
      if(key.isNotBlank()){itemAttempted=true;fake.log += "item-attempt:$key"}
     }
     if(pageChange=="no-effect" && command.optString("cmd")=="click") {
      val element=Json.parseObject(fake.observe(timeoutMs)).optArray("elements")!!.objects().firstOrNull{it.optString("id")==command.optString("id")}
      if(element?.optString("itemKey")=="k1")return RendererResult(true)
     }
     return fake.act(command,timeoutMs)
    }
   }
   val planner=Planner(object:PlannerClient{
    override val describe="synthetic target repair"
    override fun complete(instructions:String,input:String,schemaName:String,schema:JsonObject,maxOutputTokens:Int):String{if(requireItemInstruction)assertTrue("teacher needs the selected-item parameter contract",instructions.contains("arg \"${'$'}item\""));capturedInput=Json.parseObject(input);teacherCalled=true;return answer}
   })
   val restored=Memory(store){now}
   val goal=Goal("repair",GoalIntent.LEARN_SITE,"practice","Ford",emptyList(),budget=Budget(actions=10,llmCalls=plannerBudget,wallMs=10000))
   val out=BrainEngine(renderer,restored,{planner},object:EngineEvents{
    override fun diagnostic(host:String,kind:String,data:JsonObject){if(kind=="planner_outcome")fake.log += "planner-outcome:"+data.optString("planner_outcome")}
   },config){now}.runTask(TaskLedger("repair",goal,fake.host,fake.url).apply{lesson="open_item"})
   if(pageChange=="no-effect" && !noHolds) {
    val failure=restored.site(fake.host).strategyFailures.values.single{row->row.feedback?.steps?.firstOrNull()?.motion==1500}
    assertTrue(failure.count>0);assertEquals(failure,Memory(store){now}.site(fake.host).strategyFailures[failure.key])
   }
   if(requireCompiled) {
    val compiled=Memory(store){now}.skills.all().filter{it.origin==SkillOrigin.COMPILED && "capability:open_item" in it.tags}
    assertTrue("verified repair must save a reusable skill",compiled.isNotEmpty())
    assertTrue(compiled.all{!it.toJson().toString().contains("k1") && !it.toJson().toString().contains("Ford")})
    val warm=FakeSite().apply{dialogShown=false;navigate("https://fake.market/search?q=Ford",1000)}
    val replay=BrainEngine(warm,Memory(store){now},{null},object:EngineEvents{},config){now}.runTask(TaskLedger("warm",goal,warm.host,warm.url).apply{lesson="open_item"})
    assertEquals(replay.notes.joinToString(),TaskStatus.DONE,replay.status);assertEquals(0,replay.llmCalls)
    assertEquals("https://fake.market/item/1",warm.url)
   }
   check(out,fake,capturedInput,restored.site(fake.host),before)
  } finally {dir.deleteRecursively()}
 }
 @Test fun heldTargetFeedbackSurvivesReloadAndContainsOnlySelectedItemApproaches(){
  scenario("""{"steps":[],"give_up":true,"confidence":0.99}""",extraFirst=true){out,fake,input,site,before->
   val held=input.optObject("task")?.optArray("held_approaches")
   assertEquals("selected item must retain both approaches",2,held?.objects()?.size)
   assertTrue(held!!.objects().any{it.optArray("steps")!!.objects().any{s->s.optString("kind")=="WAIT"}})
   val waits=held.objects().flatMap{it.optArray("steps")!!.objects()}.filter{it.optString("kind")=="WAIT"}
   assertEquals(1,waits.size);assertEquals(42,waits.single().optInt("motion"))
   assertFalse(held.toString().contains("k1"));assertFalse(held.toString().contains("Ford"))
   before.forEach{(key,value)->assertEquals(value,site.strategyFailures[key])}
   assertFalse("open_item" in out.successfulSkills);assertTrue(fake.url.contains("/search"));assertNull(out.currentItem)
  }
 }
 @Test fun distinctTeacherRepairBindsSelectedItemAndVerifiesRealDetailTransition(){
  scenario(repair,requireCompiled=true){out,fake,_,site,before->
   assertEquals(out.notes.joinToString()+out.steps.toString(),TaskStatus.DONE,out.status)
   assertEquals("https://fake.market/item/1",fake.url);assertTrue("open_item" in out.successfulSkills);assertNull(out.currentItem)
   before.forEach{(key,value)->assertEquals(value,site.strategyFailures[key])}
  }
 }
 @Test fun identicalTeacherRepairRemainsHeld(){
  scenario("""{"steps":[{"kind":"CLICK","role":"RESULT_ITEM","arg":"${'$'}item","expect":["PAGE_IS_DETAIL"]}],"confidence":0.99}"""){out,fake,_,_,_->
   assertTrue(fake.url.contains("/search"));assertFalse("open_item" in out.successfulSkills);assertTrue("planner-outcome:ACCEPTED_HELD" in fake.log)
  }
 }
 @Test fun unheldAlternativeStillRunsWithoutTeacher(){
  scenario("{}",onlyFirstHeld=true){out,fake,input,_,_->assertEquals(TaskStatus.DONE,out.status);assertEquals("https://fake.market/item/2",fake.url);assertTrue(input.keys.isEmpty())}
 }
 @Test fun teacherCannotSwitchTheSelectedItemOrOmitItsBinding(){
  for(arg in listOf("\"k2\"","null")) {
   val answer="""{"steps":[{"kind":"WAIT","optional":true},{"kind":"CLICK","role":"RESULT_ITEM","arg":$arg,"expect":["PAGE_IS_DETAIL"]}],"confidence":0.99}"""
   scenario(answer){out,fake,_,_,_->assertTrue("target-changing teacher must not click: $arg",fake.url.contains("/search"));assertTrue("planner-outcome:INCOMPATIBLE" in fake.log);assertFalse("open_item" in out.successfulSkills)}
  }
 }

 @Test fun selectedTargetSurvivesReorderingBeforeExecution(){
  scenario(repair,pageChange="reorder"){out,fake,_,_,_->assertEquals(TaskStatus.DONE,out.status);assertEquals("https://fake.market/item/1",fake.url)}
 }
 @Test fun absentOrUnverifiedSelectedTargetNeverFallsBackToAnotherItem(){
  for(change in listOf("missing","no-effect"))scenario(repair,pageChange=change){out,fake,_,_,_->
   assertTrue("must not click another item: $change",fake.url.contains("/search"));assertFalse("open_item" in out.successfulSkills)
   val attempts=fake.log.filter{it.startsWith("item-attempt:")}
   if(change=="missing")assertTrue(attempts.isEmpty()) else {
    assertTrue(attempts.isNotEmpty());assertTrue(attempts.all{it=="item-attempt:k1"});assertTrue("after-item-observation" in fake.log)
   }
  }
 }

 @Test fun selectedItemContractReachesTheActualTeacherRequest(){
  scenario(repair,requireItemInstruction=true){out,_,_,_,_->assertEquals(TaskStatus.DONE,out.status)}
 }
 @Test fun selectedItemRepairRejectsUnrelatedNavigationAndOptionalWrongTarget(){
  val prefixes=listOf(
   """{"kind":"NAVIGATE","arg":"https://fake.market/item/2","optional":true},""",
   """{"kind":"CLICK","role":"RESULT_ITEM","arg":"k2","optional":true},"""
  )
  for(prefix in prefixes)scenario("""{"steps":[$prefix{"kind":"CLICK","role":"RESULT_ITEM","arg":"${'$'}item","expect":["PAGE_IS_DETAIL"]}],"confidence":0.99}"""){out,fake,_,_,_->
   assertTrue(fake.url.contains("/search"));assertFalse("open_item" in out.successfulSkills);assertTrue("planner-outcome:INCOMPATIBLE" in fake.log)
  }
 }
 @Test fun failedRepairCannotLoseItsTargetOnTheNextTeacherOpportunity(){
  scenario(repair,pageChange="no-effect",plannerBudget=2){out,fake,_,_,_->
   assertTrue(fake.url.contains("/search"));assertFalse("open_item" in out.successfulSkills)
   assertEquals(2,out.llmCalls);assertEquals(listOf("item-attempt:k1"),fake.log.filter{it.startsWith("item-attempt:")})
   assertTrue("planner-outcome:ACCEPTED_HELD" in fake.log)
  }
 }

 @Test fun failedBuiltinReselectsALiveTargetBeforeAskingForRepair(){
  scenario(repair,pageChange="no-effect",plannerBudget=2,noHolds=true){out,fake,_,_,_->
   assertEquals(TaskStatus.DONE,out.status);assertEquals("https://fake.market/item/2",fake.url)
   assertEquals("the repair request must bind the newly selected live target",1,out.llmCalls)
  }
 }

 @Test fun itemRepairRejectsNoncompilableWaitsAndGenericCloseActions(){
  val prefixes=listOf(
   """{"kind":"WAIT","optional":false},""",
   """{"kind":"WAIT","arg":"5","optional":true},""",
   """{"kind":"CLICK","role":"CLOSE","optional":true},"""
  )
  for(prefix in prefixes)scenario("""{"steps":[$prefix{"kind":"CLICK","role":"RESULT_ITEM","arg":"${'$'}item","expect":["PAGE_IS_DETAIL"]}],"confidence":0.99}"""){out,fake,_,_,_->
   assertTrue(fake.url.contains("/search"));assertFalse("open_item" in out.successfulSkills);assertTrue("planner-outcome:INCOMPATIBLE" in fake.log)
  }
 }

}
