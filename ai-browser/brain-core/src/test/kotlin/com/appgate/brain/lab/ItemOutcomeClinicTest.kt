package com.appgate.brain.lab
import com.appgate.brain.test.Fixtures
import java.nio.file.Files
import java.io.File
import org.junit.Assert.*
import org.junit.Test
class ItemOutcomeClinicTest {
 @Test fun delayedBrowserModalAcquiresThenRetainsOnlyVerifiedLearning(){
  val root=Files.createTempDirectory("synthetic-item-clinic-").toFile()
  try{
   val result=ItemOutcomeClinic.experiment(Fixtures.observation("delayed_item"),File(root,"memory"))
   assertTrue(result.optBoolean("all_passed"));assertEquals(0,result.optInt("external_ai_requests"))
   val cases=result.optArray("cases")!!.objects();assertEquals(8,cases.size)
   assertTrue(cases.filter{it.optString("run")=="reloaded"}.all{it.optBoolean("verified") && it.optBoolean("retained_before_replay") && it.optInt("new_lessons")==0})
   assertTrue(cases.filter{it.optString("case_id") in setOf("overlay","human","challenge","offsite")}.none{it.optBoolean("verified")})
  }finally{root.deleteRecursively()}
 }
}
