package com.appgate.tv.serverlab
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
class ServerLabThreadSamplerTest {
 private val id="00000000-0000-4000-8000-000000000001"
 private val rev="a".repeat(40)
 @Test fun exactFramesAreClosedAndUnknownTextIsDropped() {
  val value=ServerLabThreadSampler.classify(arrayOf(
   StackTraceElement("com.appgate.tv.browser.BrainRuntime","get","PRIVATE_URL",42),
   StackTraceElement("android.app.Instrumentation","runOnMainSync","PRIVATE_COOKIE",7),
   StackTraceElement("private.secret","query","PRIVATE_QUERY",9)),Thread.State.BLOCKED)
  val text=value.toString();assertTrue(text.contains("BRAIN_RUNTIME_GET"));assertTrue(text.contains("MAIN_SYNC"));assertTrue(text.contains("BLOCKED"));assertFalse(text.contains("PRIVATE"));assertFalse(text.contains("private.secret"))
 }
 @Test fun similarNamesCannotSupplyEvidenceAndStackIsBounded() {
  val fake=StackTraceElement("private.com.appgate.tv.browser.BrainRuntime","get","SECRET",1)
  val value=ServerLabThreadSampler.classify(Array(257){fake},Thread.State.RUNNABLE)
  assertTrue(value.toString().contains("LIMIT"));assertFalse(value.toString().contains("SECRET"))
  assertEquals("{\"state\":\"RUNNABLE\",\"frames\":[]}",ServerLabThreadSampler.classify(arrayOf(fake),Thread.State.RUNNABLE).toString())
 }
 @Test fun capturesBothExplicitThreadsAndStopsWritingAfterClose() {
  val dir=Files.createTempDirectory("lab-watch").toFile();val entered=CountDownLatch(1);val release=CountDownLatch(1)
  val worker=Thread {entered.countDown();release.await()}.apply {isDaemon=true;start()}
  try {
   assertTrue(entered.await(1,TimeUnit.SECONDS))
   val sampler=ServerLabThreadSampler(dir,id,rev,70,{10L},{ServerLabThreadSampler.Stage.BIND_REQUESTED},Thread.currentThread(),worker,10L)
   sampler.start();val file=File(dir,"startup-threads.json");val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2)
   while(!file.exists()&&System.nanoTime()<deadline)Thread.sleep(5)
   sampler.close();assertTrue(file.exists());val bytes=file.readBytes();val text=bytes.toString(Charsets.UTF_8)
   assertTrue(text.contains(id));assertTrue(text.contains(rev));assertTrue(text.contains("BIND_REQUESTED"));assertTrue(text.contains("instrumentation"));assertTrue(text.contains("main"));assertTrue(bytes.size<=4096)
   Thread.sleep(40);assertArrayEquals(bytes,file.readBytes())
  } finally {release.countDown();worker.join(1000);dir.deleteRecursively()}
 }
 @Test fun invalidIdentityFailsBeforeCreatingFiles() {
  val dir=Files.createTempDirectory("lab-watch-invalid").toFile()
  try {try {ServerLabThreadSampler(dir,"../secret",rev,70,{0L},{ServerLabThreadSampler.Stage.GUARD},Thread.currentThread(),Thread.currentThread());fail()}catch(_:IllegalArgumentException){};assertTrue(dir.listFiles()!!.isEmpty())}finally {dir.deleteRecursively()}
 }
 @Test fun diskFailureCannotThrowOnCallerOrPreventClose() {
  val file=Files.createTempFile("lab-watch-file","tmp").toFile()
  try {val s=ServerLabThreadSampler(file,id,rev,70,{0L},{ServerLabThreadSampler.Stage.GUARD},Thread.currentThread(),Thread.currentThread(),10L);s.start();Thread.sleep(30);s.close();assertTrue(file.isFile)}finally{file.delete()}
 }
 @Test fun samplesBeforeInstrumentationRegistrationWithoutMainThreadDiskIo() {
  val parent=Files.createTempDirectory("lab-early").toFile();val dir=File(parent,"not-created")
  val stage=java.util.concurrent.atomic.AtomicReference(ServerLabThreadSampler.Stage.ON_CREATE)
  val sampler=ServerLabThreadSampler(dir,id,rev,70,{10L},{stage.get()},Thread.currentThread(),null,10L)
  try {
   assertFalse(dir.exists());sampler.start()
   val file=File(dir,"startup-threads.json");val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2)
   while(!file.exists()&&System.nanoTime()<deadline)Thread.sleep(5)
   val first=com.appgate.brain.json.Json.parseObject(file.readText())
   assertEquals("ON_CREATE",first.optString("stage"))
   assertEquals("NOT_REGISTERED",first.optObject("instrumentation")!!.optString("status"))
   sampler.registerInstrumentation(Thread.currentThread());stage.set(ServerLabThreadSampler.Stage.ON_START)
   while(!file.readText().contains("ON_START")&&System.nanoTime()<deadline)Thread.sleep(5)
   val later=com.appgate.brain.json.Json.parseObject(file.readText())
   assertEquals("ON_START",later.optString("stage"));assertFalse(later.optObject("instrumentation")!!.keys.contains("status"))
  } finally {sampler.close();parent.deleteRecursively()}
 }
 @Test fun finiteSamplingStopsWithoutOnStartOrExplicitClose() {
  val dir=Files.createTempDirectory("lab-finite").toFile()
  val s=ServerLabThreadSampler(dir,id,rev,70,{0L},{ServerLabThreadSampler.Stage.GUARD},Thread.currentThread(),Thread.currentThread(),10L)
  try {s.start();assertTrue("worker must end even if onStart never runs",s.awaitStopped(2500))}finally{s.close();dir.deleteRecursively()}
 }
}
