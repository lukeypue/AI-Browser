package com.appgate.tv.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.appgate.brain.model.VerifyStatus
import com.appgate.tv.service.BrainService
import org.mozilla.geckoview.GeckoView

/**
 * The one place a person looks at the live browser session. It is used for three things:
 *  - sign-in / verification handoff (the brain paused; finish it here, tap Continue),
 *  - approving a prepared message before it is sent,
 *  - browsing or teaching (the brain watches what you do and turns it into a skill).
 *
 * The session shown here is the very same one the engine works in; nothing is copied.
 */
class BrowserActivity : ServiceBoundActivity() {
    private lateinit var geckoView: GeckoView
    private lateinit var status: TextView
    private lateinit var lessonView: TextView
    private lateinit var urlView: TextView
    private lateinit var continueButton: Button
    private lateinit var approveRow: LinearLayout
    private lateinit var previewView: TextView
    private lateinit var pauseButton: Button
    private lateinit var teachButton: Button
    private var teaching = false
    private var attached = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "AI Browser"
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.bg) }
        status = Ui.text(this, "Connecting to the browser engine…", 14f).apply { setPadding(16, 10, 16, 4) }
        urlView = Ui.text(this, "", 11f, Ui.muted).apply { setPadding(16, 0, 16, 6); maxLines = 1 }
        root.addView(status)
        root.addView(urlView)
        lessonView = Ui.text(this, "", 12f, Ui.accent).apply {
            setPadding(16, 4, 16, 8)
            maxLines = 2
            visibility = View.GONE
            setOnClickListener { showLessonHelp() }
            contentDescription = "Show lesson, pass condition, and last result"
        }
        root.addView(lessonView)

        continueButton = Ui.button(this, "DONE — CONTINUE") { service?.resumeAfterHuman(); finish() }.apply { visibility = View.GONE }
        root.addView(continueButton)

        approveRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(16, 4, 16, 4) }
        previewView = Ui.text(this, "", 14f, Ui.warn)
        approveRow.addView(Ui.text(this, "This message is filled in but NOT sent. Send it?", 13f, Ui.text, true))
        approveRow.addView(previewView)
        approveRow.addView(Ui.row(this,
            Ui.button(this, "SEND IT") { val s = service ?: return@button; s.grantSend(s.state.grantLedgerId) },
            Ui.button(this, "DON'T SEND") { val s = service ?: return@button; s.declineSend(s.state.grantLedgerId) }
        ))
        root.addView(approveRow)

        pauseButton = Ui.button(this, "PAUSE") { togglePause() }
        teachButton = Ui.button(this, "TEACH") { toggleTeach() }
        root.addView(Ui.row(this,
            Ui.button(this, "◀ BACK") { service?.session?.goBack() },
            Ui.button(this, "RELOAD") { service?.session?.reload() },
            pauseButton,
            teachButton
        ))

        geckoView = GeckoView(this).apply { setBackgroundColor(android.graphics.Color.WHITE) }
        root.addView(geckoView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    override fun onServiceReady(service: BrainService) {
        service.activityProvider = { this }
        val url = intent.getStringExtra("url")
        val s = service.session
        if (s != null) {
            s.attachTo(geckoView)
            attached = true
            if (url != null && !intent.getBooleanExtra("consumed", false)) {
                intent.putExtra("consumed", true)
                service.startBrowsing(url)
            }
        } else {
            status.text = "The browser engine is still starting…"
        }
        onState(service.state)
        if (intent.getBooleanExtra("show_teaching_help", false)) {
            intent.putExtra("show_teaching_help", false)
            showLessonHelp()
        }
    }

    override fun onServiceLost() {
        val s = service ?: return
        if (attached) { s.session?.detachFromView(); attached = false }
        s.activityProvider = { null }
        if (teaching) { s.setTeachMode(false); teaching = false }
    }

    override fun onState(state: BrainService.State) {
        status.text = state.status
        val guide = state.learningGuide
        val sameSite = guide != null && com.appgate.brain.perception.UrlPatterns.sameSite("https://${guide.host}/", state.url)
        lessonView.visibility = if (sameSite) View.VISIBLE else View.GONE
        lessonView.text = guide?.let { "Lesson: ${it.goal}\nTap for pass condition, last result, and teaching help" }.orEmpty()
        urlView.text = state.url
        if (!attached && state.engineReady) { service?.session?.let { it.attachTo(geckoView); attached = true } }
        val needsHuman = state.mode == BrainService.Mode.NEED_HUMAN || state.mode == BrainService.Mode.BROWSING || state.mode == BrainService.Mode.PAUSED
        continueButton.visibility = if (needsHuman) View.VISIBLE else View.GONE
        continueButton.text = when (state.mode) {
            BrainService.Mode.NEED_HUMAN -> "DONE — CONTINUE (${state.humanReason.ifBlank { "sign-in finished" }})"
            BrainService.Mode.PAUSED -> "RESUME"
            else -> "DONE — BACK TO AI BROWSER"
        }
        approveRow.visibility = if (state.mode == BrainService.Mode.NEED_GRANT) View.VISIBLE else View.GONE
        previewView.text = state.grantPreview
        pauseButton.text = if (state.mode == BrainService.Mode.SEARCHING || state.mode == BrainService.Mode.LEARNING) "PAUSE" else "RESUME"
        teachButton.text = if (teaching) "DONE TEACHING" else "TEACH"
    }

    override fun onStep(description: String, status: VerifyStatus?) {
        urlView.text = "${status?.name ?: ""} $description".trim()
    }

    private fun togglePause() {
        val s = service ?: return
        if (s.state.mode == BrainService.Mode.SEARCHING || s.state.mode == BrainService.Mode.LEARNING) s.pause() else s.resumeAfterHuman()
    }

    private fun showLessonHelp() {
        val explanation = service?.state?.learningGuide?.text() ?: "No learning attempt is available yet."
        AlertDialog.Builder(this).setTitle("Lesson and pass conditions")
            .setMessage(explanation + "\n\nTo demonstrate: pause, wait for the current action to finish, tap TEACH, perform this step on the page, then tap DONE TEACHING. The app will try the procedure with practice values and report whether it verified. AI sees the goal and expected checks; the verifier decides success from the observed page.")
            .setPositiveButton("OK", null).show()
    }

    private fun toggleTeach() {
        val s = service ?: return
        teaching = !teaching
        if (teaching) {
            AlertDialog.Builder(this).setTitle("Teach mode")
                .setMessage("Demonstrate the lesson shown above, from opening its control to applying the value. Wait for the current automated action to finish before demonstrating. Tap DONE TEACHING when finished; the app will check the procedure with practice values and report the result. Sign-in fields are excluded.")
                .setPositiveButton("OK", null).show()
            if (s.state.mode == BrainService.Mode.SEARCHING || s.state.mode == BrainService.Mode.LEARNING) s.pause()
        }
        s.setTeachMode(teaching)
        teachButton.text = if (teaching) "DONE TEACHING" else "TEACH"
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        val s = service?.session
        if (s != null && s.canGoBack) s.goBack() else super.onBackPressed()
    }
}
