package com.appgate.brain.test

import com.appgate.brain.json.Json
import com.appgate.brain.json.JsonObject
import java.io.File

/** Locates the recorded extractor observations regardless of the working directory Gradle uses. */
object Fixtures {
    private val candidates = listOf(
        "src/test/fixtures", "brain-core/src/test/fixtures", "../brain-core/src/test/fixtures"
    )

    fun dir(): File = candidates.map { File(it) }.firstOrNull { it.isDirectory }
        ?: (System.getProperty("brain.fixtures")?.let { File(it) }?.takeIf { it.isDirectory })
        ?: error("fixtures directory not found from ${File(".").absolutePath}")

    fun observationJson(name: String): String = File(dir(), "$name.observation.json").readText()

    fun observation(name: String): JsonObject = Json.parseObject(observationJson(name))
}
