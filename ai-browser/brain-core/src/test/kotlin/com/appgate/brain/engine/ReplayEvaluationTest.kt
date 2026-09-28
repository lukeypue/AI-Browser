package com.appgate.brain.engine

import com.appgate.brain.goal.GoalParser
import com.appgate.brain.json.*
import com.appgate.brain.memory.*
import com.appgate.brain.model.*
import com.appgate.brain.test.FakeSite
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Reproducible synthetic evaluation. Counts are observed; no live-site speed claim. */
class ReplayEvaluationTest {
    @Test fun reportColdWarmAndUnavailableEvidence() {
        val storage = InMemoryStorage()
        val goal = GoalParser.parse("Ford Expedition under 8000 with a 3.73 axle", Budget(itemsInspected = 6, actions = 60, wallMs = 10000))
        val rows = JsonArray()
        for (name in listOf("cold", "warm")) {
            val fake = FakeSite()
            val memory = Memory(storage)
            val start = System.nanoTime()
            val engine = BrainEngine(fake, memory, { null }, object : EngineEvents {}, EngineConfig(pacingOverrideMs = 0, ambiguousRecheckMs = 0))
            val out = engine.runTask(TaskLedger(name, goal, fake.host, "https://${fake.host}/"))
            assertEquals(TaskStatus.DONE, out.status)
            assertTrue(out.verdicts.values.any { it.perConstraint["axle_ratio"] == Verdict.UNKNOWN })
            assertTrue(engine.resultFor(out).verified.all { it.perConstraint["axle_ratio"] == Verdict.SAT })
            rows.add(JsonObject().put("scenario", name).put("status", out.status.name).put("actions", out.actions)
                .put("llm_calls", out.llmCalls).put("elapsed_ms", (System.nanoTime() - start) / 1_000_000)
                .put("items", out.verdicts.size).put("inspected", out.itemsInspected).put("verified_skills", out.successfulSkills.size)
                .put("used_learned_search_url", out.usedSearchUrl).put("compiled_skills", memory.skills.all().count { it.origin == SkillOrigin.COMPILED }))
        }
        val report = JsonObject().put("environment", "synthetic FakeSite JVM replay; no network or model").put("runs", rows)
        val target = File(System.getProperty("brain.replay.report", "build/reports/brain/replay.json"))
        target.parentFile.mkdirs(); target.writeText(report.toString())
    }
}
