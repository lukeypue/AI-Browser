package com.appgate.tv.browser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
class OffscreenViewportGeometryTest {
 @Test fun logicalViewportSurvivesNormalAndFractionalDisplayDensities() {
  for ((density, expected) in listOf(
   1f to OffscreenViewportGeometry.Pixels(412,915),
   0.75f to OffscreenViewportGeometry.Pixels(309,686),
   2.45f to OffscreenViewportGeometry.Pixels(1009,2242),
   2.625f to OffscreenViewportGeometry.Pixels(1082,2402),
   3.5f to OffscreenViewportGeometry.Pixels(1442,3203),
   4f to OffscreenViewportGeometry.Pixels(1648,3660))) {
   val actual = OffscreenViewportGeometry.physicalPixels(density)
   assertEquals("density $density",expected,actual)
   assertEquals(412.0,actual.width/density.toDouble(),1.0)
   assertEquals(915.0,actual.height/density.toDouble(),1.0)
  }
 }
 @Test fun invalidDensityFallsBackToOneWithoutCrashing() {
  for (density in listOf(Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY,0f,-1f))
   assertEquals(OffscreenViewportGeometry.Pixels(412,915),OffscreenViewportGeometry.physicalPixels(density))
 }
 @Test fun unreasonableDensityCannotAllocateAnUnboundedSurface() {
  for (density in listOf(4.5f,20f,Float.MAX_VALUE)) {
   val pixels = OffscreenViewportGeometry.physicalPixels(density)
   assertEquals(4096,pixels.height)
   assertEquals(1844,pixels.width)
   assertTrue(pixels.width.toLong()*pixels.height < 8L*1024*1024)
  }
 }
 @Test fun tinyPositiveDensityNeverProducesAnEmptySurface() {
  assertEquals(OffscreenViewportGeometry.Pixels(1,1),OffscreenViewportGeometry.physicalPixels(0.00001f))
 }
}
