package com.appgate.brain.engine

import com.appgate.brain.model.*

/** One execution normalization for persisted programs and duplicate-proposal identity. */
object BoundProgram {
    fun step(source: Step, params: Map<String,String>): Step {
        val bound=source.copy(facetKey=source.facetKey?.let{StepGrounder.substitute(it,params)},
            arg=source.arg?.let{StepGrounder.substitute(it,params)},
            expect=source.expect.map{expectation(it,params)})
        return if(bound.kind==StepKind.TYPE && bound.role==Role.SEARCH_BOX && !bound.submit &&
            bound.expect.isEmpty() && bound.arg!=null)
            bound.copy(expect=listOf(Postcondition.ValueIs("query",bound.arg))) else bound
    }
    fun expectation(value: Postcondition, params: Map<String,String>): Postcondition = when(value) {
        is Postcondition.AnyOf -> Postcondition.AnyOf(value.alternatives.map{expectation(it,params)})
        is Postcondition.ConstraintApplied -> Postcondition.ConstraintApplied(StepGrounder.substitute(value.key,params),value.value?.let{StepGrounder.substitute(it,params)})
        is Postcondition.ValueIs -> Postcondition.ValueIs(StepGrounder.substitute(value.facetKey,params),StepGrounder.substitute(value.value,params))
        is Postcondition.UrlQueryHas -> Postcondition.UrlQueryHas(StepGrounder.substitute(value.key,params))
        is Postcondition.RoleAppeared -> value.copy(facetKey=value.facetKey?.let{StepGrounder.substitute(it,params)})
        else -> value
    }
}
