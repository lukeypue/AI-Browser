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
class TeacherCorrectionRecoveryTest {
 private val now=1_800_000_000_000L
 private val giveUp="""{"steps":[],"give_up":true,"confidence":0.99}"""
 private val repaired="""{"steps":[{"kind":"WAIT","optional":true},{"kind":"CLICK","role":"RESULT_ITEM","arg":"${'$'}item","expect":["PAGE_IS_DETAIL"]}],"confidence":0.99}"""
 private val config=EngineConfig(pacingOverrideMs=0,plannerCooldownMs=0,ambiguousRecheckMs=0,idleSleepMs=0)
 private class ProcessExit:Error()
 private inner class Harness {
  val directory=Files.createTempDirectory("teacher-recovery-").toFile()
  val fake=FakeSite().apply{dialogShown=false;navigate("https://fake.market/search?q=Ford",1000)}
  val storage=FileBrainStorage(directory)
  val memory=Memory(storage){now}
  val page=SpsParser().parse(fake.observe(1000))
  val requests=mutableListOf<JsonObject>()
  var change=""
  val originalHolds:Map<String,StrategyFailure>
  val ledger=TaskLedger("recover",Goal("recover",GoalIntent.LEARN_SITE,"practice","Ford",emptyList(),budget=Budget(actions=10,llmCalls=2,wallMs=10000)),fake.host,fake.url).apply{lesson="open_item"}
  init {
   val site=memory.site(fake.host)
   for(item in listOf("k1","k2","k3")) {
    val params=mapOf("item" to item)
    FailedStrategies.record(site,FailedStrategies.key(page,"open_item",BuiltinSkills.openItem.body,params),false,now,
     FailedStrategies.approach(page,"open_item",BuiltinSkills.openItem.body,params))
   }
   originalHolds=site.strategyFailures.toMap();memory.saveSite(site)
  }
  val renderer=object:Renderer by fake {
   override fun observe(timeoutMs:Long):String {
    val raw=Json.parseObject(fake.observe(timeoutMs))
    if(change=="reorder" || change=="missing") {
     val elements=raw.optArray("elements")!!.objects();val items=raw.optArray("items")!!.objects()
     raw.put("elements",JsonArray(if(change=="missing")elements.filter{it.optString("itemKey")!="k1"}else elements.sortedBy{if(it.optString("itemKey")=="k2")0 else 1}))
     raw.put("items",JsonArray(if(change=="missing")items.filter{it.optString("key")!="k1"}else items.reversed()))
    }
    if(change=="busy")raw.put("settle",JsonObject().put("state","BUSY"))
    return raw.toString()
   }
  }
  fun planner(expectCorrectionSecond:Boolean=true,reply:(Int)->String)=Planner(object:PlannerClient {
   override val describe="scripted recovery teacher"
   override fun complete(instructions:String,input:String,schemaName:String,schema:JsonObject,maxOutputTokens:Int):String {
    requests+=Json.parseObject(input)
    assertTrue("no unbounded third provider request",requests.size<=2)
    val persisted=Memory(storage){now}.loadLedger("recover")!!
    assertEquals("request accounting must already be durable",requests.size,persisted.llmCalls)
    if(requests.size==2 && expectCorrectionSecond){assertTrue(persisted.proposalCorrectionUsed);assertFalse(persisted.proposalCorrectionPending)}
    return reply(requests.size)
   }
  })
  fun run(planner:Planner,task:TaskLedger=ledger,settings:EngineConfig=config)=BrainEngine(renderer,memory,{planner},object:EngineEvents{},settings){now}.runTask(task)
  fun unchangedHolds(){originalHolds.forEach{(key,value)->assertEquals(value,Memory(storage){now}.site(fake.host).strategyFailures[key])}}
  fun close(){directory.deleteRecursively()}
 }
 private fun scenario(test:(Harness)->Unit){val h=Harness();try{test(h)}finally{h.close()}}
 @Test fun originalTargetSurvivesReorderingBetweenTeacherReplies()=scenario{h->
  val out=h.run(h.planner{index->if(index==1){h.change="reorder";giveUp}else repaired})
  assertEquals(2,h.requests.size);assertEquals(TaskStatus.DONE,out.status);assertEquals("https://fake.market/item/1",h.fake.url);h.unchangedHolds()
 }
 @Test fun missingOriginalTargetStopsWithoutRetargetingOrAnotherRequest()=scenario{h->
  val out=h.run(h.planner{h.change="missing";giveUp})
  assertEquals(1,h.requests.size);assertTrue(h.fake.url.contains("/search"));assertFalse("open_item" in out.successfulSkills);h.unchangedHolds()
 }
 @Test fun interveningHumanStateNeverReachesTheTeacher()=scenario{h->
  val out=h.run(h.planner{h.fake.loginWall=true;giveUp})
  assertEquals(1,h.requests.size);assertEquals(TaskStatus.NEED_HUMAN,out.status);assertFalse("open_item" in out.successfulSkills)
 }
 @Test fun higherConfidenceAndRewordingCannotReplayTheSameRejectedProcedure()=scenario{h->
  val low=repaired.replace("0.99","0.2")
  val out=h.run(h.planner{index->if(index==1)low else repaired.replace("\"${'$'}item\"","\"k1\"")})
  assertEquals(2,h.requests.size);assertFalse("open_item" in out.successfulSkills);assertTrue(h.fake.url.contains("/search"))
  assertEquals(ProposalRejectionReason.DUPLICATE,Memory(h.storage){now}.loadLedger("recover")!!.proposalRejections.last().reason);h.unchangedHolds()
 }
 @Test fun heldTeacherProposalGetsCorrectionBeforeTerminalAdmission()=scenario{h->
  val held="""{"steps":[{"kind":"CLICK","role":"RESULT_ITEM","arg":"${'$'}item","expect":["PAGE_IS_DETAIL"]}],"confidence":0.99}"""
  val out=h.run(h.planner{index->if(index==1)held else repaired})
  assertEquals(2,h.requests.size);assertEquals(TaskStatus.DONE,out.status)
  assertEquals("HELD_PROCEDURE",h.requests[1].optObject("task")!!.optArray("proposal_rejections")!!.objects().single().optString("reason"));h.unchangedHolds()
 }
 @Test fun transportRefusalOnCorrectionStopsWithoutThirdRequest()=scenario{h->
  val out=h.run(h.planner{index->if(index==1)giveUp else throw PlannerRefused("synthetic refusal")})
  assertEquals(2,h.requests.size);assertFalse("open_item" in out.successfulSkills);assertTrue(h.fake.url.contains("/search"));h.unchangedHolds()
 }
 @Test fun crashAfterSecondReservationCannotMintAnotherRequestOnReload()=scenario{h->
  val planner=h.planner{index->if(index==1)giveUp else throw ProcessExit()}
  try{h.run(planner);fail("simulated process exit required")}catch(_:ProcessExit){}
  val saved=Memory(h.storage){now}.loadLedger("recover")!!;assertEquals(2,saved.llmCalls);assertTrue(saved.proposalCorrectionUsed)
  val resumed=h.run(planner,saved)
  assertEquals(2,h.requests.size);assertFalse("open_item" in resumed.successfulSkills);h.unchangedHolds()
 }
 @Test fun anExistingOlderRepairCooldownIsNeverBypassed()=scenario{h->
  val site=h.memory.site(h.fake.host);val key=FailedStrategies.key(h.page,"open_item")
  repeat(2){FailedStrategies.record(site,key,false,now)};h.memory.saveSite(site)
  val out=h.run(h.planner{repaired});assertTrue(h.requests.isEmpty());assertFalse("open_item" in out.successfulSkills)
 }
 @Test fun ownReservationMayUseExactlyOneCorrectionEvenWhenItCreatesCooldown()=scenario{h->
  val site=h.memory.site(h.fake.host);val key=FailedStrategies.key(h.page,"open_item")
  FailedStrategies.record(site,key,false,now);h.memory.saveSite(site)
  val out=h.run(h.planner{index->if(index==1)giveUp else repaired})
  assertEquals(2,h.requests.size);assertEquals(TaskStatus.DONE,out.status);h.unchangedHolds()
 }
 @Test fun anotherReservationDuringTransportCannotBeMistakenForOurOwn()=scenario{h->
  val out=h.run(h.planner{index->
   if(index==1){val site=h.memory.site(h.fake.host);FailedStrategies.record(site,FailedStrategies.key(h.page,"open_item"),false,now);h.memory.saveSite(site);giveUp}else repaired
  })
  assertEquals("an intervening reservation invalidates the correction exception",1,h.requests.size)
  assertFalse("open_item" in out.successfulSkills);h.unchangedHolds()
 }
 @Test fun configuredSameStateLimitStillApplies()=scenario{h->
  val out=h.run(h.planner{giveUp},settings=config.copy(maxPlannerCallsSameState=1))
  assertEquals(1,h.requests.size);assertFalse("open_item" in out.successfulSkills)
 }
 @Test fun merelyRemovingGiveUpCannotTurnTheSameProposalIntoADifferentProcedure()=scenario{h->
  val out=h.run(h.planner{index->if(index==1)Json.parseObject(repaired).put("give_up",true).toString()else repaired})
  assertEquals(2,h.requests.size);assertFalse("open_item" in out.successfulSkills);assertTrue(h.fake.url.contains("/search"))
 }

 @Test fun interveningBusyPageDoesNotConsumeAnotherTeacherRequest()=scenario{h->
  val out=h.run(h.planner{h.change="busy";giveUp})
  assertEquals(1,h.requests.size);assertFalse("open_item" in out.successfulSkills);assertTrue(h.fake.url.contains("/search"))
 }
 @Test fun crashAfterFirstRequestStillLeavesOnlyOneRequestOnReload()=scenario{h->
  val planner=h.planner(expectCorrectionSecond=false){index->if(index==1)throw ProcessExit()else repaired}
  try{h.run(planner);fail("simulated process exit required")}catch(_:ProcessExit){}
  val saved=Memory(h.storage){now}.loadLedger("recover")!!;assertEquals(1,saved.llmCalls)
  val resumed=h.run(planner,saved)
  assertEquals(2,h.requests.size);assertTrue(resumed.llmCalls<=2);h.unchangedHolds()
 }

}
