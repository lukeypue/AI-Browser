package com.appgate.brain.engine

import com.appgate.brain.json.JsonObject
import com.appgate.brain.model.*
import com.appgate.brain.memory.FailedStrategies

enum class DiagnosticCode { VERIFIED, NO_TARGET, REPEAT_STATE_LIMIT, OUTSIDE_TASK_HOST, ACTION_REJECTED, STALE_DOCUMENT, RENDERER_TIMEOUT, INTERLOCK_BLOCKED, UNMET_EXPECTATION, PAGE_UNSETTLED, AUTH_WALL, CHALLENGE, UNEXPECTED_HOST, MODEL_UNCERTAIN }

/** Diagnostics contain typed coordinates and counts, never action arguments or page text. */
object SemanticDiagnostics {
    private val capabilities = setOf("search", "constrain_numeric", "select_facet", "scroll_results", "seek_pagination", "next_page", "load_more", "sort_results", "open_item", "expand_description", "go_back", "dismiss_dialog", "open_filters", "open_facet", "apply_filters", "open_category", "prepare_message", "commit_send", "navigate", "custom")
    fun capability(value: String): String = value.takeIf { it in capabilities } ?: "other"
    fun host(url: String): String = runCatching { java.net.URI(url).host?.lowercase()?.take(253).orEmpty() }.getOrDefault("")

    private fun page(sps: SemanticPageState): JsonObject = JsonObject()
        .put("host", host(sps.url)).put("page_type", sps.pageType.name).put("items", sps.resultKeys.size)
        .put("controls", sps.affordances.count { it.visible && it.enabled })
        .put("search_boxes", sps.byRole(Role.SEARCH_BOX).size).put("result_links", sps.byRole(Role.RESULT_ITEM).size)
        .put("facet_controls", sps.byRole(Role.FACET).size)
        .put("filter_shapes", com.appgate.brain.json.Json.arr(sps.affordances.filter { it.visible && it.role in setOf(Role.FACET, Role.FACET_OPEN) }.map {
            JsonObject().put("role", it.role.name).put("key", FailedStrategies.facet(it.facetKey)).put("kind", it.facetKind)
                .put("tag", it.tag).put("expanded", it.features["expanded"] == 1.0)
        }.distinctBy { it.toString() }.take(32)))
        .put("close_shapes", com.appgate.brain.json.Json.arr(sps.byRole(Role.CLOSE).take(8).map {
            JsonObject().put("score", it.roleScore).put("region", it.regionRole.name).put("same_site", it.sameSite).put("commit", it.isCommit)
        }))
        .put("facet_openers", sps.byRole(Role.FACET_OPEN).size)
        .put("filter_dialog", LearningOpportunities.dialogHoldsFilters(sps))
        .put("facet_keys", com.appgate.brain.json.Json.arr(sps.affordances.filter { it.visible && it.role in setOf(Role.FACET, Role.FACET_OPEN) }.mapNotNull { FailedStrategies.facet(it.facetKey) }.distinct().sorted()))
        .put("dialog_roles", JsonObject().apply { sps.affordances.filter { it.visible && (it.regionRole == RegionRole.DIALOG || it.features["in_dialog"] > 0) }.groupingBy { it.role.name }.eachCount().forEach { (role, count) -> put(role, count) } })
        .put("dialog_controls", sps.affordances.count { it.visible && (it.regionRole == RegionRole.DIALOG || it.features["in_dialog"] > 0) })
        .put("next_controls", sps.byRole(Role.PAGE_NEXT).size).put("load_more_controls", sps.byRole(Role.LOAD_MORE).size)
        .put("scroll_y", sps.scrollY).put("scroll_height", sps.scrollHeight).put("viewport_height", sps.viewportHeight)
        .put("closers", sps.byRole(Role.CLOSE).size).put("dialog", sps.dialogOpen)
        .put("auth_wall", sps.authWall).put("challenge", sps.challenge).put("settle", sps.settle.name)

    fun learningRecheck(site: SiteModel, sps: SemanticPageState?, now: Long): JsonObject = JsonObject()
        .put("available", false).put("human_hold", site.learningNeedsHuman)
        .put("reason", Curriculum.unavailableReason(site, now, sps))
        .put("page_type", sps?.pageType?.name).put("settle", sps?.settle?.name)
        .put("pending", site.curriculum.count { !it.done })
        .put("page", sps?.let { page(it) })
        .put("pending_states", com.appgate.brain.json.Json.arr(site.curriculum.filter { !it.done }.map { item ->
            JsonObject().put("lesson", capability(item.id))
                .put("opportunity", item.opportunity.takeIf { it in setOf("UNKNOWN", "AVAILABLE", "ABSENT") } ?: "UNKNOWN")
                .put("cooling", item.retryAt > now)
                .put("live_target", sps?.let { LearningOpportunities.target(it, item.id) }?.skillId?.let { capability(it) })
        }))

    fun action(ledger: TaskLedger, before: SemanticPageState, after: SemanticPageState?, step: Step?, status: VerifyStatus?, code: DiagnosticCode): JsonObject = JsonObject()
        .put("code", code.name).put("status", status?.name).put("phase", ledger.phase.name)
        .put("source", when { ledger.programSource.startsWith("skill:") -> "skill"; ledger.programSource == "planner" -> "planner"; else -> "engine" })
        .put("capability", capability(ledger.programCapability)).put("lesson", capability(ledger.lesson))
        .put("kind", step?.kind?.name).put("role", step?.role?.name).put("facet", FailedStrategies.facet(step?.facetKey))
        .put("expected_host", host("https://${ledger.host}/"))
        .put("before", page(before)).put("after", after?.let { page(it) })
        .put("new_items", after?.resultKeys?.count { it !in before.resultKeys })
}
