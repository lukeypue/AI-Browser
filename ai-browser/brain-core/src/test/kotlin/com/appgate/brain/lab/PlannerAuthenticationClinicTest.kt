package com.appgate.brain.lab
import com.appgate.brain.planner.*
import com.appgate.brain.memory.*
import com.appgate.brain.json.JsonObject
import org.junit.Assert.*
import org.junit.Test
class PlannerAuthenticationClinicTest {
 private class Rejected:PlannerClient{
  var calls=0;override val describe="synthetic"
  override fun complete(instructions:String,input:String,schemaName:String,schema:JsonObject,maxOutputTokens:Int):String{calls++;throw PlannerHttpException(401,"private-body")}
 }
 @Test fun cityAuthenticatesItsRetryControlThroughAFreshStateReader(){
  val storage=InMemoryStorage();val delegate=Rejected();val guarded=AuthenticationGuardedClient.create(PlannerAuthenticationHold(storage)){delegate}!!
  assertTrue(runCatching{guarded.complete("","","test",JsonObject())}.exceptionOrNull() is PlannerHttpException)
  assertTrue(PlannerDiagnosticClinic.authenticationRetryHeld(delegate,storage));assertEquals(1,delegate.calls)
 }
 @Test fun aNonDurableRejectionProducesAFalseDiagnosticFinding(){
  val storage=object:BrainStorage by InMemoryStorage(){override fun write(key:String,value:String){throw java.io.IOException("private-disk")}}
  val delegate=Rejected();val guarded=AuthenticationGuardedClient.create(PlannerAuthenticationHold(storage)){delegate}!!
  assertTrue(runCatching{guarded.complete("","","test",JsonObject())}.exceptionOrNull() is PlannerHttpException)
  assertFalse(PlannerDiagnosticClinic.authenticationRetryHeld(delegate,storage));assertEquals(2,delegate.calls)
 }
}
