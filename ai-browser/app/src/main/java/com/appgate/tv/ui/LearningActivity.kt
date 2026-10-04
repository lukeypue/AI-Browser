package com.appgate.tv.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.appgate.brain.engine.Curriculum
import com.appgate.brain.model.VerifyStatus
import com.appgate.brain.profile.SiteProfiles
import com.appgate.tv.service.BrainService

/**
 * Overnight learning dashboard. Learning runs in the service (screen off is fine); this
 * screen only shows progress per site and offers Start / Stop / open browser.
 */
class LearningActivity : ServiceBoundActivity() {
    private lateinit var status: TextView
    private lateinit var stepsView: TextView
    private lateinit var progress: LinearLayout
    private lateinit var humanButton: android.widget.Button
    private lateinit var usageView: TextView
    private lateinit var strongerTeacher: android.widget.CheckBox
    private var updatingTeacher = false
    private val recent = ArrayDeque<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Overnight Learning"
        val scroll = ScrollView(this).apply { setBackgroundColor(Ui.bg) }
        val column = Ui.column(this)
        column.addView(Ui.text(this, "Overnight Learning", 24f, Ui.text, true))
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrDefault("unknown")
        column.addView(Ui.text(this, "Build $version · Verified learning", 12f, Ui.muted))
        column.addView(Ui.text(this, "The brain practices focused lessons, saves verified procedures, and reuses them on later visits. Each site gets a bounded turn. Sites needing sign-in wait while other sites continue. Training only reads and filters; it does not send messages or change accounts.", 13f, Ui.muted).apply { setPadding(0, 6, 0, 10) })
        column.addView(Ui.row(this,
            Ui.button(this, "START") { service?.startLearning() },
            Ui.button(this, "PAUSE") { service?.pause() },
            Ui.button(this, "STOP") { service?.stopLearning() }
        ))
        column.addView(android.widget.CheckBox(this).apply {
            text = "Allow AI help: up to 30 requests/hour and 120/day. Off = local only."
            setTextColor(Ui.text)
            isChecked = com.appgate.tv.store.PlannerKeyStore.teacherEnabled(this@LearningActivity)
            setOnCheckedChangeListener { _, checked ->
                com.appgate.tv.store.PlannerKeyStore.setTeacherEnabled(this@LearningActivity, checked)
                refreshProgress()
            }
        })
        strongerTeacher = android.widget.CheckBox(this).apply {
            text = "Use stronger OpenAI help for 24 hours (GPT-5.4). Higher cost: about 3.3× mini token rates, plus reasoning tokens. Same 6/hour and 24/day limits; then your usual model resumes."
            setTextColor(Ui.text)
            isEnabled = com.appgate.tv.store.PlannerKeyStore.strongerTeacherEligible(this@LearningActivity)
            isChecked = com.appgate.tv.store.PlannerKeyStore.strongerTeacherActive(this@LearningActivity)
            setOnCheckedChangeListener { _, checked ->
                if (!updatingTeacher) {
                    com.appgate.tv.store.PlannerKeyStore.setStrongerTeacher(this@LearningActivity, checked)
                    refreshProgress()
                }
            }
        }
        column.addView(strongerTeacher)
        usageView = Ui.text(this, "", 12f, Ui.muted)
        column.addView(usageView)
        column.addView(android.widget.CheckBox(this).apply {
            text = "Include sites that need my account (Facebook Marketplace). Slower pacing; use a test account if you can."
            setTextColor(Ui.text)
            isChecked = getSharedPreferences("settings", MODE_PRIVATE).getBoolean("learn_account_sites", false)
            setOnCheckedChangeListener { _, checked -> getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("learn_account_sites", checked).apply() }
        })
        humanButton = Ui.button(this, "A site needs you — open the browser") { startActivity(Intent(this, BrowserActivity::class.java)) }.apply { visibility = View.GONE }
        column.addView(humanButton)
        column.addView(Ui.button(this, "WATCH THE BROWSER") { startActivity(Intent(this, BrowserActivity::class.java)) })
        status = Ui.text(this, "", 14f, Ui.accent).apply { setPadding(0, 10, 0, 4) }
        column.addView(status)
        stepsView = Ui.text(this, "", 11f, Ui.muted).apply { setPadding(0, 0, 0, 10) }
        column.addView(stepsView)
        column.addView(Ui.text(this, "Curriculum progress", 18f, Ui.text, true).apply { setPadding(0, 10, 0, 6) })
        progress = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(progress)
        column.addView(Ui.button(this, "SAVE DIAGNOSTICS TO DOWNLOADS") {
            service?.diagnostics?.saveToDownloads()?.onSuccess { Toast.makeText(this, "Saved $it", Toast.LENGTH_LONG).show() }?.onFailure { Toast.makeText(this, it.message, Toast.LENGTH_LONG).show() }
        })
        scroll.addView(column)
        setContentView(scroll)
    }

    override fun onServiceReady(service: BrainService) { refreshProgress() }

    override fun onState(state: BrainService.State) {
        status.text = state.status
        humanButton.visibility = if (state.mode == BrainService.Mode.NEED_HUMAN) View.VISIBLE else View.GONE
        humanButton.text = "${state.host.ifBlank { "A site" }} needs you — open the browser"
        refreshProgress()
    }

    override fun onStep(description: String, status: VerifyStatus?) {
        recent.addLast("${when (status) { VerifyStatus.VERIFIED -> "✓"; VerifyStatus.FAILED -> "✗"; else -> "…" }} $description")
        while (recent.size > 8) recent.removeFirst()
        stepsView.text = recent.joinToString("\n")
    }

    private fun refreshProgress() {
        if (::strongerTeacher.isInitialized) {
            updatingTeacher = true
            strongerTeacher.isEnabled = com.appgate.tv.store.PlannerKeyStore.strongerTeacherEligible(this)
            strongerTeacher.isChecked = com.appgate.tv.store.PlannerKeyStore.strongerTeacherActive(this)
            updatingTeacher = false
        }
        val s = service ?: return
        val usage = s.memory.teacherBudget.snapshot()
        val enabled = com.appgate.tv.store.PlannerKeyStore.teacherEnabled(this)
        usageView.text = buildString {
            append(if (enabled) "AI requests: ${usage.requests24h}/24 in the last 24 hours · ${usage.requestsHour}/6 in the last hour" else "Local-only mode · no new AI API calls")
            append("\nReported tokens: ${usage.inputTokens} in / ${usage.outputTokens} out (${usage.reportedRequests} responses)")
            if (com.appgate.tv.store.PlannerKeyStore.strongerTeacherActive(this@LearningActivity)) {
                val minutes = ((com.appgate.tv.store.PlannerKeyStore.strongerTeacherUntil(this@LearningActivity) - System.currentTimeMillis()) / 60_000L).coerceAtLeast(1)
                append("\nStronger teacher: $minutes minutes remaining")
            }
            if (usage.lastModel.isNotBlank()) append("\nLast model: ${usage.lastModel}")
            if (enabled && !usage.allowed) append("\n${usage.reason}")
        }
        progress.removeAllViews()
        SiteProfiles.training.forEach { profile ->
            val site = s.memory.site(profile.hosts.first())
            Curriculum.ensure(site)
            val c = Ui.card(this)
            c.addView(Ui.text(this, profile.name, 15f, Ui.text, true))
            c.addView(Ui.text(this, "${Curriculum.progress(site)} · ${site.verifiedActions} verified actions · ${site.bindings.size} controls remembered", 12f, Ui.muted))
            if (site.lastLearningStatus.isNotBlank()) c.addView(Ui.text(this, site.lastLearningStatus, 12f, Ui.muted))
            if (site.learningNeedsHuman) c.addView(Ui.button(this, "REVIEW ${profile.name.uppercase()}") {
                service?.pause()
                startActivity(Intent(this, BrowserActivity::class.java).putExtra("url", profile.startUrl))
            })
            c.addView(Ui.text(this, site.curriculum.joinToString("\n") {
                (if (it.done) "✓ " else "○ ") + it.description + if (!it.done && it.opportunity == "ABSENT") " — waiting for this control" else ""
            }, 12f, if (Curriculum.allLessonsComplete(site)) Ui.good else Ui.muted))
            progress.addView(c, Ui.cardParams())
        }
    }
}
