package com.appgate.brain.engine

import com.appgate.brain.memory.InMemoryStorage
import com.appgate.brain.memory.Memory
import com.appgate.brain.profile.SiteProfile
import com.appgate.brain.test.FakeSite
import org.junit.Assert.*
import org.junit.Test

class LearningRetryTest {
    private val now = 1_800_000_000_000L
    private val config = EngineConfig(pacingOverrideMs = 0, plannerCooldownMs = 0, ambiguousRecheckMs = 0, idleSleepMs = 0, maxDecisionsWithoutProgress = 0)
    private fun profile(host: String) = SiteProfile("fake", "Fake", listOf(host), "https://$host/")

    @Test fun ordinaryNoProgressRetriesAfterOneMinute() {
        val fake = FakeSite().apply { dialogShown = false }; val memory = Memory(InMemoryStorage())
        val events = object : EngineEvents {}
        LearningSession(BrainEngine(fake, memory, { null }, events, config, clock = { now }), memory, events,
            listOf(profile(fake.host)), clock = { now }).run(maxSites = 1)
        assertEquals("one failed opportunity should defer without duplicate tasks", 1, memory.site(fake.host).lessonOrdinal)
        assertEquals(now + 60_000L, memory.site(fake.host).learningBlockedUntil)
    }

    @Test fun existingOrdinaryCooldownIsShortenedWithoutErasingLearning() {
        val fake = FakeSite(); val memory = Memory(InMemoryStorage()); val site = memory.site(fake.host)
        Curriculum.markSkillVerified(site, "search", now - 1)
        site.learningBlockedUntil = now + 12 * 60_000L
        val events = object : EngineEvents {}
        LearningSession(BrainEngine(fake, memory, { null }, events, config), memory, events,
            listOf(profile(fake.host)), clock = { now }).run(maxSites = 1)
        assertEquals(now + 60_000L, site.learningBlockedUntil)
        assertTrue(site.curriculum.single { it.id == "search" }.done)
        assertTrue(fake.log.isEmpty())
    }

    @Test fun dailyReviewAndChallengeHoldsAreNotShortened() {
        for (kind in listOf("review", "challenge", "human")) {
            val fake = FakeSite(); val memory = Memory(InMemoryStorage()); val site = memory.site(fake.host)
            Curriculum.ensure(site)
            if (kind == "review") site.curriculum.forEach { Curriculum.markSkillVerified(site, it.id, now) }
            if (kind == "challenge") { site.challengeDay = now / 86_400_000L; site.challengesToday = 3 }
            if (kind == "human") site.learningNeedsHuman = true
            val blocked = now + if (kind == "review") 24 * 60 * 60_000L else 12 * 60_000L
            site.learningBlockedUntil = blocked
            val events = object : EngineEvents {}
            LearningSession(BrainEngine(fake, memory, { null }, events, config), memory, events,
                listOf(profile(fake.host)), clock = { now }).run(maxSites = 1)
            assertEquals(kind, blocked, site.learningBlockedUntil)
            assertTrue(kind, fake.log.isEmpty())
        }
    }

    @Test fun countdownDoesNotImplyLoginUnlessASiteNeedsReview() {
        for (needsHuman in listOf(false, true)) {
            val fake = FakeSite(); val memory = Memory(InMemoryStorage())
            memory.site(fake.host).learningBlockedUntil = now + 60_000L
            memory.site("review.market").learningNeedsHuman = needsHuman
            memory.site("review.market").learningBlockedUntil = now + 60_000L
            val messages = mutableListOf<String>()
            lateinit var session: LearningSession
            val events = object : EngineEvents {
                override fun status(text: String) {
                    messages += text
                    if (text.contains("next automatic retry")) session.stop()
                }
            }
            session = LearningSession(BrainEngine(fake, memory, { null }, events, config), memory, events,
                listOf(profile(fake.host), profile("review.market")), clock = { now })
            session.run()
            val countdown = messages.single { it.contains("next automatic retry") }
            assertTrue(countdown, countdown.contains("1 min"))
            assertEquals(countdown, needsHuman, countdown.contains("need your review"))
            assertFalse(countdown, countdown.contains("sign-in"))
            assertTrue(fake.log.isEmpty())
        }
    }
}
