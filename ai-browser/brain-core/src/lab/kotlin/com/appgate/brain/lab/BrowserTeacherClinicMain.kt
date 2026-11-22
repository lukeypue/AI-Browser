package com.appgate.brain.lab

import com.appgate.brain.engine.*
import com.appgate.brain.json.*
import com.appgate.brain.memory.*
import com.appgate.brain.model.*
import com.appgate.brain.planner.*
import java.io.File

/** Lab-only bridge: generated Chromium pages, no live site or external model access. */
private class BrowserClinicRenderer : Renderer {
    private val input = System.`in`.bufferedReader()
    private var sequence = 0
    private var url = "https://teacher-browser.fixture.invalid/"
    private var documentId = ""
    private fun rpc(method: String, extra: JsonObject = JsonObject()): JsonObject {
        val id = ++sequence
        extra.put("kind", "RPC").put("id", id).put("method", method)
        println(extra.toString()); System.out.flush()
        val line = input.readLine() ?: error("fixture bridge closed")
        require(line.length <= 1_048_576)
        return Json.parseObject(line).also { require(it.optInt("id") == id) }
    }
    private fun result(row: JsonObject) = RendererResult(row.optBoolean("ok"))
    override fun observe(timeoutMs: Long): String {
        val response = rpc("observe")
        require(response.optBoolean("ok"))
        val raw = response["observation"] as? JsonObject ?: error("fixture observation missing")
        url = raw.optString("url"); documentId = raw.optString("documentId")
        require(java.net.URI(url).host == "teacher-browser.fixture.invalid")
        return raw.toString()
    }
    override fun act(command: JsonObject, timeoutMs: Long): RendererResult {
        if (documentId.isNotBlank()) command.put("expectedDocumentId", documentId)
        command.put("expectedUrl", url)
        return result(rpc("act", JsonObject().put("command", command)))
    }
    override fun navigate(url: String, timeoutMs: Long) = result(rpc("navigate", JsonObject().put("url", url)))
    override fun back(timeoutMs: Long) = result(rpc("back"))
    override fun waitSettle(timeoutMs: Long) = result(rpc("settle"))
    override fun currentUrl() = url
    override fun setNetworkMode(mode: String, allowlist: List<String>, commitEndpoints: List<String>): RendererResult {
        require(mode == "TRAIN")
        return result(rpc("network"))
    }
    override fun recover() = result(rpc("recover"))
    override fun isAlive() = true
}

fun main(args: Array<String>) {
    require(args.size == 4)
    val mode = args[0]
    require(mode in setOf("training", "evaluation", "ablate"))
    val directory = File(args[1])
    require(directory.canonicalPath.startsWith(File(System.getProperty("java.io.tmpdir")).canonicalPath + "/teacher-browser-"))
    val query = args[2]; require(query.matches(Regex("synthetic-[a-z0-9-]{1,80}")))
    val memory = Memory(FileBrainStorage(directory))
    if (mode == "ablate") memory.skills.all().filter { it.origin == SkillOrigin.COMPILED }.forEach { memory.skills.remove(it.id) }
    var calls = 0
    val client = if (mode == "training") {
        val candidate = File(args[3]); require(candidate.isFile && candidate.length() <= 65536)
        val response = candidate.readText()
        object : PlannerClient {
            override val describe = "recorded-actual-teacher-browser-fixture"
            override fun complete(instructions: String, input: String, schemaName: String, schema: JsonObject, maxOutputTokens: Int): String {
                check(++calls <= 2)
                return if (calls == 1) """{"steps":[],"give_up":true,"confidence":0.99}""" else response
            }
        }
    } else null
    val renderer = BrowserClinicRenderer()
    val goal = Goal("browser-clinic-" + query, GoalIntent.LEARN_SITE, "synthetic search", query, emptyList(),
        budget = Budget(itemsInspected = 1, actions = 16, llmCalls = if (client == null) 0 else 2, wallMs = 25000))
    val task = TaskLedger(goal.id, goal, "teacher-browser.fixture.invalid", renderer.currentUrl()).apply { lesson = "search" }
    val config = EngineConfig(pacingOverrideMs = 0, plannerCooldownMs = 0, ambiguousRecheckMs = 0,
        idleSleepMs = 0, maxConsecutiveFailures = 8, maxDecisionsWithoutProgress = 40)
    val result = BrainEngine(renderer, memory, { client?.let { Planner(it) } }, object : EngineEvents {}, config).runTask(task, EngineMode.TRAIN)
    println(JsonObject().put("kind", "DONE").put("status", result.status.name).put("actions", result.actions)
        .put("teacher_calls", calls).put("verified", "search" in result.successfulSkills)
        .put("compiled_skills", memory.skills.all().count { it.origin == SkillOrigin.COMPILED }).toString())
}
