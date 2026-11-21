package com.appgate.brain.lab
import com.appgate.brain.json.Json
import com.appgate.brain.test.Fixtures
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
class NonmodalBannerClinicTest {
 @Test fun realBrowserBannerListingLearnsAndRetainsAcrossFreshMemoryReload(){
  val root=Files.createTempDirectory("nonmodal-banner-clinic").toFile()
  try {
   val bundle=Fixtures.observation("nonmodal_banner")
   val report=ItemOutcomeClinic.experiment(bundle,java.io.File(root,"trial"))
   assertTrue(report.optBoolean("all_passed"))
   val rows=report.optArray("cases")!!.objects()
   assertEquals(8,rows.size)
   val successful=rows.filter{it.optString("case_id") in setOf("immediate","delayed")}
   assertEquals(4,successful.size)
   assertTrue(successful.all{it.optInt("clicks")==1&&it.optBoolean("verified")})
   assertTrue(successful.filter{it.optString("run")=="cold"}.all{it.optInt("new_lessons")==1})
   assertTrue(successful.filter{it.optString("run")=="reloaded"}.all{it.optBoolean("retained_before_replay")&&it.optInt("new_lessons")==0})
  }finally{root.deleteRecursively()}
 }
}
