package com.appgate.brain.engine

import com.appgate.brain.json.JsonObject
import com.appgate.brain.memory.*
import com.appgate.brain.model.*
import com.appgate.brain.perception.SpsParser
import com.appgate.brain.planner.*
import com.appgate.brain.profile.SiteProfile
import com.appgate.brain.test.FakeSite
import org.junit.Assert.*
import org.junit.Test

class LearningEligibilityTest {
    private val initial = 1_800_000_000_000L
    private val config = EngineConfig(pacingOverrideMs = 0, plannerCooldownMs = 0, ambiguousRecheckMs = 0, idleSleepMs = 0)

    @Test fun heldSharedSearchYieldsWithoutCreatingAnotherFailedTask() {
        val fake = FakeSite().apply { dialogShown = false }
        var now = initial
        val storage = InMemoryStorage()
        val memory = Memory(storage) { now }
        val page = SpsParser().parse(fake.observe(1000), now)
        val site = memory.site(fake.host)
        Curriculum.ensure(site)
        Curriculum.markSkillVerified(site, "search", now)
        val local = listOf(Step(StepKind.TYPE, Role.SEARCH_BOX, arg = "Ford", submit = true))
        repeat(2) {
            FailedStrategies.record(site, FailedStrategies.key(page, "search", memory.skills.get("search")!!.body), false, now)
            FailedStrategies.record(site, FailedStrategies.key(page, "search", local), false, now)
            FailedStrategies.record(site, FailedStrategies.key(page, "search"), false, now)
        }
        memory.saveSite(site)
        val restored = Memory(storage) { now }
        var finished = 0
        var calls = 0
        var recheckReason = ""
        val planner = Planner(object : PlannerClient {
            override val describe = "test"
            override fun complete(instructions: String, input: String, schemaName: String, schema: JsonObject, maxOutputTokens: Int): String {
                calls++
                return """{"steps":[{"kind":"TYPE","role":"SEARCH_BOX","arg":"${'$'}query","submit":true}],"confidence":0.95}"""
            }
        })
        val events = object : EngineEvents {
            override fun finished(ledger: TaskLedger, result: TaskResult?) { finished++ }
            override fun diagnostic(host: String, kind: String, data: JsonObject) {
                if (kind == "learning_recheck") recheckReason = data.optString("reason")
            }
        }
        val engine = BrainEngine(fake, restored, { planner }, events, config) { now }
        val profile = SiteProfile("fake", "Fake", listOf(fake.host), fake.url, trainingQueries = listOf("Ford"))
        LearningSession(engine, restored, events, listOf(profile), clock = { now }).run(1)
        assertEquals("held prerequisite must be checked before creating a task", 0, finished)
        assertEquals(0, calls)
        assertEquals(0, restored.site(fake.host).lessonOrdinal)
        assertTrue(restored.site(fake.host).curriculum.single { it.id == "search" }.done)
        assertEquals("procedure_cooldown", recheckReason)
        assertFalse(engine.learningLessonAvailable(profile, page, "open_item"))
        assertTrue("relevant new controls reopen a concrete procedure", engine.learningLessonAvailable(profile,
            page.copy(affordances = page.affordances + Affordance("new", Role.SUBMIT, tag = "button")), "open_item"))
        assertFalse(engine.learningLessonAvailable(profile, page.copy(authWall = true), "open_item"))
        now += Curriculum.RETRY_MS
        assertTrue("a fresh bounded teacher repair remains available", engine.learningLessonAvailable(profile, page, "open_item"))
        assertEquals("eligibility never reserves or calls the teacher", 0, calls)
        now = initial
        val localKey = FailedStrategies.key(page, "search", local)
        val failure = restored.site(fake.host).strategyFailures.remove(localKey)!!
        assertTrue("a different local repair remains eligible", engine.learningLessonAvailable(profile, page, "open_item"))
        restored.site(fake.host).strategyFailures[localKey] = failure
        val learned = restored.skills.get("search")!!.copy(id = "verified-alternative-search", origin = SkillOrigin.COMPILED,
            body = listOf(Step(StepKind.CLICK, Role.SUBMIT, optional = true),
                Step(StepKind.TYPE, Role.SEARCH_BOX, arg = "\$query", submit = true, expect = listOf(Postcondition.PageTypeIs(PageType.RESULTS)))),
            tags = setOf("verified_v2", "capability:search"), statsByHost = mapOf(fake.host to BetaStat(successes = 3.0)))
        restored.skills.put(learned)
        assertTrue("a verified different learned procedure remains eligible", engine.learningLessonAvailable(profile, page, "open_item"))
    }
}
