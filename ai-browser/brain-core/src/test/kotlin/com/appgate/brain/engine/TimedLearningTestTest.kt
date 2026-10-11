package com.appgate.brain.engine

import com.appgate.brain.model.VerifyStatus
import org.junit.Assert.*
import org.junit.Test

class TimedLearningTestTest {
    @Test fun deadlineUsesElapsedTimeAndIncludesPauses() {
        val run = TimedLearningTest(30, 1000)
        assertFalse(run.expired(1_800_999))
        assertTrue(run.expired(1_801_000))
        assertEquals(0L, run.remainingMs(1_801_001))
    }
    @Test fun sixtyMinutesDoesNotStopAtThirty() {
        val run = TimedLearningTest(60, 0)
        assertFalse(run.expired(1_800_000))
        assertTrue(run.expired(3_600_000))
    }
    @Test fun summaryCountsOnlyOutcomesAndDeduplicatesCompletedTasks() {
        val run = TimedLearningTest(30, 0)
        run.step(null); run.step(VerifyStatus.VERIFIED); run.step(VerifyStatus.FAILED)
        run.task("one", 2); run.task("one", 2)
        val result = run.finish(120_000, "stopped")
        assertEquals(1, result.verified)
        assertEquals(1, result.failed)
        assertEquals(1, result.tasks)
        assertEquals(2, result.verifiedSkills)
        assertEquals(120_000L, result.elapsedMs)
        run.step(VerifyStatus.VERIFIED)
        assertEquals(result, run.finish(1_800_000, "deadline"))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsUnboundedDuration() { TimedLearningTest(0, 0) }
}
