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
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class LabCompletedRehearsalTest {
    private val initial = 1_800_000_000_000L
    private val day = 24 * 60 * 60_000L
    private val config = EngineConfig(pacingOverrideMs = 0, plannerCooldownMs = 0, ambiguousRecheckMs = 0, idleSleepMs = 0)
    private class Fixture(val time: AtomicLong, val storage: InMemoryStorage = InMemoryStorage()) {
        val fake = FakeSite().apply { dialogShown = false; navigate("https://fake.market/search?q=Ford", 1000) }
        var memory = Memory(storage) { time.get() }
        val profile = SiteProfile("fake", "Synthetic", listOf(fake.host), "https://fake.market/",
            searchUrl = "https://fake.market/search?q={q}", trainingQueries = listOf("Ford"), minActionIntervalMs = 0)
        val ledgers = mutableListOf<TaskLedger>()
        val rehearsals = mutableListOf<JsonObject>()
        var teacherCalls = 0
        val planner = Planner(object : PlannerClient {
            override val describe = "forbidden rehearsal teacher"
            override fun complete(instructions: String, input: String, schemaName: String, schema: JsonObject, maxOutputTokens: Int): String {
                teacherCalls++
                error("completed rehearsals must never request a teacher")
            }
        })
        val events = object : EngineEvents {
            override fun finished(ledger: TaskLedger, result: TaskResult?) { ledgers += ledger }
            override fun diagnostic(host: String, kind: String, data: JsonObject) {
                if (kind == "learning_recheck" && data.optString("rehearsal").isNotBlank()) rehearsals += data
            }
        }
        fun completeAll(at: Long = time.get() - 600_000L): SiteModel = memory.site(fake.host).also { site ->
            Curriculum.ensure(site)
            site.curriculum.forEach { Curriculum.markSkillVerified(site, it.id, at) }
            memory.saveSite(site)
        }
        fun reload() { memory = Memory(storage) { time.get() } }
    }

    private fun session(f: Fixture, lab: Boolean): LearningSession {
        val engine = BrainEngine(f.fake, f.memory, { f.planner }, f.events, config) { f.time.get() }
        return if (lab) LearningSession(engine, f.memory, f.events, listOf(f.profile), clock = { f.time.get() }, completedRehearsal = true)
        else LearningSession(engine, f.memory, f.events, listOf(f.profile), clock = { f.time.get() })
    }
    private fun completedPractice(site: SiteModel, now: Long, page: SemanticPageState, available: (String) -> Boolean = { true }): String =
        Curriculum.practiceLesson(site, now, page, available, includeComplete = true)


    @Test fun completedLabClearsBlockingDialogBeforeRehearsal() {
        val f = Fixture(AtomicLong(initial)); val site = f.completeAll()
        f.fake.dialogShown = true
        session(f, true).run(1)
        assertEquals("dialog preparation continues to one results rehearsal", 2, f.ledgers.size)
        val out = f.ledgers.first()
        assertEquals("dismiss_dialog", out.lesson)
        assertTrue("dismissal must be verified", "dismiss_dialog" in out.successfulSkills)
        assertFalse(f.fake.dialogShown)
        assertEquals(0, f.teacherCalls)
        assertEquals(11, site.curriculum.count { it.done })
    }

    @Test fun completedDialogPracticePreservesFailureAndRetryHolds() {
        val f = Fixture(AtomicLong(initial)); val site = f.completeAll()
        val page = SpsParser().parse(f.fake.apply { dialogShown = true }.observe(1000), initial).copy(settle = Settle.IDLE)
        assertEquals("dismiss_dialog", completedPractice(site, initial, page))
        assertEquals("", completedPractice(site, initial, page) { false })
        site.curriculum.single { it.id == "dismiss_dialog" }.retryAt = initial + 30_000L
        assertEquals("", completedPractice(site, initial, page))
        assertEquals("", completedPractice(site, initial, page.copy(authWall = true)))
        assertEquals("", completedPractice(site, initial, page.copy(challenge = true)))
        assertEquals("", completedPractice(site, initial, page.copy(settle = Settle.BUSY)))
    }

    @Test fun defaultKeepsTheNormalDayReviewHold() {
        val f = Fixture(AtomicLong(initial)); val site = f.completeAll()
        site.learningBlockedUntil = initial + day; f.memory.saveSite(site)
        session(f, false).run(1)
        assertTrue(f.ledgers.isEmpty())
        assertEquals(initial + day, site.learningBlockedUntil)
        assertEquals(initial - 600_000L + day, Curriculum.nextReviewAt(site))
        assertTrue(f.fake.log.none { it.startsWith("act ") })
    }

    @Test fun labExecutesDueKnownSkillWithoutTeacherOrNewLessonCredit() {
        val f = Fixture(AtomicLong(initial)); val site = f.completeAll()
        val completed = site.curriculum.map { it.id }.toSet()
        session(f, true).run(1)
        assertEquals("one bounded completed-skill rehearsal", 1, f.ledgers.size)
        val out = f.ledgers.single()
        assertTrue("rehearsal must perform a browser action", out.actions > 0)
        assertTrue("rehearsal must verify the requested known capability", out.lesson in out.successfulSkills)
        assertEquals(0, out.goal.budget.llmCalls)
        assertEquals(0, f.teacherCalls)
        assertEquals(1, f.rehearsals.size)
        assertEquals(completed, site.curriculum.filter { it.done }.map { it.id }.toSet())
        assertEquals(11, site.curriculum.count { it.done })
        assertEquals(initial + Curriculum.REHEARSAL_INTERVAL_MS, site.learningBlockedUntil)
    }

    @Test fun completedPracticeRequiresDueSameSkillOnSafeResults() {
        val f = Fixture(AtomicLong(initial)); val site = f.completeAll(initial)
        val page = SpsParser().parse(f.fake.observe(1000), initial).copy(settle = Settle.IDLE)
        assertEquals("", Curriculum.practiceLesson(site, initial + day, page))
        assertEquals("", completedPractice(site, initial + 299_999L, page))
        val due = initial + Curriculum.REHEARSAL_INTERVAL_MS
        val lesson = completedPractice(site, due, page)
        assertTrue("opt-in completed work must become runnable at five minutes", lesson.isNotBlank())
        assertEquals(lesson, LearningOpportunities.target(page, lesson)?.skillId)
        assertEquals("", completedPractice(site, due, page) { false })
        for (unsafe in listOf(page.copy(authWall = true), page.copy(challenge = true), page.copy(dialogOpen = true),
            page.copy(settle = Settle.BUSY), page.copy(pageType = PageType.DETAIL))) {
            assertEquals("", completedPractice(site, due, unsafe))
        }
    }

    @Test fun labCapsLegacyDayDeferralButPreservesShortAndHumanHolds() {
        val f = Fixture(AtomicLong(initial)); val site = f.completeAll()
        site.learningBlockedUntil = initial + day
        session(f, true).run(1)
        assertTrue(f.ledgers.isEmpty())
        assertEquals(initial + Curriculum.REHEARSAL_INTERVAL_MS, site.learningBlockedUntil)
        val short = initial + 90_000L; site.learningBlockedUntil = short
        session(f, true).run(1)
        assertEquals(short, site.learningBlockedUntil)
        site.learningBlockedUntil = initial + day; site.learningNeedsHuman = true; site.lastLearningStatus = "operator review"
        session(f, true).run(1)
        assertEquals(initial + day, site.learningBlockedUntil)
        assertTrue(site.learningNeedsHuman)
        assertTrue(f.ledgers.isEmpty())
    }

    @Test fun labPreservesDailyChallengeAndProcedureHolds() {
        val f = Fixture(AtomicLong(initial)); val site = f.completeAll()
        site.challengeDay = initial / 86_400_000L; site.challengesToday = 3; site.learningBlockedUntil = initial + day
        session(f, true).run(1)
        assertEquals(initial + day, site.learningBlockedUntil)
        assertTrue(f.ledgers.isEmpty())
        site.challengesToday = 0; site.learningBlockedUntil = 0
        site.curriculum.forEach { it.retryAt = initial + 60_000L }
        session(f, true).run(1)
        assertTrue(f.ledgers.isEmpty())
        assertTrue(site.curriculum.all { it.retryAt == initial + 60_000L })
    }

    @Test fun labCadenceAndAllVerifiedLessonsSurviveMemoryReload() {
        val f = Fixture(AtomicLong(initial)); f.completeAll()
        session(f, true).run(1)
        assertEquals(1, f.ledgers.size)
        f.reload(); f.time.set(initial + 299_999L)
        session(f, true).run(1)
        assertEquals(1, f.ledgers.size)
        f.time.set(initial + Curriculum.REHEARSAL_INTERVAL_MS)
        session(f, true).run(1)
        assertEquals(2, f.ledgers.size)
        assertEquals(11, f.memory.site(f.fake.host).curriculum.count { it.done })
        assertEquals(0, f.teacherCalls)
    }

    @Test fun labWaitingResumesAtFiveMinuteHoldWithoutWaitingForDayReview() {
        val f = Fixture(AtomicLong(initial)); val site = f.completeAll()
        site.learningBlockedUntil = initial + Curriculum.REHEARSAL_INTERVAL_MS
        val session = session(f, true); val error = AtomicReference<Throwable>()
        val worker = Thread { try { session.run(2) } catch (t: Throwable) { error.set(t) } }.apply { isDaemon = true }
        worker.start()
        try {
            val deadline = System.nanoTime() + 2_000_000_000L
            while (!session.isWaiting && worker.isAlive && System.nanoTime() < deadline) Thread.sleep(1)
            assertTrue(session.isWaiting)
            assertTrue(f.ledgers.isEmpty())
            f.time.set(initial + Curriculum.REHEARSAL_INTERVAL_MS)
            worker.join(2500)
            assertFalse("five-minute lab retry must ignore only the day review deadline", worker.isAlive)
            assertNull(error.get())
            assertEquals(1, f.ledgers.size)
        } finally { session.stop(); worker.interrupt(); worker.join(2000) }
    }
}
