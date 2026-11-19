package com.appgate.brain.lab
import com.appgate.brain.engine.EngineEvents
import com.appgate.brain.diagnostics.PhoneReportExporter
import com.appgate.brain.json.*

/** Actual engine callbacks; bounded first/last samples, exact streaming counts. No extra browser/AI calls. */
class CityDiagnostics:EngineEvents {
 private val started=System.nanoTime()
 private val first=mutableListOf<Pair<Int,JsonObject>>()
 private val last=java.util.ArrayDeque<Pair<Int,JsonObject>>()
 private var opening:Pair<Int,JsonObject>?=null
 private var plannerFailure:Pair<Int,JsonObject>?=null
 private val counts=KEYS.associateWith{0}.toMutableMap()
 override fun diagnostic(host:String,kind:String,data:JsonObject) {
  val e=PhoneReportExporter.projectEvent(kind,data)?:return
  e.put("elapsed_ms",((System.nanoTime()-started)/1_000_000L).coerceIn(0,86_400_000L))
  fun inc(key:String,by:Int=1){counts[key]=((counts[key]?:0)+by).coerceAtMost(1_000_000)}
  inc("events_seen")
  val row=counts.getValue("events_seen") to e
  if(kind=="planner_outcome" && e.optString("planner_outcome")=="ERROR" && plannerFailure==null)plannerFailure=row
  if(kind=="action_outcome" && e.optString("capability")=="open_item" && opening==null)opening=row
  if(kind=="action_outcome")when(e.optString("status")){"VERIFIED"->inc("action_verified");"FAILED"->inc("action_failed")}
  if(kind=="memory_checkpoint") {
   if(e.optObject("counters")?.optInt("outcome_neutral")==1)inc("neutral_programs")
   if(e.optString("diagnostic_code")=="STRATEGY_HELD")inc("held_programs")
  }
  if(kind=="planner_outcome" && e.optString("planner_outcome")=="ERROR")inc("planner_errors")
  if(kind=="learning_outcome")inc("new_lessons",e.optObject("counters")?.optInt("new_lessons")?.coerceIn(0,11)?:0)
  if(first.size<3)first+=row else {last.addLast(row);if(last.size>3)last.removeFirst()}
 }
 fun toJson():JsonObject {
  val protectedRows=(listOfNotNull(opening,plannerFailure)+last.toList()).distinctBy{it.first}
  val protectedOrdinals=protectedRows.map{it.first}.toSet()
  val trace=(protectedRows+first.filter{it.first !in protectedOrdinals}.take((6-protectedRows.size).coerceAtLeast(0))).sortedBy{it.first}.map{it.second}
  val out=JsonObject()
  KEYS.forEach{out.put(it,if(it=="events_dropped")counts.getValue("events_seen")-trace.size else counts.getValue(it))}
  return out.put("trace",JsonArray(trace))
 }
 companion object {
  val KEYS=listOf("events_seen","events_dropped","action_verified","action_failed","neutral_programs","held_programs","planner_errors","new_lessons")
  fun report(stages:Map<String,List<SimulationRun>>):JsonObject {
   val summaries=JsonObject()
   stages.forEach{(name,rows)->
    val summary=JsonObject().put("episodes",rows.size)
    KEYS.forEach{key->summary.put(key,rows.sumOf{it.diagnostics?.optInt(key)?:0})}
    summaries.put(name,summary)
   }
   val all=stages.flatMap{(stage,rows)->rows.map{stage to it}}
   val ranked=all.sortedWith(compareByDescending<Pair<String,SimulationRun>>{!it.second.score.success}
    .thenByDescending{it.second.diagnostics?.optInt("action_failed")?:0}.thenByDescending{it.second.diagnostics?.optInt("held_programs")?:0}
    .thenByDescending{it.second.diagnostics?.optInt("neutral_programs")?:0}.thenBy{it.first}.thenBy{it.second.scenario.id})
   val signatures=mutableMapOf<String,Int>();val samples=mutableListOf<JsonObject>()
   for((stage,row) in ranked) {
    val d=row.diagnostics?:continue
    val signature=stage+"|"+row.task.status.name+"|"+(d.optArray("trace")?.objects()?.lastOrNull{it.optString("diagnostic_code")!="UNKNOWN"}?.optString("diagnostic_code")?:"UNKNOWN")
    if((signatures[signature]?:0)>=2)continue
    signatures[signature]=(signatures[signature]?:0)+1
    samples+=JsonObject().put("stage",stage).put("scenario",row.scenario.id).put("diagnostics",d)
    if(samples.size==8)break
   }
   return JsonObject().put("schema_version",1).put("synthetic_only",true).put("scope","FROZEN_EXAM_EPISODES")
    .put("stage_summaries",summaries).put("samples",JsonArray(samples)).put("samples_truncated",samples.size<all.size)
  }
 }
}
