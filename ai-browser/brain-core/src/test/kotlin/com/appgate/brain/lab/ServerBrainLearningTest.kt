package com.appgate.brain.lab
import com.appgate.brain.json.*
import org.junit.Assert.*
import org.junit.Test
class ServerBrainLearningTest {
 private val proposal = """{"schema_version":1,"capability":"search","steps":[{"kind":"TYPE","role":"SEARCH_BOX","submit":false},{"kind":"CLICK","role":"SUBMIT","submit":false}]}"""
 @Test fun rejectsUnsafeWithoutChangingMemory() {
  val lab=ServerBrainLearning(); val prior=mapOf("marker" to "{}")
  for(bad in listOf(proposal.replace("\"submit\":false","\"submit\":true"),proposal.replace("SUBMIT","SEND"),proposal.replace("\"capability\":\"search\"","\"capability\":\"search\",\"url\":\"x\""),proposal.replace("\"kind\":\"TYPE\"","\"text\":\"secret\",\"kind\":\"TYPE\""))) {
   val r=lab.cycle(bad,prior); assertFalse(r.report.optBoolean("proposal_valid")); assertEquals(prior,r.snapshot); assertFalse(r.report.optBoolean("promotion_recommended"))
  }
 }
 @Test fun persistentSkillReloadIsFrozenAndReusable() {
  val lab=ServerBrainLearning(); val first=lab.cycle(proposal,emptyMap())
  assertTrue(first.report.optBoolean("proposal_valid")); assertTrue(first.report.optBoolean("promotion_recommended"))
  assertFalse(first.snapshot.keys.any{it.startsWith("ledger/")})
  val reloaded=ServerBrainLearning.decodeMemory(ServerBrainLearning.encodeMemory(first.snapshot))
  val second=lab.cycle(null,reloaded); assertEquals(first.snapshot,second.snapshot)
  assertFalse(second.report.optBoolean("promotion_recommended"))
  val totals=second.report.optObject("totals")!!
  assertTrue(totals.optObject("current")!!.optInt("compiled_reuse")>0)
  assertTrue(totals.optObject("current")!!.optInt("successes")>totals.optObject("cold")!!.optInt("successes") || totals.optObject("current")!!.optInt("actions")<totals.optObject("cold")!!.optInt("actions"))
  assertEquals(0,second.report.optInt("evaluation_teacher_calls"))
 }
 @Test fun equalProposalIsNotUpgradeAndManifestsAreDisjoint() {
  val lab=ServerBrainLearning(); val first=lab.cycle(proposal,emptyMap()); val r=lab.cycle(proposal,first.snapshot)
  assertFalse(r.report.optBoolean("promotion_recommended"))
  val train=r.report.optArray("training_manifest")!!.objects(); val exam=r.report.optArray("evaluation_manifest")!!.objects()
  assertTrue(train.all{it.optInt("seed")<0}); assertTrue(exam.all{it.optInt("seed")>0})
  assertTrue(train.none{t->exam.any{it.optString("host")==t.optString("host")}})
  assertEquals(4,exam.map{it.optString("profile")}.toSet().size)
 }
 @Test fun everyProfileReusesVerifiedSearchAfterReload() {
  val first=ServerBrainLearning().cycle(proposal,emptyMap())
  val second=ServerBrainLearning().cycle(null,ServerBrainLearning.decodeMemory(ServerBrainLearning.encodeMemory(first.snapshot)))
  val rows=second.report.optArray("cases")!!.objects()
  assertEquals(12,rows.size)
  rows.forEach { row -> assertTrue(row.toString(),row.optObject("current")!!.optBoolean("success")); assertTrue(row.toString(),row.optObject("current")!!.optBoolean("compiled_reuse")) }
  assertTrue(first.report.optBoolean("promotion_recommended"))
 }
 @Test fun duplicateProposalKeysAreRejected() {
  val duplicate=proposal.replace("\"capability\":\"search\"","\"capability\":\"search\",\"capability\":\"search\"")
  assertFalse(ServerBrainLearning().cycle(duplicate,emptyMap()).report.optBoolean("proposal_valid"))
 }

 @Test fun cliCannotOverwriteChampionByOutputAlias() {
  val dir=java.nio.file.Files.createTempDirectory("server-brain-cli").toFile()
  try {
   val champion=java.io.File(dir,"champion.json").apply { writeText("{}") }
   val proposalFile=java.io.File(dir,"proposal.json").apply { writeText(proposal) }
   try {
    main(arrayOf(proposalFile.path,champion.path,champion.path,java.io.File(dir,"report.json").path))
    fail("champion alias must be rejected")
   } catch(expected:IllegalArgumentException) { assertEquals("{}",champion.readText()) }
  } finally { dir.deleteRecursively() }
 }

}
