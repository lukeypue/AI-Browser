package com.appgate.brain.model

import com.appgate.brain.json.JsonArray
import com.appgate.brain.json.JsonObject

/**
 * How a step names the thing it wants to act on. A target is abstract (role + facet key +
 * optional disambiguator); the grounder resolves it to a live affordance id at execution
 * time. [affordanceId] is a hint valid only for the current snapshot.
 */
data class AffordanceRef(
    val role: Role,
    val facetKey: String? = null,
    val nameHint: String? = null,
    val index: Int? = null,
    val affordanceId: String? = null,
    val itemKey: String? = null
) {
    fun toJson(): JsonObject = JsonObject()
        .put("role", role.name)
        .put("facet", facetKey)
        .put("name", nameHint)
        .put("index", index)
        .put("id", affordanceId)
        .put("item", itemKey)

    override fun toString(): String = buildString {
        append(role.name)
        facetKey?.let { append("[").append(it).append("]") }
        nameHint?.let { append("\"").append(it).append("\"") }
        index?.let { append("#").append(it) }
    }

    companion object {
        fun fromJson(o: JsonObject?): AffordanceRef? {
            if (o == null) return null
            return AffordanceRef(
                role = Role.parse(o.optStringOrNull("role")),
                facetKey = o.optStringOrNull("facet"),
                nameHint = o.optStringOrNull("name"),
                index = if (o.has("index")) o.optInt("index") else null,
                affordanceId = o.optStringOrNull("id"),
                itemKey = o.optStringOrNull("item")
            )
        }
    }
}

enum class ActionKind { CLICK, TYPE, SELECT, SCROLL, NAVIGATE, BACK, WAIT, DISMISS, SET_RANGE }

/** Typed action with pre-computed effect class and the postconditions its author expects. */
data class Action(
    val kind: ActionKind,
    val target: AffordanceRef? = null,
    val text: String? = null,
    val url: String? = null,
    val amount: Int? = null,             // scroll delta or wait ms
    val submit: Boolean = false,         // for TYPE: press Enter / submit after typing
    val expect: List<Postcondition> = emptyList(),
    val effect: EffectClass = EffectClass.MUTATE_LOCAL,
    val grantId: String? = null,
    val rationale: String = ""
) {
    fun describe(): String = when (kind) {
        ActionKind.CLICK -> "click ${target}"
        ActionKind.TYPE -> "type into ${target}${if (submit) " + submit" else ""}"
        ActionKind.SELECT -> "select a value in ${target}"
        ActionKind.SCROLL -> "scroll ${amount ?: 0}"
        ActionKind.NAVIGATE -> "navigate ${url?.take(80)}"
        ActionKind.BACK -> "back"
        ActionKind.WAIT -> "wait ${amount ?: 0}ms"
        ActionKind.DISMISS -> "dismiss dialog"
        ActionKind.SET_RANGE -> "set range ${target}"
    }

    fun toJson(): JsonObject = JsonObject()
        .put("kind", kind.name)
        .put("target", target?.toJson())
        .put("text", text)
        .put("url", url)
        .put("amount", amount)
        .put("submit", submit)
        .put("effect", effect.name)
        .put("grant", grantId)
        .put("expect", JsonArray(expect.map { it.toJson() }))
        .put("rationale", rationale.take(200))

    companion object {
        fun fromJson(o: JsonObject): Action = Action(
            kind = ActionKind.values().firstOrNull { it.name == o.optString("kind") } ?: ActionKind.WAIT,
            target = AffordanceRef.fromJson(o.optObject("target")),
            text = o.optStringOrNull("text"),
            url = o.optStringOrNull("url"),
            amount = if (o.has("amount")) o.optInt("amount") else null,
            submit = o.optBoolean("submit"),
            expect = o.optArray("expect")?.objects()?.mapNotNull { Postcondition.fromJson(it) } ?: emptyList(),
            effect = EffectClass.parse(o.optStringOrNull("effect")),
            grantId = o.optStringOrNull("grant"),
            rationale = o.optString("rationale")
        )
    }
}

/**
 * Postconditions are the only source of reward. "Clicked" is never an outcome; a verified
 * semantic delta is.
 */
sealed class Postcondition {
    abstract val name: String
    open fun toJson(): JsonObject = JsonObject().put("kind", name)

    object ResultsChanged : Postcondition() { override val name = "RESULTS_CHANGED" }
    object NewResults : Postcondition() { override val name = "NEW_RESULTS" }
    object EndOfResults : Postcondition() { override val name = "END_OF_RESULTS" }
    object TextExpanded : Postcondition() { override val name = "TEXT_EXPANDED" }
    object DialogClosed : Postcondition() { override val name = "DIALOG_CLOSED" }
    object DialogOpened : Postcondition() { override val name = "DIALOG_OPENED" }
    object UrlChanged : Postcondition() { override val name = "URL_CHANGED" }
    object ComposerReady : Postcondition() { override val name = "COMPOSER_READY" }
    object ScrolledDown : Postcondition() { override val name = "SCROLLED_DOWN" }

    data class PageTypeIs(val pageType: PageType) : Postcondition() {
        override val name = "PAGE_TYPE_IS"
        override fun toJson() = super.toJson().put("page_type", pageType.name)
    }

    data class ConstraintApplied(val key: String, val value: String?) : Postcondition() {
        override val name = "CONSTRAINT_APPLIED"
        override fun toJson() = super.toJson().put("key", key).put("value", value)
    }

    data class DetailMatches(val itemKey: String?) : Postcondition() {
        override val name = "DETAIL_MATCHES"
        override fun toJson() = super.toJson().put("item", itemKey)
    }

    data class UrlQueryHas(val key: String) : Postcondition() {
        override val name = "URL_QUERY_HAS"
        override fun toJson() = super.toJson().put("key", key)
    }

    data class RoleAppeared(val role: Role, val facetKey: String? = null) : Postcondition() {
        override val name = "ROLE_APPEARED"
        override fun toJson() = super.toJson().put("role", role.name).put("key", facetKey)
        fun matchesFacet(key: String?): Boolean = facetKey == null || key == facetKey ||
            (facetKey in setOf("price", "mileage", "year", "distance", "bedrooms", "bathrooms") &&
                (key == "${facetKey}_min" || key == "${facetKey}_max"))
    }

    data class ValueIs(val facetKey: String, val value: String) : Postcondition() {
        override val name = "VALUE_IS"
        override fun toJson() = super.toJson().put("key", facetKey).put("value", value)
    }

    /** Satisfied when at least one alternative holds. */
    data class AnyOf(val alternatives: List<Postcondition>) : Postcondition() {
        override val name = "ANY_OF"
        override fun toJson() = super.toJson().put("of", JsonArray(alternatives.map { it.toJson() }))
    }

    companion object {
        fun anyOf(vararg alternatives: Postcondition): Postcondition = AnyOf(alternatives.toList())

        fun fromJson(o: JsonObject?): Postcondition? {
            if (o == null) return null
            return when (o.optString("kind")) {
                "ANY_OF" -> AnyOf(o.optArray("of")?.objects()?.mapNotNull { fromJson(it) } ?: emptyList())
                "RESULTS_CHANGED" -> ResultsChanged
                "NEW_RESULTS" -> NewResults
                "END_OF_RESULTS" -> EndOfResults
                "TEXT_EXPANDED" -> TextExpanded
                "DIALOG_CLOSED" -> DialogClosed
                "DIALOG_OPENED" -> DialogOpened
                "URL_CHANGED" -> UrlChanged
                "COMPOSER_READY" -> ComposerReady
                "SCROLLED_DOWN" -> ScrolledDown
                "PAGE_TYPE_IS" -> PageTypeIs(PageType.parse(o.optStringOrNull("page_type")))
                "CONSTRAINT_APPLIED" -> ConstraintApplied(o.optString("key"), o.optStringOrNull("value"))
                "DETAIL_MATCHES" -> DetailMatches(o.optStringOrNull("item"))
                "URL_QUERY_HAS" -> UrlQueryHas(o.optString("key"))
                "ROLE_APPEARED" -> RoleAppeared(Role.parse(o.optStringOrNull("role")), o.optStringOrNull("key"))
                "VALUE_IS" -> ValueIs(o.optString("key"), o.optString("value"))
                else -> null
            }
        }
    }
}

enum class VerifyStatus { VERIFIED, FAILED, AMBIGUOUS, HUMAN_NEEDED }

data class VerifierResult(
    val status: VerifyStatus,
    val evidence: List<String>,
    val satisfied: List<Postcondition> = emptyList(),
    val unsatisfied: List<Postcondition> = emptyList()
) {
    val verified: Boolean get() = status == VerifyStatus.VERIFIED
}

/** A single-use, time-limited, user-confirmed permission to perform one COMMIT_EXTERNAL action. */
data class Grant(
    val id: String,
    val taskId: String,
    val host: String,
    val role: Role,
    val previewHash: String,
    val issuedAt: Long,
    val ttlMs: Long = 120_000L,
    var used: Boolean = false
) {
    fun covers(action: Action, host: String, previewHash: String?, now: Long): Boolean {
        if (used) return false
        if (now - issuedAt > ttlMs) return false
        if (!hostsMatch(this.host, host)) return false
        val role = action.target?.role ?: return false
        if (role != this.role) return false
        return previewHash != null && previewHash == this.previewHash
    }

    fun toJson(): JsonObject = JsonObject().put("id", id).put("task", taskId).put("host", host)
        .put("role", role.name).put("preview", previewHash).put("issued", issuedAt).put("ttl", ttlMs).put("used", used)

    companion object {
        /** "www.facebook.com" and "facebook.com" are the same site for a grant. */
        fun hostsMatch(a: String, b: String): Boolean {
            val x = a.lowercase().removePrefix("www.").removeSuffix(".")
            val y = b.lowercase().removePrefix("www.").removeSuffix(".")
            return x == y || x.endsWith(".$y") || y.endsWith(".$x")
        }
    }
}
