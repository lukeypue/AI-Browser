package com.appgate.brain.skills

import com.appgate.brain.json.JsonArray
import com.appgate.brain.memory.Memory
import com.appgate.brain.model.BetaStat
import com.appgate.brain.model.PageType
import com.appgate.brain.model.Precondition
import com.appgate.brain.model.Role
import com.appgate.brain.model.SemanticPageState
import com.appgate.brain.model.Skill
import com.appgate.brain.model.SkillOrigin
import com.appgate.brain.model.Step
import com.appgate.brain.model.StepKind
import com.appgate.brain.model.Postcondition
import com.appgate.brain.model.ConstraintOp
import com.appgate.brain.model.TaskLedger
import com.appgate.brain.planner.Planner
import com.appgate.brain.util.Hashing

/**
 * Turns verified planner programs and explained human demonstrations into reusable skills:
 * abstract concrete values into parameters, drop steps that were unnecessary, keep Opt for
 * steps that verified on some runs only, generalize preconditions, and seed stats from the
 * runs that produced the skill.
 */
object SkillCompiler {

    /** A planner program that verified end to end becomes a COMPILED skill (or reinforces an existing one). */
    fun compileFromPlanner(memory: Memory, ledger: TaskLedger, program: List<Step>, now: Long): Skill? {
        if (program.isEmpty() || !ledger.programCompleted || ledger.programPage == null || ledger.programPost.isEmpty()) return null
        if (program.indices.filter { !program[it].optional }.let { it.isEmpty() || !ledger.programVerifiedSteps.containsAll(it) }) return null
        val abstracted = abstractSteps(program, ledger)
        // Never persist task values, item names or literal browsing URLs in procedural memory.
        if (abstracted.steps.any { it.kind == StepKind.NAVIGATE || (it.arg != null && !it.arg.startsWith("$")) }) return null
        val post = abstractSteps(listOf(Step(StepKind.WAIT, expect = ledger.programPost)), ledger).steps.single().expect
        val signature = "${ledger.host}|${ledger.programPage}|${ledger.programCapability}|" + abstracted.steps.joinToString("|") { it.toJson().toString() } + post.joinToString { it.toJson().toString() }
        val id = "compiled_" + Hashing.short(signature).take(10)
        val existing = memory.skills.get(id)
        val pageTypes = setOf(ledger.programPage!!)
        val skill = existing?.withOutcome(ledger.host, true, now) ?: Skill(
            id = id, version = 1,
            intent = "verified procedure for ${ledger.programCapability}",
            params = abstracted.params,
            pre = listOf(Precondition.PageTypeIn(pageTypes)) + abstracted.steps.firstOrNull()?.role?.let { listOf(Precondition.HasRole(it)) }.orEmpty(),
            body = abstracted.steps,
            post = post,
            origin = SkillOrigin.COMPILED,
            statsByHost = mapOf(ledger.host to BetaStat().record(true, now)),
            provenance = listOf("task:${ledger.id}"),
            tags = setOf("compiled", "verified_v2", "capability:${ledger.programCapability}")
        )
        memory.skills.put(skill)
        return skill
    }

    data class Abstracted(val steps: List<Step>, val params: List<String>)

    /** Replace typed values with parameters: the goal query becomes $query, constraint values become $value. */
    fun abstractSteps(program: List<Step>, ledger: TaskLedger): Abstracted {
        val params = LinkedHashSet<String>()
        val query = ledger.goal.query
        val values = ledger.goal.constraints.associate { c ->
            val key = when (c.op) { ConstraintOp.LTE -> "${c.key}_max"; ConstraintOp.GTE -> "${c.key}_min"; else -> c.key }
            key to c.value
        }
        fun value(text: String?, facet: String?): String? {
            if (text == null) return null
            val key = when {
                query.isNotBlank() && text.equals(query, true) -> "query"
                facet != null && values[facet]?.equals(text, true) == true -> facet
                else -> values.entries.filter { it.value.equals(text, true) }.singleOrNull()?.key
            }
            return if (key != null) { params += key; "\$$key" } else text
        }
        fun expect(p: Postcondition): Postcondition = when (p) {
            is Postcondition.AnyOf -> Postcondition.AnyOf(p.alternatives.map { expect(it) })
            is Postcondition.ValueIs -> p.copy(value = value(p.value, p.facetKey)!!)
            is Postcondition.ConstraintApplied -> p.copy(value = value(p.value, p.key))
            is Postcondition.DetailMatches -> Postcondition.DetailMatches(null)
            else -> p
        }
        val steps = program.map { s ->
            val arg = if (s.kind == StepKind.CLICK && s.role == Role.RESULT_ITEM) { params += "item"; "\$item" } else value(s.arg, s.facetKey)
            s.copy(arg = arg, expect = s.expect.map { expect(it) }, nameHint = null)
        }
        return Abstracted(steps, params.toList())
    }

    /**
     * A human demonstration: (before SPS, actions on typed affordances, after SPS). The planner
     * explains it as a program; we abstract by contrast (features present on the clicked element
     * but absent on unclicked siblings are kept in the binding; values become parameters).
     */
    fun compileFromDemonstration(memory: Memory, planner: Planner?, host: String, trace: JsonArray, before: SemanticPageState, after: SemanticPageState, now: Long): Skill? {
        if (trace.size == 0) return null
        val explained = planner?.let { runCatching { it.explainDemonstration(trace, before, after) }.getOrNull() }
        val steps = explained?.steps?.takeIf { it.isNotEmpty() } ?: stepsFromTrace(trace)
        if (steps.isEmpty()) return null
        val params = steps.mapNotNull { it.arg }.filter { it.startsWith("$") }.map { it.removePrefix("$") }.distinct()
        val signature = steps.joinToString("|") { it.describe() }
        val id = "demo_" + Hashing.short(signature).take(10)
        val skill = memory.skills.get(id) ?: Skill(
            id = id, version = 1,
            intent = explained?.rationale?.ifBlank { null } ?: ("demonstrated: " + steps.joinToString(", ") { it.describe() }.take(140)),
            params = params,
            pre = listOf(Precondition.PageTypeIn(setOf(before.pageType))) + steps.firstOrNull()?.role?.let { listOf(Precondition.HasRole(it)) }.orEmpty(),
            body = steps,
            post = steps.lastOrNull()?.expect.orEmpty(),
            origin = SkillOrigin.DEMONSTRATED,
            statsByHost = mapOf(host to BetaStat()),
            provenance = listOf("demo@${before.pageType}"),
            tags = setOf("demonstrated")
        )
        memory.skills.put(skill)
        return skill
    }

    /** Fallback without a planner: one step per recorded human action on a typed affordance. */
    private fun stepsFromTrace(trace: JsonArray): List<Step> = trace.objects().mapNotNull { t ->
        val role = Role.parse(t.optStringOrNull("role"))
        if (role == Role.UNKNOWN || role.isCommit) return@mapNotNull null
        val kind = when (t.optString("kind")) { "type" -> StepKind.TYPE; "select" -> StepKind.SELECT; "scroll" -> StepKind.SCROLL; else -> StepKind.CLICK }
        val arg = when (kind) { StepKind.TYPE -> "\$value"; StepKind.SELECT -> "\$value"; else -> null }
        Step(kind, role, t.optStringOrNull("facet"), arg, optional = false, submit = t.optBoolean("submit"), nameHint = t.optStringOrNull("name"),
            expect = com.appgate.brain.verify.Verifier.defaultExpectations(
                com.appgate.brain.model.Action(if (kind == StepKind.TYPE) com.appgate.brain.model.ActionKind.TYPE else com.appgate.brain.model.ActionKind.CLICK,
                    com.appgate.brain.model.AffordanceRef(role, t.optStringOrNull("facet")), submit = t.optBoolean("submit")),
                SemanticPageState("", "", "", "", PageType.UNKNOWN, 0.0, emptyList(), emptyList(), emptyList(), emptyMap(), com.appgate.brain.model.Settle.UNKNOWN, false, false, false, "")))
    }
}
