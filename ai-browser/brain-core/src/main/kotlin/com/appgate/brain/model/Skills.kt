package com.appgate.brain.model

import com.appgate.brain.json.JsonArray
import com.appgate.brain.json.JsonObject
import kotlin.math.pow

/**
 * Beta(alpha, beta) success statistics with exponential time decay.
 *
 *   alpha_t = 1 + sum_i s_i * lambda^(days_i) * nu_i
 *   beta_t  = 1 + sum_i (1 - s_i) * lambda^(days_i) * nu_i
 *
 * We store the running sums and the timestamp of the last fold so decay is applied lazily.
 */
data class BetaStat(
    val successes: Double = 0.0,
    val failures: Double = 0.0,
    val lastUpdatedAt: Long = 0L
) {
    fun decayed(now: Long, dailyLambda: Double = DEFAULT_LAMBDA): BetaStat {
        if (lastUpdatedAt <= 0L || now <= lastUpdatedAt) return this
        val days = (now - lastUpdatedAt) / 86_400_000.0
        val f = dailyLambda.pow(days)
        return copy(successes = successes * f, failures = failures * f, lastUpdatedAt = now)
    }

    fun record(success: Boolean, now: Long, weight: Double = 1.0): BetaStat {
        val d = decayed(now)
        return if (success) d.copy(successes = d.successes + weight, lastUpdatedAt = now)
        else d.copy(failures = d.failures + weight, lastUpdatedAt = now)
    }

    val alpha: Double get() = 1.0 + successes
    val beta: Double get() = 1.0 + failures
    /** Posterior mean P(success). */
    val p: Double get() = alpha / (alpha + beta)
    /** Effective number of observations. */
    val n: Double get() = successes + failures
    /** Posterior variance; small when we have seen many trials. */
    val variance: Double get() = (alpha * beta) / ((alpha + beta).pow(2) * (alpha + beta + 1))

    fun toJson(): JsonObject = JsonObject().put("s", successes).put("f", failures).put("t", lastUpdatedAt)

    companion object {
        const val DEFAULT_LAMBDA = 0.98
        fun fromJson(o: JsonObject?): BetaStat = if (o == null) BetaStat() else BetaStat(o.optDouble("s"), o.optDouble("f"), o.optLong("t"))
    }
}

enum class StepKind { CLICK, TYPE, SELECT, SCROLL, BACK, WAIT, NAVIGATE, DISMISS, SET_RANGE }

/**
 * One step of a skill program. Steps reference roles and canonical facet keys only; the
 * value comes from a named parameter (`$max`) or a literal.
 */
data class Step(
    val kind: StepKind,
    val role: Role? = null,
    val facetKey: String? = null,        // may be a parameter reference like "$key"
    val arg: String? = null,             // "$query", "$max" or a literal
    val optional: Boolean = false,       // Opt(step): skipped if its expect already holds or target is absent
    val submit: Boolean = false,
    val expect: List<Postcondition> = emptyList(),
    val nameHint: String? = null
) {
    fun toJson(): JsonObject = JsonObject().put("kind", kind.name).put("role", role?.name).put("facet", facetKey).put("arg", arg)
        .put("opt", optional).put("submit", submit).put("name", nameHint).put("expect", JsonArray(expect.map { it.toJson() }))

    fun describe(): String = "${if (optional) "opt " else ""}${kind.name.lowercase()} ${role?.name ?: ""}${facetKey?.let { "[$it]" } ?: ""}${arg?.let { " $it" } ?: ""}"

    companion object {
        fun fromJson(o: JsonObject): Step = Step(
            kind = StepKind.values().firstOrNull { it.name == o.optString("kind") } ?: StepKind.WAIT,
            role = o.optStringOrNull("role")?.let { Role.parse(it) },
            facetKey = o.optStringOrNull("facet"),
            arg = o.optStringOrNull("arg"),
            optional = o.optBoolean("opt"),
            submit = o.optBoolean("submit"),
            expect = o.optArray("expect")?.objects()?.mapNotNull { Postcondition.fromJson(it) } ?: emptyList(),
            nameHint = o.optStringOrNull("name")
        )
    }
}

sealed class Precondition {
    abstract fun toJson(): JsonObject
    data class PageTypeIn(val types: Set<PageType>) : Precondition() {
        override fun toJson() = JsonObject().put("kind", "PAGE_TYPE_IN").putStrings("types", types.map { it.name })
    }
    data class HasRole(val role: Role, val facetKey: String? = null) : Precondition() {
        override fun toJson() = JsonObject().put("kind", "HAS_ROLE").put("role", role.name).put("facet", facetKey)
    }
    companion object {
        fun fromJson(o: JsonObject): Precondition? = when (o.optString("kind")) {
            "PAGE_TYPE_IN" -> PageTypeIn(o.optStrings("types").map { PageType.parse(it) }.toSet())
            "HAS_ROLE" -> HasRole(Role.parse(o.optStringOrNull("role")), o.optStringOrNull("facet"))
            else -> null
        }
    }
}

enum class SkillOrigin { BUILTIN, COMPILED, DEMONSTRATED, AUTHORED }

/**
 * A skill is an abstract program over affordance roles. No selector ever appears here; the
 * per-site answer to "which element plays that role" lives in a [Binding].
 */
data class Skill(
    val id: String,
    val version: Int,
    val intent: String,
    val params: List<String>,
    val pre: List<Precondition>,
    val body: List<Step>,
    val post: List<Postcondition>,
    val origin: SkillOrigin,
    val statsByHost: Map<String, BetaStat> = emptyMap(),
    val provenance: List<String> = emptyList(),
    val tags: Set<String> = emptySet()
) {
    fun stat(host: String): BetaStat = statsByHost[host] ?: BetaStat()

    fun withOutcome(host: String, success: Boolean, now: Long): Skill =
        copy(statsByHost = statsByHost + (host to stat(host).record(success, now)))

    fun toJson(): JsonObject = JsonObject().put("id", id).put("version", version).put("intent", intent)
        .putStrings("params", params)
        .put("pre", JsonArray(pre.map { it.toJson() }))
        .put("body", JsonArray(body.map { it.toJson() }))
        .put("post", JsonArray(post.map { it.toJson() }))
        .put("origin", origin.name)
        .put("stats", JsonObject().also { j -> statsByHost.forEach { (h, s) -> j.put(h, s.toJson()) } })
        .putStrings("provenance", provenance.take(20))
        .putStrings("tags", tags)

    companion object {
        fun fromJson(o: JsonObject): Skill = Skill(
            id = o.optString("id"),
            version = o.optInt("version", 1),
            intent = o.optString("intent"),
            params = o.optStrings("params"),
            pre = o.optArray("pre")?.objects()?.mapNotNull { Precondition.fromJson(it) } ?: emptyList(),
            body = o.optArray("body")?.objects()?.map { Step.fromJson(it) } ?: emptyList(),
            post = o.optArray("post")?.objects()?.mapNotNull { Postcondition.fromJson(it) } ?: emptyList(),
            origin = SkillOrigin.values().firstOrNull { it.name == o.optString("origin") } ?: SkillOrigin.COMPILED,
            statsByHost = o.optObject("stats")?.entries()?.mapNotNull { (h, v) -> v.asObjectOrNull()?.let { h to BetaStat.fromJson(it) } }?.toMap() ?: emptyMap(),
            provenance = o.optStrings("provenance"),
            tags = o.optStrings("tags").toSet()
        )
    }
}

/**
 * Per-site grounding cache: which element played a role on a page type, described by
 * features and a small set of hints. Hints are bounded bonuses re-verified by features, never
 * a short-circuit — that is the rule that keeps bindings from decaying into selector scripts.
 */
data class Binding(
    val host: String,
    val pageType: PageType,
    val role: Role,
    val facetKey: String?,
    val siteVersion: String,
    val features: FeatureVec,
    val nameHints: List<String>,
    val stats: BetaStat,
    val lastVerifiedAt: Long,
    val shadowed: Boolean = false
) {
    val key: String get() = key(pageType, role, facetKey)

    fun toJson(): JsonObject = JsonObject().put("host", host).put("page", pageType.name).put("role", role.name).put("facet", facetKey)
        .put("ver", siteVersion).put("features", features.toJson()).putStrings("names", nameHints).put("stats", stats.toJson())
        .put("verified", lastVerifiedAt).put("shadowed", shadowed)

    companion object {
        fun key(pageType: PageType, role: Role, facetKey: String?): String = "${pageType.name}|${role.name}|${facetKey ?: "-"}"
        fun fromJson(o: JsonObject): Binding = Binding(
            host = o.optString("host"),
            pageType = PageType.parse(o.optStringOrNull("page")),
            role = Role.parse(o.optStringOrNull("role")),
            facetKey = o.optStringOrNull("facet"),
            siteVersion = o.optString("ver"),
            features = FeatureVec.fromJson(o.optObject("features")),
            nameHints = o.optStrings("names"),
            stats = BetaStat.fromJson(o.optObject("stats")),
            lastVerifiedAt = o.optLong("verified"),
            shadowed = o.optBoolean("shadowed")
        )
    }
}
