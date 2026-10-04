package com.appgate.brain.lab

import java.io.File
import java.security.MessageDigest
import com.appgate.brain.json.JsonObject

fun main(args: Array<String>) {
    val start = System.nanoTime()
    val report = SimulationTrainer().experiment()
    val root = File(".").canonicalFile
    fun git(vararg args: String): String = runCatching {
        val process = ProcessBuilder(listOf("git") + args).directory(root).redirectErrorStream(true).start()
        val value = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor() == 0) value else "unavailable"
    }.getOrDefault("unavailable")
    val files = listOf(File(root, "src/main"), File(root, "src/lab")).flatMap { it.walkTopDown().filter(File::isFile).toList() } + File(root, "build.gradle")
    val digest = MessageDigest.getInstance("SHA-256")
    files.sortedBy { it.relativeTo(root).invariantSeparatorsPath }.forEach {
        digest.update(it.relativeTo(root).invariantSeparatorsPath.toByteArray(Charsets.UTF_8))
        digest.update(0.toByte()); digest.update(it.readBytes()); digest.update(0.toByte())
    }
    report.put("source", JsonObject().put("revision", git("rev-parse", "HEAD"))
        .put("engine_and_lab_sha256", digest.digest().joinToString("") { "%02x".format(it) })
        .put("dirty_engine_or_lab", git("status", "--porcelain", "--", "src/main", "src/lab", "build.gradle").isNotEmpty())
        .put("rollback_revision", "38eccb4cf702002fea0893c33c6c1a6d5f51bde6"))
    report.put("experiment_elapsed_ms", (System.nanoTime() - start) / 1_000_000)
    val target = File(args.firstOrNull() ?: "build/reports/brain/simulation.json")
    target.parentFile?.mkdirs()
    target.writeText(report.toString())
    println("Report: ${target.absolutePath}")
    println("Baseline: ${report.optObject("baseline")}")
    println("After: ${report.optObject("after_training")}")
    println("Unseen: ${report.optObject("unseen_after_training")}")
}
