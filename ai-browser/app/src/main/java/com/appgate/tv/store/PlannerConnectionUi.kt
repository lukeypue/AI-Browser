package com.appgate.tv.store

import com.appgate.brain.planner.PlannerConnectionStatus

/** Testable settings transaction; never stores or logs connection values. */
object PlannerConnectionUi {
    fun applyDisableSelection(enabled: Boolean, setEnabled: (Boolean) -> Unit) {
        if (!enabled) setEnabled(false)
    }
    fun applySettings(enabled: Boolean, setEnabled: (Boolean) -> Unit, saveConnection: () -> Unit): Result<Unit> = runCatching {
        setEnabled(false)
        saveConnection()
        if (enabled) setEnabled(true)
    }
    fun testSummaryNote(state: PlannerConnectionStatus, earlierAuthenticationFailures: Int, enabled: Boolean): String {
        val earlier = if (earlierAuthenticationFailures > 0) " Earlier AI authentication requests were rejected." else ""
        if (!enabled) return earlier + " At test end, local-only mode was selected."
        return when (state) {
            PlannerConnectionStatus.AUTHENTICATION_REJECTED -> " At test end, AI requests were paused after authentication rejection. Local learning continues; check AI planner settings and tap SAVE to retry."
            PlannerConnectionStatus.STORAGE_UNAVAILABLE -> " At test end, the AI connection state needed repair; local learning continues."
            PlannerConnectionStatus.READY -> if (earlier.isNotEmpty()) earlier + " At test end, AI was allowed to retry." else ""
        }
    }
}
