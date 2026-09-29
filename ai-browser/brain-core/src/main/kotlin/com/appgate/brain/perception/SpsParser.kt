package com.appgate.brain.perception

import com.appgate.brain.json.Json
import com.appgate.brain.json.JsonObject
import com.appgate.brain.model.Affordance
import com.appgate.brain.model.BBox
import com.appgate.brain.model.Collection
import com.appgate.brain.model.ItemSummary
import com.appgate.brain.model.PageType
import com.appgate.brain.model.Region
import com.appgate.brain.model.RegionRole
import com.appgate.brain.model.Role
import com.appgate.brain.model.SemanticPageState
import com.appgate.brain.model.Settle
import com.appgate.brain.util.Hashing
import com.appgate.brain.util.Text

/** Raw page-level signals from the content script. */
data class PageSignals(
    val passwordFields: Int,
    val authPath: Boolean,
    val authPhrases: Boolean,
    val challengeWidget: Boolean,
    val challengePhrase: Boolean,
    val challengePath: Boolean,
    val dialog: Boolean,
    val dialogCoverage: Double,
    val sparse: Boolean,
    val textLength: Int,
    val formCount: Int,
    val resultsHint: Boolean,
    val detailHint: Boolean,
    val loginLinks: Int,
    val hasMain: Boolean,
    val errorHint: Boolean,
    val priceCount: Int,
    val h1: String
)

/**
 * Turns the content script's raw JSON into a [SemanticPageState]. All semantics (roles,
 * facet keys, page type, constraints, hashing, redaction) happen here so they are testable
 * on the JVM and identical across renderers.
 */
class SpsParser(private val siteFacetVocabulary: Map<String, String> = emptyMap()) {

    fun parse(json: String, now: Long = System.currentTimeMillis()): SemanticPageState = parse(Json.parseObject(json), now)

    fun parse(o: JsonObject, now: Long = System.currentTimeMillis()): SemanticPageState {
        val url = o.optString("url")
        val host = o.optString("host").ifBlank { UrlPatterns.host(url) }.lowercase()
        val title = Redactor.name(o.optString("title"), 80)
        val sig = signals(o.optObject("signals") ?: JsonObject())
        val viewport = o.optObject("viewport") ?: JsonObject()
        val settleObj = o.optObject("settle") ?: JsonObject()

        val regions = o.optArray("regions")?.objects()?.map { r ->
            Region(r.optString("id"), regionRole(r.optString("role")), BBox.fromJson(r.optArray("bbox")))
        } ?: emptyList()
        val regionRoles = regions.associate { it.id to it.role }

        val isAuthPage = isAuthWall(url, host, sig)
        val challenge = isChallenge(url, sig)
        val guess = preliminaryPageType(url, sig, challenge, isAuthPage)
        val ctx = ClassifierContext(guess, siteFacetVocabulary, isAuthPage || challenge)

        val rawElements = o.optArray("elements")?.objects()?.map { e -> rawElement(e, regionRoles, host) } ?: emptyList()
        val affordances = rawElements.mapNotNull { RoleClassifier.classify(it, ctx) }
            .let { dedupe(it) }

        val items = if (isAuthPage || challenge) emptyList() else parseItems(o, affordances)
        val collections = mutableListOf<Collection>()
        if (items.isNotEmpty()) {
            collections += Collection(
                role = "results",
                itemKeys = items.map { it.key },
                visibleCount = items.size,
                endReached = null,
                items = items
            )
        }
        val dialogOpen = sig.dialog
        val pageType = finalPageType(url, sig, challenge, isAuthPage, affordances, items, dialogOpen)
        val confidence = pageTypeConfidence(pageType, sig, affordances, items)
        val constraints = if (isAuthPage || challenge) emptyMap() else activeConstraints(url, affordances)
        val detailText = if (pageType == PageType.DETAIL) Redactor.snippet(o.optString("detailText"), 30_000) else ""
        val hash = hash(host, pageType, affordances, constraints, items, dialogOpen)
        // UI roles/facet schema only: bundle URLs, ads and listing IDs are not redesigns.
        val schema = affordances.filter { it.visible && it.role != Role.RESULT_ITEM }
            .map { "${it.role}:${it.facetKey.orEmpty()}:${it.tag}" }.distinct().sorted()
        val siteVersion = "semantic:" + Hashing.short(pageType.name + "|" + schema.joinToString("|"))
        val settle = when (settleObj.optString("state")) {
            "IDLE" -> Settle.IDLE
            "BUSY" -> Settle.BUSY
            else -> Settle.UNKNOWN
        }
        return SemanticPageState(
            host = host,
            url = url,
            urlPattern = UrlPatterns.urlPattern(url),
            title = if (isAuthPage || challenge) "" else title,
            pageType = pageType,
            pageTypeConfidence = confidence,
            regions = regions,
            affordances = affordances,
            collections = collections,
            constraintsActive = constraints,
            settle = settle,
            challenge = challenge,
            authWall = isAuthPage,
            dialogOpen = dialogOpen,
            hash = hash,
            detailText = detailText,
            textLength = sig.textLength,
            viewportHeight = viewport.optInt("h"),
            scrollHeight = viewport.optInt("scrollH"),
            scrollY = viewport.optInt("scrollY"),
            capturedAt = now,
            siteVersion = siteVersion
        )
    }

    private fun signals(s: JsonObject) = PageSignals(
        passwordFields = s.optInt("passwordFields"),
        authPath = s.optBoolean("authPath"),
        authPhrases = s.optBoolean("authPhrases"),
        challengeWidget = s.optBoolean("challengeWidget"),
        challengePhrase = s.optBoolean("challengePhrase"),
        challengePath = s.optBoolean("challengePath"),
        dialog = s.optBoolean("dialog"),
        dialogCoverage = s.optDouble("dialogCoverage"),
        sparse = s.optBoolean("sparse"),
        textLength = s.optInt("textLength"),
        formCount = s.optInt("formCount"),
        resultsHint = s.optBoolean("resultsHint"),
        detailHint = s.optBoolean("detailHint"),
        loginLinks = s.optInt("loginLinks"),
        hasMain = s.optBoolean("hasMain"),
        errorHint = s.optBoolean("errorHint"),
        priceCount = s.optInt("priceCount"),
        h1 = s.optString("h1")
    )

    private fun regionRole(raw: String): RegionRole = RegionRole.values().firstOrNull { it.name.equals(raw, true) } ?: RegionRole.UNKNOWN

    private fun rawElement(e: JsonObject, regionRoles: Map<String, RegionRole>, host: String): RawElement {
        val href = e.optStringOrNull("href")
        val hrefHost = href?.let { if (it.startsWith("http")) UrlPatterns.host(it) else host }
        val sameSite = hrefHost == null || UrlPatterns.baseDomain(hrefHost) == UrlPatterns.baseDomain(host)
        val hrefPath = href?.let { h ->
            if (h.startsWith("http")) "/" + h.substringAfter("://").substringAfter('/', "") else h
        }?.substringBefore('#')
        val queryKeys = href?.substringBefore('#')?.substringAfter('?', "")?.split('&')?.mapNotNull { it.substringBefore('=').takeIf { k -> k.isNotBlank() }?.lowercase() } ?: emptyList()
        val regionId = e.optStringOrNull("region")
        return RawElement(
            id = e.optString("id"),
            tag = e.optString("tag"),
            type = e.optStringOrNull("type"),
            ariaRole = e.optStringOrNull("role"),
            name = Text.clean(e.optString("name")),
            placeholder = e.optStringOrNull("placeholder"),
            regionId = regionId,
            regionRole = regionId?.let { regionRoles[it] } ?: regionRole(e.optString("regionRole")),
            bbox = BBox.fromJson(e.optArray("bbox")),
            visible = e.optBoolean("visible", true),
            enabled = e.optBoolean("enabled", true),
            selected = e.optBoolean("selected"),
            expanded = if (e.has("expanded")) e.optBoolean("expanded") else null,
            hasPopup = e.optBoolean("haspopup"),
            hrefPath = hrefPath?.substringBefore('?'),
            hrefQueryKeys = queryKeys,
            sameSite = sameSite,
            value = e.optStringOrNull("value"),
            choices = e.optStrings("choices"),
            near = Text.clean(e.optString("near")),
            depth = e.optInt("depth"),
            siblingIndex = e.optInt("idx"),
            siblingCount = e.optInt("siblings"),
            inForm = e.optBoolean("inForm"),
            submitType = e.optBoolean("submitType"),
            min = e.optStringOrNull("min"),
            max = e.optStringOrNull("max"),
            classTokens = e.optStrings("cls"),
            contentEditable = e.optBoolean("editable"),
            rel = e.optStringOrNull("rel"),
            inCard = e.optBoolean("inCard"),
            cardHasPrice = e.optBoolean("cardHasPrice"),
            listSize = e.optInt("listSize"),
            itemKey = e.optStringOrNull("itemKey"),
            formHasSearch = e.optBoolean("formHasSearch"),
            inDialog = e.optBoolean("inDialog")
        )
    }

    /** Two affordances with the same role/facet/name inside one region collapse to the best-scoring one. */
    private fun dedupe(list: List<Affordance>): List<Affordance> {
        val seen = LinkedHashMap<String, Affordance>()
        for (a in list) {
            val key = if (a.role == Role.RESULT_ITEM) a.id else "${a.role}|${a.facetKey}|${a.name.lowercase()}|${a.regionId}|${a.hrefPath}"
            val prev = seen[key]
            if (prev == null || a.roleScore > prev.roleScore) seen[key] = a
        }
        return seen.values.toList()
    }

    private fun parseItems(o: JsonObject, affordances: List<Affordance>): List<ItemSummary> {
        val affByKey = affordances.filter { it.role == Role.RESULT_ITEM && it.itemKey != null }.associateBy { it.itemKey!! }
        val out = ArrayList<ItemSummary>()
        val seen = HashSet<String>()
        o.optArray("items")?.objects()?.forEach { it ->
            val title = Redactor.snippet(it.optString("title"), 120)
            val href = it.optStringOrNull("href")
            val hrefPath = href?.let { h -> if (h.startsWith("http")) "/" + h.substringAfter("://").substringAfter('/', "") else h }?.substringBefore('#')
            val text = Redactor.snippet(it.optString("text"), 400)
            val key = it.optStringOrNull("key") ?: Hashing.short((title + "|" + (hrefPath ?: "")).lowercase())
            if (title.isBlank() || !seen.add(key)) return@forEach
            val priceText = it.optStringOrNull("price")
            out += ItemSummary(
                key = key,
                affordanceId = it.optStringOrNull("aff") ?: affByKey[key]?.id,
                title = title,
                price = priceText?.let { p -> Text.price(p) } ?: Text.price(text),
                mileage = Text.mileage(text) ?: Text.mileage(title),
                year = Text.year(title) ?: Text.year(text),
                hrefPath = hrefPath?.substringBefore('?'),
                snippet = text
            )
        }
        return out.take(200)
    }

    private fun isAuthWall(url: String, host: String, sig: PageSignals): Boolean {
        val h = host.lowercase()
        if (AuthHosts.isAuthHost(h)) return true
        val path = url.substringAfter("://", url).substringAfter('/', "").substringBefore('?').lowercase()
        val authPath = sig.authPath || Regex("(^|/)(login|log-in|signin|sign-in|signup|sign-up|auth|oauth|checkpoint|account/login|accounts/login|sessions?/new|password)(/|$)").containsMatchIn("/$path")
        if (sig.passwordFields > 0 && (sig.sparse || authPath || sig.authPhrases)) return true
        if (authPath && sig.sparse && sig.loginLinks > 0) return true
        return false
    }

    private fun isChallenge(url: String, sig: PageSignals): Boolean {
        if (sig.challengeWidget) return true
        val path = url.lowercase()
        val challengePath = sig.challengePath || Regex("(captcha|/challenge|/checkpoint|security-check|verify-you|are-you-human|cdn-cgi/challenge)").containsMatchIn(path)
        return (challengePath && (sig.challengePhrase || sig.sparse)) || (sig.challengePhrase && sig.sparse)
    }

    private fun preliminaryPageType(url: String, sig: PageSignals, challenge: Boolean, auth: Boolean): PageType = when {
        challenge -> PageType.CHALLENGE
        auth -> PageType.AUTH_WALL
        sig.detailHint && !sig.resultsHint -> PageType.DETAIL
        sig.resultsHint -> PageType.RESULTS
        else -> PageType.UNKNOWN
    }

    private fun finalPageType(url: String, sig: PageSignals, challenge: Boolean, auth: Boolean, aff: List<Affordance>, items: List<ItemSummary>, dialogOpen: Boolean): PageType {
        if (challenge) return PageType.CHALLENGE
        if (auth) return PageType.AUTH_WALL
        if (sig.errorHint && sig.sparse) return PageType.ERROR
        val path = ("/" + url.substringAfter("://", url).substringAfter('/', "")).substringBefore('?').lowercase()
        val facets = aff.count { it.role == Role.FACET || it.role == Role.FACET_OPEN }
        val hasComposer = aff.any { it.role == Role.COMPOSER_INPUT }
        if (dialogOpen && sig.dialogCoverage >= 0.35) {
            val inDialogFacets = aff.count { (it.role == Role.FACET || it.role == Role.FACET_APPLY) && (it.regionRole == RegionRole.DIALOG) }
            if (inDialogFacets >= 2) return PageType.FACET_PANEL
            if (hasComposer) return PageType.MESSAGES
            // Marketplaces open item details in a modal over the results: a detail-like dialog is a DETAIL page.
            if (sig.detailHint || aff.any { it.role == Role.MESSAGE_SELLER && it.regionRole == RegionRole.DIALOG }) return PageType.DETAIL
            if (items.size < 3) return PageType.DIALOG
        }
        if (Regex("(^|/)(messages|inbox|chat|conversations)(/|$)").containsMatchIn(path) || (hasComposer && items.isEmpty() && !sig.detailHint)) return PageType.MESSAGES
        if (Regex("(^|/)(profile|user|users|u|member|members|seller|account|my)(/|$)").containsMatchIn(path) && items.size < 3) return PageType.PROFILE
        if (items.size >= 3 || (items.size >= 2 && sig.resultsHint)) return PageType.RESULTS
        if (sig.detailHint || aff.any { it.role == Role.MESSAGE_SELLER } || (sig.priceCount in 1..3 && sig.h1.isNotBlank() && items.size < 2 && sig.textLength > 300)) return PageType.DETAIL
        if (sig.resultsHint && items.isNotEmpty()) return PageType.RESULTS
        if (sig.resultsHint) return PageType.RESULTS
        if (facets >= 3 && items.isEmpty()) return PageType.FACET_PANEL
        if (path == "/" || path.isBlank()) return PageType.HOME
        if (Regex("(^|/)(search|s|find|results|browse|listings?|classifieds?|marketplace|cars|vehicles|category|c)(/|$)").containsMatchIn(path)) {
            return if (aff.any { it.role == Role.SEARCH_BOX }) PageType.SEARCH else PageType.RESULTS
        }
        return PageType.UNKNOWN
    }

    private fun pageTypeConfidence(pageType: PageType, sig: PageSignals, aff: List<Affordance>, items: List<ItemSummary>): Double = when (pageType) {
        PageType.RESULTS -> (0.5 + 0.1 * items.size).coerceAtMost(0.95)
        PageType.DETAIL -> if (sig.detailHint) 0.85 else 0.6
        PageType.CHALLENGE, PageType.AUTH_WALL -> 0.9
        PageType.HOME -> 0.8
        PageType.UNKNOWN -> 0.2
        else -> 0.6
    }

    /** Constraints the page currently has applied: filled facets, checked toggles, URL query keys. */
    private fun activeConstraints(url: String, aff: List<Affordance>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        aff.firstOrNull { it.role == Role.SEARCH_BOX && !it.value.isNullOrBlank() }?.let { out["query"] = it.value!!.take(80) }
        for (a in aff) {
            val key = a.facetKey ?: continue
            if (a.role != Role.FACET) continue
            val v = a.value?.trim().orEmpty()
            when (a.facetKind) {
                "toggle", "choice" -> if (a.selected && a.tag != "select" && a.name.isNotBlank()) out[key] = a.name.take(40)
                else -> {}
            }
            if (a.tag == "select" && v.isNotBlank() && !isPlaceholderValue(v)) out[key] = v.take(40)
            if ((a.facetKind == "numeric_min" || a.facetKind == "numeric_max" || a.facetKind == "text" || a.facetKind == "range") && v.isNotBlank() && !isPlaceholderValue(v)) out[key] = v.take(40)
        }
        val query = url.substringBefore('#').substringAfter('?', "")
        if (query.isNotBlank()) {
            query.split('&').forEach { pair ->
                val k = pair.substringBefore('=').lowercase()
                val v = pair.substringAfter('=', "")
                val canonical = QueryKeys.canonical(k) ?: return@forEach
                if (v.isNotBlank() && canonical !in out) out[canonical] = java.net.URLDecoder.decode(v, "UTF-8").take(40)
            }
        }
        // Path-encoded facets (KSL style: /priceTo/8000/mileageTo/150000)
        val path = url.substringBefore('?').substringAfter("://", url).substringAfter('/', "")
        val parts = path.split('/')
        for (i in 0 until parts.size - 1) {
            val canonical = QueryKeys.canonical(parts[i].lowercase()) ?: continue
            val v = parts[i + 1]
            if (v.isNotBlank() && !v.contains(':') && canonical !in out) out[canonical] = java.net.URLDecoder.decode(v, "UTF-8").take(40)
        }
        return out
    }

    private fun isPlaceholderValue(v: String): Boolean {
        val t = v.lowercase().trim()
        return t.isBlank() || t == "0" || t == "any" || t == "all" || t == "none" || t.startsWith("select") || t.startsWith("choose") || t.startsWith("all ") || t.startsWith("any ") || t == "-" || t == "no max" || t == "no min" || t == "no limit" || t.startsWith("no ") || t == "default"
    }

    private fun hash(host: String, pageType: PageType, aff: List<Affordance>, constraints: Map<String, String>, items: List<ItemSummary>, dialogOpen: Boolean): String {
        val roles = aff.filter { it.visible }.map { "${it.role.name}:${it.facetKey ?: ""}" }.sorted().distinct().joinToString(",")
        val cons = constraints.toSortedMap().entries.joinToString(",") { "${it.key}=${it.value.lowercase()}" }
        val keys = items.take(50).joinToString(",") { it.key }
        return Hashing.sha256Hex("$host|${pageType.name}|$roles|$cons|$keys|$dialogOpen", 24)
    }
}

/** Query / path key canonicalisation shared by many marketplace URL schemes. */
object QueryKeys {
    private val table: Map<String, String> = mapOf(
        "q" to "query", "query" to "query", "keyword" to "query", "keywords" to "query", "search" to "query", "term" to "query", "k" to "query", "_nkw" to "query",
        "minprice" to "price_min", "min_price" to "price_min", "pricefrom" to "price_min", "price_from" to "price_min", "pricemin" to "price_min", "_udlo" to "price_min", "list_price_min" to "price_min", "price_low" to "price_min",
        "maxprice" to "price_max", "max_price" to "price_max", "priceto" to "price_max", "price_to" to "price_max", "pricemax" to "price_max", "_udhi" to "price_max", "list_price_max" to "price_max", "price_high" to "price_max", "price" to "price_max",
        "minmileage" to "mileage_min", "min_mileage" to "mileage_min", "mileagefrom" to "mileage_min", "mileage_from" to "mileage_min", "min_auto_miles" to "mileage_min",
        "maxmileage" to "mileage_max", "max_mileage" to "mileage_max", "mileageto" to "mileage_max", "mileage_to" to "mileage_max", "max_auto_miles" to "mileage_max", "mileage" to "mileage_max",
        "minyear" to "year_min", "min_year" to "year_min", "yearfrom" to "year_min", "year_from" to "year_min", "min_auto_year" to "year_min", "startyear" to "year_min",
        "maxyear" to "year_max", "max_year" to "year_max", "yearto" to "year_max", "year_to" to "year_max", "max_auto_year" to "year_max", "endyear" to "year_max",
        "make" to "make", "makes" to "make", "auto_make_model" to "make", "model" to "model", "models" to "model", "trim" to "trim",
        "sort" to "sort", "sortby" to "sort", "sort_by" to "sort", "order" to "sort", "_sop" to "sort",
        "page" to "page", "p" to "page", "pg" to "page", "offset" to "page", "start" to "page",
        "zip" to "zip", "postal" to "zip", "postal_code" to "zip", "zipcode" to "zip", "location" to "location", "city" to "location", "distance" to "distance", "radius" to "distance", "search_distance" to "distance",
        "category" to "category", "cat" to "category", "categoryid" to "category", "category_id" to "category",
        "condition" to "condition", "transmission" to "transmission", "drivetrain" to "drivetrain", "fuel" to "fuel", "color" to "color", "body" to "body_style", "bodystyle" to "body_style", "body_style" to "body_style",
        "bedrooms" to "bedrooms", "min_bedrooms" to "bedrooms", "beds" to "bedrooms", "bathrooms" to "bathrooms", "baths" to "bathrooms", "sellertype" to "seller_type", "seller_type" to "seller_type", "titletype" to "title_status"
    )

    fun canonical(key: String): String? = table[key.lowercase()]
}

object AuthHosts {
    private val exact = setOf(
        "accounts.google.com", "accounts.youtube.com", "myaccount.google.com", "accounts.googleusercontent.com",
        "appleid.apple.com", "idmsa.apple.com", "login.microsoftonline.com", "login.live.com",
        "auth.offerup.com", "login.offerup.com", "id.ksl.com", "account.ksl.com", "accounts.ksl.com", "login.ksl.com"
    )
    private val prefixes = listOf("login.", "signin.", "auth.", "accounts.", "id.", "sso.", "oauth.", "identity.")

    fun isAuthHost(host: String): Boolean {
        val h = host.lowercase().removePrefix("www.")
        if (h in exact) return true
        if (prefixes.any { h.startsWith(it) } && !h.startsWith("id.ksl")) return true
        if (h.endsWith(".auth0.com") || h.endsWith(".okta.com") || h.endsWith(".onelogin.com")) return true
        return false
    }

    /** Hosts a sign-in flow legitimately visits; used only for logging classification, never to block. */
    fun isIdentityProvider(host: String): Boolean {
        val h = host.lowercase()
        return h.endsWith("google.com") || h.endsWith("googleusercontent.com") || h.endsWith("gstatic.com") || h.endsWith("googleapis.com") ||
            h.endsWith("facebook.com") || h.endsWith("fbcdn.net") || h.endsWith("apple.com") || h.endsWith("microsoftonline.com") || h.endsWith("live.com")
    }
}
