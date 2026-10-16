package com.appgate.brain.diagnostics
import com.appgate.brain.engine.*
import com.appgate.brain.memory.*
import com.appgate.brain.model.*
import com.appgate.brain.json.*
import com.appgate.brain.test.FakeSite
import org.junit.Assert.*
import org.junit.Test
class TeacherEvidenceTest {
 @Test fun completedTaskExportsBoundedTeacherAttemptsWithoutProviderCredentials() {
  val fake=FakeSite().apply{dialogShown=false}
  val memory=Memory(InMemoryStorage())
  var outcome:JsonObject?=null
  val events=object:EngineEvents{override fun diagnostic(host:String,kind:String,data:JsonObject){if(kind=="learning_outcome")outcome=data}}
  val engine=BrainEngine(fake,memory,{null},events,EngineConfig(pacingOverrideMs=0,maxDecisionsWithoutProgress=0))
  val ledger=TaskLedger("teacher-evidence",Goal("g",GoalIntent.LEARN_SITE,"practice","Ford",emptyList(),budget=Budget(llmCalls=5)),fake.host,"https://fake.market/")
  ledger.llmCalls=2
  engine.runTask(ledger,EngineMode.TRAIN)
  assertNotNull(outcome);assertEquals(2,outcome!!.optInt("teacher_attempts"))
 }
}
