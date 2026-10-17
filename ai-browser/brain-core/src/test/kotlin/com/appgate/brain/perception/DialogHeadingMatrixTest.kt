package com.appgate.brain.perception

import com.appgate.brain.engine.*

import com.appgate.brain.diagnostics.*
import com.appgate.brain.json.*
import com.appgate.brain.model.*
import com.appgate.brain.perception.SpsParser
import org.junit.Assert.*
import org.junit.Test

/** Hermetic parser projections of the native browser heading/scope regression fixtures. */
class DialogHeadingMatrixTest {
 private fun page(id:String):SemanticPageState {
  val matrix=com.appgate.brain.test.Fixtures.observation("dialog_heading_matrix")
  return SpsParser().parse(matrix.optObject(id)!!,1000)
 }
 @Test fun normalH2ItemModalWithVisibleFactsIsDetail() {
  val p=page("item_h2");assertTrue(p.dialogOpen);assertEquals(PageType.DETAIL,p.pageType);assertTrue(p.detailText.contains("Synthetic vehicle"))
 }
 @Test fun normalH3ItemModalWithSameVisibleFactsShouldAlsoBeDetail() {
  val p=page("item_h3");assertTrue(p.dialogOpen)
  assertEquals("heading level alone must not hide the same visible listing evidence",PageType.DETAIL,p.pageType)
 }
 @Test fun normalAriaLevelTwoItemModalWithSameVisibleFactsShouldAlsoBeDetail() {
  val p=page("item_role_heading_2");assertTrue(p.dialogOpen);assertEquals(PageType.DETAIL,p.pageType)
 }
 @Test fun ordinaryConsentWithOnlyBackgroundPriceRemainsOrdinaryDialog() {
  val p=page("ordinary_consent");assertEquals(PageType.DIALOG,p.pageType);assertFalse(p.isHumanOnly);assertEquals("",p.detailText)
 }
 @Test fun passwordLoginDialogRemainsHumanOnly() {
  val p=page("login");assertEquals(PageType.AUTH_WALL,p.pageType);assertTrue(p.isHumanOnly);assertEquals("",p.detailText)
 }
 @Test fun verificationChallengeDialogRemainsHumanOnly() {
  val p=page("challenge");assertEquals(PageType.CHALLENGE,p.pageType);assertTrue(p.isHumanOnly);assertEquals("",p.detailText)
 }
 @Test fun exportedActionPageTypeDescribesAfterNotBefore() {
  val after=page("ordinary_consent");val before=after.copy(pageType=PageType.RESULTS,dialogOpen=false)
  val goal=com.appgate.brain.goal.GoalParser.parse("synthetic vehicle").copy(intent=GoalIntent.LEARN_SITE)
  val ledger=TaskLedger("synthetic",goal,after.host,after.url).apply{lesson="expand_description";programCapability="open_item"}
  val x=SemanticDiagnostics.action(ledger,before,after,Step(StepKind.CLICK,Role.RESULT_ITEM),VerifyStatus.FAILED,DiagnosticCode.UNMET_EXPECTATION)
  val c=PhoneTestContext("12345678-1234-4234-8234-123456789abc",1000,100000,0,appRevision="a".repeat(40),versionCode=918)
  val raw=Json.obj("kind" to "action_outcome","host" to after.host,"elapsed" to 1500,"boot" to 0,"test_id" to c.id,"x" to x)
  val event=PhoneReportExporter.export(c,listOf(raw),2000,101000,0,false,"a".repeat(40),918).optArray("events")!!.objects().single()
  assertEquals("RESULTS",x.optObject("before")!!.optString("page_type"))
  assertEquals("DIALOG",x.optObject("after")!!.optString("page_type"))
  assertEquals("DIALOG",event.optString("page_type"))
 }

 @Test fun hiddenAndBackgroundHeadingsDoNotEstablishDialogDetail() {
  for(id in listOf("hidden_heading","background_heading")){
   val p=page(id);assertEquals(PageType.DIALOG,p.pageType);assertEquals("",p.detailText)
  }
 }
 @Test fun smallOrShortOrdinaryDialogCannotBorrowBackgroundListingEvidence() {
  for(id in listOf("small_consent","short_consent")){
   val p=page(id);assertTrue(p.dialogOpen);assertNotEquals(PageType.DETAIL,p.pageType);assertEquals("",p.detailText)
  }
 }
}
