package com.appgate.brain.planner

import com.appgate.brain.json.Json
import com.appgate.brain.json.JsonObject
import com.appgate.brain.memory.BrainStorage
import java.util.UUID

enum class PlannerConnectionStatus { READY, AUTHENTICATION_REJECTED, STORAGE_UNAVAILABLE }

/** Only connection state: never credentials, endpoints, request text or website learning. */
class PlannerAuthenticationHold(private val storage: BrainStorage) {
    private data class State(val generation: String, val blocked: Boolean)
    private val rejectedInProcess = mutableSetOf<String>()

    fun status(): PlannerConnectionStatus = synchronized(lock) {
        val state = read() ?: return@synchronized PlannerConnectionStatus.STORAGE_UNAVAILABLE
        persistKnownRejection(state)
        if (state.blocked || state.generation in rejectedInProcess) PlannerConnectionStatus.AUTHENTICATION_REJECTED
        else PlannerConnectionStatus.READY
    }

    /** Only an explicit settings SAVE calls this; clocks, tasks and allowance toggles do not. */
    fun allowRetry() = synchronized(lock) {
        save(State(UUID.randomUUID().toString(), false))
        rejectedInProcess.clear()
    }

    internal fun ticket(): String? = synchronized(lock) { read()?.generation }

    internal fun requireAllowed(ticket: String?) = synchronized(lock) {
        val state = read()
        state?.let(::persistKnownRejection)
        if (ticket == null || state == null || state.generation != ticket || state.blocked || ticket in rejectedInProcess)
            throw PlannerRefused("AI connection is paused; save AI planner settings to retry")
    }

    internal fun rejected(ticket: String?) = synchronized(lock) {
        if (ticket == null) return@synchronized
        val state = read()
        if (state != null && state.generation != ticket) return@synchronized // An old response cannot hold a newer SAVE.
        rejectedInProcess.add(ticket) // Preserve rejection even if the read itself temporarily failed.
        if (state == null) return@synchronized
        // A write failure still holds this process, and must not hide the original HTTP401.
        runCatching { save(state.copy(blocked = true)) }
    }

    /** Existing allowlisted boundary metadata; this is not another request/error counter. */
    fun diagnostic(): JsonObject = JsonObject().also {
        when (status()) {
            PlannerConnectionStatus.AUTHENTICATION_REJECTED -> it.put("planner_failure", "AUTHENTICATION")
            PlannerConnectionStatus.STORAGE_UNAVAILABLE -> it.put("planner_failure", "UNKNOWN")
            PlannerConnectionStatus.READY -> Unit
        }
    }

    private fun persistKnownRejection(state: State) {
        // Recover durability once a temporarily unreadable/unwritable store is available again.
        if (!state.blocked && state.generation in rejectedInProcess) runCatching { save(state.copy(blocked = true)) }
    }

    private fun read(): State? {
        val result = runCatching { storage.readResult(KEY) }.getOrNull() ?: return null
        if (result.failed) return null
        val raw = result.value ?: return State("initial", false)
        val json = Json.parseObjectOrNull(raw) ?: return null
        if (json.keys != setOf("schema", "generation", "blocked") || json["schema"]?.asDoubleOrNull() != 1.0) return null
        val generation = json["generation"]?.asStringOrNull() ?: return null
        if (generation != "initial" && runCatching { UUID.fromString(generation).toString() == generation }.getOrDefault(false).not()) return null
        return State(generation, json["blocked"]?.asBooleanOrNull() ?: return null)
    }

    private fun save(state: State) = storage.write(KEY, JsonObject().put("schema", 1)
        .put("generation", state.generation).put("blocked", state.blocked).toString())

    companion object {
        private const val KEY = "connection/state"
        private val lock = Any()
    }
}

/** Gate before delegate accounting/transport, preserving the actual first error and provider. */
class AuthenticationGuardedClient(private val delegate: PlannerClient, private val hold: PlannerAuthenticationHold) : PlannerClient {
    private val ticket = hold.ticket()
    override val describe: String get() = delegate.describe
    override val provider: PlannerProvider? get() = delegate.provider
    override fun complete(instructions: String, input: String, schemaName: String, schema: JsonObject, maxOutputTokens: Int): String {
        hold.requireAllowed(ticket)
        return try { delegate.complete(instructions, input, schemaName, schema, maxOutputTokens) }
        catch (error: Exception) {
            if (error is PlannerHttpException && error.code == 401) hold.rejected(ticket)
            throw error
        }
    }
}
