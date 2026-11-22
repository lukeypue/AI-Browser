package com.appgate.brain.engine

import com.appgate.brain.goal.ConstraintEvaluator
import com.appgate.brain.json.JsonObject
import com.appgate.brain.memory.Episode
import com.appgate.brain.memory.Memory
import com.appgate.brain.memory.FailedStrategies
import com.appgate.brain.model.Action
import com.appgate.brain.model.ActionKind
import com.appgate.brain.model.Binding
import com.appgate.brain.model.ConstraintSource
import com.appgate.brain.model.Goal
import com.appgate.brain.model.GoalIntent
import com.appgate.brain.model.Grant
import com.appgate.brain.model.LedgerStep
import com.appgate.brain.model.PageType
import com.appgate.brain.model.Postcondition
import com.appgate.brain.model.Role
import com.appgate.brain.model.ScrollContext
import com.appgate.brain.model.SemanticPageState
import com.appgate.brain.model.SiteModel
import com.appgate.brain.model.Skill
import com.appgate.brain.model.Step
import com.appgate.brain.model.StepKind
import com.appgate.brain.model.ProposalRejectionReason
import com.appgate.brain.model.TeacherProposalRejection
import com.appgate.brain.model.TaskLedger
import com.appgate.brain.model.TaskPhase
import com.appgate.brain.model.TaskResult
import com.appgate.brain.model.TaskStatus
import com.appgate.brain.model.Verdict
import com.appgate.brain.model.VerifyStatus
import com.appgate.brain.perception.SpsParser
import com.appgate.brain.perception.UrlPatterns
import com.appgate.brain.planner.Planner
import com.appgate.brain.planner.PlannerRefused
import com.appgate.brain.profile.SiteProfile
import com.appgate.brain.profile.SiteProfiles
import com.appgate.brain.skills.SkillCompiler
import com.appgate.brain.util.Hashing
import com.appgate.brain.verify.Verifier
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private object DiagnosticTaskIds {
    private val next=AtomicInteger()
    fun allocate():Int=next.updateAndGet{if(it<1_000_000)it+1 else 1_000_001}.takeIf{it<=1_000_000}?:0
}

interface EngineEvents {
    fun diagnostic(host: String, kind: String, data: JsonObject) {}
    fun progress(ledger: TaskLedger, reason: String) {}
    fun status(text: String) {}
    fun guide(explanation: LearningGuide) {}
    fun step(ledger: TaskLedger, description: String, status: VerifyStatus?) {}
    fun needHuman(ledger: TaskLedger, reason: String, url: String) {}
    fun needGrant(ledger: TaskLedger, previewText: String, previewHash: String) {}
    fun finished(ledger: TaskLedger, result: TaskResult?) {}
    fun log(level: String, message: String) {}
}

data class EngineConfig(
    val maxTimeouts: Int = 3,
    val plannerCooldownMs: Long = 15_000L,
    val maxPlannerCallsSameState: Int = 2,
    val maxConsecutiveFailures: Int = 6,
    val maxDecisionsWithoutProgress: Int = 24,
    val plannerConfidenceFloor: Double = 0.35,
    val ambiguousRecheckMs: Long = 2_500L,
    val idleSleepMs: Long = 200L,
    val pacingOverrideMs: Long? = null          // tests: 0; production: null (profile pacing)
)

/**
 * The planner/executor/verifier loop over Semantic Page State.
 *
 * One thread runs one task at a time; every renderer call has a deadline; every step is
 * checkpointed to the ledger before it runs and after it is verified. The verifier is the
 * only writer of success/failure into memory; the planner is consulted only on a miss.
 */
class BrainEngine(
    private val renderer: Renderer,
    private val memory: Memory,
    private val plannerFactory: () -> Planner?,
    private val events: EngineEvents,
    private val config: EngineConfig = EngineConfig(),
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private val stopRequested = AtomicBoolean(false)
    @Volatile var currentLedger: TaskLedger? = null
        private set
    private var lastPlannerCallAt = 0L
    private var latestPage: SemanticPageState? = null
    private var programBefore: SemanticPageState? = null
    // Atomic predicate satisfaction is separate from causal evidence for mastery.
    private val programSatisfiedSteps = mutableSetOf<Int>()
    private var lessonsBefore: Set<String> = emptySet()
    private val learningBusySince = mutableMapOf<String, Long>()
    private val emptyLandingRecoveryAt = mutableMapOf<String, Long>()

    fun requestStop() { stopRequested.set(true) }
    fun clearStop() { stopRequested.set(false) }

    /** Issue a grant for the ledger's current preview; the next tick may commit. */
    fun grant(ledger: TaskLedger): Grant {
        val g = Grant(Hashing.short("grant" + clock() + ledger.id), ledger.id, ledger.host, Role.SEND, ledger.previewHash, clock())
        ledger.grants += g
        ledger.status = TaskStatus.RUNNING
        memory.saveLedger(ledger)
        return g
    }

    // ------------------------------------------------------------------ public entry points

    /** One bounded, model-free opportunity check. No task or success credit is created. */
    fun probeLearning(profile: SiteProfile, beginCompletedPracticeVisit: Boolean = false): SemanticPageState? {
        if (stopRequested.get()) return null
        val host = profile.hosts.first()
        val site = memory.site(host)
        val executor = Executor(renderer, { SpsParser(site.facetVocabulary + profile.facetVocabulary) })
        renderer.setNetworkMode(EngineMode.TRAIN.name,
            site.endpointEffects.filterValues { it == com.appgate.brain.model.EffectClass.READ }.keys.toList(),
            site.endpointEffects.filterValues { it == com.appgate.brain.model.EffectClass.COMMIT_EXTERNAL }.keys.toList())
        val queries = profile.trainingQueries.ifEmpty { listOf("mountain bike") }
        val query = queries[Math.floorMod(clock() / 60_000L, queries.size.toLong()).toInt()]
        val goal = Goal("probe", GoalIntent.LEARN_SITE, "learning opportunity check", query, emptyList())
        val knownSearch = profile.searchUrlFor(goal) ?: site.searchUrlTemplate?.let {
            profile.copy(searchUrl = it).searchUrlFor(goal)
        }
        val probe = TaskLedger("probe", goal, host, profile.startUrl)
        return runCatching {
            val current = renderer.currentUrl()
            var page = if (allowedUrl(probe, current, profile)) executor.observe(clock()) else null
            // Background requests can keep an otherwise stranded page BUSY forever.
            // Measure across site visits, then try one read-only reset at the next probe.
            val busyExpired = if (page?.settle == com.appgate.brain.model.Settle.BUSY && page?.isHumanOnly != true) {
                clock() - learningBusySince.getOrPut(host) { clock() } >= 60_000L
            } else { if (page != null) learningBusySince.remove(host); false }
            val failedApproach = site.lastLearningStatus.startsWith("FAILED:") || site.lastLearningStatus.startsWith("PARTIAL:")
            val resetFailedPage = failedApproach && (site.lastLearningStatus.contains("recently failed approaches") ||
                site.lastLearningStatus.contains("no verified procedure available")) &&
                page?.settle == com.appgate.brain.model.Settle.IDLE
            val recoveryDue = !site.learningNeedsHuman && site.challengesToday < 3 &&
                site.learningBlockedUntil <= clock() && page?.isHumanOnly != true &&
                page?.pageType !in setOf(PageType.MESSAGES, PageType.PROFILE) && (resetFailedPage || busyExpired)
            // A results page or non-dialog filter panel with no unfinished lesson targets cannot improve by
            // observation alone. Try one different safe practice search; no AI call,
            // lesson completion or cooldown reset is credited to this navigation.
            Curriculum.ensure(site)
            val strandedPage = page?.let { p ->
                p.pageType in setOf(PageType.RESULTS, PageType.FACET_PANEL) && p.settle == com.appgate.brain.model.Settle.IDLE && !p.dialogOpen &&
                    site.curriculum.any { !it.done && it.retryAt <= clock() } &&
                    site.curriculum.filter { !it.done && it.retryAt <= clock() }.none { LearningOpportunities.target(p, it.id) != null } &&
                    knownSearch != null && (knownSearch != current || p.pageType == PageType.FACET_PANEL)
            } == true
            // A sparse landing at the expected URL is still stranded. Observe it safely,
            // then allow one read-only reload per retry interval, never on auth/dialogs.
            val emptyLanding = page?.let { p ->
                p.pageType in setOf(PageType.HOME, PageType.SEARCH, PageType.UNKNOWN) &&
                    p.settle == com.appgate.brain.model.Settle.IDLE && !p.dialogOpen && !p.isHumanOnly &&
                    p.resultKeys.isEmpty() && site.curriculum.none { LearningOpportunities.target(p, it.id) != null }
            } == true
            val emptyLandingDue = emptyLanding && !site.learningNeedsHuman && site.challengesToday < 3 &&
                site.learningBlockedUntil <= clock() && knownSearch != null && knownSearch == current &&
                (emptyLandingRecoveryAt[host]?.let { clock() - it >= Curriculum.RETRY_MS } ?: true)
            // Synthetic completed practice starts a new bounded visit from this
            // profile's known search, rather than relabelling the previous layout.
            // Navigation earns no learning and never clears a failure or human hold.
            val completedVisitReset = beginCompletedPracticeVisit && Curriculum.allLessonsComplete(site) &&
                !site.learningNeedsHuman && site.challengesToday < 3 && site.learningBlockedUntil <= clock() &&
                knownSearch != null && page?.let { p -> !p.isHumanOnly && !p.dialogOpen && p.settle == com.appgate.brain.model.Settle.IDLE &&
                    p.pageType in setOf(PageType.HOME, PageType.SEARCH, PageType.RESULTS, PageType.FACET_PANEL, PageType.DETAIL) } == true
            // Description practice can start on this detail page. Back practice needs
            // a results checkpoint in the new lesson ledger, so first reestablish results.
            val usableDetail = page?.let { p ->
                if (p.pageType != PageType.DETAIL) false else {
                    Curriculum.observe(site, p, clock())
                    Curriculum.nextLesson(site, clock(), p) == "expand_description" &&
                        LearningOpportunities.target(p, "expand_description")?.skillId == "expand_description"
                }
            } == true
            // Always observe an already open auth/challenge before navigating elsewhere.
            if (page?.isHumanOnly != true && (page == null || recoveryDue || emptyLandingDue || completedVisitReset || (page.pageType == PageType.ERROR && page.settle == com.appgate.brain.model.Settle.IDLE && !page.dialogOpen) || strandedPage || (page.pageType == PageType.DETAIL && !usableDetail) ||
                    (page.pageType in setOf(PageType.HOME, PageType.SEARCH, PageType.UNKNOWN) && !page.dialogOpen &&
                        page.settle == com.appgate.brain.model.Settle.IDLE && knownSearch != null && knownSearch != current))) {
                val target = knownSearch?.takeIf { allowedUrl(probe, it, profile) }
                    ?: profile.startUrl.takeIf { allowedUrl(probe, it, profile) } ?: return@runCatching null
                if (emptyLandingDue) emptyLandingRecoveryAt[host] = clock()
                val outcome = executor.execute(Action(ActionKind.NAVIGATE, url = target,
                    effect = com.appgate.brain.model.EffectClass.NAVIGATE), emptySps(host), EngineMode.TRAIN, emptyList(), null, clock())
                page = (outcome as? ExecOutcome.Done)?.after ?: return@runCatching null
                if (recoveryDue || emptyLandingDue || completedVisitReset) {
                    learningBusySince.remove(host)
                    site.lastLearningStatus = "Rechecked safe search after a stalled page"
                    events.diagnostic(host, "learning_recovery", JsonObject()
                        .put("reason", if (completedVisitReset) "completed_practice_visit" else if (emptyLandingDue) "empty_landing" else if (busyExpired) "busy_timeout" else "failed_approach")
                        .put("page_type", page!!.pageType.name))
                }
            }
            val observed = page ?: return@runCatching null
            if (!allowedUrl(probe, observed.url, profile) || UrlPatterns.host(observed.url).removePrefix("www.") != observed.host.removePrefix("www.")) {
                if (observed.isHumanOnly) {
                    site.learningNeedsHuman = true
                    site.lastLearningStatus = "NEED_HUMAN: Sign-in or verification required"
                } else site.lastLearningStatus = "Waiting: site navigation did not reach this source"
                memory.saveSite(site)
                return@runCatching null
            }
            if (observed.isHumanOnly) {
                if (observed.challenge) site.recordChallenge(clock())
                site.learningNeedsHuman = true
                site.lastLearningStatus = "NEED_HUMAN: " + if (observed.challenge) "Human verification required" else "Sign-in required"
                memory.saveSite(site)
                return@runCatching null
            }
            if (observed.settle == com.appgate.brain.model.Settle.BUSY) learningBusySince.getOrPut(host) { clock() }
            else learningBusySince.remove(host)
            Curriculum.observe(site, observed, clock())
            memory.saveSite(site)
            observed
        }.getOrElse {
            events.log("warn", "learning observation unavailable: ${it.javaClass.simpleName}")
            null
        }
    }

    /** Check the live prerequisite against the executor's failure holds before allocating a lesson.
     * This does not execute, reserve teacher budget, persist a ledger, or award credit. */
    fun learningLessonAvailable(profile: SiteProfile, page: SemanticPageState, lesson: String, allowTeacher: Boolean = true): Boolean {
        val site = memory.site(profile.hosts.first())
        val goal = Curriculum.nextGoal(site, profile, site.lessonOrdinal, page, lesson)
        val ledger = TaskLedger("eligibility", goal, site.host, page.url).apply {
            this.lesson = lesson
            if (page.pageType == PageType.RESULTS) resultsUrl = page.url
        }
        val target = learningTarget(ledger, page, site) ?: return false
        val params = skillParams(ledger) + target.params
        val learned = memory.skills.reusable(page, target.skillId, params, ledger)
        if (listOfNotNull(learned, memory.skills.get(target.skillId)).any {
                FailedStrategies.allowed(site, FailedStrategies.key(page, target.skillId, it.body, params), clock()) &&
                    !rejectedPrefixHeld(it.body, params, page, site, ledger,
                        it.origin != com.appgate.brain.model.SkillOrigin.BUILTIN && ("verified_v2" in it.tags || "demonstrated_candidate_v2" in it.tags))
            }) return true
        val local = LocalRecoveryPlanner.propose(ledger, page, site, target.skillId)
        if (local != null) {
            val capability = if (local.completesCapability) target.skillId else "open_filters"
            if (FailedStrategies.allowed(site, FailedStrategies.key(page, capability, local.steps, params + local.params), clock()) &&
                !rejectedPrefixHeld(local.steps, params + local.params, page, site, ledger, true)) return true
        }
        return allowTeacher && FailedStrategies.repairAllowed(site, FailedStrategies.key(page, target.skillId), clock()) && plannerFactory() != null
    }

    /** Resolve from the live observation for both scheduling and execution.
     * Prefer another runnable item to repairing an unchanged held item. If all
     * candidates are held, preserve the existing bounded repair path and holds. */
    private fun learningTarget(ledger: TaskLedger, page: SemanticPageState, site: SiteModel): LearningTarget? {
        val candidates = LearningOpportunities.targets(page, ledger.lesson)
        if (candidates.size <= 1) return candidates.firstOrNull()
        return candidates.firstOrNull { target ->
            val params = skillParams(ledger) + target.params
            val learned = memory.skills.reusable(page, target.skillId, params, ledger)
            listOfNotNull(learned, memory.skills.get(target.skillId)).any { skill ->
                FailedStrategies.allowed(site, FailedStrategies.key(page, target.skillId, skill.body, params), clock()) &&
                    !rejectedPrefixHeld(skill.body, params, page, site, ledger,
                        skill.origin != com.appgate.brain.model.SkillOrigin.BUILTIN && ("verified_v2" in skill.tags || "demonstrated_candidate_v2" in skill.tags))
            }
        } ?: candidates.firstOrNull()
    }

    /** Inspect only the first actually runnable step. A distinct prerequisite may
     * reveal a changed control, while an unchanged held SELECT cannot monopolize visits. */
    private fun rejectedPrefixHeld(steps: List<Step>, params: Map<String, String>, page: SemanticPageState,
                                   site: SiteModel, ledger: TaskLedger, strict: Boolean): Boolean {
        val grounder = StepGrounder(site)
        for (step in steps) when (val grounded = grounder.ground(step, params, page, ledger.visited, strict = strict)) {
            is GroundingOutcome.Skip -> continue
            is GroundingOutcome.Missing -> return false
            is GroundingOutcome.Ready -> {
                val key = FailedStrategies.rejectedFacetSelectKey(page, grounded.grounded.action) ?: return false
                return !FailedStrategies.allowed(site, key, clock())
            }
        }
        return false
    }

    fun runTask(ledger: TaskLedger, mode: EngineMode = EngineMode.ASSIST): TaskLedger {
        if (ledger.done) return ledger
        stopRequested.set(false)
        currentLedger = ledger
        ledger.diagnosticTaskSequence=DiagnosticTaskIds.allocate()
        ledger.diagnosticProgramSequence=if(ledger.currentProgram.isEmpty())0 else 1
        ledger.diagnosticEnded=false
        ledger.diagnosticOutcomeReported=false
        latestPage = null
        programBefore = null
        // A partial program cannot carry verification credit across a process/task resume.
        ledger.programVerifiedSteps.clear()
        programSatisfiedSteps.clear()
        ledger.programCompleted = false
        ledger.cursor = 0
        val runStarted = System.nanoTime()
        val elapsedBefore = ledger.elapsedMs
        val remainingWallMs = { ledger.goal.budget.wallMs - elapsedBefore - (System.nanoTime() - runStarted) / 1_000_000L }
        val profile = SiteProfiles.forHost(ledger.host) ?: SiteProfiles.generic(ledger.host)
        val site = memory.site(ledger.host)
        lessonsBefore = site.curriculum.filter { it.done }.map { it.id }.toSet()
        ledger.diagnosticRehearsal=ledger.lesson in lessonsBefore
        if (memoryDiagnosticSites.add(ledger.host)) memoryCheckpoint(ledger, "MEMORY_SNAPSHOT")
        val parserFactory = { SpsParser(site.facetVocabulary + profile.facetVocabulary) }
        val executor = Executor(renderer, parserFactory)
        val policy = TaskPolicy(profile, site)
        val effectiveMode = if (ledger.goal.intent == GoalIntent.LEARN_SITE) EngineMode.TRAIN else mode
        renderer.setNetworkMode(effectiveMode.name, site.endpointEffects.filterValues { it == com.appgate.brain.model.EffectClass.READ }.keys.toList(), site.endpointEffects.filterValues { it == com.appgate.brain.model.EffectClass.COMMIT_EXTERNAL }.keys.toList())

        if (ledger.status == TaskStatus.PENDING) { ledger.startedAt = clock(); }
        ledger.status = TaskStatus.RUNNING
        ledger.humanReason = ""
        memory.saveLedger(ledger)
        if (ledger.goal.intent == GoalIntent.LEARN_SITE) events.guide(LearningGuide.snapshot(ledger))
        events.status("Starting on ${profile.name}: ${ledger.goal.describe()}")
        events.diagnostic(ledger.host,"task_lifecycle",SemanticDiagnostics.journey(ledger).put("status","RUNNING").put("lesson",SemanticDiagnostics.capability(ledger.lesson)))

        try {
            // Land on the site (or on the last checkpoint after a resume).
            val current = runCatching { renderer.currentUrl() }.getOrDefault("")
            val currentHost = UrlPatterns.host(current)
            val fresh = ledger.actions == 0 && ledger.lastCheckpointUrl.isBlank()
            val target = when {
                fresh -> ledger.startUrl                                   // a new task always starts from a known page
                ledger.lastCheckpointUrl.isNotBlank() && !UrlPatterns.sameSite(current, ledger.lastCheckpointUrl) -> ledger.lastCheckpointUrl
                currentHost.isBlank() || !UrlPatterns.sameSite("https://$currentHost/", ledger.startUrl) -> ledger.startUrl
                else -> null
            }
            if (target != null) {
                if (!allowedUrl(ledger, target, profile)) {
                    ledger.status = TaskStatus.FAILED
                    ledger.terminalReason = "start page is outside this source"
                    return finish(ledger, site)
                }
                val outcome = executor.execute(Action(ActionKind.NAVIGATE, url = target, expect = emptyList(), effect = com.appgate.brain.model.EffectClass.NAVIGATE), emptySps(ledger.host), effectiveMode, ledger.grants, null, clock())
                if (outcome is ExecOutcome.Failed) { handleRendererFailure(ledger, outcome.reason, outcome.timeout); if (ledger.done) return finish(ledger, site) }
            }

            var consecutiveNoAction = 0
            var landing = target != null
            var previousPageObservations = 0
            while (!ledger.done && !ledger.blocked) {
                if (stopRequested.get()) { ledger.status = TaskStatus.PAUSED; ledger.note("paused by user"); memory.saveLedger(ledger); events.status("Paused"); return ledger }
                ledger.elapsedMs = elapsedBefore + (System.nanoTime() - runStarted) / 1_000_000L
                ProgressSupervisor.terminal(ledger, config, ledger.elapsedMs)?.let { reason ->
                    ledger.terminalReason = reason
                    ledger.status = if (reason.contains("budget")) TaskStatus.BUDGET_EXHAUSTED else if (ledger.verdicts.isNotEmpty()) TaskStatus.PARTIAL else TaskStatus.FAILED
                    ledger.note(reason)
                }
                if (ledger.done) break
                ledger.decisions++
                ledger.decisionsWithoutProgress++

                val sps = observeOrRecover(executor, ledger) ?: break
                // A queued navigation can briefly expose the previous site's document.
                // Wait only for that exact prior URL; other redirects still require review.
                if (landing && !allowedUrl(ledger, sps.url, profile) && currentHost.isNotBlank() &&
                    current == sps.url) {
                    if (++previousPageObservations >= 8) {
                        ledger.status = TaskStatus.FAILED
                        ledger.terminalReason = "site transition timeout"
                        break
                    }
                    Thread.sleep(config.idleSleepMs)
                    continue
                }
                if (!acceptPage(ledger, sps, profile)) break
                landing = false
                latestPage = sps
                site.recordPage(sps.pageType, clock())
                trackSiteVersion(site, sps)
                if (!sps.isHumanOnly && ledger.goal.intent == GoalIntent.LEARN_SITE) Curriculum.observe(site, sps, clock())
                collectCards(ledger, sps)
                ledger.lastSpsHash = sps.hash
                if (sps.pageType != PageType.ERROR && !sps.isHumanOnly) ledger.lastCheckpointUrl = sps.url

                if (sps.isHumanOnly) {
                    diagnostic(ledger, sps, null, null, VerifyStatus.HUMAN_NEEDED, if (sps.challenge) DiagnosticCode.CHALLENGE else DiagnosticCode.AUTH_WALL)
                    if (sps.challenge) site.recordChallenge(clock())
                    memory.saveSite(site)
                    val reason = if (sps.challenge) "This site is asking for a human verification step." else "This site needs you to sign in."
                    ledger.status = TaskStatus.NEED_HUMAN
                    ledger.humanReason = reason
                    memory.saveLedger(ledger)
                    events.needHuman(ledger, reason, sps.url)
                    return ledger
                }

                if (ledger.currentProgram.isEmpty() && ledger.repairReason.isNotBlank()) {
                    val repairReason = ledger.repairReason
                    val capability = ledger.programCapability
                    ledger.repairReason = "" // One bounded opportunity per failed procedure; quotas still apply.
                    // A finished learning program no longer owns its bound steps. Resolve
                    // a live target again instead of sending an unbound repair request.
                    val repairBindings = if (ledger.goal.intent == GoalIntent.LEARN_SITE && capability == "open_item")
                        learningTarget(ledger, sps, site)?.takeIf { it.skillId == capability }?.params.orEmpty()
                        else emptyMap()
                    if (askPlanner(ledger, sps, site, profile, repairReason, capability, repairBindings)) continue
                }

                // Continue an in-flight program before asking the policy again.
                if (ledger.currentProgram.isNotEmpty() && ledger.cursor < ledger.currentProgram.size) {
                    if (dismissBlockingPrerequisite(ledger, sps, site, profile, executor, effectiveMode)) continue
                    runProgramStep(ledger, sps, site, profile, executor, effectiveMode, remainingWallMs)
                    consecutiveNoAction = 0
                    continue
                }
                if (ledger.currentProgram.isNotEmpty()) finishProgram(ledger, sps, site, success = true)
                if (ledger.repairReason.isNotBlank()) continue

                when (val decision = learningDecision(ledger, sps) ?: policy.decide(ledger.goal, sps, ledger)) {
                    is PolicyDecision.RunSkill -> {
                        val learned = memory.skills.reusable(sps, decision.skillId, skillParams(ledger) + decision.params, ledger)
                        val params = skillParams(ledger) + decision.params
                        val skill = listOfNotNull(learned, memory.skills.get(decision.skillId)).firstOrNull {
                            FailedStrategies.allowed(site, FailedStrategies.key(sps, decision.skillId, it.body, params), clock())
                        }
                        if (skill == null) {
                            val failures = listOfNotNull(learned, memory.skills.get(decision.skillId)).maxOfOrNull {
                                site.strategyFailures[FailedStrategies.key(sps, decision.skillId, it.body, params)]?.count ?: 0
                            } ?: 0
                            if (listOfNotNull(learned, memory.skills.get(decision.skillId)).isNotEmpty())
                                memoryCheckpoint(ledger, "STRATEGY_HELD", decision.skillId, sps,
                                    JsonObject().put("strategy_prior_failures", failures))
                            ledger.attemptedSkills += decision.skillId
                            if (!askPlanner(ledger, sps, site, profile, "recently failed procedure; try another approach", decision.skillId, params)) {
                                ledger.status = if (ledger.verdicts.isEmpty()) TaskStatus.FAILED else TaskStatus.PARTIAL
                                ledger.terminalReason = "known failed procedure; use a different approach or changed controls"
                            }
                            continue
                        }
                        startProgram(ledger, skill.body, skillParams(ledger) + decision.params, "skill:${skill.id}", decision.reason, decision.skillId, skill.post)
                        consecutiveNoAction = 0
                    }
                    is PolicyDecision.Navigate -> {
                        startProgram(ledger, listOf(Step(com.appgate.brain.model.StepKind.NAVIGATE, arg = decision.url, expect = listOf(Postcondition.UrlChanged))), emptyMap(), "navigate", decision.reason)
                        consecutiveNoAction = 0
                    }
                    is PolicyDecision.AskPlanner -> {
                        val capability = capabilityFor(ledger)
                        val learned = memory.skills.reusable(sps, capability, skillParams(ledger), ledger)
                        if (learned != null) {
                            startProgram(ledger, learned.body, skillParams(ledger), "skill:${learned.id}", "reuse verified $capability", capability, learned.post)
                        } else if (!askPlanner(ledger, sps, site, profile, decision.reason)) {
                            if (!exploratoryFallback(ledger, sps)) {
                                ledger.status = if (ledger.verdicts.isNotEmpty()) TaskStatus.PARTIAL else TaskStatus.FAILED
                                ledger.terminalReason = "no verified procedure available"
                                ledger.note("stuck: ${decision.reason}")
                            }
                        }
                    }
                    is PolicyDecision.Finish -> { ledger.status = decision.status; ledger.terminalReason = decision.reason; ledger.note(decision.reason) }
                    is PolicyDecision.NeedHuman -> {
                        ledger.status = TaskStatus.NEED_HUMAN; ledger.humanReason = decision.reason
                        memory.saveLedger(ledger); events.needHuman(ledger, decision.reason, sps.url); return ledger
                    }
                    is PolicyDecision.Grant -> {
                        ledger.previewHash = Hashing.sha256Hex(ledger.host + "|" + ledger.previewText)
                        ledger.status = TaskStatus.NEED_GRANT
                        memory.saveLedger(ledger); events.needGrant(ledger, ledger.previewText, ledger.previewHash); return ledger
                    }
                    is PolicyDecision.EvaluateDetail -> {
                        evaluateDetail(ledger, sps, decision.itemKey)
                        consecutiveNoAction++
                        if (consecutiveNoAction > 4) { ledger.currentItem = null }
                    }
                }
                if (ledger.consecutiveFailures >= config.maxConsecutiveFailures) {
                    ledger.status = if (ledger.verdicts.isNotEmpty()) TaskStatus.PARTIAL else TaskStatus.FAILED
                    ledger.terminalReason = "too many consecutive failures"
                    ledger.note("too many consecutive failures")
                }
                memory.saveLedger(ledger)
            }
        } catch (t: InterruptedException) {
            ledger.status = TaskStatus.PAUSED
            memory.saveLedger(ledger)
            return ledger
        } catch (t: Exception) {
            ledger.status = TaskStatus.FAILED
            ledger.note("engine error: ${t.javaClass.simpleName}: ${t.message?.take(160)}")
            events.log("error", "engine error: $t")
        } finally {
            ledger.elapsedMs = elapsedBefore + (System.nanoTime() - runStarted) / 1_000_000L
            memory.saveLedger(ledger)
            if(ledger.blocked) { diagnosticLearningOutcome(ledger,site);diagnosticTaskEnd(ledger) }
        }
        return finish(ledger, site)
    }

    private fun finish(ledger: TaskLedger, site: SiteModel): TaskLedger {
        val settledResults = latestPage?.let { it.pageType == PageType.RESULTS && !it.dialogOpen && !it.isHumanOnly && it.settle == com.appgate.brain.model.Settle.IDLE } == true
        if (ledger.neutralScrollOnly == true && (!settledResults || "scroll_results" in ledger.successfulSkills ||
            ledger.timeouts > 0 || ledger.plannerFailures > 0 || ledger.llmCalls > 0 || ledger.repairReason.isNotBlank() ||
            ledger.steps.any { it.status != null && it.status != VerifyStatus.VERIFIED })) ledger.neutralScrollOnly = false
        if (ledger.blocked) { memory.saveLedger(ledger); memory.saveSite(site); return ledger }
        if (!ledger.done) ledger.status = if (ledger.verdicts.isNotEmpty()) TaskStatus.PARTIAL else TaskStatus.FAILED
        memory.saveLedger(ledger)
        memory.saveSite(site)
        val result = if (ledger.goal.intent == GoalIntent.FIND_LISTINGS || ledger.goal.intent == GoalIntent.LEARN_SITE) resultFor(ledger) else null
        diagnosticLearningOutcome(ledger,site)
        diagnosticTaskEnd(ledger)
        events.finished(ledger, result)
        currentLedger = null
        return ledger
    }

    fun resultFor(ledger: TaskLedger): TaskResult =
        ConstraintEvaluator.summarize(ledger.goal, ledger.verdicts.values, ledger.itemsInspected, ledger.notes, ledger.status.name)

    // ------------------------------------------------------------------ programs

    private fun startProgram(ledger: TaskLedger, steps: List<Step>, params: Map<String, String>, source: String, reason: String,
                             capability: String = source.removePrefix("skill:"), post: List<Postcondition> = emptyList()) {
        ledger.diagnosticProgramSequence=(ledger.diagnosticProgramSequence+1).coerceAtMost(1_000_000)
        // Bind parameters now so the persisted program is self-contained.
        ledger.currentProgram = steps.map { BoundProgram.step(it,params) }
        val page = latestPage
        val strategyKey = page?.let { FailedStrategies.key(it, capability, ledger.currentProgram, skillParams(ledger)) }
        val held = strategyKey != null && !FailedStrategies.allowed(memory.site(ledger.host), strategyKey, clock())
        val priorFailures = strategyKey?.let { memory.site(ledger.host).strategyFailures[it]?.count } ?: 0
        // Successful admissions already have an outcome checkpoint; avoid duplicate rows.
        if (held) {
            FailedStrategies.explainHeld(memory.site(ledger.host), strategyKey!!,
                page?.let { FailedStrategies.approach(it, capability, ledger.currentProgram, skillParams(ledger)) })
            memory.saveSite(memory.site(ledger.host))
            memoryCheckpoint(ledger, "STRATEGY_HELD", capability, page,
                JsonObject().put("strategy_prior_failures", priorFailures).put("program_source",SemanticDiagnostics.programSource(source)).also{data->strategyComparison(ledger,capability,strategyKey).let{j->j.keys.forEach{data.put(it,j[it])}}})
            ledger.currentProgram = emptyList()
            ledger.status = if (ledger.verdicts.isEmpty()) TaskStatus.FAILED else TaskStatus.PARTIAL
            ledger.terminalReason = "known failed procedure; use a different approach or changed controls"
            memory.saveLedger(ledger)
            return
        }
        ledger.cursor = 0
        programBefore = latestPage
        ledger.programPage = latestPage?.pageType
        ledger.programVerifiedSteps.clear()
        programSatisfiedSteps.clear()
        ledger.programCompleted = false
        ledger.programCapability = capability
        ledger.programPost = post.ifEmpty { steps.lastOrNull { !it.optional }?.expect.orEmpty() }.map { bindExpect(it, params) }
        ledger.programPost = (ledger.programPost + ledger.currentProgram.filter { it.role == Role.FACET && it.facetKey != null && it.arg != null }
            .map { Postcondition.ValueIs(it.facetKey!!, it.arg!!) }).distinct()
        if (source.startsWith("skill:")) ledger.attemptedSkills += capability
        ledger.programSource = source
        ledger.note("$source: $reason")
        events.status(reason)
        memory.saveLedger(ledger)
    }

    private fun bindExpect(p: Postcondition, params: Map<String, String>): Postcondition = BoundProgram.expectation(p,params)

    /** An ordinary overlay cannot replace the suspended program or borrow its verification credit.
     * The persisted original program also encounters this guard after a process restart. */
    private fun dismissBlockingPrerequisite(ledger: TaskLedger, before: SemanticPageState, site: SiteModel, profile: SiteProfile, executor: Executor, mode: EngineMode): Boolean {
        val pending = ledger.currentProgram.getOrNull(ledger.cursor) ?: return false
        if (!before.dialogOpen || before.isHumanOnly || before.pageType in setOf(PageType.DETAIL, PageType.FACET_PANEL, PageType.MESSAGES) ||
            LearningOpportunities.dialogHoldsFilters(before) || pending.role == Role.CLOSE || pending.kind == com.appgate.brain.model.StepKind.DISMISS) return false
        fun stop(reason: String) {
            ledger.status = TaskStatus.PARTIAL
            ledger.terminalReason = reason
            memory.saveLedger(ledger)
        }
        val step = Step(com.appgate.brain.model.StepKind.DISMISS, Role.CLOSE, expect = listOf(Postcondition.DialogClosed))
        val grounded = (StepGrounder(site).ground(step, emptyMap(), before, ledger.visited, strict = true) as? GroundingOutcome.Ready)?.grounded
        if (grounded == null) { stop("ordinary dialog has no safe dismissal target"); return true }
        val key = FailedStrategies.key(before, "dismiss_dialog", listOf(step))
        if (!FailedStrategies.allowed(site, key, clock())) { stop("dialog dismissal procedure is held after a failed attempt"); return true }
        if (stopRequested.get()) return true
        if (ledger.actions >= ledger.goal.budget.actions) {
            ledger.status = TaskStatus.BUDGET_EXHAUSTED; ledger.terminalReason = "action budget exhausted"; memory.saveLedger(ledger); return true
        }
        if (!ProgressSupervisor.permit(ledger, before, step)) { stop("dialog dismissal repeat state limit"); return true }
        pace(profile)
        if (stopRequested.get()) return true
        ledger.actions++
        memory.saveLedger(ledger)
        val started = clock()
        val outcome = executor.execute(grounded.action, before, mode, ledger.grants, null, clock())
        val firstAfter = (outcome as? ExecOutcome.Done)?.after
        if (firstAfter != null && !acceptPage(ledger, firstAfter, profile)) return true
        var after = firstAfter
        var result = after?.let { Verifier.verify(grounded.action, before, it, ledger.visited) }
        if (result?.status == VerifyStatus.AMBIGUOUS) {
            Thread.sleep(config.ambiguousRecheckMs)
            runCatching { executor.observe(clock()) }.getOrNull()?.let { observed ->
                after = observed; result = Verifier.verify(grounded.action, before, observed, ledger.visited)
            }
        }
        val observed = after
        if (observed != null && !acceptPage(ledger, observed, profile)) return true
        if (observed != null) latestPage = observed
        val status = result?.status ?: VerifyStatus.FAILED
        val code = when {
            status == VerifyStatus.VERIFIED -> DiagnosticCode.VERIFIED
            status == VerifyStatus.HUMAN_NEEDED -> if (observed?.challenge == true) DiagnosticCode.CHALLENGE else DiagnosticCode.AUTH_WALL
            outcome is ExecOutcome.Failed && outcome.timeout -> DiagnosticCode.RENDERER_TIMEOUT
            outcome is ExecOutcome.Failed -> DiagnosticCode.ACTION_REJECTED
            outcome is ExecOutcome.Blocked -> DiagnosticCode.INTERLOCK_BLOCKED
            status == VerifyStatus.AMBIGUOUS -> DiagnosticCode.PAGE_UNSETTLED
            else -> DiagnosticCode.UNMET_EXPECTATION
        }
        events.diagnostic(ledger.host, "action_outcome", SemanticDiagnostics.action(ledger, before, observed, step, status, code).put("capability", "dismiss_dialog"))
        val evidence = result?.evidence?.joinToString("; ") ?: when (outcome) {
            is ExecOutcome.Failed -> outcome.reason
            is ExecOutcome.Blocked -> outcome.reason
            else -> "dialog dismissal did not verify"
        }
        ledger.record(LedgerStep(clock(), ledger.phase, before.hash, step.describe(), status, evidence.take(300), "prerequisite:dismiss_dialog"))
        events.step(ledger, "dismiss blocking dialog prerequisite", status)
        recordEpisode(site, before, grounded, status, observed?.pageType ?: before.pageType, clock() - started)
        if (status == VerifyStatus.HUMAN_NEEDED && observed != null) {
            if (observed.challenge) site.recordChallenge(clock())
            ledger.status = TaskStatus.NEED_HUMAN
            ledger.humanReason = if (observed.challenge) "This site is asking for a human verification step." else "This site needs you to sign in."
            memory.saveSite(site); memory.saveLedger(ledger)
            events.needHuman(ledger, ledger.humanReason, observed.url)
            return true
        }
        if (observed != null && status in setOf(VerifyStatus.VERIFIED, VerifyStatus.FAILED))
            learnFromOutcome(ledger, site, before, observed, grounded, status == VerifyStatus.VERIFIED)
        if (status == VerifyStatus.VERIFIED) {
            FailedStrategies.record(site, key, true, clock())
            progress(ledger, "dialog prerequisite|"+before.hash+"|"+observed?.hash, "ordinary dialog dismissed")
        } else if (status != VerifyStatus.HUMAN_NEEDED) {
            // No unchanged retry, and never quarantine the suspended original procedure.
            if (status == VerifyStatus.FAILED && observed?.settle == com.appgate.brain.model.Settle.IDLE && before.settle == com.appgate.brain.model.Settle.IDLE)
                FailedStrategies.record(site, key, false, clock())
            stop("ordinary dialog dismissal did not verify")
        }
        memory.saveSite(site)
        memory.saveLedger(ledger)
        return true
    }

    private fun runProgramStep(ledger: TaskLedger, sps: SemanticPageState, site: SiteModel, profile: SiteProfile, executor: Executor, mode: EngineMode, remainingWallMs: () -> Long) {
        if (programBefore == null) { programBefore = sps; ledger.programPage = sps.pageType }
        val step = ledger.currentProgram[ledger.cursor]
        if (!ProgressSupervisor.permit(ledger, sps, step)) {
            missingStep(ledger, sps, site, step, "repeat_state_limit")
            return
        }
        val grounder = StepGrounder(site)
        when (val g = grounder.ground(step, emptyMap(), sps, ledger.visited, strict = strictGrounding(ledger))) {
            is GroundingOutcome.Skip -> { ledger.cursor++; events.log("debug", "skip ${step.describe()}: ${g.reason}"); memory.saveLedger(ledger) }
            is GroundingOutcome.Missing -> {
                missingStep(ledger, sps, site, step, "no_target")
            }
            is GroundingOutcome.Ready -> executeGrounded(ledger, sps, site, profile, executor, mode, g.grounded, retriesLeft = 2, remainingWallMs = remainingWallMs)
        }
    }

    /** Learned contracts and finite local recipes cannot fall back to a different role/facet. */
    private fun strictGrounding(ledger: TaskLedger): Boolean {
        if (ledger.programSource == "local") return true
        if (!ledger.programSource.startsWith("skill:")) return false
        // A persisted program keeps its contract even after its source skill is retired.
        val skill = memory.skills.get(ledger.programSource.removePrefix("skill:")) ?: return true
        return skill.origin != com.appgate.brain.model.SkillOrigin.BUILTIN && ("verified_v2" in skill.tags || "demonstrated_candidate_v2" in skill.tags)
    }

    private fun executeGrounded(ledger: TaskLedger, before: SemanticPageState, site: SiteModel, profile: SiteProfile, executor: Executor, mode: EngineMode, grounded: GroundedStep, retriesLeft: Int, excludedIds: Set<String> = emptySet(), remainingWallMs: () -> Long) {
        if (ledger.actions >= ledger.goal.budget.actions || stopRequested.get()) return
        val action = grounded.action
        if (action.url != null && !allowedUrl(ledger, action.url, profile)) {
            missingStep(ledger, before, site, grounded.step, "outside_task_host")
            return
        }
        val rejectedSelectKey = FailedStrategies.rejectedFacetSelectKey(before, action)
        if (rejectedSelectKey != null && !FailedStrategies.allowed(site, rejectedSelectKey, clock())) {
            ledger.note("unchanged rejected facet command held across strategies")
            finishProgram(ledger, before, site, success = false, rememberFailure = false)
            return
        }
        if (ledger.goal.intent == GoalIntent.LEARN_SITE) events.guide(LearningGuide.snapshot(ledger, grounded.step.copy(expect = action.expect.ifEmpty { Verifier.defaultExpectations(action, before) })))
        val stepDesc = "${action.describe()} [${ledger.programSource}]"
        ledger.actions++
        memory.saveLedger(ledger)                       // checkpoint before acting
        events.status(stepDesc)
        pace(profile)
        val started = clock()
        when (val outcome = executor.execute(action, before, mode, ledger.grants, ledger.previewHash.takeIf { it.isNotBlank() }, clock())) {
            is ExecOutcome.Blocked -> {
                if (outcome.needGrant) {
                    ledger.previewHash = Hashing.sha256Hex(ledger.host + "|" + ledger.previewText)
                    ledger.status = TaskStatus.NEED_GRANT; memory.saveLedger(ledger)
                    events.needGrant(ledger, ledger.previewText, ledger.previewHash)
                } else {
                    diagnostic(ledger, before, null, grounded.step, VerifyStatus.FAILED, DiagnosticCode.INTERLOCK_BLOCKED)
                    ledger.consecutiveFailures++
                    ledger.note("blocked: ${outcome.reason}")
                    events.step(ledger, "$stepDesc blocked: ${outcome.reason}", VerifyStatus.FAILED)
                    finishProgram(ledger, before, site, success = false, rememberFailure = false)
                }
            }
            is ExecOutcome.Failed -> {
                val code = when { outcome.timeout -> DiagnosticCode.RENDERER_TIMEOUT; outcome.reason == "STALE_DOCUMENT" -> DiagnosticCode.STALE_DOCUMENT; else -> DiagnosticCode.ACTION_REJECTED }
                diagnostic(ledger, before, null, grounded.step, VerifyStatus.FAILED, code)
                ledger.record(LedgerStep(clock(), ledger.phase, before.hash, stepDesc, VerifyStatus.FAILED, code.name, ledger.programSource))
                events.step(ledger, stepDesc, VerifyStatus.FAILED)
                recordEpisode(site, before, grounded, VerifyStatus.FAILED, before.pageType, clock() - started)
                // A document/target refusal happened before a click. Re-observe and ground
                // the semantic step against the new document; never replay the old target.
                val stale = outcome.reason == "STALE_DOCUMENT" || outcome.reason == "STALE_TARGET" || outcome.reason == "TARGET_MISSING"
                if (stale && retriesLeft > 0 && grounded.chosen != null && action.effect != com.appgate.brain.model.EffectClass.COMMIT_EXTERNAL) {
                    val observed = runCatching { executor.observe(clock()) }.getOrNull()
                    if (observed != null && acceptPage(ledger, observed, profile) && !observed.isHumanOnly &&
                        observed.pageType == before.pageType && !observed.dialogOpen) {
                        // IDs are document-local: a document refusal invalidates the old exclusion set.
                        val excluded = if (outcome.reason == "STALE_DOCUMENT") emptySet() else
                            excludedIds + listOfNotNull(grounded.chosen.affordance.id)
                        val fresh = StepGrounder(site).ground(grounded.step, emptyMap(), observed, ledger.visited,
                            excluded, strict = strictGrounding(ledger))
                        if (fresh is GroundingOutcome.Ready && fresh.grounded.chosen != null) {
                            executeGrounded(ledger, observed, site, profile, executor, mode, fresh.grounded, retriesLeft - 1, excluded, remainingWallMs)
                            return
                        }
                    }
                    if (ledger.blocked || ledger.done) return
                }
                val rejectedNativeOption = outcome.rejected && outcome.reason.startsWith("option not found: ") &&
                    !outcome.timeout && !stale && rejectedSelectKey != null
                if (rejectedNativeOption) {
                    FailedStrategies.record(site, rejectedSelectKey!!, false, clock())
                    memory.saveSite(site)
                }
                if (outcome.timeout) handleRendererFailure(ledger, outcome.reason, true)
                else { ledger.consecutiveFailures++; recordFailureMemory(site, before, grounded.step, "exec:" + code.name) }
                // The scoped command hold already persists this definite rejection. A second
                // shape-only hold would prevent a genuine option-list change from reopening it.
                finishProgram(ledger, before, site, success = false, rememberFailure = !outcome.timeout && !stale && !rejectedNativeOption)
            }
            is ExecOutcome.Done -> {
                var after = outcome.after
                if (!acceptPage(ledger, after, profile)) return
                var result = Verifier.verify(action, before, after, ledger.visited)
                // Listing routes may show a settled loading dialog before the actual
                // detail. Observe the original click's postconditions before another
                // action; a dialog alone never earns detail or learning credit.
                val openingItem = action.kind == ActionKind.CLICK && action.target?.role == Role.RESULT_ITEM
                fun itemDeadlineExpired(): Boolean {
                    if (!openingItem || remainingWallMs() > 0L || result.status == VerifyStatus.HUMAN_NEEDED) return false
                    ledger.status = TaskStatus.BUDGET_EXHAUSTED
                    ledger.terminalReason = "task wall budget exhausted while observing item outcome"
                    memory.saveLedger(ledger)
                    return true
                }
                if (itemDeadlineExpired()) return
                var observationRechecks = 0
                var observationReadFailed = false
                var identityUnconfirmed = false
                var unreadableRecheck = false
                var deferredItem = false
                val rechecks = if (openingItem) 2 else if (result.status == VerifyStatus.AMBIGUOUS) 1 else 0
                for (attempt in 0 until rechecks) {
                    if (result.status in setOf(VerifyStatus.VERIFIED, VerifyStatus.HUMAN_NEEDED) ||
                        stopRequested.get() || ledger.blocked || ledger.done) break
                    if (itemDeadlineExpired()) return
                    val delay = if (openingItem) minOf(config.ambiguousRecheckMs.coerceIn(0L, 2_500L), remainingWallMs().coerceAtLeast(0L)) else config.ambiguousRecheckMs
                    Thread.sleep(delay)
                    if (stopRequested.get()) break
                    if (itemDeadlineExpired()) return
                    observationRechecks++
                    val again = try { executor.observe(clock(), if (openingItem) remainingWallMs().coerceAtLeast(1L) else Long.MAX_VALUE) }
                    catch (interrupted: InterruptedException) { throw interrupted }
                    catch (_: Exception) {
                        observationReadFailed = true
                        unreadableRecheck = openingItem
                        result = result.copy(status = VerifyStatus.AMBIGUOUS)
                        break
                    }
                    if (!acceptPage(ledger, again, profile)) return
                    after = again
                    deferredItem = openingItem
                    result = Verifier.verify(action, before, after, ledger.visited)
                    if (itemDeadlineExpired()) return
                    if (deferredItem && result.status == VerifyStatus.VERIFIED && !Verifier.deferredItemMatches(action, before, after)) {
                        identityUnconfirmed = true
                        unreadableRecheck = true
                        result = result.copy(status = VerifyStatus.AMBIGUOUS,
                            evidence = listOf("deferred item identity not confirmed"))
                        break
                    }
                }
                if (stopRequested.get() && result.status != VerifyStatus.HUMAN_NEEDED) return
                if (itemDeadlineExpired()) return
                if (!acceptPage(ledger, after, profile)) return
                latestPage = after
                diagnostic(ledger, before, after, grounded.step, result.status, when (result.status) {
                    VerifyStatus.VERIFIED -> DiagnosticCode.VERIFIED
                    VerifyStatus.HUMAN_NEEDED -> if (after.challenge) DiagnosticCode.CHALLENGE else DiagnosticCode.AUTH_WALL
                    VerifyStatus.AMBIGUOUS -> if (after.settle != com.appgate.brain.model.Settle.IDLE) DiagnosticCode.PAGE_UNSETTLED else DiagnosticCode.UNMET_EXPECTATION
                    else -> DiagnosticCode.UNMET_EXPECTATION
                }, VerificationDiagnostics.observed(action,before,after,result,observationRechecks,observationReadFailed,identityUnconfirmed))
                if (result.status == VerifyStatus.HUMAN_NEEDED) {
                    ledger.record(LedgerStep(clock(), ledger.phase, before.hash, stepDesc, VerifyStatus.HUMAN_NEEDED, result.evidence.joinToString("; "), ledger.programSource))
                    if (after.challenge) site.recordChallenge(clock())
                    ledger.status = TaskStatus.NEED_HUMAN
                    ledger.humanReason = if (after.challenge) "This site is asking for a human verification step." else "This site needs you to sign in."
                    ledger.currentProgram = emptyList(); ledger.cursor = 0
                    memory.saveSite(site); memory.saveLedger(ledger)
                    events.needHuman(ledger, ledger.humanReason, after.url)
                    return
                }
                val verified = result.status == VerifyStatus.VERIFIED
                ledger.record(LedgerStep(clock(), ledger.phase, before.hash, stepDesc, result.status, result.evidence.joinToString("; ").take(300), ledger.programSource))
                events.step(ledger, stepDesc, result.status)
                recordEpisode(site, before, grounded, result.status, after.pageType, outcome.ms)
                if (!unreadableRecheck) learnFromOutcome(ledger, site, before, after, grounded, verified)
                if (unreadableRecheck) {
                    // The last readable page is stale. Do not re-ground another click
                    // or teach a negative binding/edge from this unknown outcome.
                    finishProgram(ledger, after, site, success = false, rememberFailure = false)
                    ledger.status = TaskStatus.PARTIAL
                    ledger.terminalReason = "item outcome unconfirmed; waiting for a fresh opportunity"
                } else if (verified) {
                    ledger.consecutiveFailures = 0
                    programSatisfiedSteps += ledger.cursor
                    if (grounded.action.kind != ActionKind.WAIT && verifiedChange(grounded.action, before, after))
                        ledger.programVerifiedSteps += ledger.cursor
                    if (verifiedChange(grounded.action, before, after))
                        progress(ledger, "verified|${evidenceKey(before)}|${grounded.action.kind}|${grounded.step.role}|${evidenceKey(after)}", "new verified state")
                    ledger.cursor++
                    afterVerified(ledger, before, after, grounded, site)
                } else if (grounded.step.optional) {
                    ledger.cursor++            // optional steps may fail silently
                } else {
                    // DOM and effects can change during an action. Resolve a NEW live target.
                    val excluded = excludedIds + listOfNotNull(grounded.chosen?.affordance?.id)
                    val fresh = if (retriesLeft > 0 && grounded.chosen != null && after.pageType == before.pageType && !after.dialogOpen)
                        StepGrounder(site).ground(grounded.step, emptyMap(), after, ledger.visited, excluded, strict = strictGrounding(ledger)) else null
                    if (fresh is GroundingOutcome.Ready && fresh.grounded.chosen != null) {
                        executeGrounded(ledger, after, site, profile, executor, mode, fresh.grounded, retriesLeft - 1, excluded, remainingWallMs)
                        return
                    }
                    if (grounded.action.kind == ActionKind.SCROLL) ledger.scrollRoundsWithoutNew++   // exploration, not a failure
                    else { ledger.consecutiveFailures++; recordFailureMemory(site, before, grounded.step, "unverified") }
                    val scrollDelta=if(grounded.action.kind==ActionKind.SCROLL)ScrollContext.delta(before,after) else 0L
                    val paginationExpectation=grounded.action.expect.isNotEmpty() &&
                        grounded.action.expect.all{Verifier.isScrollPostcondition(it)}
                    val paginationAction=grounded.action.kind==ActionKind.SCROLL ||
                        grounded.step.role==Role.LOAD_MORE || grounded.step.role==Role.PAGE_NEXT
                    val observedEnd=paginationAction && Verifier.holds(Postcondition.EndOfResults,grounded.action,before,after,ledger.visited,mutableListOf())
                    // Partial movement, an incomparable surface, and an independently observed end
                    // do not prove a broken pagination procedure or earn a new-results lesson.
                    val neutralPagination=paginationExpectation && (observedEnd ||
                        (grounded.action.kind==ActionKind.SCROLL && (scrollDelta==null || scrollDelta>0L)))
                    finishProgram(ledger, after, site, success = false, rememberFailure = result.status == VerifyStatus.FAILED && before.settle == com.appgate.brain.model.Settle.IDLE && after.settle == com.appgate.brain.model.Settle.IDLE && !neutralPagination)
                }
                memory.saveLedger(ledger)
            }
        }
    }

    private fun afterVerified(ledger: TaskLedger, before: SemanticPageState, after: SemanticPageState, grounded: GroundedStep, site: SiteModel) {
        val role = grounded.step.role
        val key = grounded.step.facetKey
        if ((role == Role.FACET || role == Role.FACET_APPLY) && key != null && !key.startsWith("$")) {
            val value = grounded.step.arg
            if (after.constraintsActive[key]?.let { value != null && Verifier.valuesMatch(value, it) } == true) ledger.appliedConstraints += key
        }
        if (role == Role.SEARCH_BOX && grounded.action.submit && after.pageType == PageType.RESULTS) {
            ledger.phase = if (ledger.phase == TaskPhase.START || ledger.phase == TaskPhase.SEARCH) TaskPhase.CONSTRAIN else ledger.phase
            ledger.resultsUrl = after.url
            learnSearchUrl(site, ledger.goal, after.url)
        }
        if (grounded.action.kind == ActionKind.NAVIGATE && ledger.programSource == "navigate" && after.pageType == PageType.RESULTS && ledger.phase == TaskPhase.START) {
            ledger.phase = TaskPhase.CONSTRAIN
            ledger.resultsUrl = after.url
        }
        if (role == Role.SEND && ledger.goal.intent == GoalIntent.PREPARE_MESSAGE) {
            ledger.phase = TaskPhase.DONE
            ledger.note("message sent with grant ${grounded.action.grantId ?: ledger.grants.lastOrNull()?.id ?: ""}")
        }
        if (role == Role.PAGE_NEXT || role == Role.LOAD_MORE || grounded.action.kind == ActionKind.SCROLL) {
            val newKeys = after.resultKeys.count { it !in ledger.visited }
            ledger.scrollRoundsWithoutNew = if (newKeys > 0) 0 else ledger.scrollRoundsWithoutNew + 1
        }
        collectCards(ledger, after)
    }

    private fun finishProgram(ledger: TaskLedger, sps: SemanticPageState, site: SiteModel, success: Boolean) =
        finishProgram(ledger, sps, site, success, rememberFailure = true)

    private fun finishProgram(ledger: TaskLedger, sps: SemanticPageState, site: SiteModel, success: Boolean, rememberFailure: Boolean) {
        val before = programBefore
        val required = ledger.currentProgram.indices.filter { !ledger.currentProgram[it].optional }
        val capabilityHolds = when (ledger.programCapability) {
            "search" -> before != null && sps.pageType == PageType.RESULTS &&
                TaskPolicy(profileFor(ledger), site).queryApplied(ledger.goal, sps) &&
                Verifier.holds(Postcondition.ResultsChanged, Action(ActionKind.WAIT), before, sps, emptySet(), mutableListOf())
            "constrain_numeric", "select_facet" -> {
                val facets = ledger.currentProgram.filter { it.role == Role.FACET && it.facetKey != null && it.arg != null }
                // An apply-only repair still carries the original typed constraint contract.
                fun constraints(p: Postcondition): List<Pair<String, String>> = when (p) {
                    is Postcondition.ConstraintApplied -> p.value?.let { listOf(p.key to it) }.orEmpty()
                    is Postcondition.ValueIs -> listOf(p.facetKey to p.value)
                    is Postcondition.AnyOf -> p.alternatives.flatMap { constraints(it) }
                    else -> emptyList()
                }
                val expected = (facets.map { it.facetKey!! to it.arg!! } + ledger.programPost.flatMap { constraints(it) }).distinct()
                sps.pageType != PageType.FACET_PANEL && !sps.dialogOpen && expected.isNotEmpty() && expected.all { (key, value) ->
                    sps.constraintsActive[key]?.let { Verifier.valuesMatch(value, it) } == true
                }
            }
            "open_item" -> sps.pageType == PageType.DETAIL
            "load_more", "scroll_results", "next_page" -> before != null && sps.pageType == PageType.RESULTS &&
                Verifier.holds(Postcondition.NewResults, Action(ActionKind.WAIT), before, sps, emptySet(), mutableListOf())
            else -> true
        }
        // A failed Enter may already have filled the input. That exact, satisfied
        // query is a prerequisite, not a second action to manufacture. Retain the
        // ordinary required-TYPE portable contract, and accept its satisfied state
        // only when a required Submit really transitioned and new results verify.
        val submittedSearch = ledger.programCapability == "search" && capabilityHolds && before != null &&
            Verifier.holds(Postcondition.ValueIs("query", ledger.goal.query), Action(ActionKind.WAIT), before, sps, emptySet(), mutableListOf()) &&
            required.any { i -> ledger.currentProgram[i].let { step ->
                step.kind == StepKind.CLICK && step.role == Role.SUBMIT &&
                    Postcondition.ResultsChanged in step.expect && i in ledger.programVerifiedSteps
            } }
        fun hasStepEvidence(i: Int): Boolean {
            if (i in ledger.programVerifiedSteps) return true
            val step = ledger.currentProgram[i]
            return submittedSearch && i in programSatisfiedSteps && step.kind == StepKind.TYPE &&
                step.role == Role.SEARCH_BOX && !step.submit && step.arg == ledger.goal.query &&
                step.expect == listOf(Postcondition.ValueIs("query", ledger.goal.query))
        }
        val programEvidence = success && before != null && required.isNotEmpty() && required.all(::hasStepEvidence) &&
            ledger.programPost.isNotEmpty() && ledger.programPost.all {
                Verifier.holds(it, Action(ActionKind.WAIT), before, sps, emptySet(), mutableListOf())
            }
        val verified = programEvidence && capabilityHolds
        val paginationScroll=ledger.programCapability in setOf("load_more","scroll_results","next_page") &&
            ledger.currentProgram.any{it.kind==StepKind.SCROLL} && ledger.programPost.isNotEmpty() &&
            ledger.programPost.all{Verifier.isScrollPostcondition(it)}
        val scrollDelta=before?.let{ScrollContext.delta(it,sps)}
        // A failed unrelated action still records its failure; only a successful
        // scroll procedure with an unconfirmed pagination outcome is deferred.
        val canRememberFailure=rememberFailure && !(success && !verified && before!=null && paginationScroll && scrollDelta==null)
        // A verified end-of-list is useful navigation, but no evidence that this
        // program loaded new listings. Neither award mastery nor punish a valid
        // learned procedure for reaching the ordinary end of a collection.
        val terminalEnd = success && !capabilityHolds && before != null && required.isNotEmpty() &&
            required.all { it in programSatisfiedSteps } && ledger.programPost.isNotEmpty() &&
            ledger.programPost.all { Verifier.holds(it, Action(ActionKind.WAIT), before, sps, emptySet(), mutableListOf()) } &&
            ledger.programCapability in setOf("load_more", "scroll_results", "next_page") &&
            Verifier.holds(Postcondition.EndOfResults, Action(ActionKind.WAIT), before, sps, emptySet(), mutableListOf())
        // Moving toward a footer is useful exploration, not proof of loading or failure.
        // Transport uncertainty and interlock refusals cannot quarantine sound learned skills.
        val advancingScroll = success && before != null && paginationScroll && (scrollDelta?:0L)>0L
        // Repeating an already applied query with identical listings proves no new
        // search capability, but cannot blacklist a working form for future queries.
        val unchangedSearch = ledger.programCapability == "search" && before != null &&
            before.pageType == PageType.RESULTS && sps.pageType == PageType.RESULTS &&
            before.resultKeys.isNotEmpty() && before.resultKeys == sps.resultKeys &&
            committedQueryMatches(ledger.goal, before) && committedQueryMatches(ledger.goal, sps)
        val neutral = unchangedSearch || terminalEnd || !verified && (!canRememberFailure || advancingScroll)
        if (ledger.goal.intent == GoalIntent.LEARN_SITE && ledger.lesson == "scroll_results" &&
            ledger.programCapability == "scroll_results" && !verified && !ledger.blocked) {
            // Transport uncertainty and real failures are not evidence of useful movement.
            // A later neutral step cannot erase an earlier non-neutral failure in this task.
            ledger.neutralScrollOnly = ledger.neutralScrollOnly != false && success && (advancingScroll || terminalEnd)
        }
        ledger.programCompleted = verified
        val source = ledger.programSource
        val savedCapability = ledger.programCapability
        val strategyKey = before?.let { FailedStrategies.key(it, savedCapability, ledger.currentProgram, skillParams(ledger)) }
        val priorFailures = strategyKey?.let { site.strategyFailures[it]?.count } ?: 0
        var memoryCode = "STRATEGY_NOT_RECORDED"
        val programPresent = ledger.currentProgram.isNotEmpty()
        val sourceEligible = source.startsWith("skill:") || source in setOf("planner", "local")
        if (before != null && ledger.currentProgram.isNotEmpty() && !ledger.blocked && !neutral &&
            (verified || canRememberFailure) && (source.startsWith("skill:") || source in setOf("planner", "local"))) {
            FailedStrategies.record(site, strategyKey!!, verified, clock(),
                if(!verified) FailedStrategies.approach(before, savedCapability, ledger.currentProgram, skillParams(ledger)) else null)
            memoryCode = if (verified) "STRATEGY_SUCCESS_SAVED" else "STRATEGY_FAILURE_SAVED"
            if (verified) FailedStrategies.record(site, FailedStrategies.key(before, ledger.programCapability), true, clock())
        }
        val completedBeforeProgram=site.curriculum.filter{it.done}.map{it.id}.toSet()
        if (source.startsWith("skill:")) {
            val id = source.removePrefix("skill:")
            if (!neutral) memory.skills.recordOutcome(id, ledger.host, verified)
            if (verified) {
                ledger.successfulSkills += ledger.programCapability
                Curriculum.markSkillVerified(site, ledger.programCapability, clock())
            }
        } else if (source in setOf("planner", "local") && verified) {
            // Composite compilation proof includes the satisfied exact-query
            // prerequisite. Keep the original ledger's causal transitions intact.
            val compilationLedger = if (submittedSearch) ledger.copy(
                programVerifiedSteps = (ledger.programVerifiedSteps + required.filter(::hasStepEvidence)).toMutableSet()
            ) else ledger
            SkillCompiler.compileVerified(memory, compilationLedger, ledger.currentProgram, clock())
            ledger.successfulSkills += ledger.programCapability
            Curriculum.markSkillVerified(site, ledger.programCapability, clock())
        }
        if (terminalEnd) ledger.note("end of results verified; no new loading lesson credited")
        if (unchangedSearch) ledger.note("unchanged search context; no new learning credited")
        if (!verified && success && !neutral) ledger.consecutiveFailures++
        if (!verified && !neutral && (source.startsWith("skill:") || source == "local") && !ledger.blocked)
            ledger.repairReason = "repair failed ${ledger.programCapability} procedure"
        if (!verified && !neutral && source == "planner") ledger.plannerFailures++
        ledger.currentProgram = emptyList()
        ledger.cursor = 0
        ledger.programSource = ""
        val discoveryKey = ledger.constraintAttempts.keys.firstOrNull { it.startsWith("__discovery:") }
        if (source == "local" && discoveryKey != null) {
            ledger.programCapability = discoveryKey.removePrefix("__discovery:")
            ledger.repairReason = if (verified) "continue after opening controls" else "local discovery did not reveal controls"
            ledger.constraintAttempts.remove(discoveryKey)
        }
        programBefore = null
        memory.saveLedger(ledger)
        memory.saveSite(site)
        memoryCheckpoint(ledger, memoryCode, savedCapability, before, strategyComparison(ledger,savedCapability,strategyKey)
            .put("program_source",SemanticDiagnostics.programSource(source))
            .put("new_lessons",site.curriculum.count{it.done && it.id !in completedBeforeProgram})
            .put("before_present", if (before != null) 1 else 0).put("program_present", if (programPresent) 1 else 0)
            .put("source_eligible", if (sourceEligible) 1 else 0).put("outcome_blocked", if (ledger.blocked) 1 else 0)
            .put("strategy_prior_failures", priorFailures)
            .put("strategy_current_failures", strategyKey?.let { site.strategyFailures[it]?.count } ?: 0)
            .put("outcome_verified", if (verified) 1 else 0).put("outcome_neutral", if (neutral) 1 else 0)
            .put("outcome_rememberable", if (canRememberFailure) 1 else 0)
            .put("before_results", if (before?.pageType == PageType.RESULTS) 1 else 0)
            .put("after_dialog", if (sps.dialogOpen) 1 else 0)
            .put("dialog_detail_controls", sps.affordances.count { it.visible &&
                (it.regionRole == com.appgate.brain.model.RegionRole.DIALOG || it.features["in_dialog"] > 0) &&
                it.role in setOf(Role.DETAIL_TITLE, Role.EXPAND_TEXT) }.coerceAtMost(1_000_000)))
    }


    /** Neutral refreshes require committed URL evidence, never an editable draft. */
    private fun committedQueryMatches(goal: Goal, sps: SemanticPageState): Boolean = runCatching {
        fun normalized(value: String) = value.trim().lowercase().replace(Regex("""\s+"""), " ")
        val wanted = normalized(goal.query)
        if (wanted.isBlank()) return@runCatching false
        val keys = setOf("q", "query", "keywords", "keyword", "search", "search_query", "searchterm", "term")
        val values = java.net.URI(sps.url).rawQuery.orEmpty().split('&').mapNotNull { part ->
            val key = java.net.URLDecoder.decode(part.substringBefore('='), "UTF-8").lowercase()
            if (key !in keys) null else java.net.URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
        }
        values.isNotEmpty() && values.all { normalized(it) == wanted }
    }.getOrDefault(false)

    // ------------------------------------------------------------------ learning

    private fun learnFromOutcome(ledger: TaskLedger, site: SiteModel, before: SemanticPageState, after: SemanticPageState, grounded: GroundedStep, verified: Boolean) {
        val chosen = grounded.chosen ?: return
        val role = grounded.step.role ?: return
        val facet = grounded.step.facetKey?.takeIf { !it.startsWith("$") }
        val binding = Binding(
            host = site.host, pageType = before.pageType, role = role, facetKey = facet, siteVersion = before.siteVersion,
            features = chosen.affordance.features, nameHints = listOf(chosen.affordance.name).filter { it.isNotBlank() },
            stats = com.appgate.brain.model.BetaStat(), lastVerifiedAt = 0L
        )
        memory.recordBinding(site.host, binding, verified)
        site.recordEdge(before.pageType, role, facet, after.pageType, verified, clock())
        site.recordCalibration(grounded.predictedP, verified)
        if (verified) site.verifiedActions++ else site.failedActions++
        memory.saveSite(site)
    }

    private fun recordFailureMemory(site: SiteModel, sps: SemanticPageState, step: Step, reason: String) {
        val role = step.role ?: return
        site.recordFailure(sps.pageType, role, step.facetKey?.takeIf { !it.startsWith("$") }, reason, clock())
        memory.saveSite(site)
    }

    private fun recordEpisode(site: SiteModel, before: SemanticPageState, grounded: GroundedStep, status: VerifyStatus, toPage: PageType, ms: Long) {
        memory.recordEpisode(Episode(clock(), site.host, before.pageType, grounded.step.role, grounded.step.facetKey?.takeIf { !it.startsWith("$") },
            grounded.action.kind.name, status, grounded.predictedP, toPage, ms, "engine"))
    }

    private fun trackSiteVersion(site: SiteModel, sps: SemanticPageState) {
        if (sps.siteVersion.isBlank()) return
        if (site.siteVersion.isBlank()) { site.siteVersion = sps.siteVersion; site.versionChangedAt = clock(); return }
        if (site.siteVersion != sps.siteVersion) {
            // This is a visible page shape, not a deployment version. Pagination, drawers
            // and dialogs change it routinely. Live role/feature grounding and verified
            // failures decide whether a binding is useful; a shape change alone cannot.
            site.siteVersion = sps.siteVersion
            site.versionChangedAt = clock()
        }
    }

    /** Learn a direct search URL template from a verified search (query encoded in the URL). */
    private fun learnSearchUrl(site: SiteModel, goal: Goal, url: String) {
        if (goal.query.isBlank()) return
        val encoded = URLEncoder.encode(goal.query, "UTF-8")
        val variants = listOf(encoded, encoded.replace("+", "%20"), encoded.replace("+", "-"), goal.query.replace(' ', '+'), goal.query.replace(' ', '-'))
        for (v in variants) {
            if (url.contains(v, ignoreCase = true)) {
                val template = url.replace(v, "{q}", ignoreCase = true).substringBefore('#')
                if (template.contains("{q}")) {
                    site.searchUrlTemplate = template
                    memory.saveSite(site)
                    return
                }
            }
        }
    }

    // ------------------------------------------------------------------ cards & detail

    private fun collectCards(ledger: TaskLedger, sps: SemanticPageState) {
        val items = sps.results?.items ?: return
        if (sps.pageType != PageType.RESULTS) return
        if (ledger.phase == TaskPhase.START || ledger.phase == TaskPhase.SEARCH) {
            // Only collect once we believe the results reflect the query.
            if (!TaskPolicy(SiteProfiles.forHost(ledger.host) ?: SiteProfiles.generic(ledger.host), memory.site(ledger.host)).queryApplied(ledger.goal, sps)) return
        }
        var added = 0
        for (item in items) {
            if (ledger.verdicts.containsKey(item.key)) continue
            ledger.verdicts[item.key] = ConstraintEvaluator.fromCard(ledger.goal, item, sps.host)
            ledger.visited += item.key
            added++
        }
        if (added > 0) { progress(ledger, "cards:${ledger.verdicts.size}", "new results"); events.log("debug", "collected $added cards (${ledger.verdicts.size} total)") }
    }

    private fun evaluateDetail(ledger: TaskLedger, sps: SemanticPageState, itemKey: String) {
        val v = ledger.verdicts[itemKey] ?: run { ledger.currentItem = null; return }
        var updated = ConstraintEvaluator.withDetail(ledger.goal, v, sps.detailText, sps.url)
        val planner = if (ledger.goal.intent == GoalIntent.LEARN_SITE) null else plannerFactory()
        for (c in ledger.goal.rare) {
            if (updated.perConstraint[c.key] == Verdict.UNKNOWN && planner != null && ledger.llmCalls < ledger.goal.budget.llmCalls && sps.detailText.length > 80) {
                ledger.llmCalls++
                val ev = runCatching { planner.extractEvidence(c, sps.detailText, sps.title) }.getOrNull()
                if (ev != null && ev.verdict != Verdict.UNKNOWN && ev.confidence >= 0.6) {
                    updated = updated.copy(
                        perConstraint = updated.perConstraint + (c.key to ev.verdict),
                        evidence = updated.evidence + com.appgate.brain.model.Evidence(c.key, ev.quote, "model")
                    )
                }
            }
        }
        ledger.verdicts[itemKey] = updated
        ledger.itemsInspected++
        progress(ledger, "detail:$itemKey", "detail inspected")
        ledger.inspectQueue.remove(itemKey)
        ledger.currentItem = null
        ledger.record(LedgerStep(clock(), ledger.phase, sps.hash, "inspect listing", VerifyStatus.VERIFIED, updated.perConstraint.entries.joinToString(", ") { "${it.key}=${it.value}" }, "evaluator"))
        events.step(ledger, "inspected a listing: " + updated.perConstraint.entries.joinToString(", ") { "${it.key}=${it.value.name.lowercase()}" }, VerifyStatus.VERIFIED)
        memory.saveLedger(ledger)
    }

    // ------------------------------------------------------------------ planner

    private fun askPlanner(ledger: TaskLedger, sps: SemanticPageState, site: SiteModel, profile: SiteProfile, reason: String, repairCapability: String? = null, repairBindings: Map<String, String> = emptyMap()): Boolean {
        val capability = repairCapability ?: capabilityFor(ledger)
        if (ledger.goal.intent == GoalIntent.LEARN_SITE && ledger.effectiveGoal.filterable.isEmpty()) {
            // A resumed drawer discovery can arrive here before the next lesson decision.
            ledger.learningConstraints = LearningOpportunities.target(sps, ledger.lesson)?.constraints.orEmpty()
        }
        var params = skillParams(ledger) + repairBindings
        val pending = if (ledger.goal.intent == GoalIntent.LEARN_SITE && ledger.proposalCorrectionPending)
            ledger.proposalRejections.lastOrNull() else null
        val correcting = pending != null
        if (correcting) {
            // Consume the single correction before any provider access. The persisted
            // quota snapshot authorizes only this task's own immediately prior reservation.
            ledger.proposalCorrectionPending = false
            val alreadyUsed = ledger.proposalCorrectionUsed
            ledger.proposalCorrectionUsed = true
            if (pending!!.target != null) {
                val original = LearningOpportunities.targets(sps,ledger.lesson).firstOrNull { target ->
                    target.skillId==capability && target.params["item"]?.let { com.appgate.brain.util.Hashing.short(it) }==pending.target
                }
                if (original != null) params = params + original.params
            }
            val quota = site.strategyFailures[pending.quotaKey]
            val valid = !alreadyUsed && sps.settle==com.appgate.brain.model.Settle.IDLE &&
                TeacherProposalFeedback.context(sps,capability,params)==pending.context &&
                (pending.target==null || params["item"]?.let { com.appgate.brain.util.Hashing.short(it) }==pending.target && params["item"] in sps.resultKeys) &&
                pending.quotaKey==FailedStrategies.key(sps,capability) && quota?.count==pending.quotaCount && quota.at==pending.quotaAt
            memory.saveLedger(ledger)
            if (!valid) {
                plannerOutcome(ledger,capability,"UNAVAILABLE")
                ledger.status=TaskStatus.PARTIAL
                ledger.terminalReason="teacher correction context changed; waiting for a fresh lesson"
                memory.saveLedger(ledger)
                return true
            }
        }
        // A correction must reach its teacher rather than silently executing another
        // local recipe or switching the selected target.
        if (!correcting && tryLocalRepair(ledger, sps, site, capability)) { plannerOutcome(ledger,capability,"LOCAL_REPAIR");return true }
        if (capability == "open_item" && (params["item"].isNullOrBlank() ||
                params["item"]!!.startsWith("$") || params["item"] !in sps.resultKeys)) {
            // Failed learning programs can lose their bound steps on completion. Return
            // to live policy selection before requesting a target-scoped repair.
            plannerOutcome(ledger,capability,"UNAVAILABLE");ledger.note("repair requires a selected live item"); return false
        }
        val planner = plannerFactory() ?: run { plannerOutcome(ledger,capability,"UNAVAILABLE");events.log("info", "planner unavailable: $reason"); return false }
        if (ledger.llmCalls >= ledger.goal.budget.llmCalls) { plannerOutcome(ledger,capability,"TASK_BUDGET");ledger.note("planner budget exhausted"); return false }
        if (ledger.lastPlannerStateHash == sps.hash && ledger.plannerCallsOnSameState >= config.maxPlannerCallsSameState) { plannerOutcome(ledger,capability,"STATE_LIMIT");ledger.note("planner already tried this state twice"); return false }
        val plannerKey = FailedStrategies.key(sps, capability)
        if (ledger.goal.intent == GoalIntent.LEARN_SITE && !correcting && !FailedStrategies.repairAllowed(site, plannerKey, clock())) {
            plannerOutcome(ledger,capability,"REPAIR_COOLDOWN")
            ledger.note("recent AI repairs failed on this page; waiting for a changed page or cooldown")
            return false
        }
        if (clock() - lastPlannerCallAt < config.plannerCooldownMs) Thread.sleep((config.plannerCooldownMs - (clock() - lastPlannerCallAt)).coerceAtLeast(0L))
        lastPlannerCallAt = clock()
        ledger.llmCalls++
        if (ledger.goal.intent == GoalIntent.LEARN_SITE) {
            // Reserve the attempt before calling the model; a crash cannot reset the quota.
            // A verified procedure clears it; an unverified answer never does.
            FailedStrategies.record(site, plannerKey, false, clock())
            memory.saveSite(site)
        }
        ledger.plannerCallsOnSameState = if (ledger.lastPlannerStateHash == sps.hash) ledger.plannerCallsOnSameState + 1 else 1
        ledger.lastPlannerStateHash = sps.hash
        // An interrupted provider request cannot reset task or same-state accounting.
        memory.saveLedger(ledger)
        // Keep this exact reservation, not whichever request last changed shared state
        // while the provider was running. Only our own reservation may authorize correction.
        val quotaReservation=if(ledger.goal.intent==GoalIntent.LEARN_SITE) site.strategyFailures[plannerKey]?.copy() else null
        events.status("Thinking about this page…")
        val failuresHere = site.failures.values.filter { it.pageType == sps.pageType }.sortedByDescending { it.lastAt }.take(3).map { "${it.role}${it.facetKey?.let { k -> "[$k]" } ?: ""}: ${it.reason}" }
        plannerOutcome(ledger,capability,"PROPOSAL_STARTED")
        val plannerStartedNanos=System.nanoTime()
        val program = try {
            planner.proposeProgram(ledger.effectiveGoal, sps, ledger, memory.skills.retrieve(reason + " " + ledger.goal.intent.name), failuresHere, profile.quirks, capability, FailedStrategies.feedback(site, sps, capability, params), TeacherProposalFeedback.matching(ledger,sps,capability,params))
        } catch (e: PlannerRefused) {
            plannerOutcome(ledger,capability,"REFUSED",com.appgate.brain.planner.PlannerFailureDiagnostics.metadata(e,planner.diagnosticProvider,(System.nanoTime()-plannerStartedNanos)/1_000_000));events.log("warn", "planner refused"); return false
        } catch (e: Exception) {
            val failure=com.appgate.brain.planner.PlannerFailureDiagnostics.metadata(e,planner.diagnosticProvider,(System.nanoTime()-plannerStartedNanos)/1_000_000)
            plannerOutcome(ledger,capability,"ERROR",failure);events.log("warn", "planner error: ${failure.optString("planner_failure")}"); ledger.plannerFailures++; return false
        }
        if (program.vocabulary.isNotEmpty()) { site.facetVocabulary.putAll(program.vocabulary); memory.saveSite(site) }
        memory.recordPlannerLabel(site.host, JsonObject().put("page", sps.pageType.name).put("reason", reason.take(80)).put("steps", program.steps.size).put("conf", program.confidence).put("at", clock()))
        if (program.needsHuman != null) {
            plannerOutcome(ledger,capability,"NEEDS_HUMAN")
            diagnostic(ledger, sps, null, null, null, DiagnosticCode.MODEL_UNCERTAIN)
            // Human-only pages are stopped before the planner is called. Its uncertainty
            // on an ordinary page is not proof of a sign-in wall and must not create an
            // indefinite account hold. End this attempt; the learner can retry later.
            ledger.status = if (ledger.verdicts.isEmpty()) TaskStatus.FAILED else TaskStatus.PARTIAL
            ledger.terminalReason = "planner could not interpret page; automatic learning retry available"
            ledger.humanReason = ""
            memory.saveLedger(ledger)
            return true
        }
        val proposalSignature=TeacherProposalFeedback.signature(program.steps,params,program.giveUp)
        if (TeacherProposalFeedback.matching(ledger,sps,capability,params).any { it.signature==proposalSignature }) {
            plannerOutcome(ledger,capability,if(program.giveUp)"GIVE_UP" else "ACCEPTED_HELD")
            return rejectTeacherProposal(ledger,sps,capability,params,program,quotaReservation,ProposalRejectionReason.DUPLICATE)
        }
        if (program.giveUp || program.steps.isEmpty() || program.confidence < config.plannerConfidenceFloor) {
            val rejection=if(program.giveUp)ProposalRejectionReason.GIVE_UP else if(program.steps.isEmpty())ProposalRejectionReason.EMPTY else ProposalRejectionReason.LOW_CONFIDENCE
            plannerOutcome(ledger,capability,rejection.name)
            return rejectTeacherProposal(ledger,sps,capability,params,program,quotaReservation,rejection)
        }
        // Execution accepts the requested query either literally or as a reusable parameter.
        // Other literal queries must not satisfy this task's search contract.
        val contractSteps = program.steps.map { step ->
            if (step.kind == com.appgate.brain.model.StepKind.TYPE && step.role == Role.SEARCH_BOX &&
                ledger.goal.query.isNotBlank() && step.arg?.equals(ledger.goal.query, true) == true) step.copy(arg = "\$query") else step
        }
        if (ledger.goal.intent == GoalIntent.LEARN_SITE && !com.appgate.brain.skills.PortableSkills.compatible(capability,
                contractSteps, program.steps.lastOrNull { !it.optional }?.expect.orEmpty())) {
            plannerOutcome(ledger,capability,"INCOMPATIBLE")
            return rejectTeacherProposal(ledger,sps,capability,params,program,quotaReservation,ProposalRejectionReason.INCOMPATIBLE)
        }
        // The selected live target scopes both negative feedback and execution. A teacher
        // cannot silently replace it or fall back to a generic listing click.
        val selectedItem = params["item"]
        val wrongItem = contractSteps.any { it.role == Role.RESULT_ITEM &&
            it.arg?.let { arg -> StepGrounder.substitute(arg, params) } != params["item"] }
        val unrelatedItemAction = capability == "open_item" && params["item"] != null &&
            (contractSteps.none { !it.optional && it.kind == StepKind.CLICK && it.role == Role.RESULT_ITEM } ||
                contractSteps.any { step -> when (step.kind) {
                    StepKind.WAIT -> step.role != null || !step.optional || step.arg != null
                    StepKind.CLICK -> step.role != Role.RESULT_ITEM
                    else -> true
                } })
        if ((selectedItem != null && wrongItem) || unrelatedItemAction) {
            plannerOutcome(ledger,capability,"INCOMPATIBLE")
            return rejectTeacherProposal(ledger,sps,capability,params,program,quotaReservation,ProposalRejectionReason.WRONG_TARGET)
        }
        if (!FailedStrategies.allowed(site,FailedStrategies.key(sps,capability,program.steps,params),clock())) {
            plannerOutcome(ledger,capability,"ACCEPTED_HELD")
            return rejectTeacherProposal(ledger,sps,capability,params,program,quotaReservation,ProposalRejectionReason.HELD_PROCEDURE)
        }
        ledger.attemptedSkills += capability
        startProgram(ledger, program.steps, params, "planner", program.rationale.ifBlank { "planner program" }, capability)
        plannerOutcome(ledger,capability,if(ledger.currentProgram.isEmpty() && ledger.done)"ACCEPTED_HELD" else "ACCEPTED_STARTED")
        return true
    }

    private fun missingStep(ledger: TaskLedger, sps: SemanticPageState, site: SiteModel, step: Step, reason: String) {
        if (ledger.goal.intent == GoalIntent.LEARN_SITE) events.guide(LearningGuide.snapshot(ledger, step))
        diagnostic(ledger, sps, null, step, VerifyStatus.FAILED, when (reason) {
            "repeat_state_limit" -> DiagnosticCode.REPEAT_STATE_LIMIT
            "outside_task_host" -> DiagnosticCode.OUTSIDE_TASK_HOST
            else -> DiagnosticCode.NO_TARGET
        })
        ledger.actions++
        ledger.consecutiveFailures++
        ledger.record(LedgerStep(clock(), ledger.phase, sps.hash, step.describe(), VerifyStatus.FAILED, reason, ledger.programSource))
        events.step(ledger, "cannot execute ${step.kind} ${step.role}: $reason", VerifyStatus.FAILED)
        recordFailureMemory(site, sps, step, "$reason:${sps.siteVersion}")
        finishProgram(ledger, sps, site, success = false, rememberFailure = reason == "no_target" && sps.settle == com.appgate.brain.model.Settle.IDLE)
    }

    private fun progress(ledger: TaskLedger, key: String, reason: String) {
        if (ProgressSupervisor.evidence(ledger, key)) events.progress(ledger, reason)
    }

    private val priorDiagnosticKeys=linkedMapOf<String,String>()
    private fun strategyComparison(ledger:TaskLedger,capability:String,key:String?):JsonObject {
        val out=JsonObject();if(key==null)return out
        val coordinate=ledger.host+"|"+SemanticDiagnostics.capability(capability)
        priorDiagnosticKeys[coordinate]?.let{out.put("strategy_key_same",if(it==key)1 else 0)}
        priorDiagnosticKeys[coordinate]=key
        while(priorDiagnosticKeys.size>64)priorDiagnosticKeys.remove(priorDiagnosticKeys.keys.first())
        return out
    }
    private fun diagnosticLearningOutcome(ledger:TaskLedger,site:SiteModel) {
        if(ledger.diagnosticOutcomeReported)return
        ledger.diagnosticOutcomeReported=true
        events.diagnostic(ledger.host, "learning_outcome", SemanticDiagnostics.journey(ledger).put("status", ledger.status.name)
            .put("lesson", SemanticDiagnostics.capability(ledger.lesson)).put("items", ledger.verdicts.size).put("inspected", ledger.itemsInspected)
            .put("teacher_attempts", ledger.llmCalls).put("verified_skills", ledger.successfulSkills.size).put("new_lessons", site.curriculum.count { it.done && it.id !in lessonsBefore })
            .put("lessons_complete", site.curriculum.count { it.done }).put("lessons_total", site.curriculum.size))
    }
    private fun diagnosticTaskEnd(ledger:TaskLedger) {
        if(ledger.diagnosticEnded)return
        ledger.diagnosticEnded=true
        events.diagnostic(ledger.host,"task_lifecycle",SemanticDiagnostics.journey(ledger).put("status",ledger.status.name).put("lesson",SemanticDiagnostics.capability(ledger.lesson)))
    }
    private fun plannerOutcome(ledger:TaskLedger,capability:String,outcome:String,details:JsonObject?=null) {
        events.diagnostic(ledger.host,"planner_outcome",SemanticDiagnostics.journey(ledger).also{data->details?.keys?.forEach{key->data.put(key,details[key])}}.put("planner_outcome",outcome)
            .put("capability",SemanticDiagnostics.capability(capability)).put("lesson",SemanticDiagnostics.capability(ledger.lesson)))
    }
    private val memoryDiagnosticSites = mutableSetOf<String>()

    private fun memoryCheckpoint(ledger: TaskLedger, code: String, capability: String = ledger.programCapability,
                                 page: SemanticPageState? = null, extra: JsonObject = JsonObject()) {
        val data = memory.diagnosticSnapshot(ledger.host).also{data->SemanticDiagnostics.journey(ledger).let{j->j.keys.forEach{data.put(it,j[it])}}}.put("code", code)
            .put("capability", SemanticDiagnostics.capability(capability))
            .put("lesson", SemanticDiagnostics.capability(ledger.lesson)).put("page_type", page?.pageType?.name)
        extra.keys.forEach { data.put(it, extra[it]) }
        events.diagnostic(ledger.host, "memory_checkpoint", data)
    }

    private fun diagnostic(ledger: TaskLedger, before: SemanticPageState, after: SemanticPageState?, step: Step?, status: VerifyStatus?, code: DiagnosticCode, details: JsonObject? = null) {
        val data=SemanticDiagnostics.action(ledger, before, after, step, status, code)
        details?.keys?.forEach { data.put(it,details[it]) }
        events.diagnostic(ledger.host, "action_outcome", data)
    }

    private fun verifiedChange(action: Action, before: SemanticPageState, after: SemanticPageState): Boolean {
        fun changed(condition: Postcondition): Boolean = when (condition) {
            is Postcondition.AnyOf -> condition.alternatives.any { changed(it) }
            else -> Verifier.holds(condition, action, before, after, emptySet(), mutableListOf()) &&
                !Verifier.holds(condition, action, before, before, emptySet(), mutableListOf())
        }
        return action.expect.any { changed(it) }
    }

    private fun evidenceKey(sps: SemanticPageState): String = Hashing.short("${sps.hash}|${sps.url}|${sps.textLength}|${sps.detailText}|${sps.scrollY}")

    private fun allowedUrl(ledger: TaskLedger, url: String, profile: SiteProfile): Boolean {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        if (uri.scheme !in setOf("http", "https")) return false
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return false
        val allowed = (profile.hosts + profile.navigationHosts + ledger.host).map { it.lowercase().removePrefix("www.") }
        return allowed.any { host == it || host.endsWith(".$it") }
    }

    private fun acceptPage(ledger: TaskLedger, sps: SemanticPageState, profile: SiteProfile): Boolean {
        if (allowedUrl(ledger, sps.url, profile) && UrlPatterns.host(sps.url).removePrefix("www.") == sps.host.removePrefix("www.")) return true
        if (ledger.neutralScrollOnly != null) ledger.neutralScrollOnly = false
        // An incidental click or Back during training can leave the task site. There is
        // nothing for the user to solve there; stop this lesson without exploring it.
        if (ledger.goal.intent == GoalIntent.LEARN_SITE && (ledger.actions > 0 || !sps.isHumanOnly)) {
            diagnostic(ledger, latestPage ?: emptySps(ledger.host), sps, ledger.currentProgram.getOrNull(ledger.cursor), VerifyStatus.FAILED, DiagnosticCode.UNEXPECTED_HOST)
            ledger.status = if (ledger.verdicts.isNotEmpty()) TaskStatus.PARTIAL else TaskStatus.FAILED
            ledger.terminalReason = "left task site during training"
            return false
        }
        diagnostic(ledger, latestPage ?: emptySps(ledger.host), sps, ledger.currentProgram.getOrNull(ledger.cursor), VerifyStatus.HUMAN_NEEDED, DiagnosticCode.UNEXPECTED_HOST)
        ledger.status = TaskStatus.NEED_HUMAN
        ledger.terminalReason = "unexpected host"
        ledger.humanReason = "The browser left this task's site. Review the page before continuing."
        events.needHuman(ledger, ledger.humanReason, sps.url)
        return false
    }

    private fun capabilityFor(ledger: TaskLedger): String = when (ledger.phase) {
        TaskPhase.START, TaskPhase.SEARCH -> if (ledger.goal.intent == GoalIntent.CUSTOM) "custom" else "search"
        TaskPhase.CONSTRAIN -> "constrain_numeric"
        TaskPhase.COLLECT -> "load_more"
        TaskPhase.INSPECT -> "open_item"
        else -> "custom"
    }

    private fun rejectTeacherProposal(ledger: TaskLedger, page: SemanticPageState,
        capability: String, params: Map<String,String>, program: com.appgate.brain.planner.PlannerProgram,
        reservation: com.appgate.brain.model.StrategyFailure?, reason: ProposalRejectionReason): Boolean {
        ledger.note("teacher proposal rejected: ${reason.name}")
        plannerOutcome(ledger,capability,"PROPOSAL_REJECTED",JsonObject().put("proposal_rejection",reason.name))
        if (ledger.goal.intent != GoalIntent.LEARN_SITE) return false
        val quotaKey=FailedStrategies.key(page,capability)
        val quota=reservation ?: return false
        val row=TeacherProposalRejection(TeacherProposalFeedback.context(page,capability,params),
            TeacherProposalFeedback.signature(program.steps,params,program.giveUp),reason,
            FailedStrategies.approach(page,capability,program.steps,params)?.steps.orEmpty(),
            params["item"]?.let { com.appgate.brain.util.Hashing.short(it) },quotaKey,quota.count,quota.at)
        val safe=TeacherProposalRejection.fromJson(row.toJson()) ?: return false
        ledger.proposalRejections += safe
        while (ledger.proposalRejections.size>4) ledger.proposalRejections.removeAt(0)
        val retry=!ledger.proposalCorrectionUsed && !ledger.blocked &&
            ledger.llmCalls<ledger.goal.budget.llmCalls && ledger.plannerCallsOnSameState<config.maxPlannerCallsSameState
        ledger.proposalCorrectionPending=retry
        if (retry) {
            ledger.programCapability=capability
            ledger.repairReason="revise rejected teacher proposal: ${reason.name}"
        }
        memory.saveLedger(ledger)
        return retry
    }

    private fun skillParams(ledger: TaskLedger): Map<String, String> = buildMap {
        put("query", ledger.goal.query)
        ledger.currentItem?.let { put("item", it) }
        val policy = TaskPolicy(SiteProfiles.generic(ledger.host), memory.site(ledger.host))
        ledger.effectiveGoal.constraints.forEach { c -> policy.facetKeyFor(c)?.let { put(it, c.value) } }
    }

    private fun profileFor(ledger: TaskLedger) = SiteProfiles.forHost(ledger.host) ?: SiteProfiles.generic(ledger.host)

    private fun learningDecision(ledger: TaskLedger, sps: SemanticPageState): PolicyDecision? {
        if (ledger.goal.intent != GoalIntent.LEARN_SITE || ledger.lesson.isBlank()) return null
        if (ledger.lesson in ledger.successfulSkills) return PolicyDecision.Finish(TaskStatus.DONE, "lesson verified: ${ledger.lesson}")
        val target = learningTarget(ledger, sps, memory.site(ledger.host))
            ?: return PolicyDecision.Finish(TaskStatus.PARTIAL, "lesson control not available on this page; waiting for an opportunity")
        if (target.constraints.isNotEmpty() && ledger.effectiveGoal.filterable.isEmpty()) ledger.learningConstraints = target.constraints
        val key = "__lesson_target:${target.skillId}"
        // Discovery must reach a footer within this visit: rotating to another source
        // reloads results. Movement is verified on every scroll; overall task budgets
        // still bound the work, while other lesson/repair targets retain two attempts.
        val targetLimit = if (target.skillId == "seek_pagination") 12 else 2
        if ((ledger.constraintAttempts[key] ?: 0) >= targetLimit) return PolicyDecision.Finish(TaskStatus.PARTIAL, "lesson attempt complete; no new verified result")
        ledger.constraintAttempts[key] = (ledger.constraintAttempts[key] ?: 0) + 1
        if (target.skillId == "open_item" && sps.pageType == PageType.RESULTS) ledger.resultsUrl = sps.url
        if (target.skillId == "go_back" && ledger.resultsUrl.isBlank())
            return PolicyDecision.Finish(TaskStatus.PARTIAL, "return lesson needs a known results page")
        // Keep an already-chosen bound while a drawer is pending; do not choose a new
        // numeric value merely because typing changed the control before Apply verified.
        val params = target.params.toMutableMap()
        target.constraints.firstOrNull()?.let { proposed ->
            ledger.effectiveGoal.filterable.firstOrNull { it.key == proposed.key && it.op == proposed.op }?.let { requested ->
                params["value"] = requested.value
            }
        }
        return PolicyDecision.RunSkill(target.skillId, skillParams(ledger) + params, "practice ${ledger.lesson}")
    }

    private fun tryLocalRepair(ledger: TaskLedger, sps: SemanticPageState, site: SiteModel, capability: String): Boolean {
        val plan = LocalRecoveryPlanner.propose(ledger, sps, site, capability) ?: return false
        // Discovery executes as open_filters, so its preflight must use the same
        // capability and bindings as startProgram. A held discovery should leave
        // the bounded teacher opportunity available for a different procedure.
        val actualCapability = if (plan.completesCapability) capability else "open_filters"
        val params = skillParams(ledger) + plan.params
        val key = FailedStrategies.key(sps, actualCapability, plan.steps, params)
        val attempt = "__local:$key"
        if ((ledger.constraintAttempts[attempt] ?: 0) >= 1 || !FailedStrategies.allowed(site, key, clock())) return false
        ledger.constraintAttempts[attempt] = 1
        ledger.constraintAttempts.keys.removeAll { it.startsWith("__discovery:") }
        // A canonical continuation survives pause/process resume without persisting page values.
        if (!plan.completesCapability) ledger.constraintAttempts["__discovery:$capability"] = 1
        ledger.attemptedSkills += actualCapability
        startProgram(ledger, plan.steps, params, "local", "local repair: $capability", actualCapability, plan.postconditions)
        return true
    }

    /** Cheap exploration when no planner is available: scroll, close dialogs, or return to results. */
    private fun exploratoryFallback(ledger: TaskLedger, sps: SemanticPageState): Boolean {
        if ((ledger.constraintAttempts["__explore"] ?: 0) >= 3) return false
        ledger.constraintAttempts["__explore"] = (ledger.constraintAttempts["__explore"] ?: 0) + 1
        return when {
            sps.dialogOpen && !LearningOpportunities.dialogHoldsFilters(sps) && sps.has(Role.CLOSE) -> { startProgram(ledger, memory.skills.get("dismiss_dialog")!!.body, emptyMap(), "skill:dismiss_dialog", "explore: close dialog"); true }
            ledger.resultsUrl.isNotBlank() && sps.url != ledger.resultsUrl -> { startProgram(ledger, listOf(Step(com.appgate.brain.model.StepKind.NAVIGATE, arg = ledger.resultsUrl, expect = listOf(Postcondition.UrlChanged))), emptyMap(), "navigate", "explore: back to results"); true }
            sps.pageType == PageType.RESULTS -> { startProgram(ledger, memory.skills.get("scroll_results")!!.body, emptyMap(), "skill:scroll_results", "explore: scroll"); true }
            else -> false
        }
    }

    // ------------------------------------------------------------------ renderer health

    private fun observeOrRecover(executor: Executor, ledger: TaskLedger): SemanticPageState? {
        repeat(2) { attempt ->
            try {
                return executor.observe(clock())
            } catch (t: RendererTimeout) {
                handleRendererFailure(ledger, "observe timeout", true)
            } catch (t: RendererGone) {
                handleRendererFailure(ledger, "renderer gone", true)
            } catch (t: Exception) {
                if (ledger.goal.intent == GoalIntent.LEARN_SITE && ledger.lesson == "scroll_results") ledger.neutralScrollOnly = false
                ledger.note("observe error: ${t.message?.take(100)}")
                events.log("warn", "observe error: $t")
                Thread.sleep(800)
            }
            if (ledger.done) return null
        }
        ledger.status = TaskStatus.FAILED
        ledger.note("renderer unresponsive")
        return null
    }

    private fun handleRendererFailure(ledger: TaskLedger, reason: String, timeout: Boolean) {
        ledger.timeouts++
        ledger.note("renderer failure: $reason")
        events.log("warn", "renderer failure ($reason); recovering")
        if (ledger.timeouts > config.maxTimeouts) { ledger.status = TaskStatus.FAILED; return }
        runCatching { renderer.recover() }
        Thread.sleep(700)
        val target = ledger.lastCheckpointUrl.ifBlank { ledger.startUrl }
        runCatching { renderer.navigate(target, 20_000L); renderer.waitSettle(10_000L) }
    }

    private fun pace(profile: SiteProfile) {
        val base = config.pacingOverrideMs ?: profile.minActionIntervalMs
        if (base <= 0L) return
        val jitter = (Math.random() * 400).toLong()
        Thread.sleep(base + jitter)
    }

    private fun emptySps(host: String) = SemanticPageState(host, "", "", "", PageType.UNKNOWN, 0.0, emptyList(), emptyList(), emptyList(), emptyMap(), com.appgate.brain.model.Settle.UNKNOWN, false, false, false, "")
}
