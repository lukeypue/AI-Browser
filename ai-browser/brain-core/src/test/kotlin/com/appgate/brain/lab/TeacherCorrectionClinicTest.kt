package com.appgate.brain.lab
import com.appgate.brain.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
class TeacherCorrectionClinicTest {
 @Test fun capturedCorrectionIsTheActualScopedEngineRequestAfterScriptedRejection(){
  val dir=Files.createTempDirectory("correction-capture-").toFile()
  try{
   val report=FrontierScreen().captureCorrection(dir)
   assertTrue(report.optBoolean("captured"));assertEquals("SCRIPTED_NEGATIVE_STUB",report.optString("first_reply_kind"))
   val request=Json.parseObject(File(dir,"teacher-request.json").readText());val task=request.optObject("input")!!.optObject("task")!!
   assertEquals("search",task.optString("requested_capability"))
   assertEquals("GIVE_UP",task.optArray("proposal_rejections")!!.objects().single().optString("reason"))
   assertTrue("actual local failures must remain explained",task.optArray("held_approaches")!!.size>=2)
   assertEquals(2,report.optObject("trial")!!.optInt("teacher_calls"))
  }finally{dir.deleteRecursively()}
 }
 @Test fun oneOffSuccessWithUnverifiedPrerequisiteDoesNotProveSavedLearning(){
  val dir=Files.createTempDirectory("bad-prerequisite-").toFile()
  try{
   val program="""{"steps":[{"kind":"CLICK","role":"TAB","expect":["RESULTS_CHANGED","URL_CHANGED"]},{"kind":"TYPE","role":"SEARCH_BOX","arg":"${'$'}query","submit":true,"expect":["PAGE_IS_RESULTS","RESULTS_CHANGED"]}],"confidence":0.99}"""
   val report=FrontierScreen().run(dir,program,"SCRIPTED_READINESS",correction=true)
   assertTrue("the task may succeed after a partial prerequisite",report.optBoolean("training_oracle_success"))
   assertEquals("an unverified necessary prerequisite cannot become a saved program",0,report.optInt("compiled_skills"))
   assertEquals(0,report.optInt("retained_successes"));assertEquals(0,report.optInt("evaluation_teacher_calls"))
  }finally{dir.deleteRecursively()}
 }

}
