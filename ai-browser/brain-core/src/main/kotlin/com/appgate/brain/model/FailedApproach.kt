package com.appgate.brain.model

import com.appgate.brain.json.*
import com.appgate.brain.perception.Vocabulary

/** Closed, content-free description. Context stays local; it is never provider input. */
data class FailedApproach(val context: String, val steps: List<FailureStep>) {
 fun toJson()=JsonObject().put("context",context).put("steps",JsonArray(steps.map{it.toJson()}))
 fun publicJson()=JsonObject().put("steps",JsonArray(steps.map{it.toJson()}))
 companion object {
  fun fromJson(o:JsonObject?):FailedApproach? {
   if(o==null || o.keys.toSet()!=setOf("context","steps"))return null
   val context=o.optString("context")
   if(!context.matches(Regex("[a-f0-9]{8,64}")))return null
   val rows=o.optArray("steps")?:return null
   if(rows.size !in 1..8)return null
   val steps=rows.objects().map{FailureStep.fromJson(it)?:return null}
   if(steps.size!=rows.size)return null
   return FailedApproach(context,steps)
  }
 }
}

data class FailureStep(val kind:StepKind,val role:Role?,val facet:String?,val submit:Boolean,val optional:Boolean,
 val motion:Int?=null,val orderContract:Boolean=false) {
 fun toJson()=JsonObject().put("kind",kind.name).put("role",role?.name).put("facet",facet)
  .put("submit",submit).put("optional",optional).put("motion",motion).put("order_contract",orderContract)
 companion object {
  fun canonicalFacet(value:String?)=value==null || value.removeSuffix("_min").removeSuffix("_max") in Vocabulary.facetLexicon
  fun fromJson(o:JsonObject):FailureStep? {
   if(o.keys.toSet()!=setOf("kind","role","facet","submit","optional","motion","order_contract"))return null
   val kind=StepKind.values().firstOrNull{it.name==o.optString("kind")}?:return null
   val role=o.optStringOrNull("role")?.let{v->Role.values().firstOrNull{it.name==v}?:return null}
   if(role!=null && (role.isCommit || role in setOf(Role.UNKNOWN,Role.LOGIN,Role.ACCOUNT,Role.COMPOSER_INPUT,Role.MESSAGE_SELLER)))return null
   val facet=o.optStringOrNull("facet");if(!canonicalFacet(facet))return null
   val submit=o["submit"]?.asBooleanOrNull()?:return null
   val optional=o["optional"]?.asBooleanOrNull()?:return null
   val order=o["order_contract"]?.asBooleanOrNull()?:return null
   val raw=o["motion"]?.asDoubleOrNull()
   if(raw!=null && (!raw.isFinite() || raw%1!=0.0 || raw !in -10000.0..10000.0 || kind !in setOf(StepKind.SCROLL,StepKind.WAIT)))return null
   if(o["motion"] !is JsonNull && raw==null)return null
   if(o["role"] !is JsonNull && role==null || o["facet"] !is JsonNull && facet==null)return null
   return FailureStep(kind,role,facet,submit,optional,raw?.toInt(),order)
  }
 }
}
