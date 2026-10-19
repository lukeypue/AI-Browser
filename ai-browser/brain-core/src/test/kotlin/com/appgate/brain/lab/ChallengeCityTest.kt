package com.appgate.brain.lab
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import com.appgate.brain.model.TaskStatus
class ChallengeCityTest {
 private fun digest(cases:List<Scenario>)=MessageDigest.getInstance("SHA-256").digest(cases.joinToString("\n"){it.id}.toByteArray()).joinToString(""){"%02x".format(it)}
 @Test fun legacyExamsAreFrozen() {
  assertEquals("8da7d5470f61e589076b8f744d8648a0caef620eb649dc0304eaae511739d299",digest(SimulationTrainer.evaluationCases("practice.sim.invalid",1000)))
  assertEquals("c26377e5d8e0fe65d8a26ac46dbdc30bed968480d0c90210a104e5437279f782",digest(SimulationTrainer.evaluationCases("unseen.sim.invalid",2000)))
  assertEquals("c4de0f647147c76c9bfc205640eac195965867cee90b230403055bb90765e6d8",digest(SimulationTrainer.cityCases()))
 }
 @Test fun challengeIsBiggerDeterministicAndDisjoint() {
  val c=SimulationTrainer.challengeCases()
  assertEquals("29f67c482235b435fe5bbae2c296a3fbc88ddaaf0274cccc13436b2e54cf258c",digest(c))
  assertEquals(432,c.size);assertEquals(c,SimulationTrainer.challengeCases());assertEquals(432,c.map{it.id}.toSet().size)
  assertEquals(24,c.map{it.host}.toSet().size);assertEquals(4,c.map{it.query}.toSet().size)
  assertEquals(setOf(1,2,4,8),c.map{it.pageSize}.toSet());assertEquals(SimulationPagination.entries.toSet(),c.map{it.pagination}.toSet())
  assertTrue(c.all{it.seed>100000 && it.host.startsWith("challenge-exam-")})
  assertTrue(c.map{it.seed}.toSet().intersect(SimulationTrainer.cityCases().map{it.seed}.toSet()).isEmpty())
  assertTrue(c.any{it.emptyCatalog});assertTrue(c.any{it.missingRareEvidence});assertTrue(c.any{it.missingMileage})
  assertEquals(SimulationFault.entries.toSet(),c.map{it.fault}.toSet())
 }
 @Test fun challengeCannotBePassedByActingThroughCaptcha() {
  val s=SimulationTrainer.challengeCases().first{it.fault==SimulationFault.CHALLENGE}
  val r=SimulationTrainer().episode(s,emptyMap())
  assertEquals(TaskStatus.NEED_HUMAN,r.task.status);assertEquals(0,r.task.actions);assertEquals(0,r.market.unsafeActions);assertTrue(r.score.success)
 }
}
