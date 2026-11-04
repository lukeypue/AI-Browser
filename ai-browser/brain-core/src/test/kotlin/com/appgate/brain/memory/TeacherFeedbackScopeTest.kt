package com.appgate.brain.memory
import com.appgate.brain.json.*
import com.appgate.brain.model.*
import com.appgate.brain.perception.SpsParser
import com.appgate.brain.test.FakeSite
import org.junit.Assert.*
import org.junit.Test

class TeacherFeedbackScopeTest {
 private val now=1_800_000_000_000L
 private fun page()=SpsParser().parse(FakeSite().apply{dialogShown=false;navigate("https://fake.market/search?q=Ford",1000)}.observe(1000),now)
 private fun item(arg:String)=listOf(Step(StepKind.CLICK,Role.RESULT_ITEM,arg=arg,expect=listOf(Postcondition.PageTypeIs(PageType.DETAIL))))
 @Test fun boundListingArgumentsMustNotCollapseIntoACollectionWideFailure() {
  val a=FailedStrategies.approach(page(),"open_item",item("private-item-A"),emptyMap())!!
  val b=FailedStrategies.approach(page(),"open_item",item("private-item-B"),emptyMap())!!
  assertNotEquals("target scope must follow the actual bound execution argument",a.context,b.context)
  val site=SiteModel(page().host)
  FailedStrategies.record(site,"aaaaaaaa",false,now,a)
  assertTrue("no exact target means no transferable negative evidence",FailedStrategies.feedback(site,page(),"open_item",emptyMap()).isEmpty())
  assertEquals(1,FailedStrategies.feedback(site,page(),"open_item",mapOf("item" to "private-item-A")).size)
  assertTrue(FailedStrategies.feedback(site,page(),"open_item",mapOf("item" to "private-item-B")).isEmpty())
 }
 @Test fun optionalCorruptFeedbackCannotDiscardALegacyHoldOrReachAModel() {
  val raw=JsonObject().put("host","fixture.market").put("strategy_failures",JsonArray().add(JsonObject()
   .put("key","aaaaaaaa").put("count",1).put("at",now).put("feedback",JsonObject().put("context","aaaaaaaa")
    .put("steps",JsonArray().add(JsonObject().put("kind","CLICK").put("role","SEND").put("arg","private-token"))))))
  val site=SiteModel.fromJson(raw)
  assertFalse(FailedStrategies.allowed(site,"aaaaaaaa",now))
  assertNull(site.strategyFailures.getValue("aaaaaaaa").feedback)
  assertTrue(FailedStrategies.feedback(site,page(),"search",emptyMap()).isEmpty())
 }
 @Test fun storedDescriptorsDropEveryLiteralAndChangedControlsDoNotInheritThem() {
  val page=page();val site=SiteModel(page.host)
  val steps=listOf(Step(StepKind.TYPE,Role.SEARCH_BOX,arg="private-query",nameHint="private-label",submit=true,
   expect=listOf(Postcondition.ValueIs("query","private-query"))))
  val description=FailedStrategies.approach(page,"search",steps,emptyMap())!!
  FailedStrategies.record(site,"aaaaaaaa",false,now,description)
  val stored=SiteModel.fromJson(Json.parseObject(site.toJson().toString()))
  val feedback=FailedStrategies.feedback(stored,page,"search",emptyMap())
  assertEquals(1,feedback.size)
  val public=feedback.single().publicJson().toString()
  for(private in listOf("private-query","private-label","https://","context","aaaaaaaa"))assertFalse(public.contains(private))
  val changed=page.copy(affordances=page.affordances+Affordance("new",Role.SUBMIT,tag="a"))
  assertTrue(FailedStrategies.feedback(stored,changed,"search",emptyMap()).isEmpty())
  assertTrue(FailedStrategies.feedback(SiteModel("another.market"),page,"search",emptyMap()).isEmpty())
  FailedStrategies.record(stored,"aaaaaaaa",true,now+1)
  assertTrue(FailedStrategies.feedback(stored,page,"search",emptyMap()).isEmpty())
 }
 @Test fun legacyBackfillPreservesTheOriginalHoldCountAndTime() {
  val site=SiteModel(page().host);FailedStrategies.record(site,"aaaaaaaa",false,now)
  FailedStrategies.explainHeld(site,"aaaaaaaa",FailedStrategies.approach(page(),"search",listOf(Step(StepKind.TYPE,Role.SEARCH_BOX,submit=true)),emptyMap()))
  val row=site.strategyFailures.getValue("aaaaaaaa")
  assertEquals(1,row.count);assertEquals(now,row.at);assertNotNull(row.feedback)
 }
 @Test fun conflictingBoundTargetAndTaskItemMustNotCreateNegativeTeachingEvidence() {
  assertNull(FailedStrategies.approach(page(),"open_item",item("private-item-A"),mapOf("item" to "private-item-B")))
  assertNotNull(FailedStrategies.approach(page(),"open_item",item("private-item-A"),mapOf("item" to "private-item-A")))
  assertNotNull(FailedStrategies.approach(page(),"open_item",item("\$item"),mapOf("item" to "private-item-A")))
  assertNull(FailedStrategies.approach(page(),"open_item",item("\$other"),mapOf("other" to "private-item-A","item" to "private-item-B")))
  val site=SiteModel(page().host)
  FailedStrategies.record(site,"aaaaaaaa",false,now,FailedStrategies.approach(page(),"open_item",item("private-item-A"),mapOf("item" to "private-item-B")))
  val restored=SiteModel.fromJson(Json.parseObject(site.toJson().toString()))
  assertFalse(FailedStrategies.allowed(restored,"aaaaaaaa",now+100000))
  assertEquals(1,restored.strategyFailures.getValue("aaaaaaaa").count)
  assertNull(restored.strategyFailures.getValue("aaaaaaaa").feedback)
 }
 @Test fun aWellFormedCommitRoleIsRejectedBeforeProviderProjection() {
  val unsafe=FailedApproach("aaaaaaaa",listOf(FailureStep(StepKind.CLICK,Role.SEND,null,false,false)))
  assertNull(FailedApproach.fromJson(unsafe.toJson()))
  val site=SiteModel(page().host)
  FailedStrategies.record(site,"aaaaaaaa",false,now,unsafe)
  assertTrue(FailedStrategies.feedback(site,page(),"search",emptyMap()).isEmpty())
 }
}
