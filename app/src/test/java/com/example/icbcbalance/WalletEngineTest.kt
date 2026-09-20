package com.example.icbcbalance

import com.example.icbcbalance.data.*
import com.example.icbcbalance.parser.BankMessageParser
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class WalletEngineTest {
    private val now = LocalDateTime.of(2026, 9, 20, 22, 25).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val text = "[15条]尾号7874卡9月20日22:24支出(充值财付通-微信零钱充值账户)11元，余额1,115.71元。【工商银行】"
    private fun initial() = WalletState(accounts = listOf(
        CardAccount(id = "one", balance = BalanceEngine.manualCalibrate(BalanceState(), "7874", 112671, "com.icbc", now - 180000)!!),
        CardAccount(id = "two", name = "第二张卡", balance = BalanceEngine.manualCalibrate(BalanceState(), "1234", 200000, "com.icbc", now - 180000)!!)
    ), selectedAccountId = "one")

    @Test fun actualSmsSignatureAndFullWidthPunctuation() {
        assertTrue(BankMessageParser.matchesBank(text, "", "95588", "工商银行"))
        assertTrue(BankMessageParser.matchesBank("正文", "+8695588", "95588", "工商银行"))
        assertFalse(BankMessageParser.matchesBank("工商银行优惠", "12345", "95588", "工商银行"))
        val parsed = BankMessageParser.parseSms(text.replace('(', '（').replace(')', '）'), now)!!
        assertEquals(111571L, parsed.balanceCents)
    }
    @Test fun currentNotificationCalibratesAndMergesThreeDeliveryPaths() {
        val sms = BankMessageParser.parseSms(text, now)!!
        val app = BankMessageParser.parseNotification(text, now)!!.copy(packageName = "com.icbc")
        var state = WalletEngine.apply(initial(), "one", app)
        state = WalletEngine.apply(state, "one", sms.copy(delivery = Delivery.SMS_NOTIFICATION))
        state = WalletEngine.apply(state, "one", sms)
        assertEquals(111571L, state.accounts[0].balance.balanceCents)
        assertEquals(BalanceConfidence.BANK_CONFIRMED, state.accounts[0].balance.confidence)
        assertEquals(200000L, state.accounts[1].balance.balanceCents)
        assertEquals(1, state.ledger.size)
        assertEquals(3, state.ledger.single().deliveries.size)
        assertEquals(now - 60000, state.ledger.single().transactionAt)
        assertEquals(state, WalletEngine.apply(state, "one", sms))
    }
    @Test fun smsFirstDoesNotDoubleCountLaterAppNotification() {
        val sms = BankMessageParser.parseSms(text, now)!!
        val state = WalletEngine.apply(initial(), "one", sms)
        val next = WalletEngine.apply(state, "one", BankMessageParser.parseNotification(text, now)!!)
        assertEquals(111571L, next.accounts[0].balance.balanceCents)
        assertEquals(1, next.ledger.size)
    }
    @Test fun reprocessingUpgradesTruncatedMerchantWithoutDoubleCounting() {
        val nestedText = "[1条]尾号7874卡9月21日06:53支出(消费美团支付-广东石磨肠粉（双阳支路店）)19.50元，余额1,087.80元。【工商银行】"
        val received = LocalDateTime.of(2026, 9, 21, 6, 54)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val parsed = BankMessageParser.parseSms(nestedText, received)!!
        val truncated = parsed.copy(transaction = parsed.transaction.copy(description = "双阳支路店"))
        val old = WalletState(accounts = listOf(CardAccount(id = "one", balance =
            BalanceEngine.manualCalibrate(BalanceState(), "7874", 110730, "com.icbc", received - 180000)!!)))
        val first = WalletEngine.apply(old, "one", truncated)
        val corrected = WalletEngine.apply(first, "one", parsed)
        assertEquals(108780L, corrected.accounts.single().balance.balanceCents)
        assertEquals(1, corrected.ledger.size)
        assertEquals("消费美团支付-广东石磨肠粉(双阳支路店)", corrected.ledger.single().description)
        assertEquals(1, corrected.accounts.single().balance.recentTransactions.size)
        assertEquals(corrected.ledger.single().transactionKey,
            corrected.accounts.single().balance.recentTransactions.single().transactionKey)
    }
    @Test fun balancedSmsCanUpgradeAnEarlierTransactionOnlySmsNotification() {
        val transactionOnly = BankMessageParser.parseSms(
            text.replace("，余额1,115.71元", ""), now
        )!!.copy(delivery = Delivery.SMS_NOTIFICATION)
        val first = WalletEngine.apply(initial(), "one", transactionOnly)
        assertNull(first.ledger.single().balanceCents)
        val corrected = WalletEngine.apply(first, "one", BankMessageParser.parseSms(text, now)!!)
        assertEquals(111571L, corrected.accounts[0].balance.balanceCents)
        assertEquals(BalanceConfidence.BANK_CONFIRMED, corrected.accounts[0].balance.confidence)
        assertEquals(111571L, corrected.ledger.single().balanceCents)
        assertEquals(1, corrected.ledger.size)
    }
    @Test fun manualCheckpointRemainsAuthoritativeButOldMessageEntersLedger() {
        val old = initial().copy(accounts = listOf(CardAccount(id = "one", balance =
            BalanceEngine.manualCalibrate(BalanceState(), "7874", 500000, "com.icbc", now)!!)))
        val result = WalletEngine.apply(old, "one", BankMessageParser.parseSms(text, now)!!)
        assertEquals(500000L, result.accounts.single().balance.balanceCents)
        assertEquals(BalanceConfidence.MANUAL_CONFIRMED, result.accounts.single().balance.confidence)
        assertEquals(1, result.ledger.size)
    }
    @Test fun emptyInitialBalanceCanBeInitializedBySms() {
        val old = WalletState(accounts = listOf(CardAccount(id = "new", balance = BalanceState(cardLast4 = "7874"))))
        val next = WalletEngine.apply(old, "new", BankMessageParser.parseSms(text, now)!!)
        assertEquals(111571L, next.accounts.single().balance.balanceCents)
        assertEquals(BalanceConfidence.BANK_CONFIRMED, next.accounts.single().balance.confidence)
    }
    @Test fun wrongCardCannotEnterAnotherLedger() {
        assertEquals(initial(), WalletEngine.apply(initial(), "two", BankMessageParser.parseSms(text, now)!!))
    }
    @Test fun upgradePreservesBalanceAndTransactionTimeAndHistory() {
        val old = BalanceEngine.apply(initial().accounts[0].balance, BankMessageParser.parseNotification(text, now)!!)
        val migrated = WalletEngine.decode(null, Gson().toJson(old))
        assertEquals(old, migrated.accounts.single().balance)
        assertEquals(now - 60000, migrated.ledger.single().transactionAt)
        assertEquals(migrated, WalletEngine.decode(Gson().toJson(migrated), Gson().toJson(BalanceState())))
    }
    @Test fun replayAfterEngineWindowEvictionDoesNotAlterBalance() {
        val app = BankMessageParser.parseNotification(text, now)!!
        val applied = WalletEngine.apply(initial(), "one", app)
        val evicted = applied.copy(accounts = applied.accounts.map { it.copy(balance = it.balance.copy(processedEvents = emptyList())) })
        assertEquals(evicted, WalletEngine.apply(evicted, "one", app))
    }
    @Test fun manualCalibrationKeepsRecentRowsAndDedupEvidence() {
        val applied = BalanceEngine.apply(initial().accounts[0].balance, BankMessageParser.parseNotification(text, now)!!)
        val calibrated = BalanceEngine.manualCalibrate(applied, "7874", 800000, "com.icbc", now + 1)!!
        assertEquals(applied.recentTransactions, calibrated.recentTransactions)
        assertTrue(calibrated.processedEvents.isNotEmpty())
    }
    @Test fun widgetUsesChosenCardAndPersistsOptions() {
        val wallet = initial().copy(widget = WidgetOptions(accountId = "two", style = "ink", hideAmount = true))
        assertEquals("1234", wallet.widgetState().cardLast4)
        assertEquals("第二张卡", wallet.widgetState().displayName)
        assertTrue(wallet.widgetState().widgetOptions.hideAmount)
        assertEquals(wallet, Gson().fromJson(Gson().toJson(wallet), WalletState::class.java))
    }
    @Test fun eachWidgetHasIndependentCardSelectionAndLatestTransaction() {
        val expense = BankMessageParser.parseNotification(text, now)!!
        val secondTx = expense.copy(transaction = expense.transaction.copy(cardLast4 = "1234",
            minute = 25, description = "第二张卡交易", amountCents = 300, receivedAtMillis = now + 60000))
        var wallet = WalletEngine.apply(initial(), "one", expense)
        wallet = WalletEngine.apply(wallet, "two", secondTx)
        wallet = wallet.copy(widgets = listOf(
            WidgetInstanceOptions(11, listOf("one"), "paper"),
            WidgetInstanceOptions(12, listOf("one", "two"), "ink", hideAmounts = true)
        ))
        val first = wallet.widgetContent(wallet.widgetOptions(11))
        val total = wallet.widgetContent(wallet.widgetOptions(12))
        assertEquals(111571L, first.balanceCents)
        assertEquals("充值财付通-微信零钱充值账户", first.latestTransaction!!.description)
        assertEquals(311271L, total.balanceCents)
        assertEquals("第二张卡交易", total.latestTransaction!!.description)
        assertTrue(wallet.widgetOptions(12).hideAmounts)
        assertEquals("auto", wallet.widgetOptions(99).style)
    }
    @Test fun widgetTotalExcludesUncalibratedCards() {
        val wallet = initial().copy(accounts = initial().accounts +
            CardAccount(id = "unknown", balance = BalanceState(cardLast4 = "9999")))
        val content = wallet.widgetContent(WidgetInstanceOptions(1,
            listOf("one", "two", "unknown")))
        assertEquals(312671L, content.balanceCents)
        assertEquals(1, content.uncalibratedAccountCount)
        assertEquals(3, content.selectedAccountCount)
    }
    @Test fun firstSmsReplaysLaterNotificationEvenWithoutInitialBalance() {
        var wallet = WalletState(accounts = listOf(CardAccount(id = "new", balance = BalanceState(cardLast4 = "7874"))))
        val later = BankMessageParser.parseNotification(text.replace("22:24", "22:25"), now)!!
        wallet = WalletEngine.apply(wallet, "new", later)
        assertEquals(BalanceConfidence.UNKNOWN, wallet.accounts.single().balance.confidence)
        wallet = WalletEngine.apply(wallet, "new", BankMessageParser.parseSms(text, now)!!)
        assertEquals(110471L, wallet.accounts.single().balance.balanceCents)
        assertEquals(BalanceConfidence.ESTIMATED, wallet.accounts.single().balance.confidence)
    }
    @Test fun ledgerKeepsMoreThanEngineWindowAndSortsByTransactionTime() {
        var wallet = initial()
        val base = BankMessageParser.parseNotification(text, now)!!
        repeat(130) { index ->
            val message = base.copy(transaction = base.transaction.copy(description = "测试$index", amountCents = 1))
            wallet = WalletEngine.apply(wallet, "one", message)
        }
        assertEquals(130, wallet.ledger.size)
        assertEquals(100, wallet.accounts[0].balance.processedEvents.size)
        val smsBefore = BankMessageParser.parseSms(text.replace("22:24", "22:23"), now - 60000)!!
        val rebased = WalletEngine.apply(wallet, "one", smsBefore)
        assertEquals(111441L, rebased.accounts[0].balance.balanceCents)
        assertEquals(130, rebased.accounts[0].balance.provisionalTransactionCount)
        assertEquals(wallet, WalletEngine.apply(wallet, "one", base.copy(transaction = base.transaction.copy(description = "测试0", amountCents = 1))))
    }
}
