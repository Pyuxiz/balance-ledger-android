package com.example.icbcbalance.data

enum class Direction { INCOME, EXPENSE }
enum class Source { ICBC_NOTIFICATION, ICBC_SMS }
enum class BalanceConfidence { UNKNOWN, MANUAL_CONFIRMED, BANK_CONFIRMED, ESTIMATED }

data class BankTransaction(
    val cardLast4: String,
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
    val direction: Direction,
    val amountCents: Long,
    val description: String,
    val source: Source,
    val receivedAtMillis: Long
)

enum class Delivery { BANK_NOTIFICATION, SMS_BROADCAST, SMS_NOTIFICATION }
data class ParsedMessage(val transaction: BankTransaction, val balanceCents: Long? = null,
    val delivery: Delivery = if (transaction.source == Source.ICBC_SMS) Delivery.SMS_BROADCAST else Delivery.BANK_NOTIFICATION,
    val packageName: String = "")

data class ProcessedEvent(
    val fingerprint: String,
    val transactionKey: String,
    val source: Source,
    val timestamp: Long,
    val reconciled: Boolean,
    val transactionAt: Long,
    val signedAmountCents: Long
)

data class RecentTransaction(
    val transactionKey: String,
    val direction: Direction,
    val amountCents: Long,
    val description: String,
    val timestamp: Long,
    val source: Source
)

data class BalanceState(
    val balanceCents: Long = 0,
    val cardLast4: String = "",
    val configuredPackage: String = "com.icbc",
    val confidence: BalanceConfidence = BalanceConfidence.UNKNOWN,
    val lastUpdatedAt: Long = 0,
    val lastBankConfirmedAt: Long? = null,
    val provisionalTransactionCount: Int = 0,
    val lastTransactionText: String? = null,
    val processedEvents: List<ProcessedEvent> = emptyList(),
    val recentTransactions: List<RecentTransaction> = emptyList(),
    val calibrationAt: Long = 0,
    val checkpointBalanceCents: Long = 0,
    val checkpointTransactionAt: Long = 0,
    val displayName: String = "工商银行",
    val widgetOptions: WidgetOptions = WidgetOptions()
)

data class WidgetOptions(
    val accountId: String = "", val style: String = "auto", val showName: Boolean = true,
    val showTail: Boolean = true, val showStatus: Boolean = true, val showTime: Boolean = true,
    val showRecent: Boolean = false, val hideAmount: Boolean = false
)

data class WidgetInstanceOptions(
    val appWidgetId: Int = 0,
    val accountIds: List<String> = emptyList(),
    val style: String = "auto",
    val hideAmounts: Boolean = false
)

data class WidgetContent(
    val balanceCents: Long? = null,
    val latestTransaction: LedgerEntry? = null,
    val selectedAccountCount: Int = 0,
    val uncalibratedAccountCount: Int = 0
)

data class CardAccount(
    val id: String = "", val name: String = "工商银行", val smsSender: String = "95588",
    val smsSignature: String = "工商银行", val balance: BalanceState = BalanceState()
)

data class LedgerEntry(
    val id: String, val accountId: String, val transactionKey: String, val transactionAt: Long,
    val direction: Direction, val amountCents: Long, val description: String,
    val deliveries: List<Delivery>, val balanceCents: Long? = null, val receivedAt: Long,
    val packageName: String = ""
)

data class WalletState(
    val accounts: List<CardAccount> = emptyList(), val selectedAccountId: String = "",
    val ledger: List<LedgerEntry> = emptyList(), val widget: WidgetOptions = WidgetOptions(),
    val widgets: List<WidgetInstanceOptions> = emptyList(),
    val smsNotificationFallback: Boolean = true, val smsPackages: List<String> = emptyList(),
    val lastSmsBroadcastAt: Long = 0, val lastSmsNotificationAt: Long = 0,
    val lastNotificationAt: Long = 0, val lastResult: String = "等待银行动账消息"
) {
    fun selected(): CardAccount? = accounts.find { it.id == selectedAccountId } ?: accounts.firstOrNull()
    fun widgetState(): BalanceState {
        val account = accounts.find { it.id == widget.accountId } ?: selected()
        return (account?.balance ?: BalanceState()).copy(displayName = account?.name ?: "余额手账", widgetOptions = widget)
    }


    fun widgetOptions(appWidgetId: Int): WidgetInstanceOptions =
        widgets.find { it.appWidgetId == appWidgetId } ?: WidgetInstanceOptions(
            appWidgetId = appWidgetId,
            accountIds = widget.accountId.takeIf(String::isNotBlank)?.let(::listOf).orEmpty(),
            style = widget.style,
            hideAmounts = widget.hideAmount
        )

    fun widgetContent(options: WidgetInstanceOptions): WidgetContent {
        val requested = options.accountIds.toSet()
        val selected = if (requested.isEmpty()) accounts else accounts.filter { it.id in requested }
        val effective = selected.ifEmpty { accounts }
        val known = effective.filter { it.balance.confidence != BalanceConfidence.UNKNOWN }
        val total = try {
            if (known.isEmpty()) null else known.fold(0L) { sum, account ->
                Math.addExact(sum, account.balance.balanceCents)
            }
        } catch (_: ArithmeticException) { null }
        val ids = effective.mapTo(mutableSetOf()) { it.id }
        return WidgetContent(
            balanceCents = total,
            latestTransaction = ledger.asSequence().filter { it.accountId in ids }
                .maxByOrNull { it.transactionAt },
            selectedAccountCount = effective.size,
            uncalibratedAccountCount = effective.count { it.balance.confidence == BalanceConfidence.UNKNOWN }
        )
    }
}
