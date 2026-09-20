package com.example.icbcbalance.data

import com.example.icbcbalance.parser.TransactionMatcher
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

object BalanceEngine {
    private const val EVENT_LIMIT = 100
    private const val EVENT_AGE_MS = 7L * 24 * 60 * 60 * 1000

    fun manualCalibrate(old: BalanceState, card: String, balanceCents: Long, packageName: String, now: Long): BalanceState? {
        if (!Regex("\\d{4}").matches(card) || packageName.isBlank() || balanceCents < 0) return null
        return old.copy(balanceCents = balanceCents, checkpointBalanceCents = balanceCents,
            cardLast4 = card, configuredPackage = packageName.trim(), confidence = BalanceConfidence.MANUAL_CONFIRMED,
            lastUpdatedAt = now, provisionalTransactionCount = 0, calibrationAt = now,
            checkpointTransactionAt = now, processedEvents = old.processedEvents.map { it.copy(reconciled = true) },
            recentTransactions = old.recentTransactions,
            lastTransactionText = null)
    }

    fun apply(old: BalanceState, message: ParsedMessage): BalanceState {
        val tx = message.transaction
        if (old.cardLast4 != tx.cardLast4) return old
        val key = TransactionMatcher.key(tx)
        val fingerprint = TransactionMatcher.fingerprint(tx)
        if (old.processedEvents.any { it.fingerprint == fingerprint }) return old
        val transactionAt = transactionMillis(tx) ?: return old
        val now = tx.receivedAtMillis
        val kept = old.processedEvents.filter { kotlin.math.abs(now - it.timestamp) <= EVENT_AGE_MS }
        val signed = if (tx.direction == Direction.INCOME) tx.amountCents else -tx.amountCents
        val event = ProcessedEvent(fingerprint, key, tx.source, now, false, transactionAt, signed)
        val recent = RecentTransaction(key, tx.direction, tx.amountCents, tx.description, now, tx.source)

        if (tx.source == Source.ICBC_NOTIFICATION) {
            // The bank checkpoint already includes transactions at or before its timestamp.
            val alreadyCovered = transactionAt <= old.checkpointTransactionAt ||
                kept.any { it.transactionKey == key && it.source == Source.ICBC_SMS }
            val eventToStore = event.copy(reconciled = alreadyCovered)
            if (alreadyCovered || kept.any { it.transactionKey == key && it.source == Source.ICBC_NOTIFICATION }) {
                return old.copy(processedEvents = (kept + eventToStore).takeLast(EVENT_LIMIT))
            }
            if (old.confidence == BalanceConfidence.UNKNOWN) {
                // Keep transactions until the first balance checkpoint arrives. Never assume a zero baseline.
                return old.copy(processedEvents = (kept + event).takeLast(EVENT_LIMIT),
                    recentTransactions = (listOf(recent) + old.recentTransactions).take(10),
                    lastTransactionText = transactionText(tx), lastUpdatedAt = now)
            }
            val next = safeAdd(old.balanceCents, signed) ?: return old
            if (next < 0) return old
            return old.copy(balanceCents = next, confidence = BalanceConfidence.ESTIMATED,
                lastUpdatedAt = now, provisionalTransactionCount = old.provisionalTransactionCount + 1,
                lastTransactionText = transactionText(tx),
                processedEvents = (kept + event).takeLast(EVENT_LIMIT),
                recentTransactions = (listOf(recent) + old.recentTransactions).take(10))
        }

        val bankBalance = message.balanceCents ?: return old
        if (bankBalance < 0) return old
        // An older SMS cannot roll back a newer bank confirmation or manual calibration.
        if (transactionAt < old.checkpointTransactionAt) {
            return old.copy(processedEvents = (kept + event.copy(reconciled = true)).takeLast(EVENT_LIMIT))
        }
        val rebased = kept.map { prior ->
            if (prior.source == Source.ICBC_NOTIFICATION &&
                (prior.transactionAt <= transactionAt || prior.transactionKey == key)) prior.copy(reconciled = true)
            else prior
        }
        val pending = rebased.filter { it.source == Source.ICBC_NOTIFICATION && !it.reconciled && it.transactionAt > transactionAt }
            .distinctBy { it.transactionKey }
        var nextBalance = bankBalance
        for (item in pending) {
            nextBalance = safeAdd(nextBalance, item.signedAmountCents) ?: return old
            if (nextBalance < 0) return old
        }
        return old.copy(balanceCents = nextBalance, checkpointBalanceCents = bankBalance,
            checkpointTransactionAt = transactionAt,
            confidence = if (pending.isEmpty()) BalanceConfidence.BANK_CONFIRMED else BalanceConfidence.ESTIMATED,
            lastUpdatedAt = now, lastBankConfirmedAt = now,
            provisionalTransactionCount = pending.size,
            lastTransactionText = transactionText(tx),
            processedEvents = (rebased + event.copy(reconciled = true)).takeLast(EVENT_LIMIT),
            recentTransactions = (listOf(recent) + old.recentTransactions.filterNot { it.transactionKey == key }).take(10))
    }

    private fun safeAdd(a: Long, b: Long): Long? = try { Math.addExact(a, b) } catch (_: ArithmeticException) { null }

    private fun transactionText(tx: BankTransaction): String =
        "${if (tx.direction == Direction.INCOME) "+" else "-"}${tx.amountCents} ${tx.description}"

    // Select the nearest occurrence of this month/day/time to the receipt date, including New Year.
    fun transactionMillis(tx: BankTransaction): Long? = try {
        val zone = ZoneId.systemDefault()
        val receipt = java.time.Instant.ofEpochMilli(tx.receivedAtMillis).atZone(zone).toLocalDateTime()
        (-1..1).mapNotNull { offset ->
            try { LocalDateTime.of(receipt.year + offset, tx.month, tx.day, tx.hour, tx.minute) }
            catch (_: Exception) { null }
        }.minByOrNull { kotlin.math.abs(ChronoUnit.MINUTES.between(it, receipt)) }
            ?.atZone(zone)?.toInstant()?.toEpochMilli()
            ?.takeIf { kotlin.math.abs(it - tx.receivedAtMillis) <= EVENT_AGE_MS }
    } catch (_: Exception) { null }
}
