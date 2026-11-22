package com.appgate.brain.model

import com.appgate.brain.json.*

/** Proposal rejection is not an executed action failure or a learned capability. */
enum class ProposalRejectionReason { GIVE_UP, EMPTY, LOW_CONFIDENCE, INCOMPATIBLE, WRONG_TARGET, HELD_PROCEDURE, DUPLICATE }

data class TeacherProposalRejection(
    val context: String,
    val signature: String,
    val reason: ProposalRejectionReason,
    val steps: List<FailureStep>,
    val target: String?,
    val quotaKey: String,
    val quotaCount: Int,
    val quotaAt: Long
) {
    fun toJson() = publicJson().put("context", context).put("signature", signature).put("target", target)
        .put("quota_key", quotaKey).put("quota_count", quotaCount).put("quota_at", quotaAt)
    fun publicJson() = JsonObject().put("reason", reason.name).put("steps", JsonArray(steps.map { it.toJson() }))
    companion object {
        private val digest = Regex("[a-f0-9]{8,64}")
        fun fromJson(value: JsonObject?): TeacherProposalRejection? {
            if (value == null || value.keys.toSet() != setOf("context","signature","reason","steps","target","quota_key","quota_count","quota_at")) return null
            val context=value.optString("context");val signature=value.optString("signature");val quota=value.optString("quota_key")
            if (!digest.matches(context) || !digest.matches(signature) || !digest.matches(quota)) return null
            val reason=ProposalRejectionReason.values().firstOrNull { it.name==value.optString("reason") } ?: return null
            val target=value.optStringOrNull("target")
            if (target != null && !digest.matches(target) || target==null && value["target"] !is JsonNull) return null
            val array=value.optArray("steps") ?: return null
            if (array.size !in 0..8) return null
            val steps=array.objects().map { FailureStep.fromJson(it) ?: return null }
            if (steps.size != array.size) return null
            val count=value["quota_count"]?.asDoubleOrNull() ?: return null
            val at=value["quota_at"]?.asDoubleOrNull() ?: return null
            if (!count.isFinite() || count%1!=0.0 || count !in 1.0..64.0 || !at.isFinite() || at%1!=0.0 || at !in 0.0..9_000_000_000_000_000.0) return null
            return TeacherProposalRejection(context,signature,reason,steps,target,quota,count.toInt(),at.toLong())
        }
    }
}
