package com.appgate.brain.memory

import com.appgate.brain.model.SiteModel
import com.appgate.brain.model.SkillOrigin

data class ConsolidationReport(
    val host: String,
    val bindingsRetired: Int,
    val bindingsShadowed: Int,
    val failuresPruned: Int,
    val edgesPruned: Int,
    val skillsDeduped: Int,
    val quarantined: Boolean,
    val notes: List<String>
)

/**
 * The nightly job (also safe to run at any idle moment): fold stats, detect version drift,
 * retire what no longer works, dedupe skills, enforce budgets. Raw observations never
 * accumulate because only verifier outcomes are aggregated.
 */
class Consolidation(private val memory: Memory) {

    fun run(host: String, now: Long = memory.now()): ConsolidationReport {
        val site = memory.site(host)
        val notes = mutableListOf<String>()
        var retired = 0; var shadowed = 0; var failuresPruned = 0; var edgesPruned = 0

        // 1. Decay-aware binding retirement: P(success) < 0.3 after 5 trials, or unseen for 60 days.
        val iter = site.bindings.entries.iterator()
        while (iter.hasNext()) {
            val (_, b) = iter.next()
            val stat = b.stats.decayed(now)
            val stale = b.lastVerifiedAt > 0 && now - b.lastVerifiedAt > 60L * 86_400_000L
            if ((stat.n >= 5 && stat.p < 0.3) || stale) { iter.remove(); retired++ }
        }

        // 2. Version drift: bindings verified under a different site version get shadowed, not deleted.
        if (site.siteVersion.isNotBlank()) {
            site.bindings.replaceAll { _, b ->
                if (b.siteVersion.isNotBlank() && b.siteVersion != site.siteVersion && !b.shadowed && b.stats.decayed(now).p < 0.6) { shadowed++; b.copy(shadowed = true) } else b
            }
        }

        // 3. Failure memory decays slower than successes but must not grow without bound.
        val failIter = site.failures.entries.iterator()
        while (failIter.hasNext()) {
            val (_, f) = failIter.next()
            if (now - f.lastAt > 45L * 86_400_000L) { failIter.remove(); failuresPruned++ }
        }
        if (site.failures.size > 400) {
            site.failures.entries.sortedBy { it.value.lastAt }.take(site.failures.size - 400).forEach { site.failures.remove(it.key); failuresPruned++ }
        }

        // 4. Prune graph edges that were never verified and are old.
        val edgeIter = site.edges.entries.iterator()
        while (edgeIter.hasNext()) {
            val (_, e) = edgeIter.next()
            if (e.verified == 0 && now - e.lastAt > 30L * 86_400_000L) { edgeIter.remove(); edgesPruned++ }
        }

        // 5. Budgets.
        if (site.bindings.size > 5000) {
            site.bindings.entries.sortedBy { it.value.stats.decayed(now).p }.take(site.bindings.size - 5000).forEach { site.bindings.remove(it.key) }
            notes += "binding budget enforced"
        }

        // 6. Calibration alarm: a Brier score above 0.25 means the verifier is being fooled on this host.
        val quarantined = site.brierCount >= 30 && site.brier > 0.25
        if (quarantined) {
            notes += "Brier ${"%.2f".format(site.brier)} > 0.25: quarantining bindings (shadowed) until fresh verifications"
            site.bindings.replaceAll { _, b -> b.copy(shadowed = true) }
            site.brierSum = 0.0; site.brierCount = 0
        }

        site.lastConsolidatedAt = now
        memory.saveSite(site)

        // 7. Dedupe compiled skills with identical bodies after abstraction.
        val skills = memory.skills.all().filter { it.origin != SkillOrigin.BUILTIN }
        val seen = HashMap<String, String>()
        var deduped = 0
        for (s in skills) {
            // Preserve host evidence, capability, expectations and submit/optional semantics.
            // Only byte-equivalent records apart from identity are redundant.
            val sig = s.copy(id = "").toJson().toString()
            val keep = seen[sig]
            if (keep == null) seen[sig] = s.id else { memory.skills.remove(s.id); deduped++ }
        }
        if (memory.skills.all().size > 300) notes += "skill budget exceeded"
        return ConsolidationReport(host, retired, shadowed, failuresPruned, edgesPruned, deduped, quarantined, notes)
    }

    fun runAll(now: Long = memory.now()): List<ConsolidationReport> = memory.knownHosts().map { run(it, now) }
}
