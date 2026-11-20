package com.appgate.brain.planner
import com.appgate.brain.json.*
import com.appgate.brain.memory.*
import com.appgate.brain.engine.*
import com.appgate.brain.goal.GoalParser
import com.appgate.brain.model.*
import com.appgate.brain.test.FakeSite
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class PlannerAuthenticationHoldTest {
 private fun call(c:PlannerClient)=runCatching{c.complete("private-instructions","private-input","test",JsonObject())}.exceptionOrNull()
 private fun client(h:PlannerAuthenticationHold,b:TeacherBudget,http:HttpTransport,beforeWrapping:()->Unit={}):PlannerClient =
  AuthenticationGuardedClient.create(h){
   val config=PlannerConfig(apiKey="fixture-placeholder")
   beforeWrapping()
   PlannerClients.create(config,BudgetedTeacher(b,{true},{true}),http)
  }!!
 private class Response(var status:Int=401):HttpTransport(){
  var calls=0
  override fun postJson(url:String,body:String,headers:Map<String,String>,connectTimeoutMs:Int,readTimeoutMs:Int):String{
   calls++;if(status!=200)throw PlannerHttpException(status,"private-body");return """{"output_text":"{}"}"""
  }
 }
 @Test fun first401HoldsFreshClientsAndDoesNotReserveAgainAfterDurableReload(){
  val dir=Files.createTempDirectory("auth-hold-").toFile()
  try{
   val storage=FileBrainStorage(dir);val h=PlannerAuthenticationHold(storage);val b=TeacherBudget(InMemoryStorage());val http=Response()
   assertTrue(call(client(h,b,http)) is PlannerHttpException)
   val restored=PlannerAuthenticationHold(FileBrainStorage(dir))
   assertEquals(PlannerConnectionStatus.AUTHENTICATION_REJECTED,restored.status())
   repeat(4){assertNotNull(call(client(restored,b,http)))}
   assertEquals(1,http.calls);assertEquals(1,b.snapshot(true).requests24h)
  }finally{dir.deleteRecursively()}
 }
 @Test fun explicitSaveRetriesButStaleClientsCannotSpendAllowance(){
  val h=PlannerAuthenticationHold(InMemoryStorage());val b=TeacherBudget(InMemoryStorage());val http=Response();val stale=client(h,b,http)
  assertNotNull(call(stale));h.allowRetry();http.status=200
  assertNotNull(call(stale));assertEquals(1,http.calls)
  val fresh=client(h,b,http);assertNull(call(fresh));assertEquals(2,http.calls);assertEquals(2,b.snapshot(true).requests24h)
  assertEquals(PlannerConnectionStatus.READY,h.status())
 }
 @Test fun aLateOld401CannotBlockANewerSavedConnection(){
  val storage=InMemoryStorage();val h=PlannerAuthenticationHold(storage);val b=TeacherBudget(InMemoryStorage())
  val http=object:HttpTransport(){override fun postJson(url:String,body:String,headers:Map<String,String>,connectTimeoutMs:Int,readTimeoutMs:Int):String{
   PlannerAuthenticationHold(storage).allowRetry();throw PlannerHttpException(401)
  }}
  assertTrue(call(client(h,b,http)) is PlannerHttpException)
  assertEquals(PlannerConnectionStatus.READY,h.status())
 }
 @Test fun nonAuthenticationFailuresDoNotDisableTheConnection(){
  for(code in listOf(403,429,503)){
   val h=PlannerAuthenticationHold(InMemoryStorage());val http=Response(code);val b=TeacherBudget(InMemoryStorage())
   repeat(2){assertTrue(call(client(h,b,http)) is PlannerHttpException)}
   assertEquals(2,http.calls);assertEquals(PlannerConnectionStatus.READY,h.status())
  }
 }
 @Test fun timeoutAndInvalidOutputDoNotCreateAnAuthenticationHold(){
  for(timeout in listOf(false,true)){
   val h=PlannerAuthenticationHold(InMemoryStorage());val b=TeacherBudget(InMemoryStorage());var calls=0
   val http=object:HttpTransport(){override fun postJson(url:String,body:String,headers:Map<String,String>,connectTimeoutMs:Int,readTimeoutMs:Int):String{
    calls++;if(timeout)throw java.net.SocketTimeoutException("private-url");return "not-json"
   }}
   repeat(2){assertNotNull(call(client(h,b,http)))}
   assertEquals(2,calls);assertEquals(PlannerConnectionStatus.READY,h.status())
  }
 }
 @Test fun corruptStateFailsClosedAndExplicitSaveRepairsIt(){
  val storage=InMemoryStorage();storage.write("connection/state","private-corrupt");val h=PlannerAuthenticationHold(storage);val b=TeacherBudget(InMemoryStorage());val http=Response(200)
  assertEquals(PlannerConnectionStatus.STORAGE_UNAVAILABLE,h.status());assertNotNull(call(client(h,b,http)));assertEquals(0,http.calls);assertEquals(0,b.snapshot(true).requests24h)
  h.allowRetry();assertNull(call(client(h,b,http)));assertEquals(1,http.calls)
 }
 @Test fun unreadableStateDoesNotMasqueradeAsMissingState(){
  val storage=object:BrainStorage by InMemoryStorage(){override fun readResult(key:String)=StorageRead(null,true)}
  val h=PlannerAuthenticationHold(storage);val b=TeacherBudget(InMemoryStorage());val http=Response(200)
  assertEquals(PlannerConnectionStatus.STORAGE_UNAVAILABLE,h.status());assertNotNull(call(client(h,b,http)));assertEquals(0,http.calls)
 }
 @Test fun writeFailureStillStopsRepeatedRequestsInThisProcess(){
  val storage=object:BrainStorage by InMemoryStorage(){override fun write(key:String,value:String){throw java.io.IOException("private-disk")}}
  val h=PlannerAuthenticationHold(storage);val b=TeacherBudget(InMemoryStorage());val http=Response()
  assertTrue(call(client(h,b,http)) is PlannerHttpException)
  repeat(3){assertNotNull(call(client(h,b,http)))}
  assertEquals(1,http.calls);assertEquals(PlannerConnectionStatus.AUTHENTICATION_REJECTED,h.status())
  assertTrue(runCatching{h.allowRetry()}.isFailure);assertEquals(PlannerConnectionStatus.AUTHENTICATION_REJECTED,h.status())
 }
 @Test fun holdStoresNoRequestOrCredentialMetadata(){
  val storage=InMemoryStorage();val h=PlannerAuthenticationHold(storage);val b=TeacherBudget(InMemoryStorage());val http=Response();call(client(h,b,http))
  val raw=storage.snapshot().values.single();val fields=Json.parseObject(raw)
  assertEquals(setOf("schema","generation","blocked"),fields.keys)
  for(value in listOf("fixture-placeholder","private","https://","OPENAI","model","input"))assertFalse(raw.contains(value))
 }
 @Test fun localVerifiedSkillsContinueWhilePlannerIsHeld(){
  val h=PlannerAuthenticationHold(InMemoryStorage());val b=TeacherBudget(InMemoryStorage());val http=Response();call(client(h,b,http));val fake=FakeSite();val memory=Memory(InMemoryStorage())
  val engine=BrainEngine(fake,memory,{if(h.status()==PlannerConnectionStatus.READY)Planner(client(h,b,http))else null},object:EngineEvents{},config=EngineConfig(plannerCooldownMs=0,ambiguousRecheckMs=0,pacingOverrideMs=0))
  val goal=GoalParser.parse("Ford Expedition under 8000",Budget(itemsInspected=3,actions=60,wallMs=60000))
  val result=engine.runTask(TaskLedger("auth-held-local",goal,fake.host,"https://${fake.host}/"))
  assertEquals(TaskStatus.DONE,result.status);assertTrue(result.actions>0);assertEquals(1,http.calls)
  assertTrue(memory.skills.get("search")!!.stat(fake.host).successes>0)
 }
 @Test fun masterOffStillPreventsTheFirstTransport(){
  val h=PlannerAuthenticationHold(InMemoryStorage());val b=TeacherBudget(InMemoryStorage());val http=Response(200)
  val c=AuthenticationGuardedClient.create(h){PlannerClients.create(PlannerConfig(apiKey="fixture"),BudgetedTeacher(b,{false},{true}),http)}!!
  assertTrue(call(c) is TeacherBudgetExceeded);assertEquals(0,http.calls);assertEquals(PlannerConnectionStatus.READY,h.status())
 }
 @Test fun heldReasonSurvivesAllowlistedTestBoundaryWithoutInventingARequest(){
  val h=PlannerAuthenticationHold(InMemoryStorage());val b=TeacherBudget(InMemoryStorage());val http=Response();call(client(h,b,http))
  val event=com.appgate.brain.diagnostics.PhoneReportExporter.projectEvent("learning_test_end",h.diagnostic())!!
  assertEquals("AUTHENTICATION",event.optString("planner_failure"));assertFalse(event.optObject("counters")!!.has("http_status"))
  assertEquals(1,http.calls);assertEquals(1,b.snapshot(true).requests24h)
  val id="00000000-0000-4000-8000-000000000001";val start=1800000000000L
  val context=com.appgate.brain.diagnostics.PhoneTestContext(id,0,start,0,appRevision="a".repeat(40),versionCode=971)
  val end=h.diagnostic().put("reason","deadline").put("planner_errors",0).put("planner_auth_errors",0).put("new_lessons",0)
  val raw=JsonObject().put("test_id",id).put("boot",0).put("elapsed",3600000).put("kind","learning_test_end").put("x",end)
  val report=com.appgate.brain.diagnostics.PhoneReportExporter.export(context,listOf(raw),3600000,start+3600000,1,false,"a".repeat(40),971)
  assertEquals(0,report.optObject("summary")!!.optInt("planner_errors"));assertFalse(report.toString().contains("generation"))
  java.io.File("build/reports/authentication-hold-phone-fixture.json").apply{parentFile.mkdirs();writeText(com.appgate.brain.diagnostics.PhoneReportExporter.canonical(report))}
  h.allowRetry();assertFalse(h.diagnostic().has("planner_failure"))
 }

 @Test fun readFailureDuring401CallbackCannotForgetTheRejectionWhenReadsRecover(){
  val backing=InMemoryStorage();var unreadable=false
  val storage=object:BrainStorage by backing {override fun readResult(key:String)=if(unreadable)StorageRead(null,true)else backing.readResult(key)}
  val hold=PlannerAuthenticationHold(storage);val budget=TeacherBudget(InMemoryStorage());var calls=0
  val http=object:HttpTransport(){override fun postJson(url:String,body:String,headers:Map<String,String>,connectTimeoutMs:Int,readTimeoutMs:Int):String{calls++;unreadable=true;throw PlannerHttpException(401)}}
  assertTrue(call(client(hold,budget,http)) is PlannerHttpException);unreadable=false
  assertEquals(PlannerConnectionStatus.AUTHENTICATION_REJECTED,hold.status())
  assertEquals(PlannerConnectionStatus.AUTHENTICATION_REJECTED,PlannerAuthenticationHold(storage).status())
  assertNotNull(call(client(hold,budget,http)));assertEquals(1,calls)
 }

 @Test fun settingsSavedDuringClientConstructionCannotAuthorizeOldConfig(){
  val hold=PlannerAuthenticationHold(InMemoryStorage());val budget=TeacherBudget(InMemoryStorage());val http=Response()
  val stale=client(hold,budget,http){hold.allowRetry()}
  assertNotNull(call(stale));assertEquals(0,http.calls);assertEquals(0,budget.snapshot(true).requests24h)
  assertEquals(PlannerConnectionStatus.READY,hold.status())
  http.status=200;assertNull(call(client(hold,budget,http)));assertEquals(1,http.calls)
 }

}
