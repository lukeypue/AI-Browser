package com.appgate.brain.engine

import com.appgate.brain.json.*
import com.appgate.brain.memory.*
import com.appgate.brain.model.*
import com.appgate.brain.profile.SiteProfile
import com.appgate.brain.test.FakeSite
import org.junit.Assert.*
import org.junit.Test

class DiscoveryVisitProgressTest {
    @Test fun discoveryOnlyTasksYieldAfterThreeGoalsWithoutANewLesson() {
        var now = 1_800_000_000_000L
        val fake = FakeSite().apply { dialogShown = false; navigate("https://fake.market/search?q=Ford", 1000) }
        val memory = Memory(InMemoryStorage()) { now }
        val site = memory.site(fake.host); Curriculum.ensure(site)
        site.curriculum.filter { it.id !in setOf("next_page", "load_more") }.forEach { it.completedAt = now - 1 }
        var footer = false
        lateinit var engine: BrainEngine
        val renderer = object : Renderer by fake {
            override fun observe(timeoutMs: Long): String {
                if (engine.currentLedger == null) { now += 301_000L; footer = false }
                val raw = Json.parseObject(fake.observe(timeoutMs))
                raw.put("elements", JsonArray())
                raw.put("viewport", JsonObject().put("w", 400).put("h", 400).put("scrollH", 2000).put("scrollY", if (footer) 1600 else 0))
                raw.put("settle", JsonObject().put("state", if (footer) "BUSY" else "IDLE"))
                return raw.toString()
            }
            override fun act(command: JsonObject, timeoutMs: Long): RendererResult {
                if (command.optString("cmd") == "scroll") { footer = true; return RendererResult(true) }
                return fake.act(command, timeoutMs)
            }
        }
        val completed = mutableListOf<TaskLedger>()
        val events = object : EngineEvents { override fun finished(ledger: TaskLedger, result: TaskResult?) { completed += ledger } }
        engine = BrainEngine(renderer, memory, { null }, events,
            EngineConfig(pacingOverrideMs = 0, plannerCooldownMs = 0, ambiguousRecheckMs = 0, idleSleepMs = 0)) { now }
        val profile = SiteProfile("fake", "Fake", listOf(fake.host), fake.url, trainingQueries = listOf("Ford"))
        LearningSession(engine, memory, events, listOf(profile), clock = { now }).run(1)
        assertEquals("verified helpers cannot keep a visit alive as new lessons", 3, completed.size)
        assertTrue(completed.all { it.status == TaskStatus.PARTIAL && "seek_pagination" in it.successfulSkills })
        assertTrue(site.curriculum.filter { it.id in setOf("next_page", "load_more") }.none { it.done })
        assertTrue(site.strategyFailures.isEmpty())
    }
}
