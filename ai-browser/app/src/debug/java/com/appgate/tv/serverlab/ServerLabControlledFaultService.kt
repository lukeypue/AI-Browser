package com.appgate.tv.serverlab

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.appgate.tv.BuildConfig

/** Explicit single-use instrumentation probe; never present in release builds. */
class ServerLabControlledFaultService : Service() {
    companion object {
        private val trigger = ServerLabFaultCapture.SingleUseTrigger()
        fun arm(cycleId: String) = trigger.arm(cycleId,
            BuildConfig.DEBUG && BuildConfig.SERVER_ANDROID_LAB && ServerLabRuntime.active)
    }
    override fun onCreate() {
        super.onCreate()
        val armed = trigger.consume() != null
        if (armed && BuildConfig.DEBUG && BuildConfig.SERVER_ANDROID_LAB && ServerLabRuntime.active &&
            packageName == "com.aibrowser.universal.serverlab") {
            throw SecurityException("SYNTHETIC_STARTUP_PROBE")
        }
        stopSelf()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
