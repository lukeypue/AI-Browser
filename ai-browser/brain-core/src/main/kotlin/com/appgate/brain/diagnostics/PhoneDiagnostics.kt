package com.appgate.brain.diagnostics
import com.appgate.brain.json.*
import java.time.Instant
import java.security.MessageDigest
import java.util.concurrent.*
import kotlin.math.abs
data class PhoneTestContext(val id:String,val startedElapsed:Long,val startedWall:Long,val trimGeneration:Int,val bootId:Int=0,val appRevision:String?=null,val versionCode:Int?=null)
object PhoneReportExporter {
 private val codes=setOf("learning_test_start","learning_test_end","learning_test_interrupted","step","task_finished","verified_progress","need_human","need_grant","renderer_gone","service_timeout","job_error","watchdog","action_outcome","learning_recheck","learning_recovery","learning_outcome","net_blocked","net_unknown_mutation")
 private val pages=setOf("HOME","SEARCH","RESULTS","FACET_PANEL","DETAIL","PROFILE","MESSAGES","DIALOG","AUTH_WALL","CHALLENGE","ERROR","UNKNOWN")
 private val statuses=setOf("VERIFIED","FAILED","AMBIGUOUS","HUMAN_NEEDED","UNKNOWN")
 private val phases=setOf("START","SEARCH","CONSTRAIN","COLLECT","INSPECT","PREPARE_MESSAGE","PREVIEW","DONE","UNKNOWN")
 private val diagnostics=setOf("VERIFIED","NO_TARGET","REPEAT_STATE_LIMIT","OUTSIDE_TASK_HOST","ACTION_REJECTED","STALE_DOCUMENT","RENDERER_TIMEOUT","INTERLOCK_BLOCKED","UNMET_EXPECTATION","PAGE_UNSETTLED","AUTH_WALL","CHALLENGE","UNEXPECTED_HOST","MODEL_UNCERTAIN","UNKNOWN")
 private val counts=setOf("items","controls","search_boxes","result_links","facet_controls","dialog_controls","next_controls","load_more_controls","new_items","decisions","verified_skills")
 private val sites=mapOf("facebook.com" to "FACEBOOK","www.facebook.com" to "FACEBOOK","m.facebook.com" to "FACEBOOK","ksl.com" to "KSL","www.ksl.com" to "KSL","classifieds.ksl.com" to "KSL","cars.ksl.com" to "KSL","offerup.com" to "OFFERUP","www.offerup.com" to "OFFERUP","ebay.com" to "EBAY","www.ebay.com" to "EBAY","craigslist.org" to "CRAIGSLIST","www.craigslist.org" to "CRAIGSLIST","autotrader.com" to "AUTOTRADER","www.autotrader.com" to "AUTOTRADER","cars.com" to "CARS_COM","www.cars.com" to "CARS_COM")
 private fun enum(value:String,allowed:Set<String>)=value.takeIf{it in allowed}?:"UNKNOWN"
 private fun count(value:JsonValue?):Long?=value?.asDoubleOrNull()?.takeIf{it.isFinite()&&it>=0&&it<=1_000_000&&it%1.0==0.0}?.toLong()
 fun export(c:PhoneTestContext,e:List<JsonObject>,n:Long,w:Long,t:Int,i:Boolean,r:String,v:Int,currentBoot:Int=0):JsonObject {
  require(r.matches(Regex("[0-9a-f]{40}")));require(v>=0)
  val own=e.filter{it.optString("test_id")==c.id&&it.optInt("boot",-1)==c.bootId}
  val crossedBoot=currentBoot!=c.bootId
  require((c.appRevision==null)==(c.versionCode==null))
  c.appRevision?.let{require(it.matches(Regex("[0-9a-f]{40}")))}
  c.versionCode?.let{require(it>=0)}
  // A legacy interrupted context has no trustworthy starting build. Never guess.
  require(!(i||crossedBoot)||c.appRevision!=null)
  val buildChanged=c.appRevision!=null&&(c.appRevision!=r||c.versionCode!=v)
  require(!buildChanged||i||crossedBoot)
  val endElapsed=if(crossedBoot)own.maxOfOrNull{it.optLong("elapsed",c.startedElapsed)}?:c.startedElapsed else n
  val elapsed=(endElapsed-c.startedElapsed).coerceIn(0,86_400_000)
  val current=own.filter{it.optLong("elapsed",-1) in c.startedElapsed..endElapsed}.sortedBy{it.optLong("elapsed")}
  val output=JsonArray()
  current.forEach { raw ->
   val kind=raw.optString("kind");if(kind !in codes)return@forEach
   val extra=raw.optObject("x")?:JsonObject();val page=extra.optObject("after")?:extra.optObject("before")?:extra
   val counters=JsonObject();counts.forEach{k->count(extra[k]?:page[k])?.let{counters.put(k,it)}}
   output.add(Json.obj("code" to kind,"site" to (sites[raw.optString("host").lowercase()]?:"UNKNOWN"),"page_type" to enum(page.optString("page_type"),pages),"status" to enum(extra.optString("status"),statuses),"phase" to enum(extra.optString("phase"),phases),"diagnostic_code" to enum(extra.optString("code"),diagnostics),"elapsed_ms" to (raw.optLong("elapsed")-c.startedElapsed).coerceIn(0,elapsed),"counters" to counters))
  }
  val end=current.lastOrNull{it.optString("kind")=="learning_test_end"}?.optObject("x")?:JsonObject()
  val reason=when(end.optString("reason")){"deadline"->"deadline";"stopped"->"stopped";"browser opened"->"browser";"search started","message task started"->"search";"replaced"->"replaced";"service ended"->"service_destroyed";else->if(i)"interrupted" else "unknown"}
  val flags=Json.obj("trimmed" to (t!=c.trimGeneration),"clock_changed" to (crossedBoot || abs((w-c.startedWall)-(n-c.startedElapsed))>60_000 || n<c.startedElapsed),"interrupted" to (i||crossedBoot))
  val summary=Json.obj("verified" to (count(end["verified_actions"])?:0),"failed" to (count(end["failed_actions"])?:0),"tasks" to (count(end["tasks"])?:0),"verified_skills" to (count(end["verified_procedure_uses"])?:0),"end_reason" to reason)
  val report=Json.obj("schema_version" to 1,"report_id" to c.id,"app_revision" to (c.appRevision?:r),"version_code" to (c.versionCode?:v),"started_at_utc" to Instant.ofEpochMilli(c.startedWall.coerceAtLeast(0)).toString(),"elapsed_ms" to elapsed,"flags" to flags,"summary" to summary,"events" to output)
  var kept=output.toList().takeLast(8000)
  if(kept.size!=output.size)flags.put("trimmed",true)
  report.put("events",JsonArray(kept))
  while(canonical(report).toByteArray(Charsets.UTF_8).size>1_048_576 && kept.isNotEmpty()) {
   flags.put("trimmed",true);kept=kept.drop((kept.size/4).coerceAtLeast(1));report.put("events",JsonArray(kept))
  }
  return report
 }
 fun canonical(v:JsonValue):String=when(v){
  is JsonObject->v.keys.sorted().joinToString(",","{","}"){Json.write(JsonString(it))+":"+canonical(v[it]?:JsonNull)}
  is JsonArray->v.joinToString(",","[","]"){canonical(it)}
  else->v.toString()
 }
 fun sha256(v:JsonValue):String=MessageDigest.getInstance("SHA-256").digest(canonical(v).toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
}
class SerialDiagnosticWriter:AutoCloseable {
 private val executor=Executors.newSingleThreadExecutor{r->Thread(r,"brain-diag").apply{isDaemon=true}}
 fun append(write:()->Unit){executor.execute(write)}
 fun <T> snapshot(read:()->T):Future<T> = executor.submit(Callable{read()})
 override fun close(){executor.shutdown()}
}
object PhoneQueuePolicy {
 fun canAdd(count:Int,bytes:Long,size:Int):Boolean=count in 0..9 && bytes>=0 && size in 1..1_048_576 && bytes+size<=10L*1_048_576
 fun canRetry(ageMs:Long):Boolean=ageMs in 0 until 7L*86_400_000
}
