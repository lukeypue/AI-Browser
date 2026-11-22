package com.appgate.brain.lab
import com.appgate.brain.engine.*
import com.appgate.brain.json.*
import com.appgate.brain.memory.*
import com.appgate.brain.model.*
import com.appgate.brain.planner.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
class TeacherRevisionAcquisitionTest {
 private val now=1_800_000_000_000L
 private val config=EngineConfig(pacingOverrideMs=0,plannerCooldownMs=0,ambiguousRecheckMs=0,idleSleepMs=0,maxConsecutiveFailures=8,maxDecisionsWithoutProgress=40)
 private fun goal(id:String,query:String,budget:Int)=Goal(id,GoalIntent.LEARN_SITE,"synthetic search",query,emptyList(),budget=Budget(actions=16,llmCalls=budget,wallMs=10000))
 private fun run(renderer:FrontierScreen.SearchGate,memory:Memory,id:String,query:String,planner:Planner?=null,budget:Int=0):TaskLedger=
  BrainEngine(renderer,memory,{planner},object:EngineEvents{},config){now}.runTask(TaskLedger(id,goal(id,query,budget),renderer.host,renderer.currentUrl()).apply{lesson="search"},EngineMode.TRAIN)
 @Test fun correctiveTeachingAcquiresNecessaryPrerequisiteAndTransfersAfterFileReloadWithoutTeacher(){
  val directory=Files.createTempDirectory("teacher-acquisition-").toFile()
  try {
   val host="teacher-revision.sim.invalid";val query="synthetic-first-query"
   val cold=FrontierScreen.SearchGate(host,0)
   val coldOut=run(cold,Memory(InMemoryStorage()){now},"cold",query)
   assertFalse("cold local approaches cannot solve this fixture",cold.oracle(query));assertFalse("search" in coldOut.successfulSkills)
   val storage=FileBrainStorage(directory);val memory=Memory(storage){now};val training=FrontierScreen.SearchGate(host,0)
   val requests=mutableListOf<JsonObject>()
   val planner=Planner(object:PlannerClient{
    override val describe="scripted corrective teacher"
    override fun complete(instructions:String,input:String,schemaName:String,schema:JsonObject,maxOutputTokens:Int):String{
     requests+=Json.parseObject(input)
     return if(requests.size==1) """{"steps":[{"kind":"SCROLL","arg":"900","expect":["SCROLLED_DOWN"]}],"confidence":0.99}""" else FrontierScreen.referenceProgram()
    }
   })
   val trained=run(training,memory,"training",query,planner,2)
   assertEquals("rejected first teaching reply must receive one correction",2,requests.size)
   assertEquals("INCOMPATIBLE",requests[1].optObject("task")!!.optArray("proposal_rejections")!!.objects().single().optString("reason"))
   assertTrue("success requires the fixture's real committed state",training.oracle(query));assertTrue("search" in trained.successfulSkills)
   assertTrue("teacher must supply the physically necessary tab prerequisite",training.selected)
   assertTrue("several actual approaches were exercised",training.commands.count{it.contains("SEARCH_BOX")}>=2)
   val reloaded=Memory(FileBrainStorage(directory)){now}
   assertTrue(reloaded.skills.all().any{it.origin==SkillOrigin.COMPILED&&it.body.any{s->s.role==Role.TAB}})
   for((variant,newQuery) in listOf(1 to "synthetic-unseen-query",2 to "synthetic-other-query")){
    val heldOut=FrontierScreen.SearchGate(host,variant)
    val out=run(heldOut,Memory(FileBrainStorage(directory)){now},"heldout-$variant",newQuery)
    assertTrue("different layout and query must work after reload without teacher",heldOut.oracle(newQuery))
    assertEquals(TaskStatus.DONE,out.status);assertEquals(0,out.llmCalls)
   }
   val saved=storage.keys("").filterNot{it.startsWith("ledger/")}.associateWith{storage.read(it)!!}
   val ablatedStore=InMemoryStorage();saved.forEach{(key,value)->ablatedStore.write(key,value)}
   val ablated=Memory(ablatedStore){now};ablated.skills.all().filter{it.origin==SkillOrigin.COMPILED}.forEach{ablated.skills.remove(it.id)}
   val without=FrontierScreen.SearchGate(host,3)
   val noSkill=run(without,ablated,"ablated","synthetic-ablated-query")
   assertFalse("removing learned procedures must remove the improvement",without.oracle("synthetic-ablated-query"));assertFalse("search" in noSkill.successfulSkills)
   val noOp=FrontierScreen.SearchGate("noop-revision.sim.invalid",4,noOp=true)
   val negative=run(noOp,Memory(FileBrainStorage(directory)){now},"noop","synthetic-negative-query")
   assertFalse(noOp.oracle("synthetic-negative-query"));assertFalse("search" in negative.successfulSkills)
   val human=FrontierScreen.SearchGate("human-revision.sim.invalid",5,human=true)
   val blocked=run(human,Memory(FileBrainStorage(directory)){now},"human","synthetic-human-query",planner,2)
   assertEquals(TaskStatus.NEED_HUMAN,blocked.status);assertEquals(0,blocked.llmCalls);assertTrue(human.commands.isEmpty())
   assertEquals("evaluation may never call the teacher",2,requests.size)
  }finally{directory.deleteRecursively()}
 }
}
