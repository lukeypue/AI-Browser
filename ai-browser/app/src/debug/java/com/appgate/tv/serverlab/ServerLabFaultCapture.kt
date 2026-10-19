package com.appgate.tv.serverlab

import com.appgate.brain.json.JsonObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException

/** Lab-only closed crash evidence. No messages, stacks, arbitrary names, or browsing values. */
internal object ServerLabFaultCapture {
    enum class Kind {
        SECURITY, ILLEGAL_ARGUMENT, ILLEGAL_STATE, NULL_POINTER, CLASS_NOT_FOUND,
        LINKAGE, OUT_OF_MEMORY, TIMEOUT, EXECUTION, INTERRUPTED,
        FOREGROUND_TIMEOUT, FOREGROUND_NOT_ALLOWED, OTHER
    }
    enum class ObjectKind { INSTRUMENTATION, CONTROLLED_SERVICE, SERVICE, APPLICATION, ACTIVITY, OTHER }
    enum class Stage {
        GUARD, BIND_SERVICE, BIND_REQUESTED, BIND_REJECTED, WAIT_BOUND, BIND_WAIT_TIMEOUT,
        SERVICE_UNAVAILABLE, ENGINE_READY, ENGINE_READY_TIMEOUT, MEMORY_BEFORE,
        LEARNING, STRICT_SNAPSHOT, MEMORY_AFTER, VERIFY_OUTCOME, UNKNOWN
    }
    data class Record(
        val cycleId: UUID, val sourceRevision: String, val versionCode: Int,
        val stage: Stage, val objectKind: ObjectKind, val exceptionKinds: List<Kind>,
        val causeChainTrimmed: Boolean, val elapsedMs: Long, val elapsedClamped: Boolean
    ) {
        init {
            require(sourceRevision.matches(Regex("[0-9a-f]{40}")) && versionCode > 0)
            require(exceptionKinds.size in 1..4 && elapsedMs in 0L..7_200_000L)
        }
        fun toJson() = JsonObject().put("schema_version", 1).put("cycle_id", cycleId.toString())
            .put("source_revision", sourceRevision).put("version_code", versionCode)
            .put("elapsed_ms", elapsedMs).put("elapsed_clamped", elapsedClamped)
            .put("stage", stage.name).put("object_kind", objectKind.name)
            .putStrings("exception_kinds", exceptionKinds.map { it.name })
            .put("cause_chain_trimmed", causeChainTrimmed)
    }

    private fun kind(error: Throwable): Kind {
        // Compare a few framework class names to literals; never retain an arbitrary class name.
        when (error.javaClass.name) {
            "android.app.RemoteServiceException\$ForegroundServiceDidNotStartInTimeException" -> return Kind.FOREGROUND_TIMEOUT
            "android.app.ForegroundServiceStartNotAllowedException" -> return Kind.FOREGROUND_NOT_ALLOWED
        }
        return when (error) {
            is OutOfMemoryError -> Kind.OUT_OF_MEMORY
            is SecurityException -> Kind.SECURITY
            is IllegalArgumentException -> Kind.ILLEGAL_ARGUMENT
            is IllegalStateException -> Kind.ILLEGAL_STATE
            is NullPointerException -> Kind.NULL_POINTER
            is ClassNotFoundException -> Kind.CLASS_NOT_FOUND
            is LinkageError -> Kind.LINKAGE
            is TimeoutException -> Kind.TIMEOUT
            is ExecutionException -> Kind.EXECUTION
            is InterruptedException -> Kind.INTERRUPTED
            else -> Kind.OTHER
        }
    }

    fun capture(cycleId: String, revision: String, version: Int, stage: String,
                objectKind: ObjectKind, error: Throwable, elapsed: Long): Record {
        val id = UUID.fromString(cycleId)
        require(id.toString() == cycleId)
        val kinds = mutableListOf<Kind>()
        val seen = IdentityHashMap<Throwable, Boolean>()
        var current: Throwable? = error
        while (current != null && kinds.size < 4 && seen.put(current, true) == null) {
            kinds += kind(current)
            current = current.cause
        }
        val phase = runCatching { Stage.valueOf(stage) }.getOrDefault(Stage.UNKNOWN)
        return Record(id, revision, version, phase, objectKind, kinds.toList(), current != null,
            elapsed.coerceIn(0L, 7_200_000L), elapsed !in 0L..7_200_000L)
    }

    /** Recording must never suppress, replace, or recover the original failure. */
    fun recordThenDelegate(record: () -> Unit, delegate: () -> Boolean): Boolean {
        try { record() } catch (_: Throwable) { /* Best-effort evidence only. */ }
        return delegate()
    }

    @Synchronized fun writeIfAbsent(directory: File, record: Record): Boolean {
        check(directory.mkdirs() || directory.isDirectory)
        val target = File(directory, record.cycleId.toString() + ".startup-fault.json")
        if (target.exists()) return false
        val bytes = record.toJson().toString().toByteArray(Charsets.UTF_8)
        check(bytes.size <= 4096)
        val temporary = File.createTempFile("startup-fault-", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { output -> output.write(bytes); output.fd.sync() }
            if (target.exists()) return false
            if (!temporary.renameTo(target)) throw IOException("fault_marker_write")
            return true
        } finally { if (temporary.exists()) temporary.delete() }
    }
    class SingleUseTrigger {
        private val armed = java.util.concurrent.atomic.AtomicReference<String?>(null)
        fun arm(id: String, active: Boolean) {
            require(active)
            require(UUID.fromString(id).toString() == id)
            check(armed.compareAndSet(null, id))
        }
        fun consume(): String? = armed.getAndSet(null)
    }

}
