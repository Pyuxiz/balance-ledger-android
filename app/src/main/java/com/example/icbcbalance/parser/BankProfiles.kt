package com.example.icbcbalance.parser

import com.example.icbcbalance.data.CardAccount

data class BankProfile(
    val id: String,
    val name: String,
    val smsSenders: List<String>,
    val signatures: List<String>
) {
    val senderSetting: String get() = smsSenders.joinToString(",")
    val signatureSetting: String get() = signatures.joinToString(",")
}

/** Starter templates for common mainland China banks. */
object BankProfiles {
    val all = listOf(
        BankProfile("icbc", "中国工商银行", listOf("95588"), listOf("工商银行", "中国工商银行", "工银信使")),
        BankProfile("ccb", "中国建设银行", listOf("95533", "106980095533"), listOf("建设银行", "中国建设银行")),
        BankProfile("abc", "中国农业银行", listOf("95599", "1069095599"), listOf("农业银行", "中国农业银行")),
        BankProfile("boc", "中国银行", listOf("95566"), listOf("中国银行")),
        BankProfile("cmb", "招商银行", listOf("95555"), listOf("招商银行")),
        BankProfile("bocom", "交通银行", listOf("95559", "106980095559"), listOf("交通银行")),
        BankProfile("psbc", "中国邮政储蓄银行", listOf("95580"), listOf("邮储银行", "中国邮政储蓄银行")),
        BankProfile("cib", "兴业银行", listOf("95561", "106980095561", "106550595561", "1065795561", "106590595561"), listOf("兴业银行"))
    )

    fun infer(account: CardAccount?): BankProfile? {
        if (account == null) return all.first()
        val senders = tokens(account.smsSender)
        val signatures = tokens(account.smsSignature)
        return all.firstOrNull { profile ->
            profile.smsSenders.any { it in senders } || profile.signatures.any { it in signatures }
        }
    }

    internal fun tokens(value: String): Set<String> = value
        .split(Regex("[,，;；、\\s]+"))
        .map(String::trim)
        .filter(String::isNotBlank)
        .toSet()
}
