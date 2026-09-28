package com.appgate.brain.memory

import com.appgate.brain.model.PageType
import com.appgate.brain.model.Precondition
import com.appgate.brain.model.Role
import com.appgate.brain.model.SemanticPageState
import com.appgate.brain.model.Skill
import com.appgate.brain.model.SkillOrigin
import com.appgate.brain.model.TaskLedger
import com.appgate.brain.engine.StepGrounder
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
        map[skillId] = s.withOutcome(host, success, memory.now())
        memory.saveSkills(map)
    }

    /** Skills whose preconditions hold on this page, ranked by per-host success then global success. */
    fun applicable(sps: SemanticPageState, tags: Set<String> = emptySet()): List<Skill> = all()
        .filter { s -> tags.isEmpty() || s.tags.any { it in tags } }
        .filter { s -> preconditionsHold(s, sps) }
        .sortedByDescending { s -> score(s, sps.host) }

    fun preconditionsHold(skill: Skill, sps: SemanticPageState): Boolean = skill.pre.all { pre ->
        when (pre) {
            is Precondition.PageTypeIn -> sps.pageType in pre.types
            is Precondition.HasRole -> sps.affordances.any { it.visible && it.enabled && it.role == pre.role && (pre.facetKey == null || it.facetKey == pre.facetKey) }
        }
    }

    /** Only evidence-backed, same-host procedures for this capability may run without a model. */
    fun reusable(sps: SemanticPageState, capability: String, params: Map<String, String>, ledger: TaskLedger): Skill? =
        applicable(sps, setOf("capability:$capability")).firstOrNull { skill ->
            skill.origin != SkillOrigin.BUILTIN && "verified_v2" in skill.tags &&
                skill.stat(ledger.host).successes > 0 && skill.stat(ledger.host).p >= 0.5 &&
                skill.params.all { params[it]?.isNotBlank() == true } &&
                skill.body.firstOrNull()?.let { step ->
                    val bound = step.copy(arg = step.arg?.let { StepGrounder.substitute(it, params) }, facetKey = step.facetKey?.let { StepGrounder.substitute(it, params) })
                    (ledger.actionStates[Hashing.short("${sps.hash}|${bound.toJson()}")] ?: 0) < 2
                } == true
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
