package com.appgate.tv.browser

import com.appgate.brain.engine.Renderer
import com.appgate.brain.engine.RendererGone
import com.appgate.brain.engine.RendererResult
import com.appgate.brain.engine.RendererTimeout
import com.appgate.brain.json.JsonObject
import java.util.concurrent.TimeoutException

/**
 * The brain's view of the browser. Every method runs on the engine thread, blocks with a
 * deadline, and turns lost ports / silent pages into typed failures the engine records.
 */
class GeckoRenderer(private val session: BrainSession) : Renderer {
    private var observedDocument = ""
    private var observedUrl = ""

    override fun observe(timeoutMs: Long): String {
        val r = send(JsonObject().put("cmd", "observe"), timeoutMs)
        val obs = r.optObject("observation") ?: throw RendererTimeout("observe returned no observation: ${r.optString("detail")}")
        observedDocument = obs.optString("documentId")
        observedUrl = obs.optString("url")
        return obs.toString()
    }

    override fun navigate(url: String, timeoutMs: Long): RendererResult {
        session.loadUri(url)
        // Wait for the load to start (the port will drop and reconnect); never longer than the deadline.
        val started = System.currentTimeMillis()
        while (System.currentTimeMillis() - started < minOf(timeoutMs, 4_000L)) {
            if (session.loading) break
            Thread.sleep(100)
        }
        return RendererResult(true, "navigating")
    }

    override fun back(timeoutMs: Long): RendererResult {
        session.goBack()
        Thread.sleep(400)
        return RendererResult(true, "back")
    }

    override fun act(command: JsonObject, timeoutMs: Long): RendererResult {
        if (observedDocument.isBlank()) return RendererResult(false, "no observed document")
        command.put("expectedDocumentId", observedDocument).put("expectedUrl", observedUrl)
        return try {
            val r = send(command, timeoutMs)
            RendererResult(r.optBoolean("ok"), r.optString("detail"), r)
        } catch (e: BrainSession.PortLost) {
            // The action itself caused a navigation before the page could answer: that is success for click/type/submit.
            RendererResult(true, "navigated")
        }
    }

    override fun waitSettle(timeoutMs: Long): RendererResult {
        val deadline = System.currentTimeMillis() + timeoutMs
        var attempts = 0
        while (System.currentTimeMillis() < deadline && attempts < 3) {
            attempts++
            try {
                val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(300L)
                val r = session.request(JsonObject().put("cmd", "settle").put("capMs", remaining), remaining + 1_500L)
                return RendererResult(true, r.optString("state", "UNKNOWN"), r)
            } catch (e: BrainSession.PortLost) {
                // Page navigated during settle: wait for the new document's script, then settle again.
                try { session.waitForPort((deadline - System.currentTimeMillis()).coerceAtLeast(500L)) } catch (t: TimeoutException) { return RendererResult(true, "UNKNOWN") }
            } catch (e: TimeoutException) {
                return RendererResult(true, "UNKNOWN")
            }
        }
        return RendererResult(true, "UNKNOWN")
    }

    override fun currentUrl(): String = session.currentUrl

    override fun setNetworkMode(mode: String, allowlist: List<String>, commitEndpoints: List<String>): RendererResult {
        session.setNetworkMode(mode, allowlist, commitEndpoints)
        return RendererResult(true, mode)
    }

    override fun recover(): RendererResult {
        session.recover()
        return RendererResult(true, "recovered")
    }

    override fun isAlive(): Boolean = session.hasPort()

    private fun send(command: JsonObject, timeoutMs: Long): JsonObject = try {
        session.request(command, timeoutMs)
    } catch (e: TimeoutException) {
        throw RendererTimeout("${command.optString("cmd")} timed out after ${timeoutMs}ms")
    } catch (e: BrainSession.PortLost) {
        if (command.optString("cmd") == "observe") {
            // The document changed under us; give the new script a moment and observe once more.
            try {
                session.waitForPort(timeoutMs.coerceAtMost(6_000L))
                session.request(command, timeoutMs)
            } catch (t: TimeoutException) { throw RendererTimeout("observe: page did not become ready") }
            catch (t: BrainSession.PortLost) { throw RendererGone(t.message ?: "port lost") }
        } else throw e
    }
}
