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
import com.appgate.brain.memory.TeacherBudget
import com.appgate.brain.profile.SiteProfiles
import com.appgate.tv.service.BrainService

/**
 * Overnight learning dashboard. Learning runs in the service (screen off is fine); this
 * screen only shows progress per site and offers Start / Stop / open browser.
 */
class LearningActivity : ServiceBoundActivity() {
    private lateinit var status: TextView
    private lateinit var guideView: TextView
    private lateinit var teachStepButton: android.widget.Button
    private lateinit var stepsView: TextView
    private lateinit var progress: LinearLayout
    private lateinit var humanButton: android.widget.Button
    private lateinit var usageView: TextView
    private lateinit var allowTeacher: android.widget.CheckBox
    private lateinit var regularTeacher: android.widget.CheckBox
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
        val guideCard = Ui.card(this)
        guideCard.addView(Ui.text(this, "What is it trying to learn?", 17f, Ui.text, true))
        guideView = Ui.text(this, "Start learning to see the lesson, action, pass condition, and last result.", 14f, Ui.text)
        guideCard.addView(guideView)
        teachStepButton = Ui.button(this, "PAUSE AND TEACH THIS STEP") {
            service?.pause()
            startActivity(Intent(this, BrowserActivity::class.java).putExtra("show_teaching_help", true))
        }.apply { isEnabled = false }
        guideCard.addView(teachStepButton)
        column.addView(guideCard, Ui.cardParams())
        column.addView(Ui.row(this,
            Ui.button(this, "START") { service?.startLearning() },
            Ui.button(this, "PAUSE") { service?.pause() },
            Ui.button(this, "STOP") { service?.stopLearning() }
        ))
        allowTeacher = android.widget.CheckBox(this).apply {
            text = "Allow AI help: up to ${TeacherBudget.HOURLY_LIMIT} requests/hour and ${TeacherBudget.DAILY_LIMIT}/day. Off = local only."
            setTextColor(Ui.text)
            isChecked = com.appgate.tv.store.PlannerKeyStore.teacherEnabled(this@LearningActivity)
            setOnCheckedChangeListener { _, checked ->
                if (!updatingTeacher) {
                    com.appgate.tv.store.PlannerKeyStore.setTeacherEnabled(this@LearningActivity, checked)
                    refreshProgress()
                }
            }
        }
        column.addView(allowTeacher)
        regularTeacher = android.widget.CheckBox(this).apply {
            text = "Unrestricted regular AI help — GPT-5.4 mini with OpenAI, or your configured model with another provider. No app hourly/daily cap; API charges apply. Stays on until unchecked."
            setTextColor(Ui.text)
            isChecked = com.appgate.tv.store.PlannerKeyStore.unrestrictedRegular(this@LearningActivity)
            setOnCheckedChangeListener { _, checked ->
                if (!updatingTeacher) {
                    com.appgate.tv.store.PlannerKeyStore.setUnrestrictedRegular(this@LearningActivity, checked)
                    refreshProgress()
                }
            }
        }
        column.addView(regularTeacher)
        strongerTeacher = android.widget.CheckBox(this).apply {
            text = "Unrestricted smarter AI help — GPT-5.4 with more reasoning. More expensive; requires OpenAI. No app hourly/daily cap. Stays on until unchecked; takes priority if both boxes are checked."
            setTextColor(Ui.text)
            isEnabled = com.appgate.tv.store.PlannerKeyStore.strongerTeacherEligible(this@LearningActivity)
            isChecked = com.appgate.tv.store.PlannerKeyStore.unrestrictedSmarter(this@LearningActivity)
            setOnCheckedChangeListener { _, checked ->
                if (!updatingTeacher) {
                    com.appgate.tv.store.PlannerKeyStore.setUnrestrictedSmarter(this@LearningActivity, checked)
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
        guideView.text = state.learningGuide?.text() ?: "Start learning to see the lesson, action, pass condition, and last result."
        teachStepButton.isEnabled = state.learningGuide != null
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
            allowTeacher.text = if (com.appgate.tv.store.PlannerKeyStore.unrestricted(this)) "Allow AI help. Unrestricted option selected below. Off = local only." else "Allow AI help: up to ${TeacherBudget.HOURLY_LIMIT} requests/hour and ${TeacherBudget.DAILY_LIMIT}/day. Off = local only."
            allowTeacher.isChecked = com.appgate.tv.store.PlannerKeyStore.teacherEnabled(this)
            regularTeacher.isChecked = com.appgate.tv.store.PlannerKeyStore.unrestrictedRegular(this)
            strongerTeacher.isEnabled = com.appgate.tv.store.PlannerKeyStore.strongerTeacherEligible(this)
            strongerTeacher.isChecked = com.appgate.tv.store.PlannerKeyStore.unrestrictedSmarter(this)
            updatingTeacher = false
        }
        val s = service ?: return
        val unrestricted = com.appgate.tv.store.PlannerKeyStore.unrestricted(this)
        val usage = s.memory.teacherBudget.snapshot(unrestricted)
        val enabled = com.appgate.tv.store.PlannerKeyStore.teacherEnabled(this)
        usageView.text = buildString {
            append(if (enabled) "AI requests: ${usage.requests24h} in 24 hours · ${usage.requestsHour} in the last hour" else "Local-only mode · no new AI API calls")
            append("\nReported tokens: ${usage.inputTokens} in / ${usage.outputTokens} out (${usage.reportedRequests} responses)")
            if (com.appgate.tv.store.PlannerKeyStore.unrestrictedSmarter(this@LearningActivity)) {
                append("\nSelected: smarter AI · unrestricted until unchecked")
            }
            else if (com.appgate.tv.store.PlannerKeyStore.unrestrictedRegular(this@LearningActivity)) append("\nSelected: regular AI · unrestricted until unchecked")
            if (enabled) append(if (unrestricted) "\nNo app request cap. Provider rate and spending limits still apply." else "\nApp limits: ${TeacherBudget.HOURLY_LIMIT}/hour · ${TeacherBudget.DAILY_LIMIT}/24 hours")
            if (enabled && !com.appgate.tv.store.PlannerKeyStore.isConfigured(this@LearningActivity)) append("\nSet an AI planner key on the home screen to use AI help.")
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
