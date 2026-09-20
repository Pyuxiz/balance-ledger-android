package com.example.icbcbalance.parser

import com.example.icbcbalance.data.BankTransaction
import java.security.MessageDigest
import java.util.Locale

object TransactionMatcher {
    fun normalizedDescription(value: String): String = value
        .replace("请点击查看详情", "")
        .replace(Regex("(?:中国)?(?:工商|建设|农业|邮政储蓄|邮储|招商|交通|兴业)银行|中国银行|工银信使"), "")
        .lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    fun key(tx: BankTransaction): String = listOf(
        tx.cardLast4, tx.month, tx.day, tx.hour, tx.minute,
        tx.direction.name, tx.amountCents, normalizedDescription(tx.description)
    ).joinToString("|")

    fun fingerprint(tx: BankTransaction): String {
        val input = "${tx.source}|${key(tx)}"
        return MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
