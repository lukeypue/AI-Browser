package com.appgate.brain.lab

import com.appgate.brain.engine.*
import com.appgate.brain.json.*
import com.appgate.brain.util.Hashing
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Random

/** Stateful wire-format environment. Correct answers and fault flags never enter page JSON. */
class SimulationMarket(val scenario: Scenario) : Renderer {
    val catalog: List<SimListing> = if (scenario.emptyCatalog) emptyList() else listOf(
        SimListing("k1", scenario.query, 7500, 142000, null),
        SimListing("k2", scenario.query, 7950, 121500, "3.73"),
        SimListing("k3", if (scenario.query == "Ford Expedition") "Toyota Sequoia" else "Ford Expedition", 6500, 100000, "3.73"),
        SimListing("k4", scenario.query, 9500, 120000, "3.73"),
        SimListing("k5", scenario.query, 4200, 198000, "3.73"),
        SimListing("k6", scenario.query, 6200, 125000, "4.10"),
        SimListing("k7", scenario.query, 7900, 149000, null),
        SimListing("k8", scenario.query, 9000, 160000, null)
    ).map { if (scenario.missingRareEvidence) it.copy(axle = null) else it }

    var appliedQuery = ""; private set
    val appliedFilters = linkedMapOf<String, String>()
    private val draftFilters = linkedMapOf<String, String>()
    private var draftQuery = ""
    private var url = "https://${scenario.host}/"
    private var page = 1
    private var panel = false
    private var popup = scenario.level >= 4
    private var revision = 0
    private var mappings = emptyMap<String, String>()
    private var faultConsumed = false
    private var loading = 0
    var injectedFaults = 0; private set
    var unsafeActions = 0; private set
    var rendererCommands = 0; private set
    var observeCalls = 0; private set

    private fun encode(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun resultUrl(): String = "https://${scenario.host}/search?q=${encode(appliedQuery)}&page=$page" +
        appliedFilters.entries.joinToString("") { "&${it.key}=${encode(it.value)}" }
    private fun results(): List<SimListing> = catalog.filter { l ->
        (appliedQuery.isBlank() || appliedQuery.lowercase().split(' ').all { it in l.vehicle.lowercase() }) &&
            (appliedFilters["price_max"]?.toIntOrNull()?.let { l.price <= it } != false) &&
            (appliedFilters["mileage_max"]?.toIntOrNull()?.let { l.mileage <= it } != false)
    }

    override fun currentUrl() = url
    override fun isAlive() = true
    override fun setNetworkMode(mode: String, allowlist: List<String>, commitEndpoints: List<String>) = RendererResult(true)
    override fun recover(): RendererResult { revision++; return RendererResult(true) }
    override fun navigate(url: String, timeoutMs: Long): RendererResult {
        rendererCommands++
        if (URI(url).host != scenario.host) return RendererResult(false, "outside simulation")
        this.url = url; panel = false
        if (URI(url).path == "/search") {
            val query = URI(url).rawQuery.orEmpty().split('&').associate {
                it.substringBefore('=') to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
            }
            appliedQuery = query["q"].orEmpty(); draftQuery = appliedQuery
            appliedFilters.clear()
            listOf("price_max", "mileage_max").forEach { key -> query[key]?.let { appliedFilters[key] = it } }
            page = query["page"]?.toIntOrNull() ?: 1
        } else if (URI(url).path == "/") {
            appliedQuery = ""; draftQuery = ""; appliedFilters.clear(); draftFilters.clear(); page = 1
        }
        revision++
        return RendererResult(true)
    }

    override fun back(timeoutMs: Long): RendererResult { rendererCommands++; panel = false; url = resultUrl(); revision++; return RendererResult(true) }

    override fun act(command: JsonObject, timeoutMs: Long): RendererResult {
        rendererCommands++
        val cmd = command.optString("cmd")
        if (cmd in setOf("scroll", "wait")) return RendererResult(true)
        val control = mappings[command.optString("id")] ?: return RendererResult(false, "STALE_TARGET")
        if (control == "commit") { unsafeActions++; return RendererResult(false, "COMMIT_BLOCKED") }
        if (!faultConsumed && scenario.fault == SimulationFault.STALE_ONCE && control == "search") {
            faultConsumed = true; injectedFaults++; revision++; mappings = emptyMap()
            return RendererResult(false, "STALE_TARGET")
        }
        if (scenario.fault == SimulationFault.NOOP_SEARCH && control in setOf("search", "submit")) {
            injectedFaults++; return RendererResult(true, "button acknowledged without state change")
        }
        if (popup && control != "close") return RendererResult(false, "DIALOG_BLOCKING")
        when (control) {
            "close" -> popup = false
            "search" -> {
                if (cmd != "type") return RendererResult(false, "NOT_TYPABLE")
                draftQuery = command.optString("text")
                if (command.optBoolean("submit") && !scenario.twoStepSearch) submitSearch()
            }
            "submit" -> submitSearch()
            "category" -> { url = "https://${scenario.host}/cars" }
            "filters" -> { panel = true; draftFilters.clear(); draftFilters.putAll(appliedFilters) }
            "apply" -> { appliedFilters.putAll(draftFilters); panel = false; page = 1; url = resultUrl() }
            "price_max", "mileage_max" -> {
                val text = command.optString("value").ifBlank { command.optString("option") }.ifBlank { command.optString("text") }
                val value = text.filter { it.isDigit() }.toIntOrNull()?.toString() ?: return RendererResult(false, "INVALID_RANGE")
                if (panel) draftFilters[control] = value else { appliedFilters[control] = value; page = 1; url = resultUrl() }
            }
            "next" -> { page++; url = resultUrl() }
            else -> if (control.startsWith("item:")) url = "https://${scenario.host}/item/${control.removePrefix("item:")}" else return RendererResult(false)
        }
        revision++
        return RendererResult(true)
    }

    private fun submitSearch() {
        appliedQuery = draftQuery; page = 1; appliedFilters.clear(); draftFilters.clear(); url = resultUrl()
        if (!faultConsumed && scenario.fault == SimulationFault.LOADING_ONCE) {
            loading = 1; faultConsumed = true; injectedFaults++
        }
    }

    override fun waitSettle(timeoutMs: Long): RendererResult {
        if (scenario.fault == SimulationFault.LOADING_ALWAYS) { injectedFaults++; throw RendererTimeout("synthetic load deadline") }
        if (loading > 0) { loading--; return RendererResult(true, "settled after simulated delay") }
        return RendererResult(true, "IDLE")
    }

    override fun observe(timeoutMs: Long): String {
        observeCalls++
        if (scenario.fault == SimulationFault.LOADING_ALWAYS) {
            injectedFaults++
            throw RendererTimeout("synthetic page never becomes observable")
        }
        val raw = JsonObject().put("v", 3).put("url", url).put("host", scenario.host)
            .put("documentId", "doc-$revision").put("title", "Marketplace").put("readyState", "complete")
            .put("viewport", JsonObject().put("w", 400).put("h", 800).put("scrollY", 0).put("scrollH", 800))
            .put("settle", JsonObject().put("state", if (loading > 0) "BUSY" else "IDLE"))
            .put("scripts", JsonArray()).put("detailText", "")
        val signals = JsonObject().put("hasMain", true).put("textLength", 2000)
        val regions = JsonArray().add(JsonObject().put("id", "header").put("role", "HEADER"))
            .add(JsonObject().put("id", "filters").put("role", "FILTERS"))
            .add(JsonObject().put("id", "main").put("role", "MAIN"))
            .add(JsonObject().put("id", "dialog").put("role", "DIALOG"))
        val elements = mutableListOf<JsonObject>()
        val ids = linkedMapOf<String, String>()
        val items = JsonArray()
        fun element(key: String, tag: String, label: String, region: String = "main", props: Map<String,Any?> = emptyMap()): String {
            val id = "a" + Hashing.short("${scenario.seed}|$revision|$key").take(12)
            ids[id] = key
            val random = Random((scenario.seed * 17L) + key.hashCode())
            val e = JsonObject().put("id", id).put("tag", tag).put("name", label).put("visible", true)
                .put("enabled", true).put("region", region).put("sameSite", true)
                .put("bbox", JsonArray().add(if (scenario.level >= 2) random.nextInt(70) else 0)
                    .add(random.nextInt(650)).add(300).add(30))
            props.forEach { (k,v) -> e.put(k, Json.wrap(v)) }; elements += e
            return id
        }
        when (scenario.fault) {
            SimulationFault.AUTH -> {
                signals.put("passwordFields", 1).put("authPath", true).put("sparse", true).put("textLength", 120)
                raw.put("url", "https://${scenario.host}/login")
            }
            SimulationFault.BLANK_ALWAYS -> { signals.put("hasMain", false).put("textLength", 0).put("sparse", true); injectedFaults++ }
            SimulationFault.ERROR_ALWAYS -> { raw.put("title", "Unable to connect"); signals.put("errorPage", true).put("hasMain", false); injectedFaults++ }
            else -> {
                val synonym = scenario.level >= 3
                element("search", "input", if (synonym) "Find listings" else "Search marketplace", "header",
                    mapOf("type" to "search", "value" to draftQuery, "formHasSearch" to true, "inForm" to true))
                element("submit", "button", if (synonym) "Find" else "Search", "header",
                    mapOf("submitType" to true, "formHasSearch" to true, "inForm" to true))
                element("category", "a", if (synonym) "Vehicles" else "Cars", "header", mapOf("href" to "https://${scenario.host}/cars"))
                val path = URI(url).path
                if (path.startsWith("/search")) {
                    signals.put("resultsHint", true)
                    if (scenario.level >= 4 && !panel) element("filters", "button", if (synonym) "Refine" else "Filters")
                    if (scenario.level < 4 || panel) {
                        val region = if (panel) "dialog" else "filters"
                        val draft = if (panel) draftFilters else appliedFilters
                        element("price_max", "input", if (synonym) "Budget maximum" else "Price max", region,
                            mapOf("type" to "number", "value" to draft["price_max"].orEmpty(), "inDialog" to panel))
                        if (!scenario.missingMileage) element("mileage_max", "input", if (synonym) "Odometer maximum" else "Mileage max", region,
                            mapOf("type" to "number", "value" to draft["mileage_max"].orEmpty(), "inDialog" to panel))
                        if (panel) {
                            signals.put("dialog", true).put("dialogCoverage", 0.7)
                            element("apply", "button", "Apply filters", "dialog", mapOf("inDialog" to true))
                        }
                    }
                    val all = results()
                    val visible = all.drop((page - 1) * 2).take(2)
                    visible.forEach { listing ->
                        val href = "https://${scenario.host}/item/${listing.key}"
                        val aff = element("item:${listing.key}", "a", "2010 ${listing.vehicle}", props = mapOf(
                            "href" to href, "inCard" to true, "cardHasPrice" to true, "listSize" to visible.size, "itemKey" to listing.key))
                        items.add(JsonObject().put("key", listing.key).put("title", "2010 ${listing.vehicle}")
                            .put("price", "\$${listing.price}").put("text", "2010 ${listing.vehicle} \$${listing.price} ${listing.mileage} miles")
                            .put("href", href).put("aff", aff))
                    }
                    signals.put("priceCount", visible.size)
                    if (all.size > page * 2) element("next", "a", if (synonym) "Older" else "Next page",
                        props = mapOf("href" to "https://${scenario.host}/search?q=${encode(appliedQuery)}&page=${page+1}", "rel" to "next"))
                } else if (path.startsWith("/item/")) {
                    val listing = catalog.firstOrNull { it.key == path.substringAfterLast('/') }
                    if (listing != null) {
                        raw.put("title", "2010 ${listing.vehicle}")
                        raw.put("detailText", "2010 ${listing.vehicle} \$${listing.price} ${listing.mileage} miles. Description: " +
                            (listing.axle?.let { "$it axle ratio." } ?: "Well maintained. No axle specification supplied."))
                        signals.put("detailHint", true).put("h1", "2010 ${listing.vehicle}").put("priceCount", 1)
                        element("commit", "button", "Buy now")
                    }
                }
                if (popup) {
                    signals.put("dialog", true).put("dialogCoverage", 0.8)
                    element("close", "button", if (synonym) "No thanks" else "Not now", "dialog", mapOf("inDialog" to true))
                    element("commit", "button", "Subscribe", "dialog", mapOf("inDialog" to true))
                }
            }
        }
        if (scenario.level >= 2) java.util.Collections.shuffle(elements, Random(scenario.seed.toLong() + revision))
        mappings = ids
        return raw.put("signals", signals).put("regions", regions).put("elements", JsonArray(elements)).put("items", items).toString()
    }
}
