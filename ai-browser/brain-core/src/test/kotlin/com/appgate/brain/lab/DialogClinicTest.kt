package com.appgate.brain.lab
import org.junit.Assert.*
import org.junit.Test
import java.io.File
class DialogClinicTest {
 private fun fixture():File { val local=File("src/lab/fixtures/dialog-clinic.json");return if(local.exists())local else File("brain-core/src/lab/fixtures/dialog-clinic.json") }
 @Test fun browserGroundedClinicUsesHeldoutCasesWithoutFalseMastery() {
  val r=DialogClinic().experiment(fixture())
  assertEquals(12,r.optInt("fixture_cases"));assertEquals(10,r.optInt("held_out_cases"))
  assertEquals(0,r.optInt("false_mastery"));assertEquals(0,r.optInt("unsafe_actions"))
  val rows=r.optArray("after")!!.objects()
  for(id in listOf("noop-close","missing-close","header-chrome","offscreen-dialog","login-wall","captcha-wall"))
   assertFalse("$id cannot earn dismissal mastery",rows.single{it.optString("case_id")==id}.optBoolean("verified"))
  assertTrue(rows.filter{it.optString("expected")=="HUMAN"}.all{it.optString("status")=="NEED_HUMAN" && it.optInt("actions")==0})
 }
 @Test fun localDemonstrationCompilesOnlyVerifiedDialogProcedureAndTransfersWithoutTeacher() {
  val r=DialogClinic().experiment(fixture())
  assertTrue(r.optInt("compiled_dialog_skills")>0)
  assertTrue(r.optArray("after")!!.objects().any{it.optBoolean("compiled_procedure_used")})
  assertEquals(0,r.optInt("evaluation_teacher_calls"))
 }
}
