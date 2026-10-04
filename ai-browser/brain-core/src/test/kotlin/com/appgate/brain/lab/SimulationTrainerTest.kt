package com.appgate.brain.lab

import com.appgate.brain.engine.*
import com.appgate.brain.memory.*
import com.appgate.brain.model.*
import org.junit.Assert.*
import org.junit.Test

class SimulationTrainerTest {
    @Test fun defaultExperimentKeepsTrainingSeparateFromBothEvaluationSets() {
        val report = SimulationTrainer().experiment()
        assertEquals(1200, report.optObject("manifest")!!.optInt("training_episodes"))
        assertEquals(64, report.optObject("manifest")!!.optString("fixed_sha256").length)
        assertEquals(1.0, report.optObject("after_training")!!.optDouble("oracle_success_rate"), 0.0)
    }

    @Test fun oracleRejectsIncorrectMissingAndKnownAxleClassifications() {
        for (missing in listOf(true, false)) {
            val scenario = Scenario(45, missingRareEvidence = missing)
            val run = SimulationTrainer().episode(scenario, emptyMap())
            assertTrue(run.score.toJson().toString(), run.score.success)
            val key = if (missing) "k1" else "k2"
            val original = run.task.verdicts.getValue(key)
            run.task.verdicts[key] = original.copy(perConstraint = original.perConstraint +
                ("axle_ratio" to if (missing) Verdict.VIOLATED else Verdict.UNKNOWN))
            assertFalse("Incorrect rare evidence classification must fail: missing=$missing",
                EpisodeScorer.score(scenario, run.market, run.task).success)
        }
    }

    @Test fun oracleAcceptsRealSearchAndKeepsUnstatedAxleUnknown() {
        val scenario = Scenario(41, level = 3, missingRareEvidence = true)
        val run = SimulationTrainer().episode(scenario, emptyMap())
        assertTrue(run.score.toJson().toString() + run.task.steps.joinToString("\n") { it.action + " -> " + it.status + " " + it.evidence }, run.score.success)
        assertTrue(run.score.returnedMatches >= 2)
        assertEquals(0, run.score.falseVerifiedClaims)
        assertTrue(run.task.verdicts.values.any { it.perConstraint["axle_ratio"] == Verdict.UNKNOWN })
    }

    @Test fun missingMileageFilterPreservesHardCardBound() {
        val run = SimulationTrainer().episode(Scenario(42, level = 5, missingMileage = true), emptyMap())
        assertTrue(run.score.toJson().toString() + run.task.steps.joinToString("\n") { it.action + " -> " + it.status + " " + it.evidence }, run.score.success)
        assertEquals(0, run.score.hardViolations)
        assertFalse("Missing filter must not be invented", "mileage_max" in run.market.appliedFilters)
    }

    @Test fun humanAndPermanentFailuresHaveBoundedSafeExit() {
        for (fault in listOf(SimulationFault.AUTH, SimulationFault.BLANK_ALWAYS,
                SimulationFault.ERROR_ALWAYS, SimulationFault.NOOP_SEARCH, SimulationFault.LOADING_ALWAYS)) {
            val run = SimulationTrainer().episode(Scenario(90 + fault.ordinal, fault = fault), emptyMap())
            assertTrue("$fault: ${run.score.toJson()}", run.score.success)
            assertTrue(run.task.done || run.task.blocked)
            assertTrue(run.task.actions <= 60)
            assertEquals(0, run.score.unsafeActions)
            assertEquals(0, run.actualTeacherRequests)
            if (fault == SimulationFault.AUTH) assertEquals(TaskStatus.NEED_HUMAN, run.task.status)
        }
    }

    @Test fun oracleRejectsDoneWithoutMatchingItems() {
        val scenario = Scenario(44)
        val market = SimulationMarket(scenario)
        val bogus = TaskLedger("bogus", scenario.goal(), scenario.host, market.currentUrl()).apply { status = TaskStatus.DONE }
        assertFalse(EpisodeScorer.score(scenario, market, bogus).success)
    }

    @Test fun oracleRejectsInventedAxleEvidenceEvenWhenEngineClaimsSuccess() {
        val scenario = Scenario(45, missingRareEvidence = true)
        val run = SimulationTrainer().episode(scenario, emptyMap())
        val first = run.task.verdicts.values.first()
        run.task.verdicts[first.itemKey] = first.copy(perConstraint = first.perConstraint + ("axle_ratio" to Verdict.SAT))
        val score = EpisodeScorer.score(scenario, run.market, run.task)
        assertFalse(score.success)
        assertTrue(score.falseVerifiedClaims > 0)
    }

    @Test fun evaluationForksAreImmutableAndOrderIndependent() {
        val trainer = SimulationTrainer()
        val seed = trainer.train(listOf(Scenario(1), Scenario(2), Scenario(3)))
        val before = seed.toMap()
        val cases = listOf(Scenario(101), Scenario(102, level = 4), Scenario(103, level = 5))
        val a = trainer.evaluate(cases, seed).associate { it.scenario.id to it.score.toJson().toString() }
        val b = trainer.evaluate(cases.reversed(), seed).associate { it.scenario.id to it.score.toJson().toString() }
        assertEquals(before, seed)
        assertEquals(a, b)
    }

    @Test fun teacherProcedureMustVerifyBeforeItTransfersToUnseenHost() {
        val trainer = SimulationTrainer()
        val trained = trainer.train((1..3).map { Scenario(it, twoStepSearch = true) })
        val memory = Memory(InMemoryStorage().apply { trained.forEach { (k,v) -> write(k,v) } }) { SimulationTrainer.NOW }
        assertTrue(memory.skills.all().any { it.origin == SkillOrigin.COMPILED && it.params.contains("query") })
        val target = Scenario(777, host = "unseen.sim.invalid", level = 3, twoStepSearch = true)
        val cold = trainer.episode(target, emptyMap())
        val warm = trainer.episode(target, trained)
        assertTrue(warm.score.toJson().toString(), warm.score.success)
        assertTrue("Must use a compiled procedure", warm.task.steps.any { it.source.startsWith("skill:compiled_") && it.status == VerifyStatus.VERIFIED })
        assertTrue("Warm must improve success or actions", !cold.score.success || warm.task.actions < cold.task.actions)
        assertEquals(0, warm.actualTeacherRequests)
    }

    @Test fun unverifiedBrokenDemonstrationCannotCreateMastery() {
        val trainer = SimulationTrainer()
        val data = trainer.train(listOf(Scenario(1, twoStepSearch = true, fault = SimulationFault.NOOP_SEARCH)))
        val memory = Memory(InMemoryStorage().apply { data.forEach { (k,v) -> write(k,v) } })
        assertFalse(memory.skills.all().any { it.origin == SkillOrigin.COMPILED && "capability:search" in it.tags })
    }

    @Test fun staleTargetRegroundsLearnedSearchWithoutDiscardingTheProcedure() {
        val trainer = SimulationTrainer()
        val trained = trainer.train((1..3).map { Scenario(it, twoStepSearch = true) })
        val run = trainer.episode(Scenario(999, host = "heldout.sim.invalid", twoStepSearch = true,
            fault = SimulationFault.STALE_ONCE), trained)
        assertTrue(run.score.toJson().toString(), run.score.success)
        assertTrue(run.task.steps.any { it.source.startsWith("skill:compiled_") && it.status == VerifyStatus.FAILED })
        assertTrue(run.task.steps.any { it.source.startsWith("skill:compiled_") && it.status == VerifyStatus.VERIFIED })
    }

    @Test fun curriculumRequiresEnoughEvidenceAndNoUnsafeOrFalseClaims() {
        val c = SimulationCurriculum()
        repeat(9) { c.record(true, 0, 0) }
        assertEquals(1, c.level)
        c.record(true, 0, 0)
        assertEquals(2, c.level)
        repeat(10) { c.record(true, 1, 0) }
        assertEquals(2, c.level)
        val bad = SimulationCurriculum()
        repeat(10) { bad.record(true, 0, 1) }
        assertEquals(1, bad.level)
    }

    @Test fun staleControlAndDelayedSettleRecoverUsingTheRealVerifier() {
        for (fault in listOf(SimulationFault.STALE_ONCE, SimulationFault.LOADING_ONCE)) {
            val run = SimulationTrainer().episode(Scenario(121 + fault.ordinal, fault = fault), emptyMap())
            assertTrue("$fault: ${run.score.toJson()}", run.score.success)
            assertTrue(run.score.recoverySuccess)
        }
    }
}
