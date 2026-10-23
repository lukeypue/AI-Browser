package com.appgate.tv.serverlab

import com.appgate.brain.json.JsonObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/** Last entered debug-only stage, not proof that the operation completed.
 * Missing evidence may mean storage failure. Never records exception text or page data.
 */
internal class ServerLabStartupTrace(
    private val directory: File,
    private val cycleId: String,
    private val revision: String,
    private val versionCode: Int,
    private val clock: () -> Long
) {
    init {
        require(runCatching { UUID.fromString(cycleId).toString() == cycleId }.getOrDefault(false))
        require(revision.matches(Regex("[0-9a-f]{40}")))
        require(versionCode > 0)
    }
    private val startedAt = clock()
    enum class Stage { ON_CREATE, ON_START, GUARD, RUNTIME_INSTALL, SETTINGS, BIND_SERVICE,
        BIND_REQUESTED, BIND_REJECTED, WAIT_BOUND, BIND_WAIT_TIMEOUT, SERVICE_UNAVAILABLE,
        ENGINE_READY, ENGINE_READY_TIMEOUT, MEMORY_BEFORE, LEARNING, STRICT_SNAPSHOT,
        MEMORY_AFTER, VERIFY_OUTCOME, FINISHED }

    @Synchronized fun record(stage: Stage): Boolean {
        val temporary = File(directory, "startup-stage.json.tmp")
        return try {
            check(directory.mkdirs() || directory.isDirectory)
            val now = clock()
            val elapsed = if (now < startedAt) 0L else
                runCatching { Math.subtractExact(now, startedAt) }.getOrDefault(Long.MAX_VALUE)
                    .coerceIn(0L, 7_200_000L)
            val bytes = JsonObject().put("schema_version", 1).put("cycle_id", cycleId)
                .put("source_revision", revision).put("version_code", versionCode)
                .put("stage", stage.name).put("elapsed_ms", elapsed)
                .toString().toByteArray(Charsets.UTF_8)
            check(bytes.size <= 1024)
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            check(temporary.renameTo(File(directory, "startup-stage.json")))
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
            true
        } catch (_: Exception) {
            false
        } finally {
            runCatching { temporary.delete() }
        }
    }
}
