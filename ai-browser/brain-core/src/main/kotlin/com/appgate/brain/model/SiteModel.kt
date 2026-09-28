package com.appgate.brain.model

import com.appgate.brain.json.JsonArray
import com.appgate.brain.json.JsonObject

/**
 * An edge of the affordance graph: acting on (role, facetKey) from a page type led to a page
 * type, and the verifier confirmed it N times. Identity is semantic — a re-render that keeps
 * roles and collections intact changes nothing here.
 */
data class GraphEdge(
    val fromPage: PageType,
    val role: Role,
    val facetKey: String?,
    val toPage: PageType,
    val traversals: Int,
    val verified: Int,
    val lastAt: Long
) {
    val key: String get() = "${fromPage.name}|${role.name}|${facetKey ?: "-"}|${toPage.name}"
    val isTrap: Boolean get() = traversals >= 6 && verified == 0

    fun toJson(): JsonObject = JsonObject().put("from", fromPage.name).put("role", role.name).put("facet", facetKey)
        .put("to", toPage.name).put("n", traversals).put("v", verified).put("at", lastAt)

    companion object {
        fun fromJson(o: JsonObject) = GraphEdge(
            PageType.parse(o.optStringOrNull("from")), Role.parse(o.optStringOrNull("role")), o.optStringOrNull("facet"),
            PageType.parse(o.optStringOrNull("to")), o.optInt("n"), o.optInt("v"), o.optLong("at")
        )
    }
}

data class FailureRecord(
    val pageType: PageType,
    val role: Role,
    val facetKey: String?,
    val reason: String,
    val count: Int,
    val lastAt: Long
) {
    val key: String get() = "${pageType.name}|${role.name}|${facetKey ?: "-"}|$reason"
    fun toJson(): JsonObject = JsonObject().put("page", pageType.name).put("role", role.name).put("facet", facetKey)
        .put("reason", reason).put("n", count).put("at", lastAt)

    companion object {
        fun fromJson(o: JsonObject) = FailureRecord(
            PageType.parse(o.optStringOrNull("page")), Role.parse(o.optStringOrNull("role")), o.optStringOrNull("facet"),
            o.optString("reason"), o.optInt("n"), o.optLong("at")
        )
    }
}

/** Curriculum item: a goal with a completion predicate, tracked per site. */
data class CurriculumItem(
    val id: String,
    val description: String,
    var completedAt: Long = 0L,
    var attempts: Int = 0,
    var blocked: Int = 0,
    var unavailable: Int = 0
) {
    val done: Boolean get() = completedAt > 0L
    fun toJson(): JsonObject = JsonObject().put("id", id).put("desc", description).put("done", completedAt).put("attempts", attempts).put("blocked", blocked).put("unavailable", unavailable)
    companion object {
        fun fromJson(o: JsonObject) = CurriculumItem(o.optString("id"), o.optString("desc"), o.optLong("done"), o.optInt("attempts"), o.optInt("blocked"), o.optInt("unavailable"))
    }
}

/**
 * Semantic site model — everything the brain knows about one host that is *not* tied to a
 * DOM: page types seen, the affordance graph, facet vocabulary, endpoint effect classes,
 * bindings, failures, curriculum progress. Contains no page content and no user data.
 */
class SiteModel(val host: String) {
    var siteVersion: String = ""
    var versionChangedAt: Long = 0L
    var lastSeenAt: Long = 0L
    var lastConsolidatedAt: Long = 0L
    var challengesToday: Int = 0
    var challengeDay: Long = 0L
    var lessonOrdinal: Int = 0
    var learningBlockedUntil: Long = 0L
    var learningNeedsHuman: Boolean = false
    var lastLearningStatus: String = ""
    var searchUrlTemplate: String? = null
    var startUrl: String? = null
    var loginUrl: String? = null
    val pageTypesSeen: MutableMap<PageType, Int> = linkedMapOf()
    val edges: MutableMap<String, GraphEdge> = linkedMapOf()
    val facetVocabulary: MutableMap<String, String> = linkedMapOf()   // site label (lowercase) -> canonical key
    val endpointEffects: MutableMap<String, EffectClass> = linkedMapOf() // "POST /api/messages" -> COMMIT_EXTERNAL
    val bindings: MutableMap<String, Binding> = linkedMapOf()
    val failures: MutableMap<String, FailureRecord> = linkedMapOf()
    val curriculum: MutableList<CurriculumItem> = mutableListOf()
    val quirks: MutableSet<String> = linkedSetOf()
    var verifiedActions: Int = 0
    var failedActions: Int = 0
    var brierSum: Double = 0.0
    var brierCount: Int = 0

    val brier: Double get() = if (brierCount == 0) 0.0 else brierSum / brierCount
    val hasVerifiedBindings: Boolean get() = bindings.values.any { it.stats.successes > 0 && !it.shadowed }

    fun binding(pageType: PageType, role: Role, facetKey: String?): Binding? =
        bindings[Binding.key(pageType, role, facetKey)]?.takeIf { !it.shadowed }

    fun recordPage(pageType: PageType, now: Long) {
        pageTypesSeen[pageType] = (pageTypesSeen[pageType] ?: 0) + 1
        lastSeenAt = now
    }

    fun recordEdge(from: PageType, role: Role, facetKey: String?, to: PageType, verified: Boolean, now: Long) {
        val probe = GraphEdge(from, role, facetKey, to, 0, 0, now)
        val existing = edges[probe.key]
        edges[probe.key] = GraphEdge(from, role, facetKey, to,
            (existing?.traversals ?: 0) + 1, (existing?.verified ?: 0) + (if (verified) 1 else 0), now)
    }

    fun recordFailure(pageType: PageType, role: Role, facetKey: String?, reason: String, now: Long) {
        val probe = FailureRecord(pageType, role, facetKey, reason, 0, now)
        val existing = failures[probe.key]
        failures[probe.key] = probe.copy(count = (existing?.count ?: 0) + 1)
    }

    fun failureCount(pageType: PageType, role: Role, facetKey: String?): Int =
        failures.values.filter { it.pageType == pageType && it.role == role && it.facetKey == facetKey }.sumOf { it.count }

    fun recordChallenge(now: Long) {
        val day = now / 86_400_000L
        if (day != challengeDay) { challengeDay = day; challengesToday = 0 }
        challengesToday++
    }

    fun recordCalibration(predictedP: Double, success: Boolean) {
        val outcome = if (success) 1.0 else 0.0
        brierSum += (predictedP - outcome) * (predictedP - outcome)
        brierCount++
        if (brierCount > 500) { brierSum *= 0.5; brierCount = 250 }
    }

    fun toJson(): JsonObject = JsonObject()
        .put("host", host).put("version", siteVersion).put("version_at", versionChangedAt).put("seen", lastSeenAt)
        .put("consolidated", lastConsolidatedAt).put("challenges", challengesToday).put("challenge_day", challengeDay)
        .put("lesson_ordinal", lessonOrdinal).put("learning_blocked_until", learningBlockedUntil)
        .put("learning_needs_human", learningNeedsHuman).put("learning_status", lastLearningStatus)
        .put("search_url", searchUrlTemplate).put("start_url", startUrl).put("login_url", loginUrl)
        .put("pages", JsonObject().also { j -> pageTypesSeen.forEach { (k, v) -> j.put(k.name, v) } })
        .put("edges", JsonArray(edges.values.map { it.toJson() }))
        .put("facets", JsonObject().also { j -> facetVocabulary.forEach { (k, v) -> j.put(k, v) } })
        .put("endpoints", JsonObject().also { j -> endpointEffects.forEach { (k, v) -> j.put(k, v.name) } })
        .put("bindings", JsonArray(bindings.values.map { it.toJson() }))
        .put("failures", JsonArray(failures.values.map { it.toJson() }))
        .put("curriculum", JsonArray(curriculum.map { it.toJson() }))
        .putStrings("quirks", quirks)
        .put("verified", verifiedActions).put("failed", failedActions)
        .put("brier_sum", brierSum).put("brier_n", brierCount)

    companion object {
        fun fromJson(o: JsonObject): SiteModel {
            val m = SiteModel(o.optString("host"))
            m.siteVersion = o.optString("version")
            m.versionChangedAt = o.optLong("version_at")
            m.lastSeenAt = o.optLong("seen")
            m.lastConsolidatedAt = o.optLong("consolidated")
            m.challengesToday = o.optInt("challenges")
            m.challengeDay = o.optLong("challenge_day")
            m.lessonOrdinal = o.optInt("lesson_ordinal")
            m.learningBlockedUntil = o.optLong("learning_blocked_until")
            m.learningNeedsHuman = o.optBoolean("learning_needs_human")
            m.lastLearningStatus = o.optString("learning_status")
            m.searchUrlTemplate = o.optStringOrNull("search_url")
            m.startUrl = o.optStringOrNull("start_url")
            m.loginUrl = o.optStringOrNull("login_url")
            o.optObject("pages")?.entries()?.forEach { (k, v) -> m.pageTypesSeen[PageType.parse(k)] = v.asDoubleOrNull()?.toInt() ?: 0 }
            o.optArray("edges")?.objects()?.forEach { e -> GraphEdge.fromJson(e).let { m.edges[it.key] = it } }
            o.optObject("facets")?.entries()?.forEach { (k, v) -> v.asStringOrNull()?.let { m.facetVocabulary[k] = it } }
            o.optObject("endpoints")?.entries()?.forEach { (k, v) -> m.endpointEffects[k] = EffectClass.parse(v.asStringOrNull()) }
            o.optArray("bindings")?.objects()?.forEach { b -> Binding.fromJson(b).let { m.bindings[it.key] = it } }
            o.optArray("failures")?.objects()?.forEach { f -> FailureRecord.fromJson(f).let { m.failures[it.key] = it } }
            o.optArray("curriculum")?.objects()?.forEach { c -> m.curriculum += CurriculumItem.fromJson(c) }
            m.quirks += o.optStrings("quirks")
            m.verifiedActions = o.optInt("verified")
            m.failedActions = o.optInt("failed")
            m.brierSum = o.optDouble("brier_sum")
            m.brierCount = o.optInt("brier_n")
            return m
        }
    }
}
