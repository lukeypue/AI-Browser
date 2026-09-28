package com.appgate.tv.store

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.appgate.brain.json.JsonObject
import com.appgate.brain.perception.Redactor
import java.io.File
import java.util.concurrent.Executors

/**
 * A bounded, privacy-safe event log (NDJSON) the user can export to Downloads or share.
 * Lines carry only the brain's own semantic coordinates: host, page type, role, facet key,
 * verification status, timings. No listing text, no names, no query values, no URLs with
 * values, no keys. Nothing is uploaded automatically — sharing is always a button the user taps.
 */
class DiagnosticsLog(private val context: Context) {
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "brain-diag") }
    private val file = File(context.filesDir, FILE_NAME)
    @Volatile private var lines = 0

    init { lines = runCatching { if (file.exists()) file.bufferedReader().use { it.lineSequence().count() } else 0 }.getOrDefault(0) }

    fun event(kind: String, host: String = "", detail: String = "", extra: JsonObject? = null) {
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrDefault("unknown")
        val line = JsonObject().put("t", System.currentTimeMillis()).put("build", version).put("kind", kind).put("host", host.take(80)).put("detail", Redactor.snippet(detail, 240))
        if (extra != null) line.put("x", extra)
        writer.execute {
            runCatching {
                file.appendText(line.toString() + "\n", Charsets.UTF_8)
                lines++
                if (lines > MAX_LINES) trim()
            }
        }
    }

    private fun trim() {
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

    fun clear() { writer.execute { runCatching { file.delete() }; lines = 0 } }

    companion object {
        const val FILE_NAME = "brain_diagnostics.ndjson"
        const val MAX_LINES = 8_000
    }
}
