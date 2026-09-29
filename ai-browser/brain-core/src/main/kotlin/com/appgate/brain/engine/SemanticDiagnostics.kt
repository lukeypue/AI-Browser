package com.appgate.brain.engine

import com.appgate.brain.json.JsonObject
import com.appgate.brain.model.*
import com.appgate.brain.memory.FailedStrategies

enum class DiagnosticCode { VERIFIED, NO_TARGET, REPEAT_STATE_LIMIT, OUTSIDE_TASK_HOST, ACTION_REJECTED, STALE_DOCUMENT, RENDERER_TIMEOUT, INTERLOCK_BLOCKED, UNMET_EXPECTATION, PAGE_UNSETTLED, AUTH_WALL, CHALLENGE, UNEXPECTED_HOST, MODEL_UNCERTAIN }

/** Diagnostics contain typed coordinates and counts, never action arguments or page text. */
object SemanticDiagnostics {
    private val capabilities = setOf("search", "constrain_numeric", "select_facet", "scroll_results", "next_page", "load_more", "sort_results", "open_item", "expand_description", "go_back", "dismiss_dialog", "open_filters", "apply_filters", "open_category", "prepare_message", "commit_send", "navigate", "custom")
    fun capability(value: String): String = value.takeIf { it in capabilities } ?: "other"
    fun host(url: String): String = runCatching { java.net.URI(url).host?.lowercase()?.take(253).orEmpty() }.getOrDefault("")

    private fun page(sps: SemanticPageState): JsonObject = JsonObject()
        .put("host", host(sps.url)).put("page_type", sps.pageType.name).put("items", sps.resultKeys.size)
        .put("controls", sps.affordances.count { it.visible && it.enabled })
        .put("search_boxes", sps.byRole(Role.SEARCH_BOX).size).put("result_links", sps.byRole(Role.RESULT_ITEM).size)
        .put("closers", sps.byRole(Role.CLOSE).size).put("dialog", sps.dialogOpen)
        .put("auth_wall", sps.authWall).put("challenge", sps.challenge).put("settle", sps.settle.name)

    fun action(ledger: TaskLedger, before: SemanticPageState, after: SemanticPageState?, step: Step?, status: VerifyStatus?, code: DiagnosticCode): JsonObject = JsonObject()
        .put("code", code.name).put("status", status?.name).put("phase", ledger.phase.name)
        .put("source", when { ledger.programSource.startsWith("skill:") -> "skill"; ledger.programSource == "planner" -> "planner"; else -> "engine" })
        .put("capability", capability(ledger.programCapability)).put("lesson", capability(ledger.lesson))
        .put("kind", step?.kind?.name).put("role", step?.role?.name).put("facet", FailedStrategies.facet(step?.facetKey))
        .put("expected_host", host("https://${ledger.host}/"))
        .put("before", page(before)).put("after", after?.let { page(it) })
        .put("new_items", after?.resultKeys?.count { it !in before.resultKeys })
}
