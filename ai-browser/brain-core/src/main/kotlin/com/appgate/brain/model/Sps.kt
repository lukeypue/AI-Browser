package com.appgate.brain.model

import com.appgate.brain.json.JsonArray
import com.appgate.brain.json.JsonObject

/**
 * Semantic Page State (SPS) — the only thing the brain reasons over.
 *
 * An SPS is an identity for a *situation*, not for a DOM: page type, the affordances a
 * user could act on (typed by role), the collections on the page keyed by stable item keys,
 * and the constraints currently applied. Two React re-renders of the same results page
 * produce the same SPS hash; two different filter states do not.
 *
 * Privacy contract (enforced by the extractor and re-checked by [com.appgate.brain.perception.Redactor]):
 *  - no input values on AuthWall/Challenge pages, no password values anywhere
 *  - accessible names are truncated and stripped of emails / phone numbers
 *  - item titles exist only for the lifetime of a task and never enter long-term memory
 */
enum class PageType {
    HOME, SEARCH, RESULTS, FACET_PANEL, DETAIL, PROFILE, MESSAGES, DIALOG, AUTH_WALL, CHALLENGE, ERROR, UNKNOWN;

    val isHumanOnly: Boolean get() = this == AUTH_WALL || this == CHALLENGE

    companion object {
        fun parse(raw: String?): PageType = values().firstOrNull { it.name.equals(raw, true) } ?: UNKNOWN
    }
}

/** What acting on an affordance does to the world. Assigned by perception, never by a planner. */
enum class EffectClass {
    READ, NAVIGATE, MUTATE_LOCAL, COMMIT_EXTERNAL;

    companion object {
        fun parse(raw: String?): EffectClass = values().firstOrNull { it.name.equals(raw, true) } ?: READ
    }
}

/** Abstract affordance roles. Skills are written against these, never against selectors. */
enum class Role(val effect: EffectClass) {
    SEARCH_BOX(EffectClass.MUTATE_LOCAL),
    SUBMIT(EffectClass.NAVIGATE),
    FACET(EffectClass.MUTATE_LOCAL),
    FACET_OPEN(EffectClass.MUTATE_LOCAL),
    FACET_APPLY(EffectClass.NAVIGATE),
    FACET_CLEAR(EffectClass.MUTATE_LOCAL),
    SORT(EffectClass.NAVIGATE),
    PAGE_NEXT(EffectClass.NAVIGATE),
    PAGE_PREV(EffectClass.NAVIGATE),
    LOAD_MORE(EffectClass.READ),
    RESULT_ITEM(EffectClass.NAVIGATE),
    DETAIL_TITLE(EffectClass.READ),
    EXPAND_TEXT(EffectClass.READ),
    CATEGORY_LINK(EffectClass.NAVIGATE),
    NAV_LINK(EffectClass.NAVIGATE),
    TAB(EffectClass.MUTATE_LOCAL),
    CLOSE(EffectClass.MUTATE_LOCAL),
    BACK(EffectClass.NAVIGATE),
    LOGIN(EffectClass.NAVIGATE),
    MESSAGE_SELLER(EffectClass.MUTATE_LOCAL),
    COMPOSER_INPUT(EffectClass.MUTATE_LOCAL),
    ATTACH(EffectClass.MUTATE_LOCAL),
    SEND(EffectClass.COMMIT_EXTERNAL),
    BUY(EffectClass.COMMIT_EXTERNAL),
    BID(EffectClass.COMMIT_EXTERNAL),
    POST(EffectClass.COMMIT_EXTERNAL),
    DELETE(EffectClass.COMMIT_EXTERNAL),
    FOLLOW(EffectClass.COMMIT_EXTERNAL),
    SAVE(EffectClass.COMMIT_EXTERNAL),
    REPORT(EffectClass.COMMIT_EXTERNAL),
    SHARE(EffectClass.MUTATE_LOCAL),
    ACCOUNT(EffectClass.NAVIGATE),
    GENERIC_BUTTON(EffectClass.MUTATE_LOCAL),
    GENERIC_LINK(EffectClass.NAVIGATE),
    UNKNOWN(EffectClass.MUTATE_LOCAL);

    val isCommit: Boolean get() = effect == EffectClass.COMMIT_EXTERNAL

    companion object {
        fun parse(raw: String?): Role = values().firstOrNull { it.name.equals(raw, true) } ?: UNKNOWN
    }
}

enum class RegionRole { HEADER, NAV, FILTERS, RESULT_LIST, MAIN, COMPOSER, DIALOG, FOOTER, UNKNOWN }

enum class Settle { IDLE, BUSY, UNKNOWN }

data class BBox(val x: Int, val y: Int, val w: Int, val h: Int) {
    val area: Int get() = w * h
    fun toJson(): JsonArray = JsonArray().add(x).add(y).add(w).add(h)

    companion object {
        fun fromJson(a: JsonArray?): BBox? {
            if (a == null || a.size < 4) return null
            return BBox(a[0].asDoubleOrNull()?.toInt() ?: 0, a[1].asDoubleOrNull()?.toInt() ?: 0,
                a[2].asDoubleOrNull()?.toInt() ?: 0, a[3].asDoubleOrNull()?.toInt() ?: 0)
        }
    }
}

/**
 * A feature vector describing an element independently of its DOM position, used for
 * grounding (matching an abstract role to a live element). Values are in [0,1].
 */
data class FeatureVec(val values: Map<String, Double>) {
    operator fun get(key: String): Double = values[key] ?: 0.0

    fun similarity(other: FeatureVec): Double {
        val keys = values.keys + other.values.keys
        if (keys.isEmpty()) return 0.0
        var dot = 0.0; var a = 0.0; var b = 0.0
        for (k in keys) {
            val x = this[k]; val y = other[k]
            dot += x * y; a += x * x; b += y * y
        }
        if (a == 0.0 || b == 0.0) return 0.0
        return dot / (Math.sqrt(a) * Math.sqrt(b))
    }

    fun toJson(): JsonObject = JsonObject().also { o -> values.forEach { (k, v) -> o.put(k, v) } }

    companion object {
        val EMPTY = FeatureVec(emptyMap())
        fun fromJson(o: JsonObject?): FeatureVec {
            if (o == null) return EMPTY
            return FeatureVec(o.entries().mapNotNull { (k, v) -> v.asDoubleOrNull()?.let { k to it } }.toMap())
        }
    }
}

data class Region(val id: String, val role: RegionRole, val bbox: BBox?)

/**
 * One actionable thing on the page. [id] is only valid for the snapshot it came from —
 * long-term memory refers to affordances by role + facet key + features, never by id.
 */
data class Affordance(
    val id: String,
    val role: Role,
    val facetKey: String? = null,        // canonical key when role == FACET / FACET_OPEN (price_max, make, ...)
    val facetKind: String? = null,       // numeric_min | numeric_max | choice | toggle | text | range
    val name: String = "",               // redacted accessible name, <= 40 chars
    val tag: String = "",
    val inputType: String? = null,
    val regionId: String? = null,
    val regionRole: RegionRole = RegionRole.UNKNOWN,
    val bbox: BBox? = null,
    val visible: Boolean = true,
    val enabled: Boolean = true,
    val selected: Boolean = false,
    val sameSite: Boolean = true,
    val hrefPath: String? = null,        // path template only, never query values
    val value: String? = null,           // current value for filters / selects; null on auth pages
    val choices: List<String> = emptyList(),
    val effect: EffectClass = role.effect,
    val itemKey: String? = null,         // when role == RESULT_ITEM
    val features: FeatureVec = FeatureVec.EMPTY,
    val roleScore: Double = 0.5
) {
    val isCommit: Boolean get() = effect == EffectClass.COMMIT_EXTERNAL
}

data class ItemSummary(
    val key: String,
    val affordanceId: String?,
    val title: String,                   // task-lifetime only, never persisted
    val price: Int? = null,
    val mileage: Int? = null,
    val year: Int? = null,
    val hrefPath: String? = null,
    val snippet: String = ""             // short card text, task-lifetime only
)

data class Collection(
    val role: String,                    // results | messages | facets
    val itemKeys: List<String>,
    val visibleCount: Int,
    val endReached: Boolean? = null,
    val items: List<ItemSummary> = emptyList()
)

data class SemanticPageState(
    val host: String,
    val url: String,
    val urlPattern: String,
    val title: String,
    val pageType: PageType,
    val pageTypeConfidence: Double,
    val regions: List<Region>,
    val affordances: List<Affordance>,
    val collections: List<Collection>,
    val constraintsActive: Map<String, String>,
    val settle: Settle,
    val challenge: Boolean,
    val authWall: Boolean,
    val dialogOpen: Boolean,
    val hash: String,
    val detailText: String = "",         // only populated on DETAIL pages; task-lifetime only
    val textLength: Int = 0,
    val viewportHeight: Int = 0,
    val scrollHeight: Int = 0,
    val scrollY: Int = 0,
    val capturedAt: Long = 0L,
    val siteVersion: String = ""
) {
    val results: Collection? get() = collections.firstOrNull { it.role == "results" }
    val resultKeys: Set<String> get() = results?.itemKeys?.toSet() ?: emptySet()
    val isHumanOnly: Boolean get() = challenge || authWall || pageType.isHumanOnly

    fun byRole(role: Role): List<Affordance> = affordances.filter { it.role == role && it.visible && it.enabled }
    fun facet(key: String): Affordance? = affordances.firstOrNull { it.role == Role.FACET && it.facetKey == key && it.visible }
    fun affordance(id: String): Affordance? = affordances.firstOrNull { it.id == id }
    fun has(role: Role): Boolean = affordances.any { it.role == role && it.visible && it.enabled }

    /** Compact summary for planner prompts and logs (<= ~4 KB). Never includes detail text. */
    fun summary(maxAffordances: Int = 60): JsonObject {
        val o = JsonObject()
            .put("host", host)
            .put("url_pattern", urlPattern)
            .put("page_type", pageType.name)
            .put("settle", settle.name)
            .put("dialog_open", dialogOpen)
        val aff = JsonArray()
        affordances.filter { it.visible }
            .sortedByDescending { it.roleScore }
            .take(maxAffordances)
            .forEach { a ->
                val j = JsonObject()
                    .put("id", a.id)
                    .put("role", a.role.name)
                    .put("name", a.name)
                    .put("effect", a.effect.name)
                    .put("region", a.regionRole.name)
                if (a.facetKey != null) j.put("facet", a.facetKey)
                if (a.facetKind != null) j.put("facet_kind", a.facetKind)
                if (!a.enabled) j.put("enabled", false)
                if (a.selected) j.put("selected", true)
                if (a.value != null && !isHumanOnly) j.put("value", a.value.take(40))
                if (a.choices.isNotEmpty()) j.putStrings("choices", a.choices.take(25).map { it.take(30) })
                aff.add(j)
            }
        o.put("affordances", aff)
        val cols = JsonArray()
        collections.forEach { c ->
            cols.add(JsonObject().put("role", c.role).put("visible", c.visibleCount).put("total_keys", c.itemKeys.size).put("end", c.endReached))
        }
        o.put("collections", cols)
        val cons = JsonObject()
        constraintsActive.forEach { (k, v) -> cons.put(k, v.take(40)) }
        o.put("constraints_active", cons)
        return o
    }
}
