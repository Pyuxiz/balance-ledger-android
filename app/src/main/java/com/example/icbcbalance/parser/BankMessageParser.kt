package com.example.icbcbalance.parser

import com.example.icbcbalance.data.BankTransaction
import com.example.icbcbalance.data.Delivery
import com.example.icbcbalance.data.Direction
import com.example.icbcbalance.data.ParsedMessage
import com.example.icbcbalance.data.Source
import com.example.icbcbalance.util.MoneyUtils
import java.time.Instant
import java.time.ZoneId

object BankMessageParser {
    private val icbcTransactionPattern = Regex(
        "尾号(\\d{4})卡(\\d{1,2})月(\\d{1,2})日(\\d{1,2}):(\\d{2})(?:网上银行)?(收入|支出)\\((.{1,100})\\)([0-9][0-9,.]*)元"
    )
    private val balancePattern = Regex(
        "(?:交易后余额|可用余额|账户余额|活期余额|余额)(?:为|是|[:：])?\\s*(?:人民币|RMB|CNY|[¥￥])?\\s*([0-9][0-9,.]*)\\s*元?",
        RegexOption.IGNORE_CASE
    )
    private val cardPatterns = listOf(
        Regex("尾号(?:为)?\\s*(\\d{4})\\s*卡"),
        Regex("(?:尾号(?:为)?|卡号末(?:四|4)位|账号末(?:四|4)位|账户末(?:四|4)位|卡尾号|账号尾号|账户尾号)(?:为|是|[:：])?\\s*(\\d{4})(?!\\d)"),
        Regex("(?:储蓄卡|借记卡|信用卡|银行卡|卡|账户|账号)[*＊xX·•\\s]*(\\d{4})(?!\\d)")
    )
    private val dateTimePatterns = listOf(
        Regex("(\\d{1,2})月(\\d{1,2})日\\s*(\\d{1,2})(?::|时)(\\d{2})分?"),
        Regex("(?:^|\\D)(\\d{1,2})[-/](\\d{1,2})\\s+(\\d{1,2}):(\\d{2})")
    )
    private val datePattern = Regex("(\\d{1,2})月(\\d{1,2})日")
    private val incomeWords = listOf("收入", "入账", "转入", "存入", "存款", "到账", "汇入", "收款", "贷记", "退款", "代发")
    private val expenseWords = listOf("支出", "消费", "付款", "转出", "取款", "扣款", "扣费", "缴费", "还款", "借记")
    private val moneyPattern = Regex(
        "(?:(?:人民币|RMB|CNY|[¥￥])\\s*([0-9][0-9,.]*)\\s*元?|([0-9][0-9,.]*)\\s*元)",
        RegexOption.IGNORE_CASE
    )
    private val descriptionPattern = Regex("(?:摘要|用途|商户|交易对方|备注)(?:为|是|[:：])\\s*([^,，。;；]{1,60})")

    fun parseNotification(text: String, now: Long): ParsedMessage? = parse(text, now, Delivery.BANK_NOTIFICATION)
    fun parseSms(text: String, now: Long): ParsedMessage? = parse(text, now, Delivery.SMS_BROADCAST)

    fun normalize(text: String): String = text.replace('（', '(').replace('）', ')')
        .replace('【', '[').replace('】', ']').replace('：', ':').replace('，', ',')
        .replace('￥', '¥').replace(Regex("[\\u00a0\\t\\r\\n]+"), " ")

    fun matchesBank(text: String, sender: String, configuredSender: String, signature: String): Boolean {
        val normalizedSender = phone(sender)
        val senderMatch = normalizedSender.isNotBlank() && BankProfiles.tokens(configuredSender)
            .map(::phone).any { it.isNotBlank() && it == normalizedSender }
        val body = normalize(text).lowercase()
        val looksLikeTransaction = cardPatterns.any { it.containsMatchIn(body) } && direction(body) != null
        val signatureMatch = looksLikeTransaction && BankProfiles.tokens(signature)
            .any { body.contains(normalize(it).lowercase()) }
        return senderMatch || signatureMatch
    }

    private fun parse(text: String, now: Long, delivery: Delivery): ParsedMessage? {
        if (text.isBlank() || text.length > 2000) return null
        val normalized = normalize(text)
        val exact = parseIcbc(normalized, now, delivery)
        if (exact != null) return exact

        val tail = cardPatterns.firstNotNullOfOrNull { it.find(normalized)?.groupValues?.getOrNull(1) } ?: return null
        val directionMatch = direction(normalized) ?: return null
        val amountMatch = moneyPattern.findAll(normalized)
            .filterNot { match ->
                val from = (match.range.first - 12).coerceAtLeast(0)
                val context = normalized.substring(from, match.range.first)
                listOf("余额", "额度", "欠款", "账单").any(context::contains)
            }
            .minByOrNull { distance(it.range.first, directionMatch.index) }
            ?.takeIf { distance(it.range.first, directionMatch.index) <= 60 } ?: return null
        val amountText = amountMatch.groupValues[1].ifBlank { amountMatch.groupValues[2] }
        val amount = MoneyUtils.parseMoneyToCents(amountText) ?: return null
        if (amount <= 0) return null

        val (month, day, hour, minute) = transactionTime(normalized, now) ?: return null
        val balance = if (delivery == Delivery.BANK_NOTIFICATION) null else
            balancePattern.find(normalized)?.groupValues?.getOrNull(1)?.let(MoneyUtils::parseMoneyToCents)
        val source = if (balance != null) Source.ICBC_SMS else Source.ICBC_NOTIFICATION
        val description = description(normalized, directionMatch, amountMatch)
        return ParsedMessage(
            BankTransaction(tail, month, day, hour, minute, directionMatch.direction, amount,
                description, source, now),
            balanceCents = balance,
            delivery = delivery
        )
    }

    private fun parseIcbc(text: String, now: Long, delivery: Delivery): ParsedMessage? {
        val match = icbcTransactionPattern.find(text) ?: return null
        val month = match.groupValues[2].toIntOrNull() ?: return null
        val day = match.groupValues[3].toIntOrNull() ?: return null
        val hour = match.groupValues[4].toIntOrNull() ?: return null
        val minute = match.groupValues[5].toIntOrNull() ?: return null
        if (!validTime(month, day, hour, minute)) return null
        val amount = MoneyUtils.parseMoneyToCents(match.groupValues[8]) ?: return null
        if (amount <= 0) return null
        val balance = if (delivery == Delivery.BANK_NOTIFICATION) null else
            balancePattern.find(text)?.groupValues?.getOrNull(1)?.let(MoneyUtils::parseMoneyToCents)
        val source = if (balance != null) Source.ICBC_SMS else Source.ICBC_NOTIFICATION
        val description = match.groupValues[7].trim().takeIf(String::isNotBlank) ?: return null
        return ParsedMessage(
            BankTransaction(match.groupValues[1], month, day, hour, minute,
                if (match.groupValues[6] == "收入") Direction.INCOME else Direction.EXPENSE,
                amount, description, source, now),
            balanceCents = balance,
            delivery = delivery
        )
    }

    private data class DirectionMatch(val direction: Direction, val index: Int, val word: String)

    private fun direction(text: String): DirectionMatch? {
        val matches = buildList {
            incomeWords.forEach { word -> text.indexOf(word).takeIf { it >= 0 }?.let { add(DirectionMatch(Direction.INCOME, it, word)) } }
            expenseWords.forEach { word -> text.indexOf(word).takeIf { it >= 0 }?.let { add(DirectionMatch(Direction.EXPENSE, it, word)) } }
        }
        return matches.minByOrNull { it.index }
    }

    private fun transactionTime(text: String, now: Long): List<Int>? {
        dateTimePatterns.forEach { pattern ->
            val match = pattern.find(text) ?: return@forEach
            val values = (1..4).map { match.groupValues[it].toIntOrNull() ?: return@forEach }
            if (validTime(values[0], values[1], values[2], values[3])) return values
        }
        val received = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
        val date = datePattern.find(text)
        val month = date?.groupValues?.getOrNull(1)?.toIntOrNull() ?: received.monthValue
        val day = date?.groupValues?.getOrNull(2)?.toIntOrNull() ?: received.dayOfMonth
        return listOf(month, day, received.hour, received.minute).takeIf {
            validTime(it[0], it[1], it[2], it[3])
        }
    }

    private fun description(text: String, direction: DirectionMatch, amount: MatchResult): String {
        val between = text.substring(
            (direction.index + direction.word.length).coerceAtMost(amount.range.first),
            amount.range.first
        ).trim().trim(',', '，', '。', ':', '：', ';', '；')
        val unwrapped = unwrapOuterParentheses(between)
            .replace(Regex("^(?:人民币|RMB|CNY|[¥￥])\\s*", RegexOption.IGNORE_CASE), "")
            .trim()
        if (unwrapped.isNotBlank()) return unwrapped.take(100)
        val labeled = descriptionPattern.find(text)?.groupValues?.getOrNull(1)?.trim()
        if (!labeled.isNullOrBlank()) return labeled
        val start = (amount.range.first - 18).coerceAtLeast(0)
        val end = (amount.range.last + 19).coerceAtMost(text.lastIndex)
        val nearby = text.substring(start, end + 1)
            .replace(moneyPattern, "")
            .replace(balancePattern, "")
            .replace(Regex("[\\[\\](),，。:：;；]"), " ")
            .replace(Regex("\\s+"), " ").trim()
        return nearby.take(60).ifBlank { if (direction.direction == Direction.INCOME) "收入" else "支出" }
    }

    /** Removes one pair only when it encloses the complete value, preserving nested merchant details. */
    private fun unwrapOuterParentheses(value: String): String {
        if (value.length < 2 || value.first() != '(' || value.last() != ')') return value
        var depth = 0
        value.forEachIndexed { index, char ->
            if (char == '(') depth++
            if (char == ')') depth--
            if (depth == 0 && index < value.lastIndex) return value
            if (depth < 0) return value
        }
        return if (depth == 0) value.substring(1, value.lastIndex).trim() else value
    }

    private fun phone(value: String): String {
        return value.trim().removePrefix("+86").removePrefix("0086").filter(Char::isDigit)
    }

    private fun distance(a: Int, b: Int): Int = kotlin.math.abs(a - b)
    private fun validTime(month: Int, day: Int, hour: Int, minute: Int): Boolean =
        month in 1..12 && day in 1..31 && hour in 0..23 && minute in 0..59
}
