package com.appgate.brain.planner

import com.appgate.brain.json.JsonObject
import com.appgate.brain.memory.TeacherBudgetExceeded
import java.io.IOException
import java.net.SocketTimeoutException

/** Closed failure metadata. Never inspect or serialize exception messages/causes. */
enum class PlannerFailure { AUTHENTICATION, PERMISSION, RATE_LIMIT, SERVER, HTTP_OTHER, TIMEOUT, TRANSPORT, INVALID_RESPONSE, BUDGET, REFUSED, UNKNOWN }
class PlannerDiagnosticException(val failure:PlannerFailure,message:String):IOException(message)
object PlannerFailureDiagnostics {
 val summaryKeys=listOf("planner_auth_errors","planner_permission_errors","planner_rate_errors","planner_server_errors","planner_transport_errors","planner_response_errors","planner_budget_errors","planner_other_errors")
 fun summaryKey(failure:String):String=when(failure) {
  "AUTHENTICATION"->"planner_auth_errors";"PERMISSION"->"planner_permission_errors";"RATE_LIMIT"->"planner_rate_errors";"SERVER"->"planner_server_errors"
  "TIMEOUT","TRANSPORT"->"planner_transport_errors";"INVALID_RESPONSE"->"planner_response_errors";"BUDGET"->"planner_budget_errors";else->"planner_other_errors"
 }
 fun failure(error:Exception):PlannerFailure=when(error) {
  is PlannerHttpException->when(error.code){401->PlannerFailure.AUTHENTICATION;403->PlannerFailure.PERMISSION;429->PlannerFailure.RATE_LIMIT;in 500..599->PlannerFailure.SERVER;else->PlannerFailure.HTTP_OTHER}
  is PlannerDiagnosticException->error.failure
  is SocketTimeoutException->PlannerFailure.TIMEOUT
  is TeacherBudgetExceeded->PlannerFailure.BUDGET
  is PlannerRefused->PlannerFailure.REFUSED
  is IOException->PlannerFailure.TRANSPORT
  else->PlannerFailure.UNKNOWN
 }
 fun metadata(error:Exception,provider:String,elapsedMs:Long):JsonObject=JsonObject()
  .put("planner_failure",failure(error).name)
  .put("planner_provider",provider.takeIf{v->PlannerProvider.values().any{it.name==v}}?:"UNKNOWN")
  .put("planner_elapsed_ms",elapsedMs.coerceIn(0,1_000_000))
  .also{if(error is PlannerHttpException && error.code in 100..599)it.put("http_status",error.code)}
}
