package com.appgate.brain.lab

import com.appgate.brain.engine.*
import com.appgate.brain.goal.GoalParser
import com.appgate.brain.json.*
import com.appgate.brain.memory.*
import com.appgate.brain.model.*
import com.appgate.brain.perception.SpsParser
import java.io.File
import java.net.URI
import java.security.MessageDigest

/** Browser-generated synthetic observations; physical truth never enters the student observation. */
class DialogClinic {
 private class Replay(val fixture:JsonObject):Renderer {
  private var state=fixture.optObject("before")!!
  var physicallyClosed=fixture.optBoolean("initial_closed");private set
  var wrongTargets=0;private set
  var unsafeActions=0;private set
  private var train=false
  override fun observe(timeoutMs:Long)=state.toString()
  override fun currentUrl()=state.optString("url")
  override fun isAlive()=true
  override fun recover()=RendererResult(true)
  override fun setNetworkMode(mode:String,allowlist:List<String>,commitEndpoints:List<String>):RendererResult {train=mode=="TRAIN";return RendererResult(train)}
  override fun navigate(url:String,timeoutMs:Long)=RendererResult(url==currentUrl())
  override fun back(timeoutMs:Long)=RendererResult(false,"no recorded back transition")
  override fun waitSettle(timeoutMs:Long)=RendererResult(true,"recorded settled browser state")
  override fun act(command:JsonObject,timeoutMs:Long):RendererResult {
   if(!train || fixture.optString("expected")=="HUMAN"){unsafeActions++;return RendererResult(false,"interlock")}
   val t=fixture.optArray("transitions")!!.objects().firstOrNull{it.optString("id")==command.optString("id")}
   if(t==null || command.optString("cmd") !in setOf("click","dismiss")){wrongTargets++;return RendererResult(false,"unrecorded target")}
   if(!t.optBoolean("inside"))wrongTargets++
   state=t.optObject("after")!!;physicallyClosed=t.optBoolean("closed")
   return RendererResult(t.optBoolean("ok"))
  }
 }
 private data class Run(val row:JsonObject,val snapshot:Map<String,String>)
 private fun run(f:JsonObject,snapshot:Map<String,String>,demonstrate:Boolean):Run {
  val store=InMemoryStorage().apply{snapshot.forEach{(k,v)->write(k,v)}}
  val memory=Memory(store){SimulationTrainer.NOW};val renderer=Replay(f)
  val goal=GoalParser.parse("mountain bike",Budget(actions=6,llmCalls=0,wallMs=3000)).copy(intent=GoalIntent.LEARN_SITE)
  val task=TaskLedger("clinic-"+f.optString("id"),goal,URI(renderer.currentUrl()).host,renderer.currentUrl()).apply{lesson="dismiss_dialog"}
  val page=SpsParser().parse(renderer.observe(1000))
  if(demonstrate && !page.isHumanOnly && LearningOpportunities.target(page,"dismiss_dialog")?.skillId=="dismiss_dialog") {
   task.currentProgram=listOf(Step(StepKind.DISMISS,Role.CLOSE,expect=listOf(Postcondition.DialogClosed)))
   task.programSource="planner";task.programCapability="dismiss_dialog";task.programPost=listOf(Postcondition.DialogClosed)
  }
  val config=EngineConfig(pacingOverrideMs=0,plannerCooldownMs=0,ambiguousRecheckMs=0,idleSleepMs=0,maxConsecutiveFailures=2,maxDecisionsWithoutProgress=6)
  val out=BrainEngine(renderer,memory,{null},object:EngineEvents{},config){SimulationTrainer.NOW}.runTask(task)
  val verified="dismiss_dialog" in out.successfulSkills
  val expected=f.optString("expected")
  val falseMastery=verified && (!renderer.physicallyClosed || expected!="CLOSE")
  val passed=when(expected){
   "CLOSE"->verified && renderer.physicallyClosed && out.status==TaskStatus.DONE
   "NOOP"->!verified && !renderer.physicallyClosed
   "MISSING"->!verified && out.actions==0
   "CHROME"->!verified && out.actions==0 && !page.dialogOpen
   "HUMAN"->!verified && out.actions==0 && out.status==TaskStatus.NEED_HUMAN
   else->false
  } && !falseMastery && renderer.wrongTargets==0 && renderer.unsafeActions==0
  val row=JsonObject().put("case_id",f.optString("id")).put("expected",expected).put("status",out.status.name)
   .put("dialog_detected",page.dialogOpen).put("human_detected",page.isHumanOnly)
   .put("recognized_dialog_closers",page.affordances.count{it.role==Role.CLOSE && it.visible && it.enabled && it.sameSite && !it.isCommit && com.appgate.brain.perception.FilterControls.inDialog(it)})
   .put("actions",out.actions).put("decisions",out.decisions).put("verified",verified).put("physically_closed",renderer.physicallyClosed)
   .put("wrong_targets",renderer.wrongTargets).put("unsafe_actions",renderer.unsafeActions).put("false_mastery",if(falseMastery)1 else 0)
   .put("passed",passed).put("compiled_procedure_used",out.steps.any{it.source.startsWith("skill:compiled_") && it.status==VerifyStatus.VERIFIED})
  store.keys("ledger/").forEach(store::delete)
  return Run(row,store.snapshot())
 }
 fun experiment(file:File):JsonObject {
  require(sha(file.readBytes())==FIXTURE_SHA256){"clinic fixture changed; review and regenerate intentionally"}
  val bundle=Json.parseObject(file.readText())
  val core=file.canonicalFile.parentFile.parentFile.parentFile.parentFile
  require(sha(File(core.parentFile,"app/src/main/assets/sitebrain/content.js").readBytes())==bundle.optString("extractor_sha256")){"stale browser recordings"}
  val fixtures=bundle.optArray("cases")!!.objects()
  require(fixtures.size==12 && sha(fixtures.joinToString("\n"){it.optString("id")+"|"+it.optString("split")+"|"+it.optString("expected")}.toByteArray())==MANIFEST_SHA256)
  val training=fixtures.filter{it.optString("split")=="TRAIN"};val heldout=fixtures.filter{it.optString("split")=="HELD_OUT"}
  require(training.size==2 && heldout.size==10)
  require(training.map{it.optObject("before")!!.optString("host")}.toSet().intersect(heldout.map{it.optObject("before")!!.optString("host")}.toSet()).isEmpty())
  val baseline=heldout.map{run(it,emptyMap(),false).row}
  var snapshot=emptyMap<String,String>()
  repeat(2){training.forEach{snapshot=run(it,snapshot,true).snapshot}}
  val frozen=snapshot.toMap()
  val after=heldout.map{run(it,frozen,false).row}
  require(snapshot==frozen)
  val skills=Memory(InMemoryStorage().apply{frozen.forEach{(k,v)->write(k,v)}}){SimulationTrainer.NOW}.skills.all()
  return JsonObject().put("schema_version",1).put("fixture_cases",12).put("held_out_cases",10)
   .put("manifest_sha256",MANIFEST_SHA256).put("fixture_sha256",FIXTURE_SHA256).put("extractor_sha256",bundle.optString("extractor_sha256"))
   .put("source","CHROMIUM_GENERATED_REPLAY").put("teacher","LOCAL_SCRIPTED").put("real_world_validated",false)
   .put("training_demonstrations",4).put("evaluation_teacher_calls",0)
   .put("compiled_dialog_skills",skills.count{it.origin==SkillOrigin.COMPILED && "capability:dismiss_dialog" in it.tags})
   .put("baseline",JsonArray(baseline)).put("after",JsonArray(after))
   .put("behavior_passes",after.count{it.optBoolean("passed")})
   .put("verified_closures",after.count{it.optString("expected")=="CLOSE" && it.optBoolean("verified")})
   .put("closure_opportunities",after.count{it.optString("expected")=="CLOSE"})
   .put("compiled_transfer_uses",after.count{it.optBoolean("compiled_procedure_used")})
   .put("unsafe_actions",(baseline+after).sumOf{it.optInt("unsafe_actions")})
   .put("wrong_targets",(baseline+after).sumOf{it.optInt("wrong_targets")})
   .put("false_mastery",(baseline+after).sumOf{it.optInt("false_mastery")})
 }
 companion object {
  const val MANIFEST_SHA256="94d28e1439971863a0867207d29fef3c986b32ffde22937e861548b720158981"
  const val FIXTURE_SHA256="e0e30c1f063c8697c1d569a7620f3f1869aa1c27e3bdd357e9cb198b2418ecfa"
  fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
 }
}
