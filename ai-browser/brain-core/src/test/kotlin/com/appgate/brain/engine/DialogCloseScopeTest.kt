package com.appgate.brain.engine
import com.appgate.brain.model.*
import com.appgate.brain.skills.Grounder
import org.junit.Assert.*
import org.junit.Test
class DialogCloseScopeTest {
 private fun page(controls: List<Affordance>, dialog: Boolean = true) = SemanticPageState(
  host="fixture.invalid", url="https://fixture.invalid/", urlPattern="/", title="", pageType=PageType.DIALOG,
  pageTypeConfidence=1.0, regions=emptyList(), affordances=controls, collections=emptyList(), constraintsActive=emptyMap(),
  settle=Settle.IDLE, challenge=false, authWall=false, dialogOpen=dialog, hash="fixture")
 private val background=Affordance("background-close",Role.CLOSE,name="Close",roleScore=1.0)
 @Test fun backgroundCloseIsNotADialogTargetWhenNoDialogCloseExists() {
  val s=page(listOf(background))
  assertTrue(Grounder(null).candidates(s,Role.CLOSE).isEmpty())
 }
 @Test fun backgroundCloseDoesNotCreateADismissalLesson() {
  assertNull(LearningOpportunities.target(page(listOf(background)),"dismiss_dialog"))
 }
 @Test fun scopedDialogCloseRemainsUsable() {
  val close=background.copy(id="dialog-close",regionRole=RegionRole.DIALOG)
  val s=page(listOf(background,close))
  assertEquals(listOf("dialog-close"),Grounder(null).candidates(s,Role.CLOSE).map{it.affordance.id})
  assertEquals("dismiss_dialog",LearningOpportunities.target(s,"dismiss_dialog")?.skillId)
 }
 @Test fun ordinaryCloseOutsideADialogRemainsUsable() {
  assertEquals("background-close",Grounder(null).candidates(page(listOf(background),false),Role.CLOSE).first().affordance.id)
 }
 @Test fun genericButtonInDialogCannotSubstituteForClose() {
  val s=page(listOf(background,background.copy(id="generic",role=Role.GENERIC_BUTTON,regionRole=RegionRole.DIALOG)))
  assertTrue(Grounder(null).candidates(s,Role.CLOSE).isEmpty())
 }
}
