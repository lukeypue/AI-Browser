package com.appgate.brain.perception
import com.appgate.brain.model.*
import com.appgate.brain.engine.LearningOpportunities
import com.appgate.brain.test.Fixtures
import org.junit.Assert.*
import org.junit.Test
class NonmodalBannerTest {
 @Test fun shortViewportBannerDoesNotHideDetailOrExpansionOpportunity(){
  val s=SpsParser().parse(Fixtures.observation("nonmodal_banner_page"))
  assertEquals(PageType.DETAIL,s.pageType);assertFalse(s.dialogOpen)
  assertTrue(s.detailText.contains("Synthetic bicycle 1"))
  assertFalse(s.detailText.contains("Explore the synthetic catalog"))
  assertEquals("expand_description",LearningOpportunities.target(s,"expand_description")?.skillId)
 }
}
