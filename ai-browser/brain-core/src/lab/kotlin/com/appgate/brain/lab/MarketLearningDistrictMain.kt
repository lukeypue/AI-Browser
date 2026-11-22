package com.appgate.brain.lab

import java.io.File
import java.security.MessageDigest
import com.appgate.brain.json.JsonObject

/** Standalone experiment; never selects or updates a running service's memory. */
fun main(args: Array<String>) {
    require(args.size == 2) { "fresh output directory and brain-core source directory required" }
    val output = File(args[0])
    require(!output.exists()) { "output directory must not already exist" }
    val core = File(args[1]).canonicalFile
    val sources = listOf("src/main/kotlin", "src/lab/kotlin").flatMap { path ->
        File(core, path).also { require(it.isDirectory) }.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }.sortedBy { it.relativeTo(core).invariantSeparatorsPath }
    fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun git(vararg args: String): String {
        val p = ProcessBuilder(listOf("git", "-C", core.path) + args).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val value = p.inputStream.bufferedReader().readText().trim()
        check(p.waitFor() == 0) { "source Git provenance unavailable" }
        return value
    }
    val revision = git("rev-parse", "HEAD").also { require(it.matches(Regex("[0-9a-f]{40}"))) }
    val sourceHash = sha(sources.joinToString("\n") { it.relativeTo(core).invariantSeparatorsPath + "\u0000" + sha(it.readBytes()) }.toByteArray(Charsets.UTF_8))
    check(output.mkdirs())
    val report = MarketLearningDistrict().experiment(output).put("source", JsonObject()
        .put("revision", revision).put("engine_and_lab_sha256", sourceHash)
        .put("provenance_scope", "supplied source-tree hash; not executing-binary attestation")
        .put("hash_scheme", "sha256(sorted relative Kotlin path + NUL + file SHA256, joined by LF; main and lab)")
        .put("dirty_engine_or_lab", git("status", "--porcelain", "--", "src/main/kotlin", "src/lab/kotlin").isNotBlank()))
    File(output, "report.json").writeText(report.toString())
    println(report.toString())
}
