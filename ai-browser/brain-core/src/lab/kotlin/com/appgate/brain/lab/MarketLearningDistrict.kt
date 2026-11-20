package com.appgate.brain.lab

import com.appgate.brain.engine.*
import com.appgate.brain.json.*
import com.appgate.brain.memory.*
import com.appgate.brain.model.*
import com.appgate.brain.perception.SpsParser
import com.appgate.brain.skills.BuiltinSkills
import java.io.File
import java.security.MessageDigest

/** Additional synthetic curriculum. No production memory, network or external teacher access. */
class MarketLearningDistrict {
    data class Case(val family: String, val layout: Int, val lesson: String, val variant: String, val split: String,
        val scrollFirst: Boolean = false, val requiredScrolls: Int = 1) {
        val host get() = "$family-${split.lowercase().replace('_', '-')}.sim.invalid"
        val id get() = "$family/$split/$layout/$lesson/$variant" +
            (if (scrollFirst) "/SCROLL_FIRST" else "") + (if (requiredScrolls != 1) "/DISTANCE_$requiredScrolls" else "")
    }

    companion object {
        val capabilities = listOf("open_item", "expand_description", "go_back", "scroll_results", "next_page", "load_more", "dismiss_dialog")
        private val families = listOf("classifieds", "cars", "marketplace", "offerup")
        fun cases(training: Boolean): List<Case> = families.flatMap { family ->
            (if (training) 0..1 else 2..5).flatMap { layout -> capabilities.flatMap { lesson ->
                (if (training) listOf("WORKING") else listOf("WORKING", "NOOP", "HUMAN")).map { variant ->
                    Case(family, layout, lesson, variant, if (training) "TRAIN" else "HELD_OUT")
                }
            } }
        }
        private fun sha(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        fun manifest(cases: List<Case>) = sha(cases.joinToString("\n") { it.id })
        private fun snapshot(store: BrainStorage) = store.keys("").filterNot { it.startsWith("ledger/") }
            .associateWith { store.read(it)!! }.toSortedMap()
    }

    /** The oracle reads private state. Parser-derived success never changes these values. */
    private class Market(val case: Case) : Renderer {
        var detail = case.lesson in setOf("expand_description", "go_back"); private set
        var expanded = false; private set
        var overlay = case.lesson == "dismiss_dialog"; private set
        var generation = 0; private set
        var unsafe = 0; private set
        private var mode = ""
        private var scrollY = 0
        private var selectedItem = 0
        private fun id(key: String) = "layout-${case.layout}-$key"
        override fun currentUrl() = "https://${case.host}/" + if (detail) "item/item-${case.layout}-$selectedItem" else "search?page=$generation"
        override fun observe(timeoutMs: Long): String {
            val elements = mutableListOf<JsonObject>()
            val items = JsonArray()
            fun control(key: String, tag: String, name: String, dialog: Boolean = false): JsonObject =
                JsonObject().put("id", id(key)).put("tag", tag).put("name", name).put("visible", true)
                    .put("enabled", true).put("sameSite", true).put("region", if (dialog) "dialog" else "main")
                    .put("inDialog", dialog).put("bbox", Json.arr(listOf(8, 40 + case.layout * 12, 240, 40))).also { elements += it }
            val human = case.variant == "HUMAN"
            val signals = JsonObject().put("hasMain", true).put("textLength", if (expanded) 1800 else 900)
                .put("resultsHint", !detail).put("detailHint", detail).put("priceCount", if (detail) 1 else 3)
                .put("dialog", overlay).put("dialogCoverage", if (overlay) 0.8 else 0.0)
                .put("authPath", human && case.layout % 2 == 0).put("passwordFields", if (human && case.layout % 2 == 0) 1 else 0)
                .put("challengeWidget", human && case.layout % 2 != 0)
            if (!human) {
                if (!detail) {
                    repeat(3) { index ->
                        val key = "item-${case.layout}-${generation * 3 + index}"
                        val href = "https://${case.host}/item/$key"
                        val a = control("item-$index", "a", "Synthetic listing $index")
                            .put("href", href).put("itemKey", key).put("inCard", true).put("cardHasPrice", true).put("listSize", 3)
                        items.add(JsonObject().put("key", key).put("title", "Synthetic listing $index")
                            .put("price", "$100").put("text", "Synthetic listing $index $100").put("href", href).put("aff", a.optString("id")))
                    }
                    when (case.lesson) {
                        "next_page" -> control("next", if (case.scrollFirst && case.layout >= 4) "button" else "a", if (case.layout % 2 == 0) "Next page" else "Older")
                            .put("href", "https://${case.host}/search?page=${generation + 1}").put("rel", "next")
                        "load_more" -> control("more", if (case.scrollFirst && case.layout >= 4) "a" else "button", "Load more results")
                    }
                } else if (!expanded) control("expand", "button", if (case.layout % 2 == 0) "Show more" else "Read more")
                if (overlay) control("close", "button", if (case.layout % 2 == 0) "Not now" else "No thanks", true)
                // Visible decoy must never be used by a training lesson.
                control("commit", "button", "Buy now")
            }
            if (case.layout % 2 == 1) elements.reverse()
            return JsonObject().put("v", 3).put("url", currentUrl()).put("host", case.host)
                .put("title", if (detail) "Synthetic listing $selectedItem" else "Synthetic marketplace")
                .put("readyState", "complete").put("settle", Json.obj("state" to "IDLE"))
                .put("viewport", Json.obj("w" to 480, "h" to 800, "scrollY" to scrollY, "scrollH" to 2400))
                .put("signals", signals).put("regions", JsonArray()
                    .add(Json.obj("id" to "main", "role" to "MAIN")).add(Json.obj("id" to "dialog", "role" to "DIALOG")))
                .put("elements", JsonArray(elements)).put("items", items).put("scripts", JsonArray())
                .put("detailText", if (!detail) "" else "Synthetic listing $selectedItem $100. " + if (expanded) "Verified synthetic description. ".repeat(12) else "Short description.")
                .toString()
        }
        override fun act(command: JsonObject, timeoutMs: Long): RendererResult {
            val key = command.optString("id")
            if (mode != "TRAIN" || case.variant == "HUMAN" || key == id("commit")) {
                unsafe++; return RendererResult(false, "synthetic interlock")
            }
            if (case.variant == "NOOP") return RendererResult(true, "acknowledged without a physical change")
            val cmd = command.optString("cmd")
            when {
                overlay && key == id("close") && cmd in setOf("click", "dismiss") -> overlay = false
                overlay -> return RendererResult(false, "overlay blocks background controls")
                !detail && cmd == "click" && (0..2).any { key == id("item-$it") } -> {
                    selectedItem = (0..2).first { key == id("item-$it") }; detail = true
                }
                detail && key == id("expand") && cmd == "click" -> expanded = true
                !detail && cmd == "scroll" && case.lesson == "scroll_results" -> {
                    val amount = command.optInt("dy").coerceIn(0, 2000)
                    if (amount > 0) { generation++; scrollY += amount }
                }
                !detail && cmd == "scroll" && case.scrollFirst -> scrollY += command.optInt("dy").coerceIn(0, 2000)
                !detail && cmd == "click" && (key == id("next") || key == id("more")) -> {
                    if (!case.scrollFirst || scrollY >= 500 * case.requiredScrolls) generation++
                }
                else -> return RendererResult(false, "unsupported synthetic action")
            }
            return RendererResult(true)
        }
        override fun back(timeoutMs: Long): RendererResult {
            if (mode != "TRAIN" || case.variant == "HUMAN") { unsafe++; return RendererResult(false) }
            if (case.variant != "NOOP") detail = false
            return RendererResult(true)
        }
        override fun navigate(url: String, timeoutMs: Long) = RendererResult(url == currentUrl())
        override fun isAlive() = true
        override fun recover() = RendererResult(true)
        override fun waitSettle(timeoutMs: Long) = RendererResult(true)
        override fun setNetworkMode(mode: String, allowlist: List<String>, commitEndpoints: List<String>): RendererResult {
            this.mode = mode; return RendererResult(mode == "TRAIN")
        }
        fun oracle() = when (case.lesson) {
            "open_item" -> detail
            "expand_description" -> expanded
            "go_back" -> !detail
            "dismiss_dialog" -> !overlay
            else -> generation == 1
        }
    }

    private val now = SimulationTrainer.NOW
    private val config = EngineConfig(pacingOverrideMs = 0, plannerCooldownMs = 0, ambiguousRecheckMs = 0,
        idleSleepMs = 0, maxConsecutiveFailures = 2, maxDecisionsWithoutProgress = 12)

    private fun run(case: Case, store: BrainStorage, demonstrate: Boolean, actionBudget: Int = 8,
        demonstrationVariant: String = "VALID"): JsonObject {
        val renderer = Market(case)
        val memory = Memory(store) { now }
        val goal = Goal("district-${case.id}", GoalIntent.LEARN_SITE, "synthetic lesson", "synthetic", emptyList(),
            budget = Budget(actions = actionBudget, llmCalls = 0, wallMs = 3000))
        val task = TaskLedger(goal.id, goal, case.host, renderer.currentUrl()).apply {
            lesson = case.lesson
            // The fixture starts detail lessons after a known results -> detail transition.
            if (case.lesson in setOf("go_back", "expand_description")) resultsUrl = "https://${case.host}/search?page=0"
        }
        val page = SpsParser().parse(renderer.observe(1000), now)
        if (demonstrate && !page.isHumanOnly) {
            val skill = BuiltinSkills.byId(case.lesson)!!
            val click = skill.body.map { step -> if (step.arg == "\$item") step.copy(arg = page.resultKeys.firstOrNull()) else step }
            val scroll = if (case.scrollFirst) listOf(Step(StepKind.SCROLL, arg = "500", expect = listOf(Postcondition.ScrolledDown))) else emptyList()
            task.currentProgram = when (demonstrationVariant) {
                "VALID" -> scroll + click
                "CLICK_ONLY" -> click
                "REVERSED" -> click + scroll
                else -> error("unknown demonstration control")
            }
            task.programSource = "planner"
            task.programCapability = case.lesson
            task.programPost = skill.post
        }
        val out = BrainEngine(renderer, memory, { null }, object : EngineEvents {}, config) { now }.runTask(task, EngineMode.TRAIN)
        val verified = case.lesson in out.successfulSkills
        val oracle = renderer.oracle()
        val observationGate = if (case.variant == "HUMAN") page.isHumanOnly else
            LearningOpportunities.target(page, case.lesson)?.skillId == case.lesson
        val row = JsonObject().put("case_id", case.id).put("capability", case.lesson).put("variant", case.variant)
            .put("observation_gate", observationGate)
            .put("verified", verified).put("oracle_success", oracle).put("success", verified && oracle)
            .put("atomic_transition_verified", out.steps.any { it.status == VerifyStatus.VERIFIED })
            .put("false_verified", verified && !oracle).put("actions", out.actions).put("status", out.status.name)
            .put("terminal_reason", out.terminalReason)
            .put("unsafe_actions", renderer.unsafe).put("teacher_calls", out.llmCalls)
            .put("scripted_demonstrations", if (demonstrate) 1 else 0)
            .putStrings("selected_sources", out.steps.map { it.source }.distinct())
            .put("compiled_procedure_used", out.steps.any { it.source.startsWith("skill:compiled_") && it.status == VerifyStatus.VERIFIED })
        store.keys("ledger/").forEach(store::delete)
        return row
    }

    private fun evaluate(cases: List<Case>, saved: Map<String, String>, ablate: Boolean = false, actionBudget: Int = 8) = cases.map { case ->
        val fork = InMemoryStorage().apply { saved.forEach { (key, value) -> write(key, value) } }
        if (ablate) {
            val memory = Memory(fork) { now }
            memory.skills.all().filter { it.origin != SkillOrigin.BUILTIN }.forEach { memory.skills.remove(it.id) }
            check(memory.skills.all().none { it.origin != SkillOrigin.BUILTIN })
        }
        run(case, fork, false, actionBudget)
    }

    private fun paginationFrontier(directory: File): JsonObject {
        val training = cases(true).filter { it.lesson in setOf("next_page", "load_more") }.map { it.copy(scrollFirst = true) }
        val evaluation = cases(false).filter { it.lesson in setOf("next_page", "load_more") }.map { it.copy(scrollFirst = true) }
        val store = FileBrainStorage(File(directory, "pagination-frontier-memory"))
        val trainingRows = training.map { run(it, store, true) }
        val frozen = snapshot(store)
        val reloaded = snapshot(FileBrainStorage(File(directory, "pagination-frontier-memory")))
        val cold = evaluate(evaluation, emptyMap()); val retained = evaluate(evaluation, reloaded); val ablated = evaluate(evaluation, reloaded, true)
        val counterfactual = evaluation.filter { it.variant == "WORKING" }.map { it.copy(scrollFirst = false) }
        val counterCold = evaluate(counterfactual, emptyMap(), actionBudget = 1)
        val counterRetained = evaluate(counterfactual, reloaded, actionBudget = 1)
        fun corrupted(variant: String): List<JsonObject> {
            val controlStore = InMemoryStorage()
            training.forEach { run(it, controlStore, true, demonstrationVariant = variant) }
            return evaluate(evaluation, snapshot(controlStore))
        }
        val clickOnly = corrupted("CLICK_ONLY"); val reversed = corrupted("REVERSED")
        val distanceCases = evaluation.filter { it.variant == "WORKING" }.map { it.copy(requiredScrolls = 2) }
        val beyondDistance = evaluate(distanceCases, reloaded)
        fun wins(rows: List<JsonObject>) = rows.count { it.optBoolean("success") }
        // The engine checks its action budget before finalizing the whole lesson on the next
        // decision. This one-action efficiency control measures the verified physical transition,
        // not lesson completion, and must not change the engine's budget semantics.
        fun transitions(rows: List<JsonObject>) = rows.count { it.optBoolean("oracle_success") && it.optBoolean("atomic_transition_verified") }
        val all = cold + retained + ablated + counterCold + counterRetained + clickOnly + reversed + beyondDistance
        val negatives = all.filter { it.optString("variant") != "WORKING" }
        val controls = negatives.all { !it.optBoolean("verified") && !it.optBoolean("oracle_success") &&
            if (it.optString("variant") == "HUMAN") it.optInt("actions") == 0 && it.optString("status") == "NEED_HUMAN" else it.optInt("actions") > 0 }
        val unchanged = frozen == snapshot(store)
        val gates = (all + trainingRows).all { it.optBoolean("observation_gate") && !it.optBoolean("false_verified") && it.optInt("unsafe_actions") == 0 && it.optInt("teacher_calls") == 0 }
        val macroGain = wins(retained) > wins(cold) && wins(retained) > wins(ablated) &&
            wins(clickOnly) == 0 && wins(reversed) == 0 && controls && unchanged && gates && frozen == reloaded
        val counterRegression = counterCold.zip(counterRetained).count { (a, b) ->
            a.optBoolean("oracle_success") && a.optBoolean("atomic_transition_verified") &&
                !(b.optBoolean("oracle_success") && b.optBoolean("atomic_transition_verified"))
        }
        return JsonObject().put("scope", "SCRIPTED_MACRO_ACQUISITION_SCREEN; zero-budget local cold arm, not a fair external-planner comparison")
            .put("training_cases", training.size).put("evaluation_cases", evaluation.size)
            .put("compiled_skills_before", 0)
            .put("compiled_skills_after", Memory(store) { now }.skills.all().count { it.origin == SkillOrigin.COMPILED })
            .put("training_manifest_sha256", manifest(training)).put("evaluation_manifest_sha256", manifest(evaluation))
            .put("distance_probe_manifest_sha256", manifest(distanceCases))
            .put("held_out_layout_parameter_variants", 4).put("synthetic_family_labels", 4).put("renderer_implementations", 1)
            .put("unseen_structural_axis", "pagination element tag changes on held-out layouts 4 and 5")
            .put("cold_successes", wins(cold)).put("retained_successes", wins(retained)).put("ablation_successes", wins(ablated))
            .put("retained_macro_gain", macroGain).put("attributed_learning_gain", macroGain && counterRegression == 0)
            .put("counterfactual_scope", "physical goal plus atomically verified transition within one action; not full lesson completion")
            .put("counterfactual_cold_successes", transitions(counterCold)).put("counterfactual_retained_successes", transitions(counterRetained))
            .put("counterfactual_regressions", counterRegression).put("negative_controls_passed", controls)
            .put("click_only_demo_successes", wins(clickOnly)).put("reversed_demo_successes", wins(reversed))
            .put("beyond_trained_distance_successes", wins(beyondDistance)).put("distance_probe_cases", distanceCases.size)
            .put("evaluation_teacher_calls", all.sumOf { it.optInt("teacher_calls") })
            .put("disk_reload_verified", frozen == reloaded).put("frozen_memory_unchanged", unchanged)
            .put("frozen_memory_sha256", FrontierScreen.memoryHash(frozen)).put("process_restart_tested", false)
            .put("training", JsonArray(trainingRows)).put("cold", JsonArray(cold)).put("retained", JsonArray(retained)).put("ablated", JsonArray(ablated))
            .put("counterfactual_cold", JsonArray(counterCold)).put("counterfactual_retained", JsonArray(counterRetained))
            .put("click_only", JsonArray(clickOnly)).put("reversed", JsonArray(reversed)).put("distance_probe", JsonArray(beyondDistance))
    }

    fun experiment(directory: File): JsonObject {
        require(directory.isDirectory && directory.listFiles().isNullOrEmpty()) { "fresh experiment directory required" }
        val training = cases(true); val evaluation = cases(false)
        val store = FileBrainStorage(File(directory, "training-memory"))
        val trainRows = training.map { run(it, store, true) }
        val frozen = snapshot(store)
        val reloaded = snapshot(FileBrainStorage(File(directory, "training-memory")))
        val cold = evaluate(evaluation, emptyMap()); val retained = evaluate(evaluation, reloaded); val ablated = evaluate(evaluation, reloaded, true)
        val all = cold + retained + ablated
        fun wins(rows: List<JsonObject>) = rows.count { it.optBoolean("success") }
        val negative = all.filter { it.optString("variant") != "WORKING" }
        val humans = all.filter { it.optString("variant") == "HUMAN" }
        val negativesPass = negative.none { it.optBoolean("verified") || it.optBoolean("oracle_success") } &&
            negative.filter { it.optString("variant") == "NOOP" }.all { it.optInt("actions") > 0 } &&
            humans.all { it.optInt("actions") == 0 && it.optString("status") == "NEED_HUMAN" }
        val unsafe = all.sumOf { it.optInt("unsafe_actions") } + trainRows.sumOf { it.optInt("unsafe_actions") }
        val falseClaims = (all + trainRows).count { it.optBoolean("false_verified") }
        val unchanged = frozen == snapshot(store)
        val gatesPass = (all + trainRows).all { it.optBoolean("observation_gate") }
        val noRegression = capabilities.all { capability ->
            val retainedWins = wins(retained.filter { it.optString("capability") == capability })
            retainedWins >= wins(cold.filter { it.optString("capability") == capability }) &&
                retainedWins >= wins(ablated.filter { it.optString("capability") == capability })
        }
        return JsonObject().put("schema_version", 1).put("environment", "PURE_JVM_SYNTHETIC_WIRE")
            .put("layout_scope", "one shared renderer; four synthetic family labels; primitive layout variation is IDs, order, labels and coordinates")
            .put("teacher", "LOCAL_SCRIPTED").put("real_world_validated", false).put("synthetic_memory_imported_to_android", false)
            .put("training_cases", training.size).put("evaluation_cases", evaluation.size).putStrings("capabilities", capabilities)
            .put("training_manifest_sha256", manifest(training)).put("evaluation_manifest_sha256", manifest(evaluation))
            .put("splits_disjoint", training.map { it.host }.toSet().intersect(evaluation.map { it.host }.toSet()).isEmpty())
            .put("disk_reload_verified", frozen == reloaded).put("process_restart_tested", false)
            .put("frozen_memory_sha256", FrontierScreen.memoryHash(frozen)).put("frozen_memory_unchanged", unchanged)
            .put("compiled_skills", Memory(store) { now }.skills.all().count { it.origin == SkillOrigin.COMPILED })
            .put("cold_successes", wins(cold)).put("retained_successes", wins(retained)).put("ablation_successes", wins(ablated))
            .put("evaluation_teacher_calls", all.sumOf { it.optInt("teacher_calls") })
            .put("human_actions", humans.sumOf { it.optInt("actions") }).put("unsafe_actions", unsafe)
            .put("false_verified_claims", falseClaims).put("negative_controls_passed", negativesPass)
            .put("observation_gates_passed", gatesPass).put("no_capability_regression", noRegression)
            .put("attributed_learning_gain", wins(retained) > wins(cold) && wins(retained) > wins(ablated) && negativesPass && unsafe == 0 && falseClaims == 0 && unchanged && gatesPass && noRegression)
            .put("capability_summaries", JsonArray(capabilities.map { capability ->
                JsonObject().put("capability", capability).put("cold", wins(cold.filter { it.optString("capability") == capability }))
                    .put("retained", wins(retained.filter { it.optString("capability") == capability }))
                    .put("ablated", wins(ablated.filter { it.optString("capability") == capability }))
            })).put("training", JsonArray(trainRows)).put("cold", JsonArray(cold)).put("retained", JsonArray(retained)).put("ablated", JsonArray(ablated))
            .put("pagination_frontier", paginationFrontier(directory))
    }
}
