package com.appgate.tv

import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.appgate.brain.profile.SiteProfiles
import com.appgate.tv.browser.BrainRuntime
import com.appgate.tv.service.BrainService
import com.appgate.tv.store.PlannerKeyStore
import com.appgate.tv.ui.BrowserActivity
import com.appgate.tv.ui.LearningActivity
import com.appgate.tv.ui.SearchActivity
import com.appgate.tv.ui.Ui
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {
    private lateinit var queryBox: EditText
    private lateinit var sourceSummary: TextView
    private var automaticUpdateCheckStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val versionName = runCatching { packageManager.getPackageInfo(packageName, 0).versionName.orEmpty() }.getOrDefault("unknown")
        title = "AI Browser $versionName"
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 41)
        }
        BrainService.start(this)

        val root = ScrollView(this).apply { setBackgroundColor(Ui.bg) }
        val column = Ui.column(this, 28)

        column.addView(Ui.text(this, "AI Browser", 30f, Ui.text, true))
        column.addView(Ui.text(this, "Site Brain $versionName — one browser engine, one sign-in, a brain that learns how sites work and finds deals for you.", 15f, Ui.muted).apply { setPadding(0, 8, 0, 16) })

        column.addView(Ui.text(this, "DEEP SEARCH", 18f, Ui.text, true))
        queryBox = EditText(this).apply {
            hint = "Ford Expedition under 8k under 150k miles with a 3.73 axle"
            setTextColor(Ui.text)
            setHintTextColor(Ui.muted)
            setSingleLine(false)
            minLines = 2
            setPadding(20, 18, 20, 18)
            setBackgroundColor(Ui.card)
        }
        column.addView(queryBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        column.addView(Ui.button(this, "SEARCH THE DEALS") {
            startActivity(Intent(this, SearchActivity::class.java).putExtra("query", queryBox.text.toString().trim()))
        })
        sourceSummary = Ui.text(this, "", 13f, Ui.muted).apply { setPadding(0, 6, 0, 4) }
        column.addView(sourceSummary)
        column.addView(Ui.row(this, Ui.button(this, "My sources") { showSources() }, Ui.button(this, "How it works") { showInfo() }))
        refreshSummary()

        column.addView(Ui.text(this, "CONNECTED SITES", 18f, Ui.text, true).apply { setPadding(0, 22, 0, 4) })
        column.addView(Ui.text(this, "Sign in yourself, once. The same browser session is used by the brain afterwards — it never sees your password, never solves a CAPTCHA or 2-step code, and never copies cookies. Google / Facebook / Apple sign-in popups work here.", 12f, Ui.muted).apply { setPadding(0, 0, 0, 8) })
        SiteProfiles.training.distinctBy { it.loginUrl ?: it.startUrl }.forEach { p ->
            val label = if (p.key.startsWith("ksl")) "Connect KSL" else "Connect ${p.name}"
            column.addView(Ui.button(this, label) { openBrowser(p.loginUrl ?: p.startUrl) })
        }
        column.addView(Ui.button(this, "Open the browser") { openBrowser("https://classifieds.ksl.com/") })
        column.addView(Ui.button(this, "Forget all sign-ins on this phone") { confirmForget() })

        column.addView(Ui.text(this, "LEARNING", 18f, Ui.text, true).apply { setPadding(0, 22, 0, 4) })
        column.addView(Ui.button(this, "OVERNIGHT LEARNING") { startActivity(Intent(this, LearningActivity::class.java)) })
        column.addView(Ui.text(this, "Runs in the background with the screen off. The brain only reads, searches and filters; messaging, buying, posting, saving and account changes are blocked twice over (in the engine and at the network layer).", 12f, Ui.muted).apply { setPadding(0, 2, 0, 8) })

        column.addView(Ui.text(this, "AI PLANNER", 18f, Ui.text, true).apply { setPadding(0, 22, 0, 4) })
        val keyButton = Button(this)
        keyButton.text = if (!PlannerKeyStore.teacherEnabled(this)) "AI HELP: LOCAL ONLY" else if (PlannerKeyStore.isConfigured(this)) "AI PLANNER KEY: SET" else "SET AI PLANNER KEY"
        keyButton.setOnClickListener { showPlannerDialog(keyButton) }
        column.addView(keyButton)
        column.addView(Ui.text(this, "Optional. Used only when the deterministic brain is stuck or a description needs reading for a rare detail. The key is encrypted with Android Keystore and stays on this phone. Sign-in and verification pages are never sent to the model.", 12f, Ui.muted).apply { setPadding(0, 2, 0, 8) })

        column.addView(Ui.text(this, "PRIVACY", 18f, Ui.text, true).apply { setPadding(0, 22, 0, 4) })
        column.addView(Ui.text(this, privacyStatement(), 12f, Ui.muted))

        column.addView(Ui.text(this, "UPDATES & DIAGNOSTICS", 18f, Ui.text, true).apply { setPadding(0, 22, 0, 4) })
        column.addView(Ui.button(this, "UPDATE AI BROWSER") { startActivity(Intent(this, UpdateActivity::class.java)) })
        column.addView(CheckBox(this).apply {
            text = "Automatically download verified AI Browser updates"
            setTextColor(Ui.text)
            isChecked = getSharedPreferences("settings", MODE_PRIVATE).getBoolean("auto_updates", true)
            setOnCheckedChangeListener { _, checked ->
                getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("auto_updates", checked).apply()
                if (checked) checkForAutomaticUpdate()
            }
        })
        column.addView(Ui.button(this, "SAVE DIAGNOSTICS TO DOWNLOADS") {
            com.appgate.tv.store.DiagnosticsLog(this).saveToDownloads()
                .onSuccess { Toast.makeText(this, "Saved $it to Downloads.", Toast.LENGTH_LONG).show() }
                .onFailure { Toast.makeText(this, it.message ?: "Could not save.", Toast.LENGTH_LONG).show() }
        })
        column.addView(Ui.text(this, "Diagnostics contain only the brain's own semantic events (page types, control roles, verified/failed, timings). No listing text, no searches, no account data.", 12f, Ui.muted).apply { setPadding(0, 2, 0, 8) })

        column.addView(Ui.text(this, "What is new in $versionName", 18f, Ui.text, true).apply { setPadding(0, 20, 0, 8) })
        column.addView(Ui.text(this, "• Learning retries after one minute and remembers recently failed approaches.\n• Working controls stay remembered through pagination and popup changes.\n• Better recognition of clickable listing cards and popup close buttons.\n• An AI guess about sign-in no longer creates a permanent hold. Start rechecks saved review requests against the current page.\n• Empty or unreadable results are reported honestly. Diagnostics now explain failed actions and count newly learned lessons.", 14f, Ui.muted))

        root.addView(column)
        setContentView(root)

        if (getSharedPreferences("settings", MODE_PRIVATE).getBoolean("auto_updates", true)) checkForAutomaticUpdate()
    }

    private fun openBrowser(url: String) {
        startActivity(Intent(this, BrowserActivity::class.java).putExtra("url", url))
    }

    private fun confirmForget() {
        AlertDialog.Builder(this).setTitle("Forget all sign-ins?")
            .setMessage("This clears every cookie, site storage and cache the browser engine keeps on this phone. You will need to sign in to sites again. Learned site knowledge (which contains no personal data) is kept.")
            .setPositiveButton("Forget") { _, _ ->
                BrainRuntime.clearAllSiteData(this).accept({ Toast.makeText(this, "All sign-ins forgotten.", Toast.LENGTH_LONG).show() }, { Toast.makeText(this, "Could not clear: ${it?.message}", Toast.LENGTH_LONG).show() })
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun showPlannerDialog(button: Button) {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 10, 40, 0) }
        val providers = com.appgate.brain.planner.PlannerProvider.values()
        val oldProvider = PlannerKeyStore.provider(this)
        val oldEndpoint = PlannerKeyStore.endpoint(this)
        val provider = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("OpenAI", "Anthropic / Claude", "Compatible API", "Groq", "Google / Gemini"))
            setSelection(providers.indexOf(oldProvider))
        }
        val enabled = CheckBox(this).apply { text = "Allow AI help when local procedures need repair"; isChecked = PlannerKeyStore.teacherEnabled(this@MainActivity) }
        val input = EditText(this).apply { hint = "Key for the selected provider (blank keeps current key)"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; setSingleLine(true) }
        val model = EditText(this).apply { hint = "Model (blank uses economical default)"; setText(PlannerKeyStore.model(this@MainActivity)); setSingleLine(true) }
        val endpoint = EditText(this).apply { hint = "Custom HTTPS operation URL (optional)"; setText(oldEndpoint); setSingleLine(true) }
        container.addView(provider); container.addView(enabled); container.addView(input); container.addView(model); container.addView(endpoint)
        val builder = AlertDialog.Builder(this).setTitle("AI planner")
            .setMessage("Local skills run first. AI help is limited to 30 requests per hour and 120 per 24 hours, shared across searches and learning. Turn help off for zero API calls. Keys are stored encrypted. Each provider has its own billing and data terms.")
            .setView(container)
            .setPositiveButton("SAVE") save@ { _, _ ->
                val key = input.text?.toString().orEmpty().trim()
                val selected = providers[provider.selectedItemPosition]
                val url = endpoint.text.toString().trim()
                if (url.isNotBlank() && runCatching { java.net.URI(url).let { it.scheme == "https" && !it.host.isNullOrBlank() && it.userInfo == null && it.query == null && it.fragment == null } }.getOrDefault(false).not()) {
                    Toast.makeText(this, "Use a complete HTTPS API URL without credentials or query parameters.", Toast.LENGTH_LONG).show(); return@save
                }
                if (key.isBlank() && PlannerKeyStore.isConfigured(this) && (selected != oldProvider || url != oldEndpoint)) {
                    Toast.makeText(this, "Paste the key for the new provider or endpoint.", Toast.LENGTH_LONG).show(); return@save
                }
                if (key.isNotBlank()) {
                    if (runCatching { PlannerKeyStore.save(this, key) }.isFailure) {
                        Toast.makeText(this, "Could not save the key.", Toast.LENGTH_LONG).show(); return@save
                    }
                }
                PlannerKeyStore.saveSettings(this, selected, model.text.toString(), url)
                PlannerKeyStore.setTeacherEnabled(this, enabled.isChecked)
                button.text = if (!enabled.isChecked) "AI HELP: LOCAL ONLY" else if (PlannerKeyStore.isConfigured(this)) "AI PLANNER KEY: SET" else "SET AI PLANNER KEY"
                Toast.makeText(this, if (enabled.isChecked) "AI settings saved." else "Local-only mode enabled.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("CANCEL", null)
        if (PlannerKeyStore.isConfigured(this)) builder.setNeutralButton("CLEAR KEY") { _, _ -> PlannerKeyStore.clear(this); button.text = "SET AI PLANNER KEY" }
        builder.show()
    }

    private fun showSources() {
        val prefs = getSharedPreferences("sources", MODE_PRIVATE)
        val all = SiteProfiles.all
        val labels = all.map { it.name }.toTypedArray()
        val checked = BooleanArray(all.size) { i -> prefs.getBoolean("always_${all[i].key}", false) }
        AlertDialog.Builder(this).setTitle("Always search these sources")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("Save") { _, _ ->
                val e = prefs.edit(); all.forEachIndexed { i, s -> e.putBoolean("always_${s.key}", checked[i]) }; e.apply(); refreshSummary()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun refreshSummary() {
        val prefs = getSharedPreferences("sources", MODE_PRIVATE)
        val always = SiteProfiles.all.filter { prefs.getBoolean("always_${it.key}", false) }.map { it.name }
        sourceSummary.text = if (always.isEmpty()) "Sources are chosen from what you ask for (vehicles → KSL Cars, OfferUp, Facebook Marketplace, …)." else "Always search: ${always.joinToString()}"
    }

    private fun showInfo() {
        AlertDialog.Builder(this).setTitle("How it works")
            .setMessage("Deep Search turns your sentence into typed requirements (make, model, max price, max mileage, rare details like a 3.73 axle). On each site the brain searches, applies the filters it can, reads listing cards, opens the ones that might match and reads their descriptions for the rare details. You get verified matches with the exact quote, possible matches where the detail simply was not mentioned, and near misses.\n\nWhen a site needs a sign-in or a human verification, the brain pauses and asks you; you finish it in the browser screen and tap Continue. It never types your password and never solves a CAPTCHA.\n\nMessaging a seller: the brain opens the listing and fills the message, then shows it to you. Nothing is sent until you tap SEND IT — and that permission is single-use.")
            .setPositiveButton("Got it", null).show()
    }

    private fun privacyStatement(): String =
        "• Nothing is sold, uploaded or collected. There is no server of ours, no analytics, no ads.\n" +
        "• Sign-ins live only inside the browser engine's profile on this phone (like any browser). The app has no cookie or password access. 'Forget all sign-ins' wipes them.\n" +
        "• The brain stores structure, not content: page types, which control plays which role, success statistics. No listing text, no seller names, no searches.\n" +
        "• If you add an AI planner key, only a short redacted description of the page structure is sent when the brain is stuck — never sign-in or verification pages, never personal data."

    private fun currentVersionCode(): Long {
        val info = runCatching { packageManager.getPackageInfo(packageName, 0) }.getOrNull() ?: return 0L
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    private fun checkForAutomaticUpdate() {
        if (automaticUpdateCheckStarted) return
        automaticUpdateCheckStarted = true
        val currentCode = currentVersionCode()
        Thread {
            val latestCode = runCatching {
                val connection = URL(UpdateActivity.LATEST_VERSION_URL).openConnection() as HttpURLConnection
                connection.connectTimeout = 8_000
                connection.readTimeout = 8_000
                connection.useCaches = false
                try {
                    if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
                    JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).getLong("versionCode")
                } finally { connection.disconnect() }
            }.getOrNull()
            if (latestCode != null && UpdateVersionPolicy.isUpdateAvailable(currentCode, latestCode)) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) startActivity(Intent(this, UpdateActivity::class.java).putExtra(UpdateActivity.EXTRA_AUTO_DOWNLOAD, true))
                }
            }
        }.start()
    }
}
