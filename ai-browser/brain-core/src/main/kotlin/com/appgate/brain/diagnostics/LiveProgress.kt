package com.appgate.brain.diagnostics

import com.appgate.brain.json.*

/** A bounded local progress snapshot using the phone report's privacy allowlist. */
object LiveProgressExporter {
 private const val MAX_COUNT=1_000_000L
 private fun count(value:JsonValue?):Long = value?.asDoubleOrNull()
  ?.takeIf { it.isFinite() && it>=0 && it<=MAX_COUNT && it%1.0==0.0 }?.toLong() ?: 0L

 fun export(c:PhoneTestContext,e:List<JsonObject>,n:Long,w:Long,t:Int,r:String,v:Int,currentBoot:Int=0,finished:Boolean=false,minimumElapsedMs:Long=0):JsonObject {
  require(c.id.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")))
  // Do not let permissive JSON integer readers associate fractional boot IDs.
  val own=e.filter { it.optString("test_id")==c.id && it["boot"]?.asDoubleOrNull()==c.bootId.toDouble() }.sortedBy { it.optLong("elapsed",-1) }
  val interrupted=currentBoot!=c.bootId || own.any {
   it.optString("kind")=="learning_test_interrupted" && it.optLong("elapsed",-1)>=c.startedElapsed &&
    (currentBoot!=c.bootId || it.optLong("elapsed")<=n)
  }
  val report=PhoneReportExporter.export(c,own,n,w,t,interrupted,r,v,currentBoot)
  // Persisted per-test heartbeat elapsed survives reboot; event times remain evidence-based.
  val elapsed=maxOf(report.optLong("elapsed_ms"),minimumElapsedMs.coerceIn(0,86_400_000))
  val flags=report.optObject("flags")!!
  val totals=linkedMapOf("verified_actions" to 0L,"failed_actions" to 0L,"tasks" to 0L,"new_lessons" to 0L,"teacher_attempts" to 0L)
  fun add(key:String,delta:Long=1L) { totals[key]=(totals.getValue(key)+delta).coerceAtMost(MAX_COUNT) }
  val recent=java.util.ArrayDeque<JsonObject>()
  var eventCount=0
  // Chunking avoids the final report's 8,000-event / 1 MiB limit losing totals.
  own.chunked(256).forEach { chunk ->
   PhoneReportExporter.export(c,chunk,n,w,t,interrupted,r,v,currentBoot).optArray("events")!!.objects().forEach { event ->
    when(event.optString("code")) {
     "action_outcome" -> when(event.optString("status")) {
      "VERIFIED" -> add("verified_actions")
      "FAILED" -> add("failed_actions")
     }
     "task_finished" -> add("tasks")
     "learning_outcome" -> {
      val counters=event.optObject("counters")!!
      add("new_lessons",count(counters["new_lessons"]))
      add("teacher_attempts",count(counters["teacher_attempts"]))
     }
    }
    recent.addLast(event);eventCount++
    if(recent.size>32)recent.removeFirst()
   }
  }
  // Global ordering is needed even when the diagnostic input is unsorted.
  val ordered=recent.toList().sortedBy { it.optLong("elapsed_ms") }
  val summary=report.optObject("summary")!!
  for((key,summaryKey) in mapOf("verified_actions" to "verified","failed_actions" to "failed","tasks" to "tasks"))
   totals[key]=maxOf(totals.getValue(key),count(summary[summaryKey]))
  if(eventCount>32)flags.put("trimmed",true)
  val ended=finished || interrupted
  val payload=Json.obj("schema_version" to 1,"test_id" to c.id,
   "app_revision" to report.optString("app_revision"),"version_code" to report.optInt("version_code"),
   "started_at_utc" to report.optString("started_at_utc"),"elapsed_ms" to elapsed,
   "sequence" to elapsed*2+(if(ended)1 else 0),"state" to (if(ended)"FINISHED" else "RUNNING"),
   "flags" to flags,"counters" to Json.obj(*totals.map { it.key to it.value }.toTypedArray()),
   "recent_events" to JsonArray(ordered))
  var kept=ordered
  while(PhoneReportExporter.canonical(payload).toByteArray(Charsets.UTF_8).size>32768 && kept.isNotEmpty()) {
   kept=kept.drop(1);flags.put("trimmed",true);payload.put("recent_events",JsonArray(kept))
  }
  return payload
 }
}
