package com.appgate.tv.store

import java.util.concurrent.Executor
import java.io.OutputStream

/** One in-flight delivery and one replaceable pending snapshot; nothing is persisted. */
class LiveDeliveryQueue<T>(private val executor:Executor,private val now:()->Long={System.nanoTime()/1_000_000},private val deliver:(T,()->Boolean)->Boolean) {
 private data class Packet<T>(val generation:Long,val value:T,val expiresAtMillis:Long)
 private val lock=Any()
 private var generation=0L
 private var pending:Packet<T>?=null
 private var running=false

 fun offer(value:T,expiresAtMillis:Long=Long.MAX_VALUE)=synchronized(lock) {
  pending=Packet(generation,value,expiresAtMillis)
  start()
 }
 fun retry()=synchronized(lock){start()}
 fun cancel()=synchronized(lock){generation++;pending=null}
 private fun start() {
  if(!running&&pending?.let{now()>=it.expiresAtMillis}==true)pending=null
  if(running||pending==null)return
  running=true
  try { executor.execute { drain() } } catch(error:RuntimeException){running=false;throw error}
 }
 private fun drain() {
  while(true) {
   val packet=synchronized(lock) {
    val next=pending
    if(next==null){running=false;return}
    pending=null;next
   }
   if(now()>=packet.expiresAtMillis)continue
   val sent=runCatching { deliver(packet.value) { synchronized(lock){generation==packet.generation} } }.getOrDefault(false)
   synchronized(lock) {
    if(!sent&&generation==packet.generation&&now()<packet.expiresAtMillis) {
     // A failed older send must not overwrite a newer observation.
     if(pending==null)pending=packet
     running=false
     return
    }
   }
  }
 }
}


/** Connect outside the consent lock, then recheck; opt-out can disconnect the established stream during a write. */
object LiveWriteBoundary {
 fun send(open:()->OutputStream,lock:Any,allowed:()->Boolean,body:ByteArray) {
  require(body.size<=32768)
  open().use { output ->
   synchronized(lock) { check(allowed()) }
   output.write(body)
  }
 }
}
