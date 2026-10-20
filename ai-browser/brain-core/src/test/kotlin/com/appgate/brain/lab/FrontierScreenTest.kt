package com.appgate.brain.lab

import com.appgate.brain.json.JsonObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.File

class FrontierScreenTest {
    @Test fun aVerifiedNewProcedureMustBeatColdAndSkillRemovalAfterReload() {
        val dir = Files.createTempDirectory("frontier-screen-").toFile()
        try {
            val report = FrontierScreen().run(dir)
            assertTrue("cold local-only baseline must actually fail the synthetic prerequisite", report.optBoolean("cold_unsolved"))
            assertTrue("the reference must verify against independent fixture state", report.optBoolean("training_oracle_success"))
            assertEquals(1, report.optInt("compiled_skills"))
            assertEquals(8, report.optInt("retained_successes"))
            assertEquals(0, report.optInt("cold_successes"))
            assertEquals(0, report.optInt("ablation_successes"))
            assertEquals(0, report.optInt("evaluation_teacher_calls"))
            assertEquals(0, report.optInt("false_verified_claims"))
            assertTrue(report.optBoolean("frozen_memory_unchanged"))
            assertTrue(report.optBoolean("query_literals_absent"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun noOpPrerequisiteAndHumanPageCannotBorrowTrainingSuccess() {
        val dir = Files.createTempDirectory("frontier-negative-").toFile()
        try {
            val report = FrontierScreen().run(dir)
            assertEquals(0, report.optInt("negative_oracle_successes", -1))
            assertEquals(0, report.optInt("negative_verified_searches", -1))
            assertEquals(0, report.optInt("human_actions", -1))
            assertEquals(0, report.optInt("human_teacher_calls", -1))
        } finally { dir.deleteRecursively() }
    }

    @Test fun ordinaryTasksMustShowTheSameAttributedSearchGain() {
        val dir = Files.createTempDirectory("frontier-task-").toFile()
        try {
            val report = FrontierScreen().run(dir)
            assertTrue(report.optBoolean("all_observation_gates_passed"))
            assertTrue(report.optBoolean("solved_control_success"))
            assertEquals(8, report.optInt("ordinary_retained_successes", -1))
            assertEquals(0, report.optInt("ordinary_cold_successes", -1))
            assertEquals(0, report.optInt("ordinary_ablation_successes", -1))
        } finally { dir.deleteRecursively() }
    }

    @Test fun aPopulatedDraftWithoutAWorkingSubmitMustNotEarnASkill() {
        val dir = Files.createTempDirectory("frontier-draft-").toFile()
        try {
            val report = FrontierScreen().run(dir)
            assertTrue("the negative must actually select the prerequisite", report.optBoolean("draft_prerequisite_selected"))
            assertTrue("the negative must reach a populated draft", report.optBoolean("draft_present"))
            assertFalse(report.optBoolean("draft_oracle_success", true))
            assertFalse(report.optBoolean("draft_verified_search", true))
            assertEquals(0, report.optInt("draft_compiled_skills", -1))
        } finally { dir.deleteRecursively() }
    }

    @Test fun modelExchangeMustCaptureTheActualReplayRequestAndResponseBytes() {
        val dir = Files.createTempDirectory("frontier-provenance-").toFile()
        try {
            val report = FrontierScreen().run(dir)
            assertTrue("replay request must be captured", File(dir, report.optString("teacher_request_artifact")).isFile)
            assertEquals(64, report.optString("teacher_request_sha256").length)
            val bytes = File(dir, report.optString("teacher_request_artifact")).readBytes()
            val actual = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals(actual, report.optString("teacher_request_sha256"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun failedLearnedStepsCannotBeReportedAsACompletedSkillOrReinforced() {
        val dir = Files.createTempDirectory("frontier-failure-credit-").toFile()
        try {
            val negative = FrontierScreen().run(dir).optObject("negative")!!
            assertTrue(negative.has("compiled_procedure_verified"))
            assertFalse(negative.optBoolean("compiled_procedure_verified", true))
            assertEquals(0.0, negative.optDouble("learned_host_successes", -1.0), 0.0)
            assertTrue(negative.optDouble("learned_host_failures", -1.0) > 0.0)
        } finally { dir.deleteRecursively() }
    }

    @Test fun requestCaptureMustPreservePreviousFailureMemory() {
        val dir = Files.createTempDirectory("frontier-prior-capture-").toFile()
        try {
            FrontierScreen().capture(dir, mapOf("experiment-marker" to "retained"))
            val store = com.appgate.brain.memory.FileBrainStorage(File(dir, "capture-memory"))
            assertEquals("retained", store.read("experiment-marker"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun teacherExchangeMustNeverEnterFrozenProceduralMemory() {
        val dir = Files.createTempDirectory("frontier-exchange-isolation-").toFile()
        try {
            FrontierScreen().run(dir)
            val frozen = com.appgate.brain.memory.FileBrainStorage(File(dir, "frozen"))
            assertFalse("teacher capsule must not become a brain storage key", "planner-request" in frozen.keys(""))
            assertTrue("synthetic query must be stripped from the frozen memory", frozen.keys("").none { key ->
                frozen.read(key)!!.contains(FrontierScreen.TRAIN_QUERY)
            })
        } finally { dir.deleteRecursively() }
    }
}
