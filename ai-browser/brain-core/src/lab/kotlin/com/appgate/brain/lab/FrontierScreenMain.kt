package com.appgate.brain.lab
import com.appgate.brain.json.*
import com.appgate.brain.memory.FileBrainStorage
import java.io.File

fun main(args: Array<String>) {
    require(args.size >= 2) { "mode and fresh output directory required" }
    val mode = args[0]
    val output = File(args[1])
    val lab = FrontierScreen()
    val report = when (mode) {
        "capture" -> {
            require(args.size in 2..3)
            lab.capture(output, if (args.size == 3) loadFrontierMemory(File(args[2])) else emptyMap())
        }
        "scripted" -> lab.run(output)
        "replay" -> {
            require(args.size in 3..4)
            val initial = if (args.size == 4) {
                loadFrontierMemory(File(args[3]))
            } else emptyMap()
            lab.run(output, File(args[2]).readText(), "RECORDED_EXTERNAL_CANDIDATE", initial)
        }
        "evaluate" -> {
            require(args.size == 3 && !output.exists())
            val frozen = loadFrontierMemory(File(args[2]))
            require(frozen.isNotEmpty())
            output.mkdirs()
            lab.evaluate(output, frozen).put("fresh_jvm_memory_reload", true)
        }
        else -> error("unknown lab mode")
    }
    output.mkdirs()
    File(output, "report.json").writeText(report.toString())
    println(report.toString())
}


private fun loadFrontierMemory(directory: File): Map<String, String> {
    require(directory.isDirectory) { "existing experiment memory required" }
    val store = FileBrainStorage(directory)
    return store.keys("").filterNot { it.startsWith("ledger/") }.associateWith { store.read(it)!! }
}
