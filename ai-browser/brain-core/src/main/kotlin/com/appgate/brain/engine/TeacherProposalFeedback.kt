package com.appgate.brain.engine

import com.appgate.brain.json.JsonArray
import com.appgate.brain.memory.FailedStrategies
import com.appgate.brain.model.*
import com.appgate.brain.perception.Vocabulary
import com.appgate.brain.util.Hashing

/** All literal plan data is hashed locally, never retained in rejection feedback. */
object TeacherProposalFeedback {
    fun context(page: SemanticPageState, capability: String, params: Map<String,String>): String =
        Hashing.sha256Hex(page.host + "|" + FailedStrategies.key(page,capability,emptyList(),params) + "|" +
            params.toSortedMap().entries.joinToString("|") { "${it.key.length}:${it.key}:${it.value.length}:${it.value}" },64)

    // Proposal metadata never changes an executable procedure's identity.
    @Suppress("UNUSED_PARAMETER")
    fun signature(steps: List<Step>, params: Map<String,String>, giveUp: Boolean): String {
        fun canonicalExpectation(value: Postcondition): Postcondition {
            if(value !is Postcondition.AnyOf) return value
            val children=value.alternatives.map(::canonicalExpectation).flatMap {
                if(it is Postcondition.AnyOf) it.alternatives else listOf(it)
            }.distinctBy{it.toJson().toString()}.sortedBy{it.toJson().toString()}
            return if(children.size==1)children.single() else Postcondition.AnyOf(children)
        }
        val canonical=steps.map { step ->
            val bound=BoundProgram.step(step,params)
            bound.copy(nameHint=bound.nameHint?.let(Vocabulary::normalize),
                expect=bound.expect.map(::canonicalExpectation).distinctBy{it.toJson().toString()}.sortedBy{it.toJson().toString()}).toJson()
        }
        return Hashing.sha256Hex(JsonArray(canonical).toString(),64)
    }

    fun matching(ledger: TaskLedger, page: SemanticPageState, capability: String, params: Map<String,String>): List<TeacherProposalRejection> {
        if (page.isHumanOnly) return emptyList()
        val context=context(page,capability,params)
        return ledger.proposalRejections.filter { it.context==context }.takeLast(2)
    }
}
