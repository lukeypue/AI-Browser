package com.appgate.brain.lab

import com.appgate.brain.json.JsonObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

fun main(args:Array<String>) {
    require(args.size>=6) { "Arguments: node renderer-script checkpoint report seed training-count [evaluation-count]" }
    val checkpoint=File(args[2]);val output=File(args[3]);val seed=args[4].toInt()
    val input=BrowserCheckpoint.load(checkpoint)
    BrowserRenderer(args[0],File(args[1])).use { renderer->
        val (report,snapshot)=BrowserTrainer(renderer) { progress->println(progress.put("type","progress")) }.batch(input,seed,args[5].toInt(),args.getOrNull(6)?.toInt()?:4)
        BrowserCheckpoint.save(checkpoint,snapshot)
        output.absoluteFile.parentFile.mkdirs()
        val temporary=File(output.parentFile,output.name+".tmp")
        temporary.writeText(report.toString())
        try{Files.move(temporary.toPath(),output.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)}
        catch(_:java.nio.file.AtomicMoveNotSupportedException){Files.move(temporary.toPath(),output.toPath(),StandardCopyOption.REPLACE_EXISTING)}
        println(JsonObject().put("type","complete").put("report",output.absolutePath).put("compiled_skills",report.optInt("compiled_skills")))
    }
}
