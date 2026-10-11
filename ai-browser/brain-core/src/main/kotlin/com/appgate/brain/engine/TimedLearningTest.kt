package com.appgate.brain.engine

import com.appgate.brain.model.VerifyStatus

/** One bounded live practice run. Caller supplies monotonic elapsed time, including pauses.
 * Outcome counters describe verified actions/rehearsals, not new lessons or model training. */
class TimedLearningTest(val minutes: Int, private val startedAt: Long) {
    init { require(minutes == 30 || minutes == 60) }
    data class Summary(val elapsedMs: Long, val verified: Int, val failed: Int, val tasks: Int, val verifiedSkills: Int, val reason: String)
    private var verified = 0
    private var failed = 0
    private val tasks = linkedMapOf<String, Int>()
    private var result: Summary? = null
    fun remainingMs(now: Long): Long = (minutes * 60_000L - (now - startedAt).coerceAtLeast(0)).coerceAtLeast(0)
    fun expired(now: Long): Boolean = remainingMs(now) == 0L
    fun step(status: VerifyStatus?) {
        if (result != null) return
        if (status == VerifyStatus.VERIFIED) verified++
        if (status == VerifyStatus.FAILED) failed++
    }
    fun task(id: String, verifiedSkills: Int) {
        if (result == null) tasks.putIfAbsent(id, verifiedSkills.coerceAtLeast(0))
    }
    fun finish(now: Long, reason: String): Summary = result ?: Summary(
        (now - startedAt).coerceAtLeast(0), verified, failed, tasks.size, tasks.values.sum(), reason
    ).also { result = it }
}
