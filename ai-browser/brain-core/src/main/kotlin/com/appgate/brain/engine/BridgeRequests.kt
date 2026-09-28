package com.appgate.brain.engine

/** Each request belongs to exactly one document port. Old ports cannot take new replies. */
class BridgeRequests<O : Any, V> {
    private data class Entry<O, V>(val owner: O, val value: V)
    private val entries = mutableMapOf<Int, Entry<O, V>>()
    @Synchronized fun put(id: Int, owner: O, value: V) { entries[id] = Entry(owner, value) }
    @Synchronized fun take(id: Int, owner: O): V? = entries[id]?.takeIf { it.owner === owner }?.let { entries.remove(id); it.value }
    @Synchronized fun remove(id: Int): V? = entries.remove(id)?.value
    @Synchronized fun removeOwner(owner: O): List<V> {
        val ids = entries.filterValues { it.owner === owner }.keys.toList()
        return ids.mapNotNull { entries.remove(it) }.map { it.value }
    }
    @Synchronized fun drain(): List<V> = entries.values.map { it.value }.also { entries.clear() }
}
