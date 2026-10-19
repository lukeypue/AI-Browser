package com.appgate.tv.serverlab

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ServerLabAnrTraceTest {
    private fun trace(pid: Int = 123, process: String = ServerLabAnrTrace.PACKAGE, frames: String = "  at com.appgate.tv.browser.BrainRuntime.get(BrainRuntime.kt:42)") = """----- pid $pid at 2026-10-19 09:10:50 -----
Cmd line: $process
"main" prio=5 tid=1 Native
$frames

"worker" prio=5 tid=2 Runnable
  at private.Class.secret(PRIVATE_QUERY:1)

----- end $pid -----
"""
    @Test fun exactOwnMainFramesProduceOnlyClosedCategories() {
        val r=ServerLabAnrTrace.parse(trace().toByteArray(),123)
        assertEquals(ServerLabAnrTrace.Status.OBSERVED,r.status)
        assertEquals(setOf(ServerLabAnrTrace.Frame.BRAIN_RUNTIME_GET),r.frames)
        assertFalse(r.toJson().toString().contains("BrainRuntime.kt"))
    }
    @Test fun wrongPidProcessAndWorkerFramesCannotSupplyEvidence() {
        listOf(trace(pid=124),trace(process="other.private")).forEach {
            assertEquals(ServerLabAnrTrace.Status.UNKNOWN,ServerLabAnrTrace.parse(it.toByteArray(),123).status)
        }
        val worker=trace(frames="  at private.Class.secret(PRIVATE_QUERY:1)").replace("private.Class.secret(PRIVATE_QUERY:1)\n\n----- end", "com.appgate.tv.browser.BrainRuntime.get(BrainRuntime.kt:42)\n\n----- end")
        assertTrue(worker.substringAfter("\"worker\"").contains("BrainRuntime.get"))
        assertTrue(ServerLabAnrTrace.parse(worker.toByteArray(),123).frames.isEmpty())
    }
    @Test fun duplicateProcessOrMainAndIncompleteBlocksRemainUnknown() {
        val original=trace()
        val duplicateMain=original.replace("\"worker\" prio=5 tid=2 Runnable","\"main\" prio=5 tid=2 Runnable")
        listOf(original+original,duplicateMain,original.substringBefore("\"worker\"").trimEnd(),original.replace("----- end 123 -----","")).forEach {
            assertEquals(ServerLabAnrTrace.Status.UNKNOWN,ServerLabAnrTrace.parse(it.toByteArray(),123).status)
        }
    }
    @Test fun privateTextAndSubstringsNeverBecomeCategoriesOrOutput() {
        val r=ServerLabAnrTrace.parse(trace(frames="  at private.Class.com.appgate.tv.browser.BrainRuntime.get(PRIVATE_URL:1)\n  PRIVATE_QUERY cookie=PRIVATE_COOKIE").toByteArray(),123)
        assertTrue(r.frames.isEmpty())
        assertFalse(r.toJson().toString().contains("PRIVATE"))
    }
    @Test fun overLimitAndNullThrowingStreamsStayClosed() {
        assertEquals(ServerLabAnrTrace.Status.LIMIT,ServerLabAnrTrace.parse(ByteArray(ServerLabAnrTrace.MAX_BYTES+1),123).status)
        assertEquals(ServerLabAnrTrace.Status.UNAVAILABLE,ServerLabAnrTrace.collect(123,100){null}.status)
        var closed=false
        val broken=object:InputStream(){override fun read():Int=throw IllegalStateException("PRIVATE"); override fun close(){closed=true}}
        assertEquals(ServerLabAnrTrace.Status.UNAVAILABLE,ServerLabAnrTrace.collect(123,100){broken}.status)
        assertTrue(closed)
    }
    @Test fun collectionBoundsBytesClosesAndTimesOutBlockedRead() {
        var closed=false
        val bytes=object:ByteArrayInputStream(ByteArray(ServerLabAnrTrace.MAX_BYTES+1)){override fun close(){closed=true;super.close()}}
        assertEquals(ServerLabAnrTrace.Status.LIMIT,ServerLabAnrTrace.collect(123,1000){bytes}.status); assertTrue(closed)
        val entered=CountDownLatch(1);val released=CountDownLatch(1);val wasClosed=CountDownLatch(1)
        val slow=object:InputStream(){override fun read():Int {entered.countDown();try {released.await(5,TimeUnit.SECONDS)}catch(_:InterruptedException){};return -1};override fun close(){wasClosed.countDown();released.countDown()}}
        val start=System.nanoTime()
        assertEquals(ServerLabAnrTrace.Status.TIMEOUT,ServerLabAnrTrace.collect(123,50){slow}.status)
        assertTrue((System.nanoTime()-start)/1000000<1500)
        assertTrue(wasClosed.await(1,TimeUnit.SECONDS))
    }

    @Test fun eventSelectionRequiresUniqueExactOwnAnrTimestamp() {
        val own=ServerLabAnrTrace.Event(ServerLabAnrTrace.PACKAGE,123,6,1000)
        assertEquals(0,ServerLabAnrTrace.selectEventIndex(listOf(own),1000))
        listOf(listOf(own.copy(timestamp=999)),listOf(own.copy(reason=5)),listOf(own.copy(process=own.process+":child")),listOf(own,own),listOf(own.copy(pid=0))).forEach {
            assertNull(ServerLabAnrTrace.selectEventIndex(it,1000))
        }
        assertNull(ServerLabAnrTrace.selectEventIndex(List(17){own},1000))
    }
    @Test fun differentBlocksCannotBeCrossStitchedAndEndMustMatch() {
        listOf(trace(process="private.other")+trace(pid=124),trace(process=ServerLabAnrTrace.PACKAGE+":child"),trace().replace("----- end 123 -----","----- end 124 -----")).forEach {
            assertEquals(ServerLabAnrTrace.Status.UNKNOWN,ServerLabAnrTrace.parse(it.toByteArray(),123).status)
        }
    }
    @Test fun namesMustMatchExactlyAndSerializationIsClosed() {
        val r=ServerLabAnrTrace.parse(trace(frames="  at com.appgate.tv.browser.BrainRuntime.getExtra(PRIVATE:1)\n  at private.com.appgate.tv.browser.BrainRuntime.get(PRIVATE:2)").toByteArray(),123)
        assertEquals(ServerLabAnrTrace.Status.OBSERVED,r.status);assertTrue(r.frames.isEmpty())
        assertEquals(setOf("status","main_state","observed_frames"),r.toJson().keys)
        listOf("PRIVATE","123","BrainRuntime").forEach { assertFalse(r.toJson().toString().contains(it)) }
    }
    @Test fun blockedOpenerDeadlineAndLateStreamClosure() {
        val lateClosed=CountDownLatch(1)
        val r=ServerLabAnrTrace.collect(123,50) {
            val until=System.nanoTime()+200000000
            while(System.nanoTime()<until) { try {Thread.sleep(10)}catch(_:InterruptedException){} }
            object:ByteArrayInputStream(trace().toByteArray()){override fun close(){lateClosed.countDown();super.close()}}
        }
        assertEquals(ServerLabAnrTrace.Status.TIMEOUT,r.status)
        assertTrue(lateClosed.await(1,TimeUnit.SECONDS))
        assertEquals(ServerLabAnrTrace.Status.UNAVAILABLE,ServerLabAnrTrace.collect(123,100){throw IllegalArgumentException("PRIVATE")}.status)
    }
    @Test fun lateOpenerThatSwallowsInterruptCannotEnterBlockingRead() {
        val closed=CountDownLatch(1);val release=CountDownLatch(1)
        val r=ServerLabAnrTrace.collect(123,50) {
            val until=System.nanoTime()+200000000
            while(System.nanoTime()<until) {try {Thread.sleep(10)}catch(_:InterruptedException){}}
            object:InputStream(){
                override fun read():Int {try {release.await(5,TimeUnit.SECONDS)}catch(_:InterruptedException){};return -1}
                override fun close(){closed.countDown();release.countDown()}
            }
        }
        assertEquals(ServerLabAnrTrace.Status.TIMEOUT,r.status)
        val actuallyClosed=closed.await(1,TimeUnit.SECONDS);release.countDown()
        assertTrue("late blocking stream must be closed before reading",actuallyClosed)
    }
    @Test fun exactByteBoundaryAndInvalidPidRemainBounded() {
        val bytes=trace().toByteArray();val exact=bytes+ByteArray(ServerLabAnrTrace.MAX_BYTES-bytes.size){32}
        assertEquals(ServerLabAnrTrace.Status.OBSERVED,ServerLabAnrTrace.parse(exact,123).status)
        assertEquals(ServerLabAnrTrace.Status.UNKNOWN,ServerLabAnrTrace.parse(bytes,0).status)
        assertEquals(ServerLabAnrTrace.Status.UNKNOWN,ServerLabAnrTrace.collect(0,100){throw AssertionError("must not open")}.status)
    }
}
