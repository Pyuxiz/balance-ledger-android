package com.example.icbcbalance

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.glance.ExperimentalGlanceApi
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.compose
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.runComposition
import com.example.icbcbalance.data.*
import com.example.icbcbalance.widget.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/** Device-side bitmap, RemoteViews, live-session and isolated DataStore regression tests. */
class WidgetRenderInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    @OptIn(ExperimentalGlanceApi::class)
    override fun onStart() {
        val report = StringBuilder()
        try {
            runBlocking {
                val sizes = listOf(
                    "2x1" to DpSize(136.dp, 63.dp), "2x2" to DpSize(136.dp, 150.dp),
                    "4x1" to DpSize(272.dp, 63.dp), "4x2" to DpSize(272.dp, 150.dp),
                    "min" to DpSize(80.dp, 40.dp), "wide" to DpSize(376.dp, 76.dp),
                    "tall" to DpSize(180.dp, 230.dp), "large" to DpSize(376.dp, 210.dp)
                )
                val content = sampleWidgetContent()
                var rendered = 0
                for (night in listOf(false, true)) {
                    val themed = themedContext(night)
                    for (style in listOf("auto", "paper", "sage", "ink")) {
                        for ((name, size) in sizes) {
                            val bitmap = WidgetCardRenderer.render(themed, content,
                                WidgetInstanceOptions(style = style), size.width.value, size.height.value)
                            check(bitmap.width > 0 && bitmap.height > 0)
                            check(bitmap.getPixel(bitmap.width / 2, bitmap.height / 2) != 0)
                            save(bitmap, "${if (night) "dark" else "light"}-$style-$name")
                            bitmap.recycle()
                            rendered++
                        }
                    }
                }
                val hidden = WidgetInstanceOptions(style = "ink", hideAmounts = true)
                check("余额已隐藏" in WidgetCardRenderer.accessibilityText(content, hidden))
                report.appendLine("PASS: $rendered shared-renderer previews across sizes, themes and palettes")

                val remoteSize = DpSize(272.dp, 150.dp)
                val previewWidget = PreviewWidget(MutableStateFlow(content), MutableStateFlow(WidgetInstanceOptions()))
                val remote = previewWidget.compose(targetContext, size = remoteSize)
                val firstHash = renderRemote(remote, remoteSize, "remote-actual")
                check(firstHash != 0L)
                report.appendLine("PASS: rendered bitmap survives Glance RemoteViews hosting")

                val states = MutableStateFlow(content)
                val options = MutableStateFlow(WidgetInstanceOptions())
                val liveWidget = PreviewWidget(states, options)
                val updates = Channel<RemoteViews>(Channel.UNLIMITED)
                val running = launch { liveWidget.runComposition(targetContext, sizes = listOf(remoteSize)).collect { updates.send(it) } }
                suspend fun nextDifferent(previous: Long): Long = withTimeout(15000) {
                    while (true) {
                        val hash = renderRemote(updates.receive(), remoteSize, "live-${System.nanoTime()}")
                        if (hash != previous) return@withTimeout hash
                    }
                    @Suppress("UNREACHABLE_CODE") 0L
                }
                try {
                    val initialHash = renderRemote(updates.receive(), remoteSize, "live-initial")
                    states.value = content.copy(balanceCents = 129472)
                    val balanceHash = nextDifferent(initialHash)
                    options.value = WidgetInstanceOptions(style = "ink", hideAmounts = true)
                    nextDifferent(balanceHash)
                    report.appendLine("PASS: live widget session refreshes balance and per-instance style")
                } finally { running.cancelAndJoin(); updates.close() }

                val storageScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                val qaFile = File(targetContext.cacheDir, "wallet-widget-${System.nanoTime()}.preferences_pb")
                val isolated = PreferenceDataStoreFactory.create(scope = storageScope, produceFile = { qaFile })
                try {
                    val repo = BalanceRepository(targetContext, isolated)
                    val first = repo.saveAccount(null, "卡一", "1111", "bank.one", "95588", "银行一", 100000)
                    val second = repo.saveAccount(null, "卡二", "2222", "bank.two", "95599", "银行二", 200000)
                    repo.setWidgetInstance(WidgetInstanceOptions(101, listOf(first), "paper"))
                    repo.setWidgetInstance(WidgetInstanceOptions(202, listOf(first, second), "ink", true))
                    val saved = BalanceRepository(targetContext, isolated).currentWallet()
                    check(saved.widgetContent(saved.widgetOptions(101)).balanceCents == 100000L)
                    check(saved.widgetContent(saved.widgetOptions(202)).balanceCents == 300000L)
                    check(saved.widgetOptions(202).hideAmounts)
                    repo.removeWidgetInstances(intArrayOf(101))
                    check(repo.currentWallet().widgets.map { it.appWidgetId } == listOf(202))
                    report.appendLine("PASS: independent card selection and settings persist per widget instance")
                } finally {
                    storageScope.coroutineContext[Job]?.cancelAndJoin()
                    qaFile.delete()
                }
            }
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", report.toString()) })
        } catch (error: Throwable) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", report.toString() + error.stackTraceToString()) })
        }
    }

    private fun themedContext(night: Boolean): Context {
        val config = Configuration(targetContext.resources.configuration)
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        return targetContext.createConfigurationContext(config)
    }

    private fun save(bitmap: Bitmap, name: String) {
        val directory = File(targetContext.getExternalFilesDir(null), "widget-qa").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun renderRemote(remote: RemoteViews, size: DpSize, name: String): Long {
        var hash = 0L
        var failure: Throwable? = null
        runOnMainSync {
            try {
                val density = targetContext.resources.displayMetrics.density
                val width = (size.width.value * density).toInt()
                val height = (size.height.value * density).toInt()
                val view = remote.apply(targetContext, FrameLayout(targetContext))
                view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, width, height)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                for (y in 0 until height step maxOf(1, height / 23)) {
                    for (x in 0 until width step maxOf(1, width / 31)) hash = hash * 31 + bitmap.getPixel(x, y)
                }
                save(bitmap, name)
                bitmap.recycle()
            } catch (error: Throwable) { failure = error }
        }
        failure?.let { throw it }
        return hash
    }

    private class PreviewWidget(
        private val states: MutableStateFlow<WidgetContent>,
        private val options: MutableStateFlow<WidgetInstanceOptions>
    ) : GlanceAppWidget() {
        override val sizeMode = SizeMode.Exact
        override suspend fun provideGlance(context: Context, id: GlanceId) {
            provideContent {
                val state by states.collectAsState(states.value)
                val option by options.collectAsState(options.value)
                RenderedBalanceCard(context, state, option)
            }
        }
    }
}
