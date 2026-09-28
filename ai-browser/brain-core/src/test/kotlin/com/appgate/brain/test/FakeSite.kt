package com.appgate.brain.test

import com.appgate.brain.engine.Renderer
import com.appgate.brain.engine.RendererResult
import com.appgate.brain.engine.RendererTimeout
import com.appgate.brain.json.Json
import com.appgate.brain.json.JsonArray
import com.appgate.brain.json.JsonObject

/**
 * A scripted marketplace used by the engine tests: the renderer answers observe/act with
 * synthetic observations in the exact wire format of content.js. It models search, a price
 * facet (select), pagination, detail pages with descriptions, a blocking dialog, a login wall
 * and a message composer with a Send button.
 */
class FakeSite(val host: String = "fake.market") : Renderer {
    data class Listing(val id: Int, val title: String, val price: Int, val mileage: Int, val description: String)

    val listings = listOf(
        Listing(1, "2008 Ford Expedition Eddie Bauer", 7500, 142000, "Well maintained, 3.73 gears, tow package. Clean title."),
        Listing(2, "2011 Ford Expedition XLT 4x4", 9900, 160000, "Runs great, new tires."),
        Listing(3, "2006 Ford Expedition Limited", 4200, 198000, "Needs work. 3.73 rear end."),
        Listing(4, "2012 Ford Expedition King Ranch", 7950, 121500, "Loaded. Heated leather. 3.73 axle ratio, tow package."),
        Listing(5, "2005 Ford Expedition", 3000, 210000, "As is."),
        Listing(6, "2010 Ford Expedition EL", 6800, 150500, "Third row, no mention of gears."),
        Listing(7, "2009 Toyota Sequoia", 7000, 130000, "Not a Ford. 3.73 axle.")
    )

    var url = "https://$host/"
    var query = ""
    var priceMax: Int? = null
    var page = 1
    var dialogShown = true
    var loginWall = false
    var composerOpen = false
    var composerText = ""
    var sent = 0
    val log = mutableListOf<String>()
    var observeTimeoutsToInject = 0
    var networkMode = ""

    private fun currentListings(): List<Listing> = listings.filter { l ->
        (query.isBlank() || query.lowercase().split(' ').all { l.title.lowercase().contains(it) }) && (priceMax == null || l.price <= priceMax!!)
    }

    override fun observe(timeoutMs: Long): String {
        if (observeTimeoutsToInject > 0) { observeTimeoutsToInject--; throw RendererTimeout("injected") }
        log += "observe $url"
        return render().toString()
    }

    override fun navigate(url: String, timeoutMs: Long): RendererResult {
        log += "navigate $url"
        this.url = url
        composerOpen = false
        val path = url.substringAfter(host)
        when {
            path.startsWith("/search") -> {
                query = java.net.URLDecoder.decode(url.substringAfter("q=", "").substringBefore('&'), "UTF-8")
                priceMax = url.substringAfter("price_max=", "").substringBefore('&').toIntOrNull()
                page = url.substringAfter("page=", "1").substringBefore('&').toIntOrNull() ?: 1
            }
            path.startsWith("/item/") -> {}
            else -> { query = ""; priceMax = null; page = 1 }
        }
        return RendererResult(true, "navigated")
    }

    override fun back(timeoutMs: Long): RendererResult {
        log += "back"
        url = resultsUrl()
        composerOpen = false
        return RendererResult(true, "back")
    }

    private fun resultsUrl(): String = "https://$host/search?q=" + java.net.URLEncoder.encode(query, "UTF-8") + (priceMax?.let { "&price_max=$it" } ?: "") + (if (page > 1) "&page=$page" else "")

    override fun act(command: JsonObject, timeoutMs: Long): RendererResult {
        val cmd = command.optString("cmd")
        val id = command.optString("id")
        log += "act $cmd $id ${command.optString("text")}${command.optString("option")}${command.optString("value")}"
        val el = elementsNow().firstOrNull { it.optString("id") == id }
        when (cmd) {
            "dismiss", "click" -> {
                val name = el?.optString("name").orEmpty()
                when {
                    name == "Not now" -> dialogShown = false
                    name == "Next page" -> { page++; url = resultsUrl() }
                    name == "Message seller" -> composerOpen = true
                    name == "Send" -> { if (composerText.isNotBlank()) { sent++; composerOpen = false; composerText = "" } }
                    el?.optString("tag") == "a" && el.has("itemKey") -> { url = "https://$host/item/" + el.optString("href").substringAfterLast('/'); }
                    name == "Search" -> { url = resultsUrl() }
                    else -> return RendererResult(false, "nothing happened for '$name'")
                }
            }
            "type" -> {
                val name = el?.optString("name").orEmpty()
                val text = command.optString("text")
                when {
                    name.contains("Search") -> { query = text; if (command.optBoolean("submit")) { page = 1; url = resultsUrl() } }
                    name.contains("message") || el?.optString("tag") == "textarea" -> composerText = text
                    else -> return RendererResult(false, "not typable")
                }
            }
            "select", "set_range" -> {
                val name = el?.optString("name").orEmpty()
                val value = command.optString("option").ifBlank { command.optString("value") }
                if (name.contains("Price to")) { priceMax = value.replace("$", "").replace(",", "").toIntOrNull(); page = 1; url = resultsUrl() }
                else return RendererResult(false, "no such facet")
            }
            "scroll" -> {}
            else -> return RendererResult(false, "unsupported")
        }
        return RendererResult(true, cmd)
    }

    override fun waitSettle(timeoutMs: Long): RendererResult = RendererResult(true, "IDLE")
    override fun currentUrl(): String = url
    override fun setNetworkMode(mode: String, allowlist: List<String>, commitEndpoints: List<String>): RendererResult { networkMode = mode; return RendererResult(true) }
    override fun recover(): RendererResult { log += "recover"; return RendererResult(true) }
    override fun isAlive(): Boolean = true

    private fun elementsNow(): List<JsonObject> = render().optArray("elements")!!.objects()

    // ------------------------------------------------------------------ rendering
    private fun render(): JsonObject {
        val path = url.substringAfter(host)
        val elements = JsonArray()
        val items = JsonArray()
        var i = 0
        fun el(tag: String, name: String, extra: Map<String, Any?> = emptyMap()): JsonObject {
            val o = JsonObject().put("id", "a${i++}").put("tag", tag).put("name", name).put("visible", true).put("enabled", true)
                .put("bbox", JsonArray().add(0).add(i * 40).add(300).add(30)).put("region", extra["region"] as? String)
            extra.forEach { (k, v) -> if (k != "region") o.put(k, Json.wrap(v)) }
            return o
        }
        val regions = JsonArray()
            .add(JsonObject().put("id", "r0").put("role", "HEADER"))
            .add(JsonObject().put("id", "r1").put("role", "FILTERS"))
            .add(JsonObject().put("id", "r2").put("role", "MAIN"))
            .add(JsonObject().put("id", "r3").put("role", "DIALOG"))
        val signals = JsonObject().put("textLength", 2000).put("hasMain", true)
        var detailText = ""
        var title = "Fake Market"
        if (loginWall) {
            signals.put("passwordFields", 1).put("authPath", true).put("sparse", true).put("textLength", 200)
            elements.add(el("input", "", mapOf("type" to "email")))
            elements.add(el("button", "Log in", mapOf("submitType" to true)))
            return JsonObject().put("v", 3).put("url", "https://$host/login").put("host", host).put("title", "").put("readyState", "complete")
                .put("viewport", JsonObject().put("w", 400).put("h", 800).put("scrollY", 0).put("scrollH", 800)).put("settle", JsonObject().put("state", "IDLE"))
                .put("signals", signals).put("regions", regions).put("elements", elements).put("items", items).put("detailText", "").put("scripts", JsonArray().add("/app.v1.js"))
        }
        elements.add(el("input", "Search Fake Market", mapOf("type" to "search", "region" to "r0", "value" to query, "formHasSearch" to true, "inForm" to true)))
        elements.add(el("button", "Search", mapOf("region" to "r0", "submitType" to true, "formHasSearch" to true, "inForm" to true)))
        elements.add(el("a", "Cars", mapOf("region" to "r0", "href" to "https://$host/cars", "sameSite" to true)))
        elements.add(el("a", "Log in", mapOf("region" to "r0", "href" to "https://$host/login", "sameSite" to true)))
        if (dialogShown) {
            signals.put("dialog", true).put("dialogCoverage", 0.5)
            elements.add(el("button", "Not now", mapOf("region" to "r3", "inDialog" to true)))
            elements.add(el("button", "Download", mapOf("region" to "r3", "inDialog" to true)))
        }
        when {
            path.startsWith("/search") -> {
                title = "$query - results"
                val all = currentListings()
                val pageItems = all.drop((page - 1) * 3).take(3)
                elements.add(el("select", "Price to", mapOf("region" to "r1", "value" to (priceMax?.let { "$" + "%,d".format(it) } ?: "Any"), "choices" to listOf("Any", "$5,000", "$8,000", "$10,000"))))
                elements.add(el("select", "Sort by", mapOf("region" to "r1", "value" to "Newest", "choices" to listOf("Newest", "Price: Low to High"))))
                pageItems.forEach { l ->
                    val key = "k${l.id}"
                    elements.add(el("a", l.title, mapOf("region" to "r2", "href" to "https://$host/item/${l.id}", "sameSite" to true, "inCard" to true, "cardHasPrice" to true, "listSize" to pageItems.size, "itemKey" to key)))
                    items.add(JsonObject().put("key", key).put("title", l.title).put("price", "$" + "%,d".format(l.price)).put("href", "https://$host/item/${l.id}").put("text", "${l.title} $" + "%,d".format(l.price) + " ${"%,d".format(l.mileage)} miles").put("aff", "a${i - 1}"))
                }
                if (all.size > page * 3) elements.add(el("a", "Next page", mapOf("region" to "r2", "href" to "https://$host/search?q=$query&page=${page + 1}", "sameSite" to true, "rel" to "next")))
                signals.put("resultsHint", true).put("priceCount", pageItems.size)
            }
            path.startsWith("/item/") -> {
                val id = path.substringAfterLast('/').toIntOrNull()
                val l = listings.firstOrNull { it.id == id }
                if (l != null) {
                    title = l.title
                    detailText = "${l.title} $" + "%,d".format(l.price) + " ${"%,d".format(l.mileage)} miles. Description: ${l.description}"
                    signals.put("detailHint", true).put("priceCount", 1).put("h1", l.title)
                    elements.add(el("button", "Message seller", mapOf("region" to "r2")))
                    elements.add(el("button", "Save", mapOf("region" to "r2")))
                    if (composerOpen) {
                        signals.put("dialog", true).put("dialogCoverage", 0.4)
                        elements.add(el("textarea", "Write a message", mapOf("region" to "r3", "inDialog" to true, "value" to composerText)))
                        elements.add(el("button", "Send", mapOf("region" to "r3", "inDialog" to true)))
                    }
                }
            }
            else -> { title = "Fake Market home" }
        }
        return JsonObject().put("v", 3).put("url", url).put("host", host).put("title", title).put("readyState", "complete")
            .put("viewport", JsonObject().put("w", 400).put("h", 800).put("scrollY", 0).put("scrollH", 800))
            .put("settle", JsonObject().put("state", "IDLE"))
            .put("signals", signals).put("regions", regions).put("elements", elements).put("items", items)
            .put("detailText", detailText).put("scripts", JsonArray().add("/app.v1.js"))
    }
}
