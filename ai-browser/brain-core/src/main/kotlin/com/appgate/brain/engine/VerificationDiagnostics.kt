package com.appgate.brain.engine
import com.appgate.brain.json.JsonObject
import com.appgate.brain.model.*

/** Closed observations of the existing verifier result; never content, identities or a second verdict. */
object VerificationDiagnostics {
 fun targetClass(role:Role?):String=when(role){
  null->"NONE";Role.RESULT_ITEM->"ITEM";Role.EXPAND_TEXT->"TEXT_EXPANDER";Role.SEARCH_BOX->"SEARCH"
  Role.FACET,Role.FACET_OPEN,Role.FACET_APPLY->"FILTER";Role.PAGE_NEXT,Role.LOAD_MORE->"PAGINATION";Role.CLOSE->"DIALOG";else->"OTHER"
 }
 fun observed(action:Action,before:SemanticPageState,after:SemanticPageState,result:VerifierResult,
              rechecks:Int,readFailed:Boolean,identityUnconfirmed:Boolean):JsonObject {
  val reason=when {
   result.status==VerifyStatus.HUMAN_NEEDED->"HUMAN_REQUIRED"
   readFailed->"RECHECK_UNREADABLE"
   identityUnconfirmed->"ITEM_IDENTITY_UNCONFIRMED"
   result.status==VerifyStatus.VERIFIED->"VERIFIED"
   action.expect.isEmpty()->"NO_EXPECTATIONS"
   after.settle!=Settle.IDLE->"PAGE_UNSETTLED"
   before.hash==after.hash->"NO_SEMANTIC_CHANGE"
   else->"EXPECTATION_UNMET"
  }
  fun bit(value:Boolean)=if(value)1 else 0
  return JsonObject().put("action_kind",action.kind.name).put("target_class",targetClass(action.target?.role))
   .put("primary_expectation",action.expect.firstOrNull()?.name?:"NONE").put("verification_reason",reason)
   .put("expected_checks",action.expect.size.coerceIn(0,1_000_000))
   .put("satisfied_checks",result.satisfied.size.coerceIn(0,1_000_000)).put("unmet_checks",result.unsatisfied.size.coerceIn(0,1_000_000))
   .put("observation_rechecks",rechecks.coerceIn(0,2)).put("observation_read_failed",bit(readFailed))
   .put("text_expansion_verified",bit(result.status==VerifyStatus.VERIFIED && Postcondition.TextExpanded in result.satisfied))
   .put("semantic_changed",bit(before.hash!=after.hash)).put("route_changed",bit(before.url!=after.url))
   .put("target_controls_before",action.target?.role?.let{before.byRole(it).size}?:0)
   .put("target_controls_after",action.target?.role?.let{after.byRole(it).size}?:0)
 }
}
