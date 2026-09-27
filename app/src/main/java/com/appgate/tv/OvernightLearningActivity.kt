package com.appgate.tv

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.content.ContentValues
import android.provider.MediaStore
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.RenderProcessGoneDetail
import android.webkit.JavascriptInterface
import android.view.View
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.EditText
import android.text.InputType
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.appgate.tv.sitebrain.ExplorerBudget
import com.appgate.tv.sitebrain.LearningEvent
import com.appgate.tv.sitebrain.LearningProgressTracker
import com.appgate.tv.sitebrain.LearningReportWriter
import com.appgate.tv.sitebrain.LearningSite
import com.appgate.tv.sitebrain.LearningSiteCatalog
import com.appgate.tv.sitebrain.SharedPreferencesSiteBrainStore
import com.appgate.tv.sitebrain.SiteBrainControllerState
import com.appgate.tv.sitebrain.SiteBrainRepository
import com.appgate.tv.sitebrain.WebViewSiteBrainController
import com.appgate.tv.sitebrain.PopupDismissal
import com.appgate.tv.sitebrain.PageSnapshot
import com.appgate.tv.sitebrain.SemanticElement
import com.appgate.tv.sitebrain.SiteEdge
import com.appgate.tv.sitebrain.SafeActionClassifier
import com.appgate.tv.sitebrain.ActionKind
import com.appgate.tv.sitebrain.SafetyClass
import com.appgate.tv.sitebrain.CapabilityGapLogger
import com.appgate.tv.sitebrain.AiTeacherKeyStore
import com.appgate.tv.sitebrain.AiTeacherClient
import com.appgate.tv.sitebrain.SiteBrainObservation
import com.appgate.tv.sitebrain.LearningQueryGenerator
import com.appgate.tv.sitebrain.GitHubLogRequest
import com.appgate.tv.sitebrain.GitHubLogRequestClient
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

class OvernightLearningActivity : AppCompatActivity() {
    private val logWriter = Executors.newSingleThreadExecutor()
    @Volatile private var logWriteInFlight = false
    private lateinit var webView: WebView
    private lateinit var status: TextView
    private lateinit var counters: TextView
    private lateinit var pauseButton: Button
    private lateinit var resumeButton: Button
    private lateinit var skipButton: Button
    private lateinit var teachButton: Button
    private lateinit var aiTeacherButton: Button
    private lateinit var requestedLogsButton: Button
    private lateinit var controller: WebViewSiteBrainController
    private lateinit var brainRepository: SiteBrainRepository
    private lateinit var gapLogger: CapabilityGapLogger

    private val handler = Handler(Looper.getMainLooper())
    private val sites = LearningSiteCatalog.defaultSites()
    private val guard = SourceSessionGuard()
    private val events = ArrayList<LearningEvent>()

    private var tracker: LearningProgressTracker? = null
    private var userPaused = false
    private var stopped = false
    private var waitingForHuman = false
    private var authScreenOpen = false
    private var actionInFlight = false
    private var actionsThisSite = 0
    private var pagesThisSite = 0
    private var verifiedThisSite = 0
    private var consecutivePlateaus = 0
    private var siteStartedAt = 0L
    private var lastProgressAt = 0L
    private var activeSessionId = 0L
    private var activeSite: LearningSite? = null
    private var lastCoverage = 0.0
    private var awaitingInitialPage = false
    private val routeActionAttempts = HashMap<String, Int>()
    private val seenRoutesThisVisit = HashSet<String>()
    private var lastLogPersistAt = 0L
    private var unpersistedEventCount = 0
    private var totalEventsThisRun = 0L
    private var teachingMode = false
    private var lastHumanTouchAt = 0L
    private var lastObservedSnapshot: PageSnapshot? = null
    private var teacherCallInFlight = false
    private var lastTeacherCallAt = 0L
    private var pendingGitHubLogRequest: GitHubLogRequest? = null
    private var logRequestCheckInFlight = false
    private var pageSettleGeneration = 0
    private var mainFrameLoading = false
    private var pageSettling = false
    private var lastHeartbeatSignature = ""
    private var teachTextDialogOpen = false
    private var pendingAutomaticSiteMove = false

    private val githubLogRequestPoller = object : Runnable {
        override fun run() {
            if (!stopped) checkGitHubLogRequest()
            if (!stopped) handler.postDelayed(this, GITHUB_LOG_REQUEST_POLL_MS)
        }
    }

    private val webViewHeartbeat = object : Runnable {
        override fun run() {
            if (!stopped && ::webView.isInitialized) {
                val pm = getSystemService(POWER_SERVICE) as PowerManager
                val screenState = if (pm.isInteractive) "SCREEN_ON" else "SCREEN_OFF"
                val stalledSeconds = LearningRuntimePolicy.stalledForMs(System.currentTimeMillis(), lastProgressAt) / 1000
                webView.evaluateJavascript(
                    """(function(){
                        var b=document.body;
                        var controls=b?b.querySelectorAll('a,button,input,select,textarea,[role]').length:0;
                        return (document.readyState||'')+'|'+location.host+'|'+location.pathname+'|'+controls;
                    })();""".trimIndent()
                ) { raw ->
                    val signature = raw.orEmpty().trim()
                    val state = when {
                        signature.isBlank() || signature == "null" -> "NO_RESPONSE"
                        signature == lastHeartbeatSignature -> "ALIVE_STABLE"
                        else -> "ALIVE_CHANGED"
                    }
                    if (signature.isNotBlank() && signature != "null") lastHeartbeatSignature = signature
                    record(
                        "WEBVIEW_HEARTBEAT",
                        state,
                        activeSite?.expectedHost.orEmpty(),
                        lastObservedSnapshot?.routeSignature.orEmpty(),
                        "$screenState; stalled=${stalledSeconds}s; $signature"
                    )
                }
            }
            if (!stopped) handler.postDelayed(this, WEBVIEW_HEARTBEAT_MS)
        }
    }
    private val watchdog = object : Runnable {
        override fun run() {
            val stalled = LearningRuntimePolicy.stalledForMs(System.currentTimeMillis(), lastProgressAt)
            // Hard recovery is intentionally checked before the normal state gates. A dead
            // evaluateJavascript callback can leave pageSettling/actionInFlight true forever.
            // In that case the UI thread is still alive, so checkpoint and recreate the renderer
            // instead of allowing a multi-hour frozen learning session.
            if (!stopped && !userPaused && !teachingMode &&
                LearningRuntimePolicy.shouldHardRecover(stalled, waitingForHuman, authScreenOpen)) {
                val site = activeSite
                record(
                    "HARD_FREEZE_RECOVERY",
                    "RESTARTING_RENDERER",
                    site?.expectedHost.orEmpty(),
                    lastObservedSnapshot?.routeSignature.orEmpty(),
                    "No useful progress for ${stalled / 1000}s; checkpoint saved before renderer recreation"
                )
                saveCheckpoint()
                stopped = true
                handler.removeCallbacksAndMessages(null)
                runCatching { webView.stopLoading() }
                runCatching { webView.removeAllViews() }
                runCatching { webView.destroy() }
                status.text = "Learning ${site?.name ?: "site"}\nFrozen browser detected. Restarting safely…"
                handler.postDelayed({
                    if (!isFinishing && !isDestroyed) recreate()
                }, 700L)
                return
            }
            if (!stopped && !userPaused && !authScreenOpen && !teachingMode && !mainFrameLoading && !pageSettling && !actionInFlight && !teacherCallInFlight) {
                if (LearningRuntimePolicy.shouldAutoSkip(stalled, waitingForHuman)) {
                    val site = activeSite
                    gapLogger.record("STALLED", site?.name.orEmpty(), site?.expectedHost.orEmpty(), "", "No useful progress for ${stalled / 1000}s before auto-skip")
                    record(
                        "WATCHDOG_AUTO_SKIP",
                        "SKIPPED",
                        site?.expectedHost.orEmpty(),
                        "",
                        "No useful progress for ${stalled / 1000}s; checkpointed and moving on"
                    )
                    skipCurrentSite(auto = true)
                }
            }
            if (!stopped) handler.postDelayed(this, WATCHDOG_POLL_MS)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Site Brain — Overnight Learning"
        // Keep the display awake while the Overnight Learning screen is open.
        // This is more reliable and cleaner than simulating fake touches every few minutes.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        startKeepAliveService()

        val brainPrefs = getSharedPreferences("site_brain_knowledge", MODE_PRIVATE)
        brainRepository = SiteBrainRepository(SharedPreferencesSiteBrainStore(brainPrefs))
        controller = WebViewSiteBrainController(brainRepository)
        gapLogger = CapabilityGapLogger(this)

        val runPrefs = getSharedPreferences("site_brain_learning_run", MODE_PRIVATE)
        tracker = LearningProgressTracker(
            siteCount = sites.size,
            savedSiteIndex = runPrefs.getInt("site_index", 0),
            savedVerified = runPrefs.getInt("verified", 0),
            savedPasses = runPrefs.getInt("passes", 0)
        )
        loadExistingLog()
        val installedInfo = runCatching { packageManager.getPackageInfo(packageName, 0) }.getOrNull()
        val installedVersionName = installedInfo?.versionName.orEmpty().ifBlank { "unknown" }
        val installedVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) installedInfo?.longVersionCode ?: 0L else @Suppress("DEPRECATION") (installedInfo?.versionCode?.toLong() ?: 0L)
        record("APP_VERSION", "ACTIVE", "", "", "$installedVersionName ($installedVersionCode)")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(10, 15, 23))
            setPadding(8, 6, 8, 6)
        }
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            text = "Preparing overnight Site Brain learning…"
        }
        counters = TextView(this).apply {
            setTextColor(Color.rgb(145, 205, 165))
            textSize = 11f
            setPadding(0, 2, 0, 3)
        }
        root.addView(status)
        root.addView(counters)

        fun compact(button: Button): Button = button.apply {
            textSize = 11f
            minHeight = 0
            minimumHeight = 0
            setPadding(6, 2, 6, 2)
        }

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pauseButton = compact(Button(this).apply {
            text = "PAUSE"
            setOnClickListener { pauseLearning() }
        })
        resumeButton = compact(Button(this).apply {
            text = "RESUME"
            isEnabled = false
            setOnClickListener { resumeLearning() }
        })
        skipButton = compact(Button(this).apply {
            text = "SKIP"
            setOnClickListener { skipCurrentSite(auto = false) }
        })
        val stopButton = compact(Button(this).apply {
            text = "STOP"
            setOnClickListener { stopLearning() }
        })
        row1.addView(pauseButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row1.addView(resumeButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row1.addView(skipButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row1.addView(stopButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row1)

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        teachButton = compact(Button(this).apply {
            text = "TEACH"
            setOnClickListener { toggleTeachMode() }
        })
        aiTeacherButton = compact(Button(this).apply {
            setOnClickListener { showAiTeacherKeyDialog() }
        })
        row2.addView(teachButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row2.addView(aiTeacherButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row2.addView(compact(Button(this).apply {
            text = "UPDATE"
            setOnClickListener { startActivity(Intent(this@OvernightLearningActivity, UpdateActivity::class.java)) }
        }), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row2.addView(compact(Button(this).apply {
            text = "SAVE LOG"
            setOnClickListener { saveLearningLogToDownloads() }
        }), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row2)
        updateAiTeacherButton()

        val row3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row3.addView(compact(Button(this).apply {
            text = "AI GAPS"
            setOnClickListener { shareGapLog() }
        }), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        requestedLogsButton = compact(Button(this).apply {
            text = "GITHUB LOGS"
            setOnClickListener {
                val request = pendingGitHubLogRequest
                if (request != null) shareRequestedLogs(request) else checkGitHubLogRequest(forceToast = true)
            }
        })
        row3.addView(requestedLogsButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row3)

        webView = WebView(this).apply {
            setBackgroundColor(Color.WHITE)
            setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.databaseEnabled = true
            // Keep autonomous training deterministic. Sites can otherwise persist or infer a
            // different locale mid-run, which makes the same semantic control look unrelated.
            // Multilingual understanding remains a Site Brain goal; English is simply the
            // canonical overnight-training locale.
            settings.userAgentString = settings.userAgentString
            // Avoid pre-rendering off-screen pages during long runs; it can retain large render surfaces.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) settings.offscreenPreRaster = false
            // Learning must see the same functional page the user sees. Modern marketplace
            // sites use images, lazy-loaded regions and image-backed controls as part of layout
            // and authentication flows, so blocking images can leave an incomplete page.
            settings.loadsImagesAutomatically = true
            settings.blockNetworkImage = false
            // Allow normal JavaScript login windows. Keep multiple-window support disabled so
            // target=_blank navigation stays in this learning WebView and retains its cookies.
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.setSupportMultipleWindows(false)
            settings.mediaPlaybackRequiresUserGesture = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = WebChromeClient()
            addJavascriptInterface(TeachBridge(), "SiteBrainTeachBridge")
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_UP) {
                    lastHumanTouchAt = System.currentTimeMillis()
                }
                false
            }
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    mainFrameLoading = true
                    pageSettling = false
                    touchProgress()
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    if (request == null || !request.isForMainFrame) return false
                    return allowNavigation(request.url.toString())
                }

                @Suppress("DEPRECATION")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                    if (url.isNullOrBlank()) return false
                    return allowNavigation(url)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    mainFrameLoading = false
                    touchProgress()
                    CookieManager.getInstance().flush()
                    if (stopped || userPaused || authScreenOpen) return
                    val actual = url.orEmpty()
                    val parsed = Uri.parse(actual)
                    val host = parsed.host.orEmpty()
                    if (!guard.accept(activeSessionId, host)) {
                        record("STALE_OR_WRONG_HOST", "IGNORED", host, parsed.path.orEmpty(), "Ignored callback for another site/session")
                        return
                    }
                    if (awaitingInitialPage) {
                        webView.clearHistory()
                        awaitingInitialPage = false
                    }
                    webView.visibility = View.VISIBLE
                    pagesThisSite++
                    val routeKey = "$host${parsed.path.orEmpty()}"
                    if (seenRoutesThisVisit.add(routeKey)) touchProgress()
                    record("PAGE_LOADED", "OBSERVED", host, parsed.path.orEmpty(), actual)
                    installHumanTeachListener()
                    if (teachingMode) {
                        refreshTeachingSnapshot()
                        return
                    }
                    waitForPageSettle {
                        if (actionInFlight) {
                            verifyAction()
                        } else {
                            tryDismissBlockingPopup { dismissed ->
                                if (!dismissed) mapAndAct()
                            }
                        }
                    }
                }

                override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                    val didCrash = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) detail?.didCrash() == true else true
                    runCatching {
                        getSharedPreferences("site_brain_crash_recovery", MODE_PRIVATE).edit()
                            .putLong("renderer_gone_at", System.currentTimeMillis())
                            .putString("site", activeSite?.name.orEmpty())
                            .putString("host", activeSite?.expectedHost.orEmpty())
                            .putBoolean("did_crash", didCrash)
                            .apply()
                    }
                    stopped = true
                    handler.removeCallbacksAndMessages(null)
                    runCatching { view?.stopLoading() }
                    runCatching { view?.removeAllViews() }
                    runCatching { view?.destroy() }
                    status.text = "Browser renderer stopped\nSite Brain checkpoint was saved. Restarting learning safely…"
                    saveCheckpoint()
                    handler.postDelayed({
                        if (!isFinishing && !isDestroyed) recreate()
                    }, 700L)
                    return true
                }

                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame != true || stopped) return
                    mainFrameLoading = false
                    pageSettling = false
                    val host = request.url.host.orEmpty()
                    if (!guard.accept(activeSessionId, host)) return
                    webView.visibility = View.INVISIBLE
                    gapLogger.record("SITE_LOAD_ERROR", activeSite?.name.orEmpty(), host, request.url.path.orEmpty(), error?.description?.toString().orEmpty())
                    record("PAGE_ERROR", "ERROR", host, request.url.path.orEmpty(), error?.description?.toString().orEmpty())
                    status.text = "Learning ${activeSite?.name ?: "site"}\nSite page did not load; retrying without showing stale content."
                    handler.postDelayed({ recoverOrMove("page error") }, 1200L)
                }
            }
        }
        root.addView(webView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        updateCounters()
        handler.postDelayed(watchdog, WATCHDOG_POLL_MS)
        handler.postDelayed(webViewHeartbeat, WEBVIEW_HEARTBEAT_MS)
        handler.postDelayed(githubLogRequestPoller, 8_000L)
        startCurrentSite()
    }

    private fun waitForPageSettle(onReady: () -> Unit) {
        val generation = ++pageSettleGeneration
        pageSettling = true
        val startedAt = System.currentTimeMillis()
        var lastSignature = ""
        var stableSamples = 0

        fun poll() {
            if (generation != pageSettleGeneration || stopped || userPaused || authScreenOpen) return
            webView.evaluateJavascript(
                """(function(){
                    var d=document;
                    var b=d.body;
                    var ready=d.readyState||'';
                    var h=b?b.scrollHeight:0;
                    var n=b?b.querySelectorAll('a,button,input,select,textarea,[role]').length:0;
                    var t=b?((b.innerText||'').length):0;
                    var r=(window.performance&&performance.getEntriesByType)?performance.getEntriesByType('resource').length:0;
                    function vis(el){
                        if(!el) return false;
                        var s=getComputedStyle(el),x=el.getBoundingClientRect();
                        return s.display!=='none'&&s.visibility!=='hidden'&&x.width>0&&x.height>0;
                    }
                    var busy=Array.from(d.querySelectorAll(
                        '[aria-busy="true"],[role="progressbar"],progress,[data-loading="true"],.loading,.spinner,.skeleton'
                    )).filter(vis).length;
                    return ready+'|'+h+'|'+n+'|'+t+'|'+r+'|'+busy;
                })();""".trimIndent()
            ) { raw ->
                if (generation != pageSettleGeneration || stopped || userPaused || authScreenOpen) return@evaluateJavascript
                val signature = raw.orEmpty().trim('"')
                val parts = signature.split('|')
                val complete = parts.firstOrNull() == "complete"
                val busy = parts.lastOrNull()?.toIntOrNull() ?: 0
                val unchanged = signature == lastSignature && lastSignature.isNotBlank()

                if (!unchanged && lastSignature.isNotBlank()) touchProgress()
                stableSamples = if (unchanged && complete && busy == 0) stableSamples + 1 else 0
                lastSignature = signature

                val elapsed = System.currentTimeMillis() - startedAt
                val settled = elapsed >= 2_400L && stableSamples >= 3
                val hardCap = elapsed >= 7_000L

                if (settled || hardCap) {
                    pageSettling = false
                    touchProgress()
                    record(
                        "PAGE_SETTLE",
                        if (settled) "STABLE" else "TIMEOUT_RECOVERING",
                        activeSite?.expectedHost.orEmpty(),
                        lastObservedSnapshot?.routeSignature.orEmpty(),
                        "waited=" + elapsed + "ms; stableSamples=" + stableSamples + "; busy=" + busy
                    )
                    onReady()
                } else {
                    handler.postDelayed({ poll() }, 450L)
                }
            }
        }

        handler.postDelayed({ poll() }, 450L)
    }

    private fun startKeepAliveService() {
        val intent = Intent(this, LearningKeepAliveService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }

    private fun allowNavigation(rawUrl: String): Boolean {
        val site = activeSite ?: return false
        val parsed = runCatching { Uri.parse(rawUrl) }.getOrNull() ?: return true
        val scheme = parsed.scheme.orEmpty().lowercase()
        val host = parsed.host.orEmpty()

        // App/deep-link schemes (Instagram, TikTok, etc.) are not webpages. Never let
        // WebView navigate to them: doing so replaces a healthy page with
        // ERR_UNKNOWN_URL_SCHEME and poisons the learning pass.
        if (scheme != "http" && scheme != "https") {
            record(
                "APP_DEEP_LINK",
                "BLOCKED_KEEPING_WEB_PAGE",
                host,
                parsed.path.orEmpty(),
                "Ignored external scheme '$scheme'; Site Brain remains on the current web page"
            )
            touchProgress()
            return true
        }
        if (site.acceptsHost(host)) return false
        if (LearningNavigationPolicy.isKnownAuthHost(host)) {
            openHumanSignIn(rawUrl)
            return true
        }
        record("CROSS_SITE_NAVIGATION", "BLOCKED", host, parsed.path.orEmpty(), "Training kept inside ${site.expectedHost}")
        return true
    }

    private fun openDedicatedSiteLogin() {
        if (stopped || authScreenOpen) return
        val site = activeSite ?: return
        val loginUrl = site.loginUrl
        if (loginUrl.isNullOrBlank()) {
            Toast.makeText(this, "No dedicated login page is configured for ${site.name}.", Toast.LENGTH_LONG).show()
            return
        }
        record("HUMAN_AUTH", "MANUAL_LOGIN_OPENED", site.expectedHost, "", "Dedicated login button opened the site sign-in flow")
        openHumanSignIn(loginUrl)
    }

    private fun openHumanSignIn(authUrl: String) {
        if (authScreenOpen || stopped) return
        val site = activeSite ?: return
        authScreenOpen = true
        waitingForHuman = true
        pauseButton.isEnabled = false
        resumeButton.isEnabled = false
        record("HUMAN_AUTH", "OPENED", Uri.parse(authUrl).host.orEmpty(), Uri.parse(authUrl).path.orEmpty(), "Opened separate human-only sign-in screen")
        status.text = "${site.name} sign-in opened\nFinish login in the sign-in screen, then return here."
        startActivityForResult(Intent(this, HumanSignInActivity::class.java).apply {
            putExtra("authUrl", authUrl)
            putExtra("targetHost", site.expectedHost)
            putExtra("targetName", site.name)
        }, AUTH_REQUEST)
    }

    private fun startCurrentSite() {
        if (stopped || userPaused || sites.isEmpty()) return
        val t = tracker ?: return
        val site = sites[t.siteIndex]
        activeSite = site
        controller = WebViewSiteBrainController(brainRepository)
        val session = guard.begin(site.key, site.expectedHost)
        activeSessionId = session.id
        actionsThisSite = 0
        pagesThisSite = 0
        verifiedThisSite = 0
        consecutivePlateaus = 0
        lastCoverage = 0.0
        routeActionAttempts.clear()
        seenRoutesThisVisit.clear()
        lastObservedSnapshot = null
        siteStartedAt = System.currentTimeMillis()
        lastProgressAt = siteStartedAt
        actionInFlight = false
        mainFrameLoading = false
        pageSettling = false
        waitingForHuman = false
        authScreenOpen = false
        pendingAutomaticSiteMove = false
        controller.markHumanResume()
        awaitingInitialPage = true
        // Keep the browser visible while switching sites. The previous implementation hid
        // WebView until onPageFinished; modern SPAs can render useful content and then keep
        // network activity alive long enough that onPageFinished is delayed or never arrives.
        // That produced the black KSL screen even though the page briefly rendered.
        webView.visibility = View.VISIBLE
        status.text = "Learning ${site.name}\nLoading this site in a fresh visual session…"
        record("SITE_START", "STARTED", site.expectedHost, "", "root=${site.startUrl}; overnight=true")
        saveCheckpoint()
        webView.stopLoading()
        webView.loadUrl("about:blank")
        handler.postDelayed({
            if (!stopped && !userPaused && activeSessionId == session.id) {
                webView.loadUrl(site.startUrl, mapOf("Accept-Language" to "en-US,en;q=0.9"))
            }
        }, 120L)
    }

    private fun mapAndAct() {
        if (stopped || userPaused || waitingForHuman || actionInFlight || authScreenOpen || teachingMode) return
        val site = activeSite ?: return
        val elapsed = System.currentTimeMillis() - siteStartedAt
        if (actionsThisSite >= LearningRuntimePolicy.maxActionsPerVisit || elapsed >= LearningRuntimePolicy.maxMinutesPerVisit * 60_000L) {
            moveToNextSite("deep overnight visit budget reached")
            return
        }

        controller.observe(webView) { result ->
            if (stopped || userPaused || authScreenOpen) return@observe
            result.onFailure {
                record("OBSERVE", "ERROR", site.expectedHost, "", it.message.orEmpty())
                recoverOrMove("observe failed")
            }.onSuccess { observation ->
                val actualHost = observation.snapshot.host
                if (!guard.accept(activeSessionId, actualHost)) {
                    record("OBSERVE", "STALE", actualHost, observation.snapshot.routeSignature, "Observation discarded by source-session guard")
                    return@onSuccess
                }
                lastObservedSnapshot = observation.snapshot
                lastCoverage = observation.brain.coverageScore
                val percent = (lastCoverage * 100).toInt().coerceIn(0, 100)
                status.text = "Learning ${site.name}\n$percent% mapped · ${observation.safeActionsFound} safe controls · $verifiedThisSite verified here · action ${actionsThisSite + 1}/${LearningRuntimePolicy.maxActionsPerVisit}"

                if (observation.controllerState == SiteBrainControllerState.WAITING_FOR_HUMAN) {
                    waitForHuman(actualHost, observation.snapshot.routeSignature, "Login/CAPTCHA/security check requires you")
                    return@onSuccess
                }

                val budget = ExplorerBudget(
                    maxPages = 5000,
                    maxActions = LearningRuntimePolicy.maxActionsPerVisit,
                    maxRevisitsPerFingerprint = 100,
                    maxElapsedMs = LearningRuntimePolicy.maxMinutesPerVisit * 60_000L,
                    pagesSeen = pagesThisSite,
                    actionsTaken = actionsThisSite,
                    startedAt = siteStartedAt
                )
                val trainingQuery = LearningQueryGenerator.nextQuery(site.key, site.name, site.expectedHost, actionsThisSite)
                val prepared = controller.prepareExploration(observation, budget, trainingQuery)
                if (prepared == null) {
                    if (!requestAiTeacher(observation, "No safe unexplored action was available")) {
                        handlePlateau(actualHost, observation.snapshot.routeSignature)
                    }
                    return@onSuccess
                }

                val attemptKey = "$actualHost|${observation.snapshot.routeSignature}|${prepared.semanticIntent}"
                val previousAttempts = routeActionAttempts[attemptKey] ?: 0
                if (!LearningRuntimePolicy.shouldTrySameRouteAction(previousAttempts)) {
                    record(
                        "DUPLICATE_BRANCH",
                        "SUPPRESSED",
                        actualHost,
                        observation.snapshot.routeSignature,
                        "Already tried ${prepared.semanticIntent} $previousAttempts times on this route; forcing a different branch"
                    )
                    handlePlateau(actualHost, observation.snapshot.routeSignature)
                    return@onSuccess
                }
                routeActionAttempts[attemptKey] = previousAttempts + 1

                actionInFlight = true
                actionsThisSite++
                record(prepared.semanticIntent, "ATTEMPTED", actualHost, observation.snapshot.routeSignature, "safe action", lastCoverage, lastCoverage)
                controller.executePrepared(webView, prepared) { accepted ->
                    if (!accepted) {
                        actionInFlight = false
                        gapLogger.record("ACTION_REJECTED", site.name, actualHost, observation.snapshot.routeSignature, prepared.semanticIntent)
                        record(prepared.semanticIntent, "REJECTED", actualHost, observation.snapshot.routeSignature, "executor did not accept action")
                        if (!requestAiTeacher(observation, "Executor rejected ${prepared.semanticIntent}")) {
                            handler.postDelayed({ mapAndAct() }, 650L)
                        }
                    } else {
                        handler.postDelayed({ verifyAction() }, 1100L)
                    }
                }
            }
        }
    }

    private fun updateAiTeacherButton() {
        if (!::aiTeacherButton.isInitialized) return
        aiTeacherButton.text = if (AiTeacherKeyStore.isConfigured(this)) "AI: ON" else "AI KEY"
    }

    private fun showAiTeacherKeyDialog() {
        val input = EditText(this).apply {
            hint = "OpenAI API key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("AI Teacher")
            .setMessage("For this test build the key is encrypted with Android Keystore and stays on this phone. The teacher sends only generic site/control structure, not cookies, passwords, or full browsing text.")
            .setView(input)
            .setPositiveButton("SAVE") { _, _ ->
                val key = input.text?.toString().orEmpty().trim()
                if (key.isNotBlank()) {
                    runCatching { AiTeacherKeyStore.save(this, key) }
                        .onSuccess { Toast.makeText(this, "AI Teacher enabled.", Toast.LENGTH_SHORT).show() }
                        .onFailure { Toast.makeText(this, "Could not save the AI Teacher key.", Toast.LENGTH_LONG).show() }
                    updateAiTeacherButton()
                }
            }
            .setNegativeButton("CANCEL", null)
        if (AiTeacherKeyStore.isConfigured(this)) {
            dialog.setNeutralButton("CLEAR KEY") { _, _ ->
                AiTeacherKeyStore.clear(this)
                updateAiTeacherButton()
                Toast.makeText(this, "AI Teacher disabled.", Toast.LENGTH_SHORT).show()
            }
        }
        dialog.show()
    }

    private fun requestAiTeacher(observation: SiteBrainObservation, reason: String): Boolean {
        val key = AiTeacherKeyStore.load(this) ?: return false
        val site = activeSite
        val trainingIntent = LearningQueryGenerator.nextQuery(site?.key.orEmpty(), site?.name.orEmpty(), observation.snapshot.host, actionsThisSite)
        val teacherReason = "Autonomous training goal: $trainingIntent. $reason"
        val now = System.currentTimeMillis()
        if (teacherCallInFlight || now - lastTeacherCallAt < AI_TEACHER_COOLDOWN_MS) return false
        teacherCallInFlight = true
        lastTeacherCallAt = now
        val source = activeSite?.name.orEmpty()
        val host = observation.snapshot.host
        val route = observation.snapshot.routeSignature
        status.text = "Learning ${activeSite?.name ?: "site"}\nAI Teacher is studying a hard control…"
        record("AI_TEACHER", "REQUESTED", host, route, teacherReason)
        AiTeacherClient.suggest(key, observation.snapshot, teacherReason) { result ->
            runOnUiThread {
                teacherCallInFlight = false
                if (stopped || userPaused || teachingMode || authScreenOpen) return@runOnUiThread
                result.onFailure { error ->
                    gapLogger.record("AI_TEACHER_ERROR", source, host, route, error.message.orEmpty())
                    record("AI_TEACHER", "ERROR", host, route, error.message.orEmpty())
                    handlePlateau(host, route)
                }.onSuccess teacherSuccess@ { suggestion ->
                    val detail = buildString {
                        append(suggestion.diagnosis)
                        suggestion.capabilityGap?.let { append(" | gap=").append(it) }
                    }
                    record("AI_TEACHER", "ANSWERED", host, route, detail)
                    if (suggestion.needsEngineCode || suggestion.actionKind == null || suggestion.targetElementId == null) {
                        gapLogger.record("AI_ENGINEER_NEEDED", source, host, route, detail)
                        handlePlateau(host, route)
                        return@teacherSuccess
                    }
                    controller.observe(webView) { currentResult ->
                        currentResult.onFailure {
                            gapLogger.record("AI_TEACHER_RECHECK_FAILED", source, host, route, it.message.orEmpty())
                            handler.postDelayed({ mapAndAct() }, 600L)
                        }.onSuccess currentSuccess@ { current ->
                            if (!guard.accept(activeSessionId, current.snapshot.host)) return@currentSuccess
                            val prepared = controller.prepareTeacherExploration(
                                current,
                                suggestion.targetElementId,
                                suggestion.actionKind,
                                LearningQueryGenerator.nextQuery(activeSite?.key.orEmpty(), activeSite?.name.orEmpty(), current.snapshot.host, actionsThisSite)
                            )
                            if (prepared == null) {
                                gapLogger.record("AI_TEACHER_UNUSABLE", source, host, route, detail)
                                handlePlateau(host, route)
                                return@currentSuccess
                            }
                            actionInFlight = true
                            actionsThisSite++
                            record(prepared.semanticIntent, "AI_TEACHER_ATTEMPTED", current.snapshot.host, current.snapshot.routeSignature, detail)
                            controller.executePrepared(webView, prepared) { accepted ->
                                if (!accepted) {
                                    actionInFlight = false
                                    gapLogger.record("AI_TEACHER_ACTION_REJECTED", source, host, route, prepared.semanticIntent)
                                    handler.postDelayed({ mapAndAct() }, 650L)
                                } else {
                                    handler.postDelayed({ verifyAction() }, 1100L)
                                }
                            }
                        }
                    }
                }
            }
        }
        return true
    }

    private fun tryDismissBlockingPopup(done: (Boolean) -> Unit) {
        webView.evaluateJavascript(PopupDismissal.javascript()) { raw ->
            val decoded = raw.orEmpty().trim().trim('"')
            if (decoded.startsWith("DISMISSED:")) {
                gapLogger.record("POPUP_DISMISSED", activeSite?.name.orEmpty(), activeSite?.expectedHost.orEmpty(), "", decoded.removePrefix("DISMISSED:"))
                record("POPUP_DISMISS", "VERIFIED", activeSite?.expectedHost.orEmpty(), "", decoded.removePrefix("DISMISSED:"))
                touchProgress()
                handler.postDelayed({ mapAndAct() }, 450L)
                done(true)
            } else {
                done(false)
            }
        }
    }

    private fun toggleTeachMode() {
        if (stopped) return
        teachingMode = !teachingMode
        if (teachingMode) {
            actionInFlight = false
            controller = WebViewSiteBrainController(brainRepository)
            teachButton.text = "DONE TEACHING"
            pauseButton.isEnabled = false
            resumeButton.isEnabled = false
            status.text = "Teach mode\nTap the hard safe button, dropdown, filter, or popup-close action yourself. The brain will record what you clicked."
            installHumanTeachListener()
            refreshTeachingSnapshot()
            record("TEACH_MODE", "STARTED", activeSite?.expectedHost.orEmpty(), "", "Human demonstration mode started")
        } else {
            teachButton.text = "TEACH ME"
            pauseButton.isEnabled = true
            resumeButton.isEnabled = false
            controller = WebViewSiteBrainController(brainRepository)
            touchProgress()
            record("TEACH_MODE", "ENDED", activeSite?.expectedHost.orEmpty(), "", "Human demonstration mode ended")
            handler.postDelayed({ mapAndAct() }, 500L)
        }
    }

    private fun refreshTeachingSnapshot() {
        controller.observe(webView) { result ->
            result.onSuccess { observation ->
                lastObservedSnapshot = observation.snapshot
                lastCoverage = observation.brain.coverageScore
            }
        }
    }

    private fun installHumanTeachListener() {
        val script = """
            (function(){
              if(window.__siteBrainTeachInstalled) return 'READY';
              window.__siteBrainTeachInstalled=true;
              function clean(v){return (v||'').replace(/\s+/g,' ').trim();}
              function locator(el){
                if(el.id) return '#'+CSS.escape(el.id);
                var n=el.getAttribute('name');
                if(n) return el.tagName.toLowerCase()+'[name="'+n.replace(/"/g,'')+'"]';
                var a=el.getAttribute('aria-label');
                if(a) return '[aria-label="'+a.replace(/"/g,'')+'"]';
                return el.tagName.toLowerCase();
              }
              function capture(e){
                if(!e.isTrusted || !window.SiteBrainTeachBridge) return;
                var el=e.target && e.target.closest ? e.target.closest('button,a,input,textarea,select,[contenteditable="true"],[role="button"],[role="option"],[role="combobox"],[aria-label]') : e.target;
                if(!el) return;
                var tag=(el.tagName||'').toLowerCase();
                var type=(el.getAttribute('type')||'').toLowerCase();
                var editable=(tag==='input' && type!=='password' && type!=='hidden' && type!=='file' && type!=='checkbox' && type!=='radio') || tag==='textarea' || el.getAttribute('contenteditable')==='true';
                if(editable && e.type==='click'){
                  SiteBrainTeachBridge.onEditableFocus(JSON.stringify({
                    host:location.host.toLowerCase(),
                    tag:tag,
                    role:el.getAttribute('role')||'',
                    label:clean(el.getAttribute('aria-label')||el.getAttribute('title')||el.getAttribute('placeholder')||el.getAttribute('name')||''),
                    inputType:type,
                    locator:locator(el)
                  }));
                }
                var p=el.parentElement;
                var payload={
                  host:location.host.toLowerCase(),
                  tag:(el.tagName||'').toLowerCase(),
                  role:el.getAttribute('role')||'',
                  label:clean(el.getAttribute('aria-label')||el.getAttribute('title')||el.innerText||el.textContent||el.value||''),
                  href:el.href||'',
                  inputType:(el.getAttribute('type')||'').toLowerCase(),
                  nearbyText:p?clean(p.innerText||p.textContent||'').slice(0,220):'',
                  locator:locator(el)
                };
                SiteBrainTeachBridge.onHumanClick(JSON.stringify(payload));
              }
              document.addEventListener('click',capture,true);
              document.addEventListener('change',capture,true);
              return 'READY';
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    inner class TeachBridge {
        @JavascriptInterface
        fun onHumanClick(json: String) {
            runOnUiThread { handleHumanClick(json) }
        }

        @JavascriptInterface
        fun onEditableFocus(json: String) {
            runOnUiThread { showTeachTextDialog(json) }
        }
    }

    private fun showTeachTextDialog(json: String) {
        if (!teachingMode || stopped || teachTextDialogOpen) return
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return
        val host = obj.optString("host")
        if (!guard.accept(activeSessionId, host)) return
        val locator = obj.optString("locator").takeIf { it.isNotBlank() } ?: return
        val label = obj.optString("label").ifBlank { "search/text field" }.take(120)
        val input = EditText(this).apply {
            hint = "Type a safe example, e.g. rake"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        teachTextDialogOpen = true
        AlertDialog.Builder(this)
            .setTitle("Teach text field")
            .setMessage("Enter the example text you want Site Brain to learn for: $label")
            .setView(input)
            .setPositiveButton("ENTER") { _, _ ->
                val value = input.text?.toString().orEmpty().trim()
                if (value.isNotBlank()) injectTeachText(locator, value, label)
            }
            .setNegativeButton("CANCEL", null)
            .setOnDismissListener { teachTextDialogOpen = false }
            .show()
    }

    private fun injectTeachText(locator: String, value: String, label: String) {
        val locatorJson = JSONObject.quote(locator)
        val valueJson = JSONObject.quote(value)
        val script = """
            (function(){
              var el=null;
              try{ el=document.querySelector($locatorJson); }catch(e){}
              if(!el) return 'MISSING';
              try{ el.focus(); }catch(e){}
              var tag=(el.tagName||'').toLowerCase();
              if(tag==='input' || tag==='textarea'){
                var proto=tag==='textarea'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;
                var desc=Object.getOwnPropertyDescriptor(proto,'value');
                if(desc && desc.set) desc.set.call(el,$valueJson); else el.value=$valueJson;
              } else if(el.getAttribute('contenteditable')==='true'){
                el.textContent=$valueJson;
              } else return 'NOT_EDITABLE';
              el.dispatchEvent(new Event('input',{bubbles:true}));
              el.dispatchEvent(new Event('change',{bubbles:true}));
              try{ el.dispatchEvent(new KeyboardEvent('keyup',{key:'Enter',code:'Enter',bubbles:true})); }catch(e){}
              return 'FILLED';
            })();
        """.trimIndent()
        webView.evaluateJavascript(script) { raw ->
            val ok = raw.orEmpty().uppercase().contains("FILLED")
            if (ok) {
                touchProgress()
                record("HUMAN_DEMO", "TEXT_FILLED", activeSite?.expectedHost.orEmpty(), lastObservedSnapshot?.routeSignature.orEmpty(), "$label=[user example]")
                handler.postDelayed({ refreshTeachingSnapshot() }, 400L)
            } else {
                record("HUMAN_DEMO", "TEXT_FILL_FAILED", activeSite?.expectedHost.orEmpty(), lastObservedSnapshot?.routeSignature.orEmpty(), label)
                Toast.makeText(this, "That field changed before I could fill it. Tap it and try once more.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun handleHumanClick(json: String) {
        if (!teachingMode || stopped || System.currentTimeMillis() - lastHumanTouchAt > 2_000L) return
        val before = lastObservedSnapshot ?: return
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return
        val host = obj.optString("host")
        if (!guard.accept(activeSessionId, host)) return
        val element = SemanticElement(
            id = "human",
            tag = obj.optString("tag"),
            role = obj.optString("role").takeIf { it.isNotBlank() },
            label = obj.optString("label").take(180),
            href = obj.optString("href").takeIf { it.isNotBlank() },
            inputType = obj.optString("inputType").takeIf { it.isNotBlank() },
            selected = false,
            disabled = false,
            nearbyText = obj.optString("nearbyText").take(220),
            locatorHints = listOfNotNull(obj.optString("locator").takeIf { it.isNotBlank() })
        )
        if (SafeActionClassifier.classify(element) != SafetyClass.SAFE) {
            record("HUMAN_DEMO", "IGNORED_UNSAFE", host, before.routeSignature, element.label)
            return
        }
        var kind = SafeActionClassifier.inferActionKind(element)
        if (kind == ActionKind.UNKNOWN && PopupDismissal.isSafeDismissLabel(element.label)) kind = ActionKind.EXPAND
        if (kind == ActionKind.UNKNOWN) kind = ActionKind.NAVIGATE
        val edgeId = ("human|${before.fingerprint}|${kind.name}|${element.label.lowercase()}|${element.href.orEmpty()}").hashCode().toUInt().toString(16)
        val edge = SiteEdge(
            id = edgeId,
            fromFingerprint = before.fingerprint,
            toFingerprint = null,
            actionKind = kind,
            semanticIntent = "HUMAN_DEMO:${kind.name}:${element.label.take(100)}",
            label = element.label,
            safetyClass = SafetyClass.SAFE,
            locatorHints = element.locatorHints,
            expectedPageType = null,
            observedPostcondition = "demonstrated by user",
            confidence = 0.70,
            successCount = 1,
            failureCount = 0,
            lastVerifiedAt = System.currentTimeMillis()
        )
        brainRepository.recordTransition(host, before.fingerprint, edge, null)
        tracker?.recordVerified()
        verifiedThisSite++
        touchProgress()
        gapLogger.record("RESOLVED_BY_HUMAN", activeSite?.name.orEmpty(), host, before.routeSignature, "${kind.name}:${element.label}", resolvedByHuman = true)
        record("HUMAN_DEMO", "LEARNED", host, before.routeSignature, "${kind.name}:${element.label}")
        handler.postDelayed({ refreshTeachingSnapshot() }, 700L)
    }

    private fun handlePlateau(host: String, route: String) {
        consecutivePlateaus++
        if (consecutivePlateaus == 3 || consecutivePlateaus % 5 == 0) {
            gapLogger.record("PLATEAU", activeSite?.name.orEmpty(), host, route, "Repeated plateau $consecutivePlateaus")
        }
        val elapsed = System.currentTimeMillis() - siteStartedAt
        if (LearningPlateauPolicy.shouldMoveOn(consecutivePlateaus, actionsThisSite, verifiedThisSite, elapsed)) {
            record("SITE_PLATEAU", "MOVING_ON", host, route, "Plateau $consecutivePlateaus reached recovery limit; checkpointing and revisiting this site on a later pass")
            saveCheckpoint()
            moveToNextSite("plateau recovery limit reached")
            return
        }
        record("SITE_PLATEAU", "CHECKPOINTED", host, route, "Plateau $consecutivePlateaus; trying a different recovery path")
        saveCheckpoint()
        controller.markHumanResume()
        when {
            consecutivePlateaus % 3 == 0 -> activeSite?.startUrl?.takeIf { it.isNotBlank() }?.let { webView.loadUrl(it) }
            webView.canGoBack() && consecutivePlateaus % 2 == 0 -> webView.goBack()
            else -> webView.evaluateJavascript("(function(){window.scrollBy(0,Math.max(window.innerHeight*0.85,700));return 'SCROLLED';})();") {
                handler.postDelayed({ mapAndAct() }, 900L)
            }
        }
    }

    private fun recoverOrMove(reason: String) {
        consecutivePlateaus++
        record("RECOVERY", "RETRYING", activeSite?.expectedHost.orEmpty(), "", reason)
        controller.markHumanResume()
        handler.postDelayed({ webView.reload() }, 1200L)
    }

    private fun verifyAction() {
        if (stopped || userPaused || authScreenOpen || !actionInFlight || !controller.hasPendingExploration()) return
        controller.verifyPending(webView) { result ->
            actionInFlight = false
            result.onFailure {
                record("VERIFY", "ERROR", activeSite?.expectedHost.orEmpty(), "", it.message.orEmpty())
                handler.postDelayed({ mapAndAct() }, 700L)
            }.onSuccess { verified ->
                val host = verified.after.host
                if (!guard.accept(activeSessionId, host)) {
                    record("VERIFY", "STALE", host, verified.after.routeSignature, "Verification discarded by source-session guard")
                    return@onSuccess
                }
                val before = lastCoverage
                lastCoverage = verified.brain.coverageScore
                if (verified.after.challengeDetected || verified.after.loginDetected) {
                    waitForHuman(host, verified.after.routeSignature, "Protected boundary reached after action")
                    return@onSuccess
                }
                val outcome = if (verified.verification.success) "VERIFIED" else "NOT_VERIFIED"
                if (verified.verification.success) {
                    tracker?.recordVerified()
                    verifiedThisSite++
                    consecutivePlateaus = 0
                    touchProgress()
                }
                record("VERIFY", outcome, host, verified.after.routeSignature, verified.verification.evidence.joinToString("; "), before, lastCoverage)
                saveCheckpoint()
                updateCounters()
                handler.postDelayed({ mapAndAct() }, 700L)
            }
        }
    }

    private fun waitForHuman(host: String, route: String, reason: String) {
        waitingForHuman = true
        pauseButton.isEnabled = false
        resumeButton.isEnabled = true
        skipButton.isEnabled = true
        record("HUMAN_BOUNDARY", "WAITING_FOR_HUMAN", host, route, reason)
        val watchdogSeconds = LearningRuntimePolicy.noProgressAutoSkipMs / 1000L
        status.text = "${activeSite?.name ?: "Site"} needs you\\nComplete login/CAPTCHA and tap Resume. If nothing happens, the watchdog will checkpoint and auto-skip after $watchdogSeconds seconds."
        saveCheckpoint()
    }

    private fun moveToNextSite(reason: String, force: Boolean = false) {
        if (stopped || (!force && userPaused)) return
        if (!force) {
            val elapsed = (System.currentTimeMillis() - siteStartedAt).coerceAtLeast(0L)
            val remaining = LearningRuntimePolicy.minAutomaticSiteDwellMs - elapsed
            if (remaining > 0L) {
                // This is the final gate for every automatic rotation path. It protects against
                // stale recovery callbacks or unexpectedly fast "done" decisions elsewhere.
                if (!pendingAutomaticSiteMove) {
                    pendingAutomaticSiteMove = true
                    val sessionAtRequest = activeSessionId
                    record(
                        "SITE_MOVE_DEFERRED",
                        "DWELLING",
                        activeSite?.expectedHost.orEmpty(),
                        lastObservedSnapshot?.routeSignature.orEmpty(),
                        "Automatic move requested too early (${elapsed / 1000}s); holding site for another ${remaining / 1000}s"
                    )
                    handler.postDelayed({
                        pendingAutomaticSiteMove = false
                        if (!stopped && !userPaused && activeSessionId == sessionAtRequest) {
                            moveToNextSite(reason)
                        }
                    }, remaining)
                }
                return
            }
        }
        pendingAutomaticSiteMove = false
        val site = activeSite
        actionInFlight = false
        waitingForHuman = false
        authScreenOpen = false
        record("SITE_CHECKPOINT", "SAVED", site?.expectedHost.orEmpty(), "", reason)
        val beforePasses = tracker?.completedPasses ?: 0
        tracker?.nextSite()
        val afterPasses = tracker?.completedPasses ?: beforePasses
        if (afterPasses > beforePasses) {
            record(
                "SITE_PASS",
                "COMPLETED",
                site?.expectedHost.orEmpty(),
                "",
                "Completed full ${sites.size}-site sweep #$afterPasses; starting next deepening pass"
            )
        }
        saveCheckpoint()
        updateCounters()
        handler.postDelayed({ startCurrentSite() }, 900L)
    }

    private fun skipCurrentSite(auto: Boolean) {
        if (stopped) return
        val site = activeSite
        webView.stopLoading()
        awaitingInitialPage = true
        webView.visibility = View.INVISIBLE
        waitingForHuman = false
        authScreenOpen = false
        actionInFlight = false
        record(
            if (auto) "SITE_AUTO_SKIP" else "SITE_SKIP",
            "SKIPPED",
            site?.expectedHost.orEmpty(),
            "",
            if (auto) "Watchdog skip; learned knowledge retained" else "Skipped by user; learned knowledge retained"
        )
        moveToNextSite(if (auto) "no-progress watchdog" else "skipped by user", true)
    }

    private fun pauseLearning() {
        if (stopped) return
        userPaused = true
        pauseButton.isEnabled = false
        resumeButton.isEnabled = true
        record("LEARNING", "PAUSED", activeSite?.expectedHost.orEmpty(), "", "Paused by user")
        status.text = "Learning paused\nCheckpoint and logs are saved."
        saveCheckpoint()
    }

    private fun resumeLearning() {
        if (stopped || authScreenOpen) return
        val clearedHumanGate = waitingForHuman
        userPaused = false
        waitingForHuman = false
        pauseButton.isEnabled = true
        resumeButton.isEnabled = false
        skipButton.isEnabled = true
        controller.markHumanResume()
        touchProgress()
        CookieManager.getInstance().flush()

        if (clearedHumanGate) {
            val host = runCatching { Uri.parse(webView.url.orEmpty()).host.orEmpty() }.getOrDefault("")
            getSharedPreferences("site_brain_human_clearance", MODE_PRIVATE).edit()
                .putLong(host.lowercase(), System.currentTimeMillis())
                .apply()
            record("HUMAN_GATE", "CLEARED_BY_USER", host, lastObservedSnapshot?.routeSignature.orEmpty(), "Reusing site-issued cookies/session; continuing current page without forced reload")
            handler.postDelayed({
                if (!stopped && !userPaused && !authScreenOpen) {
                    waitForPageSettle {
                        tryDismissBlockingPopup { dismissed ->
                            if (!dismissed) mapAndAct()
                        }
                    }
                }
            }, 500L)
        } else {
            record("LEARNING", "RESUMED", activeSite?.expectedHost.orEmpty(), "", "Resumed by user")
            handler.postDelayed({ if (!stopped && !userPaused) mapAndAct() }, 350L)
        }
    }

    private fun stopLearning() {
        stopped = true
        userPaused = false
        handler.removeCallbacksAndMessages(null)
        webView.stopLoading()
        CookieManager.getInstance().flush()
        record("LEARNING", "STOPPED", activeSite?.expectedHost.orEmpty(), "", "Stopped by user; checkpoint retained")
        saveCheckpoint()
        persistLog(force = true)
        stopService(Intent(this, LearningKeepAliveService::class.java))
        pauseButton.isEnabled = false
        resumeButton.isEnabled = false
        skipButton.isEnabled = false
        status.text = "Learning stopped\nEverything learned so far is still saved."
    }

    private fun touchProgress() {
        lastProgressAt = System.currentTimeMillis()
    }

    private fun updateCounters() {
        if (!::counters.isInitialized) return
        val t = tracker ?: return
        val stalled = LearningRuntimePolicy.stalledForMs(System.currentTimeMillis(), lastProgressAt) / 1000
        counters.text = "Site ${t.siteIndex + 1}/${sites.size} · verified ${t.verifiedDiscoveries} · passes ${t.completedPasses} · logs ${events.size} retained / ${totalEventsThisRun} new · no-progress ${stalled}s"
    }

    private fun record(
        action: String,
        outcome: String,
        host: String,
        route: String,
        note: String,
        coverageBefore: Double = lastCoverage,
        coverageAfter: Double = lastCoverage
    ) {
        val site = activeSite
        totalEventsThisRun++
        unpersistedEventCount++
        events += LearningEvent(
            timestamp = System.currentTimeMillis(),
            source = site?.name ?: "Learning Run",
            host = host,
            pageType = "MAP_SITE",
            route = route,
            action = action,
            outcome = outcome,
            coverageBefore = coverageBefore,
            coverageAfter = coverageAfter,
            note = note
        )
        while (events.size > LearningRuntimePolicy.maxLogEvents) events.removeAt(0)
        persistLog()
        updateCounters()
    }

    private fun loadExistingLog() {
        runCatching {
            val file = File(filesDir, LOG_FILE)
            if (file.exists()) {
                events.addAll(LearningReportWriter.decode(file.readText()).takeLast(LearningRuntimePolicy.maxLogEvents))
                unpersistedEventCount = 0
                lastLogPersistAt = System.currentTimeMillis()
            }
        }
    }

    private fun persistLog(force: Boolean = false) {
        val now = System.currentTimeMillis()
        val eventsSince = unpersistedEventCount
        val elapsed = (now - lastLogPersistAt).coerceAtLeast(0L)
        if (!force && !LearningRuntimePolicy.shouldPersistLog(eventsSince, elapsed)) return
        val snapshot = events.toList()
        if (force) {
            runCatching {
                File(filesDir, LOG_FILE).writeText(LearningReportWriter.encode(snapshot, "site_brain_learning_run"))
                unpersistedEventCount = 0
                lastLogPersistAt = now
            }
            return
        }
        if (logWriteInFlight) return
        logWriteInFlight = true
        unpersistedEventCount = 0
        lastLogPersistAt = now
        logWriter.execute {
            runCatching {
                val target = File(filesDir, LOG_FILE)
                val temp = File(filesDir, "$LOG_FILE.tmp")
                temp.writeText(LearningReportWriter.encode(snapshot, "site_brain_learning_run"))
                if (!temp.renameTo(target)) {
                    target.writeText(temp.readText())
                    temp.delete()
                }
            }
            logWriteInFlight = false
        }
    }

    private fun saveCheckpoint() {
        val t = tracker ?: return
        getSharedPreferences("site_brain_learning_run", MODE_PRIVATE).edit()
            .putInt("site_index", t.siteIndex)
            .putInt("verified", t.verifiedDiscoveries)
            .putInt("passes", t.completedPasses)
            .putLong("saved_at", System.currentTimeMillis())
            .putLong("last_progress_at", lastProgressAt)
            .apply()
        persistLog()
    }

    private fun checkGitHubLogRequest(forceToast: Boolean = false) {
        if (logRequestCheckInFlight) return
        logRequestCheckInFlight = true
        Thread {
            val result = GitHubLogRequestClient.fetch()
            runOnUiThread {
                logRequestCheckInFlight = false
                result.onFailure {
                    if (forceToast) Toast.makeText(this, "Could not check GitHub log request right now.", Toast.LENGTH_LONG).show()
                }.onSuccess { request ->
                    if (request == null || !GitHubLogRequestClient.isNew(this, request) || pendingGitHubLogRequest?.requestId == request.requestId) {
                        if (forceToast) Toast.makeText(this, "No new GitHub log request.", Toast.LENGTH_SHORT).show()
                        return@onSuccess
                    }
                    pendingGitHubLogRequest = request
                    requestedLogsButton.text = "SEND LOGS"
                    record("GITHUB_LOG_REQUEST", "RECEIVED", activeSite?.expectedHost.orEmpty(), "", request.requestId)
                    Toast.makeText(this, "GitHub requested both Site Brain logs. Tap SEND BOTH REQUESTED LOGS.", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun shareRequestedLogs(request: GitHubLogRequest) {
        persistLog(force = true)
        val learning = File(filesDir, LOG_FILE)
        if (!learning.exists()) {
            Toast.makeText(this, "The learning log is not ready yet.", Toast.LENGTH_LONG).show()
            return
        }
        val bundle = runCatching {
            GitHubLogRequestClient.buildBundle(this, request, learning, gapLogger.fileOrNull())
        }.getOrElse {
            Toast.makeText(this, "Could not package the requested logs.", Toast.LENGTH_LONG).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", bundle)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_SUBJECT, "AI Browser Requested Logs ${request.requestId}")
            putExtra(Intent.EXTRA_TEXT, "GitHub request ${request.requestId}. Bundle contains the Site Brain learning log and AI capability-gap log when available.")
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("AI Browser requested logs", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        GitHubLogRequestClient.markHandled(this, request)
        pendingGitHubLogRequest = null
        requestedLogsButton.text = "GITHUB LOGS"
        record("GITHUB_LOG_REQUEST", "BUNDLED", activeSite?.expectedHost.orEmpty(), "", request.requestId)
        startActivity(Intent.createChooser(intent, "Send both requested logs"))
    }

    private fun saveLearningLogToDownloads() {
        persistLog(force = true)
        val file = File(filesDir, LOG_FILE)
        if (!file.exists()) {
            Toast.makeText(this, "No learning log file exists yet.", Toast.LENGTH_LONG).show()
            return
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, LOG_FILE)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = contentResolver.insert(collection, values)
        if (uri == null) {
            Toast.makeText(this, "Could not create the log in Downloads.", Toast.LENGTH_LONG).show()
            return
        }
        runCatching {
            contentResolver.openOutputStream(uri)?.use { output ->
                file.inputStream().use { input -> input.copyTo(output) }
            } ?: error("Could not open Downloads file")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
        }.onSuccess {
            Toast.makeText(this, "Saved $LOG_FILE to Downloads.", Toast.LENGTH_LONG).show()
        }.onFailure {
            contentResolver.delete(uri, null, null)
            Toast.makeText(this, "Could not save the learning log.", Toast.LENGTH_LONG).show()
        }
    }

    private fun shareLogs() {
        persistLog(force = true)
        val file = File(filesDir, LOG_FILE)
        if (!file.exists()) {
            Toast.makeText(this, "No learning log file exists yet.", Toast.LENGTH_LONG).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_SUBJECT, "AI Browser Site Brain Learning Logs")
            putExtra(Intent.EXTRA_TEXT, "Attached Site Brain learning log (${events.size} events).")
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("Site Brain learning log", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        Toast.makeText(this, "Learning log attached as a JSON file.", Toast.LENGTH_SHORT).show()
        startActivity(Intent.createChooser(intent, "Share / Save Learning Logs"))
    }

    private fun shareGapLog() {
        val file = gapLogger.fileOrNull()
        if (file == null) {
            Toast.makeText(this, "No AI capability gaps have been recorded yet.", Toast.LENGTH_LONG).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/x-ndjson"
            putExtra(Intent.EXTRA_SUBJECT, "AI Browser Capability Gap Log")
            putExtra(Intent.EXTRA_TEXT, "Separate Site Brain capability-gap log for the AI Teacher / Engineer.")
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("AI capability gap log", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share AI Capability Gap Log"))
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != AUTH_REQUEST) return
        authScreenOpen = false
        if (resultCode == RESULT_OK) {
            waitingForHuman = false
            pauseButton.isEnabled = true
            resumeButton.isEnabled = false
            controller.markHumanResume()
            touchProgress()
            record("HUMAN_AUTH", "RETURNED", activeSite?.expectedHost.orEmpty(), "", "Human sign-in completed; reloading target site")
            webView.reload()
        } else {
            waitingForHuman = true
            resumeButton.isEnabled = true
            status.text = "Sign-in screen closed\nTap Resume to retry, Skip Site, or let the watchdog move on."
        }
    }

    override fun onDestroy() {
        saveCheckpoint()
        persistLog(force = true)
        handler.removeCallbacksAndMessages(null)
        runCatching { CookieManager.getInstance().flush() }
        if (::webView.isInitialized) {
            runCatching { webView.stopLoading() }
            runCatching { webView.loadUrl("about:blank") }
            runCatching { webView.removeAllViews() }
            runCatching { webView.destroy() }
        }
        stopService(Intent(this, LearningKeepAliveService::class.java))
        super.onDestroy()
    }

    companion object {
        private const val WEBVIEW_HEARTBEAT_MS = 5 * 60 * 1000L
        private const val AUTH_REQUEST = 4201
        private const val LOG_FILE = "site_brain_learning_log.json"
        private const val WATCHDOG_POLL_MS = 5_000L
        private const val AI_TEACHER_COOLDOWN_MS = 45_000L
        private const val GITHUB_LOG_REQUEST_POLL_MS = 60_000L
    }
}
