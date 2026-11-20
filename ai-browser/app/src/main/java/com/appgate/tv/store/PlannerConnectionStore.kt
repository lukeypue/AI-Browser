package com.appgate.tv.store

import android.content.Context
import com.appgate.brain.memory.FileBrainStorage
import com.appgate.brain.planner.PlannerAuthenticationHold
import com.appgate.brain.planner.PlannerConnectionStatus
import java.io.File

/** Shared UI/service connection state, separate from keys and learned site files. */
object PlannerConnectionStore {
    private val stores = mutableMapOf<String, PlannerAuthenticationHold>()
    fun get(context: Context): PlannerAuthenticationHold = forDirectory(context.applicationContext.filesDir)
    @Synchronized internal fun forDirectory(files: File): PlannerAuthenticationHold = stores.getOrPut(files.canonicalPath) {
        PlannerAuthenticationHold(FileBrainStorage(File(files, "planner-connection")))
    }
    fun statusText(context: Context): String = when (get(context).status()) {
        PlannerConnectionStatus.READY -> "AI connection ready to try; provider access is checked on the next request."
        PlannerConnectionStatus.AUTHENTICATION_REJECTED -> "AI requests paused after authentication rejection (401). Local learning continues. Check AI planner settings and tap SAVE to retry."
        PlannerConnectionStatus.STORAGE_UNAVAILABLE -> "AI connection state needs repair. Local learning continues. Save AI planner settings to retry."
    }
}
