package com.appgate.brain.engine
import com.appgate.brain.json.*
import com.appgate.brain.memory.FailedStrategies
import com.appgate.brain.model.*
import com.appgate.brain.perception.SpsParser
import com.appgate.brain.test.FakeSite
import org.junit.Assert.*
import org.junit.Test
class TeacherProposalFeedbackPersistenceTest {
 private fun page()=SpsParser().parse(FakeSite().apply{dialogShown=false}.observe(1000))
 private fun row():TeacherProposalRejection {
  val steps=listOf(Step(StepKind.TYPE,Role.SEARCH_BOX,arg="sensitive-query-marker",submit=true,nameHint="sensitive-label-marker"))
  val params=mapOf("query" to "sensitive-query-marker")
  return TeacherProposalRejection(TeacherProposalFeedback.context(page(),"search",params),
   TeacherProposalFeedback.signature(steps,params,false),ProposalRejectionReason.LOW_CONFIDENCE,
   FailedStrategies.approach(page(),"search",steps,params)!!.steps,null,"aaaaaaaa",1,1_800_000_000_000L)
 }
 @Test fun closedFeedbackAndAccountingSurviveLedgerReloadAndCopy(){
  val ledger=TaskLedger("typed",Goal("typed",GoalIntent.LEARN_SITE,"synthetic","synthetic",emptyList()),"fake.market","https://fake.market/")
  ledger.proposalRejections+=row();ledger.proposalCorrectionPending=true;ledger.llmCalls=1;ledger.plannerCallsOnSameState=1
  val restored=TaskLedger.fromJson(Json.parseObject(ledger.toJson().toString()))
  assertEquals(ledger.proposalRejections,restored.proposalRejections);assertTrue(restored.proposalCorrectionPending)
  assertEquals(1,restored.llmCalls);assertEquals(1,restored.plannerCallsOnSameState)
  val copied=restored.copy();assertEquals(restored.proposalRejections,copied.proposalRejections);assertTrue(copied.proposalCorrectionPending)
  restored.proposalCorrectionPending=false;restored.proposalCorrectionUsed=true;restored.llmCalls=2
  val exhausted=TaskLedger.fromJson(Json.parseObject(restored.toJson().toString()));assertTrue(exhausted.proposalCorrectionUsed);assertFalse(exhausted.proposalCorrectionPending);assertEquals(2,exhausted.llmCalls)
 }
 @Test fun rejectionNeverPersistsLiteralArgumentsOrSendsOpaqueScopeToTeacher(){
  val value=row();val text=value.toJson().toString();assertNotNull(TeacherProposalRejection.fromJson(Json.parseObject(text)))
  for(secret in listOf("sensitive-query-marker","sensitive-label-marker"))assertFalse(text.contains(secret))
  val public=value.publicJson();assertEquals(setOf("reason","steps"),public.keys.toSet())
  assertTrue(public.optArray("steps")!!.objects().all{step->step.keys.none{it in setOf("arg","name_hint","context","signature","target","rationale")}})
 }
 @Test fun corruptOrProtectedFeedbackIsRejectedWithoutDiscardingRequestAccounting(){
  val good=row().toJson()
  for(mutate in listOf<(JsonObject)->Unit>(
   {it.put("rationale","sensitive-text")},{it.put("quota_count","1")},{it.put("quota_count",0)},
   {it.put("quota_at",-1)},{it.put("context","sensitive-url")},
   {it.optArray("steps")!!.objects().single().put("role","SEND")},
   {it.optArray("steps")!!.objects().single().put("name_hint","sensitive-text")}
  )){val bad=Json.parseObject(good.toString());mutate(bad);assertNull(TeacherProposalRejection.fromJson(bad))}
  val ledger=TaskLedger("corrupt",Goal("corrupt",GoalIntent.LEARN_SITE,"synthetic","synthetic",emptyList()),"fake.market","").apply{llmCalls=2;proposalCorrectionUsed=true}
  val raw=ledger.toJson().put("proposal_rejections",JsonArray().add(good.put("extra","not allowed"))).put("proposal_correction_pending",true)
  val restored=TaskLedger.fromJson(raw);assertTrue(restored.proposalRejections.isEmpty());assertFalse(restored.proposalCorrectionPending);assertEquals(2,restored.llmCalls);assertTrue(restored.proposalCorrectionUsed)
 }
 @Test fun literalAndBoundEquivalentProceduresDeduplicateWhileRealProcedureChangesDoNot(){
  val params=mapOf("query" to "synthetic-value")
  val bound=listOf(Step(StepKind.TYPE,Role.SEARCH_BOX,arg="${'$'}query",submit=true))
  val literal=listOf(Step(StepKind.TYPE,Role.SEARCH_BOX,arg="synthetic-value",submit=true))
  assertEquals(TeacherProposalFeedback.signature(bound,params,false),TeacherProposalFeedback.signature(literal,params,false))
  assertNotEquals(TeacherProposalFeedback.signature(bound,params,false),TeacherProposalFeedback.signature(literal.map{it.copy(submit=false)},params,false))
  assertNotEquals(TeacherProposalFeedback.signature(bound,params,false),TeacherProposalFeedback.signature(literal.map{it.copy(expect=listOf(Postcondition.ResultsChanged))},params,false))
 }
 @Test fun feedbackDoesNotCrossGoalTargetOrRelevantChangedControls(){
  val page=page();val params=mapOf("query" to "sensitive-query-marker")
  val ledger=TaskLedger("scope",Goal("scope",GoalIntent.LEARN_SITE,"synthetic","synthetic",emptyList()),page.host,page.url)
  ledger.proposalRejections+=row()
  assertEquals(1,TeacherProposalFeedback.matching(ledger,page,"search",params).size)
  assertTrue(TeacherProposalFeedback.matching(ledger,page,"search",mapOf("query" to "different-query")).isEmpty())
  assertTrue(TeacherProposalFeedback.matching(ledger,page.copy(host="other.market"),"search",params).isEmpty())
  assertTrue(TeacherProposalFeedback.matching(ledger,page.copy(challenge=true),"search",params).isEmpty())
  val changed=page.copy(affordances=page.affordances+Affordance("changed",Role.SUBMIT,tag="a"))
  assertTrue(TeacherProposalFeedback.matching(ledger,changed,"search",params).isEmpty())
 }
 @Test fun equivalentBoundPostconditionsCannotBypassDuplicateDetection(){
  val params=mapOf("key" to "price_max","value" to "700")
  val parameterized=listOf(Step(StepKind.SET_RANGE,Role.FACET,facetKey="${'$'}key",arg="${'$'}value",expect=listOf(
   Postcondition.AnyOf(listOf(Postcondition.ConstraintApplied("${'$'}key","${'$'}value"),Postcondition.ValueIs("${'$'}key","${'$'}value"))),Postcondition.RoleAppeared(Role.FACET,"${'$'}key"))))
  val literal=listOf(Step(StepKind.SET_RANGE,Role.FACET,facetKey="price_max",arg="700",expect=listOf(
   Postcondition.AnyOf(listOf(Postcondition.ConstraintApplied("price_max","700"),Postcondition.ValueIs("price_max","700"))),Postcondition.RoleAppeared(Role.FACET,"price_max"))))
  assertEquals(TeacherProposalFeedback.signature(parameterized,params,false),TeacherProposalFeedback.signature(literal,params,false))
 }
 @Test fun implicitAndExplicitSearchDefaultsAreOneExecutableProcedure(){
  val params=mapOf("query" to "synthetic-query")
  val implicit=listOf(Step(StepKind.TYPE,Role.SEARCH_BOX,arg="${'$'}query",submit=false))
  val explicit=listOf(Step(StepKind.TYPE,Role.SEARCH_BOX,arg="synthetic-query",submit=false,expect=listOf(Postcondition.ValueIs("query","synthetic-query"))))
  assertEquals(TeacherProposalFeedback.signature(implicit,params,false),TeacherProposalFeedback.signature(explicit,params,false))
 }

 @Test fun proposalMetadataAndEquivalentCheckOrderingCannotMakeANewProcedure(){
  val params=mapOf("query" to "synthetic-query")
  val a=listOf(Step(StepKind.TYPE,Role.SEARCH_BOX,arg="${'$'}query",submit=true,expect=listOf(Postcondition.ResultsChanged,Postcondition.UrlChanged)))
  val b=a.map{it.copy(expect=it.expect.reversed())}
  assertEquals(TeacherProposalFeedback.signature(a,params,true),TeacherProposalFeedback.signature(a,params,false))
  assertEquals(TeacherProposalFeedback.signature(a,params,false),TeacherProposalFeedback.signature(b,params,false))
  val anyA=a.map{it.copy(expect=listOf(Postcondition.AnyOf(listOf(Postcondition.ResultsChanged,Postcondition.UrlChanged))))}
  val anyB=a.map{it.copy(expect=listOf(Postcondition.AnyOf(listOf(Postcondition.UrlChanged,Postcondition.ResultsChanged,Postcondition.ResultsChanged))))}
  assertEquals(TeacherProposalFeedback.signature(anyA,params,false),TeacherProposalFeedback.signature(anyB,params,false))
 }
 @Test fun actualActionOrderStillDefinesADifferentProcedure(){
  val type=Step(StepKind.TYPE,Role.SEARCH_BOX,arg="synthetic-query")
  val click=Step(StepKind.CLICK,Role.SUBMIT,expect=listOf(Postcondition.ResultsChanged))
  assertNotEquals(TeacherProposalFeedback.signature(listOf(type,click),emptyMap(),false),TeacherProposalFeedback.signature(listOf(click,type),emptyMap(),false))
 }

}
