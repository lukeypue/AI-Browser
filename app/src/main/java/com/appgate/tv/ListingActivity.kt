package com.appgate.tv

import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoView

class ListingActivity : AppCompatActivity() {
    private lateinit var geckoView: GeckoView
    private lateinit var session: GeckoSession
    private var canGoBack = false
    private var activeSession: GeckoSession? = null
    private var rootSession: GeckoSession? = null
    private val childSessions = mutableListOf<GeckoSession>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Connected Site"
        val url = intent.getStringExtra("url").orEmpty()

        geckoView = GeckoView(this).apply {
            setBackgroundColor(Color.WHITE)
        }
        val sessionSettings = GeckoSessionSettings.Builder()
            .usePrivateMode(false)
            .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
            .build()
        lateinit var connectedNavigationDelegate: GeckoSession.NavigationDelegate
        connectedNavigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onCanGoBack(session: GeckoSession, value: Boolean) {
                if (session === activeSession) canGoBack = value
            }

            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession> {
                // OAuth providers commonly open the account chooser in a new browsing
                // context. Promote that child into the visible GeckoView.
                val child = GeckoSession(sessionSettings).apply {
                    contentDelegate = object : GeckoSession.ContentDelegate {}
                    navigationDelegate = connectedNavigationDelegate
                }
                // Gecko owns opening the returned popup session. Opening it ourselves here
                // races Gecko's popup lifecycle and can terminate the process on OAuth-heavy
                // sites such as OfferUp. Only return the unopened child, then attach it to the
                // visible view after Gecko has accepted the new-session request.
                childSessions += child
                runOnUiThread {
                    runCatching { geckoView.releaseSession() }
                    activeSession = child
                    canGoBack = false
                    geckoView.setSession(child)
                }
                return GeckoResult.fromValue(child)
            }
        }
        session = GeckoSession(sessionSettings).apply {
            contentDelegate = object : GeckoSession.ContentDelegate {}
            navigationDelegate = connectedNavigationDelegate
            open(GeckoRuntimeProvider.get(this@ListingActivity))
        }
        rootSession = session
        activeSession = session
        geckoView.setSession(session)

        val doneButton = Button(this).apply {
            text = "DONE — RETURN TO AI BROWSER"
            setOnClickListener { finish() }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(doneButton, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(geckoView, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            ))
        }
        setContentView(root)

        if (url.startsWith("https://")) session.loadUri(url) else finish()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        val current = activeSession
        if (current != null && canGoBack) current.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        if (::geckoView.isInitialized) runCatching { geckoView.releaseSession() }
        childSessions.forEach { child -> runCatching { child.close() } }
        childSessions.clear()
        if (::session.isInitialized) runCatching { session.close() }
        super.onDestroy()
    }
}
