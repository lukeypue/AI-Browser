package com.appgate.tv.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.appgate.brain.engine.BrainEngine
import com.appgate.brain.engine.EngineConfig
import com.appgate.brain.engine.LearningGuide
import com.appgate.brain.engine.EngineEvents
import com.appgate.brain.engine.EngineMode
import com.appgate.brain.engine.LearningSession
import com.appgate.brain.engine.SearchCoordinator
import com.appgate.brain.engine.SearchOutcome
import com.appgate.brain.json.Json
import com.appgate.brain.json.JsonObject
import com.appgate.brain.memory.Consolidation
import com.appgate.brain.memory.FileBrainStorage
import com.appgate.brain.memory.Memory
import com.appgate.brain.model.Goal
import com.appgate.brain.model.TaskLedger
import com.appgate.brain.model.TaskResult
import com.appgate.brain.model.TaskStatus
import com.appgate.brain.model.VerifyStatus
import com.appgate.brain.planner.Planner
import com.appgate.brain.planner.PlannerClients
import com.appgate.brain.profile.SiteProfiles
import com.appgate.brain.skills.SkillCompiler
import com.appgate.brain.perception.SpsParser
import com.appgate.tv.browser.BrainRuntime
import com.appgate.tv.browser.BrainSession
import com.appgate.tv.browser.GeckoRenderer
import com.appgate.tv.store.DiagnosticsLog
import com.appgate.tv.store.PlannerKeyStore
import com.appgate.tv.ui.BrowserActivity
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * The one process-wide owner of the browser engine and the brain.
 *
 * Runs as a foreground service so the renderer, the engine thread and the task ledger keep
 * going with the screen off, survive Activity destruction, and can hand the live session to
 * an on-screen view whenever a person is needed (sign-in, verification, teaching) — without
 * ever copying a cookie.
 */
class BrainService : Service() {

    enum class Mode { IDLE, SEARCHING, LEARNING, BROWSING, NEED_HUMAN, NEED_GRANT, PAUSED }

    data class State(
        val mode: Mode = Mode.IDLE,
        val status: String = "Ready",
        val host: String = "",
        val url: String = "",
        val humanReason: String = "",
        val grantPreview: String = "",
        val grantLedgerId: String = "",
        val searchId: String = "",
        val ledgerIds: List<String> = emptyList(),
        val engineReady: Boolean = false,
        val learningGuide: LearningGuide? = null
    )

    interface Listener {
        fun onState(state: State) {}
        fun onStep(description: String, status: VerifyStatus?) {}
        fun onSearchOutcome(outcome: SearchOutcome) {}
        fun onLog(line: String) {}
    }

    inner class LocalBinder : Binder() { val service: BrainService get() = this@BrainService }

    private val binder = LocalBinder()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = ArrayList<Listener>()
    private val engineThread: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "brain-engine").apply { isDaemon = true } }
    private var job: Future<*>? = null
    private var wakeLock: PowerManager.WakeLock? = null

    lateinit var memory: Memory
        private set
    lateinit var diagnostics: DiagnosticsLog
        private set
    var session: BrainSession? = null
        private set
    private var engine: BrainEngine? = null
    private var coordinator: SearchCoordinator? = null
    private var learning: LearningSession? = null
    @Volatile private var timedTest: com.appgate.brain.engine.TimedLearningTest? = null
    private val executingTest = ThreadLocal<com.appgate.brain.engine.TimedLearningTest?>()
    private val testDeadline = Runnable { finishTimedTest("deadline", stop = true) }
    fun learningTestSummary(): String = getSharedPreferences("brain_jobs", MODE_PRIVATE).getString("test_summary", "No timed test completed yet.")!!
    private var currentGoal: Goal? = null
    private var currentLedgers: List<TaskLedger> = emptyList()
    private var lastOutcome: SearchOutcome? = null
    private var pendingPrepare: Pair<String, String>? = null
    @Volatile private var lastProgressAt = 0L
    @Volatile var state = State()
        private set
    var activityProvider: () -> Activity? = { null }
        set(value) { field = value; session?.activityProvider = value }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate() {
        super.onCreate()
        createChannels()
        startForeground(NOTIFICATION_ID, buildNotification("Starting the browser engine…"))
        memory = Memory(FileBrainStorage(File(filesDir, "brain")))
        diagnostics = DiagnosticsLog(this)
        val savedTest = getSharedPreferences("brain_jobs", MODE_PRIVATE)
        if (savedTest.getBoolean("test_running", false)) {
            savedTest.edit().putBoolean("test_running", false).putString("test_summary", "Previous test was interrupted when the service ended. Existing diagnostics are available; start a new timed test.").apply()
            diagnostics.event("learning_test_interrupted")
        }
        val runtime = BrainRuntime.get(this)
        BrainRuntime.whenExtensionReady { ext ->
            if (ext == null) { publish(state.copy(status = "The Site Brain bridge failed to install. Please reinstall the app.")); return@whenExtensionReady }
            val s = BrainSession(this, runtime, ext)
            s.activityProvider = activityProvider
            s.listener = sessionListener
            session = s
            val renderer = GeckoRenderer(s)
            val eng = BrainEngine(renderer, memory, { plannerOrNull() }, engineEvents, EngineConfig())
            engine = eng
            coordinator = SearchCoordinator(eng, memory, engineEvents)
            publish(state.copy(engineReady = true, status = "Ready"))
            main.postDelayed(watchdog, WATCHDOG_MS)
            engineThread.execute { runCatching { Consolidation(memory).runAll() } }
        }
        lastProgressAt = System.currentTimeMillis()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> pause()
            ACTION_STOP -> stopWork()
            ACTION_RESUME -> resumeAfterHuman()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        main.removeCallbacks(watchdog)
        finishTimedTest("service ended", stop = true)
        engine?.requestStop()
        job?.cancel(true)
        engineThread.shutdownNow()
        releaseWakeLock()
        session?.close()
        super.onDestroy()
    }

    /** Android 15+ time limit for dataSync services: checkpoint and stop cleanly instead of being killed. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        diagnostics.event("service_timeout", detail = "foreground service time limit reached; work paused")
        pause()
        stopSelf()
    }

    // ------------------------------------------------------------------ public API (main thread)

    fun addListener(l: Listener) { listeners += l; l.onState(state) }
    fun removeListener(l: Listener) { listeners -= l }

    fun currentOutcome(): SearchOutcome? = lastOutcome
    fun ledgers(): List<TaskLedger> = currentLedgers

    /** Deep search: one goal across the sources that fit it. */
    fun startSearch(goal: Goal, preferredSources: List<String>): String? {
        val coord = coordinator ?: return null
        if (job?.isDone == false) return null
        finishTimedTest("search started", stop = false)
        learning = null
        val sources = SiteProfiles.sourcesFor(goal, preferredSources)
        val ledgers = coord.newLedgers(goal, sources)
        currentGoal = goal
        currentLedgers = ledgers
        val searchId = "s" + System.currentTimeMillis()
        getSharedPreferences("brain_jobs", MODE_PRIVATE).edit().putString("last_search_goal", goal.toJson().toString()).putString("last_search_ledgers", ledgers.joinToString(",") { it.id }).apply()
        publish(state.copy(mode = Mode.SEARCHING, status = "Searching ${sources.first().name}…", searchId = searchId, ledgerIds = ledgers.map { it.id }, host = ledgers.first().host))
        diagnostics.event("search_start", detail = goal.constraints.joinToString(";") { it.key + it.op.name })
        runJob { runSearch(goal, ledgers) }
        return searchId
    }

    private fun runSearch(goal: Goal, ledgers: List<TaskLedger>) {
        val eng = engine ?: return
        // One site after another on the single renderer; publish interim results after each site so the
        // person sees listings as soon as the first site is done, not after the last.
        for (ledger in ledgers) {
            if (ledger.done) continue
            engineEvents.status("Searching ${ledger.host}…")
            val out = eng.runTask(ledger, EngineMode.ASSIST)
            val interim = com.appgate.brain.engine.SearchMerge.outcome(goal, ledgers)
            lastOutcome = interim
            main.post { listeners.forEach { it.onSearchOutcome(interim) } }
            if (out.status == TaskStatus.NEED_HUMAN || out.status == TaskStatus.NEED_GRANT || out.status == TaskStatus.PAUSED) break
        }
        val outcome = com.appgate.brain.engine.SearchMerge.outcome(goal, ledgers)
        lastOutcome = outcome
        val blocked = ledgers.firstOrNull { it.status == TaskStatus.NEED_HUMAN || it.status == TaskStatus.NEED_GRANT }
        main.post {
            listeners.forEach { it.onSearchOutcome(outcome) }
            when {
                blocked?.status == TaskStatus.NEED_HUMAN -> publish(state.copy(mode = Mode.NEED_HUMAN, humanReason = blocked.humanReason, host = blocked.host, status = "${blocked.host}: ${blocked.humanReason}"))
                blocked?.status == TaskStatus.NEED_GRANT -> publish(state.copy(mode = Mode.NEED_GRANT, grantPreview = blocked.previewText, grantLedgerId = blocked.id, status = "Waiting for your approval"))
                ledgers.any { it.status == TaskStatus.PAUSED } -> publish(state.copy(mode = Mode.PAUSED, status = "Paused"))
                else -> publish(state.copy(mode = Mode.IDLE, status = "Search finished: ${outcome.merged.verified.size} verified, ${outcome.merged.partial.size} possible, ${outcome.merged.nearMiss.size} near misses"))
            }
        }
    }

    /** Continue whatever was waiting on a person (sign-in / verification / approval). */
    fun resumeAfterHuman() {
        if (timedTest?.expired(android.os.SystemClock.elapsedRealtime()) == true) { finishTimedTest("deadline", stop = true); return }
        val s = session ?: return
        s.closePopups()
        val reviewedHost = com.appgate.brain.perception.UrlPatterns.host(s.currentUrl).removePrefix("www.")
        memory.knownHosts().filter { it.removePrefix("www.") == reviewedHost }.forEach { host ->
            val model = memory.site(host)
            model.learningNeedsHuman = false
            model.learningBlockedUntil = 0
            memory.saveSite(model)
        }
        val goal = currentGoal
        val ledgers = currentLedgers
        if (job?.isDone == false) { publish(state.copy(humanReason = "")); return }   // still running: nothing to resume
        when {
            learning != null -> {
                publish(state.copy(mode = Mode.LEARNING, status = "Resuming learning…", humanReason = ""))
                runJob { learning?.pausedLedger = null; runLearningLoop() }
            }
            goal != null && ledgers.any { !it.done } -> {
                ledgers.filter { it.status == TaskStatus.NEED_HUMAN || it.status == TaskStatus.PAUSED }.forEach { it.status = TaskStatus.RUNNING; memory.saveLedger(it) }
                publish(state.copy(mode = Mode.SEARCHING, status = "Resuming search…", humanReason = ""))
                runJob { runSearch(goal, ledgers) }
            }
            pendingPrepare != null -> { val (url, text) = pendingPrepare!!; pendingPrepare = null; prepareMessage(url, text) }
            else -> publish(state.copy(mode = Mode.IDLE, status = "Ready", humanReason = ""))
        }
    }

    /** The user approved the previewed message: issue a single-use grant and let the engine commit. */
    fun grantSend(ledgerId: String) {
        val eng = engine ?: return
        val ledger = currentLedgers.firstOrNull { it.id == ledgerId } ?: memory.loadLedger(ledgerId) ?: return
        eng.grant(ledger)
        session?.openNetworkGrant(120_000L)
        publish(state.copy(mode = Mode.SEARCHING, status = "Sending…", grantPreview = "", grantLedgerId = ""))
        runJob {
            val out = eng.runTask(ledger, EngineMode.COMMIT)
            session?.closeNetworkGrant()
            main.post {
                publish(state.copy(mode = if (out.status == TaskStatus.DONE) Mode.IDLE else Mode.NEED_HUMAN,
                    status = if (out.status == TaskStatus.DONE) "Message sent." else "Could not send: ${out.notes.lastOrNull() ?: out.status}"))
            }
        }
    }

    fun declineSend(ledgerId: String) {
        val ledger = currentLedgers.firstOrNull { it.id == ledgerId } ?: memory.loadLedger(ledgerId)
        if (ledger != null) { ledger.status = TaskStatus.ABANDONED; ledger.note("user declined to send"); memory.saveLedger(ledger) }
        publish(state.copy(mode = Mode.IDLE, status = "Not sent.", grantPreview = "", grantLedgerId = ""))
    }

    /** Open a listing and fill (never send) a message; the user approves in the browser screen. */
    fun prepareMessage(url: String, text: String) {
        val eng = engine ?: return
        if (job?.isDone == false) { pendingPrepare = url to text; return }
        finishTimedTest("message task started", stop = false)
        learning = null
        val host = com.appgate.brain.perception.UrlPatterns.host(url)
        val goal = Goal(id = "m" + System.currentTimeMillis(), intent = com.appgate.brain.model.GoalIntent.PREPARE_MESSAGE, rawText = "message seller", query = "", constraints = emptyList(), messageDraft = text, targetUrl = url)
        val ledger = TaskLedger("m" + System.currentTimeMillis(), goal, host, url)
        currentGoal = goal
        currentLedgers = listOf(ledger)
        publish(state.copy(mode = Mode.SEARCHING, status = "Opening the listing to write your message…", host = host))
        runJob {
            val out = eng.runTask(ledger, EngineMode.ASSIST)
            main.post {
                when (out.status) {
                    TaskStatus.NEED_GRANT -> publish(state.copy(mode = Mode.NEED_GRANT, grantPreview = out.previewText, grantLedgerId = out.id, status = "Review the message and approve to send"))
                    TaskStatus.NEED_HUMAN -> publish(state.copy(mode = Mode.NEED_HUMAN, humanReason = out.humanReason, status = out.humanReason))
                    else -> publish(state.copy(mode = Mode.IDLE, status = out.notes.lastOrNull() ?: out.status.name))
                }
            }
        }
    }

    /** A person wants to browse / sign in: no engine, just the live session on screen. */
    fun startBrowsing(url: String) {
        val s = session ?: return
        if (job?.isDone == false) pause()
        if (timedTest != null) { finishTimedTest("browser opened", stop = false); learning = null }
        s.setNetworkMode("OFF", emptyList(), emptyList())
        s.loadUri(url)
        publish(state.copy(mode = Mode.BROWSING, status = "Browsing", host = com.appgate.brain.perception.UrlPatterns.host(url), url = url))
    }

    fun startLearning(testMinutes: Int? = null) {
        require(testMinutes == null || testMinutes == 30 || testMinutes == 60)
        val eng = engine ?: return
        if (job?.isDone == false) return
        finishTimedTest("replaced", stop = false)
        if (testMinutes != null) {
            timedTest = com.appgate.brain.engine.TimedLearningTest(testMinutes, android.os.SystemClock.elapsedRealtime())
            main.postDelayed(testDeadline, testMinutes * 60_000L)
            getSharedPreferences("brain_jobs", MODE_PRIVATE).edit().putBoolean("test_running", true).putString("test_summary", "$testMinutes-minute test running. Pauses and human review count toward the time limit.").apply()
            diagnostics.event("learning_test_start", extra = JsonObject().put("minutes", testMinutes))
        }
        val includeAccountSites = getSharedPreferences("settings", MODE_PRIVATE).getBoolean("learn_account_sites", false)
        val sites = SiteProfiles.training.filter { includeAccountSites || !it.requiresLogin }
        val sess = LearningSession(eng, memory, engineEvents, sites)
        sess.siteIndex = getSharedPreferences("brain_jobs", MODE_PRIVATE).getInt("learning_site_index", 0)
        learning = sess
        publish(state.copy(mode = Mode.LEARNING, status = "Overnight learning started"))
        diagnostics.event("learning_start")
        runJob { runLearningLoop() }
    }

    private fun runLearningLoop() {
        val sess = learning ?: return
        val needs = sess.run()
        getSharedPreferences("brain_jobs", MODE_PRIVATE).edit().putInt("learning_site_index", sess.siteIndex).apply()
        main.post {
            if (learning !== sess || state.mode == Mode.BROWSING) return@post
            if (needs != null) publish(state.copy(mode = Mode.NEED_HUMAN, humanReason = needs.humanReason, host = needs.host, status = "${needs.host}: ${needs.humanReason}"))
            else if (sess.stopRequested) publish(state.copy(mode = Mode.PAUSED, status = "Learning paused"))
            else { learning = null; finishTimedTest("session ended", stop = false); publish(state.copy(mode = Mode.IDLE, status = "Learning session ended; verified lessons are saved")) }
        }
    }

    fun stopLearning() {
        finishTimedTest("stopped", stop = false)
        engine?.requestStop()
        learning?.stop()
        learning = null
        publish(state.copy(mode = Mode.IDLE, status = "Learning stopped; everything learned is saved"))
    }

    fun pause() {
        engine?.requestStop()
        learning?.stop()
        publish(state.copy(mode = Mode.PAUSED, status = "Pausing after the current step…"))
    }

    fun stopWork() {
        finishTimedTest("stopped", stop = false)
        engine?.requestStop()
        learning?.stop()
        learning = null
        job?.cancel(true)
        publish(state.copy(mode = Mode.IDLE, status = "Stopped"))
    }

    private fun finishTimedTest(reason: String, stop: Boolean) {
        val run = timedTest ?: return
        timedTest = null
        main.removeCallbacks(testDeadline)
        val result = run.finish(android.os.SystemClock.elapsedRealtime(), reason)
        val summary = "Test ended ($reason) after ${result.elapsedMs / 60_000} minutes: ${result.tasks} completed attempts, ${result.verified} verified actions, ${result.failed} failed actions, ${result.verifiedSkills} verified procedure uses. Repeated uses are not new lessons. Save diagnostics for review."
        getSharedPreferences("brain_jobs", MODE_PRIVATE).edit().putBoolean("test_running", false).putString("test_summary", summary).apply()
        diagnostics.event("learning_test_end", extra = JsonObject().put("reason", reason).put("elapsed_ms", result.elapsedMs)
            .put("tasks", result.tasks).put("verified_actions", result.verified).put("failed_actions", result.failed).put("verified_procedure_uses", result.verifiedSkills))
        releaseWakeLock()
        if (stop) {
            engine?.requestStop()
            learning?.stop()
            learning = null
            job?.cancel(true)
            publish(state.copy(mode = Mode.IDLE, status = summary))
            updateNotification("Learning test completed; diagnostics ready")
        }
    }

    fun setTeachMode(on: Boolean) {
        val s = session ?: return
        if (on) {
            publish(state.copy(status = "Preparing teaching — wait until the recorder is ready…"))
            engineThread.execute {
                teachTrace.clear()
                teachBefore = null
                val before = runCatching {
                    val observation = s.request(JsonObject().put("cmd", "observe"), 8_000L).optObject("observation") ?: return@runCatching null
                    val host = com.appgate.brain.perception.UrlPatterns.host(observation.optString("url"))
                    SpsParser(memory.site(host).facetVocabulary).parse(observation)
                }.getOrNull()
                if (before == null || before.isHumanOnly) {
                    main.post { publish(state.copy(status = "Teaching is unavailable here. Finish sign-in or verification first.")) }
                    return@execute
                }
                teachBefore = before
                s.teachListener = { event -> onTeachEvent(event) }
                val enabled = runCatching { s.request(JsonObject().put("cmd", "teach").put("on", true), 5_000L).optBoolean("ok") }.getOrDefault(false)
                if (!enabled) { s.teachListener = null; teachBefore = null }
                main.post { publish(state.copy(status = if (enabled) "Teaching recorder ready: demonstrate the step, then tap DONE TEACHING" else "Teaching recorder could not start. Try again.")) }
            }
        } else {
            engineThread.execute {
                runCatching { s.request(JsonObject().put("cmd", "teach").put("on", false), 5_000L) }
                s.teachListener = null
                finishTeaching()
            }
        }
    }

    // ------------------------------------------------------------------ teaching (human demonstration)

    private val teachTrace = com.appgate.brain.json.JsonArray()
    private var teachBefore: com.appgate.brain.model.SemanticPageState? = null

    private fun onTeachEvent(event: JsonObject) {
        val raw = event.optObject("element") ?: return
        engineThread.execute {
            val s = session ?: return@execute
            val host = com.appgate.brain.perception.UrlPatterns.host(event.optString("url"))
            val site = memory.site(host)
            val parser = SpsParser(site.facetVocabulary)
            val before = teachBefore ?: return@execute
            if (!com.appgate.brain.perception.UrlPatterns.sameSite(before.url, event.optString("url"))) return@execute
            val single = JsonObject().put("v", 3).put("url", event.optString("url")).put("host", host).put("elements", com.appgate.brain.json.JsonArray().add(raw)).put("signals", JsonObject()).put("regions", com.appgate.brain.json.JsonArray())
            val classified = parser.parse(single).affordances.firstOrNull()
            if (classified != null && !classified.isCommit && classified.role !in setOf(com.appgate.brain.model.Role.LOGIN, com.appgate.brain.model.Role.ACCOUNT, com.appgate.brain.model.Role.UNKNOWN)) {
                teachTrace.add(JsonObject().put("kind", event.optString("kind")).put("role", classified.role.name).put("facet", classified.facetKey)
                    .put("submit", event.optBoolean("submit")).put("has_value", event.optStringOrNull("value") != null))
                diagnostics.event("teach", host, "${classified.role}${classified.facetKey?.let { "[$it]" } ?: ""}")
                main.post { listeners.forEach { it.onLog("Recorded: ${classified.role.name.lowercase().replace('_', ' ')}${classified.facetKey?.let { " ($it)" } ?: ""}") } }
            }
        }
    }

    /** Called on the same executor after all previously queued trace events. */
    private fun finishTeaching() {
        val s = session ?: return
        val before = teachBefore
        if (before == null || teachTrace.size == 0) {
            teachBefore = null
            main.post { publish(state.copy(status = "No supported step was recorded. Tap TEACH and wait for recorder ready before demonstrating.")) }
            return
        }
        val profile = SiteProfiles.forHost(before.host) ?: SiteProfiles.generic(before.host)
        val host = memory.site(profile.hosts.first()).host
        val after = runCatching { SpsParser(memory.site(host).facetVocabulary).parse(s.request(JsonObject().put("cmd", "observe"), 8_000L).optObject("observation")!!) }.getOrNull()
        val skill = after?.let { SkillCompiler.compileFromDemonstration(memory, null, host, teachTrace, before, it, System.currentTimeMillis()) }
        teachTrace.clear()
        teachBefore = null
        if (skill == null) {
            main.post { publish(state.copy(status = "Could not make a safe training procedure. Demonstrate one focused lesson on the same site.")) }
            return
        }
        val eng = engine ?: return
        val capability = skill.tags.firstOrNull { it.startsWith("capability:") }?.removePrefix("capability:") ?: return
        val site = memory.site(host)
        val goal = com.appgate.brain.engine.Curriculum.nextGoal(site, profile, 0, before, capability)
        val task = TaskLedger("demo-check-" + System.currentTimeMillis(), goal, host, before.url, lesson = capability)
        main.post { publish(state.copy(mode = Mode.LEARNING, status = "Checking your demonstration with practice values…", host = host)) }
        runJob {
            val out = eng.runTask(task, EngineMode.TRAIN)
            com.appgate.brain.engine.Curriculum.recordAttempt(site, out)
            memory.saveSite(site)
            val checked = memory.skills.get(skill.id)
            val message = when {
                checked?.tags?.contains("verified_v2") == true -> "Demonstration verified and learned. Resume when ready."
                checked?.stat(host)?.failures?.let { it > 0.0 } == true -> "Demonstration replay did not verify. Review the lesson and last result, then try teaching again."
                else -> "Demonstration recorded; its starting controls were not reached for a check. Teach from the results page or resume learning."
            }
            main.post { publish(state.copy(mode = if (out.status == TaskStatus.NEED_HUMAN) Mode.NEED_HUMAN else Mode.PAUSED,
                status = message, humanReason = out.humanReason)) }
        }
    }

    // ------------------------------------------------------------------ internals

    private fun plannerOrNull(): Planner? {
        if (!PlannerKeyStore.teacherEnabled(this) || !memory.teacherBudget.snapshot(PlannerKeyStore.unrestricted(this)).allowed) return null
        val config = PlannerKeyStore.config(this) ?: return null
        val observer = com.appgate.brain.planner.BudgetedTeacher(memory.teacherBudget, { PlannerKeyStore.teacherEnabled(this) }, { PlannerKeyStore.unrestricted(this) }) { usage ->
            diagnostics.event("teacher_usage", "", "AI requests=${usage.requests24h} reported=${usage.reportedRequests}",
                JsonObject().put("requests_24h", usage.requests24h).put("requests_hour", usage.requestsHour)
                    .put("input_tokens", usage.inputTokens).put("output_tokens", usage.outputTokens)
                    .put("reported_requests", usage.reportedRequests).put("model", usage.lastModel)
                    .put("unrestricted", PlannerKeyStore.unrestricted(this)))
            main.post { publish(state.copy()) }
        }
        return Planner(PlannerClients.create(config, observer))
    }

    private fun runJob(block: () -> Unit) {
        acquireWakeLock()
        lastProgressAt = System.currentTimeMillis()
        val owner = timedTest
        job = engineThread.submit {
            executingTest.set(owner)
            try { block() }
            catch (t: InterruptedException) { Thread.currentThread().interrupt() }
            catch (t: Throwable) { Log.e(TAG, "job failed", t); diagnostics.event("job_error", detail = t.toString()); main.post { if (owner == null || timedTest === owner) publish(state.copy(mode = Mode.IDLE, status = "Stopped: ${t.javaClass.simpleName}")) } }
            finally { executingTest.remove(); main.post { if (job?.isDone != false) releaseWakeLock() } }
        }
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (timedTest?.expired(android.os.SystemClock.elapsedRealtime()) == true) finishTimedTest("deadline", stop = true)
            val running = job?.isDone == false
            val learner = learning
            // Read the published waiting flag before the baseline written when waiting ends.
            val waiting = learner?.isWaiting == true
            val activeProgressAt = maxOf(lastProgressAt, learner?.activeSince ?: 0L)
            if (running && !waiting && System.currentTimeMillis() - activeProgressAt > STALL_MS) {
                lastProgressAt = System.currentTimeMillis()
                if (learner != null && state.mode == Mode.LEARNING) {
                    // Overnight learning must be self-healing. Abort only the wedged lesson,
                    // recover the renderer, and immediately continue with the next source.
                    // Do not stop the LearningSession: its siteIndex is the durable rotation cursor.
                    diagnostics.event("watchdog", detail = "no verified progress for ${STALL_MS / 1000}s; recovering learner and rotating source")
                    engine?.requestStop()
                    job?.cancel(true)
                    session?.recover()
                    learner.siteIndex++
                    getSharedPreferences("brain_jobs", MODE_PRIVATE).edit()
                        .putInt("learning_site_index", learner.siteIndex).apply()
                    publish(state.copy(mode = Mode.LEARNING, status = "Recovered a stalled lesson; moving to the next site…"))
                    main.postDelayed({
                        if (learning === learner && state.mode == Mode.LEARNING && job?.isDone != false) {
                            engine?.clearStop()
                            runJob { runLearningLoop() }
                        }
                    }, 1_500L)
                } else {
                    diagnostics.event("watchdog", detail = "no verified progress for ${STALL_MS / 1000}s; pausing work")
                    engine?.requestStop()
                    job?.cancel(true)
                    session?.recover()
                    publish(state.copy(mode = Mode.PAUSED, status = "Paused after four minutes without new verified progress. You can review the page and resume."))
                }
            }
            main.postDelayed(this, WATCHDOG_MS)
        }
    }

    private val engineEvents = object : EngineEvents {
        override fun diagnostic(host: String, kind: String, data: JsonObject) { diagnostics.event(kind, host, extra = data) }
        override fun progress(ledger: TaskLedger, reason: String) {
            lastProgressAt = System.currentTimeMillis()
            diagnostics.event("verified_progress", ledger.host, reason)
        }
        override fun guide(explanation: LearningGuide) {
            val owner = executingTest.get()
            main.post {
                if (owner != null && timedTest !== owner) return@post
                val previous = state.learningGuide
                val next = if (previous?.ledgerId == explanation.ledgerId) explanation.copy(outcome = previous.outcome) else explanation
                publish(state.copy(learningGuide = next))
            }
        }
        override fun status(text: String) {
            val owner = executingTest.get()
            main.post { if (owner == null || timedTest === owner) { publish(state.copy(status = text)); updateNotification(text) } }
        }
        override fun step(ledger: TaskLedger, description: String, status: VerifyStatus?) {
            // Descriptions may contain the user's query; diagnostics keep typed coordinates only.
            diagnostics.event("step", ledger.host, extra = JsonObject().put("status", status?.name).put("phase", ledger.phase.name).put("source", ledger.programSource))
            val testOwner = executingTest.get()
            val explanation = if (ledger.goal.intent == com.appgate.brain.model.GoalIntent.LEARN_SITE) LearningGuide.snapshot(ledger, ledger.currentProgram.getOrNull(ledger.cursor), status, ledger.steps.lastOrNull()?.evidence.orEmpty()) else null
            main.post {
                if (testOwner != null && timedTest !== testOwner) return@post
                if (timedTest === testOwner && ledger.goal.intent == com.appgate.brain.model.GoalIntent.LEARN_SITE) testOwner?.step(status)
                if (explanation != null) {
                    val previous = state.learningGuide
                    val next = if (previous?.ledgerId == explanation.ledgerId && explanation.pass.contains("not supplied")) explanation.copy(pass = previous.pass) else explanation
                    publish(state.copy(learningGuide = next))
                }
                listeners.forEach { it.onStep(description, status) }
            }
        }
        override fun needHuman(ledger: TaskLedger, reason: String, url: String) {
            diagnostics.event("need_human", ledger.host, reason)
            val owner = executingTest.get()
            val learner = learning
            main.post {
                if (owner != null && timedTest !== owner) return@post
                if (learner == null) publish(state.copy(mode = Mode.NEED_HUMAN, humanReason = reason, host = ledger.host, url = url, status = "${ledger.host}: $reason"))
                notifyHuman(ledger.host, reason)
            }
        }
        override fun needGrant(ledger: TaskLedger, previewText: String, previewHash: String) {
            diagnostics.event("need_grant", ledger.host)
            main.post { publish(state.copy(mode = Mode.NEED_GRANT, grantPreview = previewText, grantLedgerId = ledger.id, status = "Review and approve the message")); notifyHuman(ledger.host, "A message is ready for your approval") }
        }
        override fun finished(ledger: TaskLedger, result: TaskResult?) {
            if (ledger.goal.intent == com.appgate.brain.model.GoalIntent.LEARN_SITE) {
                val id = ledger.id
                val verifiedUses = ledger.successfulSkills.size
                val testOwner = executingTest.get()
                main.post { if (timedTest === testOwner) testOwner?.task(id, verifiedUses) }
                val outcome = if (ledger.lesson in ledger.successfulSkills) "Verified procedure saved for this lesson." else "Attempt ended without verifying this lesson. ${ledger.status.name.lowercase().replace('_', ' ')}."
                main.post { if (testOwner != null && timedTest !== testOwner) return@post; state.learningGuide?.takeIf { it.ledgerId == id }?.let { publish(state.copy(learningGuide = it.copy(outcome = "$outcome Last step: ${it.outcome}"))) } }
            }
            diagnostics.event("task_finished", ledger.host, "${ledger.status} actions=${ledger.actions} llm=${ledger.llmCalls} items=${ledger.verdicts.size}",
                JsonObject().put("reason", ledger.terminalReason).put("elapsed_ms", ledger.elapsedMs).put("decisions", ledger.decisions).put("verified_skills", ledger.successfulSkills.size))
        }
        override fun log(level: String, message: String) {
            if (level != "debug") diagnostics.event("log_$level", detail = message)
            main.post { listeners.forEach { it.onLog(message) } }
        }
    }

    private val sessionListener = object : BrainSession.Listener {
        override fun onLocation(url: String) { main.post { publish(state.copy(url = url)) } }
        override fun onRendererGone(reason: String) {
            diagnostics.event("renderer_gone", detail = reason)
            main.post { publish(state.copy(status = "Browser restarted after a crash; continuing…")) }
        }
        override fun onNetEvent(event: JsonObject) {
            val type = event.optString("type")
            if (type == "NET_BLOCKED" || type == "NET_UNKNOWN_MUTATION") diagnostics.event(type.lowercase(), detail = event.optString("key") + " " + event.optString("op"))
            if (type == "NET_UNKNOWN_MUTATION" && state.mode == Mode.BROWSING) {
                // A person is doing something the site persists: remember the endpoint as a candidate for classification.
                val host = com.appgate.brain.perception.UrlPatterns.host(state.url)
                if (host.isNotBlank()) engineThread.execute {
                    val site = memory.site(host)
                    site.quirks.add("mutation:" + event.optString("key").take(80))
                    memory.saveSite(site)
                }
            }
        }
    }

    private fun publish(newState: State) {
        state = newState
        listeners.forEach { it.onState(newState) }
        updateNotification(newState.status)
    }

    // ------------------------------------------------------------------ notifications & wake lock

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL_WORK, "AI Browser working", NotificationManager.IMPORTANCE_LOW).apply { description = "Shows what the Site Brain is doing." })
        nm.createNotificationChannel(NotificationChannel(CHANNEL_HUMAN, "AI Browser needs you", NotificationManager.IMPORTANCE_HIGH).apply { description = "Sign-in, verification or approval needed." })
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, BrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pause = PendingIntent.getService(this, 1, Intent(this, BrainService::class.java).setAction(ACTION_PAUSE), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 2, Intent(this, BrainService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_WORK)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("AI Browser")
            .setContentText(text.take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(400)))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Pause", pause)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        runCatching { nm.notify(NOTIFICATION_ID, buildNotification(text)) }
    }

    private fun notifyHuman(host: String, reason: String) {
        val open = PendingIntent.getActivity(this, 3, Intent(this, BrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, CHANNEL_HUMAN)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("$host needs you")
            .setContentText(reason)
            .setAutoCancel(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(HUMAN_NOTIFICATION_ID, n) }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AI Browser:brain").apply { setReferenceCounted(false); acquire(10 * 60 * 60 * 1000L) }
    }

    private fun releaseWakeLock() {
        // Keep the monotonic deadline running even while a timed test is paused.
        if (timedTest != null) return
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        wakeLock = null
    }

    companion object {
        private const val TAG = "BrainService"
        const val CHANNEL_WORK = "brain_work"
        const val CHANNEL_HUMAN = "brain_human"
        const val NOTIFICATION_ID = 6607
        const val HUMAN_NOTIFICATION_ID = 6608
        const val ACTION_PAUSE = "com.appgate.tv.action.PAUSE"
        const val ACTION_STOP = "com.appgate.tv.action.STOP"
        const val ACTION_RESUME = "com.appgate.tv.action.RESUME"
        private const val WATCHDOG_MS = 20_000L
        private const val STALL_MS = 4 * 60_000L

        fun start(context: Context) {
            val intent = Intent(context, BrainService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        }

        /** Restore the last search's ledgers so results survive a process restart. */
        fun restoreLastSearch(context: Context, memory: Memory): Pair<Goal, List<TaskLedger>>? {
            val prefs = context.getSharedPreferences("brain_jobs", Context.MODE_PRIVATE)
            val goal = Json.parseObjectOrNull(prefs.getString("last_search_goal", null))?.let { runCatching { Goal.fromJson(it) }.getOrNull() } ?: return null
            val ledgers = prefs.getString("last_search_ledgers", "").orEmpty().split(',').filter { it.isNotBlank() }.mapNotNull { memory.loadLedger(it) }
            return goal to ledgers
        }
    }
}
