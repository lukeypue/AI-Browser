package com.appgate.tv.serverlab

import com.appgate.brain.json.JsonObject
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

/** Explicit synthetic-lab maintenance only. Raw trace bytes exist only in bounded RAM. */
internal object ServerLabAnrTrace {
    const val PACKAGE = "com.aibrowser.universal.serverlab"
    const val MAX_BYTES = 262144
    enum class Status { OBSERVED, UNKNOWN, UNAVAILABLE, LIMIT, TIMEOUT }
    enum class MainState { NATIVE, RUNNABLE, WAITING, TIMED_WAITING, BLOCKED, UNKNOWN }
    enum class Frame { BRAIN_RUNTIME_GET, BRAIN_RUNTIME_INIT, BRAIN_SERVICE_CREATE,
        GECKO_RUNTIME_CREATE, GECKO_SESSION_OPEN, OFFSCREEN_ATTACH, BINDER_TRANSACT, MESSAGE_QUEUE_POLL, OBJECT_WAIT, THREAD_SLEEP }
    data class Result(val status: Status, val mainState: MainState = MainState.UNKNOWN, val frames: Set<Frame> = emptySet()) {
        fun toJson() = JsonObject().put("status",status.name).put("main_state",mainState.name)
            .putStrings("observed_frames",frames.map { it.name }.sorted())
    }
    data class Event(val process: String, val pid: Int, val reason: Int, val timestamp: Long)
    fun selectEventIndex(events: List<Event>, timestamp: Long): Int? {
        if(timestamp<=0 || events.size>16) return null
        return events.indices.filter { i -> events[i].let {
            it.process==PACKAGE && it.pid>0 && it.reason==6 && it.timestamp==timestamp
        } }.singleOrNull()
    }
    private val header=Regex("----- pid ([1-9][0-9]{0,9}) at [^\\r\\n]{1,96} -----")
    private val frame=Regex("\\s+at ([A-Za-z0-9_.$<>]+)\\.([A-Za-z0-9_$<>]+)\\([^\\r\\n]{0,2048}\\)")
    private val known = mapOf(
        "com.appgate.tv.browser.BrainRuntime.get" to Frame.BRAIN_RUNTIME_GET,
        "com.appgate.tv.browser.BrainRuntime\$Companion.get" to Frame.BRAIN_RUNTIME_GET,
        "com.appgate.tv.browser.BrainRuntime.<init>" to Frame.BRAIN_RUNTIME_INIT,
        "com.appgate.tv.service.BrainService.onCreate" to Frame.BRAIN_SERVICE_CREATE,
        "org.mozilla.geckoview.GeckoRuntime.create" to Frame.GECKO_RUNTIME_CREATE,
        "org.mozilla.geckoview.GeckoSession.open" to Frame.GECKO_SESSION_OPEN,
        "com.appgate.tv.browser.OffscreenDisplay.attach" to Frame.OFFSCREEN_ATTACH,
        "android.os.BinderProxy.transactNative" to Frame.BINDER_TRANSACT,
        "android.os.MessageQueue.nativePollOnce" to Frame.MESSAGE_QUEUE_POLL,
        "java.lang.Object.wait" to Frame.OBJECT_WAIT,
        "java.lang.Thread.sleep" to Frame.THREAD_SLEEP
    )
    fun parse(bytes: ByteArray, expectedPid: Int): Result {
        if(bytes.size>MAX_BYTES) return Result(Status.LIMIT)
        if(expectedPid<=0) return Result(Status.UNKNOWN)
        val text=try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }
            catch(_:Exception) { return Result(Status.UNKNOWN) }
        val lines=text.lineSequence().map { it.removeSuffix("\r") }.toList()
        val starts=lines.indices.filter { header.matches(lines[it]) }
        val own=starts.filter { header.matchEntire(lines[it])!!.groupValues[1].toIntOrNull()==expectedPid }
        if(own.size!=1) return Result(Status.UNKNOWN)
        val start=own.single();val next=starts.firstOrNull { it>start } ?: lines.size
        val block=lines.subList(start+1,next)
        val ends=block.indices.filter { block[it].startsWith("----- end ") }
        if(ends.size!=1 || block[ends.single()]!="----- end $expectedPid -----") return Result(Status.UNKNOWN)
        val body=block.take(ends.single())
        if(body.filter { it.startsWith("Cmd line:") }!=listOf("Cmd line: $PACKAGE")) return Result(Status.UNKNOWN)
        val main=body.indices.filter { body[it].startsWith("\"main\" ") }
        if(main.size!=1) return Result(Status.UNKNOWN)
        val mi=main.single(); val title=body[mi]
        if(!Regex("\"main\" (?:prio=[0-9]+ )?tid=1 (?:Native|Runnable|Waiting|TimedWaiting|Blocked)").matches(title)) return Result(Status.UNKNOWN)
        val boundary=(mi+1 until body.size).firstOrNull { body[it].startsWith("\"") } ?: body.size
        // A process terminator proves completeness even when main is its final thread.
        val observed=body.subList(mi+1,boundary).mapNotNull { line -> frame.matchEntire(line)?.let { known[it.groupValues[1]+"."+it.groupValues[2]] } }.toSet()
        val state=when(title.substringAfterLast(' ')) {
            "Native"->MainState.NATIVE; "Runnable"->MainState.RUNNABLE; "Waiting"->MainState.WAITING
            "TimedWaiting"->MainState.TIMED_WAITING; "Blocked"->MainState.BLOCKED; else->MainState.UNKNOWN
        }
        return Result(Status.OBSERVED,state,observed)
    }
    /** Deadline includes stream opening. A late opener/read closes itself; no raw bytes escape. */
    fun collect(pid: Int, timeoutMs: Long = 3000, open: () -> InputStream?): Result {
        require(timeoutMs in 1..5000)
        if(pid<=0) return Result(Status.UNKNOWN)
        val active=AtomicReference<InputStream?>()
        val cancelled=AtomicBoolean(false)
        val pool=Executors.newSingleThreadExecutor { work -> Thread(work,"lab-anr-reader").apply { isDaemon=true } }
        val future=pool.submit<Result> {
            try {
                val stream=open() ?: return@submit Result(Status.UNAVAILABLE)
                active.set(stream)
                stream.use {
                    if(cancelled.get() || Thread.currentThread().isInterrupted) return@submit Result(Status.TIMEOUT)
                    val out=ByteArrayOutputStream();val buffer=ByteArray(4096)
                    while(true) {
                        if(cancelled.get() || Thread.currentThread().isInterrupted) return@submit Result(Status.TIMEOUT)
                        val n=it.read(buffer,0,minOf(buffer.size,MAX_BYTES+1-out.size()))
                        if(n<0) break
                        if(n==0) continue
                        out.write(buffer,0,n)
                        if(out.size()>MAX_BYTES) return@submit Result(Status.LIMIT)
                    }
                    parse(out.toByteArray(),pid)
                }
            } catch(_:Exception) { Result(Status.UNAVAILABLE) }
            finally { active.set(null) }
        }
        fun cancelAndClose() {
            cancelled.set(true)
            future.cancel(true)
            active.get()?.let { stream -> Thread({try {stream.close()}catch(_:Exception){}},"lab-anr-close").apply {isDaemon=true;start()} }
        }
        return try { future.get(timeoutMs,TimeUnit.MILLISECONDS) }
        catch(_:TimeoutException) { cancelAndClose();Result(Status.TIMEOUT) }
        catch(_:Exception) { cancelAndClose();Result(Status.UNAVAILABLE) }
        finally { pool.shutdownNow() }
    }
}
