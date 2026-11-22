package com.appgate.brain.engine
import com.appgate.brain.json.*
import com.appgate.brain.memory.FailedStrategies
import com.appgate.brain.model.*
import com.appgate.brain.perception.SpsParser
import com.appgate.brain.verify.Verifier
import org.junit.Assert.*
import org.junit.Test

class ResultsScrollContextTest {
 private fun raw(y:Int=0,rootY:Int=0,id:String="doc-1:scroll-1",kind:String="RESULTS_CONTAINER",extent:Int=1200):JsonObject {
  val rows=JsonArray();val items=JsonArray()
  repeat(3){i->rows.add(JsonObject().put("id","item-$i").put("tag","a").put("name","Synthetic item $i").put("href","https://scroll.fixture.invalid/item/$i").put("itemKey","key-$i").put("visible",true).put("enabled",true).put("sameSite",true).put("inCard",true).put("cardHasPrice",true).put("listSize",3));items.add(JsonObject().put("key","key-$i").put("title","Synthetic item $i").put("href","https://scroll.fixture.invalid/item/$i").put("aff","item-$i").put("price","$100"))}
  return JsonObject().put("v",3).put("url","https://scroll.fixture.invalid/search").put("host","scroll.fixture.invalid").put("title","Synthetic results").put("signals",JsonObject().put("hasMain",true).put("resultsHint",true).put("priceCount",3).put("textLength",1200)).put("settle",JsonObject().put("state","IDLE")).put("viewport",JsonObject().put("w",480).put("h",800).put("scrollY",rootY).put("scrollH",2000)).put("elements",rows).put("items",items).put("scrollContext",JsonObject().put("kind",kind).put("id",id).put("y",y).put("height",320).put("extent",extent))
 }
 private fun page(y:Int=0,rootY:Int=0,id:String="doc-1:scroll-1")=SpsParser().parse(raw(y,rootY,id),1_800_000_000_000L)
 private fun holds(p:Postcondition,before:SemanticPageState,after:SemanticPageState)=Verifier.holds(p,Action(ActionKind.SCROLL,amount=280),before,after,emptySet(),mutableListOf())
 @Test fun actualResultsPanelMovementVerifiesWithoutMovingTheDocument(){assertTrue(holds(Postcondition.ScrolledDown,page(),page(280)))}
 @Test fun documentMovementCannotPretendTheSelectedResultsPanelMoved(){assertFalse(holds(Postcondition.ScrolledDown,page(),page(rootY=280)))}
 @Test fun documentBottomCannotPretendTheSelectedResultsPanelEnded(){assertFalse(holds(Postcondition.EndOfResults,page(rootY=1200),page(rootY=1200)))}
 @Test fun actualResultsPanelBottomVerifiesWhileTheDocumentIsAtTheTop(){assertTrue(holds(Postcondition.EndOfResults,page(),page(880)))}
 @Test fun differentPanelsCannotManufactureMovement(){assertFalse(holds(Postcondition.ScrolledDown,page(id="doc-1:scroll-1"),page(280,id="doc-1:scroll-2")))}
 @Test fun closedPlannerSummaryShowsTargetGeometryWithoutOpaqueIdentity(){val s=page(280).summary();val c=s.optObject("scroll_context");assertNotNull(c);assertEquals("RESULTS_CONTAINER",c!!.optString("kind"));assertEquals(280,c.optInt("y"));assertFalse(s.toString().contains("doc-1:scroll-1"))}
 @Test fun malformedContextCannotFallBackToFalseDocumentSuccess(){
  for(value in listOf<JsonValue>(JsonString("private arbitrary text"),JsonObject().put("kind","RESULTS_CONTAINER").put("id","doc-1:scroll-1").put("y",-1).put("height",320).put("extent",1200),JsonObject().put("kind","RESULTS_CONTAINER").put("id","doc-1:scroll-1").put("y",0).put("height",320).put("extent",10000001))){
   val b=raw(rootY=1200).put("scrollContext",value);val a=raw(rootY=1200).put("scrollContext",value)
   assertFalse(holds(Postcondition.EndOfResults,SpsParser().parse(b),SpsParser().parse(a)))
  }
 }
 @Test fun resultsContractGetsOneDistinctIdentityWhileOldRootFailureStaysHeld(){
  val inner=page();val legacy=SpsParser().parse(raw().remove("scrollContext"))
  val body=listOf(Step(StepKind.SCROLL,arg="900",expect=listOf(Postcondition.NewResults)))
  val rootKey=FailedStrategies.key(legacy,"scroll_results",body);val innerKey=FailedStrategies.key(inner,"scroll_results",body)
  assertNotEquals(rootKey,innerKey)
  val site=SiteModel("scroll.fixture.invalid");FailedStrategies.record(site,rootKey,false,1L)
  assertFalse(FailedStrategies.allowed(site,rootKey,2L));assertTrue(FailedStrategies.allowed(site,innerKey,2L))
  FailedStrategies.record(site,innerKey,false,2L);assertFalse(FailedStrategies.allowed(site,innerKey,3L));assertFalse(FailedStrategies.allowed(site,rootKey,3L))
 }

 @Test fun diagnosticsShareSelectedSurfaceGeometryWithoutOpaqueIdentity(){
  val goal=Goal("scroll-diag",GoalIntent.LEARN_SITE,"synthetic","synthetic",emptyList())
  val ledger=TaskLedger(goal.id,goal,"scroll.fixture.invalid","https://scroll.fixture.invalid/search")
  val data=SemanticDiagnostics.action(ledger,page(),page(280),Step(StepKind.SCROLL,arg="280"),VerifyStatus.VERIFIED,DiagnosticCode.VERIFIED)
  val after=data.optObject("after")!!;assertEquals(280,after.optInt("scroll_y"));assertEquals(320,after.optInt("viewport_height"));assertEquals(1200,after.optInt("scroll_height"))
  assertEquals("RESULTS_CONTAINER",after.optObject("scroll_context")!!.optString("kind"));assertFalse(data.toString().contains("doc-1:scroll-1"))
  val shared=com.appgate.brain.diagnostics.PhoneReportExporter.projectEvent("action_outcome",data)!!.optObject("counters")!!
  assertEquals(280,shared.optInt("scroll_y"));assertEquals(320,shared.optInt("viewport_height"));assertEquals(1200,shared.optInt("scroll_height"));assertFalse(shared.has("scroll_context"))
 }
 @Test fun transientPanelIdentityChangesDoNotResetConcreteFailureMemory(){
  val body=listOf(Step(StepKind.SCROLL,arg="900",expect=listOf(Postcondition.NewResults)))
  assertEquals(FailedStrategies.key(page(id="doc-1:scroll-1"),"scroll_results",body),FailedStrategies.key(page(id="doc-2:scroll-9"),"scroll_results",body))
 }
 @Test fun typedRootKeepsLegacyFailureIdentityAndStillVerifiesRealRootMovement(){
  fun root(y:Int)=SpsParser().parse(raw(rootY=y).put("scrollContext",JsonObject().put("kind","ROOT").put("id","doc-1:root").put("y",y).put("height",800).put("extent",2000)))
  val legacy=SpsParser().parse(raw().remove("scrollContext"));val body=listOf(Step(StepKind.SCROLL,arg="900",expect=listOf(Postcondition.NewResults)))
  val key=FailedStrategies.key(legacy,"scroll_results",body);assertEquals("6e2ef3aa61eeb766",key);assertEquals(key,FailedStrategies.key(root(0),"scroll_results",body))
  assertTrue(holds(Postcondition.ScrolledDown,root(0),root(280)));assertFalse(holds(Postcondition.ScrolledDown,root(0),page(280)))
 }

 @Test fun changedMissingOrInvalidSurfaceIsAmbiguousRatherThanBroken(){
  val action=Action(ActionKind.SCROLL,amount=280,expect=listOf(Postcondition.anyOf(Postcondition.ScrolledDown,Postcondition.EndOfResults,Postcondition.NewResults)))
  val states=listOf(page(280,id="doc-1:scroll-2"),SpsParser().parse(raw().remove("scrollContext")),SpsParser().parse(raw().put("scrollContext",JsonString("unavailable"))))
  for(after in states)assertEquals(VerifyStatus.AMBIGUOUS,Verifier.verify(action,page(),after).status)
 }
 @Test fun independentlyVerifiedNewResultsRemainPositiveWhenSurfaceChanges(){
  val before=page();val after=page(280,id="doc-1:scroll-2").copy(collections=listOf(Collection("results",listOf("fresh-key"),1)))
  val action=Action(ActionKind.SCROLL,expect=listOf(Postcondition.anyOf(Postcondition.ScrolledDown,Postcondition.EndOfResults,Postcondition.NewResults)))
  assertEquals(VerifyStatus.VERIFIED,Verifier.verify(action,before,after).status)
 }
 @Test fun comparableNoOpStillFailsAndUnrelatedMissingExpectationIsNotExcused(){
  assertEquals(VerifyStatus.FAILED,Verifier.verify(Action(ActionKind.SCROLL,expect=listOf(Postcondition.ScrolledDown)),page(),page()).status)
  assertEquals(VerifyStatus.FAILED,Verifier.verify(Action(ActionKind.SCROLL,expect=listOf(Postcondition.ConstraintApplied("price_max","100"))),page(),page(id="doc-1:scroll-2")).status)
 }

 @Test fun newResultsOnlyScrollIsUnconfirmedWhenItsSurfaceChanges(){
  assertEquals(VerifyStatus.AMBIGUOUS,Verifier.verify(Action(ActionKind.SCROLL,expect=listOf(Postcondition.NewResults)),page(),page(id="doc-1:scroll-2")).status)
 }

 @Test fun paginationClickWithUnknownEndGeometryIsAmbiguousButDefiniteNoNewItemsClickFails(){
  val before=page();val after=page(id="doc-1:scroll-2");val ref=AffordanceRef(Role.LOAD_MORE)
  assertEquals(VerifyStatus.AMBIGUOUS,Verifier.verify(Action(ActionKind.CLICK,ref,expect=listOf(Postcondition.anyOf(Postcondition.NewResults,Postcondition.EndOfResults))),before,after).status)
  assertEquals(VerifyStatus.FAILED,Verifier.verify(Action(ActionKind.CLICK,ref,expect=listOf(Postcondition.NewResults)),before,after).status)
 }
}
