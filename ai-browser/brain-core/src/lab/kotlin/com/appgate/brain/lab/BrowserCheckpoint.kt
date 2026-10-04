package com.appgate.brain.lab

import com.appgate.brain.json.*
import com.appgate.brain.memory.InMemoryStorage
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object BrowserCheckpoint {
    fun fork(snapshot:Map<String,String>)=InMemoryStorage().apply { snapshot.forEach { (k,v)->write(k,v) } }
    fun load(file:File):Map<String,String> {
        if(!file.exists())return emptyMap()
        val objectValue=runCatching { Json.parseObject(file.readText()) }.getOrNull()
            ?:throw IllegalArgumentException("Saved practice memory is damaged. Keep it for review and use a new trainer folder.")
        require(objectValue.optInt("schema")==1) { "Unsupported practice memory version" }
        val values=objectValue.optObject("values")?:throw IllegalArgumentException("Practice memory values missing")
        return values.entries().associate { (k,v)->k to (v.asStringOrNull()?:throw IllegalArgumentException("Invalid practice memory value")) }
    }
    fun save(file:File,snapshot:Map<String,String>) {
        file.absoluteFile.parentFile.mkdirs()
        val values=JsonObject();snapshot.forEach { (k,v)->values.put(k,v) }
        val temp=File(file.parentFile,file.name+".tmp")
        temp.writeText(JsonObject().put("schema",1).put("values",values).toString())
        try { Files.move(temp.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE) }
        catch (_:java.nio.file.AtomicMoveNotSupportedException) { Files.move(temp.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING) }
    }
}
