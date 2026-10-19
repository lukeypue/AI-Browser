package com.appgate.brain.test

import com.appgate.brain.engine.*
import com.appgate.brain.json.Json
import com.appgate.brain.json.JsonObject

/** Draft input and committed results are deliberately independent, as on a real search form. */
class SearchFormSite(host: String = "fake.market", private val prefix: String = "",
    var enterWorks: Boolean = false, var submitWorks: Boolean = true) : Renderer {
    val site = FakeSite(host).apply { dialogShown = false }
    var draft = ""
    var enterAttempts = 0
    var buttonAttempts = 0
    private var submitId = ""
    override fun currentUrl() = site.currentUrl()
    override fun navigate(url: String, timeoutMs: Long): RendererResult = site.navigate(url, timeoutMs).also { draft = site.query }
    override fun back(timeoutMs: Long) = site.back(timeoutMs)
    override fun waitSettle(timeoutMs: Long) = site.waitSettle(timeoutMs)
    override fun recover() = site.recover()
    override fun isAlive() = true
    override fun setNetworkMode(mode: String, allowlist: List<String>, commitEndpoints: List<String>) = site.setNetworkMode(mode, allowlist, commitEndpoints)
    override fun observe(timeoutMs: Long): String {
        val raw = Json.parseObject(site.observe(timeoutMs))
        raw.optArray("elements")!!.objects().forEach {
            if (it.optString("type") == "search") it.put("value", draft)
            if (it.optBoolean("submitType") && it.optString("name") == "Search") submitId = it.optString("id")
            it.put("id", prefix + it.optString("id"))
        }
        raw.optArray("items")!!.objects().forEach { if (it.has("aff")) it.put("aff", prefix + it.optString("aff")) }
        return raw.toString()
    }
    override fun act(command: JsonObject, timeoutMs: Long): RendererResult {
        val translated = Json.parseObject(command.toString())
        translated.put("id", translated.optString("id").removePrefix(prefix))
        if (translated.optString("cmd") == "type") {
            draft = translated.optString("text")
            if (!translated.optBoolean("submit")) return RendererResult(true)
            enterAttempts++
            return if (enterWorks) site.act(translated, timeoutMs) else RendererResult(true, "ignored Enter")
        }
        if (translated.optString("cmd") == "click" && translated.optString("id") == submitId) {
            buttonAttempts++
            if (!submitWorks) return RendererResult(true, "ignored Submit")
            site.query = draft
        }
        return site.act(translated, timeoutMs)
    }
}
