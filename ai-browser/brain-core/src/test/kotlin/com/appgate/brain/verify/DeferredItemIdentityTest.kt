package com.appgate.brain.verify
import com.appgate.brain.test.Fixtures
import com.appgate.brain.perception.SpsParser
import com.appgate.brain.model.*
import org.junit.Assert.*
import org.junit.Test
class DeferredItemIdentityTest {
 private fun pages()=Fixtures.observation("delayed_item").let{SpsParser().parse(it.optObject("before")!!) to SpsParser().parse(it.optObject("detail")!!)}
 @Test fun joinedCardCurrencyAndSpacedDetailAreTheSameVisibleIdentity(){
  val(before,after)=pages();val selected=before.results!!.items.first()
  assertTrue(Verifier.deferredItemMatches(Action(ActionKind.CLICK,AffordanceRef(Role.RESULT_ITEM,itemKey=selected.key)),before,after))
 }
 @Test fun duplicateNamesDoNotIdentifyOneListingInASameUrlModal(){
  val(before,after)=pages();val collection=before.results!!
  val items=collection.items.map{it.copy(title="Synthetic bicycle")}
  val duplicate=before.copy(collections=listOf(collection.copy(items=items)))
  val detail=after.copy(title="",detailText="Synthetic bicycle description and details.",url=before.url)
  assertFalse(Verifier.deferredItemMatches(Action(ActionKind.CLICK,AffordanceRef(Role.RESULT_ITEM,itemKey=items.first().key)),duplicate,detail))
 }
 @Test fun uniqueSelectedRouteCanDisambiguateMatchingDuplicateNames(){
  val(before,after)=pages();val collection=before.results!!
  val items=collection.items.map{it.copy(title="Synthetic bicycle")}
  val duplicate=before.copy(collections=listOf(collection.copy(items=items)))
  val detail=after.copy(title="",detailText="Synthetic bicycle description and details.",url="https://"+before.host+items.first().hrefPath)
  assertTrue(Verifier.deferredItemMatches(Action(ActionKind.CLICK,AffordanceRef(Role.RESULT_ITEM,itemKey=items.first().key)),duplicate,detail))
 }
}
