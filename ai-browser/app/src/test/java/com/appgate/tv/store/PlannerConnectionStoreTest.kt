package com.appgate.tv.store
import com.appgate.brain.planner.*
import com.appgate.brain.json.JsonObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class PlannerConnectionStoreTest {
 private fun reject(h:PlannerAuthenticationHold){
  val c=AuthenticationGuardedClient(object:PlannerClient{
   override val describe="fixture"
   override fun complete(instructions:String,input:String,schemaName:String,schema:JsonObject,maxOutputTokens:Int):String=throw PlannerHttpException(401)
  },h)
  assertTrue(runCatching{c.complete("","","test",JsonObject())}.isFailure)
 }
 @Test fun uiAndServiceShareAHoldOutsideLearnedMemory(){
  val dir=Files.createTempDirectory("app-auth-hold-").toFile()
  try{
   val service=PlannerConnectionStore.forDirectory(dir);reject(service)
   val ui=PlannerConnectionStore.forDirectory(dir);assertSame(service,ui);assertEquals(PlannerConnectionStatus.AUTHENTICATION_REJECTED,ui.status())
   assertTrue(java.io.File(dir,"planner-connection").isDirectory);assertFalse(java.io.File(dir,"brain").exists())
   ui.allowRetry();assertEquals(PlannerConnectionStatus.READY,service.status())
  }finally{dir.deleteRecursively()}
 }
 @Test fun separateAppDirectoriesDoNotShareRejection(){
  val a=Files.createTempDirectory("app-auth-a-").toFile();val b=Files.createTempDirectory("app-auth-b-").toFile()
  try{reject(PlannerConnectionStore.forDirectory(a));assertEquals(PlannerConnectionStatus.READY,PlannerConnectionStore.forDirectory(b).status())}finally{a.deleteRecursively();b.deleteRecursively()}
 }
}
