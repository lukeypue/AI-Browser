package com.appgate.brain.skills

import com.appgate.brain.model.Affordance
import com.appgate.brain.model.Role
import com.appgate.brain.model.SemanticPageState
import com.appgate.brain.model.SiteModel
import com.appgate.brain.perception.Vocabulary
import com.appgate.brain.util.Text

data class Grounding(val affordance: Affordance, val score: Double, val reasons: List<String>)

/**
 * Grounding ranks live affordances for an abstract target. Score is a transparent linear
 * model over: role agreement, facet key agreement, name similarity, binding feature
 * similarity (a bounded bonus, never a short-circuit), region priors and failure memory.
 */
class Grounder(private val site: SiteModel?) {

    fun candidates(
        sps: SemanticPageState,
        role: Role,
        facetKey: String? = null,
        nameHint: String? = null,
        itemKey: String? = null,
        allowFallbackRoles: Boolean = true
    ): List<Grounding> {
        val out = ArrayList<Grounding>()
        val binding = site?.binding(sps.pageType, role, facetKey)
        val dialogClosers = if (role == Role.CLOSE && sps.dialogOpen) sps.affordances.filter {
            it.visible && it.enabled && it.role == Role.CLOSE &&
                (it.regionRole == com.appgate.brain.model.RegionRole.DIALOG || it.features["in_dialog"] > 0)
        }.map { it.id }.toSet() else emptySet()
        for (a in sps.affordances) {
            if (!a.visible || !a.enabled) continue
            if (dialogClosers.isNotEmpty() && a.id !in dialogClosers) continue
            val reasons = ArrayList<String>(4)
            var s = 0.0
            when {
                a.role == role -> { s += 1.0; reasons += "role" }
                allowFallbackRoles && compatible(role, a.role) -> { s += 0.45; reasons += "compatible role ${a.role}" }
                else -> continue
            }
            if (itemKey != null) {
                if (a.itemKey == itemKey) { s += 2.0; reasons += "item key" } else continue
            }
            if (facetKey != null) {
                when {
                    a.facetKey == facetKey -> { s += 1.0; reasons += "facet" }
                    a.facetKey != null && baseKey(a.facetKey) == baseKey(facetKey) -> { s += 0.5; reasons += "facet family" }
                    a.facetKey == null && Vocabulary.canonicalFacet(a.name) == baseKey(facetKey) -> { s += 0.4; reasons += "facet by name" }
                    a.facetKey != null -> { s -= 0.6 }
                    else -> { s -= 0.2 }
                }
            }
            if (!nameHint.isNullOrBlank()) {
                val sim = nameSimilarity(nameHint, a.name)
                if (sim > 0) { s += 0.8 * sim; reasons += "name ${"%.2f".format(sim)}" }
                else if (role == Role.CATEGORY_LINK || role == Role.NAV_LINK || role == Role.GENERIC_LINK) continue
            }
            if (binding != null) {
                val sim = binding.features.similarity(a.features)
                val bonus = (0.35 * sim * binding.stats.p).coerceAtMost(0.35)
                if (bonus > 0.02) { s += bonus; reasons += "binding ${"%.2f".format(sim)}" }
                if (binding.nameHints.any { it.equals(a.name, true) }) { s += 0.15; reasons += "binding name" }
            }
            s += 0.3 * a.roleScore
            val fails = site?.failureCount(sps.pageType, a.role, a.facetKey) ?: 0
            if (fails > 0) {
                // Role-wide history ranks targets; it must not permanently hide a
                // confidently recognized live control. Procedure cooldowns still bound retries.
                val penaltyLimit = if (a.role == role && a.roleScore >= 0.8) 0.25 else 0.6
                s -= (0.15 * fails).coerceAtMost(penaltyLimit); reasons += "failures $fails"
            }
            if (a.regionRole.name == "FOOTER" && role !in setOf(Role.PAGE_NEXT, Role.PAGE_PREV, Role.LOAD_MORE)) s -= 0.5
            if (a.bbox != null && a.bbox.y < 0) s -= 0.2
            out += Grounding(a, s, reasons)
        }
        return out.sortedByDescending { it.score }
    }

    fun best(sps: SemanticPageState, role: Role, facetKey: String? = null, nameHint: String? = null, itemKey: String? = null): Grounding? =
        candidates(sps, role, facetKey, nameHint, itemKey).firstOrNull()

    private fun baseKey(key: String): String = key.removeSuffix("_min").removeSuffix("_max")

    private fun compatible(wanted: Role, actual: Role): Boolean = when (wanted) {
        Role.FACET -> actual == Role.FACET_OPEN || actual == Role.GENERIC_BUTTON
        Role.FACET_OPEN -> actual == Role.FACET || actual == Role.GENERIC_BUTTON
        Role.FACET_APPLY -> actual == Role.SUBMIT || actual == Role.GENERIC_BUTTON
        Role.SUBMIT -> actual == Role.GENERIC_BUTTON
        Role.LOAD_MORE -> actual == Role.PAGE_NEXT
        Role.PAGE_NEXT -> actual == Role.LOAD_MORE
        Role.CLOSE -> actual == Role.GENERIC_BUTTON || actual == Role.BACK
        Role.EXPAND_TEXT -> actual == Role.GENERIC_BUTTON || actual == Role.TAB
        Role.CATEGORY_LINK -> actual == Role.NAV_LINK || actual == Role.GENERIC_LINK
        Role.RESULT_ITEM -> actual == Role.GENERIC_LINK
        Role.MESSAGE_SELLER -> actual == Role.GENERIC_BUTTON
        else -> false
    }

    companion object {
        fun nameSimilarity(a: String, b: String): Double {
            val ta = Text.tokens(a).toSet(); val tb = Text.tokens(b).toSet()
            if (ta.isEmpty() || tb.isEmpty()) return 0.0
            if (Vocabulary.normalize(a) == Vocabulary.normalize(b)) return 1.0
            val inter = ta.count { it in tb }
            return inter.toDouble() / maxOf(ta.size, tb.size)
        }
    }
}
