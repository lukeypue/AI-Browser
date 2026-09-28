package com.appgate.brain.memory

import java.io.File

/**
 * Minimal durable key/value storage. Values are JSON strings. Implementations must be
 * atomic per write (temp file + rename) so a process death mid-write never corrupts memory.
 */
interface BrainStorage {
    fun read(key: String): String?
    fun write(key: String, value: String)
    fun delete(key: String)
    fun keys(prefix: String): List<String>
}

class InMemoryStorage : BrainStorage {
    private val map = LinkedHashMap<String, String>()
    @Synchronized override fun read(key: String): String? = map[key]
    @Synchronized override fun write(key: String, value: String) { map[key] = value }
    @Synchronized override fun delete(key: String) { map.remove(key) }
    @Synchronized override fun keys(prefix: String): List<String> = map.keys.filter { it.startsWith(prefix) }
    @Synchronized fun snapshot(): Map<String, String> = LinkedHashMap(map)
}

/**
 * File-backed storage: one file per key under [dir]. Used on Android (app-private files dir)
 * and in the JVM harness. Writes are atomic via temp file + rename.
 */
class FileBrainStorage(private val dir: File) : BrainStorage {
    init { dir.mkdirs() }

    private fun file(key: String): File = File(dir, encode(key) + ".json")

    @Synchronized override fun read(key: String): String? {
        val f = file(key)
        if (!f.exists()) return null
        return runCatching { f.readText(Charsets.UTF_8) }.getOrNull()
    }

    @Synchronized override fun write(key: String, value: String) {
        val target = file(key)
        val temp = File(dir, target.name + ".tmp")
        temp.writeText(value, Charsets.UTF_8)
        if (!temp.renameTo(target)) {
            target.writeText(value, Charsets.UTF_8)
            temp.delete()
        }
    }

    @Synchronized override fun delete(key: String) { file(key).delete() }

    @Synchronized override fun keys(prefix: String): List<String> =
        dir.listFiles()?.filter { it.name.endsWith(".json") }?.map { decode(it.name.removeSuffix(".json")) }?.filter { it.startsWith(prefix) } ?: emptyList()

    fun sizeBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    private fun encode(key: String): String = buildString {
        for (c in key) {
            if (c.isLetterOrDigit() || c == '-' || c == '_' || c == '.') append(c) else append('%').append(String.format("%02x", c.code))
        }
    }

    private fun decode(name: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < name.length) {
            val c = name[i]
            if (c == '%' && i + 2 < name.length + 0 && i + 2 <= name.length - 1) {
                sb.append(name.substring(i + 1, i + 3).toInt(16).toChar()); i += 3
            } else { sb.append(c); i++ }
        }
        return sb.toString()
    }
}
