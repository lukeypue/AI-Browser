package com.appgate.brain.engine

import com.appgate.brain.json.*
import com.appgate.brain.memory.*
import com.appgate.brain.model.*
import com.appgate.brain.perception.SpsParser
import com.appgate.brain.test.FakeSite
import com.appgate.brain.skills.BuiltinSkills
import org.junit.Assert.*
import org.junit.Test

class LearningIntegrityUpdateTest {
    private val initial = 1_800_000_000_000L

    @Test fun unchangedTeacherFailuresBackOffAcrossFreshMemoryInstances() {
        val storage = InMemoryStorage()
        val page = SpsParser().parse(FakeSite().apply { dialogShown = false }.observe(1000), initial)
        val key = FailedStrategies.key(page, "search")
        val memory = Memory(storage) { initial }
        repeat(2) { FailedStrategies.record(memory.site(page.host), key, false, initial) }
        memory.saveSite(memory.site(page.host))
        var admitted = 0
        for (cycle in 1..10) {
            val now = initial + cycle * 30_000L
            val restored = Memory(storage) { now }
            val site = restored.site(page.host)
            if (FailedStrategies.repairAllowed(site, key, now)) {
                admitted++
                FailedStrategies.record(site, key, false, now)
                restored.saveSite(site)
            }
        }
        assertEquals("unchanged failures must not spend at every site probe", 3, admitted)
        val changed = page.copy(affordances = page.affordances + Affordance("new", Role.SUBMIT, tag = "button"))
        val differentKey = FailedStrategies.key(changed, "search")
        assertNotEquals(key, differentKey)
        assertTrue(FailedStrategies.repairAllowed(Memory(storage).site(page.host), differentKey, initial + 300_001L))
        assertFalse(FailedStrategies.allowed(Memory(storage).site(page.host), key, initial + 300_001L))
    }

    @Test fun scrollingToTheEndWithoutNewListingsDoesNotTeachLoading() {
        terminalScenario(resumed = false)
    }

    @Test fun resumedScrollAtTheEndDoesNotPoisonAProcedureOrAskForRepair() {
        terminalScenario(resumed = true)
    }

    private fun terminalScenario(resumed: Boolean) {
        val fake = FakeSite().apply { dialogShown = false; navigate("https://fake.market/search?q=Ford", 1000) }
        var scrolled = false
        val renderer = object : Renderer by fake {
            override fun observe(timeoutMs: Long): String {
                val raw = Json.parseObject(fake.observe(timeoutMs))
                raw.put("elements", JsonArray(raw.optArray("elements")!!.objects().filterNot { it.optString("rel") == "next" }))
                raw.put("viewport", JsonObject().put("w", 400).put("h", 400).put("scrollH", 2000).put("scrollY", if (scrolled || resumed) 1600 else 0))
                return raw.toString()
            }
            override fun act(command: JsonObject, timeoutMs: Long): RendererResult {
                if (command.optString("cmd") == "scroll") { scrolled = true; return RendererResult(true, "scrolled") }
                return fake.act(command, timeoutMs)
            }
        }
        val memory = Memory(InMemoryStorage()) { initial }
        val events = mutableListOf<VerifyStatus?>()
        val config = EngineConfig(pacingOverrideMs = 0, plannerCooldownMs = 0, ambiguousRecheckMs = 0, idleSleepMs = 0)
        val goal = Goal("end", GoalIntent.LEARN_SITE, "practice scrolling", "Ford", emptyList(), budget = Budget(actions = 4, llmCalls = 0, wallMs = 10000))
        val skillBefore = memory.skills.get("scroll_results")!!.stat(fake.host)
        val out = BrainEngine(renderer, memory, { null }, object : EngineEvents {
            override fun step(ledger: TaskLedger, description: String, status: VerifyStatus?) { events += status }
        }, config) { initial }.runTask(TaskLedger("end", goal, fake.host, fake.url).apply {
            lesson = "scroll_results"; lastCheckpointUrl = fake.url; resultsUrl = fake.url
            if (resumed) {
                currentProgram = BuiltinSkills.scrollResults.body; programSource = "skill:scroll_results"
                programCapability = "scroll_results"; programPost = BuiltinSkills.scrollResults.post
            }
        })
        assertTrue(scrolled)
        assertTrue("end-of-list remains a valid atomic state", VerifyStatus.VERIFIED in events)
        assertFalse("no new listings means no new loading skill", "scroll_results" in out.successfulSkills)
        assertFalse(memory.site(fake.host).curriculum.single { it.id == "scroll_results" }.done)
        assertTrue("ordinary end-of-list must not poison learned procedures", memory.site(fake.host).strategyFailures.isEmpty())
        assertEquals(skillBefore, memory.skills.get("scroll_results")!!.stat(fake.host))
        assertEquals(0, out.llmCalls)
        assertEquals("", out.repairReason)
    }
}
