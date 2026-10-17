package com.appgate.brain.lab

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Four positional files. "-" proposal means frozen evaluation without training. Never writes champion. */
fun main(args:Array<String>) {
    require(args.size==4) { "usage: ServerBrainMain proposal|- champion-memory candidate-memory report" }
    val outputs=listOf(File(args[2]).canonicalFile,File(args[3]).canonicalFile)
    val inputs=listOf(File(args[1]).canonicalFile)+if(args[0]=="-") emptyList() else listOf(File(args[0]).canonicalFile)
    require(outputs.distinct().size==2 && outputs.none { it in inputs }) { "outputs_must_not_alias_inputs" }
    val proposal=if(args[0]=="-") null else File(args[0]).readText(Charsets.UTF_8)
    val champion=File(args[1])
    val prior=if(champion.exists()) ServerBrainLearning.decodeMemory(champion.readText(Charsets.UTF_8)) else emptyMap()
    val result=ServerBrainLearning().cycle(proposal,prior)
    if(champion.exists()) result.report.put("current_memory_sha256",ServerBrainLearning.sha256(champion.readBytes()))
    fun atomicWrite(path:String,text:String) {
        val target=File(path).absoluteFile; target.parentFile.mkdirs()
        val temp=Files.createTempFile(target.parentFile.toPath(),target.name,".tmp")
        try {
            Files.write(temp,text.toByteArray(Charsets.UTF_8))
            Files.move(temp,target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temp) }
    }
    atomicWrite(args[2],ServerBrainLearning.encodeMemory(result.snapshot))
    atomicWrite(args[3],result.report.toString())
}
