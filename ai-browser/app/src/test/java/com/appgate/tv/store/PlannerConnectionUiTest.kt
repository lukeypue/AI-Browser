package com.appgate.tv.store
import com.appgate.brain.planner.PlannerConnectionStatus
import org.junit.Assert.*
import org.junit.Test
class PlannerConnectionUiTest {
 @Test fun explicitLocalOnlyTakesEffectBeforeFailingConnectionSave(){
  var enabled=true;var duringSave=true
  val result=PlannerConnectionUi.applySettings(false,{enabled=it}){duringSave=enabled;throw java.io.IOException("private-save")}
  assertTrue(result.isFailure);assertFalse(duringSave);assertFalse(enabled)
 }
 @Test fun failedEnableSaveCannotLeaveOldAiEnabled(){
  var enabled=true
  val result=PlannerConnectionUi.applySettings(true,{enabled=it}){throw java.io.IOException("private-save")}
  assertTrue(result.isFailure);assertFalse(enabled)
 }
 @Test fun successfulSaveAppliesChosenModeOnlyAfterConnectionWrites(){
  for(choice in listOf(false,true)){
   var enabled=true;var duringSave=true
   val result=PlannerConnectionUi.applySettings(choice,{enabled=it}){duringSave=enabled}
   assertTrue(result.isSuccess);assertFalse(duringSave);assertEquals(choice,enabled)
  }
 }
 @Test fun historicalErrorsDoNotDescribeARepairedConnectionAsPaused(){
  val ready=PlannerConnectionUi.testSummaryNote(PlannerConnectionStatus.READY,1,true)
  assertTrue(ready.contains("earlier",ignoreCase=true));assertFalse(ready.contains("paused",ignoreCase=true))
  val rejected=PlannerConnectionUi.testSummaryNote(PlannerConnectionStatus.AUTHENTICATION_REJECTED,1,true)
  assertTrue(rejected.contains("At test end"));assertTrue(rejected.contains("paused"))
 }
 @Test fun localOnlyEndStateDoesNotClaimAiIsAllowed(){
  val note=PlannerConnectionUi.testSummaryNote(PlannerConnectionStatus.READY,1,false)
  assertTrue(note.contains("local-only"));assertFalse(note.contains("allowed to retry"))
 }
 @Test fun explicitOffIsAppliedBeforeSettingsValidationCanReturn(){
  var enabled=true;PlannerConnectionUi.applyDisableSelection(false){enabled=it};assertFalse(enabled)
  PlannerConnectionUi.applyDisableSelection(true){enabled=it};assertFalse(enabled)
 }

}
