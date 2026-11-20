package com.appgate.brain.lab

import com.appgate.brain.engine.*
import com.appgate.brain.goal.GoalParser
import com.appgate.brain.json.*
import com.appgate.brain.memory.*
import com.appgate.brain.model.*
import com.appgate.brain.perception.SpsParser
import java.io.File

/** Independent synthetic delayed-transition cases; frozen City exams stay unchanged. */
object ItemOutcomeClinic {
 private class Replay(val bundle:JsonObject,val kind:String):Renderer {
  private var acted=false
  var clicks=0;private set
  var reads=0;private set
  override fun observe(timeoutMs:Long):String {
   if(!acted)return bundle.optObject("before")!!.toString()
   val index=reads++
   val raw=Json.parseObject(bundle.optObject(if(kind=="immediate" || kind=="delayed" && index>=2)"detail" else "loading")!!.toString())
   if(kind=="human")raw.optObject("signals")!!.put("passwordFields",1).put("authPhrases",true).put("sparse",true)
   if(kind=="challenge")raw.optObject("signals")!!.put("challengeWidget",true)
   if(kind=="offsite")raw.put("url","https://outside.invalid/item").put("host","outside.invalid")
   return raw.toString()
  }
  override fun act(command:JsonObject,timeoutMs:Long):RendererResult {
   val target=bundle.optObject("before")!!.optArray("items")!!.objects().first()
   if(command.optString("cmd")!="click" || command.optString("id")!=target.optString("aff"))return RendererResult(false,"unrecorded target")
   clicks++;acted=true;return RendererResult(true)
  }
  override fun currentUrl()=bundle.optObject("before")!!.optString("url")
  override fun navigate(url:String,timeoutMs:Long)=RendererResult(false,"no navigation transition")
  override fun back(timeoutMs:Long)=RendererResult(false,"no back transition")
  override fun waitSettle(timeoutMs:Long)=RendererResult(true)
  override fun recover()=RendererResult(false)
  override fun isAlive()=true
  override fun setNetworkMode(mode:String,allowlist:List<String>,commitEndpoints:List<String>)=RendererResult(mode=="TRAIN")
 }
 fun experiment(bundle:JsonObject,directory:File):JsonObject {
  require(!directory.exists());require(directory.mkdirs())
  val rows=JsonArray()
  for(kind in listOf("delayed","immediate","overlay","human","challenge","offsite")){
   val folder=File(directory,kind)
   fun attempt(label:String):JsonObject {
    val memory=Memory(FileBrainStorage(folder)){SimulationTrainer.NOW}
    val renderer=Replay(bundle,kind);val page=SpsParser().parse(renderer.observe(1000))
    val prior=if(memory.site(page.host).curriculum.any{it.id=="open_item" && it.done})1 else 0
    val target=page.results!!.items.first().key
    val task=TaskLedger("item-"+kind+"-"+label,GoalParser.parse("synthetic bicycle",Budget(actions=3,llmCalls=0,wallMs=3000)).copy(intent=GoalIntent.LEARN_SITE),page.host,page.url).apply{
     lesson="open_item";currentItem=target
    }
    var teachers=0
    val out=BrainEngine(renderer,memory,{teachers++;null},object:EngineEvents{},EngineConfig(pacingOverrideMs=0,ambiguousRecheckMs=0,plannerCooldownMs=0)){SimulationTrainer.NOW}.runTask(task)
    val newLessons=(memory.site(page.host).curriculum.count{it.done}-prior).coerceAtLeast(0)
    val verified="open_item" in out.successfulSkills
    val expected=kind in setOf("delayed","immediate")
    val pass=if(expected)verified && out.status==TaskStatus.DONE && renderer.clicks==1 &&
      (if(label=="cold")newLessons==1 else prior>=1 && newLessons==0)
     else !verified && newLessons==0 && renderer.clicks==1 && when(kind){
      "human","challenge"->out.status==TaskStatus.NEED_HUMAN
      "offsite"->out.status in setOf(TaskStatus.FAILED,TaskStatus.PARTIAL) && out.terminalReason=="left task site during training"
      else->out.status!=TaskStatus.DONE
     }
    return Json.obj("case_id" to kind,"run" to label,"passed" to pass,"verified" to verified,"status" to out.status.name,"clicks" to renderer.clicks,"observations_after_click" to renderer.reads,"new_lessons" to newLessons,"retained_before_replay" to (prior>=1),"teacher_availability_checks" to teachers,"teacher_calls" to 0)
   }
   rows.add(attempt("cold"))
   if(kind in setOf("delayed","immediate")){
    val replay=attempt("reloaded");rows.add(replay)
   }
  }
  return Json.obj("schema_version" to 1,"synthetic_only" to true,"source" to "CHROMIUM_GENERATED_REPLAY","physical_phone_verified" to false,"external_ai_requests" to 0,"teacher" to "DISABLED_FACTORY_RETURNS_NULL","cases" to rows,"all_passed" to rows.objects().all{it.optBoolean("passed")})
 }
 fun runCommand(args:Array<String>){
  require(args.size==3)
  val directory=File(File(args[1]),java.util.UUID.randomUUID().toString())
  val result=experiment(Json.parseObject(File(args[0]).readText()),directory)
  File(args[2]).apply{parentFile.mkdirs();writeText(result.toString())}
  check(result.optBoolean("all_passed")){"synthetic item outcome clinic failed"}
 }
}

fun main(args:Array<String>)=ItemOutcomeClinic.runCommand(args)
