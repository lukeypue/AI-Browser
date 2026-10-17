package com.appgate.brain.engine

import com.appgate.brain.json.Json
import com.appgate.brain.json.JsonArray
import com.appgate.brain.skills.BuiltinSkills
import com.appgate.brain.json.JsonObject
import com.appgate.brain.memory.*
import com.appgate.brain.model.*
import com.appgate.brain.perception.SpsParser
import com.appgate.brain.test.FakeSite
import org.junit.Assert.*
import org.junit.Test

class DurableFailureMemoryTest {
    private val initial = 1_800_000_000_000L
    private val config = EngineConfig(pacingOverrideMs = 0, plannerCooldownMs = 0, ambiguousRecheckMs = 0, idleSleepMs = 0)

    @Test fun oneFailedProcedureStaysBlockedAcrossReloadTimeAndClockRollback() {
        val storage = InMemoryStorage()
        val memory = Memory(storage) { initial }
        val fake = FakeSite().apply { dialogShown = false }
        val page = SpsParser().parse(fake.observe(1000), initial)
        val steps = memory.skills.get("search")!!.body
        val key = FailedStrategies.key(page, "search", steps)
        FailedStrategies.record(memory.site(fake.host), key, false, initial)
        memory.saveSite(memory.site(fake.host))
        for (now in listOf(initial, initial + 7 * 86_400_000L, initial - 86_400_000L)) {
            val restored = Memory(storage) { now }.site(fake.host)
            assertFalse("an unchanged failed procedure must not reopen just because time changed", FailedStrategies.allowed(restored, key, now))
        }
    }

    @Test fun differentProcedureOrRelevantControlsRemainEligibleAfterOneFailure() {
        val fake = FakeSite().apply { dialogShown = false }
        val memory = Memory(InMemoryStorage()) { initial }
        val page = SpsParser().parse(fake.observe(1000), initial)
        val steps = memory.skills.get("search")!!.body
        val site = memory.site(fake.host)
        FailedStrategies.record(site, FailedStrategies.key(page, "search", steps), false, initial)
        assertFalse(FailedStrategies.allowed(site, FailedStrategies.key(page, "search", steps), initial))
        val alternate = listOf(Step(StepKind.TYPE, Role.SEARCH_BOX, arg = "${'$'}query", submit = false), Step(StepKind.CLICK, Role.SUBMIT))
        assertTrue(FailedStrategies.allowed(site, FailedStrategies.key(page, "search", alternate), initial))
        val changed = page.copy(affordances = page.affordances + Affordance("new-submit", Role.SUBMIT, tag = "a"))
        assertTrue(FailedStrategies.allowed(site, FailedStrategies.key(changed, "search", steps), initial))
    }

    @Test fun ordinaryAppTaskRemembersFailedProceduresAcrossNewEngineAndDay() {
        val storage = InMemoryStorage()
        var now = initial
        fun attempt(id: String): Pair<Int, Memory> {
            val fake = FakeSite().apply { dialogShown = false }
            var acts = 0
            val renderer = object : Renderer by fake {
                override fun act(command: JsonObject, timeoutMs: Long): RendererResult {
                    acts++
                    return RendererResult(true, "synthetic action with no effect")
                }
            }
            val memory = Memory(storage) { now }
            val goal = Goal(id, GoalIntent.FIND_LISTINGS, "synthetic search", "Ford", emptyList(), budget = Budget(actions = 8, llmCalls = 0, wallMs = 10000))
            val ledger = TaskLedger(id, goal, fake.host, fake.url).apply { lastCheckpointUrl = fake.url }
            BrainEngine(renderer, memory, { null }, object : EngineEvents {}, config) { now }.runTask(ledger)
            return acts to memory
        }
        val first = attempt("first")
        assertTrue("fixture must actually execute and fail", first.first > 0)
        assertTrue("ordinary app tasks must persist procedure failures", first.second.site("fake.market").strategyFailures.isNotEmpty())
        now += 86_400_000L
        val second = attempt("after-restart")
        assertEquals("a new task/engine/day must not replay known failed actions", 0, second.first)
    }

    @Test fun ordinaryTaskResumedAtEndDoesNotBanSoundScrolling() {
        val fake = FakeSite().apply { dialogShown = false; navigate("https://fake.market/search?q=Ford", 1000) }
        var scrolled = false
        val renderer = object : Renderer by fake {
            override fun observe(timeoutMs: Long): String {
                val raw = Json.parseObject(fake.observe(timeoutMs))
                raw.put("elements", JsonArray(raw.optArray("elements")!!.objects().filterNot { it.optString("rel") == "next" }))
                raw.put("viewport", JsonObject().put("w", 400).put("h", 400).put("scrollH", 2000).put("scrollY", 1600))
                return raw.toString()
            }
            override fun act(command: JsonObject, timeoutMs: Long): RendererResult {
                if (command.optString("cmd") == "scroll") { scrolled = true; return RendererResult(true) }
                return fake.act(command, timeoutMs)
            }
        }
        val memory = Memory(InMemoryStorage()) { initial }
        val goal = Goal("end", GoalIntent.FIND_LISTINGS, "synthetic search", "Ford", emptyList(), budget = Budget(actions = 4, llmCalls = 0, wallMs = 10000))
        val ledger = TaskLedger("end", goal, fake.host, fake.url).apply {
            lastCheckpointUrl = fake.url; resultsUrl = fake.url
            currentProgram = BuiltinSkills.scrollResults.body; programSource = "skill:scroll_results"
            programCapability = "scroll_results"; programPost = BuiltinSkills.scrollResults.post
        }
        BrainEngine(renderer, memory, { null }, object : EngineEvents {}, config) { initial }.runTask(ledger)
        assertTrue("fixture must physically try scroll", scrolled)
        assertTrue("end-of-results is not a broken procedure", memory.site(fake.host).strategyFailures.isEmpty())
    }

    @Test fun transientRendererOrBusyObservationDoesNotPermanentlyBanProcedure() {
        for (issue in listOf("STALE_DOCUMENT", "timeout", "busy")) {
            val fake = FakeSite().apply { dialogShown = false }
            val renderer = object : Renderer by fake {
                override fun observe(timeoutMs: Long): String {
                    if (issue != "busy") return fake.observe(timeoutMs)
                    return Json.parseObject(fake.observe(timeoutMs)).put("elements", JsonArray())
                        .put("settle", JsonObject().put("state", "BUSY")).toString()
                }
                override fun act(command: JsonObject, timeoutMs: Long): RendererResult {
                    if (issue == "timeout") throw RendererTimeout("synthetic timeout")
                    return RendererResult(false, issue)
                }
            }
            val memory = Memory(InMemoryStorage()) { initial }
            val goal = Goal(issue, GoalIntent.FIND_LISTINGS, "synthetic search", "Ford", emptyList(), budget = Budget(actions = 4, llmCalls = 0, wallMs = 10000))
            val ledger = TaskLedger(issue, goal, fake.host, fake.url).apply {
                lastCheckpointUrl = fake.url
                currentProgram = BuiltinSkills.search.body.map { it.copy(arg = it.arg?.replace("\$query", "Ford")) }; programSource = "skill:search"
                programCapability = "search"; programPost = BuiltinSkills.search.post
            }
            BrainEngine(renderer, memory, { null }, object : EngineEvents {}, config) { initial }.runTask(ledger)
            assertTrue("transient $issue must not become a durable failed procedure", memory.site(fake.host).strategyFailures.isEmpty())
        }
    }

    @Test fun changedScrollDistanceAndDifferentListingAreDifferentApproaches() {
        val fake = FakeSite().apply { dialogShown = false; navigate("https://fake.market/search?q=Ford", 1000) }
        val page = SpsParser().parse(fake.observe(1000), initial)
        val short = listOf(Step(StepKind.SCROLL, arg = "900"))
        val long = listOf(Step(StepKind.SCROLL, arg = "1800"))
        assertNotEquals(FailedStrategies.key(page, "scroll_results", short), FailedStrategies.key(page, "scroll_results", long))
        val waitImplicit = listOf(Step(StepKind.WAIT))
        val waitExplicit = listOf(Step(StepKind.WAIT, arg = "1500"))
        assertEquals("explicit default timing is the same approach", FailedStrategies.key(page, "search", waitImplicit), FailedStrategies.key(page, "search", waitExplicit))
        val targetA = listOf(Step(StepKind.CLICK, Role.RESULT_ITEM, arg = "synthetic-listing-A"))
        val targetB = listOf(Step(StepKind.CLICK, Role.RESULT_ITEM, arg = "synthetic-listing-B"))
        assertNotEquals(FailedStrategies.key(page, "open_item", targetA), FailedStrategies.key(page, "open_item", targetB))
        val site = SiteModel(fake.host)
        FailedStrategies.record(site, FailedStrategies.key(page, "open_item", targetA), false, initial)
        val stored = site.toJson().toString()
        assertFalse(stored.contains("synthetic-listing-A"))
        assertFalse(stored.contains("Ford"))
        assertFalse(stored.contains("https://"))
    }

    @Test fun physicalScrollTowardFooterIsNeutralUntilNewResultsOrEnd() {
        val fake = FakeSite().apply { dialogShown = false; navigate("https://fake.market/search?q=Ford", 1000) }
        var y = 0
        val renderer = object : Renderer by fake {
            override fun observe(timeoutMs: Long): String = Json.parseObject(fake.observe(timeoutMs))
                .put("viewport", JsonObject().put("w", 400).put("h", 400).put("scrollH", 20000).put("scrollY", y)).toString()
            override fun act(command: JsonObject, timeoutMs: Long): RendererResult {
                if (command.optString("cmd") == "scroll") { y += 300; return RendererResult(true) }
                return fake.act(command, timeoutMs)
            }
        }
        val memory = Memory(InMemoryStorage()) { initial }
        val goal = Goal("movement", GoalIntent.LEARN_SITE, "synthetic scrolling", "Ford", emptyList(), budget = Budget(actions = 4, llmCalls = 0, wallMs = 10000))
        val ledger = TaskLedger("movement", goal, fake.host, fake.url).apply { lesson = "scroll_results"; lastCheckpointUrl = fake.url; resultsUrl = fake.url }
        BrainEngine(renderer, memory, { null }, object : EngineEvents {}, config) { initial }.runTask(ledger)
        assertTrue(y > 0)
        assertTrue("physical navigation without new results must not ban a procedure", memory.site(fake.host).strategyFailures.isEmpty())
        assertFalse("movement alone must not award mastery", "scroll_results" in ledger.successfulSkills)
    }

    @Test fun transientRefusalDoesNotQuarantineAnAlreadyLearnedSkill() {
        val fake = FakeSite().apply { dialogShown = false }
        val memory = Memory(InMemoryStorage()) { initial }
        val learned = BuiltinSkills.search.copy(id = "synthetic-learned-search", origin = SkillOrigin.COMPILED,
            tags = setOf("verified_v2", "capability:search"), statsByHost = mapOf(fake.host to BetaStat(successes = 3.0)))
        memory.skills.put(learned)
        val renderer = object : Renderer by fake {
            override fun act(command: JsonObject, timeoutMs: Long) = RendererResult(false, "STALE_DOCUMENT")
        }
        val goal = Goal("stale-learned", GoalIntent.FIND_LISTINGS, "synthetic search", "Ford", emptyList(), budget = Budget(actions = 4, llmCalls = 0, wallMs = 10000))
        val ledger = TaskLedger(goal.id, goal, fake.host, fake.url).apply {
            lastCheckpointUrl = fake.url
            currentProgram = learned.body.map { it.copy(arg = it.arg?.replace("\$query", "Ford")) }; programSource = "skill:" + learned.id
            programCapability = "search"; programPost = learned.post
        }
        BrainEngine(renderer, memory, { null }, object : EngineEvents {}, config) { initial }.runTask(ledger)
        assertEquals("an inconclusive renderer outcome must not quarantine learned skill", 0.0, memory.skills.get(learned.id)!!.stat(fake.host).failures, 0.0)
    }

    @Test fun fileBackedFailureMemorySurvivesFreshStorageInstance() {
        val dir = java.nio.file.Files.createTempDirectory("synthetic-failure-memory-").toFile()
        try {
            val first = Memory(FileBrainStorage(dir)) { initial }
            val fake = FakeSite().apply { dialogShown = false }
            val page = SpsParser().parse(fake.observe(1000), initial)
            val key = FailedStrategies.key(page, "search", BuiltinSkills.search.body)
            FailedStrategies.record(first.site(fake.host), key, false, initial)
            first.saveSite(first.site(fake.host))
            val second = Memory(FileBrainStorage(dir)) { initial + 30 * 86_400_000L }
            assertFalse(FailedStrategies.allowed(second.site(fake.host), key, initial + 30 * 86_400_000L))
        } finally { dir.deleteRecursively() }
    }
}
