package com.appgate.brain.perception

import com.appgate.brain.model.Affordance
import com.appgate.brain.model.BBox
import com.appgate.brain.model.EffectClass
import com.appgate.brain.model.FeatureVec
import com.appgate.brain.model.PageType
import com.appgate.brain.model.RegionRole
import com.appgate.brain.model.Role

/** Raw element as emitted by the content script (attributes only, no semantics). */
data class RawElement(
    val id: String,
    val tag: String,
    val type: String?,
    val ariaRole: String?,
    val name: String,
    val placeholder: String?,
    val regionId: String?,
    val regionRole: RegionRole,
    val bbox: BBox?,
    val visible: Boolean,
    val enabled: Boolean,
    val selected: Boolean,
    val expanded: Boolean?,
    val hasPopup: Boolean,
    val hrefPath: String?,
    val hrefQueryKeys: List<String>,
    val sameSite: Boolean,
    val value: String?,
    val choices: List<String>,
    val near: String,
    val depth: Int,
    val siblingIndex: Int,
    val siblingCount: Int,
    val inForm: Boolean,
    val submitType: Boolean,
    val min: String?,
    val max: String?,
    val classTokens: List<String>,
    val contentEditable: Boolean,
    val rel: String?,
    val inCard: Boolean,
    val cardHasPrice: Boolean,
    val listSize: Int,
    val itemKey: String?,
    val formHasSearch: Boolean,
    val inDialog: Boolean
)

data class ClassifierContext(
    val pageTypeGuess: PageType,
    val siteFacetVocabulary: Map<String, String> = emptyMap(),
    val isAuthPage: Boolean = false
)

/**
 * Deterministic role assignment. Produces the [Affordance] the rest of the brain reasons
 * over, including the effect class (which the planner can never override) and a feature
 * vector for grounding.
 */
object RoleClassifier {
    private val inputTextTypes = setOf("", "text", "search", "tel", "url", "email", "number")

    fun classify(raw: RawElement, ctx: ClassifierContext): Affordance? {
        val tag = raw.tag.lowercase()
        val type = (raw.type ?: "").lowercase()
        if (type == "password" || type == "hidden" || type == "file") return null
        val aria = (raw.ariaRole ?: "").lowercase()
        val label = Redactor.name(listOf(raw.name, raw.placeholder ?: "").filter { it.isNotBlank() }.distinctBy { it.lowercase() }.joinToString(" "), 80)
        val labelLower = Vocabulary.normalize(label)
        val nearLower = Vocabulary.normalize(raw.near)
        val hrefLower = (raw.hrefPath ?: "").lowercase()
        val isLink = tag == "a" || aria == "link"
        val isButton = tag == "button" || aria == "button" || (tag == "input" && (type == "submit" || type == "button")) || aria == "menuitem"
        val isTextInput = (tag == "input" && type in inputTextTypes) || tag == "textarea" || raw.contentEditable || aria == "searchbox" || aria == "textbox" || aria == "combobox"
        val isSelect = tag == "select" || aria == "listbox"
        val isCheck = tag == "input" && (type == "checkbox" || type == "radio") || aria == "checkbox" || aria == "radio" || aria == "switch"
        val isRange = tag == "input" && type == "range"
        val isOption = aria == "option"
        val isTab = aria == "tab"
        val footerNoise = Vocabulary.matches("footer_noise", labelLower) || raw.regionRole == RegionRole.FOOTER

        // Site vocabulary first (learned / profile), then global lexicon.
        val isFormControl = isTextInput || isSelect || isCheck || isRange || isOption
        val facetFromSite = ctx.siteFacetVocabulary[labelLower] ?: ctx.siteFacetVocabulary.entries.firstOrNull { (k, _) -> k.isNotBlank() && Vocabulary.wordMatch(labelLower, k) }?.value
        // Buttons/links get a facet key from their own label only; form controls may borrow the nearby label.
        val facetKey = facetFromSite ?: Vocabulary.canonicalFacet(label) ?: (if (isFormControl) Vocabulary.canonicalFacet(raw.near.take(60)) else null)
        val direction = Vocabulary.numericDirection(label) ?: (if (isFormControl) Vocabulary.numericDirection(raw.near.take(60)) else null)
        val isApplyOrClear = (isButton || isLink) && (Vocabulary.matches("filter_apply", labelLower) || Vocabulary.matches("filter_clear", labelLower))
        val inFilters = raw.regionRole == RegionRole.FILTERS || raw.regionRole == RegionRole.DIALOG || raw.inDialog

        val scores = LinkedHashMap<Role, Double>()
        fun bump(role: Role, s: Double) { if (s > 0) scores[role] = maxOf(scores[role] ?: 0.0, s) }

        if (ctx.isAuthPage) {
            // On auth walls we only expose roles, never names/values, and only a few roles.
            return when {
                isTextInput -> null
                Vocabulary.matches("login", labelLower) -> build(raw, Role.LOGIN, null, null, "", ctx)
                Vocabulary.matches("close", labelLower) -> build(raw, Role.CLOSE, null, null, "", ctx)
                else -> null
            }
        }

        // --- Search ---
        if (isTextInput) {
            val s = when {
                type == "search" || aria == "searchbox" -> 0.95
                Vocabulary.matches("search", labelLower) -> 0.9
                raw.formHasSearch && facetKey == null -> 0.6
                raw.regionRole == RegionRole.HEADER && facetKey == null -> 0.55
                else -> 0.0
            }
            bump(Role.SEARCH_BOX, s)
        }
        if (isButton && (raw.submitType || Vocabulary.matches("search", labelLower)) && (raw.formHasSearch || Vocabulary.matches("search", labelLower))) {
            bump(Role.SUBMIT, if (raw.formHasSearch) 0.9 else 0.7)
        }

        // --- Facets ---
        if (facetKey != null && facetKey != "sort" && facetKey != "category" && !isApplyOrClear) {
            when {
                isSelect -> bump(Role.FACET, 0.92)
                isRange -> bump(Role.FACET, 0.9)
                isTextInput && (type == "number" || facetKey in Vocabulary.numericKeys || facetKey == "zip" || facetKey == "location" || facetKey == "keyword") -> bump(Role.FACET, 0.9)
                isCheck -> bump(Role.FACET, 0.85)
                isOption -> bump(Role.FACET, 0.9)
                aria == "combobox" && raw.choices.isEmpty() && raw.expanded != null -> bump(Role.FACET_OPEN, 0.96)
                aria == "combobox" && raw.choices.isNotEmpty() -> bump(Role.FACET, 0.96)
                isButton && (raw.hasPopup || raw.expanded != null || inFilters) -> bump(Role.FACET_OPEN, 0.85)
                isButton -> bump(Role.FACET_OPEN, 0.6)
                isLink && inFilters -> bump(Role.FACET, 0.7)
                isLink && raw.hrefQueryKeys.isNotEmpty() -> bump(Role.FACET, 0.55)
            }
        } else if (inFilters && (isSelect || isRange || isCheck || (isTextInput && type == "number"))) {
            bump(Role.FACET, 0.6)
        }
        if (isButton || isLink) {
            if (Vocabulary.matches("filter_open", labelLower) && !isApplyOrClear) bump(Role.FACET_OPEN, 0.9)
            if (Vocabulary.matches("filter_apply", labelLower) && inFilters) bump(Role.FACET_APPLY, 0.95)
            else if (Vocabulary.matches("filter_apply", labelLower) && raw.inForm) bump(Role.FACET_APPLY, 0.6)
            if (Vocabulary.matches("filter_clear", labelLower)) bump(Role.FACET_CLEAR, 0.9)
        }

        // --- Sort ---
        if (facetKey == "sort" || Vocabulary.matches("sort", labelLower) || (isSelect && raw.choices.any { Vocabulary.matches("sort", it) })) {
            bump(Role.SORT, if (isSelect || isButton || aria == "combobox") 0.9 else if (isOption) 0.8 else 0.5)
        }

        // --- Pagination / more ---
        if (isButton || isLink) {
            val relNext = raw.rel?.contains("next") == true
            val relPrev = raw.rel?.contains("prev") == true
            if (relNext || Vocabulary.matches("next", labelLower) || raw.hrefQueryKeys.any { it in setOf("page", "p", "pg", "offset", "start") } && labelLower.matches(Regex("[0-9]+|next.*"))) bump(Role.PAGE_NEXT, if (relNext) 0.95 else 0.85)
            if (relPrev || Vocabulary.matches("prev", labelLower)) bump(Role.PAGE_PREV, 0.85)
            if (Vocabulary.matches("load_more", labelLower)) bump(Role.LOAD_MORE, 0.92)
            if (Vocabulary.matches("expand", labelLower) && !Vocabulary.matches("load_more", labelLower) &&
                !(facetKey != null && ctx.pageTypeGuess in setOf(PageType.RESULTS, PageType.FACET_PANEL))) {
                bump(Role.EXPAND_TEXT, if (raw.expanded == false) 0.92 else if (ctx.pageTypeGuess == PageType.DETAIL) 0.8 else 0.5)
            }
        }

        // --- Result items ---
        if (isLink && raw.sameSite && raw.hrefPath != null) {
            val detailPath = Regex("/(item|listing|listings|detail|details|product|vehicle|vehicles|ad|ads|post|posts|itm|p|dp|cars|listing-detail)/").containsMatchIn(hrefLower) ||
                Regex("/[0-9]{5,}").containsMatchIn(hrefLower) || Regex("/[a-z0-9-]+-[0-9]{5,}").containsMatchIn(hrefLower)
            val s = when {
                raw.inCard && raw.cardHasPrice && raw.listSize >= 3 -> 0.95
                raw.inCard && raw.listSize >= 3 && detailPath -> 0.9
                raw.regionRole == RegionRole.RESULT_LIST && (raw.cardHasPrice || detailPath) -> 0.85
                raw.inCard && raw.cardHasPrice -> 0.75
                detailPath && raw.listSize >= 3 -> 0.7
                raw.regionRole == RegionRole.RESULT_LIST && raw.listSize >= 3 -> 0.6
                else -> 0.0
            }
            bump(Role.RESULT_ITEM, s)
        }

        // --- Navigation / categories ---
        if (isLink && raw.sameSite && !footerNoise) {
            val cat = Vocabulary.matches("category", labelLower) || Regex("/(category|categories|c|cat|browse|for-sale|cars|vehicles|autos|classifieds|marketplace|listings|search)(/|$)").containsMatchIn(hrefLower)
            if (cat) bump(Role.CATEGORY_LINK, if (raw.regionRole == RegionRole.NAV || raw.regionRole == RegionRole.MAIN) 0.75 else 0.6)
            else if (raw.regionRole == RegionRole.NAV || raw.regionRole == RegionRole.HEADER) bump(Role.NAV_LINK, 0.5)
            else bump(Role.GENERIC_LINK, 0.3)
        }
        if (isTab) bump(Role.TAB, 0.85)

        // --- Dialog / navigation ---
        if (isButton || isLink) {
            if (Vocabulary.matches("close", labelLower) || (labelLower == "x" && raw.bbox != null && raw.bbox.w < 60)) bump(Role.CLOSE, if (raw.inDialog) 0.95 else 0.7)
            if (Vocabulary.matches("back", labelLower)) bump(Role.BACK, 0.8)
            if (Vocabulary.matches("login", labelLower)) bump(Role.LOGIN, 0.9)
        }

        // --- Messaging / consequential ---
        if (isButton || isLink) {
            if (Vocabulary.matches("message", labelLower) && !Vocabulary.matches("account", labelLower)) bump(Role.MESSAGE_SELLER, if (ctx.pageTypeGuess == PageType.DETAIL) 0.9 else 0.6)
            if (Vocabulary.matches("send", labelLower) && (raw.inDialog || raw.regionRole == RegionRole.COMPOSER || ctx.pageTypeGuess == PageType.MESSAGES || ctx.pageTypeGuess == PageType.DETAIL)) bump(Role.SEND, 0.9)
            if (Vocabulary.matches("attach", labelLower)) bump(Role.ATTACH, 0.8)
            if (Vocabulary.matches("buy", labelLower)) bump(Role.BUY, 0.9)
            if (Vocabulary.matches("bid", labelLower)) bump(Role.BID, 0.9)
            if (Vocabulary.matches("post", labelLower) && !Vocabulary.matches("category", labelLower)) bump(Role.POST, 0.85)
            if (Vocabulary.matches("delete", labelLower)) bump(Role.DELETE, 0.9)
            if (Vocabulary.matches("follow", labelLower)) bump(Role.FOLLOW, 0.85)
            if (Vocabulary.matches("save", labelLower)) bump(Role.SAVE, 0.8)
            if (Vocabulary.matches("report", labelLower)) bump(Role.REPORT, 0.85)
            if (Vocabulary.matches("share", labelLower)) bump(Role.SHARE, 0.8)
            if (Vocabulary.matches("account", labelLower)) bump(Role.ACCOUNT, 0.7)
        }
        if ((tag == "textarea" || raw.contentEditable) && (raw.inDialog || raw.regionRole == RegionRole.COMPOSER || Vocabulary.matches("composer", labelLower) || Vocabulary.matches("composer", nearLower))) {
            bump(Role.COMPOSER_INPUT, 0.9)
        }

        // Fallbacks
        if (scores.isEmpty()) {
            when {
                isButton && !footerNoise -> bump(Role.GENERIC_BUTTON, 0.25)
                isLink && raw.sameSite && !footerNoise -> bump(Role.GENERIC_LINK, 0.2)
                isSelect || isCheck || isRange -> bump(Role.FACET, 0.35)
                isTextInput -> bump(Role.SEARCH_BOX, 0.2)
                else -> return null
            }
        }

        val (role, score) = scores.entries.maxByOrNull { it.value }!!
        val kind = when (role) {
            Role.FACET -> when {
                isRange -> "range"
                isSelect || isOption -> "choice"
                isCheck -> "toggle"
                isTextInput && (facetKey in Vocabulary.numericKeys) -> if (direction == "min") "numeric_min" else "numeric_max"
                isTextInput -> "text"
                isLink -> "choice"
                else -> "choice"
            }
            else -> null
        }
        val resolvedFacet = when (role) {
            Role.FACET, Role.FACET_OPEN -> when {
                facetKey != null && facetKey in Vocabulary.numericKeys && (kind == "numeric_min" || kind == "numeric_max") -> facetKey + "_" + (if (kind == "numeric_min") "min" else "max")
                facetKey != null && facetKey in Vocabulary.numericKeys && kind == "choice" && direction != null -> facetKey + "_" + direction
                else -> facetKey
            }
            Role.SORT -> "sort"
            else -> null
        }
        return build(raw, role, resolvedFacet, kind, label, ctx, score)
    }

    private fun build(raw: RawElement, role: Role, facetKey: String?, facetKind: String?, label: String, ctx: ClassifierContext, score: Double = 0.5): Affordance {
        val name = if (ctx.isAuthPage) "" else Redactor.name(label, 40)
        val features = features(raw, role, label)
        val effect = if (ctx.isAuthPage) EffectClass.NAVIGATE else role.effect
        return Affordance(
            id = raw.id,
            role = role,
            facetKey = facetKey,
            facetKind = facetKind,
            name = name,
            tag = raw.tag.lowercase(),
            inputType = raw.type?.lowercase()?.takeIf { it.isNotBlank() },
            regionId = raw.regionId,
            regionRole = raw.regionRole,
            bbox = raw.bbox,
            visible = raw.visible,
            enabled = raw.enabled,
            selected = raw.selected,
            sameSite = raw.sameSite,
            hrefPath = raw.hrefPath?.let { UrlPatterns.pathTemplate(it) },
            value = if (ctx.isAuthPage) null else raw.value?.let { Redactor.name(it, 40) }?.takeIf { it.isNotBlank() },
            choices = if (ctx.isAuthPage) emptyList() else raw.choices.map { Redactor.name(it, 40) }.filter { it.isNotBlank() }.take(120),
            effect = effect,
            itemKey = raw.itemKey,
            features = features,
            roleScore = score
        )
    }

    /** Position-independent description used by the grounder and stored in bindings. */
    fun features(raw: RawElement, role: Role, label: String): FeatureVec {
        val m = LinkedHashMap<String, Double>()
        m["tag:" + raw.tag.lowercase()] = 1.0
        raw.type?.takeIf { it.isNotBlank() }?.let { m["type:" + it.lowercase()] = 1.0 }
        raw.ariaRole?.takeIf { it.isNotBlank() }?.let { m["aria:" + it.lowercase()] = 1.0 }
        m["region:" + raw.regionRole.name.lowercase()] = 1.0
        if (raw.inForm) m["in_form"] = 1.0
        if (raw.inDialog) m["in_dialog"] = 1.0
        if (raw.inCard) m["in_card"] = 1.0
        if (raw.hasPopup) m["haspopup"] = 1.0
        if (raw.expanded != null) m["expandable"] = 1.0
        if (raw.expanded == true) m["expanded"] = 1.0
        if (raw.submitType) m["submit"] = 1.0
        if (raw.contentEditable) m["editable"] = 1.0
        val bbox = raw.bbox
        if (bbox != null) {
            m["ybucket:" + (bbox.y / 300).coerceIn(0, 12)] = 1.0
            m["wide"] = if (bbox.w > 500) 1.0 else 0.0
            m["small"] = if (bbox.w < 60 && bbox.h < 60) 1.0 else 0.0
        }
        m["depth:" + (raw.depth / 4).coerceIn(0, 8)] = 1.0
        Vocabulary.normalize(label).split(' ').filter { it.length in 2..20 }.take(6).forEach { m["w:$it"] = 1.0 }
        raw.classTokens.take(3).forEach { m["c:" + it.lowercase().take(24)] = 0.5 }
        m["role:" + role.name.lowercase()] = 1.0
        return FeatureVec(m)
    }
}

object UrlPatterns {
    private val numeric = Regex("/[0-9]{4,}(?=/|$)")
    private val hexId = Regex("/[0-9a-fA-F-]{20,}(?=/|$)")
    private val slugId = Regex("(/[a-z0-9-]+)-[0-9]{5,}(?=/|$)")

    /** Path with numeric / hex identifiers replaced by :id — never includes query values. */
    fun pathTemplate(path: String): String {
        var p = path.substringBefore('?').substringBefore('#')
        if (p.isBlank()) p = "/"
        p = slugId.replace(p, "$1-:id")
        p = numeric.replace(p, "/:id")
        p = hexId.replace(p, "/:id")
        return p.take(120)
    }

    /** host + path template + sorted query keys (no values). */
    fun urlPattern(url: String): String {
        val noHash = url.substringBefore('#')
        val schemeless = noHash.substringAfter("://", noHash)
        val hostAndPath = schemeless.substringBefore('?')
        val host = hostAndPath.substringBefore('/').lowercase()
        val path = if (hostAndPath.contains('/')) "/" + hostAndPath.substringAfter('/') else "/"
        val query = noHash.substringAfter('?', "")
        val keys = query.split('&').mapNotNull { it.substringBefore('=').takeIf { k -> k.isNotBlank() } }.map { it.lowercase() }.sorted().distinct()
        return host + pathTemplate(path) + (if (keys.isEmpty()) "" else "?" + keys.joinToString(","))
    }

    fun host(url: String): String {
        val schemeless = url.substringAfter("://", url)
        return schemeless.substringBefore('/').substringBefore('?').substringBefore('#').lowercase()
    }

    fun baseDomain(host: String): String {
        val parts = host.lowercase().removeSuffix(".").split('.')
        if (parts.size <= 2) return parts.joinToString(".")
        val secondLevel = setOf("co", "com", "net", "org", "gov", "edu", "ac")
        return if (parts[parts.size - 2] in secondLevel && parts[parts.size - 1].length == 2) parts.takeLast(3).joinToString(".") else parts.takeLast(2).joinToString(".")
    }

    fun sameSite(a: String, b: String): Boolean = baseDomain(host(a)) == baseDomain(host(b))

    fun queryValue(url: String, key: String): String? {
        val query = url.substringBefore('#').substringAfter('?', "")
        if (query.isBlank()) return null
        return query.split('&').firstOrNull { it.substringBefore('=').equals(key, true) }?.substringAfter('=', "")
    }
}
