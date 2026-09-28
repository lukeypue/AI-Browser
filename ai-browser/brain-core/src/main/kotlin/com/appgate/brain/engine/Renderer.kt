package com.appgate.brain.engine

import com.appgate.brain.json.JsonObject

/** Result of a low-level renderer command. */
data class RendererResult(val ok: Boolean, val detail: String = "", val payload: JsonObject? = null)

class RendererTimeout(message: String) : RuntimeException(message)
class RendererGone(message: String) : RuntimeException(message)

/**
 * The renderer is the only thing that touches a real browser. It observes and acts; it
 * never reasons and never writes memory. Every call is synchronous with a hard deadline so
 * the engine can never hang on a lost callback — a lost callback becomes a [RendererTimeout],
 * which is a real, recorded outcome.
 *
 * Implementations: GeckoRenderer (Android, via the bundled WebExtension port) and
 * ReplayRenderer (JVM harness over recorded snapshots).
 */
interface Renderer {
    /** Raw extractor JSON for the current top-level document. */
    fun observe(timeoutMs: Long): String

    /** Navigate the top-level document; returns when the load has started or failed. */
    fun navigate(url: String, timeoutMs: Long): RendererResult

    fun back(timeoutMs: Long): RendererResult

    /** Execute a low-level command in the page (click/type/select/scroll/dismiss/set_range). */
    fun act(command: JsonObject, timeoutMs: Long): RendererResult

    /** Block until the page is settled (network idle + DOM quiet + two agreeing snapshots) or the cap elapses. */
    fun waitSettle(timeoutMs: Long): RendererResult

    /** Current top-level URL as the renderer knows it (cheap, no page round-trip). */
    fun currentUrl(): String

    /** Set network interlock mode: TRAIN (block all non-GET except learned READ), ASSIST, or OFF. */
    fun setNetworkMode(mode: String, allowlist: List<String>, commitEndpoints: List<String>): RendererResult

    /** Reset the renderer after a crash or a hang; must be safe to call repeatedly. */
    fun recover(): RendererResult

    fun isAlive(): Boolean
}
