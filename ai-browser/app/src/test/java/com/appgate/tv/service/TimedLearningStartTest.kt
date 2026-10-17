package com.appgate.tv.service

import com.appgate.brain.diagnostics.PhoneReportExporter
import com.appgate.brain.diagnostics.PhoneTestContext
import com.appgate.brain.json.JsonObject
import com.appgate.brain.model.VerifyStatus
import com.appgate.tv.serverlab.ServerLabRuntime
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class TimedLearningStartTest {
    private val revision="a".repeat(40)
    private val id="11111111-1111-4111-8111-111111111111"

    @Test fun sevenMillisecondSetupDelayUsesOneTimerAndDiagnosticsAnchor() {
        val elapsedStart=10_000L
        val wallStart=1_700_000_000_000L
        var now=elapsedStart
        lateinit var context:PhoneTestContext
        val run=beginTimedLearningTest(30,elapsedStart,wallStart) { elapsed,wall ->
            now += 7L
            context=PhoneTestContext(id,elapsed,wall,0,1,revision,70)
        }
        val deadline=now+run.remainingMs(now)
        assertEquals(elapsedStart+1_800_000L,deadline)
        assertEquals(elapsedStart,context.startedElapsed)
        assertEquals(wallStart,context.startedWall)
        assertFalse(run.expired(deadline-1))
        assertTrue(run.expired(deadline))
        run.step(VerifyStatus.VERIFIED)
        run.task("synthetic-task",1)
        val timer=run.finish(deadline,"deadline")
        val end=JsonObject().put("test_id",id).put("boot",1).put("elapsed",deadline)
            .put("kind","learning_test_end").put("x",JsonObject().put("reason","deadline")
                .put("verified_actions",timer.verified).put("verified_procedure_uses",timer.verifiedSkills)
                .put("tasks",timer.tasks).put("failed_actions",timer.failed))
        val report=PhoneReportExporter.export(context,listOf(end),deadline,
            wallStart+(deadline-elapsedStart),0,false,revision,70,currentBoot=1)
        assertEquals(1_800_000L,timer.elapsedMs)
        assertEquals(timer.elapsedMs,report.optLong("elapsed_ms"))
        assertEquals(Instant.ofEpochMilli(wallStart).toString(),report.optString("started_at_utc"))
        assertFalse(report.optObject("flags")!!.optBoolean("clock_changed",true))
        assertTrue(ServerLabRuntime.qualifiesCompleted(report,JsonObject().put("verified_lessons",1).put("verified_skills",0)))
    }

    @Test fun earlyCallbackRemainsBelowDeadlineAndCannotQualify() {
        val start=10_000L
        lateinit var context:PhoneTestContext
        val run=beginTimedLearningTest(30,start,1_700_000_000_000L) { elapsed,wall ->
            context=PhoneTestContext(id,elapsed,wall,0,1,revision,70)
        }
        val early=start+1_799_999L
        assertFalse(run.expired(early))
        assertEquals(1L,run.remainingMs(early))
        val end=JsonObject().put("test_id",id).put("boot",1).put("elapsed",early)
            .put("kind","learning_test_end").put("x",JsonObject().put("reason","deadline")
                .put("verified_actions",1).put("verified_procedure_uses",1))
        val report=PhoneReportExporter.export(context,listOf(end),early,
            context.startedWall+(early-start),0,false,revision,70,currentBoot=1)
        assertEquals(1_799_999L,report.optLong("elapsed_ms"))
        assertFalse(ServerLabRuntime.qualifiesCompleted(report,JsonObject().put("verified_lessons",1).put("verified_skills",1)))
    }
    @Test fun independentDiagnosticsAnchorReproducesSevenMillisecondShortReport() {
        val start=10_000L
        val wall=1_700_000_000_000L
        val run=com.appgate.brain.engine.TimedLearningTest(30,start)
        val context=PhoneTestContext(id,start+7L,wall+7L,0,1,revision,70)
        val deadline=start+1_800_000L
        val report=PhoneReportExporter.export(context,emptyList(),deadline,wall+1_800_000L,
            0,false,revision,70,currentBoot=1)
        assertEquals(1_800_000L,run.finish(deadline,"deadline").elapsedMs)
        assertEquals(1_799_993L,report.optLong("elapsed_ms"))
    }
}
