package com.appgate.brain.engine

import com.appgate.brain.json.JsonObject
import com.appgate.brain.memory.*
import com.appgate.brain.model.*
import com.appgate.brain.test.SearchFormSite
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SearchTransferEvaluationTest {
    private val now = 1_800_000_000_000L
    private val config = EngineConfig(pacingOverrideMs = 0, plannerCooldownMs = 0, ambiguousRecheckMs = 0, idleSleepMs = 0)
    private fun run(site: SearchFormSite, memory: Memory, id: String, query: String): TaskLedger {
        val goal = Goal(id, GoalIntent.LEARN_SITE, "synthetic search", query, emptyList(), budget = Budget(actions = 12, llmCalls = 0, wallMs = 10000))
        val ledger = TaskLedger(id, goal, site.site.host, site.currentUrl()).apply { lesson = "search"; lastCheckpointUrl = site.currentUrl() }
        return BrainEngine(site, memory, { null }, object : EngineEvents {}, config) { now }.runTask(ledger, EngineMode.TRAIN)
    }
    @Test fun savedAlternativeTransfersToUnseenHostQueryAndControlIdsWithLessFailedWork() {
        val directory = java.nio.file.Files.createTempDirectory("verified-search-transfer").toFile()
        try {
            repeat(3) { index ->
                val site = SearchFormSite(prefix = "source-$index-")
                val out = run(site, Memory(FileBrainStorage(directory)) { now }, "source_$index", "Ford")
                assertEquals(TaskStatus.DONE, out.status)
                assertTrue("search" in out.successfulSkills)
                assertEquals(if (index == 0) 1 else 0, site.enterAttempts)
                assertEquals(0, out.llmCalls)
                assertTrue("the first verified composite repair must already survive reload",
                    Memory(FileBrainStorage(directory)) { now }.skills.all().any { it.origin == SkillOrigin.COMPILED && "capability:search" in it.tags })
            }
            val restored = Memory(FileBrainStorage(directory)) { now }
            val compiled = restored.skills.all().single { it.origin == SkillOrigin.COMPILED && "capability:search" in it.tags }
            val heldout = SearchFormSite("heldout.market", "unfamiliar-")
            val retained = run(heldout, restored, "heldout_retained", "Toyota")
            val coldSite = SearchFormSite("heldout.market", "unfamiliar-")
            val cold = run(coldSite, Memory(InMemoryStorage()) { now }, "heldout_cold", "Toyota")
            assertEquals(TaskStatus.DONE, retained.status)
            assertEquals(TaskStatus.DONE, cold.status)
            assertTrue(retained.steps.any { it.source == "skill:${compiled.id}" && it.status == VerifyStatus.VERIFIED })
            assertEquals("a new host/query/control identity must reuse the verified procedure", 0, heldout.enterAttempts)
            assertEquals(1, coldSite.enterAttempts)
            assertTrue(retained.actions < cold.actions)
            assertEquals(0, retained.llmCalls + cold.llmCalls)
            assertEquals("Toyota", heldout.site.query)
            val report = JsonObject().put("content", "SYNTHETIC_FIXTURES").put("runtime", "PURE_JVM_ENGINE")
                .put("persisted_storage_reloaded", true).put("held_out_hosts", 1).put("teacher_requests", 0)
                .put("cold", JsonObject().put("verified", true).put("actions", cold.actions).put("failed_enter_attempts", coldSite.enterAttempts))
                .put("retained", JsonObject().put("verified", true).put("actions", retained.actions).put("failed_enter_attempts", heldout.enterAttempts).put("compiled_reuse", true))
            File("build/reports/brain/retained-search-evaluation.json").also { it.parentFile.mkdirs(); it.writeText(report.toString()) }
        } finally { directory.deleteRecursively() }
    }
}
