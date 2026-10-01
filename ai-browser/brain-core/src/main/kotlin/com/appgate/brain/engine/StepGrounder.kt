package com.appgate.brain.engine

import com.appgate.brain.model.Action
import com.appgate.brain.model.ActionKind
import com.appgate.brain.model.Affordance
import com.appgate.brain.model.AffordanceRef
import com.appgate.brain.model.EffectClass
import com.appgate.brain.model.Postcondition
import com.appgate.brain.model.Role
import com.appgate.brain.model.SemanticPageState
import com.appgate.brain.model.SiteModel
import com.appgate.brain.model.Step
import com.appgate.brain.model.StepKind
import com.appgate.brain.skills.Grounder
import com.appgate.brain.skills.Grounding
import com.appgate.brain.perception.Vocabulary
import com.appgate.brain.util.Text
import com.appgate.brain.verify.Verifier

/** A step turned into a concrete action with the grounding choices that produced it. */
data class GroundedStep(
    val step: Step,
    val action: Action,
    val chosen: Grounding?,
    val alternates: List<Grounding>,
    val predictedP: Double
)

sealed class GroundingOutcome {
    data class Ready(val grounded: GroundedStep) : GroundingOutcome()
    data class Skip(val reason: String) : GroundingOutcome()          // optional step that cannot / need not run
    data class Missing(val reason: String) : GroundingOutcome()       // required step with no live target
}

/**
 * Binds a skill step's parameters and grounds its abstract target to a live affordance.
 * The effect class of the resulting action always comes from perception.
 */
class StepGrounder(private val site: SiteModel?) {
    private val grounder = Grounder(site)

    fun ground(step: Step, params: Map<String, String>, sps: SemanticPageState, visited: Set<String>, excludedIds: Set<String> = emptySet(), strict: Boolean = false): GroundingOutcome {
        val facetKey = step.facetKey?.let { substitute(it, params) }?.takeIf { it.isNotBlank() && !it.startsWith("$") }
        val arg = step.arg?.let { substitute(it, params) }?.takeIf { !it.startsWith("$") }
        val expect = step.expect.map { bind(it, params) }

        when (step.kind) {
            StepKind.SCROLL -> return GroundingOutcome.Ready(GroundedStep(step, Action(ActionKind.SCROLL, amount = arg?.toIntOrNull() ?: 900, expect = expect.ifEmpty { listOf(Postcondition.anyOf(Postcondition.NewResults, Postcondition.EndOfResults, Postcondition.ScrolledDown)) }, effect = EffectClass.READ), null, emptyList(), 0.8))
            StepKind.BACK -> return GroundingOutcome.Ready(GroundedStep(step, Action(ActionKind.BACK, expect = expect.ifEmpty { listOf(Postcondition.UrlChanged) }, effect = EffectClass.NAVIGATE), null, emptyList(), 0.85))
            StepKind.WAIT -> return GroundingOutcome.Ready(GroundedStep(step, Action(ActionKind.WAIT, amount = arg?.toIntOrNull() ?: 1500, effect = EffectClass.READ), null, emptyList(), 1.0))
            StepKind.NAVIGATE -> {
                val url = arg ?: return GroundingOutcome.Missing("navigate without url")
                return GroundingOutcome.Ready(GroundedStep(step, Action(ActionKind.NAVIGATE, url = url, expect = expect.ifEmpty { listOf(Postcondition.UrlChanged) }, effect = EffectClass.NAVIGATE), null, emptyList(), 0.8))
            }
            else -> {}
        }

        val role = step.role ?: return GroundingOutcome.Missing("step has no role")
        if (strict && step.facetKey != null && facetKey == null) return GroundingOutcome.Missing("strict target has no resolved facet")
        if (role == Role.RESULT_ITEM && arg != null && arg !in sps.resultKeys) return GroundingOutcome.Missing("expected item is not on this page")
        val itemKey = if (role == Role.RESULT_ITEM) arg?.takeIf { key -> sps.results?.itemKeys?.contains(key) == true } else null
        if (strict && role == Role.RESULT_ITEM && itemKey == null) return GroundingOutcome.Missing("strict target has no expected item")
        // A choice option in a toggle group is named by its value ("Ford"), so the value is the best hint.
        val nameHint = step.nameHint ?: (if (role == Role.CATEGORY_LINK || role == Role.NAV_LINK || role == Role.TAB || (role == Role.FACET && step.kind == StepKind.SELECT)) arg else null)

        // Opt steps whose expectation already holds are skipped (e.g. filters already open).
        if (step.optional && expect.isNotEmpty() && alreadyHolds(expect, sps)) return GroundingOutcome.Skip("expectation already holds")

        val candidates = grounder.candidates(sps, role, facetKey, nameHint, itemKey, allowFallbackRoles = !strict).filter {
            val a = it.affordance
            a.id !in excludedIds && (!strict || a.role == role && a.sameSite && !a.isCommit && !a.role.isCommit &&
                (facetKey == null || a.facetKey == facetKey) && (role != Role.RESULT_ITEM || a.itemKey == itemKey))
        }
        val best = candidates.firstOrNull()
        val threshold = if (step.optional) 1.2 else 0.9
        if (best == null || best.score < threshold) {
            return if (step.optional) GroundingOutcome.Skip("no confident target for $role${facetKey?.let { "[$it]" } ?: ""}")
            else GroundingOutcome.Missing("no target for $role${facetKey?.let { "[$it]" } ?: ""}" + (best?.let { " (best ${"%.2f".format(it.score)} ${it.affordance.role} '${it.affordance.name}')" } ?: ""))
        }
        val action = actionFor(step, role, facetKey, arg, best.affordance, expect, sps) ?: return GroundingOutcome.Missing("cannot build action for ${step.kind} on ${best.affordance.role}")
        val binding = site?.binding(sps.pageType, role, facetKey)
        val predicted = ((binding?.stats?.p ?: 0.55) * 0.6 + 0.4 * minOf(1.0, best.score / 2.5)).coerceIn(0.05, 0.95)
        return GroundingOutcome.Ready(GroundedStep(step, action, best, candidates.drop(1).take(2), predicted))
    }

    private fun actionFor(step: Step, role: Role, facetKey: String?, arg: String?, a: Affordance, expect: List<Postcondition>, sps: SemanticPageState): Action? {
        val ref = AffordanceRef(role = a.role, facetKey = a.facetKey, nameHint = a.name.takeIf { it.isNotBlank() }, affordanceId = a.id, itemKey = a.itemKey)
        val defaults = { k: ActionKind -> Verifier.defaultExpectations(Action(k, ref, text = arg, submit = step.submit), sps) }
        return when (step.kind) {
            StepKind.CLICK -> Action(ActionKind.CLICK, ref, expect = expect.ifEmpty { defaults(ActionKind.CLICK) }, effect = a.effect)
            StepKind.DISMISS -> Action(ActionKind.DISMISS, ref, expect = expect.ifEmpty { listOf(Postcondition.DialogClosed) }, effect = a.effect)
            StepKind.TYPE -> {
                val text = arg ?: return null
                if (a.tag == "select") return Action(ActionKind.SELECT, ref, text = text, expect = expect.ifEmpty { defaults(ActionKind.SELECT) }, effect = a.effect)
                if (a.role == Role.FACET_OPEN || (a.tag == "button" || a.tag == "a") && a.role != Role.SEARCH_BOX) return Action(ActionKind.CLICK, ref, expect = listOf(Postcondition.anyOf(Postcondition.DialogOpened, Postcondition.RoleAppeared(Role.FACET), Postcondition.RoleAppeared(Role.SEARCH_BOX))), effect = a.effect)
                Action(ActionKind.TYPE, ref, text = text, submit = step.submit, expect = expect.ifEmpty { defaults(ActionKind.TYPE) }, effect = a.effect)
            }
            StepKind.SELECT -> {
                val text = arg ?: return null
                when {
                    a.tag == "select" -> Action(ActionKind.SELECT, ref, text = closestOption(a.choices, text) ?: text, expect = expect.ifEmpty { defaults(ActionKind.SELECT) }, effect = a.effect)
                    a.facetKind == "toggle" || a.facetKind == "choice" -> {
                        // A choice facet: the option itself is the affordance when its name matches; otherwise open it.
                        if (Grounder.nameSimilarity(a.name, text) >= 0.5 || a.tag == "input") Action(ActionKind.CLICK, ref, text = text, expect = expect.ifEmpty { defaults(ActionKind.CLICK) }, effect = a.effect)
                        else Action(ActionKind.SELECT, ref, text = text, expect = expect.ifEmpty { defaults(ActionKind.SELECT) }, effect = a.effect)
                    }
                    a.role == Role.FACET_OPEN || a.tag == "button" -> Action(ActionKind.SELECT, ref, text = text, expect = expect.ifEmpty { defaults(ActionKind.SELECT) }, effect = a.effect)
                    a.tag == "input" -> Action(ActionKind.TYPE, ref, text = text, submit = true, expect = expect.ifEmpty { defaults(ActionKind.TYPE) }, effect = a.effect)
                    else -> Action(ActionKind.SELECT, ref, text = text, expect = expect.ifEmpty { defaults(ActionKind.SELECT) }, effect = a.effect)
                }
            }
            StepKind.SET_RANGE -> {
                val value = arg ?: return null
                val direction = if ((a.facetKey ?: facetKey ?: "").endsWith("_min")) "min" else "max"
                when {
                    a.tag == "select" -> Action(ActionKind.SELECT, ref, text = closestNumericOption(a.choices, value, direction) ?: value, expect = expect.ifEmpty { defaults(ActionKind.SELECT) }, effect = a.effect)
                    a.role == Role.FACET_OPEN || a.tag == "button" -> Action(ActionKind.CLICK, ref, expect = listOf(Postcondition.anyOf(Postcondition.DialogOpened, Postcondition.RoleAppeared(Role.FACET))), effect = a.effect)
                    else -> Action(ActionKind.SET_RANGE, ref, text = value, submit = true, expect = expect.ifEmpty { defaults(ActionKind.SET_RANGE) }, effect = a.effect)
                }
            }
            else -> null
        }
    }

    private fun alreadyHolds(expect: List<Postcondition>, sps: SemanticPageState): Boolean = expect.all { p ->
        when (p) {
            is Postcondition.AnyOf -> p.alternatives.any { alreadyHolds(listOf(it), sps) }
            is Postcondition.DialogClosed -> !sps.dialogOpen
            is Postcondition.DialogOpened -> sps.dialogOpen
            is Postcondition.RoleAppeared -> sps.byRole(p.role).any { p.facetKey == null || it.facetKey == p.facetKey }
            is Postcondition.ComposerReady -> sps.has(Role.COMPOSER_INPUT) && sps.has(Role.SEND)
            is Postcondition.ConstraintApplied -> sps.constraintsActive[p.key]?.let { v -> p.value == null || Verifier.valuesMatch(p.value, v) } == true
            is Postcondition.ValueIs -> (sps.facet(p.facetKey)?.value ?: sps.constraintsActive[p.facetKey])?.let { Verifier.valuesMatch(p.value, it) } == true
            is Postcondition.PageTypeIs -> sps.pageType == p.pageType
            else -> false
        }
    }

    private fun bind(p: Postcondition, params: Map<String, String>): Postcondition = when (p) {
        is Postcondition.AnyOf -> Postcondition.AnyOf(p.alternatives.map { bind(it, params) })
        is Postcondition.ConstraintApplied -> Postcondition.ConstraintApplied(substitute(p.key, params), p.value?.let { substitute(it, params) })
        is Postcondition.RoleAppeared -> p.copy(facetKey = p.facetKey?.let { substitute(it, params) })
        is Postcondition.ValueIs -> Postcondition.ValueIs(substitute(p.facetKey, params), substitute(p.value, params))
        is Postcondition.UrlQueryHas -> Postcondition.UrlQueryHas(substitute(p.key, params))
        else -> p
    }

    companion object {
        fun substitute(template: String, params: Map<String, String>): String {
            if (!template.contains('$')) return template
            return Regex("\\$([A-Za-z_][A-Za-z0-9_]*)").replace(template) { match -> params[match.groupValues[1]] ?: match.value }
        }

        fun closestOption(choices: List<String>, wanted: String): String? {
            if (choices.isEmpty()) return null
            choices.firstOrNull { Vocabulary.normalize(it) == Vocabulary.normalize(wanted) }?.let { return it }
            val w = wanted.lowercase().trim()
            choices.firstOrNull { it.lowercase().trim() == w }?.let { return it }
            choices.firstOrNull { Text.containsAll(it.lowercase(), w) }?.let { return it }
            choices.firstOrNull { Text.containsAll(w, it.lowercase()) && it.length > 2 }?.let { return it }
            return choices.maxByOrNull { Grounder.nameSimilarity(it, wanted) }?.takeIf { Grounder.nameSimilarity(it, wanted) >= 0.5 }
        }

        /** For numeric selects: the largest option <= value (max) or smallest option >= value (min). */
        fun closestNumericOption(choices: List<String>, value: String, direction: String): String? {
            val target = Text.parseAmount(value) ?: return closestOption(choices, value)
            val parsed = choices.mapNotNull { c -> Text.parseAmount(c.replace(Regex("(?i)[^0-9k.,]"), "").trim())?.let { c to it } }
            if (parsed.isEmpty()) return closestOption(choices, value)
            return if (direction == "min") parsed.filter { it.second >= target }.minByOrNull { it.second }?.first ?: parsed.maxByOrNull { it.second }?.first
            else parsed.filter { it.second <= target }.maxByOrNull { it.second }?.first ?: parsed.minByOrNull { it.second }?.first
        }
    }
}
