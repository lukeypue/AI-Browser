package com.appgate.tv.browser

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.appgate.brain.json.Json
import com.appgate.brain.json.JsonObject
import com.appgate.brain.engine.BridgeRequests
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebRequestError
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * One browsing context for the whole app: a main GeckoSession plus any popups it opens,
 * the bridge port to the content script, an off-screen display for background work, and
 * the ability to hand the very same session to an on-screen GeckoView for a human.
 *
 * All Gecko calls happen on the main thread; [request] is the one blocking entry point the
 * engine thread uses, and it always has a deadline.
 */
class BrainSession(private val context: Context, private val runtime: GeckoRuntime, private val extension: WebExtension) {
    interface Listener {
        fun onLocation(url: String) {}
        fun onTitle(title: String) {}
        fun onActiveSessionChanged() {}
        fun onLoadingChanged(loading: Boolean) {}
        fun onRendererGone(reason: String) {}
        fun onNetEvent(event: JsonObject) {}
    }

    private val main = Handler(Looper.getMainLooper())
    private val reqIds = AtomicInteger(1)
    private val pending = BridgeRequests<WebExtension.Port, CompletableFuture<JsonObject>>()
    private val ports = HashMap<GeckoSession, WebExtension.Port>()
    private var netPort: WebExtension.Port? = null
    private val readyWaiters = ArrayList<CompletableFuture<Boolean>>()
    private val popups = ArrayList<GeckoSession>()
    private val offscreen = OffscreenDisplay()
    private var attachedView: GeckoView? = null
    @Volatile var listener: Listener? = null
    @Volatile var activityProvider: () -> Activity? = { null }
    @Volatile var currentUrl: String = ""
        private set
    @Volatile var loading: Boolean = false
        private set
    @Volatile var canGoBack: Boolean = false
        private set
    @Volatile var humanPresent: Boolean = false
    private var closed = false

    lateinit var mainSession: GeckoSession
        private set

    private val sessionSettings: GeckoSessionSettings
        get() = GeckoSessionSettings.Builder()
            .usePrivateMode(false)
            .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
            .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
            .useTrackingProtection(true)
            .suspendMediaWhenInactive(true)
            .build()

    // ------------------------------------------------------------------ session creation / delegates

    private fun newSession(): GeckoSession {
        val session = GeckoSession(sessionSettings)
        session.navigationDelegate = navigationDelegate
        session.contentDelegate = contentDelegate
        session.progressDelegate = progressDelegate
        session.promptDelegate = BrainPromptDelegate { activityProvider() }
        session.webExtensionController.setMessageDelegate(extension, contentMessageDelegate, BrainRuntime.NATIVE_APP_CONTENT)
        return session
    }

    /** The session a person would be looking at: the newest popup, or the main session. */
    fun active(): GeckoSession = popups.lastOrNull() ?: mainSession

    private val navigationDelegate = object : GeckoSession.NavigationDelegate {
        override fun onLocationChange(session: GeckoSession, url: String?, perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>, hasUserGesture: Boolean) {
            if (session === active()) {
                currentUrl = url.orEmpty()
                listener?.onLocation(currentUrl)
            }
        }

        override fun onCanGoBack(session: GeckoSession, value: Boolean) {
            if (session === active()) canGoBack = value
        }

        override fun onLoadRequest(session: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny>? {
            val uri = request.uri
            val scheme = runCatching { Uri.parse(uri).scheme.orEmpty().lowercase() }.getOrDefault("")
            if (scheme == "http" || scheme == "https" || scheme == "about" || scheme == "blob" || scheme == "data") return GeckoResult.allow()
            // App links / intent URLs: only a person may be handed to another app, and only for a sign-in style flow.
            if (humanPresent) {
                val activity = activityProvider()
                if (activity != null) runCatching {
                    val intent = if (scheme == "intent") Intent.parseUri(uri, Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                    activity.startActivity(intent)
                }
            }
            return GeckoResult.deny()
        }

        override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
            // Sign-in providers open the account chooser in a popup. Gecko opens the returned session itself;
            // we only create it, remember it, and show it. When it calls window.close() we go back (see onCloseRequest).
            // Headless (no person looking): popups are refused so the working page keeps its display.
            if (!humanPresent) { Log.i(TAG, "popup refused while unattended"); return GeckoResult.fromValue(null) }
            val child = newSession()
            popups += child
            Log.i(TAG, "popup opened (${popups.size})")
            main.post { rebindDisplay() }
            return GeckoResult.fromValue(child)
        }

        override fun onLoadError(session: GeckoSession, uri: String?, error: WebRequestError): GeckoResult<String>? = null
    }

    private val contentDelegate = object : GeckoSession.ContentDelegate {
        override fun onTitleChange(session: GeckoSession, title: String?) {
            if (session === active()) listener?.onTitle(title.orEmpty())
        }

        override fun onCloseRequest(session: GeckoSession) {
            if (popups.remove(session)) {
                Log.i(TAG, "popup closed (${popups.size} left)")
                runCatching { session.close() }
                main.post { rebindDisplay(); currentUrl = ""; listener?.onActiveSessionChanged() }
            }
        }

        override fun onCrash(session: GeckoSession) { rendererGone(session, "content process crashed") }
        override fun onKill(session: GeckoSession) { rendererGone(session, "content process killed") }
    }

    private val progressDelegate = object : GeckoSession.ProgressDelegate {
        override fun onPageStart(session: GeckoSession, url: String) {
            if (session === mainSession) { loading = true; listener?.onLoadingChanged(true) }
        }
        override fun onPageStop(session: GeckoSession, success: Boolean) {
            if (session === mainSession) { loading = false; listener?.onLoadingChanged(false) }
        }
    }

    private fun rendererGone(session: GeckoSession, reason: String) {
        Log.w(TAG, "renderer gone: $reason")
        if (popups.remove(session)) { runCatching { session.close() }; main.post { rebindDisplay() }; return }
        failAllPending("renderer gone")
        listener?.onRendererGone(reason)
    }

    // ------------------------------------------------------------------ extension bridge

    private val contentMessageDelegate = object : WebExtension.MessageDelegate {
        override fun onConnect(port: WebExtension.Port) {
            val session = port.sender.session ?: mainSession
            val old = ports[session]
            ports[session] = port
            if (old != null && old !== port) {
                pending.removeOwner(old).forEach { it.completeExceptionally(PortLost("document replaced")) }
                runCatching { old.disconnect() }
            }
            port.setDelegate(object : WebExtension.PortDelegate {
                override fun onPortMessage(message: Any, port: WebExtension.Port) {
                    if (ports[session] !== port) return
                    val json = (message as? JSONObject)?.let { Json.parseObjectOrNull(it.toString()) } ?: return
                    when (json.optString("type")) {
                        "RESULT" -> {
                            val id = json.optInt("reqId", -1)
                            val future = pending.take(id, port)
                            future?.complete(json)
                        }
                        "BRIDGE_READY" -> {
                            if (session === mainSession) {
                                val waiters = synchronized(readyWaiters) { ArrayList(readyWaiters).also { readyWaiters.clear() } }
                                waiters.forEach { it.complete(true) }
                            }
                        }
                        "TEACH" -> teachListener?.invoke(json)
                    }
                }

                override fun onDisconnect(port: WebExtension.Port) {
                    if (ports[session] === port) ports.remove(session)
                    pending.removeOwner(port).forEach { it.completeExceptionally(PortLost("port lost (navigation)")) }
                }
            })
            // A new document starts with its own guard OFF; restore the session mode.
            postContentMode(port)
        }
    }

    private fun postContentMode(port: WebExtension.Port) {
        val mode = pendingNetMode?.optString("mode") ?: "OFF"
        val command = JsonObject().put("cmd", "guard").put("mode", mode).put("reqId", 0)
        runCatching { port.postMessage(JSONObject(command.toString())) }
    }

    private val netMessageDelegate = object : WebExtension.MessageDelegate {
        override fun onConnect(port: WebExtension.Port) {
            netPort = port
            port.setDelegate(object : WebExtension.PortDelegate {
                override fun onPortMessage(message: Any, port: WebExtension.Port) {
                    val json = (message as? JSONObject)?.let { Json.parseObjectOrNull(it.toString()) } ?: return
                    listener?.onNetEvent(json)
                }
                override fun onDisconnect(port: WebExtension.Port) { if (netPort === port) netPort = null }
            })
            pendingNetMode?.let { runCatching { port.postMessage(JSONObject(it.toString())) } }
        }
    }

    @Volatile private var pendingNetMode: JsonObject? = null
    @Volatile var teachListener: ((JsonObject) -> Unit)? = null

    private fun failAllPending(reason: String) {
        val all = pending.drain()
        all.forEach { it.completeExceptionally(PortLost(reason)) }
    }

    class PortLost(message: String) : RuntimeException(message)

    /**
     * Sends a command to the main session's content script and waits for its result. Never
     * call on the main thread. A lost port (the page navigated away) surfaces as [PortLost];
     * a silent page surfaces as [TimeoutException]. Both are real, recorded outcomes.
     */
    fun request(command: JsonObject, timeoutMs: Long): JsonObject {
        check(Looper.myLooper() != Looper.getMainLooper()) { "request() must not run on the main thread" }
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        waitForPort(timeoutMs)
        val id = reqIds.getAndIncrement()
        val future = CompletableFuture<JsonObject>()
        command.put("reqId", id)
        main.post {
            if (future.isDone) return@post
            val port = ports[mainSession]
            if (port == null) future.completeExceptionally(PortLost("no port"))
            else {
                pending.put(id, port, future)
                if (future.isDone) { pending.remove(id); return@post }
                // Apply mode in the same message as the action, including after navigation.
                command.put("guardMode", pendingNetMode?.optString("mode") ?: "OFF")
                val payload = JSONObject(command.toString())
                runCatching { port.postMessage(payload) }.onFailure { pending.remove(id); future.completeExceptionally(PortLost("post failed")) }
            }
        }
        val remaining = (deadline - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(1L)
        try {
            return future.get(remaining, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            throw e
        } catch (e: java.util.concurrent.ExecutionException) {
            throw (e.cause ?: e)
        } finally {
            future.cancel(false)
            pending.remove(id)
        }
    }

    /** Blocks (off main thread) until the main session's content script has connected. */
    fun waitForPort(timeoutMs: Long) {
        val future = CompletableFuture<Boolean>()
        main.post {
            if (future.isDone) return@post
            if (ports[mainSession] != null) future.complete(true)
            else synchronized(readyWaiters) { readyWaiters += future }
        }
        try { future.get(timeoutMs, TimeUnit.MILLISECONDS) }
        finally { future.cancel(false); synchronized(readyWaiters) { readyWaiters.remove(future) } }
    }

    fun hasPort(): Boolean = ports[mainSession] != null

    // ------------------------------------------------------------------ navigation (main thread)

    fun loadUri(url: String) = main.post {
        // A load is asynchronous. Never let the next observation use the outgoing document.
        ports.remove(mainSession)?.let { old ->
            pending.removeOwner(old).forEach { it.completeExceptionally(PortLost("navigation queued")) }
        }
        runCatching { mainSession.loadUri(url) }
    }
    fun goBack() = main.post { runCatching { active().goBack() } }
    fun reload() = main.post { runCatching { active().reload() } }
    fun stop() = main.post { runCatching { active().stop() } }

    /** Closes every popup and returns to the main session (used by the "Done" button after sign-in). */
    fun closePopups() = main.post {
        popups.toList().forEach { runCatching { it.close() } }
        popups.clear()
        rebindDisplay()
        listener?.onActiveSessionChanged()
    }

    fun setNetworkMode(mode: String, allow: List<String>, commit: List<String>) {
        val msg = JsonObject().put("type", "NET_MODE").put("mode", mode).putStrings("allow", allow).putStrings("commit", commit)
        pendingNetMode = msg
        main.post {
            runCatching { netPort?.postMessage(JSONObject(msg.toString())) }
            ports.values.toList().forEach { postContentMode(it) }
        }
    }

    fun openNetworkGrant(ttlMs: Long) = main.post { runCatching { netPort?.postMessage(JSONObject(JsonObject().put("type", "NET_GRANT").put("ttlMs", ttlMs).toString())) } }
    fun closeNetworkGrant() = main.post { runCatching { netPort?.postMessage(JSONObject(JsonObject().put("type", "NET_GRANT_CLOSE").toString())) } }

    // ------------------------------------------------------------------ display handoff (main thread)

    /** Show the live session inside an on-screen view (human sign-in, teaching, watching). */
    fun attachTo(view: GeckoView) {
        offscreen.detach()
        attachedView?.let { if (it !== view) runCatching { it.releaseSession() } }
        attachedView = view
        runCatching { view.releaseSession() }
        view.setSession(active())
        humanPresent = true
    }

    /** Detach from the on-screen view and continue off-screen. */
    fun detachFromView() {
        attachedView?.let { runCatching { it.releaseSession() } }
        attachedView = null
        humanPresent = false
        offscreen.attach(active())
    }

    private fun rebindDisplay() {
        val view = attachedView
        if (view != null) {
            runCatching { view.releaseSession() }
            runCatching { view.setSession(active()) }
        } else {
            offscreen.attach(active())
        }
        listener?.onActiveSessionChanged()
    }

    /** Recreate the main session after a crash or a hang. Popups are dropped. Cookies survive (they live in the profile). */
    fun recover() {
        if (Looper.myLooper() == Looper.getMainLooper()) { recoverOnMain(); return }
        val done = CompletableFuture<Boolean>()
        main.post { recoverOnMain(); done.complete(true) }
        runCatching { done.get(10, TimeUnit.SECONDS) }
    }

    private fun recoverOnMain() {
        failAllPending("recover")
        popups.toList().forEach { runCatching { it.close() } }
        popups.clear()
        offscreen.detach()
        attachedView?.let { runCatching { it.releaseSession() } }
        runCatching { mainSession.close() }
        ports.clear()
        mainSession = newSession()
        mainSession.open(runtime)
        rebindDisplay()
    }

    fun close() {
        closed = true
        main.post {
            failAllPending("closed")
            offscreen.detach()
            attachedView?.let { runCatching { it.releaseSession() } }
            popups.forEach { runCatching { it.close() } }
            popups.clear()
            runCatching { mainSession.close() }
        }
    }

    init {
        // Runs after every delegate above is constructed (Kotlin initializes in declaration order).
        extension.setMessageDelegate(netMessageDelegate, BrainRuntime.NATIVE_APP_NET)
        mainSession = newSession()
        mainSession.open(runtime)
        offscreen.attach(mainSession)
    }

    companion object { private const val TAG = "BrainSession" }
}
