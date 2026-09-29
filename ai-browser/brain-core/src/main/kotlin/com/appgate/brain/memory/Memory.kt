package com.appgate.brain.memory

import com.appgate.brain.json.Json
import com.appgate.brain.json.JsonArray
import com.appgate.brain.json.JsonObject
import com.appgate.brain.model.Binding
import com.appgate.brain.model.PageType
import com.appgate.brain.model.Role
import com.appgate.brain.model.SiteModel
import com.appgate.brain.model.Skill
import com.appgate.brain.model.TaskLedger
import com.appgate.brain.model.VerifyStatus
import com.appgate.brain.skills.BuiltinSkills

/**
 * One compact record per executed step. Contains no page content, no URLs with values, no
 * user text — only the semantic coordinates of what happened, so learning stays private.
 */
data class Episode(
    val at: Long,
    val host: String,
    val pageType: PageType,
    val role: Role?,
    val facetKey: String?,
    val actionKind: String,
    val status: VerifyStatus,
    val predictedP: Double,
    val toPageType: PageType,
    val ms: Long,
    val source: String
) {
    fun toJson(): JsonObject = JsonObject().put("at", at).put("page", pageType.name).put("role", role?.name).put("facet", facetKey)
        .put("kind", actionKind).put("status", status.name).put("p", predictedP).put("to", toPageType.name).put("ms", ms).put("src", source)

    companion object {
        fun fromJson(host: String, o: JsonObject) = Episode(
            o.optLong("at"), host, PageType.parse(o.optStringOrNull("page")), o.optStringOrNull("role")?.let { Role.parse(it) }, o.optStringOrNull("facet"),
            o.optString("kind"), VerifyStatus.values().firstOrNull { it.name == o.optString("status") } ?: VerifyStatus.FAILED,
            o.optDouble("p"), PageType.parse(o.optStringOrNull("to")), o.optLong("ms"), o.optString("src")
        )
    }
}

/**
 * Memory facade over [BrainStorage]. Tiers:
 *  - semantic site models (per host)           key site/<host>
 *  - procedural skills (global)                key skills
 *  - episodic traces (per host, bounded)       key episodes/<host>
 *  - task ledgers (per task, deleted when done) key ledger/<id>
 *  - planner labels for future distillation     key labels/<host> (bounded, redacted)
 */
class Memory(private val storage: BrainStorage, private val clock: () -> Long = { System.currentTimeMillis() }) {
    private val siteCache = HashMap<String, SiteModel>()
    private var skillCache: MutableMap<String, Skill>? = null
    val skills: SkillLibrary by lazy { SkillLibrary(this) }

    @Synchronized
    fun site(host: String): SiteModel {
        val key = normalizeHost(host)
        siteCache[key]?.let { return it }
        val raw = storage.read("site/$key")
        val model = Json.parseObjectOrNull(raw)?.let { runCatching { SiteModel.fromJson(it) }.getOrNull() } ?: SiteModel(key)
        val hadListingText = model.bindings.values.any { it != structuralBinding(it) }
        model.bindings.replaceAll { _, binding -> structuralBinding(binding) }
        if (hadListingText) storage.write("site/$key", model.toJson().toString())
        siteCache[key] = model
        return model
    }

    @Synchronized
    fun saveSite(model: SiteModel) {
        model.bindings.replaceAll { _, binding -> structuralBinding(binding) }
        siteCache[model.host] = model
        storage.write("site/${model.host}", model.toJson().toString())
    }

    @Synchronized
    fun knownHosts(): List<String> = storage.keys("site/").map { it.removePrefix("site/") }

    // ---- skills ----
    @Synchronized
    internal fun loadSkills(): MutableMap<String, Skill> {
        skillCache?.let { return it }
        val map = LinkedHashMap<String, Skill>()
        BuiltinSkills.all.forEach { map[it.id] = it }
        Json.parseObjectOrNull(storage.read("skills"))?.optArray("skills")?.objects()?.forEach { o ->
            runCatching { Skill.fromJson(o) }.getOrNull()?.let { s ->
                val builtin = map[s.id]
                // Builtin definitions win on body/version, persisted stats are merged in.
                map[s.id] = if (builtin != null && builtin.version >= s.version) builtin.copy(statsByHost = s.statsByHost) else s
            }
        }
        skillCache = map
        return map
    }

    @Synchronized
    internal fun saveSkills(map: Map<String, Skill>) {
        skillCache = LinkedHashMap(map)
        storage.write("skills", JsonObject().put("skills", JsonArray(map.values.map { it.toJson() })).toString())
    }

    // ---- episodes ----
    @Synchronized
    fun recordEpisode(e: Episode) {
        val key = "episodes/${e.host}"
        val arr = Json.parseObjectOrNull(storage.read(key))?.optArray("e") ?: JsonArray()
        val list = arr.objects().toMutableList()
        list += e.toJson()
        while (list.size > MAX_EPISODES_PER_HOST) list.removeAt(0)
        storage.write(key, JsonObject().put("e", JsonArray(list)).toString())
    }

    @Synchronized
    fun episodes(host: String): List<Episode> =
        Json.parseObjectOrNull(storage.read("episodes/${normalizeHost(host)}"))?.optArray("e")?.objects()?.map { Episode.fromJson(host, it) } ?: emptyList()

    // ---- ledgers ----
    @Synchronized
    fun saveLedger(ledger: TaskLedger) { storage.write("ledger/${ledger.id}", ledger.toJson().toString()) }

    @Synchronized
    fun loadLedger(id: String): TaskLedger? = Json.parseObjectOrNull(storage.read("ledger/$id"))?.let { runCatching { TaskLedger.fromJson(it) }.getOrNull() }

    @Synchronized
    fun deleteLedger(id: String) { storage.delete("ledger/$id") }

    @Synchronized
    fun ledgerIds(): List<String> = storage.keys("ledger/").map { it.removePrefix("ledger/") }

    // ---- bindings ----
    @Synchronized
    fun recordBinding(host: String, rawBinding: Binding, success: Boolean) {
        val binding = structuralBinding(rawBinding)
        val site = site(host)
        val now = clock()
        val existing = site.bindings[binding.key]
        val merged = if (existing == null) binding.copy(stats = binding.stats.record(success, now), lastVerifiedAt = if (success) now else existing?.lastVerifiedAt ?: 0L)
        else existing.copy(
            features = if (success) blend(existing.features, binding.features) else existing.features,
            nameHints = (if (success) (binding.nameHints + existing.nameHints) else existing.nameHints).distinct().take(5),
            stats = existing.stats.record(success, now),
            lastVerifiedAt = if (success) now else existing.lastVerifiedAt,
            siteVersion = if (success) binding.siteVersion else existing.siteVersion,
            shadowed = if (success) false else existing.shadowed
        )
        site.bindings[binding.key] = merged
        saveSite(site)
    }

    private fun structuralBinding(binding: Binding): Binding =
        if (binding.role in setOf(Role.RESULT_ITEM, Role.DETAIL_TITLE)) binding.copy(nameHints = emptyList(),
            features = com.appgate.brain.model.FeatureVec(binding.features.values.filterKeys { !it.startsWith("w:") && !it.startsWith("c:") }))
        else binding

    private fun blend(a: com.appgate.brain.model.FeatureVec, b: com.appgate.brain.model.FeatureVec): com.appgate.brain.model.FeatureVec {
        val keys = a.values.keys + b.values.keys
        return com.appgate.brain.model.FeatureVec(keys.associateWith { k -> 0.7 * a[k] + 0.3 * b[k] }.filterValues { it > 0.05 })
    }

    // ---- planner labels (for later distillation; redacted SPS summaries only) ----
    @Synchronized
    fun recordPlannerLabel(host: String, label: JsonObject) {
        val key = "labels/${normalizeHost(host)}"
        val list = Json.parseObjectOrNull(storage.read(key))?.optArray("l")?.objects()?.toMutableList() ?: mutableListOf()
        list += label
        while (list.size > 200) list.removeAt(0)
        storage.write(key, JsonObject().put("l", JsonArray(list)).toString())
    }

    @Synchronized
    fun wipeAll() {
        storage.keys("").forEach { storage.delete(it) }
        siteCache.clear()
        skillCache = null
    }

    fun now(): Long = clock()

    companion object {
        const val MAX_EPISODES_PER_HOST = 2000
        fun normalizeHost(host: String): String = host.lowercase().removePrefix("www.").removeSuffix(".")
    }
}
