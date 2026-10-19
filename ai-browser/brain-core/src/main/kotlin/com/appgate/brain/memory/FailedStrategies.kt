package com.appgate.brain.memory

import com.appgate.brain.model.*
import com.appgate.brain.perception.Vocabulary
import com.appgate.brain.util.Hashing

/** Bounded, content-free failure memory shared by ordinary tasks and practice.
 * One failed concrete procedure is held across tasks, restarts and clock changes.
 * Relevant changed controls or a different procedure remain eligible immediately.
 * Repair-request pacing is separate: permission to ask is never permission to replay.
 */
object FailedStrategies {
    private const val REPAIR_LIMIT = 2
    private const val MAX_RECORDS = 64

    fun facet(key: String?): String? = key?.takeIf { it.removeSuffix("_min").removeSuffix("_max") in Vocabulary.facetLexicon }

    /** Only controls for the failing operation can reopen its cooldown. Listings,
     * filter option counts and unrelated header controls routinely change on live pages.
     * This signature remains content-free and ignores result identities/counts. */
    fun controlShape(sps: SemanticPageState, capability: String): String {
        val roles = when (capability) {
            "search" -> setOf(Role.SEARCH_BOX, Role.SUBMIT)
            "open_item", "go_back" -> setOf(Role.RESULT_ITEM)
            "expand_description" -> setOf(Role.EXPAND_TEXT, Role.RESULT_ITEM)
            "next_page" -> setOf(Role.PAGE_NEXT)
            "load_more" -> setOf(Role.LOAD_MORE)
            "scroll_results" -> setOf(Role.RESULT_ITEM, Role.LOAD_MORE, Role.PAGE_NEXT)
            "sort_results" -> setOf(Role.SORT)
            "dismiss_dialog" -> setOf(Role.CLOSE)
            "constrain_numeric", "select_facet", "open_filters", "open_facet", "apply_filters" -> setOf(Role.FACET, Role.FACET_OPEN, Role.FACET_APPLY, Role.CLOSE)
            else -> null
        }
        return sps.affordances.filter { it.visible && (roles == null || it.role in roles) }.map {
            "${it.role}:${facet(it.facetKey)}:${it.facetKind}:${it.tag}:${it.enabled}:${it.regionRole}"
        }.distinct().sorted().joinToString("|")
    }

    fun key(sps: SemanticPageState, capability: String, steps: List<Step>? = null, params: Map<String, String> = emptyMap()): String {
        val controls = controlShape(sps, capability)
        val procedure = steps?.joinToString("|") {
            val f = it.facetKey?.let { k -> if (k.startsWith("$")) params[k.drop(1)] else k }
            // A changed bounded movement is a new approach; query/input text is not identity.
            val movement = when (it.kind) {
                StepKind.SCROLL -> it.arg?.toIntOrNull()?.coerceIn(-10000, 10000)?.takeIf { n -> n != 900 }?.let { n -> ":distance=$n" }.orEmpty()
                StepKind.WAIT -> it.arg?.toIntOrNull()?.coerceIn(0, 10000)?.takeIf { n -> n != 1500 }?.let { n -> ":wait=$n" }.orEmpty()
                else -> ""
            }
            // A corrected sort verification contract is a scoped repair candidate.
            // Legacy failures remain held; failing this contract holds it too.
            fun ordered(p: Postcondition): Boolean = p == Postcondition.ResultOrderChanged ||
                p is Postcondition.AnyOf && p.alternatives.any { child -> ordered(child) }
            val contract = if (capability == "sort_results" && it.expect.any(::ordered)) ":order-v1" else ""
            "${it.kind}:${it.role}:${facet(f)}:${it.submit}:${it.optional}$movement$contract"
        } ?: "planner"
        // Target-specific failures must not blacklist every listing. Only the final
        // opaque digest is retained, never names, queries, URLs, item IDs or page text.
        val target = if (steps != null && capability in setOf("open_item", "expand_description", "go_back")) {
            val explicit = params["item"] ?: steps.firstOrNull { it.role == Role.RESULT_ITEM }?.arg?.let {
                if (it.startsWith("$")) params[it.drop(1)] else it
            }
            val identity = explicit ?: if (sps.pageType == PageType.DETAIL) sps.url else sps.resultKeys.sorted().joinToString("|")
            "|target:" + Hashing.short(identity)
        } else ""
        return Hashing.short("${sps.pageType}|${sps.dialogOpen}|${sps.resultKeys.isNotEmpty()}|$controls|$capability|$procedure$target")
    }

    /** A definite rejected facet SELECT stays held across program aliases. Only the
     * final opaque digest is retained, never option text, names, IDs, URLs or queries.
     * Bind to the chosen semantic control, so unrelated page changes cannot reopen it. */
    fun rejectedFacetSelectKey(sps: SemanticPageState, action: Action): String? {
        if (sps.isHumanOnly || sps.settle != Settle.IDLE || action.kind != ActionKind.SELECT ||
            action.effect != EffectClass.MUTATE_LOCAL || action.target?.role != Role.FACET) return null
        val target = sps.affordances.singleOrNull { it.id == action.target.affordanceId } ?: return null
        val key = facet(target.facetKey) ?: return null
        if (target.role != Role.FACET || target.tag != "select" || !target.visible || !target.enabled ||
            !target.sameSite || target.isCommit || action.target.facetKey != key) return null
        val raw = action.text?.takeIf { it.length in 1..128 } ?: return null
        val option = raw.trim().lowercase(java.util.Locale.ROOT).replace(Regex("\\s+"), " ").takeIf { it.isNotBlank() && it.length <= 128 } ?: return null
        if (target.choices.size > 64 || target.choices.any { it.length > 128 }) return null
        val options = target.choices.map { it.trim().lowercase(java.util.Locale.ROOT).replace(Regex("\\s+"), " ") }
            .distinct().sorted().joinToString("|") { "${it.length}:$it" }
        val shape = "${sps.pageType}|${sps.dialogOpen}|$key|${target.tag}|${target.facetKind}|${target.inputType}|${target.regionRole}|${target.effect}"
        return Hashing.sha256Hex("rejected-select-v1|$shape|choices:$options|option:${option.length}:$option", 64)
    }

    fun allowed(site: SiteModel, key: String, now: Long): Boolean {
        return (site.strategyFailures[key]?.count ?: 0) == 0
    }

    /** Admit the first alternative repair after thirty seconds, then back off
     * unchanged failed states (60s, 120s, 240s, at most 5 minutes). Relevant new
     * controls get a different key and remain eligible immediately. Concrete
     * failed programs stay held regardless of elapsed time; site probes keep their cadence. */
    fun repairAllowed(site: SiteModel, key: String, now: Long): Boolean {
        val failure = site.strategyFailures[key] ?: return true
        if (now < failure.at) return false
        if (failure.count < REPAIR_LIMIT) return true
        val delay = (com.appgate.brain.engine.Curriculum.RETRY_MS *
            (1L shl (failure.count - REPAIR_LIMIT).coerceIn(0, 4))).coerceAtMost(5 * 60_000L)
        return now - failure.at >= delay
    }

    fun record(site: SiteModel, key: String, success: Boolean, now: Long) {
        if (success) site.strategyFailures.remove(key)
        else site.strategyFailures[key] = StrategyFailure(key, ((site.strategyFailures[key]?.count ?: 0) + 1).coerceAtMost(64), now)
        if (site.strategyFailures.size > MAX_RECORDS) {
            site.strategyFailures.values.sortedBy { it.at }.take(site.strategyFailures.size - MAX_RECORDS).forEach { site.strategyFailures.remove(it.key) }
        }
    }

}
