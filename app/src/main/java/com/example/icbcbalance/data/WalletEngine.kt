package com.example.icbcbalance.data

import com.example.icbcbalance.parser.TransactionMatcher
import com.google.gson.Gson

/** Pure operations shared by migration, ingestion and tests. Ledger retention is independent of dedup. */
object WalletEngine {
    fun migrate(old: BalanceState): WalletState {
        if (old.cardLast4.isBlank()) return WalletState()
        val id = "legacy-${old.cardLast4}"
        val ledger = old.recentTransactions.distinctBy { it.transactionKey }.map { tx ->
            LedgerEntry("$id|${tx.transactionKey}", id, tx.transactionKey,
                old.processedEvents.find { it.transactionKey == tx.transactionKey }?.transactionAt ?: tx.timestamp,
                tx.direction, tx.amountCents, tx.description,
                listOf(if (tx.source == Source.ICBC_SMS) Delivery.SMS_BROADCAST else Delivery.BANK_NOTIFICATION),
                receivedAt = tx.timestamp)
        }
        return WalletState(accounts = listOf(CardAccount(id = id, balance = old)), selectedAccountId = id,
            ledger = ledger, widget = WidgetOptions(accountId = id))
    }

    fun decode(current: String?, legacy: String?, gson: Gson = Gson()): WalletState =
        if (current != null) requireNotNull(gson.fromJson(current, WalletState::class.java))
        else if (legacy != null) migrate(requireNotNull(gson.fromJson(legacy, BalanceState::class.java)))
        else WalletState()

    fun apply(old: WalletState, accountId: String, message: ParsedMessage): WalletState {
        val account = old.accounts.find { it.id == accountId } ?: return old
        val tx = message.transaction
        if (account.balance.cardLast4 != tx.cardLast4) return old
        val at = BalanceEngine.transactionMillis(tx) ?: return old
        val key = TransactionMatcher.key(tx)
        val id = "$accountId|$at|$key"
        val existing = old.ledger.find { it.accountId == accountId && it.transactionKey == key &&
            kotlin.math.abs(it.transactionAt - at) < 60_000 }
        // Imported legacy rows may use receipt time; preserve their key and match within the same year.
            ?: old.ledger.find { it.accountId == accountId && it.transactionKey == key &&
                kotlin.math.abs(it.transactionAt - at) < 7L * 86400000 }
            ?: old.ledger.find { it.accountId == accountId && it.direction == tx.direction &&
                it.amountCents == tx.amountCents && kotlin.math.abs(it.transactionAt - at) < 60_000 &&
                descriptionsRelated(it.description, tx.description) }
        val incomingIsRicher = existing != null &&
            TransactionMatcher.normalizedDescription(tx.description).length >
            TransactionMatcher.normalizedDescription(existing.description).length
        val canonicalKey = if (incomingIsRicher) key else existing?.transactionKey ?: key
        val canonicalDescription = if (incomingIsRicher) tx.description else existing?.description ?: tx.description
        val entry = existing?.copy(transactionKey = canonicalKey, description = canonicalDescription,
            deliveries = (existing.deliveries + message.delivery).distinct(),
            balanceCents = message.balanceCents ?: existing.balanceCents,
            packageName = message.packageName.ifBlank { existing.packageName })
            ?: LedgerEntry(id, accountId, key, at, tx.direction, tx.amountCents, tx.description,
                listOf(message.delivery), message.balanceCents, tx.receivedAtMillis, message.packageName)
        // The ledger outlives the engine's short reconciliation window, so it also guards replay.
        val sameFamilyAlreadyApplied = existing?.deliveries?.any {
            (it == Delivery.BANK_NOTIFICATION) == (message.delivery == Delivery.BANK_NOTIFICATION)
        } == true
        val needsBalanceUpgrade = message.balanceCents != null && existing?.balanceCents == null
        val alreadyApplied = sameFamilyAlreadyApplied && !needsBalanceUpgrade
        val correctedBalance = if (incomingIsRicher) account.balance.copy(
            processedEvents = account.balance.processedEvents.map {
                if (it.transactionKey == existing!!.transactionKey) it.copy(transactionKey = canonicalKey) else it
            },
            recentTransactions = account.balance.recentTransactions.map {
                if (it.transactionKey == existing!!.transactionKey) it.copy(
                    transactionKey = canonicalKey, description = canonicalDescription
                ) else it
            },
            lastTransactionText = account.balance.lastTransactionText?.replace(existing!!.description, canonicalDescription)
        ) else account.balance
        val ledger = if (existing == null) old.ledger + entry
            else old.ledger.map { if (it.id == existing.id) entry else it }
        val engineState = if (message.balanceCents != null) {
            // Reconciliation must not lose pending transactions just because the short cache rolled over.
            val missing = ledger.filter { it.accountId == accountId && Delivery.BANK_NOTIFICATION in it.deliveries &&
                it.transactionAt > correctedBalance.checkpointTransactionAt &&
                correctedBalance.processedEvents.none { event -> event.transactionKey == it.transactionKey } }
                .map { row -> ProcessedEvent("ledger:${row.id}", row.transactionKey, Source.ICBC_NOTIFICATION,
                    row.receivedAt, false, row.transactionAt,
                    if (row.direction == Direction.INCOME) row.amountCents else -row.amountCents) }
            correctedBalance.copy(processedEvents = correctedBalance.processedEvents + missing)
        } else correctedBalance
        val next = if (alreadyApplied) correctedBalance else BalanceEngine.apply(engineState, message)
        return old.copy(accounts = old.accounts.map { if (it.id == accountId) it.copy(balance = next) else it },
            ledger = ledger.sortedByDescending { it.transactionAt })
    }

    private fun descriptionsRelated(first: String, second: String): Boolean {
        val left = TransactionMatcher.normalizedDescription(first)
        val right = TransactionMatcher.normalizedDescription(second)
        return left.isNotBlank() && right.length > left.length && right.endsWith(left)
    }
}
