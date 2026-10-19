package com.appgate.brain.memory
import com.appgate.brain.model.*
import com.appgate.brain.json.Json
import com.appgate.brain.perception.SpsParser
import com.appgate.brain.test.FakeSite
import org.junit.Assert.*
import org.junit.Test
class RejectedFacetKeyTest {
 private val page=SpsParser().parse(FakeSite().apply { dialogShown=false;navigate("https://fake.market/search?q=Ford",1000) }.observe(1000),1800000000000L)
 private val control=page.byRole(Role.FACET).single()
 private fun action(value:String="PrivateSyntheticValue")=Action(ActionKind.SELECT,AffordanceRef(Role.FACET,control.facetKey,affordanceId=control.id),text=value,effect=EffectClass.MUTATE_LOCAL)
 private fun key(p:SemanticPageState=page,a:Action=action())=FailedStrategies.rejectedFacetSelectKey(p,a)
 @Test fun opaqueHoldSurvivesReloadWithoutPersistingValuesOrIds() {
  val k=key()!!;assertTrue(k.matches(Regex("[0-9a-f]{64}")))
  val site=SiteModel(page.host);FailedStrategies.record(site,k,false,1L)
  val serialized=site.toJson().toString()
  for(privateValue in listOf("PrivateSyntheticValue",control.id,page.url))assertFalse(serialized.contains("\"$privateValue\""))
  assertFalse(FailedStrategies.allowed(SiteModel.fromJson(Json.parseObject(serialized)),k,Long.MAX_VALUE))
 }
 @Test fun aDifferentRequestedOptionRemainsEligible() { assertNotEquals(key(),key(a=action("DifferentSyntheticValue"))) }
 @Test fun chosenSemanticControlChangesRemainEligible() {
  val changed=page.copy(affordances=page.affordances.map { if(it.id==control.id)it.copy(facetKind="changed-kind") else it })
  assertNotEquals(key(),key(changed))
 }
 @Test fun unrelatedResultsAndFreshDomIdsDoNotReopenRejectedCommand() {
  assertEquals(key(),key(page.copy(collections=page.collections.map { if(it.role=="results")it.copy(itemKeys=listOf("new-result")) else it })))
  val changed=page.copy(affordances=page.affordances.map { if(it.id==control.id)it.copy(id="fresh-document-id") else it })
  assertEquals(key(),key(changed,action().copy(target=action().target!!.copy(affordanceId="fresh-document-id"))))
 }
 @Test fun neverHoldsPersonalInputsUnsafeOrUnsettledPages() {
  assertNull(key(a=action().copy(kind=ActionKind.TYPE)))
  assertNull(key(a=action().copy(target=action().target!!.copy(role=Role.SEARCH_BOX))))
  assertNull(key(a=action("x".repeat(129))))
  assertNull(key(page.copy(authWall=true)));assertNull(key(page.copy(settle=Settle.BUSY)))
  assertNull(key(page.copy(affordances=page.affordances.map { if(it.id==control.id)it.copy(sameSite=false) else it })))
 }
 @Test fun punctuationDistinctOptionsNeverShareARejectionIdentity() {
  val keys=listOf("A_B","A-B","A B").map { key(a=action(it)) }
  assertEquals(3,keys.toSet().size)
 }
 @Test fun changedAvailableOptionsCanReopenTheChosenControl() {
  val changed=page.copy(affordances=page.affordances.map { if(it.id==control.id)it.copy(choices=it.choices+"New available option") else it })
  assertNotEquals(key(),key(changed))
 }
}
