package com.appgate.tv.serverlab

import android.app.Instrumentation
import android.app.Activity
import android.content.*
import android.os.*
import com.appgate.tv.BuildConfig
import com.appgate.tv.service.BrainService
import com.appgate.tv.store.PhoneSharing
import com.appgate.tv.store.PlannerKeyStore
import com.appgate.brain.json.*
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference

/** Invoke only against the isolated lab package, on emulator-5580. */
class ServerLabInstrumentation : Instrumentation() {
    private lateinit var args: Bundle
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); args=arguments ?: Bundle(); start() }
    override fun onStart() {
        val command=args.getString("command") ?: "smoke"
        val rawId=args.getString("cycle_id") ?: UUID.randomUUID().toString()
        // Reject arbitrary paths before creating a file.
        val id=runCatching { UUID.fromString(rawId).toString().also { require(it==rawId) } }.getOrElse {
            finish(Activity.RESULT_CANCELED,Bundle().apply { putString("result","INVALID_CYCLE_ID") }); return
        }
        if(command !in setOf("smoke","cycle","probe")) {
            finish(Activity.RESULT_CANCELED,Bundle().apply { putString("result","INVALID_COMMAND") }); return
        }
        var service:BrainService?=null
        var bound=false
        val ready=CountDownLatch(1)
        val complete=CountDownLatch(1)
        val report=AtomicReference<JsonObject?>()
        var before:JsonObject?=null
        var after:JsonObject?=null
        var engineReady=false
        var status="FAILED"
        var end="RUNTIME_FAILED"
        var elapsed=0L
        var stage="GUARD"
        var startupWait:ServerLabStartupWait?=null
        val connection=object:ServiceConnection {
            override fun onServiceConnected(name:ComponentName,binder:IBinder) { service=(binder as BrainService.LocalBinder).service; ready.countDown() }
            override fun onServiceDisconnected(name:ComponentName) {}
        }
        try {
            check(BuildConfig.DEBUG && BuildConfig.SERVER_ANDROID_LAB)
            check(targetContext.packageName=="com.aibrowser.universal.serverlab")
            ServerLabRuntime.install(targetContext, ServerLabRuntime.ReportIdentityGate { r -> report.set(r); complete.countDown() })
            // Explicitly disable transports; this never reads a planner key.
            PhoneSharing(targetContext).setEnabled(false)
            PlannerKeyStore.setTeacherEnabled(targetContext,false)
            val startup=ServerLabStartupWait(SystemClock::elapsedRealtime, SystemClock::sleep) { stage=it.name }
            startupWait=startup
            check(startup.awaitBinding(requestBind={
                runOnMainSync {
                    BrainService.start(targetContext)
                    bound=targetContext.bindService(Intent(targetContext,BrainService::class.java),connection,Context.BIND_AUTO_CREATE)
                }
                bound
            }, awaitBound={ timeout -> ready.await(timeout,TimeUnit.MILLISECONDS) }))
            val s=service ?: run { stage="SERVICE_UNAVAILABLE"; error("SERVICE_UNAVAILABLE") }
            check(startup.awaitEngineReady { s.state.engineReady })
            engineReady=true
            waitForIdleSync()
            stage="MEMORY_BEFORE"
            s.serverLabBarrier().get(30,TimeUnit.SECONDS)
            before=ServerLabRuntime.project(s.memory)
            if(command=="probe") {
                after=before; before=null; status="PROBE"; end="INTERRUPTED"
            } else {
                stage="LEARNING"
                runOnMainSync { s.startLearning(30) }
                if(command=="smoke") {
                    SystemClock.sleep(60_000)
                    runOnMainSync { s.stopWork() }
                }
                stage="STRICT_SNAPSHOT"
                check(complete.await(if(command=="cycle") 32 else 1,TimeUnit.MINUTES))
                // Future.isDone is insufficient after cancel: this executor barrier waits for the actual job.
                stage="MEMORY_AFTER"
                s.serverLabBarrier().get(60,TimeUnit.SECONDS)
                waitForIdleSync()
                after=ServerLabRuntime.project(s.memory)
                val r=report.get() ?: error("NO_STRICT_REPORT")
                elapsed=r.optLong("elapsed_ms")
                val summary=r.optObject("summary") ?: error("NO_SUMMARY")
                val interrupted=r.optObject("flags")?.optBoolean("interrupted",true) ?: true
                stage="VERIFY_OUTCOME"
                if(command=="smoke") {
                    check(summary.optInt("verified")>0)
                    status="SMOKE"; end="INTERRUPTED"
                } else if(ServerLabRuntime.qualifiesCompleted(r,after)) {
                    status="COMPLETED"; end="DEADLINE"
                } else {
                    status=if(interrupted) "INTERRUPTED" else "FAILED"
                    end=if(interrupted) "INTERRUPTED" else "RUNTIME_FAILED"
                }
            }
        } catch(_:Throwable) {
            runCatching { runOnMainSync { service?.stopWork() } }
            // No exception messages, page content, logs or preferences leave this runner.
            status="FAILED"; end="RUNTIME_FAILED"
        } finally {
            ServerLabRuntime.detach()
            if(bound) runCatching { runOnMainSync { targetContext.unbindService(connection) } }
        }
        val local=JsonObject().put("schema_version",1).put("cycle_id",id).put("status",status).put("end_reason",end)
            .put("app_elapsed_ms",elapsed.coerceIn(0,7_200_000)).put("diagnostics",if(command=="probe") null as JsonValue? else report.get())
            .put("memory_before",before).put("memory_after",after).put("engine_ready",engineReady)
            .put("version_code",BuildConfig.VERSION_CODE).put("source_revision",BuildConfig.DIAGNOSTICS_REVISION)
        val bytes=local.toString().toByteArray(Charsets.UTF_8)
        check(bytes.size<=1_048_576)
        val dir=File(targetContext.filesDir,"serverlab").apply { check(mkdirs() || isDirectory) }
        val name=if(command=="probe") "probe.json" else "$id.json"
        val temp=File(dir,"$name.tmp")
        FileOutputStream(temp).use { out -> out.write(bytes); out.fd.sync() }
        check(temp.renameTo(File(dir,name)))
        finish(if(status=="FAILED") Activity.RESULT_CANCELED else Activity.RESULT_OK,
            Bundle().apply {
                putString("result",status); putString("stage",stage); putString("output","files/serverlab/$name")
                putBoolean("bind_accepted",startupWait?.bindAccepted ?: false)
                putString("bind_wait_outcome",startupWait?.bindOutcome?.name ?: "NOT_REQUESTED")
                putLong("bind_wait_ms",startupWait?.bindWaitMs ?: 0L)
                putLong("engine_wait_ms",startupWait?.engineWaitMs ?: 0L)
                putLong("startup_wait_ms",startupWait?.startupWaitMs ?: 0L)
                putBoolean("legacy_bind_30s_exceeded",startupWait?.legacyBind30sExceeded ?: false)
            })
    }
}
