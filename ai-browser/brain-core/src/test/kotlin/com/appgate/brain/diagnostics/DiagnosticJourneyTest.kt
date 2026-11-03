package com.appgate.brain.diagnostics
import com.appgate.brain.engine.*
import com.appgate.brain.json.*
import com.appgate.brain.memory.*
import com.appgate.brain.model.*
import com.appgate.brain.test.FakeSite
import org.junit.Assert.*
import org.junit.Test
class DiagnosticJourneyTest {
 private val ctx=PhoneTestContext("11111111-1111-4111-8111-111111111111",1000,1700000000000,0,appRevision="a".repeat(40),versionCode=70)
 private fun report(x:JsonObject,kind:String="action_outcome")=PhoneReportExporter.export(ctx,listOf(Json.obj("kind" to kind,"host" to "offerup.com","elapsed" to 1500,"test_id" to ctx.id,"boot" to 0,"x" to x)),2000,1700000001000,0,false,"a".repeat(40),70)
 @Test fun journeyProjectionKeepsTypedBeforeAfterAndCorrelationWithoutPrivateExtras() {
  val x=Json.obj("before_page_type" to "RESULTS","after_page_type" to "UNKNOWN","before_settle" to "IDLE","after_settle" to "BUSY","program_source" to "SKILL","task_sequence" to 7,"program_sequence" to 2,"action_sequence" to 3,"url" to "private-url","query" to "private-query")
  val r=report(x);val e=r.optArray("events")!!.objects().single()
  assertEquals("RESULTS",e.optString("before_page_type"));assertEquals("UNKNOWN",e.optString("after_page_type"));assertEquals("BUSY",e.optString("after_settle"));assertEquals("SKILL",e.optString("program_source"));assertEquals(7,e.optObject("counters")!!.optInt("task_sequence"));assertFalse(r.toString().contains("private"))
 }
 @Test fun unknownJourneyEnumsCannotLeakAndMissingAfterIsDistinct() {
  val x=Json.obj("before_page_type" to "private","after_page_type" to "NOT_OBSERVED","before_settle" to "private","program_source" to "skill:private","task_sequence" to -1)
  val r=report(x);val e=r.optArray("events")!!.objects().single()
  assertEquals("UNKNOWN",e.optString("before_page_type"));assertEquals("NOT_OBSERVED",e.optString("after_page_type"));assertFalse(r.toString().contains("private"));assertFalse(e.optObject("counters")!!.has("task_sequence"))
 }
 @Test fun wholeRunTotalsSurviveMissingEarlierEventsAndLegacyTotalsRemainUnknown() {
  val r=report(Json.obj("new_lessons" to 2,"neutral_programs" to 11,"held_programs" to 4,"planner_errors" to 3,"reason" to "deadline"),"learning_test_end")
  assertEquals(2,r.optObject("summary")!!.optInt("new_lessons"));assertEquals(11,r.optObject("summary")!!.optInt("neutral_programs"))
  assertFalse(report(JsonObject(),"learning_test_end").optObject("summary")!!.has("new_lessons"))
 }
 @Test fun engineJourneysAreDistinctAcrossEngineInstancesAndRemainTransient() {
  val captured=mutableListOf<JsonObject>();val memory=Memory(InMemoryStorage()){1800000000000L}
  repeat(2) { i->
   val fake=FakeSite().apply{dialogShown=false}
   val events=object:EngineEvents{override fun diagnostic(host:String,kind:String,data:JsonObject){if(kind=="action_outcome")captured+=data}}
   val ledger=TaskLedger("task-$i",Goal("goal-$i",GoalIntent.LEARN_SITE,"synthetic","Ford",emptyList(),budget=Budget(actions=8,llmCalls=0)),fake.host,fake.url).apply{lesson="search"}
   BrainEngine(fake,memory,{null},events,EngineConfig(pacingOverrideMs=0,plannerCooldownMs=0,ambiguousRecheckMs=0,idleSleepMs=0)){1800000000000L}.runTask(ledger)
   assertFalse(ledger.toJson().has("task_sequence"))
  }
  assertTrue(captured.isNotEmpty());val ids=captured.map{it.optInt("task_sequence")}.toSet();assertEquals(2,ids.size);assertTrue(ids.all{it>0})
  assertTrue(captured.all{it.optInt("program_sequence")>0 && it.optInt("action_sequence")>0})
 }
 @Test fun unavailablePlannerHasAnExplicitOutcomeWithoutClaimingTransport() {
  val fake=FakeSite().apply{dialogShown=false};val rows=mutableListOf<JsonObject>()
  val events=object:EngineEvents{override fun diagnostic(host:String,kind:String,data:JsonObject){if(kind=="planner_outcome")rows+=data}}
  val goal=Goal("custom",GoalIntent.CUSTOM,"synthetic","",emptyList(),budget=Budget(actions=2,llmCalls=1))
  BrainEngine(fake,Memory(InMemoryStorage()),{null},events,EngineConfig(pacingOverrideMs=0,plannerCooldownMs=0,ambiguousRecheckMs=0,idleSleepMs=0)).runTask(TaskLedger("custom",goal,fake.host,fake.url))
  assertTrue(rows.any{it.optString("planner_outcome")=="UNAVAILABLE"})
 }
 @Test fun wholeTestAggregationCountsBeforeTrimmingAndStopsAtItsOwnerDeadline() {
  val run=TimedLearningTest(60,1000)
  run.diagnostic("memory_checkpoint",Json.obj("new_lessons" to 2))
  run.diagnostic("learning_outcome",Json.obj("new_lessons" to 2))
  repeat(11){run.diagnostic("memory_checkpoint",Json.obj("code" to "STRATEGY_NOT_RECORDED","outcome_neutral" to 1))}
  repeat(4){run.diagnostic("memory_checkpoint",Json.obj("code" to "STRATEGY_HELD"))}
  repeat(3){run.diagnostic("planner_outcome",Json.obj("planner_outcome" to "ERROR"))}
  val summary=run.finish(3601000,"deadline")
  assertEquals(2,summary.newLessons);assertEquals(11,summary.neutralPrograms);assertEquals(4,summary.heldPrograms);assertEquals(3,summary.plannerErrors)
  run.diagnostic("learning_outcome",Json.obj("new_lessons" to 99))
  assertEquals(summary,run.finish(3602000,"stopped"))
 }
 @Test fun plannerErrorsAndRefusalsHaveOneFinalOutcomeWithoutExceptionText() {
  for(mode in listOf("error","refused","give_up")) {
   val fake=FakeSite().apply{dialogShown=false};val rows=mutableListOf<JsonObject>();var calls=0
   val planner=com.appgate.brain.planner.Planner(object:com.appgate.brain.planner.PlannerClient {
    override val describe="synthetic"
    override fun complete(instructions:String,input:String,schemaName:String,schema:JsonObject,maxOutputTokens:Int):String {
     calls++;if(mode=="error")throw IllegalStateException("private-exception")
     if(mode=="refused")throw com.appgate.brain.planner.PlannerRefused("private-refusal")
     return """{"steps":[],"confidence":0.9,"give_up":true,"rationale":"private-rationale"}"""
    }
   })
   val events=object:EngineEvents{override fun diagnostic(host:String,kind:String,data:JsonObject){if(kind=="planner_outcome")rows+=data}}
   val goal=Goal("custom",GoalIntent.CUSTOM,"synthetic","",emptyList(),budget=Budget(actions=2,llmCalls=1))
   BrainEngine(fake,Memory(InMemoryStorage()),{planner},events,EngineConfig(pacingOverrideMs=0,plannerCooldownMs=0,ambiguousRecheckMs=0,idleSleepMs=0)).runTask(TaskLedger("custom",goal,fake.host,fake.url))
   assertEquals(1,calls);assertEquals(1,rows.count{it.optString("planner_outcome")=="PROPOSAL_STARTED"})
   assertEquals(1,rows.count{it.optString("planner_outcome")==when(mode){"error"->"ERROR";"refused"->"REFUSED";else->"GIVE_UP"}})
   assertFalse(rows.joinToString{PhoneReportExporter.projectEvent("planner_outcome",it).toString()}.contains("private"))
  }
 }
 @Test fun normalAndHumanTasksHaveExactlyOneTerminalLifecycleEvent() {
  for(human in listOf(false,true)) {
   val fake=FakeSite().apply{dialogShown=false;loginWall=human};val rows=mutableListOf<JsonObject>()
   val events=object:EngineEvents{override fun diagnostic(host:String,kind:String,data:JsonObject){if(kind=="task_lifecycle")rows+=data}}
   val ledger=TaskLedger("life",Goal("life",GoalIntent.LEARN_SITE,"synthetic","Ford",emptyList(),budget=Budget(actions=8,llmCalls=0)),fake.host,fake.url).apply{lesson="search"}
   BrainEngine(fake,Memory(InMemoryStorage()),{null},events,EngineConfig(pacingOverrideMs=0,plannerCooldownMs=0,ambiguousRecheckMs=0,idleSleepMs=0)).runTask(ledger)
   assertEquals(1,rows.count{it.optString("status")=="RUNNING"});assertEquals(1,rows.count{it.optString("status")!="RUNNING"})
   if(human){assertEquals("NEED_HUMAN",rows.last().optString("status"));assertEquals(0,ledger.actions)}
  }
 }
 @Test fun maximumExportKeepsWholeTestTotalsUnderTheExistingOneMiBLimit() {
  val events=(0..8500).map{Json.obj("kind" to "action_outcome","elapsed" to 1500,"test_id" to ctx.id,"boot" to 0,"x" to Json.obj("before_page_type" to "RESULTS","after_page_type" to "UNKNOWN","task_sequence" to it,"program_source" to "SKILL","query" to "private"))}+Json.obj("kind" to "learning_test_end","elapsed" to 1900,"test_id" to ctx.id,"boot" to 0,"x" to Json.obj("reason" to "deadline","new_lessons" to 2,"neutral_programs" to 100))
  val r=PhoneReportExporter.export(ctx,events,2000,1700000001000,1,false,"a".repeat(40),70)
  assertTrue(PhoneReportExporter.canonical(r).toByteArray().size<=1048576)
  assertTrue(r.optObject("flags")!!.optBoolean("trimmed"));assertEquals(2,r.optObject("summary")!!.optInt("new_lessons"));assertEquals(100,r.optObject("summary")!!.optInt("neutral_programs"));assertFalse(r.toString().contains("private"))
 }

 @Test fun verifiedLessonBeforeHumanStopIsCountedOnceAndNotRecountedOnResume() {
  val fake=FakeSite().apply{dialogShown=false};val now=1800000000000L
  val memory=Memory(InMemoryStorage()){now};val run=TimedLearningTest(60,0);val rows=mutableListOf<JsonObject>()
  val events=object:EngineEvents{override fun diagnostic(host:String,kind:String,data:JsonObject){
   run.diagnostic(kind,data);if(kind=="learning_outcome")rows+=data
   if(kind=="memory_checkpoint" && data.optString("code")=="STRATEGY_SUCCESS_SAVED" && data.optString("capability")=="search")fake.loginWall=true
  }}
  val ledger=TaskLedger("blocked-learning",Goal("blocked-learning",GoalIntent.LEARN_SITE,"synthetic","Ford",emptyList(),budget=Budget(actions=8,llmCalls=0)),fake.host,fake.url).apply{lesson="expand_description"}
  val engine=BrainEngine(fake,memory,{null},events,EngineConfig(pacingOverrideMs=0,plannerCooldownMs=0,ambiguousRecheckMs=0,idleSleepMs=0)){now}
  engine.runTask(ledger)
  assertEquals(TaskStatus.NEED_HUMAN,ledger.status);assertTrue(memory.site(fake.host).curriculum.single{it.id=="search"}.done)
  assertEquals(1,rows.size);assertEquals(1,rows.single().optInt("new_lessons"))
  engine.runTask(ledger)
  assertEquals(2,rows.size);assertEquals(0,rows.last().optInt("new_lessons"));assertEquals(1,run.finish(3600000,"deadline").newLessons)
 }
 @Test fun programSourcesSeparateMissingFromUnrecognizedValues() {
  assertEquals("NONE",SemanticDiagnostics.programSource(""));assertEquals("UNKNOWN",SemanticDiagnostics.programSource("future-private-source"))
  assertEquals("SKILL",SemanticDiagnostics.programSource("skill:search"));assertEquals("COMPILED",SemanticDiagnostics.programSource("skill:compiled_example"))
  assertEquals("PLANNER",SemanticDiagnostics.programSource("planner"));assertEquals("LOCAL",SemanticDiagnostics.programSource("local"));assertEquals("NAVIGATE",SemanticDiagnostics.programSource("navigate"))
 }

 @Test fun realLearningSessionReportsFailedPrerequisiteAndSavedCooldown() {
  val now=1800000000000L;val fake=FakeSite().apply{dialogShown=false};fake.navigate("https://${fake.host}/search?q=Ford",1000)
  val renderer=object:Renderer by fake {override fun act(command:JsonObject,timeoutMs:Long):RendererResult=if(command.optString("cmd")=="click")RendererResult(true) else fake.act(command,timeoutMs)}
  val memory=Memory(InMemoryStorage()){now};val site=memory.site(fake.host);Curriculum.ensure(site)
  site.curriculum.filter{it.id!="expand_description"}.forEach{it.completedAt=now-1000};memory.saveSite(site)
  val rows=mutableListOf<JsonObject>();val actions=mutableListOf<JsonObject>()
  val events=object:EngineEvents{override fun diagnostic(host:String,kind:String,data:JsonObject){if(kind=="learning_schedule")rows+=data;if(kind=="action_outcome")actions+=data}}
  val engine=BrainEngine(renderer,memory,{null},events,EngineConfig(pacingOverrideMs=0,plannerCooldownMs=0,ambiguousRecheckMs=0,idleSleepMs=0)){now}
  val profile=com.appgate.brain.profile.SiteProfile("fake","Fake",listOf(fake.host),fake.currentUrl(),trainingQueries=listOf("Ford"))
  LearningSession(engine,memory,events,listOf(profile),clock={now}).run(1)
  assertTrue(actions.any{it.optString("capability")=="open_item" && it.optString("status")=="FAILED"})
  val row=rows.single();assertEquals("expand_description",row.optString("lesson"));assertEquals(1,row.optInt("prerequisite_only"));assertEquals(0,row.optInt("lesson_attempted"));assertEquals(0,row.optInt("lesson_done"))
  assertTrue(row.optLong("cooldown_remaining_ms")>0)
 }

 @Test fun savedLessonIsCountableAtDeadlineBeforeTheTaskEndCallback() {
  val now=1800000000000L;val fake=FakeSite().apply{dialogShown=false};val memory=Memory(InMemoryStorage()){now};val run=TimedLearningTest(60,0)
  var frozen:TimedLearningTest.Summary?=null
  val events=object:EngineEvents{override fun diagnostic(host:String,kind:String,data:JsonObject){
   run.diagnostic(kind,data)
   if(kind=="memory_checkpoint" && data.optString("code")=="STRATEGY_SUCCESS_SAVED" && data.optString("capability")=="search" && frozen==null) {
    assertTrue(memory.site(fake.host).curriculum.single{it.id=="search"}.done)
    frozen=run.finish(3600000,"deadline");fake.loginWall=true
   }
  }}
  val ledger=TaskLedger("deadline-learning",Goal("deadline-learning",GoalIntent.LEARN_SITE,"synthetic","Ford",emptyList(),budget=Budget(actions=8,llmCalls=0)),fake.host,fake.url).apply{lesson="expand_description"}
  BrainEngine(fake,memory,{null},events,EngineConfig(pacingOverrideMs=0,plannerCooldownMs=0,ambiguousRecheckMs=0,idleSleepMs=0)){now}.runTask(ledger)
  assertNotNull(frozen);assertEquals("verified saved learning must be counted before task completion",1,frozen!!.newLessons)
  assertEquals(frozen,run.finish(3601000,"stopped"))
 }
}
