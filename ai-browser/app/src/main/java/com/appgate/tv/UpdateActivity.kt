package com.appgate.tv

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.security.MessageDigest
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

class UpdateActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var downloadButton: Button
    private val handler = Handler(Looper.getMainLooper())
    private var downloadId: Long = -1L
    private var downloadedUri: Uri? = null
    private var installerOpened = false
    private var latestVersionCode: Long? = null
    private var latestVersionName: String? = null
    private var autoDownloadStarted = false

    private val pollDownload = object : Runnable {
        override fun run() {
            if (downloadId < 0L || downloadedUri != null) return
            checkDownloadStatus()
            if (downloadedUri == null && downloadId >= 0L) handler.postDelayed(this, 1000L)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) != downloadId) return
            checkDownloadStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Update AI Browser"
        val currentInfo = runCatching { packageManager.getPackageInfo(packageName, 0) }.getOrNull()
        val currentVersion = currentInfo?.versionName.orEmpty().ifBlank { "unknown" }
        val currentCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) currentInfo?.longVersionCode ?: 0L else @Suppress("DEPRECATION") (currentInfo?.versionCode?.toLong() ?: 0L)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 28)
            setBackgroundColor(Color.rgb(12, 18, 28))
        }
        root.addView(TextView(this).apply {
            text = "AI Browser Updater"
            textSize = 28f
            setTextColor(Color.WHITE)
        })
        status = TextView(this).apply {
            text = "Current version: $currentVersion ($currentCode)\n\nChecking whether a newer AI Browser build is available…"
            textSize = 15f
            setTextColor(Color.rgb(185, 205, 225))
            setPadding(0, 12, 0, 18)
        }
        root.addView(status)
        progress = ProgressBar(this).apply { visibility = View.GONE }
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        downloadButton = Button(this).apply {
            text = "CHECKING FOR UPDATE…"
            textSize = 17f
            isEnabled = false
            setOnClickListener {
                val latest = latestVersionCode
                if (latest != null && UpdateVersionPolicy.isUpdateAvailable(currentVersionCode(), latest)) downloadLatest()
                else checkLatestVersion()
            }
        }
        root.addView(downloadButton)
        root.addView(Button(this).apply {
            text = "BACK WITHOUT UPDATING"
            setOnClickListener { finish() }
        })
        root.addView(TextView(this).apply {
            text = "If a downloaded APK has a different signing identity, AI Browser stops before opening Android's installer instead of ending with the vague 'App not updated' message. Do not uninstall this app until your Site Brain backup is complete."
            textSize = 12f
            setTextColor(Color.rgb(150, 175, 200))
            setPadding(0, 18, 0, 0)
        })
        setContentView(root)

        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED) else registerReceiver(receiver, filter)
        checkLatestVersion()
    }

    private fun currentVersionCode(): Long {
        val info = runCatching { packageManager.getPackageInfo(packageName, 0) }.getOrNull() ?: return 0L
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    private fun checkLatestVersion() {
        downloadButton.isEnabled = false
        downloadButton.text = "CHECKING FOR UPDATE…"
        progress.visibility = View.VISIBLE
        val currentCode = currentVersionCode()
        Thread {
            val result = runCatching {
                val connection = URL(LATEST_VERSION_URL).openConnection() as HttpURLConnection
                connection.connectTimeout = 8_000
                connection.readTimeout = 8_000
                connection.useCaches = false
                connection.setRequestProperty("Accept", "application/json")
                try {
                    if (connection.responseCode !in 200..299) error("HTTP " + connection.responseCode)
                    val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                    json.getLong("versionCode") to json.optString("versionName", "latest")
                } finally {
                    connection.disconnect()
                }
            }
            runOnUiThread {
                progress.visibility = View.GONE
                result.onSuccess { pair ->
                    val code = pair.first
                    val name = pair.second
                    latestVersionCode = code
                    latestVersionName = name
                    if (UpdateVersionPolicy.isUpdateAvailable(currentCode, code)) {
                        status.text = "Update available: $name ($code)\nInstalled: ${packageVersionName()} ($currentCode)\n\nTap Update. Your Site Brain knowledge and logs stay in place."
                        downloadButton.text = "UPDATE TO $name"
                        downloadButton.isEnabled = true
                        if (intent.getBooleanExtra(EXTRA_AUTO_DOWNLOAD, false) && !autoDownloadStarted) {
                            autoDownloadStarted = true
                            downloadLatest()
                        }
                    } else {
                        status.text = "You are on the latest version.\n\nInstalled: ${packageVersionName()} ($currentCode)\nLatest published: $name ($code)"
                        downloadButton.text = "YOU ARE UP TO DATE"
                        downloadButton.isEnabled = false
                    }
                }.onFailure {
                    status.text = "Could not check the latest version right now. No update was started.\n\nInstalled: ${packageVersionName()} ($currentCode)\nTap below to try the check again."
                    downloadButton.text = "CHECK AGAIN"
                    downloadButton.isEnabled = true
                }
            }
        }.start()
    }

    private fun packageVersionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
    }.getOrDefault("unknown")

    private fun downloadLatest() {
        installerOpened = false
        val target = updateFile()
        runCatching { if (target.exists()) target.delete() }
        val request = DownloadManager.Request(Uri.parse(LATEST_APK_URL))
            .setTitle("AI Browser update")
            .setDescription("Downloading latest Site Brain engine")
            .setMimeType(APK_MIME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, UPDATE_FILE)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
        val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        downloadId = dm.enqueue(request)
        downloadButton.isEnabled = false
        progress.visibility = View.VISIBLE
        status.text = "Downloading the latest approved AI Browser build…\nSite Brain knowledge remains untouched while the engine downloads."
        handler.removeCallbacks(pollDownload)
        handler.post(pollDownload)
    }

    private fun checkDownloadStatus() {
        if (downloadId < 0L) return
        val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        val cursor = dm.query(DownloadManager.Query().setFilterById(downloadId)) ?: return
        cursor.use {
            if (!it.moveToFirst()) return
            val statusIndex = it.getColumnIndex(DownloadManager.COLUMN_STATUS)
            if (statusIndex < 0) return
            when (it.getInt(statusIndex)) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    handler.removeCallbacks(pollDownload)
                    progress.visibility = View.GONE
                    downloadedUri = dm.getUriForDownloadedFile(downloadId)
                    downloadId = -1L
                    if (downloadedUri == null || !updateFile().exists()) {
                        failDownload("Android reported the download complete, but the APK file could not be opened.")
                        return
                    }
                    status.text = "Download complete. Checking package and signing identity…"
                    verifyAndInstall()
                }
                DownloadManager.STATUS_FAILED -> {
                    val reasonIndex = it.getColumnIndex(DownloadManager.COLUMN_REASON)
                    val reason = if (reasonIndex >= 0) it.getInt(reasonIndex) else -1
                    failDownload("Update download failed (Android reason $reason). Your Site Brain data was not changed.")
                }
                else -> {
                    progress.visibility = View.VISIBLE
                    status.text = "Downloading the latest approved AI Browser build…\nYou can leave this screen open; completion is checked directly instead of relying only on the notification."
                }
            }
        }
    }

    private fun failDownload(message: String) {
        handler.removeCallbacks(pollDownload)
        progress.visibility = View.GONE
        downloadId = -1L
        downloadedUri = null
        downloadButton.isEnabled = true
        status.text = message
    }

    private fun verifyAndInstall() {
        val file = updateFile()
        val candidate = archivePackageInfo(file)
        if (candidate == null) {
            failDownload("The downloaded file is not a readable Android APK. It was not opened.")
            return
        }
        if (candidate.packageName != packageName) {
            failDownload("The downloaded APK belongs to a different app (${candidate.packageName}). It was blocked before installation.")
            return
        }
        val installed = installedPackageInfo()
        val installedSigner = installed?.let(::signerSha256).orEmpty()
        val candidateSigner = signerSha256(candidate)
        if (!UpdateCompatibility.sameSigner(installedSigner, candidateSigner)) {
            progress.visibility = View.GONE
            downloadButton.isEnabled = true
            status.text = "Update blocked safely: this installed copy and the downloaded APK have different Android signing identities.\n\nDo NOT uninstall yet. Back up the Site Brain first, then perform the one-time stable-signing migration. After that transition, future Update-button installs will use one permanent signing identity."
            return
        }
        status.text = "Update verified. Signing identity matches. Opening Android's installer…\nYour Site Brain data remains in place during this in-place update."
        installDownloadedUpdate()
    }

    private fun installDownloadedUpdate() {
        val uri = downloadedUri ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            status.text = "Android needs one-time permission for AI Browser to install its own updates. Enable 'Allow from this source', then return here."
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        if (installerOpened) return
        installerOpened = true
        startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    @Suppress("DEPRECATION")
    private fun installedPackageInfo(): PackageInfo? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
        }
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun archivePackageInfo(file: File): PackageInfo? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNATURES)
        }
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun signerSha256(info: PackageInfo): String {
        val signature = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners?.firstOrNull()
        } else {
            info.signatures?.firstOrNull()
        } ?: return ""
        val digest = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun updateFile(): File = File(
        getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
        UPDATE_FILE
    )

    override fun onResume() {
        super.onResume()
        when {
            downloadId >= 0L -> {
                handler.removeCallbacks(pollDownload)
                handler.post(pollDownload)
            }
            downloadedUri != null && !installerOpened &&
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()) -> verifyAndInstall()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(receiver) }
        super.onDestroy()
    }

    companion object {
        private const val APK_MIME = "application/vnd.android.package-archive"
        private const val UPDATE_FILE = "AI-Browser-latest.apk"
        const val EXTRA_AUTO_DOWNLOAD = "auto_download_update"
        const val LATEST_APK_URL = "https://github.com/lukeypue/AI-Browser/releases/download/ai-browser-latest/AI-Browser-latest.apk"
        const val LATEST_VERSION_URL = "https://github.com/lukeypue/AI-Browser/releases/download/ai-browser-latest/latest-version.json"
    }
}
