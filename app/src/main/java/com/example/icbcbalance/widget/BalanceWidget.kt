package com.example.icbcbalance.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.AppWidgetId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.ContentScale
import androidx.glance.layout.fillMaxSize
import com.example.icbcbalance.data.BalanceRepository
import com.example.icbcbalance.data.WidgetContent
import com.example.icbcbalance.data.WidgetInstanceOptions
import com.example.icbcbalance.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class BalanceWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repository = BalanceRepository(context)
        val initial = repository.currentWallet()
        val appWidgetId = (id as? AppWidgetId)?.appWidgetId ?: 0
        provideContent {
            val wallet by repository.wallet.collectAsState(initial)
            val options = wallet.widgetOptions(appWidgetId)
            RenderedBalanceCard(context, wallet.widgetContent(options), options)
        }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent { RenderedBalanceCard(context, sampleWidgetContent(), WidgetInstanceOptions()) }
    }
}

@Composable
internal fun RenderedBalanceCard(context: Context, content: WidgetContent, options: WidgetInstanceOptions) {
    val size = LocalSize.current
    val bitmap = WidgetCardRenderer.render(context, content, options, size.width.value, size.height.value)
    Image(ImageProvider(bitmap), contentDescription = WidgetCardRenderer.accessibilityText(content, options),
        modifier = GlanceModifier.fillMaxSize().clickable(actionStartActivity<MainActivity>()),
        contentScale = ContentScale.FillBounds)
}

internal fun sampleWidgetContent() = WidgetContent(balanceCents = 129374,
    latestTransaction = com.example.icbcbalance.data.LedgerEntry(
        id = "preview", accountId = "preview", transactionKey = "preview", transactionAt = 0,
        direction = com.example.icbcbalance.data.Direction.EXPENSE, amountCents = 3800,
        description = "生活消费", deliveries = emptyList(), receivedAt = 0))

abstract class BaseBalanceWidgetReceiver : GlanceAppWidgetReceiver() {
    final override val glanceAppWidget: GlanceAppWidget = BalanceWidget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try { BalanceRepository(context.applicationContext).removeWidgetInstances(appWidgetIds) }
            finally { scope.cancel(); pending.finish() }
        }
    }
}

class BalanceWidgetReceiver : BaseBalanceWidgetReceiver()
class BalanceWidgetCompactReceiver : BaseBalanceWidgetReceiver()
class BalanceWidgetWideReceiver : BaseBalanceWidgetReceiver()
class BalanceWidgetLargeReceiver : BaseBalanceWidgetReceiver()

fun balanceWidgetComponents(context: Context): List<ComponentName> = listOf(
    BalanceWidgetCompactReceiver::class.java,
    BalanceWidgetReceiver::class.java,
    BalanceWidgetWideReceiver::class.java,
    BalanceWidgetLargeReceiver::class.java
).map { ComponentName(context, it) }

fun balanceWidgetIds(context: Context): IntArray {
    val manager = AppWidgetManager.getInstance(context)
    return balanceWidgetComponents(context).flatMap { manager.getAppWidgetIds(it).asIterable() }
        .distinct().sorted().toIntArray()
}
