package com.appgate.brain.lab
import com.appgate.brain.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
class CodexTeacherTrialTest {
 private val proposal="""{"steps":[{"kind":"DISMISS","role":"CLOSE","facet_key":null,"arg":null,"name_hint":null,"optional":false,"submit":false,"expect":["DIALOG_CLOSED"]}],"confidence":1,"needs_human":null,"give_up":false,"rationale":"synthetic","vocabulary":[]}"""
 @Test fun teacherProposalMustVerifyThenTransferWithoutEvaluationTeacherCalls(){
  val r=CodexDialogClinic(proposal).experiment(File("src/lab/fixtures/dialog-clinic.json"))
  assertEquals("CODEX_EXEC_PROPOSAL",r.optString("teacher"));assertEquals(4,r.optInt("compiled_transfer_uses"))
  assertEquals(0,r.optInt("false_mastery"));assertEquals(0,r.optInt("evaluation_teacher_calls"))
 }
 @Test fun unsafeOrOptionalTeacherProposalIsRejected(){
  for(p in listOf(proposal.replace("DISMISS","COMMIT"),proposal.replace("CLOSE","LOGIN"),proposal.replace("false,\"submit\"","true,\"submit\""))){
   assertThrows(IllegalArgumentException::class.java){CodexDialogClinic(p).experiment(File("src/lab/fixtures/dialog-clinic.json"))}
  }
 }
}
