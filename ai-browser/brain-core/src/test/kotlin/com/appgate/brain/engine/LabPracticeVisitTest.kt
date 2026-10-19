package com.appgate.brain.engine

import com.appgate.brain.memory.*
import com.appgate.brain.model.*
import com.appgate.brain.planner.*
import com.appgate.brain.json.JsonObject
import com.appgate.brain.perception.SpsParser
import com.appgate.brain.profile.SiteProfile
import com.appgate.brain.test.FakeSite
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class LabPracticeVisitTest {
    private val initial = 1_800_000_000_000L
    private val config = EngineConfig(pacingOverrideMs = 0, plannerCooldownMs = 0, ambiguousRecheckMs = 0, idleSleepMs = 0)
    private class Fixture {
        val now = AtomicLong(1_800_000_000_000L)
        val fake = FakeSite().apply { dialogShown = false; navigate("https://fake.market/search?q=Ford&style=one", 1000) }
        val memory = Memory(InMemoryStorage()) { now.get() }
        val outcomes = mutableListOf<TaskLedger>()
        val profiles = listOf("one", "two").map { style -> SiteProfile(style, "Synthetic $style", listOf(fake.host), "https://fake.market/",
            searchUrl = "https://fake.market/search?q={q}&style=$style", trainingQueries = listOf("Ford")) }
        init {
            val site = memory.site(fake.host); Curriculum.ensure(site)
            site.curriculum.forEach { Curriculum.markSkillVerified(site, it.id, now.get() - 600_000L) }
            memory.saveSite(site)
        }
    }
    private fun session(f: Fixture): LearningSession {
        val events = object : EngineEvents { override fun finished(ledger: TaskLedger, result: TaskResult?) { f.outcomes += ledger } }
        val engine = BrainEngine(f.fake, f.memory, { null }, events, config) { f.now.get() }
        return LearningSession(engine, f.memory, events, f.profiles, clock = { f.now.get() }, completedRehearsal = true)
    }
    @Test fun completedPracticeVisitsTheNextProfileInsteadOfRemainingOnThePreviousLayout() {
        val f = Fixture(); val session = session(f)
        session.run(1)
        f.now.set(initial + Curriculum.REHEARSAL_INTERVAL_MS)
        session.run(1)
        assertTrue("profile rotation must actually visit the second synthetic entry", f.fake.log.any { it.startsWith("navigate ") && it.contains("style=two") })
        assertEquals(11, f.memory.site(f.fake.host).curriculum.count { it.done })
        assertTrue(f.outcomes.all { it.llmCalls == 0 })
    }
    @Test fun aVerifiedDialogPrerequisiteContinuesToOneUsefulResultsRehearsal() {
        val f = Fixture(); f.fake.dialogShown = true
        session(f).run(1)
        assertEquals("dialog preparation plus one results capability, then yield", 2, f.outcomes.size)
        assertEquals("dismiss_dialog", f.outcomes.first().lesson)
        assertTrue("dismiss_dialog" in f.outcomes.first().successfulSkills)
        assertTrue(f.outcomes.last().lesson != "dismiss_dialog" && f.outcomes.last().lesson in f.outcomes.last().successfulSkills)
        assertTrue(f.outcomes.all { it.llmCalls == 0 && it.goal.budget.llmCalls == 0 })
        assertEquals(initial + Curriculum.REHEARSAL_INTERVAL_MS, f.memory.site(f.fake.host).learningBlockedUntil)
    }

    @Test fun completedPracticeDoesNotAllocateATaskForAnUnavailableTeacher() {
        val f = Fixture(); f.fake.dialogShown = true
        val page = SpsParser().parse(f.fake.observe(1000), initial)
        val site = f.memory.site(f.fake.host)
        val skill = f.memory.skills.get("dismiss_dialog")!!
        FailedStrategies.record(site, FailedStrategies.key(page, "dismiss_dialog", skill.body), false, initial)
        f.memory.saveSite(site)
        var calls = 0
        val teacher = Planner(object : PlannerClient {
            override val describe = "forbidden completed-practice teacher"
            override fun complete(instructions: String, input: String, schemaName: String, schema: JsonObject, maxOutputTokens: Int): String {
                calls++;error("completed practice has zero teacher budget")
            }
        })
        val events = object : EngineEvents { override fun finished(ledger: TaskLedger, result: TaskResult?) { f.outcomes += ledger } }
        val engine = BrainEngine(f.fake, f.memory, { teacher }, events, config) { f.now.get() }
        LearningSession(engine, f.memory, events, f.profiles, clock = { f.now.get() }, completedRehearsal = true).run(1)
        assertTrue("a configured but forbidden teacher is not an executable alternative", f.outcomes.isEmpty())
        assertEquals(0, calls)
        assertFalse(FailedStrategies.allowed(site, FailedStrategies.key(page, "dismiss_dialog", skill.body), initial))
    }

    @Test fun freshPracticeNavigationDoesNotEraseRememberedFailedProcedures() {
        val f = Fixture(); val site = f.memory.site(f.fake.host)
        val page = SpsParser().parse(f.fake.observe(1000), initial)
        val key = FailedStrategies.key(page, "search", f.memory.skills.get("search")!!.body, mapOf("query" to "Ford"))
        FailedStrategies.record(site, key, false, initial);f.memory.saveSite(site)
        session(f).run(1)
        assertFalse(FailedStrategies.allowed(site, key, initial + 86_400_000L))
        assertEquals(11, site.curriculum.count { it.done })
    }
}
