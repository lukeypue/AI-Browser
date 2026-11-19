package com.appgate.brain.engine

import com.appgate.brain.model.VerifyStatus
import com.appgate.brain.json.JsonObject

/** One bounded live practice run. Caller supplies monotonic elapsed time, including pauses.
 * Action/procedure reuse and newly verifier-credited curriculum transitions are distinct counters; none measures model training. */
class TimedLearningTest(val minutes: Int, private val startedAt: Long) {
    init { require(minutes == 30 || minutes == 60) }
    data class Summary(val elapsedMs: Long, val verified: Int, val failed: Int, val tasks: Int, val verifiedSkills: Int, val reason: String, val newLessons:Int=0, val neutralPrograms:Int=0, val heldPrograms:Int=0, val plannerErrors:Int=0, val diagnosticEvents:Int=0,val plannerFailureCounts:Map<String,Int> = emptyMap())
    private var verified = 0
    private var failed = 0
    private val tasks = linkedMapOf<String, Int>()
    private var result: Summary? = null
    private var newLessons=0
    private var neutralPrograms=0
    private var heldPrograms=0
    private var plannerErrors=0
    private var diagnosticEvents=0
    private val plannerFailureCounts=com.appgate.brain.planner.PlannerFailureDiagnostics.summaryKeys.associateWith{0}.toMutableMap()
    /** Called by the owning test before log trimming. Counts events, not provider transport. */
    @Synchronized fun diagnostic(kind:String,data:JsonObject) {
        if(result!=null)return
        diagnosticEvents=(diagnosticEvents+1).coerceAtMost(1_000_000)
        if(kind=="memory_checkpoint") {
            newLessons=(newLessons+data.optInt("new_lessons").coerceIn(0,11)).coerceAtMost(1_000_000)
            if(data.optInt("outcome_neutral")==1)neutralPrograms=(neutralPrograms+1).coerceAtMost(1_000_000)
            if(data.optString("code")=="STRATEGY_HELD")heldPrograms=(heldPrograms+1).coerceAtMost(1_000_000)
        }
        if(kind=="planner_outcome" && data.optString("planner_outcome")=="ERROR") {
            plannerErrors=(plannerErrors+1).coerceAtMost(1_000_000)
            val key=com.appgate.brain.planner.PlannerFailureDiagnostics.summaryKey(data.optString("planner_failure"))
            plannerFailureCounts[key]=(plannerFailureCounts.getValue(key)+1).coerceAtMost(1_000_000)
        }
    }
    fun remainingMs(now: Long): Long = (minutes * 60_000L - (now - startedAt).coerceAtLeast(0)).coerceAtLeast(0)
    fun expired(now: Long): Boolean = remainingMs(now) == 0L
    @Synchronized fun step(status: VerifyStatus?) {
        if (result != null) return
        if (status == VerifyStatus.VERIFIED) verified++
        if (status == VerifyStatus.FAILED) failed++
    }
    @Synchronized fun task(id: String, verifiedSkills: Int) {
        if (result == null) tasks.putIfAbsent(id, verifiedSkills.coerceAtLeast(0))
    }
    @Synchronized fun finish(now: Long, reason: String): Summary = result ?: Summary(
        (now - startedAt).coerceAtLeast(0), verified, failed, tasks.size, tasks.values.sum(), reason, newLessons, neutralPrograms, heldPrograms, plannerErrors, diagnosticEvents, plannerFailureCounts.toMap()
    ).also { result = it }
}
