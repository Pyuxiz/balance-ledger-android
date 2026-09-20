package com.example.icbcbalance.data

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.glance.appwidget.updateAll
import com.example.icbcbalance.widget.BalanceWidget
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import com.google.gson.Gson
import java.util.UUID

private val Context.balanceDataStore by preferencesDataStore("balance_state")

class BalanceRepository(private val context: Context,
    private val store: DataStore<Preferences> = context.applicationContext.balanceDataStore) {
    private val key = stringPreferencesKey("wallet_json_v2")
    private val legacyKey = stringPreferencesKey("state_json_v1")
    private val gson = Gson()
    val wallet = store.data.map { WalletEngine.decode(it[key], it[legacyKey], gson) }
    val state = wallet.map { it.widgetState() }
    suspend fun current() = state.first()
    suspend fun currentWallet() = wallet.first()

    private suspend fun edit(update: (WalletState) -> WalletState) {
        var changed = false
        store.edit { prefs ->
            val old = WalletEngine.decode(prefs[key], prefs[legacyKey], gson)
            val next = update(old)
            if (next != old || prefs[key] == null) {
                prefs[key] = gson.toJson(next)
                changed = true
            }
        }
        if (changed) try { BalanceWidget().updateAll(context.applicationContext) }
        catch (e: Exception) { Log.w(TAG, "Data saved; widget refresh failed", e) }
    }

    suspend fun apply(message: ParsedMessage, accountId: String? = null) {
        edit { old ->
            val matches = old.accounts.filter { it.balance.cardLast4 == message.transaction.cardLast4 &&
                (accountId == null || it.id == accountId) &&
                (message.delivery != Delivery.BANK_NOTIFICATION || message.packageName.isBlank() ||
                    it.balance.configuredPackage == message.packageName) }
            if (matches.size != 1) old.copy(lastResult = "未匹配到唯一卡源，请检查尾号与来源配对")
            else {
                val next = WalletEngine.apply(old, matches.single().id, message)
                val status = if (message.balanceCents != null) {
                    if (next.accounts.first { it.id == matches.single().id }.balance.lastBankConfirmedAt == message.transaction.receivedAtMillis)
                        "已用银行消息余额校准" else "银行消息已识别；重复或早于当前校准点，余额保持不变"
                } else "已识别银行动账消息"
                next.copy(lastResult = status,
                    lastSmsNotificationAt = if (message.delivery == Delivery.SMS_NOTIFICATION) System.currentTimeMillis() else next.lastSmsNotificationAt,
                    lastNotificationAt = if (message.delivery == Delivery.BANK_NOTIFICATION) System.currentTimeMillis() else next.lastNotificationAt)
            }
        }
        Log.i(TAG, "Ingested ${message.delivery} card ••••${message.transaction.cardLast4}")
    }

    suspend fun diagnostic(text: String, smsBroadcast: Boolean = false) = edit { it.copy(lastResult = text,
        lastSmsBroadcastAt = if (smsBroadcast) System.currentTimeMillis() else it.lastSmsBroadcastAt) }

    suspend fun saveAccount(id: String?, name: String, card: String, packageName: String,
        sender: String, signature: String, initialCents: Long?): String {
        require(card.matches(Regex("[0-9]{4}"))) { "请输入 4 位卡尾号" }
        require(name.isNotBlank()) { "请输入卡源名称" }
        require(packageName.isBlank() || validPackage(packageName)) { "通知应用包名格式不正确" }
        require(initialCents == null || initialCents >= 0) { "余额不能为负" }
        val target = id ?: UUID.randomUUID().toString()
        edit { old ->
            require(old.accounts.none { it.id != target && it.balance.cardLast4 == card &&
                (it.balance.configuredPackage == packageName ||
                    (signature.isNotBlank() && it.smsSignature == signature) ||
                    (sender.isNotBlank() && it.smsSender == sender)) }) { "已有相同尾号和来源的卡，无法可靠区分" }
            val previous = old.accounts.find { it.id == target }
            require(previous == null || previous.balance.cardLast4 == card) { "已有卡的尾号不可改，请添加新卡源" }
            var balance = (previous?.balance ?: BalanceState()).copy(cardLast4 = card, configuredPackage = packageName)
            if (initialCents != null) balance = BalanceEngine.manualCalibrate(balance, card, initialCents,
                packageName.ifBlank { "unpaired.local" }, System.currentTimeMillis())!!.copy(configuredPackage = packageName)
            val account = CardAccount(target, name.trim(), sender.trim(), signature.trim(), balance)
            old.copy(accounts = if (previous == null) old.accounts + account else old.accounts.map { if (it.id == target) account else it },
                selectedAccountId = old.selectedAccountId.ifBlank { target })
        }
        return target
    }

    suspend fun selectAccount(id: String) = edit { old ->
        require(old.accounts.any { it.id == id }); old.copy(selectedAccountId = id)
    }
    suspend fun deleteAccount(id: String) = edit { old ->
        val left = old.accounts.filterNot { it.id == id }
        val remainingIds = left.map { it.id }
        old.copy(accounts = left, ledger = old.ledger.filterNot { it.accountId == id },
            selectedAccountId = if (old.selectedAccountId == id) left.firstOrNull()?.id.orEmpty() else old.selectedAccountId,
            widget = if (old.widget.accountId == id) old.widget.copy(accountId = "") else old.widget,
            widgets = old.widgets.map { options ->
                if (options.accountIds.isEmpty() || id !in options.accountIds) options
                else options.copy(accountIds = (options.accountIds - id).ifEmpty { remainingIds })
            })
    }
    suspend fun setWidget(options: WidgetOptions) = edit { it.copy(widget = options) }
    suspend fun setWidgetInstance(options: WidgetInstanceOptions) {
        require(options.appWidgetId > 0) { "小组件实例无效" }
        edit { old ->
            val validIds = options.accountIds.distinct().filter { id -> old.accounts.any { it.id == id } }
            require(old.accounts.isEmpty() || validIds.isNotEmpty()) { "请至少选择一个卡源" }
            old.copy(widgets = old.widgets.filterNot { it.appWidgetId == options.appWidgetId } +
                options.copy(accountIds = validIds))
        }
    }
    suspend fun removeWidgetInstances(appWidgetIds: IntArray) = edit { old ->
        val removed = appWidgetIds.toSet()
        old.copy(widgets = old.widgets.filterNot { it.appWidgetId in removed })
    }
    suspend fun setFallback(enabled: Boolean) = edit { it.copy(smsNotificationFallback = enabled) }
    suspend fun pairSmsPackage(pkg: String) = edit { it.copy(smsPackages = (it.smsPackages + pkg).distinct()) }
    suspend fun removeSmsPackage(pkg: String) = edit { it.copy(smsPackages = it.smsPackages - pkg) }

    // Kept for existing integrations; all writes now target one account in the wallet.
    suspend fun calibrate(card: String, balanceCents: Long, packageName: String): Boolean = try {
        val existing = currentWallet().accounts.find { it.balance.cardLast4 == card && it.balance.configuredPackage == packageName }
        saveAccount(existing?.id, existing?.name ?: "工商银行", card, packageName,
            existing?.smsSender ?: "95588", existing?.smsSignature ?: "工商银行", balanceCents)
        true
    } catch (_: IllegalArgumentException) { false }
    suspend fun setPackage(packageName: String): Boolean {
        if (!validPackage(packageName)) return false
        val account = currentWallet().selected() ?: return false
        saveAccount(account.id, account.name, account.balance.cardLast4, packageName, account.smsSender, account.smsSignature, null)
        return true
    }
    companion object {
        const val TAG = "BalanceLedger"
        fun validPackage(value: String) = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+").matches(value)
    }
}
