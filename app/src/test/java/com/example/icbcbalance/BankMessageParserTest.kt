package com.example.icbcbalance

import com.example.icbcbalance.data.*
import com.example.icbcbalance.parser.*
import com.example.icbcbalance.util.MoneyUtils
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class BankMessageParserTest {
    private val now = LocalDateTime.of(2026, 9, 18, 20, 43).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val income = "尾号7874卡9月18日20:42网上银行收入(银联入账)10.98元。请点击查看详情。"
    private val expense = "尾号7874卡9月18日20:42支出(充值财付通-微信零钱充值账户)1元。请点击查看详情。"
    private val sms = "[4条]尾号7874卡9月18日20:42网上银行收入(银联入账)10.98元，余额1,295.76元。[工商银行]"

    @Test fun notificationExamples() {
        val first = BankMessageParser.parseNotification(income, now)!!.transaction
        assertEquals("7874", first.cardLast4)
        assertEquals(Direction.INCOME, first.direction)
        assertEquals(1098L, first.amountCents)
        assertTrue(first.description.contains("银联入账"))
        val second = BankMessageParser.parseNotification(expense, now)!!.transaction
        assertEquals(Direction.EXPENSE, second.direction)
        assertEquals(100L, second.amountCents)
        assertEquals(1L, BankMessageParser.parseNotification(income.replace("10.98", "0.01"), now)!!.transaction.amountCents)
    }

    @Test fun smsAndMoneyVariants() {
        val parsed = BankMessageParser.parseSms(sms, now)!!
        assertEquals(1098L, parsed.transaction.amountCents)
        assertEquals(129576L, parsed.balanceCents)
        assertEquals(100L, MoneyUtils.parseMoneyToCents("1"))
        assertEquals(120L, MoneyUtils.parseMoneyToCents("1.2"))
        assertEquals(123L, MoneyUtils.parseMoneyToCents("1.23"))
        assertEquals(129576L, MoneyUtils.parseMoneyToCents("1,295.76"))
        assertNull(MoneyUtils.parseMoneyToCents("1.234"))
        assertNull(MoneyUtils.parseMoneyToCents("999999999999999999999"))
        assertNull(BankMessageParser.parseSms("广告：贷款立享优惠[工商银行]", now))
        assertNull(BankMessageParser.parseSms("尾号7874卡9月18日20:42收入(测试)X元", now))
        assertNull(BankMessageParser.parseSms("尾号7874卡9月18日20:42收入(测试)1元", now)!!.balanceCents)
    }

    @Test fun reconciliationBothOrdersAndDuplicates() {
        val initial = BalanceEngine.manualCalibrate(BalanceState(), "7874", 100000, "com.icbc", now - 120000)!!
        val app = BankMessageParser.parseNotification(income.replace("10.98", "20"), now)!!
        val bank = BankMessageParser.parseSms(sms.replace("10.98", "20").replace("1,295.76", "1,020.00"), now + 1000)!!
        val appFirst = BalanceEngine.apply(BalanceEngine.apply(initial, app), bank)
        val smsFirst = BalanceEngine.apply(BalanceEngine.apply(initial, bank), app)
        assertEquals(102000L, appFirst.balanceCents)
        assertEquals(102000L, smsFirst.balanceCents)
        assertEquals(BalanceConfidence.BANK_CONFIRMED, appFirst.confidence)
        assertEquals(0, appFirst.provisionalTransactionCount)
        assertEquals(smsFirst, BalanceEngine.apply(smsFirst, app))
    }

    @Test fun invalidAndWrongCardNeverChangeBalance() {
        val initial = BalanceEngine.manualCalibrate(BalanceState(), "7874", 100000, "com.icbc", now - 120000)!!
        val wrongCard = BankMessageParser.parseNotification(income.replace("7874", "9999"), now)!!
        assertEquals(initial, BalanceEngine.apply(initial, wrongCard))
        assertNull(BankMessageParser.parseSms(sms.replace("1,295.76", "notmoney"), now)?.balanceCents)
    }

    @Test fun lateSmsRebasesLaterProvisionalTransactions() {
        val initial = BalanceEngine.manualCalibrate(BalanceState(), "7874", 100000, "com.icbc", now - 120000)!!
        val later = BankMessageParser.parseNotification(
            "尾号7874卡9月18日20:43支出(零钱充值)1元。请点击查看详情。", now + 2000)!!
        val estimated = BalanceEngine.apply(initial, later)
        assertEquals(99900L, estimated.balanceCents)
        val bank = BankMessageParser.parseSms(sms.replace("1,295.76", "1,020.00"), now + 3000)!!
        val rebased = BalanceEngine.apply(estimated, bank)
        assertEquals(101900L, rebased.balanceCents)
        assertEquals(BalanceConfidence.ESTIMATED, rebased.confidence)
        assertEquals(1, rebased.provisionalTransactionCount)
        assertEquals(rebased, BalanceEngine.apply(rebased, bank))
        val stale = BankMessageParser.parseSms(sms.replace("20:42", "20:41").replace("1,295.76", "500.00"), now + 5000)!!
        assertEquals(101900L, BalanceEngine.apply(rebased, stale).balanceCents)
    }

    @Test fun persistedEventsStillDeduplicateAfterReload() {
        val initial = BalanceEngine.manualCalibrate(BalanceState(), "7874", 100000, "com.icbc", now - 120000)!!
        val app = BankMessageParser.parseNotification(income, now)!!
        val applied = BalanceEngine.apply(initial, app)
        val loaded = Gson().fromJson(Gson().toJson(applied), BalanceState::class.java)
        assertEquals(applied, loaded)
        assertEquals(applied, BalanceEngine.apply(loaded, app))
        val variant = app.transaction.copy(description = "银联 入账，请点击查看详情。[工商银行]")
        assertEquals(TransactionMatcher.key(app.transaction), TransactionMatcher.key(variant))
    }

    @Test fun actualBankSmsFormatCalibratesExpense() {
        val later = LocalDateTime.of(2026, 9, 18, 23, 2).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val text = "[7条]尾号7874卡9月18日23:01支出(充值财付通-微信零钱充值账户)10元，余额1,283.73元。[工商银行]"
        val parsed = BankMessageParser.parseSms(text, later)!!
        assertEquals(Direction.EXPENSE, parsed.transaction.direction)
        assertEquals(1000L, parsed.transaction.amountCents)
        assertEquals(128373L, parsed.balanceCents)
        val initialized = BalanceEngine.manualCalibrate(BalanceState(), "7874", 129373, "com.icbc", later - 180000)!!
        val calibrated = BalanceEngine.apply(initialized, parsed)
        assertEquals(128373L, calibrated.balanceCents)
        assertEquals(BalanceConfidence.BANK_CONFIRMED, calibrated.confidence)
    }

    @Test fun nestedMerchantParenthesesKeepTheCompleteDescription() {
        val received = LocalDateTime.of(2026, 9, 21, 6, 54)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val text = "[1条]尾号7874卡9月21日06:53支出(消费美团支付-广东石磨肠粉（双阳支路店）)19.50元，余额1,087.80元。【工商银行】"
        val parsed = BankMessageParser.parseSms(text, received)!!
        assertEquals(Direction.EXPENSE, parsed.transaction.direction)
        assertEquals(1950L, parsed.transaction.amountCents)
        assertEquals(108780L, parsed.balanceCents)
        assertEquals("消费美团支付-广东石磨肠粉(双阳支路店)", parsed.transaction.description)
    }

    @Test fun commonBankMessageVariants() {
        val cases = listOf(
            "您尾号1234的储蓄卡账户9月18日23时01分消费人民币10.00元，账户余额为人民币990.00元。[中国建设银行]" to Direction.EXPENSE,
            "尾号1234账户9月18日23:01转入人民币88.50元，可用余额1078.50元。[中国农业银行]" to Direction.INCOME,
            "借记卡*1234于9月18日 23:01 POS消费人民币8.80元，交易后余额人民币1069.70元。[中国银行]" to Direction.EXPENSE,
            "您账户尾号1234于9月18日23:01入账50.00元，余额1119.70元。[招商银行]" to Direction.INCOME,
            "尾号1234卡9月18日23:01付款人民币12.34元，余额1107.36元。[交通银行]" to Direction.EXPENSE,
            "账号末四位1234于9月18日23:01存入人民币100元，余额1207.36元。[邮储银行]" to Direction.INCOME,
            "账户尾号1234于9月18日23:01扣款人民币6.00元，账户余额1201.36元。[兴业银行]" to Direction.EXPENSE
        )
        cases.forEach { (text, direction) ->
            val parsed = BankMessageParser.parseSms(text, now)
            assertNotNull(text, parsed)
            assertEquals(text, "1234", parsed!!.transaction.cardLast4)
            assertEquals(text, direction, parsed.transaction.direction)
            assertNotNull(text, parsed.balanceCents)
            assertEquals(text, Delivery.SMS_BROADCAST, parsed.delivery)
        }
    }

    @Test fun transactionOnlySmsIsAcceptedAsProvisional() {
        val text = "尾号1234卡9月18日23:01消费人民币12.34元，商户：便利店。[中国建设银行]"
        val parsed = BankMessageParser.parseSms(text, now)!!
        assertNull(parsed.balanceCents)
        assertEquals(Source.ICBC_NOTIFICATION, parsed.transaction.source)
        assertEquals(1234L, parsed.transaction.amountCents)
        assertTrue(parsed.transaction.description.contains("便利店"))
    }

    @Test fun configuredBankIdentitySupportsMultipleValues() {
        assertTrue(BankMessageParser.matchesBank("普通动账正文", "+86106980095533", "95533,106980095533", "建设银行,中国建设银行"))
        assertTrue(BankMessageParser.matchesBank("中国建设银行 尾号1234卡9月18日23:01消费10元", "", "95533", "建设银行,中国建设银行"))
        assertFalse(BankMessageParser.matchesBank("中国银行活动通知", "10086", "95566", "中国银行".replace("中国银行", "工商银行")))
    }

    @Test fun genericParserRejectsIncompleteOrNonTransactionText() {
        assertNull(BankMessageParser.parseSms("尾号1234卡9月18日23:01可用余额1000元。[中国建设银行]", now))
        assertNull(BankMessageParser.parseSms("尾号1234卡9月18日23:01消费提醒，金额请登录查看。[中国建设银行]", now))
        assertNull(BankMessageParser.parseSms("尊敬的客户，本月活动优惠10元。[招商银行]", now))
    }
}
