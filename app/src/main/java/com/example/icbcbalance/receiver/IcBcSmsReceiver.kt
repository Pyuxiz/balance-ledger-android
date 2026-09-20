package com.example.icbcbalance.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.example.icbcbalance.data.BalanceRepository
import com.example.icbcbalance.parser.BankMessageParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class IcBcSmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        Log.d(BalanceRepository.TAG, "SMS broadcast delivered")
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val segments = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                if (segments.isEmpty()) return@launch
                val sender = segments.first().originatingAddress.orEmpty().trim()
                val body = segments.joinToString("") { it.messageBody.orEmpty() }
                val repo = BalanceRepository(context.applicationContext)
                repo.diagnostic("短信广播已送达，检查银行格式", smsBroadcast = true)
                val wallet = repo.currentWallet()
                val accounts = wallet.accounts.filter {
                    BankMessageParser.matchesBank(body, sender, it.smsSender, it.smsSignature)
                }
                if (accounts.isEmpty()) return@launch
                Log.d(BalanceRepository.TAG, "SMS received from recognized bank sender/signature")
                val message = BankMessageParser.parseSms(body, System.currentTimeMillis())
                if (message == null) {
                    Log.d(BalanceRepository.TAG, "SMS has no valid bank transaction; ignored")
                    return@launch
                }
                Log.d(BalanceRepository.TAG, "Parsed SMS for ••••${message.transaction.cardLast4}")
                val matches = accounts.filter { it.balance.cardLast4 == message.transaction.cardLast4 }
                if (matches.size == 1) repo.apply(message, matches.single().id)
                else repo.diagnostic("银行短信已送达，但未匹配到唯一卡源")
            } catch (e: Exception) { Log.e(BalanceRepository.TAG, "SMS processing failed", e) }
            finally { pending.finish() }
        }
    }
}
