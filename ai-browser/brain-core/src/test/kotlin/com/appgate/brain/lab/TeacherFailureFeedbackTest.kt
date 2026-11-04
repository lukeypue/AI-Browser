package com.appgate.brain.lab

import com.appgate.brain.json.*
import com.appgate.brain.memory.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** A missing memory-to-teacher connection must fail, even though exact execution holds work. */
class TeacherFailureFeedbackTest {
 @Test fun actualTeacherRequestExplainsHeldProceduresWithoutSendingOpaqueKeysOrValues() {
  val dir=Files.createTempDirectory("teacher-feedback-").toFile()
  try {
   FrontierScreen().capture(dir)
   val request=Json.parseObject(File(dir,"teacher-request.json").readText())
   val task=request.optObject("input")!!.optObject("task")!!
   val held=task.optArray("held_approaches")
   assertNotNull("the teacher must see the actual failed procedures, not only opaque execution holds",held)
   val rows=held!!.objects()
   assertTrue("both failed Enter and separate-submit approaches must be represented",rows.size>=2)
   val steps=rows.flatMap{it.optArray("steps")!!.objects()}
   assertTrue(steps.any{it.optString("kind")=="TYPE" && it.optString("role")=="SEARCH_BOX" && it.optBoolean("submit")})
   assertTrue(steps.any{it.optString("kind")=="CLICK" && it.optString("role")=="SUBMIT"})
   val text=held.toString()
   assertFalse(text.contains(FrontierScreen.TRAIN_QUERY))
   for(forbidden in listOf("context","key","arg","name","url","at"))assertTrue("private field: "+forbidden,rows.all{!it.has(forbidden)})
   val store=FileBrainStorage(File(dir,"capture-memory"))
   val restored=Memory(store){1_800_000_100_000L}.site(FrontierScreen.TRAIN_HOST)
   val failures=restored.toJson().optArray("strategy_failures")!!.objects()
   assertTrue("feedback must survive a fresh file-backed memory instance",failures.any{it.optObject("feedback")!=null})
  } finally {dir.deleteRecursively()}
 }
}
