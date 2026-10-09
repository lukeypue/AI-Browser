package com.appgate.brain.engine

import com.appgate.brain.memory.Memory
import com.appgate.brain.model.Goal
import com.appgate.brain.model.ItemVerdict
import com.appgate.brain.model.TaskLedger
import com.appgate.brain.model.TaskResult
import com.appgate.brain.model.TaskStatus
import com.appgate.brain.profile.SiteProfile
import com.appgate.brain.profile.SiteProfiles
import com.appgate.brain.util.Hashing

/** Result of a deep search across several sites, merged and ranked. */
data class SearchOutcome(
    val goal: Goal,
    val perSite: Map<String, TaskLedger>,
    val merged: TaskResult
)

/**
 * Runs one goal over several sites, one after another, on a single renderer. A site that
 * needs a human pauses the whole search until resumed; the ledger for every site survives.
 */
class SearchCoordinator(private val engine: BrainEngine, private val memory: Memory, private val events: EngineEvents) {

    fun newLedgers(goal: Goal, sources: List<SiteProfile>): List<TaskLedger> = sources.map { p ->
        TaskLedger(id = Hashing.short(goal.id + p.key + System.nanoTime()), goal = goal, host = p.hosts.first(), startUrl = p.startUrl).also { memory.saveLedger(it) }
    }

    /** Runs every ledger that is not finished; returns as soon as one needs a human or a grant. */
    fun run(goal: Goal, ledgers: List<TaskLedger>, mode: EngineMode = EngineMode.ASSIST): SearchOutcome {
        for (ledger in ledgers) {
            if (ledger.done) continue
            events.status("Searching ${ledger.host}…")
            val out = engine.runTask(ledger, mode)
            if (out.status == TaskStatus.NEED_HUMAN || out.status == TaskStatus.NEED_GRANT || out.status == TaskStatus.PAUSED) break
        }
        return outcome(goal, ledgers)
    }

    fun outcome(goal: Goal, ledgers: List<TaskLedger>): SearchOutcome = SearchMerge.outcome(goal, ledgers)
}

/** Merges per-site ledgers into one ranked result; pure, so UIs can rebuild results after a restart. */
object SearchMerge {
    fun outcome(goal: Goal, ledgers: List<TaskLedger>): SearchOutcome {
        val all = LinkedHashMap<String, ItemVerdict>()
        val notes = mutableListOf<String>()
        var inspected = 0
        for (l in ledgers) {
            l.verdicts.values.forEach { v -> all[l.host + "|" + v.itemKey] = v }
            inspected += l.itemsInspected
            val status = when (l.status) {
                TaskStatus.DONE -> "done"
                TaskStatus.NEED_HUMAN -> "needs you (${l.humanReason.ifBlank { "sign-in or verification" }})"
                TaskStatus.FAILED -> "could not complete"
                TaskStatus.PENDING -> "not started"
                else -> l.status.name.lowercase()
            }
            notes += "${l.host}: $status, ${l.verdicts.size} listings seen, ${l.itemsInspected} inspected"
        }
        val merged = com.appgate.brain.goal.ConstraintEvaluator.summarize(goal, all.values, inspected, notes, if (ledgers.all { it.status == TaskStatus.DONE }) "DONE" else "PARTIAL")
        return SearchOutcome(goal, ledgers.associateBy { it.host }, merged)
    }
}

/**
 * Overnight learning: rotate through the training sites, each driven by its curriculum.
 * Rotation happens when the curriculum is complete, three goals in a row are blocked, or the
 * per-site chunk elapses — never on a plateau timer.
 */
class LearningSession(
    private val engine: BrainEngine,
    private val memory: Memory,
    private val events: EngineEvents,
    private val sites: List<SiteProfile> = SiteProfiles.training,
    private val perSiteChunkMs: Long = 20 * 60_000L,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    @Volatile var siteIndex: Int = 0
    @Volatile var stopRequested: Boolean = false
    @Volatile var pausedLedger: TaskLedger? = null
    @Volatile var isWaiting: Boolean = false
        private set
    @Volatile var activeSince: Long = 0L
        private set
    private val lessonRetryMs = 60_000L

    fun stop() { stopRequested = true; engine.requestStop() }

    /** Runs until stopped (or the caller's finite visit limit); blocked sites wait without ending the session. */
    fun run(maxSites: Int = Int.MAX_VALUE): TaskLedger? {
        stopRequested = false
        activeSince = clock()
        engine.clearStop()
        var visited = 0
        var skipped = 0
        if (sites.isEmpty()) return null
        // An explicit Start revalidates saved human-review requests once. The normal
        // landing check observes the current page before any action; actual auth walls,
        // challenges and unrelated redirects still stop. Idle retries never clear holds.
        sites.forEach { profile ->
            val site = memory.site(profile.hosts.first())
            if (site.learningNeedsHuman && site.lastLearningStatus.startsWith("NEED_HUMAN:")) {
                site.learningNeedsHuman = false
                site.lastLearningStatus = "Rechecking the current page after Start"
                memory.saveSite(site)
            }
        }
        while (!stopRequested && visited < maxSites) {
            val profile = sites[siteIndex % sites.size]
            val site = memory.site(profile.hosts.first())
            Curriculum.ensure(site)
            // 7.1.0 deferred all lessons for a day after only the basic skills verified.
            // Keep normal failure cooldowns and every human-review hold intact.
            if (Curriculum.isComplete(site) && !Curriculum.allLessonsComplete(site) &&
                site.learningBlockedUntil > clock() + 30 * 60_000L) {
                site.learningBlockedUntil = Curriculum.earliestRetryAt(site, clock())
                memory.saveSite(site)
            }
            if (site.challengeDay != clock() / 86_400_000L) { site.challengeDay = clock() / 86_400_000L; site.challengesToday = 0 }
            val now = clock()
            // Migrate old generic cooldowns, but never shorten a pending lesson's retry.
            if (!site.learningNeedsHuman && site.challengesToday < 3 && !Curriculum.allLessonsComplete(site) &&
                site.learningBlockedUntil > now + lessonRetryMs && site.learningBlockedUntil <= now + 30 * 60_000L) {
                site.learningBlockedUntil = maxOf(now + lessonRetryMs, Curriculum.earliestRetryAt(site, now))
                memory.saveSite(site)
            }
            if (site.challengesToday >= 3 || site.learningNeedsHuman || site.learningBlockedUntil > clock() || !Curriculum.reviewDue(site, clock())) {
                events.status("${profile.name}: ${if (site.learningNeedsHuman) "waiting for sign-in or review" else if (Curriculum.allLessonsComplete(site)) "all lessons verified; review scheduled" else "cooling down"}")
                memory.saveSite(site); siteIndex++; visited++; skipped++
                if (skipped >= sites.size && visited < maxSites) {
                    waitForNextSite()
                    skipped = 0
                }
                continue
            }
            skipped = 0
            val startedAt = clock()
            var goalsThisVisit = 0
            var blockedInARow = 0
            events.status("Learning ${profile.name} — ${Curriculum.progress(site)}")
            while (!stopRequested && clock() - startedAt < perSiteChunkMs && goalsThisVisit < 4) {
                // A minute recheck is an observation, not a new generic finding task.
                val page = engine.probeLearning(profile)
                if (page != null) Curriculum.observe(site, page, clock())
                val lesson = if (page == null || site.learningNeedsHuman) "" else Curriculum.nextLesson(site, clock(), page)
                if (lesson.isBlank()) {
                    site.learningBlockedUntil = maxOf(clock() + lessonRetryMs, Curriculum.earliestRetryAt(site, clock()))
                    if (!site.learningNeedsHuman) site.lastLearningStatus = "Waiting for an observed lesson opportunity"
                    events.diagnostic(site.host, "learning_recheck", SemanticDiagnostics.learningRecheck(site, page, clock()))
                    memory.saveSite(site)
                    break
                }
                val goal = Curriculum.nextGoal(site, profile, site.lessonOrdinal++, page, lesson)
                goalsThisVisit++
                val ledger = TaskLedger(Hashing.short("learn" + System.nanoTime()), goal, profile.hosts.first(), page!!.url)
                ledger.lesson = lesson
                // Keep the observed drawer/results state instead of reloading its URL.
                ledger.lastCheckpointUrl = page.url
                if (page.pageType == com.appgate.brain.model.PageType.RESULTS) ledger.resultsUrl = page.url
                val masteredBefore = site.curriculum.filter { it.done }.map { it.id }.toSet()
                val out = engine.runTask(ledger, EngineMode.TRAIN)
                Curriculum.recordAttempt(site, out, clock())
                site.lastLearningStatus = "${out.status}: ${out.terminalReason.ifBlank { out.humanReason }}"
                memory.saveSite(site)
                if (out.done) memory.deleteLedger(out.id)
                when (out.status) {
                    TaskStatus.NEED_HUMAN, TaskStatus.NEED_GRANT -> {
                        pausedLedger = out
                        site.learningNeedsHuman = true
                        memory.saveSite(site)
                        events.status("${profile.name} needs you; continuing to another site")
                        break
                    }
                    TaskStatus.PAUSED -> return null
                    else -> if (out.successfulSkills.none { it !in masteredBefore }) blockedInARow++ else blockedInARow = 0
                }
                if (blockedInARow >= 3) { site.learningBlockedUntil = clock() + lessonRetryMs; memory.saveSite(site); events.log("info", "${profile.name}: three goals without a new verified skill; retry in 1 min"); break }
                if (Curriculum.allLessonsComplete(site)) { site.learningBlockedUntil = clock() + 24 * 60 * 60_000L; memory.saveSite(site); break }
            }
            siteIndex++
            visited++
            // A bounded visit always yields to other sites before another four goals.
        }
        return null
    }

    private fun waitForNextSite() {
        isWaiting = true
        var lastStatus = ""
        try {
            while (!stopRequested) {
                val now = clock()
                val next = sites.mapNotNull { profile ->
                    val site = memory.site(profile.hosts.first())
                    if (site.learningNeedsHuman) null else maxOf(site.learningBlockedUntil,
                        Curriculum.nextReviewAt(site),
                        if (site.challengesToday >= 3) (site.challengeDay + 1) * 86_400_000L else 0L)
                }.minOrNull()
                if (next != null && next <= now) return
                val reviewNote = if (sites.any { memory.site(it.hosts.first()).learningNeedsHuman })
                    " Some sites need your review; use their Review buttons." else ""
                val status = if (next == null) "Learning is waiting for you. Open a site's Review button, complete sign-in or verification, then tap Done."
                    else "Learning is still active; next automatic retry in ${((next - now + 59_999L) / 60_000L).coerceAtLeast(1)} min.$reviewNote"
                if (status != lastStatus) { events.status(status); lastStatus = status }
                Thread.sleep(if (next == null) 1_000L else (next - now).coerceIn(1L, 1_000L))
            }
        } finally {
            // Waiting is not verified progress, but must not consume the active-work watchdog.
            activeSince = clock()
            isWaiting = false
        }
    }
}
