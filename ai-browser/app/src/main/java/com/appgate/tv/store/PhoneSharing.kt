package com.appgate.tv.store

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.work.*
import com.appgate.brain.diagnostics.PhoneQueuePolicy
import com.appgate.brain.diagnostics.PhoneReportExporter
import com.appgate.brain.json.*
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.HttpsURLConnection

/** Phone holds only its upload credential. It never receives a repository credential. */
class PhoneSharing(context:Context) {
 private val app=context.applicationContext
 private val prefs=app.getSharedPreferences("phone_sharing",Context.MODE_PRIVATE)
 private val queue=File(app.noBackupFilesDir,"phone-report-queue").apply{mkdirs()}
 init {
  synchronized(lock){
   val marker=File(app.noBackupFilesDir,"phone-sharing-enrollment")
   if(!marker.exists()){
    prefs.edit().clear().putBoolean("enabled",false).commit()
    marker.writeText("sharing-consent-is-device-local-v1")
   }
  }
 }

 fun enabled()=prefs.getBoolean("enabled",false)
 fun generation()=prefs.getLong("generation",0)
 fun status():String=synchronized(lock){
  if(!enabled())"Automatic phone diagnostics: OFF"
  else "${prefs.getString("status","Pair this phone to enable delivery")} · ${files().size} pending"
 }
 fun setEnabled(on:Boolean)=synchronized(lock){
  prefs.edit().putBoolean("enabled",on).putLong("generation",generation()+1).putString("status",if(on)"Pair this phone to enable delivery" else "Sharing off; unsent reports cleared").commit()
  if(!on){active?.disconnect();active=null;WorkManager.getInstance(app).cancelAllWorkByTag(TAG);queue.listFiles()?.forEach{it.delete()};prefs.edit().remove("credential").remove("device").commit()}
 }
 private fun key():SecretKey {
  val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
  return (store.getKey(KEY,null) as? SecretKey)?:KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply{
   init(KeyGenParameterSpec.Builder(KEY,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
  }.generateKey()
 }
 private fun encrypt(secret:String):String {
  val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key())
  return b64(cipher.iv)+":"+b64(cipher.doFinal(secret.toByteArray(Charsets.UTF_8)))
 }
 private fun credential():String {
  val parts=prefs.getString("credential",null)?.split(':')?:error("Pairing required")
  val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.decode(parts[0],FLAGS)))
  return String(cipher.doFinal(Base64.decode(parts[1],FLAGS)),Charsets.UTF_8)
 }
 fun pair(code:String):Boolean {
  val owner=generation();val token=b64(ByteArray(32).also{SecureRandom().nextBytes(it)})
  if(!enabled()||!code.matches(Regex("[A-Za-z0-9_-]{32}")))return false
  if(prefs.contains("credential")){prefs.edit().putString("status","To pair again, turn sharing off and on; unsent copies will be cleared").commit();return false}
  return runCatching {
   val (status,raw)=request("/v1/pair",PhoneReportExporter.canonical(Json.obj("code" to code,"credential" to token)),null,owner)
   require(status==201);val device=Json.parseObject(raw).optString("device_id");require(UUID.fromString(device).toString()==device)
   synchronized(lock){require(enabled()&&generation()==owner);prefs.edit().putString("credential",encrypt(token)).putString("device",device).putString("status","Paired; waiting for a completed phone test").commit()}
   schedule();true
  }.getOrElse {synchronized(lock){if(enabled()&&generation()==owner)prefs.edit().putString("status","Pairing failed; check the single-use code and connection").commit()};false}
 }
 private fun boot()=Settings.Global.getInt(app.contentResolver,Settings.Global.BOOT_COUNT,-1)
 private fun files()=queue.listFiles()?.filter{it.name.endsWith(".json")}?.sortedBy{it.name}.orEmpty()
 private fun prune() {
  queue.listFiles()?.filter{it.name.endsWith(".tmp")}?.forEach{it.delete()}
  files().forEach {file->
   val valid=runCatching {
    val envelope=Json.parseObject(file.readText());val age=if(envelope.optInt("boot")==boot())SystemClock.elapsedRealtime()-envelope.optLong("created_elapsed") else System.currentTimeMillis()-envelope.optLong("created_wall")
    PhoneQueuePolicy.canRetry(age)
   }.getOrDefault(false)
   if(!valid){file.delete();prefs.edit().putString("status","An unsent report expired or its retry clock was invalid").commit()}
  }
 }
 fun enqueue(report:JsonObject,owner:Long)=synchronized(lock){
  if(!enabled()||generation()!=owner)return@synchronized
  prune();val payload=PhoneReportExporter.canonical(report)
  val stored=PhoneReportExporter.canonical(Json.obj("created_wall" to System.currentTimeMillis(),"created_elapsed" to SystemClock.elapsedRealtime(),"boot" to boot(),"generation" to owner,"report" to report))
  val bytes=stored.toByteArray(Charsets.UTF_8);val existing=files()
  if(payload.toByteArray(Charsets.UTF_8).size>1048576 || !PhoneQueuePolicy.canAdd(existing.size,existing.sumOf{it.length()},bytes.size)){
   prefs.edit().putString("status","Report not queued: the 10-report / 10 MiB queue limit was reached").commit();return@synchronized
  }
  val id=report.optString("report_id");require(UUID.fromString(id).toString()==id)
  val destination=File(queue,"$id.json");if(destination.exists())return@synchronized
  val temporary=File(queue,"$id.tmp")
  FileOutputStream(temporary).use{it.write(bytes);it.fd.sync()};check(temporary.renameTo(destination))
  prefs.edit().putString("status","Queued; waiting for network and pairing").commit();schedule()
 }
 fun snapshotFailed(){synchronized(lock){if(enabled())prefs.edit().putString("status","Snapshot failed; no report sent").commit()}}
 private fun schedule(){
  if(!enabled()||!prefs.contains("credential"))return
  val request=OneTimeWorkRequestBuilder<PhoneUploadWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).addTag(TAG).build()
  WorkManager.getInstance(app).enqueueUniqueWork(TAG,ExistingWorkPolicy.APPEND_OR_REPLACE,request)
 }
 fun drain(worker:Worker):Boolean {
  val pending=synchronized(lock){if(!enabled())return true;prune();files()}
  for(file in pending){
   if(worker.isStopped)return false
   val packet=synchronized(lock){
    if(!enabled())return true
    if(!file.exists())return@synchronized null
    val envelope=Json.parseObject(file.readText());val report=envelope.optObject("report")?:error("Invalid report")
    if(envelope.optLong("generation")!=generation()){file.delete();return@synchronized null}
    Triple(report,generation(),prefs.getString("device",null)?.let{it+"."+credential()})
   }?:continue
   val authorization=packet.third?:return false
   val payload=PhoneReportExporter.canonical(packet.first)
   val response=runCatching{request("/v1/reports",payload,authorization,packet.second)}.getOrElse{
    synchronized(lock){if(enabled())prefs.edit().putString("status","Delivery pending; will retry over Wi-Fi or mobile data").commit()};return false
   }
   if(response.first!=200){
    synchronized(lock){if(enabled())prefs.edit().putString("status",when(response.first){401->"Pairing no longer valid; pair this phone again";429->"Receiver rate limit reached; delivery will retry";else->"Receiver rejected delivery (${response.first}); report remains pending"}).commit()}
    return false
   }
   val receipt=runCatching{Json.parseObject(response.second)}.getOrNull()?:return false
   if(receipt.optString("report_id")!=packet.first.optString("report_id")||receipt.optString("sha256")!=PhoneReportExporter.sha256(packet.first))return false
   synchronized(lock){
    if(enabled()&&generation()==packet.second){file.delete();prefs.edit().putString("last_receipt",receipt.toString()).putString("status","Durably received: ${receipt.optString("report_id")} · SHA-256 ${receipt.optString("sha256")}").commit()}
   }
  }
  return true
 }
 private fun request(path:String,body:String,token:String?,owner:Long):Pair<Int,String> {
  val connection=synchronized(lock){
   check(enabled()&&generation()==owner)
   (URL(ORIGIN+path).openConnection() as HttpsURLConnection).apply {
    instanceFollowRedirects=false;connectTimeout=15000;readTimeout=20000;requestMethod="POST";doOutput=true
    setRequestProperty("Content-Type","application/json");token?.let{setRequestProperty("Authorization","Bearer $it")}
    setFixedLengthStreamingMode(body.toByteArray(Charsets.UTF_8).size);active=this
   }
  }
  try{
   check(enabled()&&generation()==owner)
   connection.outputStream.use{it.write(body.toByteArray(Charsets.UTF_8))}
   val status=connection.responseCode;val stream=if(status in 200..299)connection.inputStream else connection.errorStream
   val raw=stream?.use {
    val buffer=ByteArray(4097);var size=0
    while(size<buffer.size){val got=it.read(buffer,size,buffer.size-size);if(got<0)break;size+=got}
    require(size<=4096);String(buffer,0,size,Charsets.UTF_8)
   }.orEmpty()
   return status to raw
  }finally{connection.disconnect();synchronized(lock){if(active===connection)active=null}}
 }
 companion object {
  const val ORIGIN="https://reports.ryzod.com"
  private const val KEY="phone-report-upload-credential-v1"
  private const val TAG="phone-diagnostic-upload"
  private const val FLAGS=Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
  private val lock=Any()
  @Volatile private var active:HttpsURLConnection?=null
  private fun b64(bytes:ByteArray)=Base64.encodeToString(bytes,FLAGS)
 }
}

class PhoneUploadWorker(context:Context,params:WorkerParameters):Worker(context,params){
 override fun doWork():Result=runCatching{if(PhoneSharing(applicationContext).drain(this))Result.success() else Result.retry()}.getOrElse{Result.retry()}
}
