package com.appgate.brain.util

import java.security.MessageDigest

object Hashing {
    fun sha256Hex(input: String, length: Int = 24): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder()
        for (b in bytes) sb.append(String.format("%02x", b))
        return sb.substring(0, minOf(length, sb.length))
    }

    fun short(input: String): String = sha256Hex(input, 16)
}

/** Text helpers for card/detail parsing. All deterministic, locale-independent (US units). */
object Text {
    private val ws = Regex("\\s+")
    private val money = Regex("\\$\\s?([0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\\.[0-9]{2})?")
    private val moneyK = Regex("\\$\\s?([0-9]+(?:\\.[0-9])?)\\s?[kK]\\b")
    private val miles = Regex("(?i)\\b([0-9]{1,3}(?:,[0-9]{3})+|[0-9]{4,6})\\s?(?:k\\s?)?(?:miles?|mi\\b|mileage)")
    private val milesK = Regex("(?i)\\b([0-9]{1,3})\\s?k\\s?(?:miles?|mi\\b)")
    private val year = Regex("\\b(19[5-9][0-9]|20[0-4][0-9])\\b")
    private val perMonth = Regex("(?i)/\\s?mo(nth)?|per\\s+month|monthly")

    fun clean(value: String?): String = (value ?: "").replace(ws, " ").trim()

    fun tokens(value: String): List<String> = Regex("[a-z0-9]+(?:\\.[0-9]+)?").findAll(value.lowercase()).map { it.value }.toList()

    fun truncate(value: String, max: Int): String = if (value.length <= max) value else value.substring(0, max)

    /** First plausible asking price in text; ignores monthly payment figures. */
    fun price(text: String): Int? {
        val cleaned = text
        val candidates = mutableListOf<Int>()
        moneyK.findAll(cleaned).forEach { m ->
            val v = m.groupValues[1].toDoubleOrNull()?.times(1000)?.toInt()
            if (v != null && !isMonthly(cleaned, m.range.last)) candidates += v
        }
        money.findAll(cleaned).forEach { m ->
            val v = m.groupValues[1].replace(",", "").toIntOrNull()
            if (v != null && v >= 1 && !isMonthly(cleaned, m.range.last)) candidates += v
        }
        return candidates.firstOrNull()
    }

    private fun isMonthly(text: String, endIndex: Int): Boolean {
        val tail = text.substring(endIndex + 1, minOf(text.length, endIndex + 14))
        return perMonth.containsMatchIn(tail)
    }

    fun mileage(text: String): Int? {
        milesK.find(text)?.let { m -> return m.groupValues[1].toIntOrNull()?.times(1000) }
        miles.find(text)?.let { m -> return m.groupValues[1].replace(",", "").toIntOrNull() }
        return null
    }

    fun year(text: String): Int? = year.find(text)?.value?.toIntOrNull()

    fun parseAmount(raw: String): Int? {
        val t = raw.lowercase().replace(",", "").replace("$", "").trim()
        val k = Regex("^([0-9]+(?:\\.[0-9]+)?)\\s?k$").find(t)
        if (k != null) return k.groupValues[1].toDoubleOrNull()?.times(1000)?.toInt()
        return t.toDoubleOrNull()?.toInt()
    }

    fun formatNumber(n: Int): String = "%,d".format(n)

    fun containsAll(haystack: String, needle: String): Boolean {
        val h = tokens(haystack).toSet()
        val needed = tokens(needle)
        return needed.isNotEmpty() && needed.all { it in h }
    }
}
