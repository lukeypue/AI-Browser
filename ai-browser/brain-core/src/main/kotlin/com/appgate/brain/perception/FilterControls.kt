package com.appgate.brain.perception

import com.appgate.brain.model.Affordance
import com.appgate.brain.model.RegionRole
import com.appgate.brain.model.Role

/** Generic checkboxes (cookies, preferences) are not evidence of a search filter. */
object FilterControls {
    fun inDialog(a: Affordance): Boolean = a.regionRole == RegionRole.DIALOG || a.features["in_dialog"] > 0

    fun isFilter(a: Affordance): Boolean = when (a.role) {
        Role.FACET_APPLY, Role.FACET_CLEAR -> true
        Role.FACET, Role.FACET_OPEN -> a.facetKey?.removeSuffix("_min")?.removeSuffix("_max") in Vocabulary.facetLexicon
        else -> false
    }
}
