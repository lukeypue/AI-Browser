package com.appgate.brain.engine

import com.appgate.brain.goal.GoalParser
import com.appgate.brain.json.Json
import com.appgate.brain.json.JsonObject
import com.appgate.brain.memory.InMemoryStorage
import com.appgate.brain.memory.Memory
import com.appgate.brain.model.*
import com.appgate.brain.test.FakeSite
import org.junit.Assert.*
import org.junit.Test

class StaleRefusalTest {
    private val now = 1_800_000_000_000L
    private val search = Step(StepKind.TYPE, Role.SEARCH_BOX, arg = "Ford Expedition", submit = true,
        expect = listOf(Postcondition.PageTypeIs(PageType.RESULTS)))

    private fun run(renderer: Renderer, step: Step = search, source: String = "local", grants: List<Grant> = emptyList()): TaskLedger {
        val goal = GoalParser.parse("Ford Expedition", Budget(actions = 3, llmCalls = 0, wallMs = 10000)).copy(intent = GoalIntent.CUSTOM)
        val task = TaskLedger("stale", goal, "fake.market", renderer.currentUrl()).apply {
            currentProgram = listOf(step); programSource = source; programCapability = "custom"
            programPost = step.expect; previewHash = "preview"; this.grants += grants
            lastCheckpointUrl = renderer.currentUrl()
        }
        return BrainEngine(renderer, Memory(InMemoryStorage()) { now }, { null }, object : EngineEvents {},
            EngineConfig(pacingOverrideMs = 0, ambiguousRecheckMs = 0, idleSleepMs = 0,
                maxConsecutiveFailures = 1)) { now }.runTask(task)
    }

    @Test fun staleDocumentReobservesAndMayReuseIdsFromTheNewDocument() {
        val fake = FakeSite().apply { dialogShown = false }
        val ids = mutableListOf<String>()
        val renderer = object : Renderer by fake {
            override fun act(command: JsonObject, timeoutMs: Long): RendererResult {
                if (command.optString("cmd") != "type") return fake.act(command, timeoutMs)
                ids += command.optString("id")
                return if (ids.size == 1) RendererResult(false, "STALE_DOCUMENT") else fake.act(command, timeoutMs)
            }
        }
        val out = run(renderer)
        assertEquals(listOf("a0", "a0"), ids)
        assertTrue(out.successfulSkills.contains("custom"))
        assertTrue(out.steps.any { it.status == VerifyStatus.FAILED && it.evidence.contains("STALE_DOCUMENT") })
    }

    @Test fun persistentStaleDocumentsStopAfterTheBoundedRetryBudget() {
        val fake = FakeSite().apply { dialogShown = false }
        var calls = 0
        val renderer = object : Renderer by fake {
            override fun act(command: JsonObject, timeoutMs: Long): RendererResult {
                calls++; return RendererResult(false, "STALE_DOCUMENT")
            }
        }
        val out = run(renderer)
        assertEquals(3, calls)
        assertTrue(out.done || out.blocked)
        assertFalse(out.successfulSkills.contains("custom"))
        assertEquals(0, out.llmCalls)
    }

    @Test fun sameDocumentItemRetryPreservesListingIdentity() {
        val fake = FakeSite().apply { dialogShown = false; query = "Ford Expedition"; url = "https://fake.market/search?q=Ford" }
        var refused = false
        var oldId = ""
        val attempted = mutableListOf<String>()
        val renderer = object : Renderer by fake {
            override fun observe(timeoutMs: Long): String {
                val raw = Json.parseObject(fake.observe(timeoutMs))
                if (refused) {
                    raw.optArray("elements")!!.objects().firstOrNull { it.optString("itemKey") == "k1" }
                        ?.put("id", "replacement")
                    raw.optArray("items")!!.objects().firstOrNull { it.optString("key") == "k1" }
                        ?.put("aff", "replacement")
                }
                return raw.toString()
            }
            override fun act(command: JsonObject, timeoutMs: Long): RendererResult {
                attempted += command.optString("id")
                if (!refused) { refused = true; oldId = command.optString("id"); return RendererResult(false, "STALE_TARGET") }
                assertEquals("replacement", command.optString("id"))
                return fake.act(Json.parseObject(command.toString()).put("id", oldId), timeoutMs)
            }
        }
        val out = run(renderer, Step(StepKind.CLICK, Role.RESULT_ITEM, arg = "k1",
            expect = listOf(Postcondition.PageTypeIs(PageType.DETAIL))))
        assertEquals(2, attempted.size)
        assertEquals("https://fake.market/item/1", fake.url)
        assertTrue(out.successfulSkills.contains("custom"))
    }

    @Test fun refreshToAuthenticationOrAnotherHostCannotReplayTheAction() {
        for (redirect in listOf(false, true)) {
            val fake = FakeSite().apply { dialogShown = false }
            var calls = 0
            val renderer = object : Renderer by fake {
                override fun observe(timeoutMs: Long): String {
                    val raw = Json.parseObject(fake.observe(timeoutMs))
                    if (redirect && calls > 0) raw.put("host", "other.market").put("url", "https://other.market/")
                    return raw.toString()
                }
                override fun act(command: JsonObject, timeoutMs: Long): RendererResult {
                    calls++; if (!redirect) fake.loginWall = true
                    return RendererResult(false, "STALE_DOCUMENT")
                }
            }
            val out = run(renderer)
            assertEquals(1, calls)
            assertTrue(out.done || out.blocked)
            assertFalse(out.successfulSkills.contains("custom"))
        }
    }

    @Test fun staleCommitRefusalDoesNotRetryEvenWithAValidGrant() {
        val fake = FakeSite().apply {
            dialogShown = false; url = "https://fake.market/item/4"; composerOpen = true; composerText = "Hello"
        }
        var calls = 0
        val renderer = object : Renderer by fake {
            override fun act(command: JsonObject, timeoutMs: Long): RendererResult {
                calls++; return RendererResult(false, "STALE_DOCUMENT")
            }
        }
        val grant = Grant("g", "stale", fake.host, Role.SEND, "preview", now)
        val out = run(renderer, Step(StepKind.CLICK, Role.SEND), "planner", listOf(grant))
        assertEquals(1, calls)
        assertTrue(grant.used)
        assertEquals(0, fake.sent)
        assertFalse(out.successfulSkills.contains("custom"))
    }
}
