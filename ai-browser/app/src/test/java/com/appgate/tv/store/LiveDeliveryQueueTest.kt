package com.appgate.tv.store

import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.Executor

class LiveDeliveryQueueTest {
 private class Manual:Executor {
  val jobs=ArrayDeque<Runnable>()
  override fun execute(command:Runnable){jobs.addLast(command)}
  fun run(){jobs.removeFirst().run()}
 }
 @Test fun aThousandPendingSnapshotsCoalesceToLatestWithOneScheduledTask() {
  val executor=Manual();val sent=mutableListOf<Int>()
  val queue=LiveDeliveryQueue<Int>(executor){value,current->assertTrue(current());sent.add(value);true}
  repeat(1000){queue.offer(it)}
  assertEquals(1,executor.jobs.size);executor.run()
  assertEquals(listOf(999),sent);assertTrue(executor.jobs.isEmpty())
 }
 @Test fun whileSendingOnlyNewestPendingValueIsDelivered() {
  val executor=Manual();val sent=mutableListOf<Int>()
  lateinit var queue:LiveDeliveryQueue<Int>
  queue=LiveDeliveryQueue(executor){value,_->sent.add(value);if(value==1)for(i in 2..1000)queue.offer(i);true}
  queue.offer(1);executor.run()
  assertEquals(listOf(1,1000),sent);assertTrue(executor.jobs.isEmpty())
 }
 @Test fun cancellationInvalidatesInFlightGenerationAndClearsPending() {
  val executor=Manual();val sent=mutableListOf<Int>()
  lateinit var queue:LiveDeliveryQueue<Int>
  queue=LiveDeliveryQueue(executor){value,current->
   assertTrue(current());queue.offer(2);queue.cancel();assertFalse(current());sent.add(value);false
  }
  queue.offer(1);executor.run();queue.retry()
  assertEquals(listOf(1),sent);assertTrue(executor.jobs.isEmpty())
 }
 @Test fun failedLatestSnapshotRemainsBoundedAndCanRetry() {
  val executor=Manual();val attempts=mutableListOf<Int>();var success=false
  val queue=LiveDeliveryQueue<Int>(executor){value,_->attempts.add(value);success}
  queue.offer(1);executor.run();assertTrue(executor.jobs.isEmpty())
  queue.offer(2);queue.offer(3);executor.run();success=true;queue.retry();executor.run()
  assertEquals(listOf(1,3,3),attempts)
 }
 @Test fun failedOldSnapshotCannotReplaceNewerPendingSnapshot() {
  val executor=Manual();val sent=mutableListOf<Int>()
  lateinit var queue:LiveDeliveryQueue<Int>
  queue=LiveDeliveryQueue(executor){value,_->sent.add(value);if(value==1){queue.offer(2);false}else true}
  queue.offer(1);executor.run();queue.retry();executor.run()
  assertEquals(listOf(1,2),sent)
 }
 @Test fun cancelledScheduledTaskCannotSendItsOldValueAfterReenable() {
  val executor=Manual();val sent=mutableListOf<Int>()
  val queue=LiveDeliveryQueue<Int>(executor){value,_->sent.add(value);true}
  queue.offer(1);queue.cancel();queue.offer(2)
  assertEquals(1,executor.jobs.size);executor.run()
  assertEquals(listOf(2),sent)
 }

 @Test fun expiredSnapshotIsDiscardedWithoutDeliveryOrRetry() {
  val executor=Manual();var now=0L;val sent=mutableListOf<Int>()
  val queue=LiveDeliveryQueue<Int>(executor,now={now}){value,_->sent.add(value);false}
  queue.offer(1,expiresAtMillis=30);now=30;executor.run();queue.retry()
  assertTrue(sent.isEmpty());assertTrue(executor.jobs.isEmpty())
 }
 @Test fun expiryDuringFailedSendCannotResurrectSnapshot() {
  val executor=Manual();var now=0L;val sent=mutableListOf<Int>()
  val queue=LiveDeliveryQueue<Int>(executor,now={now}){value,_->sent.add(value);now=30;false}
  queue.offer(1,expiresAtMillis=30);executor.run();queue.retry()
  assertEquals(listOf(1),sent);assertTrue(executor.jobs.isEmpty())
 }
 @Test fun optOutDuringConnectionAcquisitionPreventsBodyUpload() {
  val lock=Any();var enabled=true
  val bytes=java.io.ByteArrayOutputStream()
  assertThrows(IllegalStateException::class.java){
   LiveWriteBoundary.send({enabled=false;bytes},lock,{enabled},byteArrayOf(1,2,3))
  }
  assertEquals(0,bytes.size())
 }
 @Test fun currentConnectionWritesBoundedBodyAfterAcquisition() {
  val bytes=java.io.ByteArrayOutputStream()
  LiveWriteBoundary.send({bytes},Any(),{true},byteArrayOf(1,2,3))
  assertArrayEquals(byteArrayOf(1,2,3),bytes.toByteArray())
 }

 @Test fun bodyWriteDoesNotHoldConsentLockSoOptOutCanDisconnect() {
  val lock=Any();var written=false
  val output=object:java.io.OutputStream(){
   override fun write(value:Int){assertFalse(Thread.holdsLock(lock));written=true}
   override fun write(bytes:ByteArray){assertFalse(Thread.holdsLock(lock));written=true}
  }
  LiveWriteBoundary.send({output},lock,{true},byteArrayOf(1))
  assertTrue(written)
 }
}
