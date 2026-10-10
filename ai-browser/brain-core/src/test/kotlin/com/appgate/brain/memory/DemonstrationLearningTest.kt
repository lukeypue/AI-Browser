package com.appgate.brain.memory

import com.appgate.brain.engine.*
import com.appgate.brain.model.*
import com.appgate.brain.json.*
import com.appgate.brain.skills.SkillCompiler
import com.appgate.brain.perception.SpsParser
import com.appgate.brain.test.FakeSite
import org.junit.Assert.*
import org.junit.Test

class DemonstrationLearningTest {
    private fun page() = SpsParser().parse(FakeSite("example.com").apply { dialogShown = false }.observe(1000), 1000)
        .copy(pageType = PageType.HOME, affordances = listOf(Affordance("query", Role.SEARCH_BOX, tag = "input", roleScore = 1.0)))
    private fun trace(role: Role = Role.SEARCH_BOX) = JsonArray().add(JsonObject().put("kind", "type").put("role", role.name).put("name", "private label").put("has_value", true).put("submit", true))
    private fun ledger(intent: GoalIntent = GoalIntent.LEARN_SITE) = TaskLedger("demo", Goal("goal", intent, "", "cars", emptyList()), "example.com", "https://example.com").apply { lesson = "search" }
    @Test fun demonstrationIsATrainingCandidateWithoutSuccessCredit() {
        val memory = Memory(InMemoryStorage()) { 1000L }
        val p = page()
        val skill = SkillCompiler.compileFromDemonstration(memory, null, p.host, trace(), p, p, 1000)!!
        assertEquals(0.0, skill.stat(p.host).successes, 0.0)
        assertFalse("verified_v2" in skill.tags)
        assertTrue("demonstrated_candidate_v2" in skill.tags)
        assertEquals("$" + "query", skill.body.single().arg)
        assertNull(skill.body.single().nameHint)
        assertNotNull(memory.skills.reusable(p, "search", mapOf("query" to "cars"), ledger()))
        assertNull(memory.skills.reusable(p, "search", mapOf("query" to "cars"), ledger(GoalIntent.FIND_LISTINGS)))
        assertFalse(Curriculum.allLessonsComplete(memory.site(p.host)))
    }
    @Test fun failedCandidateIsHeldAndCannotRetryAsLearned() {
        val memory = Memory(InMemoryStorage()) { 1000L }; val p = page()
        val skill = SkillCompiler.compileFromDemonstration(memory, null, p.host, trace(), p, p, 1000)!!
        memory.skills.recordOutcome(skill.id, p.host, false)
        assertNull(memory.skills.reusable(p, "search", mapOf("query" to "cars"), ledger()))
        assertFalse("verified_v2" in memory.skills.get(skill.id)!!.tags)
    }
    @Test fun replayMustVerifyBeforeDemonstrationBecomesLearned() {
        val memory = Memory(InMemoryStorage()) { 1000L }
        val fake = FakeSite("example.com").apply { dialogShown = false }
        val before = SpsParser().parse(fake.observe(1000), 1000)
        val skill = SkillCompiler.compileFromDemonstration(memory, null, before.host, trace(), before, before, 1000)!!
        val task = ledger().copy(goal = ledger().goal.copy(budget = Budget(itemsInspected = 1, actions = 16, llmCalls = 0, wallMs = 10_000)))
        val out = BrainEngine(fake, memory, { null }, object : EngineEvents {}, EngineConfig(pacingOverrideMs = 0, ambiguousRecheckMs = 0)).runTask(task)
        assertTrue(out.steps.any { it.source == "skill:${skill.id}" && it.status == VerifyStatus.VERIFIED })
        assertTrue("${out.status} ${out.terminalReason} ${out.notes} steps=${out.steps} skill=${memory.skills.get(skill.id)}", "search" in out.successfulSkills)
        assertTrue("verified_v2" in memory.skills.get(skill.id)!!.tags)
        assertFalse("demonstrated_candidate_v2" in memory.skills.get(skill.id)!!.tags)
        assertTrue(memory.site(before.host).curriculum.first { it.id == "search" }.done)
    }
    @Test fun sortUsesTheParameterSuppliedByLearning() {
        val memory = Memory(InMemoryStorage()) { 1000L }; val p = page().copy(pageType = PageType.RESULTS)
        val t = JsonArray().add(JsonObject().put("kind", "select").put("role", Role.SORT.name))
        val skill = SkillCompiler.compileFromDemonstration(memory, null, p.host, t, p, p, 1000)!!
        assertEquals(listOf("order"), skill.params)
        assertEquals("$" + "order", skill.body.single().arg)
        assertTrue("capability:sort_results" in skill.tags)
    }
    @Test fun loadMoreKeepsItsOwnCapability() {
        val memory = Memory(InMemoryStorage()) { 1000L }; val p = page().copy(pageType = PageType.RESULTS)
        val t = JsonArray().add(JsonObject().put("kind", "click").put("role", Role.LOAD_MORE.name))
        val skill = SkillCompiler.compileFromDemonstration(memory, null, p.host, t, p, p, 1000)!!
        assertTrue("capability:load_more" in skill.tags)
        assertFalse("capability:next_page" in skill.tags)
    }
    @Test fun focusedPrerequisiteDemosHaveLiveTargets() {
        val p = page().copy(affordances = listOf(
            Affordance("opener", Role.FACET_OPEN, facetKey = "price", roleScore = 1.0),
            Affordance("apply", Role.FACET_APPLY, roleScore = 1.0),
            Affordance("category", Role.CATEGORY_LINK, roleScore = 1.0)))
        for (capability in listOf("open_filters", "open_facet", "apply_filters", "open_category")) {
            assertEquals(capability, LearningOpportunities.target(p, capability)?.skillId)
            assertNull(LearningOpportunities.target(p.copy(authWall = true), capability))
        }
    }
    @Test fun unsafeAndCrossSiteDemonstrationsAreRejected() {
        val memory = Memory(InMemoryStorage()) { 1000L }; val p = page()
        assertNull(SkillCompiler.compileFromDemonstration(memory, null, p.host, trace(Role.LOGIN), p, p, 1000))
        assertNull(SkillCompiler.compileFromDemonstration(memory, null, p.host, trace(Role.SEND), p, p, 1000))
        assertNull(SkillCompiler.compileFromDemonstration(memory, null, p.host, trace(), p.copy(authWall = true), p, 1000))
        assertNull(SkillCompiler.compileFromDemonstration(memory, null, p.host, trace(), p, p.copy(host = "other.example", url = "https://other.example/"), 1000))
    }
}
