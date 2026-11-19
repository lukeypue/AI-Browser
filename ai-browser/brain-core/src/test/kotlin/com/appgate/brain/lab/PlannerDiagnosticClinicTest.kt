package com.appgate.brain.lab
import org.junit.Assert.*
import org.junit.Test
class PlannerDiagnosticClinicTest {
 @Test fun realEngineControlsReportClosedFailureCausesWithoutExternalRequests() {
  val clinic:com.appgate.brain.json.JsonObject?=PlannerDiagnosticClinic.run()
  assertNotNull("City reports must exercise actual engine failure reporting",clinic)
  assertEquals(0,clinic!!.optInt("external_requests"));assertTrue(clinic.optBoolean("synthetic_only"))
  val cases=clinic.optArray("cases")!!.objects()
  assertEquals(listOf("authentication","permission","rate_limit","server","timeout","invalid_response","unknown","returned"),cases.map{it.optString("case_id")})
  assertTrue(cases.all{it.optBoolean("diagnostic_expectation_met") && it.optInt("client_calls")==1})
  assertEquals("AUTHENTICATION",cases.first().optObject("planner_result")!!.optString("planner_failure"))
  assertEquals(401,cases.first().optObject("planner_result")!!.optObject("counters")!!.optInt("http_status"))
  assertFalse(clinic.toString().contains("private"));assertFalse(clinic.toString().contains("https://"))
 }
}
