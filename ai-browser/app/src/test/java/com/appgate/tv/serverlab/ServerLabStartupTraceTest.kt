package com.appgate.tv.serverlab

import com.appgate.brain.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ServerLabStartupTraceTest {
    private val id = "01234567-89ab-4cde-8fab-0123456789ab"
    private val revision = "397a2efa01f6fab0d625cc96acd9759cf1ecbd26"
    private fun temporary(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("startup-trace").toFile()
        try { block(dir) } finally { dir.deleteRecursively() }
    }
    @Test fun lastStageSurvivesAWriterReloadWithOnlyClosedMetadata() = temporary { dir ->
        var now = 100L
        val writer = ServerLabStartupTrace(dir, id, revision, 70) { now }
        assertTrue("startup stage must be durably written", writer.record(ServerLabStartupTrace.Stage.ON_CREATE))
        now = 240L
        assertTrue(writer.record(ServerLabStartupTrace.Stage.WAIT_BOUND))
        val saved = Json.parseObject(File(dir, "startup-stage.json").readText())
        assertEquals(setOf("schema_version", "cycle_id", "source_revision", "version_code", "stage", "elapsed_ms"), saved.keys)
        assertEquals(id, saved.optString("cycle_id"))
        assertEquals(revision, saved.optString("source_revision"))
        assertEquals("WAIT_BOUND", saved.optString("stage"))
        assertEquals(140L, saved.optLong("elapsed_ms"))
        assertEquals(1, saved.optInt("schema_version"))
        assertEquals(70, saved.optInt("version_code"))
        assertEquals(listOf("startup-stage.json"), dir.listFiles()!!.map { it.name })
    }
    @Test fun aNewInvocationOverwritesOldIdentityAndDoesNotGrowFiles() = temporary { dir ->
        val first = ServerLabStartupTrace(dir, id, revision, 70) { 100L }
        repeat(20) { assertTrue(first.record(ServerLabStartupTrace.Stage.ENGINE_READY)) }
        val other = "11234567-89ab-4cde-8fab-0123456789ab"
        val second = ServerLabStartupTrace(dir, other, revision, 70) { 500L }
        assertTrue(second.record(ServerLabStartupTrace.Stage.ON_CREATE))
        val saved = Json.parseObject(File(dir, "startup-stage.json").readText())
        assertEquals(other, saved.optString("cycle_id"))
        assertEquals(0L, saved.optLong("elapsed_ms"))
        assertEquals(1, dir.listFiles()!!.size)
    }
    @Test fun aBackwardClockCannotEmitNegativeElapsedTime() = temporary { dir ->
        var now = 100L
        val writer = ServerLabStartupTrace(dir, id, revision, 70) { now }
        now = 1L
        assertTrue(writer.record(ServerLabStartupTrace.Stage.ON_START))
        assertEquals(0L, Json.parseObject(File(dir, "startup-stage.json").readText()).optLong("elapsed_ms"))
    }
    @Test fun aFailedReplacementPreservesThePreviousMarker() = temporary { dir ->
        val writer = ServerLabStartupTrace(dir, id, revision, 70) { 0L }
        assertTrue(writer.record(ServerLabStartupTrace.Stage.ON_START))
        val previous = File(dir, "startup-stage.json").readBytes()
        val blocked = File(dir, "startup-stage.json.tmp").apply { mkdir() }
        File(blocked, "blocker").writeText("synthetic")
        assertFalse(writer.record(ServerLabStartupTrace.Stage.WAIT_BOUND))
        assertArrayEquals(previous, File(dir, "startup-stage.json").readBytes())
    }
    @Test fun malformedIdentityIsRejectedBeforeWritingAnything() = temporary { dir ->
        for ((badId, badRevision, version) in listOf(Triple("../PRIVATE", revision, 70), Triple(id, "PRIVATE_TOKEN", 70), Triple(id, revision, 0))) {
            try {
                ServerLabStartupTrace(dir, badId, badRevision, version) { 0L }
                fail("invalid diagnostic identity must be rejected")
            } catch (_: IllegalArgumentException) {}
        }
        assertTrue(dir.listFiles()!!.isEmpty())
    }
    @Test fun unavailableStorageReturnsFalseWithoutMaskingOriginalFailure() = temporary { dir ->
        val file = File(dir, "not-a-directory").apply { writeText("synthetic") }
        val writer = ServerLabStartupTrace(file, id, revision, 70) { 0L }
        assertFalse(writer.record(ServerLabStartupTrace.Stage.BIND_REQUESTED))
        assertEquals("synthetic", file.readText())
    }
    @Test fun elapsedMetadataStaysBoundedAndCannotRecordArbitraryText() = temporary { dir ->
        var now = 0L
        val writer = ServerLabStartupTrace(dir, id, revision, 70) { now }
        now = Long.MAX_VALUE
        assertTrue(writer.record(ServerLabStartupTrace.Stage.MEMORY_BEFORE))
        val raw = File(dir, "startup-stage.json").readText()
        val saved = Json.parseObject(raw)
        assertEquals(7_200_000L, saved.optLong("elapsed_ms"))
        assertTrue(raw.toByteArray().size <= 1024)
        assertFalse(raw.contains("cookie"))
        assertFalse(raw.contains("http"))
    }
}
