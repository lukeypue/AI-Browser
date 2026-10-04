package com.appgate.brain.lab

import com.appgate.brain.engine.RendererTimeout
import com.appgate.brain.engine.RendererGone
import com.appgate.brain.json.Json
import com.appgate.brain.json.JsonObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Lab-only owned child; one outstanding request and a hard deadline. */
class JsonLineBridge(command: List<String>, directory: File) : AutoCloseable {
    private val process = ProcessBuilder(command).directory(directory).redirectError(ProcessBuilder.Redirect.INHERIT).start()
    private val input = process.inputStream.bufferedReader()
    private val output = process.outputStream.bufferedWriter()
    private val reader = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "browser-rpc-reader").apply { isDaemon = true } }
    private var sequence = 0
    @Synchronized fun request(operation: String, payload: JsonObject, timeoutMs: Long): JsonObject {
        if (!process.isAlive) throw RendererGone("practice renderer exited")
        val id = ++sequence
        output.write(JsonObject().put("id", id).put("op", operation).put("payload", payload).toString())
        output.newLine(); output.flush()
        val future = reader.submit<String?> { input.readLine() }
        val line = try { future.get(timeoutMs.coerceIn(50, 60000), TimeUnit.MILLISECONDS) }
        catch (_: TimeoutException) { close(); throw RendererTimeout("practice browser $operation exceeded its deadline") }
        catch (e: Exception) { close(); throw RendererGone("practice bridge disconnected: ${e.javaClass.simpleName}") }
        val response = Json.parseObject(line ?: throw RendererGone("practice renderer closed its output"))
        check(response.optInt("id") == id) { "practice response identity mismatch" }
        if (!response.optBoolean("ok")) throw RendererGone(response.optString("error", "practice renderer rejected request"))
        return response.optObject("result") ?: JsonObject()
    }
    fun isAlive(): Boolean = process.isAlive
    override fun close() {
        runCatching { process.descendants().forEach { it.destroy() } }
        process.destroy()
        if (!process.waitFor(500, TimeUnit.MILLISECONDS)) {
            runCatching { process.descendants().forEach { it.destroyForcibly() } }
            process.destroyForcibly()
        }
        reader.shutdownNow()
    }
}
