package com.appgate.tv.serverlab

import com.appgate.brain.json.JsonObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Debug-only observational samples. No raw stack/name/message is written; no fsync or main-thread I/O. */
internal class ServerLabThreadSampler(
 private val directory: File, private val cycleId: String, private val revision: String,
 private val version: Int, private val clock: () -> Long, private val stage: () -> Stage,
 private val main: Thread, instrumentation: Thread?, private val intervalMs: Long = 2000L
) : AutoCloseable {
 enum class Stage { GUARD, ON_CREATE, ON_START, RUNTIME_INSTALL, SETTINGS, BIND_REQUESTED, BEFORE_MAIN_DISPATCH,
  MAIN_BIND_ENTERED, BEFORE_SERVICE_START, AFTER_SERVICE_START, BEFORE_BIND_CALL, AFTER_BIND_CALL,
  BOUND_CALLBACK, WAIT_BOUND, ENGINE_READY, READY, CLEANUP, UNKNOWN }
 private val instrumentation=AtomicReference<Thread?>(instrumentation)
 private val started=clock()
 private val closed=AtomicBoolean(false)
 private val launched=AtomicBoolean(false)
 private val worker=Executors.newSingleThreadScheduledExecutor { task -> Thread(task,"lab-startup-sampler").apply {isDaemon=true} }
 init {
  require(runCatching {UUID.fromString(cycleId).toString()==cycleId}.getOrDefault(false))
  require(revision.matches(Regex("[0-9a-f]{40}")) && version>0 && intervalMs in 10..5000)
 }
 fun start() {
  if(closed.get() || !launched.compareAndSet(false,true))return
  var count=0
  worker.scheduleWithFixedDelay({
   if(!closed.get() && count<60) {count++;sample()}
   // A lost onStart must not leave an immortal diagnostic worker.
   if(closed.get() || count>=60)worker.shutdown()
  },intervalMs,intervalMs,TimeUnit.MILLISECONDS)
 }
 fun registerInstrumentation(thread: Thread) { instrumentation.compareAndSet(null,thread) }
 fun awaitStopped(timeoutMs: Long): Boolean {
  require(timeoutMs in 1..5000)
  return worker.awaitTermination(timeoutMs,TimeUnit.MILLISECONDS)
 }
 private fun sample() {
  val temporary=File(directory,"startup-threads.json.tmp")
  try {
   val row=JsonObject().put("schema_version",1).put("cycle_id",cycleId).put("source_revision",revision)
    .put("version_code",version).put("stage",stage().name)
    .put("elapsed_ms",(clock()-started).coerceIn(0L,7200000L))
    .put("main",classify(main.stackTrace,main.state))
    .put("instrumentation",instrumentation.get()?.let {classify(it.stackTrace,it.state)}
     ?: JsonObject().put("state","NEW").putStrings("frames",emptyList()).put("status","NOT_REGISTERED"))
   val bytes=row.toString().toByteArray(Charsets.UTF_8)
   check(bytes.size<=4096)
   if(closed.get())return
   check(directory.mkdirs() || directory.isDirectory)
   temporary.writeBytes(bytes)
   if(!closed.get())check(temporary.renameTo(File(directory,"startup-threads.json")))
  } catch(_:Exception) {
   // Optional evidence must never make startup fail. Missing evidence is not success.
  } finally {runCatching {temporary.delete()}}
 }
 override fun close() {
  if(!closed.compareAndSet(false,true))return
  worker.shutdownNow()
  try {awaitStopped(2000)}catch(_:InterruptedException){Thread.currentThread().interrupt()}
 }
 companion object {
  private val known=mapOf(
   "com.appgate.tv.browser.BrainRuntime.get" to "BRAIN_RUNTIME_GET",
   "com.appgate.tv.service.BrainService.onCreate" to "BRAIN_SERVICE_CREATE",
   "org.mozilla.geckoview.GeckoRuntime.create" to "GECKO_RUNTIME_CREATE",
   "org.mozilla.gecko.GeckoThread.waitForInit" to "GECKO_WAIT_INIT",
   "org.mozilla.geckoview.GeckoSession.open" to "GECKO_SESSION_OPEN",
   "android.app.Instrumentation.runOnMainSync" to "MAIN_SYNC",
   "android.app.Instrumentation\$SyncRunnable.waitForComplete" to "MAIN_SYNC_WAIT",
   "android.app.ContextImpl.startForegroundService" to "SERVICE_START",
   "android.app.ContextImpl.bindService" to "SERVICE_BIND",
   "android.os.BinderProxy.transactNative" to "BINDER_TRANSACT",
   "android.os.MessageQueue.nativePollOnce" to "MESSAGE_QUEUE_POLL",
   "com.appgate.tv.serverlab.ServerLabStartupTrace.record" to "TRACE_WRITE",
   "sun.nio.ch.FileChannelImpl.force" to "FILE_FORCE",
   "sun.nio.ch.FileDispatcherImpl.force0" to "FILE_FORCE",
   "java.io.FileDescriptor.sync" to "FILE_SYNC",
   "java.lang.Object.wait" to "OBJECT_WAIT",
   "java.util.concurrent.CountDownLatch.await" to "LATCH_WAIT",
   "java.lang.Thread.sleep" to "THREAD_SLEEP")
  fun classify(stack: Array<StackTraceElement>, state: Thread.State): JsonObject {
   if(stack.size>256)return JsonObject().put("state",state.name).putStrings("frames",emptyList()).put("status","LIMIT")
   return JsonObject().put("state",state.name).putStrings("frames",stack.mapNotNull {known[it.className+"."+it.methodName]}.distinct().sorted())
  }
 }
}
