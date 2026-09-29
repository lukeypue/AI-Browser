package com.appgate.brain.memory

import com.appgate.brain.model.PageType
import com.appgate.brain.model.Precondition
import com.appgate.brain.model.Role
import com.appgate.brain.model.SemanticPageState
import com.appgate.brain.model.Skill
import com.appgate.brain.model.SkillOrigin
import com.appgate.brain.model.TaskLedger
import com.appgate.brain.model.EffectClass
import com.appgate.brain.model.StepKind
import com.appgate.brain.model.Postcondition
import com.appgate.brain.engine.StepGrounder
import com.appgate.brain.engine.GroundingOutcome
import com.appgate.brain.skills.PortableSkills
import com.appgate.brain.util.Hashing
import com.appgate.brain.util.Text

/** Procedural memory: builtin + compiled + demonstrated skills, retrieved by intent and preconditions. */
class SkillLibrary(private val memory: Memory) {

    fun all(): List<Skill> = memory.loadSkills().values.toList()

    fun get(id: String): Skill? = memory.loadSkills()[id]

    fun put(skill: Skill) {
        val map = memory.loadSkills()
        map[skill.id] = skill
        memory.saveSkills(map)
    }

    fun remove(id: String) {
        val map = memory.loadSkills()
        if (map.remove(id) != null) memory.saveSkills(map)
    }

    fun recordOutcome(skillId: String, host: String, success: Boolean) {
        val map = memory.loadSkills()
        val s = map[skillId] ?: return
        val updated = s.withOutcome(host, success, memory.now())
        // Keep a failed learned procedure on hold until independent verified recompilation.
        map[skillId] = if (!success && s.origin != SkillOrigin.BUILTIN)
            updated.copy(tags = updated.tags + PortableSkills.failureTag(host)) else updated
        memory.saveSkills(map)
    }

    /** Skills whose preconditions hold on this page, ranked by per-host success then global success. */
    fun applicable(sps: SemanticPageState, tags: Set<String> = emptySet()): List<Skill> = all()
        .filter { s -> tags.isEmpty() || s.tags.any { it in tags } }
        .filter { s -> preconditionsHold(s, sps) }
        .sortedByDescending { s -> score(s, sps.host) }

    fun preconditionsHold(skill: Skill, sps: SemanticPageState, params: Map<String, String> = emptyMap()): Boolean = skill.pre.all { pre ->
        when (pre) {
            is Precondition.PageTypeIn -> sps.pageType in pre.types
            is Precondition.HasRole -> sps.affordances.any { it.visible && it.enabled && it.role == pre.role &&
                (pre.facetKey == null || it.facetKey == StepGrounder.substitute(pre.facetKey, params)) }
        }
    }

    /** Prefer verified local procedures; strong source evidence can admit one safe probation on a new host. */
    fun reusable(sps: SemanticPageState, capability: String, params: Map<String, String>, ledger: TaskLedger): Skill? {
        if (sps.isHumanOnly || sps.pageType !in PortableSkills.pages) return null
        // The engine validates live URL/profile aliases. Outcomes use the task's site identity.
        val host = ledger.host
        val candidates = all().filter { skill ->
            skill.origin != SkillOrigin.BUILTIN && "verified_v2" in skill.tags && "capability:$capability" in skill.tags &&
                PortableSkills.failureTag(host) !in skill.tags && PortableSkills.safeProgram(skill.body, skill.post) &&
                (capability == "custom" || PortableSkills.compatible(capability, skill.body, skill.post)) &&
                (skill.params + PortableSkills.parameters(skill.body, skill.post)).all {
                    params[it]?.let { value -> value.isNotBlank() && !PortableSkills.isParameter(value) } == true
                } && boundKeysValid(skill, capability, params) &&
                preconditionsHold(skill, sps, params) && entryMatches(skill, sps, params, ledger) &&
                (ledger.goal.intent != com.appgate.brain.model.GoalIntent.LEARN_SITE ||
                    FailedStrategies.allowed(memory.site(ledger.host), FailedStrategies.key(sps, capability, skill.body, params), memory.now()))
        }
        candidates.filter { it.stat(host).let { local -> local.successes > 0 && local.p >= 0.5 } }
            .maxByOrNull { score(it, host) }?.let { return it }
        if (capability !in PortableSkills.capabilities) return null
        return candidates.filter { skill ->
            skill.stat(host).failures == 0.0 &&
                skill.pre.any { it is Precondition.PageTypeIn && sps.pageType in it.types && it.types.all { page -> page in PortableSkills.pages } } &&
                skill.statsByHost.any { (sourceHost, stats) -> sourceHost != host && PortableSkills.failureTag(sourceHost) !in skill.tags &&
                    stats.decayed(memory.now()).let { source -> source.successes >= 2.0 - 1e-6 && source.p >= 0.75 - 1e-6 } }
        }.maxByOrNull { score(it, host) }
    }

    private fun boundKeysValid(skill: Skill, capability: String, params: Map<String, String>): Boolean {
        fun key(value: String) = StepGrounder.substitute(value, params)
        fun stateKey(value: String) = key(value).let { it == "query" || PortableSkills.canonicalFacet(it) }
        fun post(p: Postcondition): Boolean = when (p) {
            is Postcondition.AnyOf -> p.alternatives.all(::post)
            is Postcondition.ValueIs -> stateKey(p.facetKey)
            is Postcondition.ConstraintApplied -> stateKey(p.key)
            is Postcondition.UrlQueryHas -> key(p.key) in setOf("q", "query", "search", "keyword", "sort", "order", "page", "offset")
            else -> true
        }
        return skill.post.all(::post) && skill.body.all { step ->
            (step.facetKey == null || PortableSkills.canonicalFacet(key(step.facetKey))) && step.expect.all(::post) &&
                (capability != "constrain_numeric" || step.role != Role.FACET || step.facetKey != null &&
                    PortableSkills.numericFacet(key(step.facetKey)) && step.arg != null && Text.parseAmount(key(step.arg)) != null)
        }
    }

    private fun entryMatches(skill: Skill, sps: SemanticPageState, params: Map<String, String>, ledger: TaskLedger): Boolean {
        val step = PortableSkills.entry(skill.body) ?: return false
        val bound = step.copy(arg = step.arg?.let { StepGrounder.substitute(it, params) },
            facetKey = step.facetKey?.let { StepGrounder.substitute(it, params) })
        if ((ledger.actionStates[Hashing.short("${sps.hash}|${bound.toJson()}")] ?: 0) >= 2) return false
        if (bound.facetKey != null && !PortableSkills.canonicalFacet(bound.facetKey)) return false
        if (bound.kind == StepKind.SCROLL) return sps.pageType == PageType.RESULTS
        if (bound.kind == StepKind.BACK) return true
        if (bound.role == Role.RESULT_ITEM && bound.arg !in sps.resultKeys) return false
        // Validate the actual ranked choice, not just the existence of an exact candidate.
        val ready = StepGrounder(memory.site(ledger.host)).ground(step, params, sps, ledger.visited) as? GroundingOutcome.Ready ?: return false
        val a = ready.grounded.chosen?.affordance ?: return false
        return a.visible && a.enabled && a.role == bound.role &&
            a.effect != EffectClass.COMMIT_EXTERNAL && a.sameSite &&
            (bound.facetKey == null || a.facetKey == bound.facetKey) &&
            (bound.role != Role.RESULT_ITEM || a.itemKey == bound.arg)
    }

    fun score(skill: Skill, host: String): Double {
        val local = skill.stat(host)
        val global = skill.statsByHost.values.fold(0.0 to 0.0) { acc, s -> (acc.first + s.successes) to (acc.second + s.failures) }
        val globalP = (1 + global.first) / (2 + global.first + global.second)
        val localWeight = (local.n / (local.n + 3.0)).coerceIn(0.0, 1.0)
        return localWeight * local.p + (1 - localWeight) * globalP + (if (skill.origin == SkillOrigin.BUILTIN) 0.05 else 0.0)
    }

    /** Retrieval by free-text intent for planner context: top-k by token overlap with the intent string and tags. */
    fun retrieve(intent: String, k: Int = 5): List<Skill> {
        val q = Text.tokens(intent).toSet()
        return all().map { s ->
            val t = (Text.tokens(s.intent) + s.tags + s.id.split('_')).toSet()
            s to q.count { it in t }.toDouble() / maxOf(1, q.size)
        }.filter { it.second > 0 }.sortedByDescending { it.second }.take(k).map { it.first }
    }

    fun skillsForRole(role: Role, pageType: PageType): List<Skill> = all().filter { s -> s.body.any { it.role == role } && s.pre.all { it !is Precondition.PageTypeIn || pageType in it.types } }
}
