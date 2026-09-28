package com.appgate.brain.json

/**
 * Tiny dependency-free JSON model, parser and writer.
 *
 * brain-core must run identically on Android, on the JVM test harness and inside the
 * offline replay lab, so it carries no third-party dependencies at all. This is a strict
 * RFC 8259 parser with a forgiving reader API (optString/optInt/...) so persistence code
 * stays short and never throws on missing fields.
 */
sealed class JsonValue {
    open val isNull: Boolean get() = false

    fun asObjectOrNull(): JsonObject? = this as? JsonObject
    fun asArrayOrNull(): JsonArray? = this as? JsonArray
    fun asStringOrNull(): String? = (this as? JsonString)?.value
    fun asDoubleOrNull(): Double? = (this as? JsonNumber)?.value
    fun asBooleanOrNull(): Boolean? = (this as? JsonBool)?.value

    override fun toString(): String = Json.write(this)
}

object JsonNull : JsonValue() {
    override val isNull: Boolean get() = true
}

data class JsonBool(val value: Boolean) : JsonValue() {
    override fun toString(): String = Json.write(this)
}

data class JsonNumber(val value: Double) : JsonValue() {
    override fun toString(): String = Json.write(this)
}

data class JsonString(val value: String) : JsonValue() {
    override fun toString(): String = Json.write(this)
}

class JsonArray(initial: List<JsonValue> = emptyList()) : JsonValue(), Iterable<JsonValue> {
    private val items = ArrayList<JsonValue>(initial)
    val size: Int get() = items.size
    operator fun get(index: Int): JsonValue = items[index]
    fun add(value: JsonValue?): JsonArray { items.add(value ?: JsonNull); return this }
    fun add(value: String?): JsonArray = add(value?.let { JsonString(it) })
    fun add(value: Number?): JsonArray = add(value?.let { JsonNumber(it.toDouble()) })
    fun add(value: Boolean?): JsonArray = add(value?.let { JsonBool(it) })
    fun clear(): JsonArray { items.clear(); return this }
    override fun iterator(): Iterator<JsonValue> = items.iterator()
    fun toList(): List<JsonValue> = items.toList()
    fun strings(): List<String> = items.mapNotNull { it.asStringOrNull() }
    fun objects(): List<JsonObject> = items.mapNotNull { it.asObjectOrNull() }
    override fun toString(): String = Json.write(this)
    override fun equals(other: Any?): Boolean = other is JsonArray && other.items == items
    override fun hashCode(): Int = items.hashCode()
}

class JsonObject(initial: Map<String, JsonValue> = emptyMap()) : JsonValue() {
    private val fields = LinkedHashMap<String, JsonValue>(initial)
    val keys: Set<String> get() = fields.keys
    val size: Int get() = fields.size
    fun has(key: String): Boolean = fields.containsKey(key) && !fields[key]!!.isNull
    operator fun get(key: String): JsonValue? = fields[key]
    fun put(key: String, value: JsonValue?): JsonObject { fields[key] = value ?: JsonNull; return this }
    fun put(key: String, value: String?): JsonObject = put(key, value?.let { JsonString(it) })
    fun put(key: String, value: Number?): JsonObject = put(key, value?.let { JsonNumber(it.toDouble()) })
    fun put(key: String, value: Boolean?): JsonObject = put(key, value?.let { JsonBool(it) })
    fun putStrings(key: String, values: Iterable<String>): JsonObject = put(key, JsonArray(values.map { JsonString(it) }))
    fun remove(key: String): JsonObject { fields.remove(key); return this }

    fun optString(key: String, default: String = ""): String = fields[key]?.asStringOrNull() ?: default
    fun optStringOrNull(key: String): String? = fields[key]?.asStringOrNull()?.takeIf { it.isNotBlank() }
    fun optDouble(key: String, default: Double = 0.0): Double = fields[key]?.asDoubleOrNull() ?: default
    fun optInt(key: String, default: Int = 0): Int = fields[key]?.asDoubleOrNull()?.toInt() ?: default
    fun optLong(key: String, default: Long = 0L): Long = fields[key]?.asDoubleOrNull()?.toLong() ?: default
    fun optBoolean(key: String, default: Boolean = false): Boolean = fields[key]?.asBooleanOrNull() ?: default
    fun optObject(key: String): JsonObject? = fields[key]?.asObjectOrNull()
    fun optArray(key: String): JsonArray? = fields[key]?.asArrayOrNull()
    fun optStrings(key: String): List<String> = optArray(key)?.strings() ?: emptyList()
    fun entries(): List<Pair<String, JsonValue>> = fields.entries.map { it.key to it.value }
    override fun toString(): String = Json.write(this)
    override fun equals(other: Any?): Boolean = other is JsonObject && other.fields == fields
    override fun hashCode(): Int = fields.hashCode()
}

class JsonParseException(message: String) : RuntimeException(message)

object Json {
    fun parse(text: String): JsonValue = Parser(text).parseDocument()

    fun parseObject(text: String): JsonObject =
        parse(text) as? JsonObject ?: throw JsonParseException("Expected a JSON object")

    fun parseObjectOrNull(text: String?): JsonObject? =
        if (text.isNullOrBlank()) null else runCatching { parseObject(text) }.getOrNull()

    fun obj(vararg pairs: Pair<String, Any?>): JsonObject {
        val o = JsonObject()
        pairs.forEach { (k, v) -> o.put(k, wrap(v)) }
        return o
    }

    fun arr(values: Iterable<Any?>): JsonArray = JsonArray(values.map { wrap(it) })

    fun wrap(value: Any?): JsonValue = when (value) {
        null -> JsonNull
        is JsonValue -> value
        is String -> JsonString(value)
        is Boolean -> JsonBool(value)
        is Number -> JsonNumber(value.toDouble())
        is Enum<*> -> JsonString(value.name)
        is Map<*, *> -> JsonObject().also { o -> value.forEach { (k, v) -> o.put(k.toString(), wrap(v)) } }
        is Iterable<*> -> JsonArray(value.map { wrap(it) })
        is Array<*> -> JsonArray(value.map { wrap(it) })
        else -> JsonString(value.toString())
    }

    fun write(value: JsonValue, pretty: Boolean = false): String {
        val sb = StringBuilder()
        write(value, sb, pretty, 0)
        return sb.toString()
    }

    fun quote(value: String): String {
        val sb = StringBuilder(value.length + 2)
        writeString(value, sb)
        return sb.toString()
    }

    private fun write(value: JsonValue, sb: StringBuilder, pretty: Boolean, depth: Int) {
        when (value) {
            is JsonNull -> sb.append("null")
            is JsonBool -> sb.append(if (value.value) "true" else "false")
            is JsonNumber -> writeNumber(value.value, sb)
            is JsonString -> writeString(value.value, sb)
            is JsonArray -> {
                if (value.size == 0) { sb.append("[]"); return }
                sb.append('[')
                var first = true
                for (item in value) {
                    if (!first) sb.append(',')
                    first = false
                    if (pretty) newline(sb, depth + 1)
                    write(item, sb, pretty, depth + 1)
                }
                if (pretty) newline(sb, depth)
                sb.append(']')
            }
            is JsonObject -> {
                if (value.size == 0) { sb.append("{}"); return }
                sb.append('{')
                var first = true
                for ((k, v) in value.entries()) {
                    if (!first) sb.append(',')
                    first = false
                    if (pretty) newline(sb, depth + 1)
                    writeString(k, sb)
                    sb.append(if (pretty) ": " else ":")
                    write(v, sb, pretty, depth + 1)
                }
                if (pretty) newline(sb, depth)
                sb.append('}')
            }
        }
    }

    private fun newline(sb: StringBuilder, depth: Int) {
        sb.append('\n')
        repeat(depth) { sb.append("  ") }
    }

    private fun writeNumber(d: Double, sb: StringBuilder) {
        if (d.isNaN() || d.isInfinite()) { sb.append("null"); return }
        if (d == Math.floor(d) && Math.abs(d) < 1e15) sb.append(d.toLong()) else sb.append(d)
    }

    private fun writeString(s: String, sb: StringBuilder) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ' || c == ' ' || c == ' ') {
                    sb.append("\\u").append(String.format("%04x", c.code))
                } else sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun parseDocument(): JsonValue {
            skipWs()
            val v = parseValue()
            skipWs()
            if (i != s.length) throw JsonParseException("Trailing characters at $i")
            return v
        }

        private fun parseValue(): JsonValue {
            if (i >= s.length) throw JsonParseException("Unexpected end of input")
            return when (val c = s[i]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> JsonString(parseString())
                't' -> literal("true", JsonBool(true))
                'f' -> literal("false", JsonBool(false))
                'n' -> literal("null", JsonNull)
                else -> if (c == '-' || c.isDigit()) parseNumber() else throw JsonParseException("Unexpected '$c' at $i")
            }
        }

        private fun literal(word: String, value: JsonValue): JsonValue {
            if (!s.startsWith(word, i)) throw JsonParseException("Bad literal at $i")
            i += word.length
            return value
        }

        private fun parseObject(): JsonObject {
            val o = JsonObject()
            i++ // {
            skipWs()
            if (peek() == '}') { i++; return o }
            while (true) {
                skipWs()
                if (peek() != '"') throw JsonParseException("Expected key at $i")
                val key = parseString()
                skipWs()
                if (peek() != ':') throw JsonParseException("Expected ':' at $i")
                i++
                skipWs()
                o.put(key, parseValue())
                skipWs()
                when (peek()) {
                    ',' -> { i++; continue }
                    '}' -> { i++; return o }
                    else -> throw JsonParseException("Expected ',' or '}' at $i")
                }
            }
        }

        private fun parseArray(): JsonArray {
            val a = JsonArray()
            i++ // [
            skipWs()
            if (peek() == ']') { i++; return a }
            while (true) {
                skipWs()
                a.add(parseValue())
                skipWs()
                when (peek()) {
                    ',' -> { i++; continue }
                    ']' -> { i++; return a }
                    else -> throw JsonParseException("Expected ',' or ']' at $i")
                }
            }
        }

        private fun parseString(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw JsonParseException("Unterminated string")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) throw JsonParseException("Bad escape")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) throw JsonParseException("Bad unicode escape")
                                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> throw JsonParseException("Bad escape \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun parseNumber(): JsonNumber {
            val start = i
            if (peek() == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-')) i++
            val text = s.substring(start, i)
            return JsonNumber(text.toDoubleOrNull() ?: throw JsonParseException("Bad number '$text'"))
        }

        private fun peek(): Char = if (i < s.length) s[i] else '\u0000'

        private fun skipWs() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++
        }
    }
}
