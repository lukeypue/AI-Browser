package com.appgate.brain.engine

import com.appgate.brain.json.JsonObject
import com.appgate.brain.model.Action
import com.appgate.brain.model.ActionKind
import com.appgate.brain.model.EffectClass
import com.appgate.brain.model.Grant
import com.appgate.brain.model.Role
import com.appgate.brain.model.SemanticPageState
import com.appgate.brain.perception.SpsParser

enum class EngineMode { TRAIN, ASSIST, COMMIT }

sealed class ExecOutcome {
    data class Done(val after: SemanticPageState, val detail: String, val ms: Long) : ExecOutcome()
    data class Blocked(val reason: String, val needGrant: Boolean = false) : ExecOutcome()
    data class Failed(val reason: String, val timeout: Boolean = false) : ExecOutcome()
}

/**
 * Runs typed actions through the renderer with three responsibilities that the planner can
 * never bypass: the capability interlock (effect class × mode × grants), bounded waits, and
 * the post-action observation. It decides nothing about success — the verifier does.
 */
class Executor(
    private val renderer: Renderer,
    private val parserFactory: () -> SpsParser,
    private val timeouts: Timeouts = Timeouts()
) {
    data class Timeouts(
        val observeMs: Long = 9_000L,
        val actMs: Long = 8_000L,
        val navigateMs: Long = 20_000L,
        val settleMs: Long = 8_000L,
        val settleAfterNavigateMs: Long = 12_000L
    )

    fun execute(action: Action, before: SemanticPageState, mode: EngineMode, grants: List<Grant>, previewHash: String?, now: Long): ExecOutcome {
        // ---- Interlock 1: effect class vs mode ----
        if (action.effect == EffectClass.COMMIT_EXTERNAL || action.target?.role?.isCommit == true) {
            val grant = grants.firstOrNull { it.covers(action, before.host, previewHash, now) }
                ?: return ExecOutcome.Blocked("COMMIT_EXTERNAL requires a matching unexpired grant", needGrant = true)
            grant.used = true
        }
        if (mode == EngineMode.TRAIN && action.target?.role == Role.COMPOSER_INPUT) {
            return ExecOutcome.Blocked("TRAIN mode never types into a composer")
        }
        if (mode == EngineMode.TRAIN && action.target?.role in setOf(Role.SEND, Role.BUY, Role.BID, Role.POST, Role.DELETE, Role.FOLLOW, Role.SAVE, Role.REPORT)) {
            return ExecOutcome.Blocked("TRAIN mode never acts on ${action.target?.role}")
        }
        if (before.isHumanOnly) return ExecOutcome.Blocked("human-only page")

        val started = System.currentTimeMillis()
        val result = try {
            when (action.kind) {
                ActionKind.NAVIGATE -> renderer.navigate(action.url ?: return ExecOutcome.Failed("navigate without url"), timeouts.navigateMs)
                ActionKind.BACK -> renderer.back(timeouts.navigateMs)
                ActionKind.WAIT -> { Thread.sleep((action.amount ?: 1000).toLong().coerceIn(100L, 10_000L)); RendererResult(true, "waited") }
                else -> renderer.act(command(action), timeouts.actMs)
            }
        } catch (t: RendererTimeout) {
            return ExecOutcome.Failed("renderer timeout: ${t.message}", timeout = true)
        } catch (t: RendererGone) {
            return ExecOutcome.Failed("renderer gone: ${t.message}", timeout = true)
        }
        if (!result.ok) return ExecOutcome.Failed(result.detail.ifBlank { "renderer rejected action" })

        val settleCap = if (action.kind == ActionKind.NAVIGATE || action.kind == ActionKind.BACK || action.effect == EffectClass.NAVIGATE) timeouts.settleAfterNavigateMs else timeouts.settleMs
        runCatching { renderer.waitSettle(settleCap) }
        val after = try {
            parserFactory().parse(renderer.observe(timeouts.observeMs), now)
        } catch (t: RendererTimeout) {
            return ExecOutcome.Failed("observe timeout after action", timeout = true)
        } catch (t: RendererGone) {
            return ExecOutcome.Failed("renderer gone after action", timeout = true)
        } catch (t: Exception) {
            return ExecOutcome.Failed("observe failed: ${t.message}")
        }
        return ExecOutcome.Done(after, result.detail, System.currentTimeMillis() - started)
    }

    fun observe(now: Long): SemanticPageState = parserFactory().parse(renderer.observe(timeouts.observeMs), now)

    companion object {
        fun command(action: Action): JsonObject {
            val id = action.target?.affordanceId
            return when (action.kind) {
                ActionKind.CLICK -> JsonObject().put("cmd", "click").put("id", id)
                ActionKind.DISMISS -> JsonObject().put("cmd", "dismiss").put("id", id)
                ActionKind.TYPE -> JsonObject().put("cmd", "type").put("id", id).put("text", action.text ?: "").put("submit", action.submit)
                ActionKind.SELECT -> JsonObject().put("cmd", "select").put("id", id).put("option", action.text ?: "")
                ActionKind.SET_RANGE -> JsonObject().put("cmd", "set_range").put("id", id).put("value", action.text ?: "").put("submit", action.submit)
                ActionKind.SCROLL -> JsonObject().put("cmd", "scroll").put("dy", action.amount ?: 900)
                ActionKind.WAIT -> JsonObject().put("cmd", "wait").put("ms", action.amount ?: 1000)
                ActionKind.NAVIGATE -> JsonObject().put("cmd", "navigate").put("url", action.url)
                ActionKind.BACK -> JsonObject().put("cmd", "back")
            }
        }
    }
}
