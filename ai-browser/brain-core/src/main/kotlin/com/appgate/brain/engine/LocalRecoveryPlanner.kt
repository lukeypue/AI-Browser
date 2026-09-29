package com.appgate.brain.engine

import com.appgate.brain.model.*
import com.appgate.brain.perception.Vocabulary
import com.appgate.brain.util.Text

data class LocalRecoveryPlan(
    val steps: List<Step>,
    val params: Map<String, String>,
    val postconditions: List<Postcondition>,
    val completesCapability: Boolean = true
)

/**
 * Finite recipes over observed controls. This proposes data only: the engine owns attempt
 * limits, live grounding, executor interlocks and verification. A drawer discovery is not
 * a successful filter operation and must be followed by a new proposal from a fresh SPS.
 */
object LocalRecoveryPlanner {
    private val filterPages = setOf(PageType.RESULTS, PageType.SEARCH, PageType.FACET_PANEL, PageType.DIALOG)
    private data class RequestedFacet(val key: String, val value: String, val numeric: Boolean)

    fun propose(ledger: TaskLedger, sps: SemanticPageState, site: SiteModel, capability: String): LocalRecoveryPlan? {
        if (sps.isHumanOnly || sps.settle == Settle.BUSY ||
            hostIdentity(sps.host) != hostIdentity(ledger.host) || hostIdentity(site.host) != hostIdentity(ledger.host)) return null
        return when (capability) {
            "search" -> search(ledger, sps)
            "constrain_numeric", "select_facet", "apply_filters" -> facet(ledger, sps, capability)
            "open_filters" -> if (sps.pageType in filterPages) open(sps, null, true) else null
            "sort_results" -> sort(ledger, sps)
            else -> null
        }
    }

    private fun search(ledger: TaskLedger, sps: SemanticPageState): LocalRecoveryPlan? {
        if (sps.dialogOpen || sps.pageType !in setOf(PageType.HOME, PageType.SEARCH, PageType.RESULTS, PageType.UNKNOWN)) return null
        val query = ledger.effectiveGoal.query.takeIf { it.isNotBlank() } ?: return null
        val control = sps.byRole(Role.SEARCH_BOX).singleOrNull()?.takeIf { safe(it) && editable(it) } ?: return null
        return LocalRecoveryPlan(
            listOf(Step(StepKind.TYPE, Role.SEARCH_BOX, arg = "\$query", submit = true, nameHint = control.name,
                expect = listOf(Postcondition.anyOf(Postcondition.ResultsChanged, Postcondition.PageTypeIs(PageType.RESULTS))))),
            mapOf("query" to query), listOf(Postcondition.PageTypeIs(PageType.RESULTS))
        )
    }

    private fun facet(ledger: TaskLedger, sps: SemanticPageState, capability: String): LocalRecoveryPlan? {
        if (sps.pageType !in filterPages) return null
        val requested = requestedFacets(ledger, capability).firstOrNull { request ->
            !matches(request, sps.constraintsActive[request.key])
        } ?: return null
        val params = mapOf("key" to requested.key, "value" to requested.value)
        val controls = sps.byRole(Role.FACET).filter { it.facetKey == requested.key }
        if (controls.isEmpty()) return if (capability == "apply_filters") null else open(sps, requested, false)

        // A group of choice buttons can share one facet key; the exact option disambiguates.
        val candidates = if (!requested.numeric && controls.size > 1) controls.filter { optionControl(it, requested.value) } else controls
        val control = candidates.singleOrNull()?.takeIf { safe(it) } ?: return null
        if (!supports(control, requested)) return null
        val applyControls = sps.byRole(Role.FACET_APPLY)
        if (applyControls.size > 1 || applyControls.any { !safe(it) }) return null
        val apply = applyControls.singleOrNull()
        val pending = matches(requested, control.value) || (control.selected && optionControl(control, requested.value))
        if (capability == "apply_filters" && (!pending || apply == null)) return null
        // A visible modal cannot be completed without a known way to apply/close its filter.
        if ((sps.dialogOpen || sps.pageType == PageType.FACET_PANEL) && apply == null) return null
        if (pending && apply == null) return null

        val steps = mutableListOf<Step>()
        if (!pending) {
            val kind = when {
                requested.numeric -> StepKind.SET_RANGE
                optionControl(control, requested.value) -> StepKind.CLICK
                else -> StepKind.SELECT
            }
            steps += Step(kind, Role.FACET, "\$key", "\$value", submit = requested.numeric,
                nameHint = control.name,
                expect = listOf(Postcondition.anyOf(Postcondition.ValueIs("\$key", "\$value"), Postcondition.ConstraintApplied("\$key", "\$value"))))
        }
        if (apply != null) steps += Step(StepKind.CLICK, Role.FACET_APPLY, nameHint = apply.name,
            expect = listOf(Postcondition.anyOf(Postcondition.ConstraintApplied("\$key", "\$value"), Postcondition.DialogClosed, Postcondition.ResultsChanged)))
        val post = mutableListOf<Postcondition>(Postcondition.ConstraintApplied("\$key", "\$value"), Postcondition.PageTypeIs(PageType.RESULTS))
        // DialogClosed is a transition predicate, so do not require it for inline controls.
        if (sps.dialogOpen) post += Postcondition.DialogClosed
        return LocalRecoveryPlan(steps, params, post)
    }

    private fun open(sps: SemanticPageState, requested: RequestedFacet?, completesCapability: Boolean): LocalRecoveryPlan? {
        if (sps.dialogOpen) return null
        val all = sps.byRole(Role.FACET_OPEN)
        val specific = requested?.let { r -> all.filter { it.facetKey == r.key } }.orEmpty()
        val family = requested?.let { r -> all.filter { it.facetKey != null && baseKey(it.facetKey) == baseKey(r.key) } }.orEmpty()
        val control = (specific.ifEmpty { family }.ifEmpty { all.filter { it.facetKey == null } }).singleOrNull()?.takeIf { safe(it) } ?: return null
        val expect = listOf(Postcondition.anyOf(Postcondition.RoleAppeared(Role.FACET), Postcondition.PageTypeIs(PageType.FACET_PANEL), Postcondition.DialogOpened))
        return LocalRecoveryPlan(
            listOf(Step(StepKind.CLICK, Role.FACET_OPEN, facetKey = control.facetKey, nameHint = control.name, expect = expect)),
            emptyMap(), expect, completesCapability
        )
    }

    private fun sort(ledger: TaskLedger, sps: SemanticPageState): LocalRecoveryPlan? {
        if (sps.pageType != PageType.RESULTS || sps.dialogOpen) return null
        val wanted = ledger.currentProgram.firstOrNull { it.role == Role.SORT }?.arg?.takeIf { it.isNotBlank() && !it.startsWith("$") }
            ?: ledger.effectiveGoal.constraints.firstOrNull { it.key == "sort" && it.op == ConstraintOp.EQ }?.value?.takeIf { it.isNotBlank() }
            ?: return null
        val control = sps.byRole(Role.SORT).singleOrNull()?.takeIf { safe(it) } ?: return null
        if (control.choices.count { normalized(it) == normalized(wanted) } != 1 || normalized(control.value.orEmpty()) == normalized(wanted)) return null
        return LocalRecoveryPlan(
            listOf(Step(StepKind.SELECT, Role.SORT, arg = "\$order", nameHint = control.name, expect = listOf(Postcondition.ResultsChanged))),
            mapOf("order" to wanted), listOf(Postcondition.ResultsChanged, Postcondition.PageTypeIs(PageType.RESULTS))
        )
    }

    private fun requestedFacets(ledger: TaskLedger, capability: String): List<RequestedFacet> {
        val requests = ledger.effectiveGoal.filterable.mapNotNull { constraint ->
            val numeric = constraint.op in setOf(ConstraintOp.LTE, ConstraintOp.GTE) && constraint.numericValue != null
            if (capability == "constrain_numeric" && !numeric || capability == "select_facet" && numeric) return@mapNotNull null
            if (!numeric && constraint.op != ConstraintOp.EQ || constraint.key == "sort" || constraint.value.isBlank()) return@mapNotNull null
            val key = when {
                constraint.key.endsWith("_min") || constraint.key.endsWith("_max") -> constraint.key
                constraint.op == ConstraintOp.LTE -> "${constraint.key}_max"
                constraint.op == ConstraintOp.GTE -> "${constraint.key}_min"
                else -> constraint.key
            }
            RequestedFacet(key, constraint.value, numeric)
        }
        val desired = ledger.currentProgram.firstOrNull { it.role == Role.FACET }?.facetKey
            ?: ledger.programPost.filterIsInstance<Postcondition.ConstraintApplied>().firstOrNull()?.key
        return if (desired == null || desired.startsWith("$")) requests else requests.sortedBy { it.key != desired }
    }

    private fun supports(control: Affordance, request: RequestedFacet): Boolean {
        if (request.numeric) {
            if (control.tag == "select") return control.choices.count { Text.parseAmount(it) == Text.parseAmount(request.value) } == 1
            return editable(control)
        }
        return control.choices.count { normalized(it) == normalized(request.value) } == 1 || optionControl(control, request.value)
    }

    private fun optionControl(control: Affordance, wanted: String): Boolean =
        control.choices.isEmpty() && control.facetKind in setOf("choice", "toggle") &&
            control.tag in setOf("button", "input") && normalized(control.name) == normalized(wanted)

    private fun matches(request: RequestedFacet, actual: String?): Boolean = actual != null &&
        if (request.numeric) Text.parseAmount(actual) == Text.parseAmount(request.value) else normalized(actual) == normalized(request.value)

    private fun safe(control: Affordance): Boolean = control.visible && control.enabled && control.sameSite && !control.isCommit && !control.role.isCommit
    private fun editable(control: Affordance): Boolean = control.tag in setOf("input", "textarea") && control.inputType !in setOf("password", "hidden", "file", "submit", "button", "checkbox", "radio")
    private fun normalized(value: String): String = Vocabulary.normalize(value)
    private fun baseKey(key: String): String = key.removeSuffix("_min").removeSuffix("_max")
    private fun hostIdentity(host: String): String = host.lowercase().removePrefix("www.")
}
