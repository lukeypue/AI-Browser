package com.appgate.brain.engine

import com.appgate.brain.goal.GoalParser
import com.appgate.brain.memory.InMemoryStorage
import com.appgate.brain.memory.Memory
import com.appgate.brain.model.*
import com.appgate.brain.profile.SiteProfile
import com.appgate.brain.test.FakeSite
import org.junit.Assert.*
import org.junit.Test

class LearningNavigationTest {
    private val config = EngineConfig(pacingOverrideMs = 0L, ambiguousRecheckMs = 0L)

    @Test fun probeRejectsOffsiteFallbackBeforeNavigation() {
        val fake = FakeSite().apply { url = "https://unrelated.test/"; dialogShown = false }
        val profile = SiteProfile("bad", "Bad profile", listOf(fake.host), "https://outside.test/", minActionIntervalMs = 0L)
        val engine = BrainEngine(fake, Memory(InMemoryStorage()), { null }, object : EngineEvents {}, config)
        assertNull(engine.probeLearning(profile))
        assertFalse(fake.log.any { it.startsWith("navigate ") })
    }

    @Test fun taskRejectsOffsiteStartBeforeNavigation() {
        val fake = FakeSite().apply { dialogShown = false }
        val goal = GoalParser.parse("mountain bike").copy(intent = GoalIntent.LEARN_SITE)
        val ledger = TaskLedger("bad-start", goal, fake.host, "https://outside.test/")
        val engine = BrainEngine(fake, Memory(InMemoryStorage()), { null }, object : EngineEvents {}, config)
        assertEquals(TaskStatus.FAILED, engine.runTask(ledger).status)
        assertFalse(fake.log.any { it.startsWith("navigate ") })
    }
}
