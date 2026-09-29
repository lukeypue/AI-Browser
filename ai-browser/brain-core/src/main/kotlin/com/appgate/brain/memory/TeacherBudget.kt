package com.appgate.brain.memory

import com.appgate.brain.json.Json
import com.appgate.brain.json.JsonArray
import com.appgate.brain.json.JsonObject

class TeacherBudgetExceeded(message: String) : IllegalStateException(message)

data class TeacherBudgetSnapshot(
    val requests24h: Int, val requestsHour: Int,
    val inputTokens: Long, val outputTokens: Long, val reportedRequests: Int,
    val lastModel: String, val allowed: Boolean, val reason: String,
    val nextAllowedAt: Long = 0L
)

/** Content-free, durable allowance shared by every teacher route, including failed calls.
 * Reservation is written BEFORE transport. Restarting a task/service cannot reset it.
 * A backwards wall clock freezes expiry until time catches up; invalid storage fails closed.
 */
class TeacherBudget(private val storage: BrainStorage, private val clock: () -> Long = { System.currentTimeMillis() }) {
    private data class State(val json: JsonObject, val entries: MutableList<JsonObject>, val now: Long, val valid: Boolean)

    fun reserve(provider: String, model: String, maxOutputTokens: Int): Long = synchronized(storage) {
        val state = read()
        val summary = summarize(state)
        if (!summary.allowed) throw TeacherBudgetExceeded(summary.reason)
        val id = state.json.optLong("sequence") + 1
        state.json.put("sequence", id)
        state.entries += JsonObject().put("id", id).put("at", state.now)
            .put("provider", identifier(provider)).put("model", identifier(model))
            .put("max_output", maxOutputTokens.coerceIn(0, 100_000))
        save(state)
        id
    }

    /** Usage replaces the reservation's totals so duplicate callbacks cannot double count. */
    fun recordUsage(id: Long, inputTokens: Long, outputTokens: Long, cachedInputTokens: Long = 0L, reasoningTokens: Long = 0L) = synchronized(storage) {
        val state = read()
        if (!state.valid) return@synchronized
        val entry = state.entries.firstOrNull { it.optLong("id") == id } ?: return@synchronized
        entry.put("input", inputTokens.coerceAtLeast(0L)).put("output", outputTokens.coerceAtLeast(0L))
            .put("cached", cachedInputTokens.coerceIn(0L, inputTokens.coerceAtLeast(0L)))
            .put("reasoning", reasoningTokens.coerceIn(0L, outputTokens.coerceAtLeast(0L))).put("reported", true)
        save(state)
    }

    fun snapshot(): TeacherBudgetSnapshot = synchronized(storage) {
        val state = read()
        if (state.valid) save(state)
        summarize(state)
    }

    private fun read(): State {
        val raw = storage.read(KEY)
        val json = if (raw == null) JsonObject().put("schema", 1).put("sequence", 0).put("seen", 0).put("requests", JsonArray())
            else Json.parseObjectOrNull(raw)
        val valid = json != null && json.optInt("schema") == 1 && json.optArray("requests") != null &&
            json.optArray("requests")!!.all { it.asObjectOrNull()?.let { e -> e.has("at") && e.has("id") } == true }
        val stateJson = json ?: JsonObject()
        val now = maxOf(0L, clock(), stateJson.optLong("seen"))
        val entries = stateJson.optArray("requests")?.objects()?.filter { now - it.optLong("at") < DAY_MS }?.toMutableList() ?: mutableListOf()
        return State(stateJson, entries, now, valid)
    }

    private fun summarize(state: State): TeacherBudgetSnapshot {
        val hour = state.entries.filter { state.now - it.optLong("at") < HOUR_MS }
        val allowed = state.valid && hour.size < HOURLY_LIMIT && state.entries.size < DAILY_LIMIT
        val reason = when {
            !state.valid -> "AI usage record needs repair; local learning continues"
            state.entries.size >= DAILY_LIMIT -> "AI allowance reached (24 requests per 24 hours); local learning continues"
            hour.size >= HOURLY_LIMIT -> "AI hourly allowance reached (6 requests); local learning continues"
            else -> "AI help available"
        }
        val next = maxOf(
            if (hour.size >= HOURLY_LIMIT) hour.sortedBy { it.optLong("at") }[hour.size - HOURLY_LIMIT].optLong("at") + HOUR_MS else 0L,
            if (state.entries.size >= DAILY_LIMIT) state.entries.sortedBy { it.optLong("at") }[state.entries.size - DAILY_LIMIT].optLong("at") + DAY_MS else 0L
        )
        return TeacherBudgetSnapshot(state.entries.size, hour.size, state.entries.sumOf { it.optLong("input") },
            state.entries.sumOf { it.optLong("output") }, state.entries.count { it.optBoolean("reported") },
            state.entries.lastOrNull()?.let { "${it.optString("provider")}/${it.optString("model")}" }.orEmpty(), allowed, reason, next)
    }

    private fun save(state: State) {
        storage.write(KEY, state.json.put("seen", state.now).put("requests", JsonArray(state.entries)).toString())
    }

    private fun identifier(value: String): String = value.takeIf {
        it.matches(Regex("[A-Za-z0-9_.:/-]{1,120}")) && !it.startsWith("sk-") && !it.startsWith("gsk_") && !it.contains("://")
    } ?: "custom"

    companion object {
        const val DAILY_LIMIT = 24
        const val HOURLY_LIMIT = 6
        private const val DAY_MS = 86_400_000L
        private const val HOUR_MS = 3_600_000L
        private const val KEY = "teacher/usage"
    }
}
