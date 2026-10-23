package com.appgate.brain.engine

import com.appgate.brain.json.Json
import com.appgate.brain.memory.*
import com.appgate.brain.model.*
import com.appgate.brain.perception.SpsParser
import com.appgate.brain.profile.SiteProfile
import com.appgate.brain.test.FakeSite
import org.junit.Assert.*
import org.junit.Test

class AlternateListingLearningTest {
    private val now = 1_800_000_000_000L
    private val config = EngineConfig(pacingOverrideMs = 0, plannerCooldownMs = 0, ambiguousRecheckMs = 0, idleSleepMs = 0)
    private fun fake() = FakeSite().apply { dialogShown = false; navigate("https://fake.market/search?q=Ford", 1000) }
    private fun page(fake: FakeSite) = SpsParser().parse(fake.observe(1000), now)
    private fun holdFirst(memory: Memory, page: SemanticPageState): String {
        val target = LearningOpportunities.target(page, "open_item")!!
        val key = FailedStrategies.key(page, "open_item", memory.skills.get("open_item")!!.body, target.params)
        FailedStrategies.record(memory.site(page.host), key, false, now)
        memory.saveSite(memory.site(page.host))
        return key
    }
    private fun engine(fake: FakeSite, memory: Memory) = BrainEngine(fake, memory, { null }, object : EngineEvents {}, config) { now }

    @Test fun retainedFirstListingFailureDoesNotHideAnotherLiveOpportunity() {
        val fake = fake(); val storage = InMemoryStorage()
        val initial = Memory(storage) { now }; val observed = page(fake)
        assertTrue(observed.resultKeys.size > 1)
        val held = holdFirst(initial, observed)
        val restored = Memory(storage) { now }
        val profile = SiteProfile("fake", "Fake", listOf(fake.host), "https://fake.market/")
        assertTrue("a different listing remains runnable after reload",
            engine(fake, restored).learningLessonAvailable(profile, observed, "open_item", allowTeacher = false))
        assertFalse("choosing an alternative must preserve the original failure",
            FailedStrategies.allowed(restored.site(fake.host), held, now))
    }

    @Test fun actualLearningOpensAnotherListingWithoutReplayingHeldFirstItem() {
        val fake = fake(); val storage = InMemoryStorage()
        val observed = page(fake); val initial = Memory(storage) { now }
        val held = holdFirst(initial, observed)
        val restored = Memory(storage) { now }
        val goal = Goal("alternate-item", GoalIntent.LEARN_SITE, "synthetic detail lesson", "Ford", emptyList(),
            budget = Budget(actions = 8, llmCalls = 0, wallMs = 10000))
        val ledger = TaskLedger(goal.id, goal, fake.host, fake.url).apply { lesson = "open_item"; lastCheckpointUrl = fake.url }
        val out = engine(fake, restored).runTask(ledger, EngineMode.TRAIN)
        assertTrue("a genuinely opened detail must earn its own verified lesson", "open_item" in out.successfulSkills)
        assertEquals(TaskStatus.DONE, out.status)
        assertEquals(PageType.DETAIL, page(fake).pageType)
        assertEquals("https://fake.market/item/2", fake.url)
        assertEquals("only the alternative should be clicked", 1, out.actions)
        assertEquals(0, out.llmCalls)
        assertFalse(FailedStrategies.allowed(restored.site(fake.host), held, now))
    }

    @Test fun noEligibleAlternativePreservesTheExistingHoldAndSafetyGates() {
        val fake = fake(); val observed = page(fake)
        val memory = Memory(InMemoryStorage()) { now }
        val site = memory.site(fake.host); val skill = memory.skills.get("open_item")!!
        observed.affordances.filter { it.role == Role.RESULT_ITEM && it.itemKey in observed.resultKeys }.forEach {
            FailedStrategies.record(site, FailedStrategies.key(observed, "open_item", skill.body, mapOf("item" to it.itemKey!!)), false, now)
        }
        val before = site.strategyFailures.toMap()
        val profile = SiteProfile("fake", "Fake", listOf(fake.host), "https://fake.market/")
        for (unsafe in listOf(observed, observed.copy(authWall = true), observed.copy(challenge = true),
            observed.copy(settle = Settle.BUSY))) {
            assertFalse(engine(fake, memory).learningLessonAvailable(profile, unsafe, "open_item", allowTeacher = false))
        }
        assertEquals(before, site.strategyFailures)
    }


    @Test fun candidateBoundAppliesAfterDeduplicationAndUnsafeItemsAreExcluded() {
        val observed = page(fake())
        val source = observed.affordances.first { it.role == Role.RESULT_ITEM }
        val safe = (1..12).map { source.copy(id = "safe-$it", itemKey = "key-$it") }
        val unsafe = listOf(source.copy(id = "hidden", itemKey = "hidden", visible = false),
            source.copy(id = "disabled", itemKey = "disabled", enabled = false),
            source.copy(id = "offsite", itemKey = "offsite", sameSite = false),
            source.copy(id = "commit", itemKey = "commit", effect = EffectClass.COMMIT_EXTERNAL),
            source.copy(id = "not-a-result", itemKey = "unlisted"))
        val candidates = LearningOpportunities.targets(observed.copy(
            affordances = unsafe + List(12) { safe.first().copy(id = "duplicate-$it") } + safe,
            collections = observed.collections.map { it.copy(itemKeys = (safe.map { a -> a.itemKey!! } + unsafe.dropLast(1).map { a -> a.itemKey!! })) }
        ), "open_item")
        assertEquals((1..8).map { "key-$it" }, candidates.map { it.params.getValue("item") })
    }

    @Test fun changingVisibleOrderDoesNotMoveTheFailureToTheWrongItem() {
        val fake = fake(); val observed = page(fake())
        val storage = InMemoryStorage(); val initial = Memory(storage) { now }
        val held = holdFirst(initial, observed)
        val restored = Memory(storage) { now }
        val profile = SiteProfile("fake", "Fake", listOf(fake.host), "https://fake.market/")
        val reordered = observed.copy(affordances = observed.affordances.reversed())
        assertTrue(engine(fake, restored).learningLessonAvailable(profile, reordered, "open_item", allowTeacher = false))
        assertFalse(FailedStrategies.allowed(restored.site(fake.host), held, now))
    }

    @Test fun backLessonCanUseAnotherItemAndVerifyTheReturn() {
        val fake = fake(); val observed = page(fake())
        val storage = InMemoryStorage(); holdFirst(Memory(storage) { now }, observed)
        val restored = Memory(storage) { now }
        val goal = Goal("alternate-back", GoalIntent.LEARN_SITE, "synthetic return lesson", "Ford", emptyList(),
            budget = Budget(actions = 8, llmCalls = 0, wallMs = 10000))
        val ledger = TaskLedger(goal.id, goal, fake.host, fake.url).apply { lesson = "go_back"; lastCheckpointUrl = fake.url }
        val out = engine(fake, restored).runTask(ledger, EngineMode.TRAIN)
        assertEquals(TaskStatus.DONE, out.status)
        assertTrue("go_back" in out.successfulSkills)
        assertTrue("open_item" in out.successfulSkills)
        assertEquals(PageType.RESULTS, page(fake).pageType)
        assertEquals(2, out.actions)
        assertEquals(0, out.llmCalls)
    }


    @Test fun diskReloadKeepsFailedItemHeldAcrossThreeVerifiedAlternativeRuns() {
        val directory = java.nio.file.Files.createTempDirectory("brain-alternate-item").toFile()
        try {
            val initialFake = fake()
            val held = holdFirst(Memory(FileBrainStorage(directory)) { now }, page(initialFake))
            repeat(3) { ordinal ->
                val fake = fake()
                val memory = Memory(FileBrainStorage(directory)) { now + ordinal * 1000 }
                val goal = Goal("disk-alternate-$ordinal", GoalIntent.LEARN_SITE, "synthetic detail", "Ford", emptyList(),
                    budget = Budget(actions = 8, llmCalls = 0, wallMs = 10000))
                val ledger = TaskLedger(goal.id, goal, fake.host, fake.url).apply { lesson = "open_item"; lastCheckpointUrl = fake.url }
                val out = engine(fake, memory).runTask(ledger, EngineMode.TRAIN)
                assertEquals("https://fake.market/item/2", fake.url)
                assertTrue("open_item" in out.successfulSkills)
                assertEquals(1, out.actions)
                assertEquals(0, out.llmCalls)
                assertFalse(FailedStrategies.allowed(memory.site(fake.host), held, now))
            }
            val restored = Memory(FileBrainStorage(directory)) { now + 10000 }
            assertTrue(restored.site(initialFake.host).curriculum.single { it.id == "open_item" }.done)
            assertFalse(FailedStrategies.allowed(restored.site(initialFake.host), held, now))
        } finally { directory.deleteRecursively() }
    }

}
