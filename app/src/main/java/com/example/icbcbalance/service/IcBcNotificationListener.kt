package com.example.icbcbalance.service

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.example.icbcbalance.data.*
import com.example.icbcbalance.parser.BankMessageParser
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Only metadata about notification apps is kept for pairing; unpaired message bodies are not saved. */
data class NotificationApp(val packageName: String, val label: String, val count: Int, val lastSeen: Long)

class IcBcNotificationListener : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onListenerConnected() {
        instance = this
        connected.value = true
        Log.i(BalanceRepository.TAG, "Notification listener connected")
        rescan()
    }
    override fun onListenerDisconnected() {
        connected.value = false
        instance = null
        requestRebind(ComponentName(this, IcBcNotificationListener::class.java))
    }
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        rememberApp(sbn)
        process(sbn)
    }
    private fun rememberApp(sbn: StatusBarNotification) {
        val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }
            .getOrDefault(sbn.packageName)
        val previous = apps.value.find { it.packageName == sbn.packageName }
        apps.value = (apps.value.filterNot { it.packageName == sbn.packageName } +
            NotificationApp(sbn.packageName, label, previous?.count ?: 1, sbn.postTime)).sortedByDescending { it.lastSeen }.take(100)
    }
    private fun rescan() {
        val active = runCatching { activeNotifications?.toList().orEmpty() }.getOrDefault(emptyList())
        active.groupBy { it.packageName }.forEach { (_, items) ->
            rememberApp(items.maxBy { it.postTime })
            apps.value = apps.value.map { if (it.packageName == items.first().packageName) it.copy(count = items.size) else it }
        }
        // Process oldest first. Engine also handles SMS/notification delivery in either order.
        active.sortedBy { it.postTime }.forEach(::process)
    }
    private fun process(sbn: StatusBarNotification) {
        scope.launch {
            try {
                if (kotlin.math.abs(System.currentTimeMillis() - sbn.postTime) > 7L * 86400000) return@launch
                val repo = BalanceRepository(applicationContext)
                val wallet = repo.currentWallet()
                val bankAccounts = wallet.accounts.filter { it.balance.configuredPackage == sbn.packageName }
                val smsPackage = Telephony.Sms.getDefaultSmsPackage(applicationContext)
                val fromSms = wallet.smsNotificationFallback &&
                    (sbn.packageName == smsPackage || sbn.packageName in wallet.smsPackages)
                if (bankAccounts.isEmpty() && !fromSms) return@launch
                val candidates = texts(sbn.notification.extras)
                var recognized = false
                for (text in candidates) {
                    val parsed = if (fromSms) BankMessageParser.parseSms(text, sbn.postTime)
                        else BankMessageParser.parseNotification(text, sbn.postTime)
                    if (parsed == null) continue
                    val accounts = (if (fromSms) wallet.accounts else bankAccounts).filter {
                        it.balance.cardLast4 == parsed.transaction.cardLast4 &&
                            (!fromSms || BankMessageParser.matchesBank(text, "", it.smsSender, it.smsSignature))
                    }
                    if (accounts.size != 1) continue
                    recognized = true
                    repo.apply(parsed.copy(delivery = if (fromSms) Delivery.SMS_NOTIFICATION else Delivery.BANK_NOTIFICATION,
                        packageName = sbn.packageName), accounts.single().id)
                }
                if (!recognized && (fromSms || bankAccounts.isNotEmpty()))
                    Log.d(BalanceRepository.TAG, "Paired notification has no matching complete bank transaction")
            } catch (e: Exception) { Log.e(BalanceRepository.TAG, "Notification processing failed", e) }
        }
    }
    override fun onDestroy() {
        if (instance === this) { instance = null; connected.value = false }
        scope.cancel()
        super.onDestroy()
    }
    companion object {
        @Volatile private var instance: IcBcNotificationListener? = null
        private val apps = MutableStateFlow<List<NotificationApp>>(emptyList())
        val availableApps = apps.asStateFlow()
        val connected = MutableStateFlow(false)
        fun refresh(context: Context): Boolean {
            val service = instance
            if (service != null) { service.rescan(); return true }
            requestRebind(ComponentName(context, IcBcNotificationListener::class.java))
            return false
        }
        fun defaultSmsPackage(context: Context): String? = Telephony.Sms.getDefaultSmsPackage(context)
        private fun texts(extras: Bundle): List<String> {
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val bodies = buildList {
                listOf(Notification.EXTRA_BIG_TEXT, Notification.EXTRA_TEXT).forEach { key ->
                    extras.getCharSequence(key)?.toString()?.let(::add)
                }
                extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach { add(it.toString()) }
                @Suppress("DEPRECATION")
                extras.getParcelableArray(Notification.EXTRA_MESSAGES)?.forEach { value ->
                    (value as? Bundle)?.getCharSequence("text")?.toString()?.let(::add)
                }
            }.filter { it.isNotBlank() }
            return (bodies + bodies.map { "$title $it" })
                .filter { it.length <= 2000 }.distinct()
        }
    }
}
