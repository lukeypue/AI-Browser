package com.appgate.tv.serverlab

import android.content.Context
import com.appgate.tv.BuildConfig
import com.appgate.brain.profile.SiteProfile
import com.appgate.brain.json.*
import com.appgate.brain.memory.Memory
import com.appgate.brain.diagnostics.PhoneReportExporter
import java.security.MessageDigest

/** Debug-only seam. The Gradle property alone never activates fixture execution. */
object ServerLabRuntime {
    @Volatile private var observer: ((JsonObject) -> Unit)? = null
    val active: Boolean get() = BuildConfig.DEBUG && BuildConfig.SERVER_ANDROID_LAB && observer != null
    fun install(context: Context, listener: (JsonObject) -> Unit) {
        check(BuildConfig.DEBUG && BuildConfig.SERVER_ANDROID_LAB)
        check(context.packageName == "com.aibrowser.universal.serverlab")
        check(observer == null)
        observer = listener
    }
    fun detach() { observer = null }
    fun observe(report: JsonObject) { if (active) observer?.invoke(report) }
    fun profiles(default: List<SiteProfile>): List<SiteProfile> {
        if (!active) return default
        return listOf("classifieds", "cars", "marketplace", "offerup").map { key ->
            val url = "https://reports.ryzod.com/practice-fixtures/$key/"
            SiteProfile(key="serverlab_$key", name="Synthetic $key", hosts=listOf("reports.ryzod.com"),
                startUrl=url, searchUrl=url+"?q={q}&price_max={price_max}&mileage_max={mileage_max}&year_min={year_min}",
                facetVocabulary=mapOf("maximum price" to "price", "maximum mileage" to "mileage", "minimum year" to "year", "sort by" to "sort"),
                categories=if(key=="cars")setOf("vehicles") else setOf("general","shopping"),
                trainingQueries=listOf("synthetic bicycle","synthetic truck","synthetic drill"))
        }
    }
    fun qualifiesCompleted(report: JsonObject, memory: JsonObject): Boolean {
        val flags=report.optObject("flags") ?: return false
        val summary=report.optObject("summary") ?: return false
        return report.optLong("elapsed_ms") in 1_800_000L..7_200_000L &&
            !flags.optBoolean("interrupted",true) && !flags.optBoolean("clock_changed",true) &&
            summary.optString("end_reason")=="deadline" && summary.optInt("verified")>0 &&
            summary.optInt("verified_skills")>0 && memory.optInt("verified_lessons")>0
    }
    private fun hash(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    // Literal arguments, hints, query values, IDs and timestamps never enter the projection.
    private fun shape(value: JsonValue): JsonValue = when(value) {
        is JsonObject -> JsonObject().also { out -> value.entries().sortedBy { it.first }.forEach { (k,v) ->
            if(k !in setOf("name","url","query","text")) out.put(k,shape(v))
        } }
        is JsonArray -> JsonArray(value.map(::shape))
        is JsonString -> if(value.value.matches(Regex("[A-Z_]+|\\$[a-z_]+")) ||
            value.value in setOf("price","mileage","year","sort","condition","make","model","query","distance"))
            value else JsonString("<literal>")
        else -> value
    }
    /** Called only after the service executor barrier, never while a lesson writes memory. */
    fun project(memory: Memory): JsonObject {
        val skills=memory.skills.all()
        val bodies=skills.map { hash(PhoneReportExporter.canonical(shape(JsonArray(it.body.map { s -> s.toJson() })))) }.sorted()
        val verified=skills.filter { "verified_v2" in it.tags }.map { hash(PhoneReportExporter.canonical(shape(JsonArray(it.body.map { s -> s.toJson() })))) }.sorted()
        val bindings=mutableListOf<String>()
        val lessons=mutableListOf<String>()
        val failures=mutableListOf<String>()
        memory.knownHosts().sorted().forEach { host ->
            val site=memory.site(host)
            site.bindings.values.forEach { b ->
                val features=JsonObject()
                b.features.values.toSortedMap().filterKeys { !it.startsWith("w:") && !it.startsWith("c:") }.forEach { (k,v) -> features.put(k,v) }
                bindings += hash(PhoneReportExporter.canonical(JsonObject().put("host_hash",hash(host))
                    .put("page",b.pageType.name).put("role",b.role.name).put("facet",b.facetKey)
                    .put("features",features).put("shadowed",b.shadowed)))
            }
            site.curriculum.filter { it.done }.forEach { lessons += hash(host+"|"+it.id) }
            site.strategyFailures.keys.forEach { failures += hash(host+"|"+it) }
        }
        skills.filter { s -> s.tags.any { it.startsWith("failed_") } || s.statsByHost.values.any { it.failures>0 } }
            .forEach { failures += hash(PhoneReportExporter.canonical(shape(JsonArray(it.body.map { s -> s.toJson() })))) }
        val projection=JsonObject().putStrings("bodies",bodies).putStrings("verified",verified)
            .putStrings("bindings",bindings.sorted()).putStrings("lessons",lessons.sorted()).putStrings("failures",failures.sorted())
        return JsonObject().put("fingerprint",PhoneReportExporter.sha256(projection))
            .put("verified_lessons",lessons.size).put("verified_skills",verified.size).put("failed_strategies",failures.size)
    }
}
