package com.appgate.tv.serverlab

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.ExecutionException

class ServerLabFaultCaptureTest {
    private val id = "11111111-1111-4111-8111-111111111111"
    private val revision = "a".repeat(40)
    private fun capture(error: Throwable = SecurityException("PRIVATE_SECRET"), stage: String = "WAIT_BOUND", elapsed: Long = 250L) =
        ServerLabFaultCapture.capture(id, revision, 70, stage, ServerLabFaultCapture.ObjectKind.SERVICE, error, elapsed)

    @Test fun securityFaultRetainsOnlyClosedFields() {
        val record = capture()
        assertEquals(listOf(ServerLabFaultCapture.Kind.SECURITY), record.exceptionKinds)
        assertEquals(ServerLabFaultCapture.Stage.WAIT_BOUND, record.stage)
        val json = record.toJson()
        assertEquals(setOf("schema_version", "cycle_id", "source_revision", "version_code", "elapsed_ms", "elapsed_clamped", "stage", "object_kind", "exception_kinds", "cause_chain_trimmed"), json.keys)
        assertEquals(id, json.optString("cycle_id"))
        assertEquals(revision, json.optString("source_revision"))
        assertFalse(json.toString().contains("PRIVATE_SECRET"))
        assertFalse(json.toString().contains("SecurityException"))
    }

    private class PrivateNamedFailure : RuntimeException("PRIVATE_QUERY https://private.invalid cookie=PRIVATE_COOKIE")
    @Test fun unknownExceptionNamesAndStagesCannotEscape() {
        val record = capture(PrivateNamedFailure(), "PRIVATE_STAGE")
        assertEquals(listOf(ServerLabFaultCapture.Kind.OTHER), record.exceptionKinds)
        assertEquals(ServerLabFaultCapture.Stage.UNKNOWN, record.stage)
        val raw = record.toJson().toString()
        listOf("PRIVATE", "PrivateNamedFailure", "private.invalid", "cookie=").forEach { assertFalse(raw.contains(it)) }
    }

    @Test fun nestedCausesUseBoundedKnownKinds() {
        val error = ExecutionException(IllegalStateException("PRIVATE", NullPointerException("PRIVATE")))
        val record = capture(error)
        assertEquals(listOf(ServerLabFaultCapture.Kind.EXECUTION, ServerLabFaultCapture.Kind.ILLEGAL_STATE, ServerLabFaultCapture.Kind.NULL_POINTER), record.exceptionKinds)
        assertFalse(record.causeChainTrimmed)
    }

    @Test fun cyclicCausesCannotLoop() {
        val first = RuntimeException("PRIVATE_FIRST")
        val second = IllegalStateException("PRIVATE_SECOND")
        first.initCause(second); second.initCause(first)
        val record = capture(first)
        assertEquals(listOf(ServerLabFaultCapture.Kind.OTHER, ServerLabFaultCapture.Kind.ILLEGAL_STATE), record.exceptionKinds)
        assertTrue(record.causeChainTrimmed)
    }

    @Test fun longCauseChainsAreTrimmedAtFour() {
        var error: Throwable = SecurityException("PRIVATE")
        repeat(12) { error = IllegalStateException("PRIVATE", error) }
        val record = capture(error)
        assertEquals(4, record.exceptionKinds.size)
        assertTrue(record.causeChainTrimmed)
        assertTrue(record.toJson().toString().toByteArray().size <= 4096)
    }

    @Test fun pathLikeAndNoncanonicalCycleIdsAreRejected() {
        listOf("../../PRIVATE", "1-1-1-1-1", id.uppercase().replace("11111111", "AAAAAAAA")).forEach { bad ->
            assertTrue(runCatching { ServerLabFaultCapture.capture(bad, revision, 70, "LEARNING", ServerLabFaultCapture.ObjectKind.OTHER, RuntimeException(), 1L) }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test fun sourceAndVersionMustBeValid() {
        assertTrue(runCatching { ServerLabFaultCapture.capture(id, "PRIVATE_REVISION", 70, "LEARNING", ServerLabFaultCapture.ObjectKind.OTHER, RuntimeException(), 1L) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { ServerLabFaultCapture.capture(id, revision, 0, "LEARNING", ServerLabFaultCapture.ObjectKind.OTHER, RuntimeException(), 1L) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun elapsedMetadataIsBoundedAndClampingIsVisible() {
        val low = capture(elapsed = -1L); val high = capture(elapsed = 7_200_001L); val normal = capture(elapsed = 250L)
        assertEquals(0L, low.elapsedMs); assertTrue(low.elapsedClamped)
        assertEquals(7_200_000L, high.elapsedMs); assertTrue(high.elapsedClamped)
        assertEquals(250L, normal.elapsedMs); assertFalse(normal.elapsedClamped)
    }

    @Test fun aRecordingFailureStillDelegatesExactlyOnce() {
        var delegated = 0
        val result = ServerLabFaultCapture.recordThenDelegate({ throw AssertionError("PRIVATE_WRITE_ERROR") }) { delegated++; false }
        assertFalse(result); assertEquals(1, delegated)
        assertTrue(ServerLabFaultCapture.recordThenDelegate({ }) { true })
    }

    @Test fun originalDelegateExceptionIsNeverWrappedOrSuppressed() {
        val original = SecurityException("PRIVATE_ORIGINAL")
        val observed = runCatching { ServerLabFaultCapture.recordThenDelegate({ throw IllegalStateException("PRIVATE_WRITE_ERROR") }) { throw original } }.exceptionOrNull()
        assertSame(original, observed)
    }

    @Test fun oneAtomicMarkerIsSavedWithoutOverwritingTheFirst() {
        val dir = Files.createTempDirectory("serverlab-fault-test").toFile()
        try {
            val first = capture(); val second = capture(IllegalStateException("PRIVATE_SECOND"))
            assertTrue(ServerLabFaultCapture.writeIfAbsent(dir, first))
            val file = dir.resolve(id + ".startup-fault.json")
            val raw = file.readBytes()
            assertArrayEquals(first.toJson().toString().toByteArray(Charsets.UTF_8), raw)
            assertFalse(ServerLabFaultCapture.writeIfAbsent(dir, second))
            assertArrayEquals(raw, file.readBytes())
            assertEquals(listOf(file.name), dir.listFiles()!!.map { it.name })
            assertFalse(String(raw, Charsets.UTF_8).contains("PRIVATE"))
        } finally { dir.deleteRecursively() }
    }
    @Test fun controlledProbeRequiresAnActiveCanonicalSession() {
        val trigger = ServerLabFaultCapture.SingleUseTrigger()
        assertTrue(runCatching { trigger.arm(id, false) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { trigger.arm("../../PRIVATE", true) }.exceptionOrNull() is IllegalArgumentException)
        assertNull(trigger.consume())
    }
    @Test fun controlledProbeIsSingleUseAndCannotReplaceAnArmedSession() {
        val trigger = ServerLabFaultCapture.SingleUseTrigger()
        trigger.arm(id, true)
        assertTrue(runCatching { trigger.arm("22222222-2222-4222-8222-222222222222", true) }.exceptionOrNull() is IllegalStateException)
        assertEquals(id, trigger.consume()); assertNull(trigger.consume())
        trigger.arm(id, true); assertEquals(id, trigger.consume())
    }

}
