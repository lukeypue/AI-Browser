package com.appgate.brain.memory

import com.appgate.brain.engine.*
import com.appgate.brain.goal.GoalParser
import com.appgate.brain.model.*
import com.appgate.brain.perception.SpsParser
import com.appgate.brain.skills.SkillCompiler
import com.appgate.brain.test.FakeSite
import org.junit.Assert.*
import org.junit.Test

class PortableSkillTest {
    private val now = 10_000L
    private fun memory() = Memory(InMemoryStorage()) { now }
    private fun task(host: String, query: String = "Ford Expedition") = TaskLedger(
        "private task text", GoalParser.parse(query).copy(query = query,
            budget = Budget(itemsInspected = 1, actions = 16, llmCalls = 0, wallMs = 10_000)), host, "https://$host/")
    private fun search(query: String = "Ford Expedition") = listOf(
        Step(StepKind.DISMISS, Role.CLOSE, optional = true, expect = listOf(Postcondition.DialogClosed)),
        Step(StepKind.TYPE, Role.SEARCH_BOX, arg = query, submit = true,
            expect = listOf(Postcondition.ResultsChanged)))
    private fun learn(memory: Memory, host: String, program: List<Step> = search(),
                      capability: String = "search", post: List<Postcondition> = listOf(Postcondition.ResultsChanged),
                      page: PageType = PageType.HOME, query: String = "Ford Expedition"): Skill? {
        val ledger = task(host, query).apply {
            programCompleted = true
            programPage = page
            programCapability = capability
            programPost = post
            programVerifiedSteps.addAll(program.indices.filter { !program[it].optional })
        }
        return SkillCompiler.compileFromPlanner(memory, ledger, program, now)
    }
    private fun page(host: String = "target.market", role: Role = Role.SEARCH_BOX, facet: String? = null) =
        SpsParser().parse(FakeSite(host).apply { dialogShown = false }.observe(1000), now)
            .copy(affordances = listOf(Affordance("fresh-control", role, facetKey = facet,
                tag = if (role == Role.SEARCH_BOX) "input" else "select", roleScore = 1.0)))

    @Test fun canonicalCompilationMergesHostsWithoutPersistingQueryOrTaskText() {
        val memory = memory()
        val first = learn(memory, "source.market")!!
        val second = learn(memory, "another.market", search("Toyota Sequoia"), query = "Toyota Sequoia")!!
        assertEquals(first.id, second.id)
        assertEquals(1.0, second.stat("source.market").successes, 0.0)
        assertEquals(1.0, second.stat("another.market").successes, 0.0)
        assertEquals(1, memory.skills.all().count { it.origin == SkillOrigin.COMPILED })
        val saved = second.toJson().toString()
        for (privateText in listOf("Ford", "Toyota", "private task text")) assertFalse(saved.contains(privateText))
    }

    @Test fun discoveredLearningConstraintCompilesWithoutRetainingItsLiteral() {
        val memory = memory()
        val ledger = task("source.market").let {
            it.copy(goal = it.goal.copy(intent = GoalIntent.LEARN_SITE, constraints = emptyList()))
        }.apply {
            learningConstraints = listOf(Constraint("price", ConstraintOp.LTE, "7139"))
            programPage = PageType.FACET_PANEL
            programCapability = "constrain_numeric"
            programPost = listOf(Postcondition.ValueIs("price_max", "7139"))
            programCompleted = true
            programVerifiedSteps += 0
        }
        val program = listOf(Step(StepKind.TYPE, Role.FACET, "price_max", "7139",
            expect = listOf(Postcondition.ValueIs("price_max", "7139"))))
        val skill = SkillCompiler.compileVerified(memory, ledger, program, now)
        assertNotNull(skill)
        assertEquals("\$price_max", skill!!.body.single().arg)
        assertEquals(listOf(Postcondition.ValueIs("price_max", "\$price_max")), skill.post)
        assertFalse(skill.toJson().toString().contains("7139"))
        val live = page("source.market", Role.FACET, "price_max").copy(pageType = PageType.FACET_PANEL)
        val params = mapOf("price_max" to "6400")
        assertEquals(skill.id, memory.skills.reusable(live, "constrain_numeric", params, ledger)?.id)
        val grounded = StepGrounder(null).ground(skill.body.single(), params, live, emptySet()) as GroundingOutcome.Ready
        assertEquals("6400", grounded.grounded.action.text)
    }

    @Test fun twoSourceSuccessesTransferToHeldOutHostAndRunWithoutPlanner() {
        val memory = memory()
        learn(memory, "source.market")
        val target = page()
        assertNull(memory.skills.reusable(target, "search", mapOf("query" to "Toyota Sequoia"), task(target.host)))
        val skill = learn(memory, "source.market")!!
        assertEquals(skill.id, memory.skills.reusable(target, "search", mapOf("query" to "Toyota Sequoia"), task(target.host))?.id)

        val fake = FakeSite(target.host).apply { dialogShown = false }
        val out = BrainEngine(fake, memory, { null }, object : EngineEvents {},
            EngineConfig(pacingOverrideMs = 0, ambiguousRecheckMs = 0)).runTask(task(target.host, "Toyota Sequoia"))
        assertEquals(TaskStatus.DONE, out.status)
        assertEquals("Toyota Sequoia", fake.query)
        assertEquals(0, out.llmCalls)
        assertTrue(out.steps.any { it.source == "skill:${skill.id}" && it.status == VerifyStatus.VERIFIED })
        assertTrue(memory.skills.get(skill.id)!!.stat(target.host).successes > 0)
    }

    @Test fun optionalCloseDoesNotBecomeRequiredEntry() {
        val memory = memory()
        val skill = learn(memory, "source.market")!!
        assertTrue(skill.pre.contains(Precondition.HasRole(Role.SEARCH_BOX)))
        assertFalse(skill.pre.contains(Precondition.HasRole(Role.CLOSE)))
        assertEquals(skill.id, memory.skills.reusable(page("source.market"), "search", mapOf("query" to "new query"), task("source.market"))?.id)
    }

    @Test fun engineAuthorizedHostAliasUsesTaskIdentityForLocalOutcomes() {
        val memory = memory()
        val skill = learn(memory, "source.market")!!
        assertEquals(skill.id, memory.skills.reusable(page("www.source.market"), "search", mapOf("query" to "new"), task("source.market"))?.id)
    }

    @Test fun typeThenSubmitSearchRetainsTypedQueryExpectation() {
        val memory = memory()
        val program = listOf(Step(StepKind.TYPE, Role.SEARCH_BOX, arg = "Ford Expedition",
            expect = listOf(Postcondition.ValueIs("query", "Ford Expedition"))),
            Step(StepKind.CLICK, Role.SUBMIT, expect = listOf(Postcondition.ResultsChanged)))
        val skill = learn(memory, "source.market", program)
        assertNotNull(skill)
        assertEquals(Postcondition.ValueIs("query", "\$query"), skill!!.body.first().expect.single())
    }

    @Test fun nextButtonCanRepairLoadMoreWhenItVerifiablyAddsResults() {
        val memory = memory()
        val program = listOf(Step(StepKind.CLICK, Role.PAGE_NEXT, expect = listOf(Postcondition.NewResults)))
        repeat(2) { assertNotNull(learn(memory, "source.market", program, "load_more", listOf(Postcondition.NewResults), PageType.RESULTS)) }
        val target = page(role = Role.PAGE_NEXT).copy(pageType = PageType.RESULTS)
        assertNotNull(memory.skills.reusable(target, "load_more", emptyMap(), task(target.host)))
    }

    @Test fun sourceFailureAndTargetFailureCannotBeHiddenByMoreSourceSuccesses() {
        val memory = memory()
        val skill = learn(memory, "source.market")!!
        learn(memory, "source.market")
        memory.skills.recordOutcome(skill.id, "source.market", false)
        assertNull(memory.skills.reusable(page(), "search", mapOf("query" to "new"), task("target.market")))
        repeat(4) { learn(memory, "source.market") }
        assertNotNull(memory.skills.reusable(page(), "search", mapOf("query" to "new"), task("target.market")))
        memory.skills.recordOutcome(skill.id, "target.market", false)
        repeat(4) { learn(memory, "source.market") }
        assertNull(memory.skills.reusable(page(), "search", mapOf("query" to "new"), task("target.market")))
        assertEquals(1.0, memory.skills.get(skill.id)!!.stat("target.market").failures, 0.0)
        learn(memory, "target.market") // Verified independent local repair recovers this host.
        assertNotNull(memory.skills.reusable(page(), "search", mapOf("query" to "new"), task("target.market")))
    }

    @Test fun sameHostSuccessRanksBeforeAnUntriedSourceProcedure() {
        val memory = memory()
        val local = learn(memory, "target.market", search().drop(1))!!
        repeat(12) { learn(memory, "source.market") }
        assertEquals(local.id, memory.skills.reusable(page(), "search", mapOf("query" to "new"), task("target.market"))?.id)
    }

    @Test fun transferRequiresExactBoundFacetAndAllReferencedParameters() {
        val memory = memory()
        val steps = listOf(Step(StepKind.SET_RANGE, Role.FACET, "\$key", "\$value",
            expect = listOf(Postcondition.ValueIs("\$key", "\$value"))))
        repeat(2) { learn(memory, "source.market", steps, "constrain_numeric",
            listOf(Postcondition.ValueIs("\$key", "\$value")), PageType.RESULTS) }
        val target = page(role = Role.FACET, facet = "price_max").copy(pageType = PageType.RESULTS)
        val params = mapOf("key" to "price_max", "value" to "7500")
        val skill = memory.skills.reusable(target, "constrain_numeric", params, task(target.host))
        assertNotNull(skill)
        val ready = StepGrounder(null).ground(skill!!.body.single(), params, target, emptySet()) as GroundingOutcome.Ready
        assertEquals("price_max", ready.grounded.action.target?.facetKey)
        assertEquals("7500", ready.grounded.action.text)
        assertNull(memory.skills.reusable(target, "constrain_numeric", mapOf("key" to "price_max"), task(target.host)))
        assertNull(memory.skills.reusable(target.copy(affordances = target.affordances.map { it.copy(facetKey = "price_min") }), "constrain_numeric", params, task(target.host)))
        assertNull(memory.skills.reusable(target, "constrain_numeric", mapOf("key" to "private-label", "value" to "7500"), task(target.host)))
    }

    @Test fun postconditionOnlyParametersAreRequiredForReplay() {
        val memory = memory()
        val skill = learn(memory, "target.market", search(), "custom",
            listOf(Postcondition.ValueIs("condition", "\$condition")))!!
        assertTrue("condition" in skill.params)
        assertNull(memory.skills.reusable(page(), "custom", mapOf("query" to "new"), task("target.market")))
        assertNotNull(memory.skills.reusable(page(), "custom", mapOf("query" to "new", "condition" to "used"), task("target.market")))
    }

    @Test fun unresolvedValuesAndNoncanonicalLaterFacetBindingsCannotReplay() {
        val memory = memory()
        repeat(2) { learn(memory, "source.market") }
        assertNull(memory.skills.reusable(page(), "search", mapOf("query" to "\$still_missing"), task("target.market")))

        val steps = listOf(Step(StepKind.CLICK, Role.FACET_OPEN, expect = listOf(Postcondition.DialogOpened)),
            Step(StepKind.SET_RANGE, Role.FACET, "\$key", "\$value", expect = listOf(Postcondition.ValueIs("\$key", "\$value"))))
        repeat(2) { learn(memory, "source.market", steps, "constrain_numeric",
            listOf(Postcondition.ValueIs("\$key", "\$value")), PageType.RESULTS) }
        val target = page(role = Role.FACET_OPEN).copy(pageType = PageType.RESULTS)
        assertNotNull(memory.skills.reusable(target, "constrain_numeric", mapOf("key" to "price_max", "value" to "7000"), task(target.host)))
        for (key in listOf("private-label", "condition")) {
            assertNull(memory.skills.reusable(target, "constrain_numeric", mapOf("key" to key, "value" to "7000"), task(target.host)))
        }
    }

    @Test fun humanOnlyWrongPageAndCommitEntryCannotReuse() {
        val memory = memory()
        repeat(2) { learn(memory, "source.market") }
        val target = page()
        for (invalid in listOf(target.copy(authWall = true), target.copy(pageType = PageType.DETAIL),
            page(role = Role.GENERIC_BUTTON), target.copy(affordances = target.affordances.map { it.copy(effect = EffectClass.COMMIT_EXTERNAL) }),
            target.copy(affordances = target.affordances.map { it.copy(enabled = false) }))) {
            assertNull(memory.skills.reusable(invalid, "search", mapOf("query" to "new"), task(target.host)))
        }
    }

    @Test fun transferRejectsAnOppositeBoundChosenOverTheExactEntry() {
        val memory = memory()
        val program = listOf(Step(StepKind.SET_RANGE, Role.FACET, "price_max", "\$value",
            expect = listOf(Postcondition.ValueIs("price_max", "\$value"))))
        repeat(2) { learn(memory, "source.market", program, "constrain_numeric",
            listOf(Postcondition.ValueIs("price_max", "\$value")), PageType.RESULTS) }
        val target = page(role = Role.FACET, facet = "price_max").copy(pageType = PageType.RESULTS,
            affordances = listOf(Affordance("max", Role.FACET, "price_max", tag = "input", roleScore = 0.1,
                features = FeatureVec(mapOf("tag:input" to 1.0))),
                Affordance("min", Role.FACET, "price_min", tag = "select", roleScore = 1.0,
                    features = FeatureVec(mapOf("tag:select" to 1.0)))))
        val site = memory.site(target.host)
        val binding = Binding(target.host, PageType.RESULTS, Role.FACET, "price_max", "v1",
            FeatureVec(mapOf("tag:select" to 1.0)), emptyList(), BetaStat(20.0), now)
        site.bindings[binding.key] = binding
        assertNull(memory.skills.reusable(target, "constrain_numeric", mapOf("value" to "7000"), task(target.host)))
    }

    @Test fun unsafeActionsAndIncompatibleCapabilityCannotCompile() {
        val memory = memory()
        for (role in listOf(Role.LOGIN, Role.ACCOUNT, Role.MESSAGE_SELLER, Role.COMPOSER_INPUT, Role.ATTACH, Role.SHARE, Role.SEND, Role.BUY)) {
            assertNull(role.name, learn(memory, "source.market", search() + Step(StepKind.CLICK, role)))
        }
        assertNull(learn(memory, "source.market", search(), "next_page"))
        assertNull(learn(memory, "source.market", search(), post = listOf(Postcondition.anyOf(Postcondition.ResultsChanged, Postcondition.UrlChanged))))
        assertNull(learn(memory, "source.market", search(), post = listOf(Postcondition.ComposerReady)))
        assertNull(learn(memory, "source.market", search(), page = PageType.AUTH_WALL))
    }

    @Test fun literalValuesUrlsAndHiddenPostconditionContentCannotPersist() {
        val memory = memory()
        assertNull(learn(memory, "source.market", search("unrelated private text")))
        assertNull(learn(memory, "source.market", listOf(Step(StepKind.NAVIGATE, arg = "https://source.market/private"))))
        assertNull(learn(memory, "source.market", search(), "custom",
            listOf(Postcondition.anyOf(Postcondition.ResultsChanged, Postcondition.ValueIs("condition", "private value")))))
        assertNull(learn(memory, "source.market", listOf(Step(StepKind.SELECT, Role.FACET, "private-label", "\$value")), "custom"))
    }

    @Test fun safeScrollConstantIsCanonicalAndWaitOnlyCannotCompile() {
        val memory = memory()
        val scroll = learn(memory, "source.market", listOf(Step(StepKind.SCROLL, arg = "0900")), "scroll_results",
            listOf(Postcondition.NewResults), PageType.RESULTS)
        assertNotNull(scroll)
        assertEquals("900", scroll!!.body.single().arg)
        assertNull(learn(memory, "source.market", listOf(Step(StepKind.SCROLL, arg = "private")), "scroll_results", listOf(Postcondition.NewResults)))
        assertNull(learn(memory, "source.market", listOf(Step(StepKind.WAIT)), "custom"))
    }

    @Test fun legacyUnsafeOrCustomSkillsCannotTransferEvenWithVerifiedTag() {
        val memory = memory()
        val base = learn(memory, "source.market")!!
        for (skill in listOf(
            base.copy(id = "unsafe", body = base.body + Step(StepKind.CLICK, Role.SEND)),
            base.copy(id = "literal", body = base.body.map { if (it.role == Role.SEARCH_BOX) it.copy(arg = "private query") else it }),
            base.copy(id = "weak", post = listOf(Postcondition.anyOf(Postcondition.ResultsChanged, Postcondition.UrlChanged))),
            base.copy(id = "custom", tags = setOf("verified_v2", "capability:custom")))) {
            val isolated = memory()
            isolated.skills.put(skill.copy(statsByHost = mapOf("source.market" to BetaStat(20.0))))
            val capability = if (skill.id == "custom") "custom" else "search"
            assertNull(skill.id, isolated.skills.reusable(page(), capability, mapOf("query" to "new"), task("target.market")))
        }
    }
}
