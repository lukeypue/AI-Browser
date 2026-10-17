package com.appgate.tv.store

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.appgate.brain.json.JsonObject
import com.appgate.brain.perception.Redactor
import java.io.File
import com.appgate.brain.diagnostics.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A bounded, privacy-safe event log (NDJSON) the user can export to Downloads or share.
 * Lines carry only the brain's own semantic coordinates: host, page type, role, facet key,
 * verification status, timings. No listing text, no names, no query values, no URLs with
 * values, no keys. Automatic sanitized sharing requires explicit consent in Phone test diagnostics.
 */
class DiagnosticsLog(private val context: Context) {
    private val writer = SerialDiagnosticWriter()
    private val liveSnapshotQueued = AtomicBoolean(false)
    private val phonePrefs = context.getSharedPreferences("phone_test_context", Context.MODE_PRIVATE)
    private var trimGeneration = phonePrefs.getInt("trim_generation", 0)
    @Volatile private var writeFailed = false
    @Volatile private var phoneTestId = phonePrefs.getString("id", "").orEmpty()
    private fun boot() = android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1)
    private val file = File(context.filesDir, FILE_NAME)
    @Volatile private var lines = 0

    init { lines = runCatching { if (file.exists()) file.bufferedReader().use { it.lineSequence().count() } else 0 }.getOrDefault(0) }

    fun event(kind: String, host: String = "", detail: String = "", extra: JsonObject? = null) {
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrDefault("unknown")
        val line = JsonObject().put("t", System.currentTimeMillis()).put("elapsed", android.os.SystemClock.elapsedRealtime()).put("boot", boot()).put("test_id", phoneTestId).put("build", version).put("kind", kind).put("host", host.take(80)).put("detail", Redactor.snippet(detail, 240))
        if (extra != null) line.put("x", extra)
        writer.append {
            runCatching {
                file.appendText(line.toString() + "\n", Charsets.UTF_8)
                lines++
                if (lines > MAX_LINES) trim()
            }.onFailure { writeFailed = true }
        }
    }

    private fun trim() {
        trimGeneration++
        phonePrefs.edit().putInt("trim_generation", trimGeneration).commit()
        val all = file.readLines(Charsets.UTF_8)
        val keep = all.takeLast(MAX_LINES * 3 / 4)
        val temp = File(context.filesDir, "$FILE_NAME.tmp")
        temp.writeText(keep.joinToString("\n") + "\n", Charsets.UTF_8)
        if (!temp.renameTo(file)) { file.writeText(temp.readText()); temp.delete() }
        lines = keep.size
    }

    fun fileOrNull(): File? = file.takeIf { it.exists() && it.length() > 0 }

    fun saveToDownloads(): Result<String> = runCatching {
        if (android.os.Build.VERSION.SDK_INT < 29) error("Saving to Downloads needs Android 10+; use Share instead.")
        val src = fileOrNull() ?: error("No diagnostics have been recorded yet.")
        val name = "ai-browser-diagnostics-${System.currentTimeMillis() / 1000}.ndjson"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/x-ndjson")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: error("Could not create the file in Downloads.")
        resolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } } ?: error("Could not open Downloads file")
        values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        name
    }

    fun shareIntent(): Intent? {
        val src = fileOrNull() ?: return null
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", src)
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/x-ndjson"
            putExtra(Intent.EXTRA_SUBJECT, "AI Browser diagnostics")
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun clear() { writer.append { runCatching { file.delete() }; lines = 0; trimGeneration++; phonePrefs.edit().putInt("trim_generation", trimGeneration).commit() } }

    fun beginPhoneTest(startedElapsed: Long, startedWall: Long) {
        val sharing = PhoneSharing(context)
        sharing.beginLiveTest()
        val id = UUID.randomUUID().toString()
        val owner = sharing.generation()
        val startedBoot = boot()
        val startedRevision = com.appgate.tv.BuildConfig.DIAGNOSTICS_REVISION
        val startedVersion = context.packageManager.getPackageInfo(context.packageName, 0).versionCode
        phoneTestId = id
        com.appgate.tv.serverlab.ServerLabRuntime.beginTest(id)
        writer.append {
            phonePrefs.edit().putString("id", id).putLong("elapsed", startedElapsed).putLong("wall", startedWall)
                .putInt("boot", startedBoot).putInt("test_trim", trimGeneration).putLong("consent_generation", owner).putLong("live_elapsed",0)
                .putString("test_revision", startedRevision).putInt("test_version", startedVersion).commit()
        }
    }

    private fun phoneContext(id:String)=PhoneTestContext(id, phonePrefs.getLong("elapsed",0), phonePrefs.getLong("wall",0), phonePrefs.getInt("test_trim",0), phonePrefs.getInt("boot",-1),
        appRevision=phonePrefs.getString("test_revision",null),versionCode=if(phonePrefs.contains("test_version"))phonePrefs.getInt("test_version",-1) else null)
    private fun phoneEvents()=if(file.exists())file.useLines{lines->lines.mapNotNull{com.appgate.brain.json.Json.parseObjectOrNull(it)}.toList()} else emptyList()
    private fun liveReport(test:PhoneTestContext,events:List<JsonObject>,elapsed:Long,wall:Long,currentBoot:Int,finished:Boolean,interrupted:Boolean=false):JsonObject {
        val evidence=if(interrupted&&currentBoot==test.bootId)events+JsonObject().put("kind","learning_test_interrupted").put("test_id",test.id).put("boot",test.bootId).put("elapsed",elapsed) else events
        return LiveProgressExporter.export(test,evidence,elapsed,wall,trimGeneration,com.appgate.tv.BuildConfig.DIAGNOSTICS_REVISION,
            context.packageManager.getPackageInfo(context.packageName,0).versionCode,currentBoot,finished,minimumElapsedMs=phonePrefs.getLong("live_elapsed",0))
    }
    fun snapshotLiveProgress() {
        val sharing=PhoneSharing(context)
        if(!sharing.liveEnabled()||phoneTestId.isBlank()||!liveSnapshotQueued.compareAndSet(false,true))return
        val expectedId=phoneTestId
        val owner=sharing.generation()
        val liveOwner=sharing.liveGeneration()
        val elapsed=android.os.SystemClock.elapsedRealtime()
        val wall=System.currentTimeMillis()
        val currentBoot=boot()
        // A serial barrier flushes all preceding diagnostic writes before the read.
        writer.snapshot {
            try {
                check(!writeFailed)
                if(phonePrefs.getString("id",null)!=expectedId||phonePrefs.getLong("consent_generation",-1)!=owner)return@snapshot
                if(!sharing.liveEnabled()||sharing.generation()!=owner||sharing.liveGeneration()!=liveOwner)return@snapshot
                val report=liveReport(phoneContext(expectedId),phoneEvents(),elapsed,wall,currentBoot,false)
                check(phonePrefs.edit().putLong("live_elapsed",report.optLong("elapsed_ms")).commit())
                sharing.enqueueLive(report,owner,liveOwner)
            } catch(_:Exception){sharing.liveSnapshotFailed()}
            finally {liveSnapshotQueued.set(false)}
        }
    }

    fun completePhoneTest(interrupted: Boolean = false) {
        val sharing = PhoneSharing(context)
        val finishedElapsed = android.os.SystemClock.elapsedRealtime()
        val finishedWall = System.currentTimeMillis()
        val finishedBoot = boot()
        val finishedLiveOwner = sharing.liveGeneration()
        phoneTestId = ""
        // The read is enqueued after all preceding event writes, including the end marker.
        writer.snapshot {
            val id = phonePrefs.getString("id", null) ?: return@snapshot
            try {
                check(!writeFailed)
                val owner = phonePrefs.getLong("consent_generation", -1)
                if(sharing.liveEnabled()&&sharing.generation()==owner&&sharing.liveGeneration()==finishedLiveOwner){
                    // A live snapshot failure must never prevent the durable final report.
                    runCatching {
                        val report=liveReport(phoneContext(id),phoneEvents(),finishedElapsed,finishedWall,finishedBoot,true,interrupted)
                        check(phonePrefs.edit().putLong("live_elapsed",report.optLong("elapsed_ms")).commit())
                        sharing.enqueueLive(report,owner,finishedLiveOwner)
                    }.onFailure { sharing.liveSnapshotFailed() }
                }
                if (com.appgate.tv.serverlab.ServerLabRuntime.active) {
                    val strict = PhoneReportExporter.export(phoneContext(id), phoneEvents(), finishedElapsed, finishedWall, trimGeneration, interrupted, com.appgate.tv.BuildConfig.DIAGNOSTICS_REVISION, context.packageManager.getPackageInfo(context.packageName, 0).versionCode, currentBoot = finishedBoot)
                    com.appgate.tv.serverlab.ServerLabRuntime.observe(strict)
                }
                if (sharing.enabled() && sharing.generation() == owner) {
                    val test = PhoneTestContext(id, phonePrefs.getLong("elapsed", 0), phonePrefs.getLong("wall", 0), phonePrefs.getInt("test_trim", 0), phonePrefs.getInt("boot", -1),
                        appRevision = phonePrefs.getString("test_revision", null),
                        versionCode = if (phonePrefs.contains("test_version")) phonePrefs.getInt("test_version", -1) else null)
                    val events = if (file.exists()) file.useLines { lines -> lines.mapNotNull { com.appgate.brain.json.Json.parseObjectOrNull(it) }.toList() } else emptyList()
                    val version = context.packageManager.getPackageInfo(context.packageName, 0).versionCode
                    sharing.enqueue(PhoneReportExporter.export(test, events, finishedElapsed, finishedWall, trimGeneration, interrupted, com.appgate.tv.BuildConfig.DIAGNOSTICS_REVISION, version, currentBoot = finishedBoot), owner)
                }
            } catch (_: Exception) { sharing.snapshotFailed() }
            finally { phonePrefs.edit().remove("id").remove("test_revision").remove("test_version").remove("live_elapsed").commit() }
        }
    }

    companion object {
        const val FILE_NAME = "brain_diagnostics.ndjson"
        const val MAX_LINES = 8_000
    }
}
