package com.example.icbcbalance.util

import java.text.NumberFormat
import java.util.Locale

object MoneyUtils {
    private val moneyPattern = Regex("(?:0|[1-9]\\d{0,2}(?:,\\d{3})*|[1-9]\\d*)(?:\\.\\d{1,2})?")

    fun parseMoneyToCents(text: String): Long? {
        val value = text.trim()
        if (!moneyPattern.matches(value)) return null
        val parts = value.replace(",", "").split('.')
        val yuan = parts[0].toLongOrNull() ?: return null
        val fraction = when (parts.size) {
            1 -> 0L
            2 -> parts[1].padEnd(2, '0').toLongOrNull() ?: return null
            else -> return null
        }
        return try { Math.addExact(Math.multiplyExact(yuan, 100L), fraction) }
        catch (_: ArithmeticException) { null }
    }

    fun format(cents: Long): String {
        val nf = NumberFormat.getNumberInstance(Locale.CHINA)
        nf.minimumFractionDigits = 2
        nf.maximumFractionDigits = 2
        return nf.format(java.math.BigDecimal.valueOf(cents, 2))
    }
}
