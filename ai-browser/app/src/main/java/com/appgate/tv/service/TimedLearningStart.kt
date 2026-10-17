package com.appgate.tv.service

import com.appgate.brain.engine.TimedLearningTest

/** The service deadline and diagnostics must share the same caller-captured clocks. */
internal fun beginTimedLearningTest(
    minutes: Int,
    startedElapsed: Long,
    startedWall: Long,
    beginDiagnostics: (Long, Long) -> Unit
): TimedLearningTest {
    val run = TimedLearningTest(minutes, startedElapsed)
    beginDiagnostics(startedElapsed, startedWall)
    return run
}
