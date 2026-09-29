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
import com.appgate.brain.perception.Vocabulary
import com.appgate.brain.util.Hashing

/**
 * Turns verified planner programs and explained human demonstrations into reusable skills:
 * abstract concrete values into parameters, drop steps that were unnecessary, keep Opt for
 * steps that verified on some runs only, generalize preconditions, and seed stats from the
 * runs that produced the skill.
 */
object SkillCompiler {

    /** A planner program that verified end to end becomes a COMPILED skill (or reinforces an existing one). */
    fun compileFromPlanner(memory: Memory, ledger: TaskLedger, program: List<Step>, now: Long): Skill? =
        compileVerified(memory, ledger, program, now)

    /** Shared by verified local repairs and planner programs; proposing a program is never evidence. */
    fun compileVerified(memory: Memory, ledger: TaskLedger, program: List<Step>, now: Long): Skill? {
        if (program.isEmpty() || !ledger.programCompleted || ledger.programPage == null || ledger.programPost.isEmpty()) return null
        if (program.indices.filter { !program[it].optional }.let { it.isEmpty() || !ledger.programVerifiedSteps.containsAll(it) }) return null
        if (ledger.programPage !in PortableSkills.pages || ledger.programCapability !in PortableSkills.capabilities + "custom") return null
        val abstracted = abstractSteps(program, ledger)
        val post = abstractSteps(listOf(Step(StepKind.WAIT, expect = ledger.programPost)), ledger).steps.single().expect
        if (!PortableSkills.safeProgram(abstracted.steps, post)) return null
        if (ledger.programCapability != "custom" && !PortableSkills.compatible(ledger.programCapability, abstracted.steps, post)) return null
        val entry = PortableSkills.entry(abstracted.steps) ?: return null
        val params = PortableSkills.parameters(abstracted.steps, post).sorted()
        val signature = "${ledger.programPage}|${ledger.programCapability}|" + abstracted.steps.joinToString("|") { it.toJson().toString() } + post.joinToString { it.toJson().toString() }
        val id = "compiled_" + Hashing.short(signature).take(10)
        val existing = memory.skills.get(id)
        val pageTypes = setOf(ledger.programPage!!)
        val skill = existing?.withOutcome(ledger.host, true, now)?.let {
            // A verified local repair clears only this host's hold, never another host's failure.
            it.copy(tags = it.tags - PortableSkills.failureTag(ledger.host))
        } ?: Skill(
            id = id, version = 2,
            intent = "verified procedure for ${ledger.programCapability}",
            params = params,
            pre = listOf(Precondition.PageTypeIn(pageTypes)) + entry.role?.let { listOf(Precondition.HasRole(it, entry.facetKey)) }.orEmpty(),
            body = abstracted.steps,
            post = post,
            origin = SkillOrigin.COMPILED,
            statsByHost = mapOf(ledger.host to BetaStat().record(true, now)),
            provenance = listOf("verified:${Hashing.short(ledger.id)}"),
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
        val values = ledger.effectiveGoal.constraints.associate { c ->
            val key = when (c.op) { ConstraintOp.LTE -> "${c.key}_max"; ConstraintOp.GTE -> "${c.key}_min"; else -> c.key }
            key to c.value
        }
        fun value(text: String?, facet: String?): String? {
            if (text == null) return null
            if (PortableSkills.isParameter(text)) { params += text.removePrefix("$"); return text }
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
            val arg = when {
                s.kind == StepKind.CLICK && s.role == Role.RESULT_ITEM -> { params += "item"; "\$item" }
                s.kind == StepKind.SCROLL -> s.arg?.toIntOrNull()?.toString() ?: s.arg
                else -> value(s.arg, s.facetKey)
            }
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

/** Structural checks shared by compilation and probationary reuse, including legacy persisted skills. */
internal object PortableSkills {
    val pages = setOf(PageType.HOME, PageType.SEARCH, PageType.RESULTS, PageType.FACET_PANEL, PageType.DETAIL, PageType.DIALOG, PageType.UNKNOWN)
    val capabilities = setOf("search", "constrain_numeric", "select_facet", "open_filters", "apply_filters", "sort_results",
        "next_page", "load_more", "scroll_results", "open_item", "expand_description", "go_back", "dismiss_dialog", "open_category")
    private val safeRoles = setOf(Role.SEARCH_BOX, Role.SUBMIT, Role.FACET, Role.FACET_OPEN, Role.FACET_APPLY, Role.FACET_CLEAR,
        Role.SORT, Role.PAGE_NEXT, Role.PAGE_PREV, Role.LOAD_MORE, Role.RESULT_ITEM, Role.EXPAND_TEXT, Role.CATEGORY_LINK,
        Role.NAV_LINK, Role.TAB, Role.CLOSE, Role.BACK)
    private val parameter = Regex("\\$[A-Za-z_][A-Za-z0-9_]{0,63}")
    fun isParameter(value: String): Boolean = parameter.matches(value)
    fun canonicalFacet(key: String): Boolean = key in Vocabulary.facetLexicon ||
        (key.endsWith("_min") || key.endsWith("_max")) && key.dropLast(4) in Vocabulary.numericKeys
    fun numericFacet(key: String): Boolean = key.removeSuffix("_min").removeSuffix("_max") in Vocabulary.numericKeys
    private fun key(value: String) = isParameter(value) || canonicalFacet(value)
    private fun stateKey(value: String) = value == "query" || key(value)
    private fun value(value: String?) = value == null || isParameter(value)
    fun failureTag(host: String) = "failed_host:${Hashing.short(host)}"
    fun entry(body: List<Step>): Step? = body.firstOrNull { !it.optional && it.kind != StepKind.WAIT }

    fun safeProgram(body: List<Step>, post: List<Postcondition>): Boolean = body.size in 1..8 && entry(body) != null &&
        post.isNotEmpty() && post.all(::safePost) && body.all { s ->
            (s.role == null || s.role in safeRoles) && s.nameHint == null &&
                (s.facetKey == null || key(s.facetKey)) && s.expect.all(::safePost) &&
                when (s.kind) {
                    StepKind.NAVIGATE -> false
                    StepKind.SCROLL -> s.role == null && (s.arg == null || s.arg.toIntOrNull()?.let { it in 1..2000 } == true)
                    StepKind.BACK -> s.role in setOf(null, Role.BACK) && s.arg == null
                    StepKind.WAIT -> s.role == null && s.arg == null
                    StepKind.DISMISS -> s.role == Role.CLOSE && s.arg == null
                    StepKind.TYPE -> s.role in setOf(Role.SEARCH_BOX, Role.FACET) && s.arg != null && value(s.arg)
                    StepKind.SELECT -> s.role in setOf(Role.FACET, Role.SORT) && s.arg != null && value(s.arg)
                    StepKind.SET_RANGE -> s.role == Role.FACET && s.arg != null && value(s.arg)
                    StepKind.CLICK -> s.role != null && value(s.arg)
                }
        }

    private fun safePost(p: Postcondition): Boolean = when (p) {
        is Postcondition.AnyOf -> p.alternatives.isNotEmpty() && p.alternatives.all(::safePost)
        is Postcondition.ConstraintApplied -> stateKey(p.key) && value(p.value)
        is Postcondition.ValueIs -> stateKey(p.facetKey) && isParameter(p.value)
        is Postcondition.DetailMatches -> p.itemKey == null
        is Postcondition.UrlQueryHas -> p.key in setOf("q", "query", "search", "keyword", "sort", "order", "page", "offset") || isParameter(p.key)
        is Postcondition.PageTypeIs -> p.pageType in pages
        is Postcondition.RoleAppeared -> p.role in safeRoles
        Postcondition.ComposerReady -> false
        else -> true
    }

    fun parameters(body: List<Step>, post: List<Postcondition>): Set<String> {
        val result = linkedSetOf<String>()
        fun add(value: String?) { if (value != null && isParameter(value)) result += value.removePrefix("$") }
        fun visit(p: Postcondition) { when (p) {
            is Postcondition.AnyOf -> p.alternatives.forEach(::visit)
            is Postcondition.ConstraintApplied -> { add(p.key); add(p.value) }
            is Postcondition.ValueIs -> { add(p.facetKey); add(p.value) }
            is Postcondition.UrlQueryHas -> add(p.key)
            else -> {}
        } }
        body.forEach { add(it.facetKey); add(it.arg); it.expect.forEach(::visit) }
        post.forEach(::visit)
        return result
    }

    /** A conjunction proves a capability if one clause does; every branch of an OR must prove it. */
    private fun proves(post: List<Postcondition>, predicate: (Postcondition) -> Boolean): Boolean {
        fun guarantee(p: Postcondition): Boolean = if (p is Postcondition.AnyOf)
            p.alternatives.isNotEmpty() && p.alternatives.all(::guarantee) else predicate(p)
        return post.any(::guarantee)
    }

    fun compatible(capability: String, body: List<Step>, post: List<Postcondition>): Boolean {
        if (capability !in capabilities) return false
        val required = body.filter { !it.optional && it.kind != StepKind.WAIT }
        val roles = when (capability) {
            "search" -> setOf(Role.SEARCH_BOX, Role.SUBMIT, Role.TAB)
            "constrain_numeric", "select_facet" -> setOf(Role.FACET, Role.FACET_OPEN, Role.FACET_APPLY)
            "open_filters" -> setOf(Role.FACET_OPEN)
            "apply_filters" -> setOf(Role.FACET_APPLY)
            "sort_results" -> setOf(Role.SORT)
            "next_page", "load_more", "scroll_results" -> setOf(Role.PAGE_NEXT, Role.LOAD_MORE)
            "open_item" -> setOf(Role.RESULT_ITEM)
            "expand_description" -> setOf(Role.EXPAND_TEXT)
            "open_category" -> setOf(Role.CATEGORY_LINK)
            "go_back" -> setOf(Role.BACK)
            else -> emptySet()
        } + Role.CLOSE
        if (body.any { it.role != null && it.role !in roles }) return false
        if (body.any { it.kind == StepKind.BACK && capability != "go_back" ||
                it.kind == StepKind.SCROLL && capability !in setOf("next_page", "load_more", "scroll_results") }) return false
        fun has(role: Role) = required.any { it.role == role }
        fun result(p: Postcondition) = p == Postcondition.ResultsChanged || p == Postcondition.NewResults
        fun pagination() = has(Role.PAGE_NEXT) || has(Role.LOAD_MORE) || required.any { it.kind == StepKind.SCROLL }
        return when (capability) {
            "search" -> required.any { it.kind == StepKind.TYPE && it.role == Role.SEARCH_BOX && it.arg == "\$query" } &&
                proves(post) { result(it) || it == Postcondition.PageTypeIs(PageType.RESULTS) }
            "constrain_numeric", "select_facet" -> {
                val facets = required.filter { it.role == Role.FACET }
                facets.isNotEmpty() && facets.all { s -> s.facetKey != null && s.arg != null && proves(post) {
                    it == Postcondition.ConstraintApplied(s.facetKey, s.arg) || it == Postcondition.ValueIs(s.facetKey, s.arg)
                } }
            }
            "open_filters" -> has(Role.FACET_OPEN) && proves(post) { it == Postcondition.DialogOpened ||
                it == Postcondition.RoleAppeared(Role.FACET) || it == Postcondition.RoleAppeared(Role.FACET_APPLY) || it == Postcondition.PageTypeIs(PageType.FACET_PANEL) }
            "apply_filters" -> has(Role.FACET_APPLY) && proves(post) { it == Postcondition.ResultsChanged || it == Postcondition.DialogClosed || it == Postcondition.PageTypeIs(PageType.RESULTS) }
            "sort_results" -> has(Role.SORT) && proves(post) { it == Postcondition.ResultsChanged }
            "next_page" -> pagination() && proves(post, ::result)
            "load_more", "scroll_results" -> pagination() &&
                proves(post) { it == Postcondition.NewResults || it == Postcondition.EndOfResults }
            "open_item" -> has(Role.RESULT_ITEM) && proves(post) { it == Postcondition.DetailMatches(null) || it == Postcondition.PageTypeIs(PageType.DETAIL) }
            "expand_description" -> has(Role.EXPAND_TEXT) && proves(post) { it == Postcondition.TextExpanded }
            "dismiss_dialog" -> has(Role.CLOSE) && proves(post) { it == Postcondition.DialogClosed }
            "go_back" -> required.any { it.kind == StepKind.BACK } && proves(post) { it == Postcondition.UrlChanged }
            "open_category" -> has(Role.CATEGORY_LINK) && proves(post) { it == Postcondition.UrlChanged }
            else -> false
        }
    }
}
