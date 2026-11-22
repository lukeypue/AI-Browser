package com.appgate.brain.engine
import com.appgate.brain.json.*
import com.appgate.brain.memory.*
import com.appgate.brain.model.*
import com.appgate.brain.perception.SpsParser
import com.appgate.brain.planner.*
import com.appgate.brain.profile.SiteProfiles
import com.appgate.brain.skills.BuiltinSkills
import com.appgate.brain.test.FakeSite
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
class TeacherProposalRevisionTest {
 private val now=1_800_000_000_000L
 private val config=EngineConfig(pacingOverrideMs=0,plannerCooldownMs=0,ambiguousRecheckMs=0,idleSleepMs=0)
 private val repaired="""{"steps":[{"kind":"WAIT","optional":true},{"kind":"CLICK","role":"RESULT_ITEM","arg":"${'$'}item","expect":["PAGE_IS_DETAIL"]}],"confidence":0.99}"""
 private val giveUp="""{"steps":[],"give_up":true,"confidence":0.99}"""
 private fun scenario(answers:List<String>,budget:Int?=null,check:(TaskLedger,List<JsonObject>,Memory,FakeSite)->Unit){
  val dir=Files.createTempDirectory("teacher-revision-").toFile()
  try {
   val fake=FakeSite().apply{dialogShown=false;navigate("https://fake.market/search?q=Ford",1000)}
   val page=SpsParser().parse(fake.observe(1000));val memory=Memory(FileBrainStorage(dir)){now};val site=memory.site(fake.host)
   for(item in listOf("k1","k2","k3")){
    val params=mapOf("item" to item)
    FailedStrategies.record(site,FailedStrategies.key(page,"open_item",BuiltinSkills.openItem.body,params),false,now,
     FailedStrategies.approach(page,"open_item",BuiltinSkills.openItem.body,params))
   }
   memory.saveSite(site)
   val requests=mutableListOf<JsonObject>()
   val planner=Planner(object:PlannerClient{
    override val describe="synthetic correcting teacher"
    override fun complete(instructions:String,input:String,schemaName:String,schema:JsonObject,maxOutputTokens:Int):String{
     requests+=Json.parseObject(input);assertTrue("at most two teacher requests",requests.size<=2)
     return answers.getOrElse(requests.lastIndex){answers.last()}
    }
   })
   val generated=Curriculum.nextGoal(site,SiteProfiles.generic(fake.host),0,page,"open_item")
   val goal=generated.copy(rawText="practice",query="Ford",budget=generated.budget.copy(llmCalls=budget?:generated.budget.llmCalls,wallMs=10000))
   val out=BrainEngine(fake,memory,{planner},object:EngineEvents{},config){now}.runTask(TaskLedger("revision",goal,fake.host,fake.url).apply{lesson="open_item"})
   check(out,requests,Memory(FileBrainStorage(dir)){now},fake)
  }finally{dir.deleteRecursively()}
 }
 @Test fun rejectedTeacherReplyGetsSpecificCorrectionAndLearnsThroughActualExecution(){
  scenario(listOf(giveUp,repaired)){out,requests,memory,fake->
   assertEquals("rejection needs a bounded corrective reply in the same lesson",2,requests.size)
   val feedback=requests[1].optObject("task")?.optArray("proposal_rejections")
   assertNotNull("the correcting teacher must receive the rejection reason",feedback)
   assertEquals("GIVE_UP",feedback!!.objects().single().optString("reason"))
   assertEquals(out.notes.joinToString(),TaskStatus.DONE,out.status)
   assertEquals("https://fake.market/item/1",fake.url)
   assertTrue(memory.site(fake.host).curriculum.any{it.id=="open_item"&&it.done})
   assertTrue(memory.skills.all().any{it.origin==SkillOrigin.COMPILED&&"capability:open_item" in it.tags})
   val replay=FakeSite().apply{dialogShown=false;navigate("https://fake.market/search?q=Ford",1000)}
   val again=BrainEngine(replay,memory,{null},object:EngineEvents{},config){now}.runTask(TaskLedger("reloaded",out.goal,replay.host,replay.url).apply{lesson="open_item"})
   assertEquals(again.notes.joinToString(),TaskStatus.DONE,again.status);assertEquals(0,again.llmCalls)
  }
 }
 @Test fun explicitSingleCallBudgetStillStopsWithoutCorrection(){
  scenario(listOf(giveUp,repaired),budget=1){out,requests,_,fake->
   assertEquals(1,requests.size);assertFalse("open_item" in out.successfulSkills);assertTrue(fake.url.contains("/search"))
  }
 }
 @Test fun twoRejectedRepliesNeverEarnLearningOrRunForever(){
  scenario(listOf(giveUp,giveUp)){out,requests,memory,fake->
   assertEquals(2,requests.size);assertFalse("open_item" in out.successfulSkills)
   assertFalse(memory.site(fake.host).curriculum.any{it.id=="open_item"&&it.done});assertTrue(fake.url.contains("/search"))
  }
 }
 @Test fun incompatibleProposalIsExplainedThenAValidAlternativeCanPass(){
  val bad="""{"steps":[{"kind":"SCROLL","arg":"900","expect":["SCROLLED_DOWN"]}],"confidence":0.99}"""
  scenario(listOf(bad,repaired)){out,requests,_,_->
   assertEquals(2,requests.size);assertEquals("INCOMPATIBLE",requests[1].optObject("task")!!.optArray("proposal_rejections")!!.objects().single().optString("reason"))
   assertEquals(TaskStatus.DONE,out.status)
  }
 }
}
