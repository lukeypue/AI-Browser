package com.appgate.tv.serverlab

/** Debug instrumentation only. Deadlines cover bind/engine waits, not main-thread calls or cleanup. */
class ServerLabStartupWait(
    private val clock: () -> Long,
    private val sleep: (Long) -> Unit,
    private val onStage: (Stage) -> Unit = {}
) {
    enum class Stage { BIND_REQUESTED, BIND_REJECTED, WAIT_BOUND, BIND_WAIT_TIMEOUT, ENGINE_READY, ENGINE_READY_TIMEOUT }
    enum class BindOutcome { NOT_REQUESTED, REJECTED, WAITING, CONNECTED, TIMED_OUT, INTERRUPTED }

    private val startedAt = clock()
    private val startupDeadline = startedAt + 120_000L
    var bindAccepted = false
        private set
    var bindOutcome = BindOutcome.NOT_REQUESTED
        private set
    var bindWaitMs = 0L
        private set
    var engineWaitMs = 0L
        private set
    var startupWaitMs = 0L
        private set
    // Measured await elapsed time; scheduler delay means this is not a callback-arrival claim.
    val legacyBind30sExceeded: Boolean get() = bindWaitMs > 30_000L

    fun awaitBinding(requestBind: () -> Boolean, awaitBound: (Long) -> Boolean): Boolean {
        onStage(Stage.BIND_REQUESTED)
        bindAccepted = requestBind()
        if (!bindAccepted) {
            bindOutcome = BindOutcome.REJECTED
            onStage(Stage.BIND_REJECTED)
            startupWaitMs = elapsed(startedAt)
            return false
        }
        bindOutcome = BindOutcome.WAITING
        onStage(Stage.WAIT_BOUND)
        val waitStarted = clock()
        try {
            val remaining = (startupDeadline - clock()).coerceAtLeast(0L)
            // An exhausted budget must not invoke an await with zero or start another attempt.
            val connected = remaining > 0L && awaitBound(remaining) && clock() <= startupDeadline
            bindOutcome = if (connected) BindOutcome.CONNECTED else BindOutcome.TIMED_OUT
            if (!connected) onStage(Stage.BIND_WAIT_TIMEOUT)
            return connected
        } catch (interrupted: InterruptedException) {
            bindOutcome = BindOutcome.INTERRUPTED
            throw interrupted
        } finally {
            bindWaitMs = elapsed(waitStarted)
            startupWaitMs = elapsed(startedAt)
        }
    }

    fun awaitEngineReady(ready: () -> Boolean): Boolean {
        onStage(Stage.ENGINE_READY)
        val waitStarted = clock()
        try {
            while (!ready() && clock() < startupDeadline) {
                val remaining = startupDeadline - clock()
                if (remaining <= 0L) break
                sleep(minOf(100L, remaining))
            }
            val available = clock() <= startupDeadline && ready()
            if (!available) onStage(Stage.ENGINE_READY_TIMEOUT)
            return available
        } finally {
            engineWaitMs = elapsed(waitStarted)
            startupWaitMs = elapsed(startedAt)
        }
    }

    private fun elapsed(since: Long): Long = (clock() - since).coerceIn(0L, 120_000L)
}
