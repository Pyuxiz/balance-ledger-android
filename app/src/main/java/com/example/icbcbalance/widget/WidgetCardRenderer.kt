package com.example.icbcbalance.widget

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.example.icbcbalance.data.Direction
import com.example.icbcbalance.data.WidgetContent
import com.example.icbcbalance.data.WidgetInstanceOptions
import com.example.icbcbalance.util.MoneyUtils
import kotlin.math.min

/** The app preview and the desktop widget both display the bitmap produced here. */
object WidgetCardRenderer {
    private data class Palette(val background: Int, val foreground: Int, val secondary: Int,
        val divider: Int, val income: Int, val expense: Int)

    fun render(context: Context, content: WidgetContent, options: WidgetInstanceOptions,
        widthDp: Float, heightDp: Float): Bitmap {
        val density = min(context.resources.displayMetrics.density, 2.25f)
        val width = (widthDp.coerceAtLeast(80f) * density).toInt().coerceAtLeast(1)
        val height = (heightDp.coerceAtLeast(40f) * density).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val palette = palette(context, options.style)
        val unit = density
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), 22f * unit, 22f * unit,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.background })

        val compact = heightDp < 96f && widthDp < 220f
        val wideShort = heightDp < 105f && widthDp >= 220f
        val narrowCard = !compact && !wideShort && widthDp < 240f
        val horizontalPadding = (if (widthDp < 220f) 15f else 20f) * unit
        val verticalPadding = (if (heightDp < 90f) 10f else 17f) * unit
        val amount = if (options.hideAmounts) "¥ ••••" else
            content.balanceCents?.let { "¥ ${MoneyUtils.format(it)}" } ?: "¥ --"

        when {
            compact -> drawFitted(canvas, amount, horizontalPadding, verticalPadding,
                width - horizontalPadding * 2, height - verticalPadding * 2,
                palette.foreground, 38f * unit, Typeface.BOLD, Paint.Align.LEFT, true)
            wideShort -> drawWideShort(canvas, content, options, palette, amount,
                horizontalPadding, verticalPadding, width, height, unit)
            narrowCard -> drawNarrowCard(canvas, content, options, palette, amount,
                horizontalPadding, verticalPadding, width, height, unit)
            else -> drawWideCard(canvas, content, options, palette, amount,
                horizontalPadding, verticalPadding, width, height, unit)
        }
        return bitmap
    }

    fun accessibilityText(content: WidgetContent, options: WidgetInstanceOptions): String {
        val balance = if (options.hideAmounts) "余额已隐藏" else
            content.balanceCents?.let { "当前余额 ${MoneyUtils.format(it)} 元" } ?: "当前余额未知"
        val latest = content.latestTransaction ?: return balance
        val direction = if (latest.direction == Direction.INCOME) "收入" else "支出"
        val amount = if (options.hideAmounts) "金额已隐藏" else "${MoneyUtils.format(latest.amountCents)} 元"
        return "$balance，最近一笔${latest.description}，$direction$amount"
    }

    private fun drawWideShort(canvas: Canvas, content: WidgetContent, options: WidgetInstanceOptions,
        palette: Palette, amount: String, padX: Float, padY: Float, width: Int, height: Int, unit: Float) {
        val dividerX = width * 0.56f
        drawFitted(canvas, amount, padX, padY, dividerX - padX - 13f * unit, height - padY * 2,
            palette.foreground, 34f * unit, Typeface.BOLD, Paint.Align.LEFT, true)
        val latest = content.latestTransaction ?: return
        canvas.drawRoundRect(RectF(dividerX, padY, dividerX + unit, height - padY), unit, unit,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.divider })
        val rightX = dividerX + 14f * unit
        val right = width - padX
        val signed = signedAmount(latest.direction, latest.amountCents, options.hideAmounts)
        val amountPaint = textPaint(if (latest.direction == Direction.INCOME) palette.income else palette.expense,
            15f * unit, Typeface.BOLD, Paint.Align.RIGHT)
        val baseline = centerBaseline(height, amountPaint)
        drawEllipsized(canvas, latest.description, rightX, baseline,
            (right - rightX - amountPaint.measureText(signed) - 9f * unit).coerceAtLeast(0f),
            textPaint(palette.secondary, 12f * unit, Typeface.NORMAL, Paint.Align.LEFT))
        canvas.drawText(signed, right, baseline, amountPaint)
    }

    private fun drawNarrowCard(canvas: Canvas, content: WidgetContent, options: WidgetInstanceOptions,
        palette: Palette, amount: String, padX: Float, padY: Float, width: Int, height: Int, unit: Float) {
        val lowerSpace = 35f * unit
        drawFitted(canvas, amount, padX, padY, width - padX * 2,
            (height - padY * 2 - lowerSpace).coerceAtLeast(24f * unit), palette.foreground,
            42f * unit, Typeface.BOLD, Paint.Align.LEFT, true)
        val latest = content.latestTransaction ?: return
        val paint = textPaint(if (latest.direction == Direction.INCOME) palette.income else palette.expense,
            16f * unit, Typeface.BOLD, Paint.Align.LEFT)
        canvas.drawText(signedAmount(latest.direction, latest.amountCents, options.hideAmounts),
            padX, height - padY, paint)
    }

    private fun drawWideCard(canvas: Canvas, content: WidgetContent, options: WidgetInstanceOptions,
        palette: Palette, amount: String, padX: Float, padY: Float, width: Int, height: Int, unit: Float) {
        val transactionArea = if (content.latestTransaction == null) 0f else 43f * unit
        drawFitted(canvas, amount, padX, padY, width - padX * 2,
            (height - padY * 2 - transactionArea).coerceAtLeast(28f * unit), palette.foreground,
            50f * unit, Typeface.BOLD, Paint.Align.LEFT, true)
        val latest = content.latestTransaction ?: return
        val baseline = height - padY
        val signed = signedAmount(latest.direction, latest.amountCents, options.hideAmounts)
        val amountPaint = textPaint(if (latest.direction == Direction.INCOME) palette.income else palette.expense,
            18f * unit, Typeface.BOLD, Paint.Align.RIGHT)
        drawEllipsized(canvas, latest.description, padX, baseline,
            (width - padX * 2 - amountPaint.measureText(signed) - 14f * unit).coerceAtLeast(0f),
            textPaint(palette.secondary, 14f * unit, Typeface.NORMAL, Paint.Align.LEFT))
        canvas.drawText(signed, width - padX, baseline, amountPaint)
    }

    private fun signedAmount(direction: Direction, cents: Long, hidden: Boolean): String =
        if (hidden) "••••" else "${if (direction == Direction.INCOME) "+" else "−"}${MoneyUtils.format(cents)}"

    private fun drawFitted(canvas: Canvas, text: String, x: Float, y: Float, width: Float, height: Float,
        color: Int, preferredSize: Float, typeface: Int, align: Paint.Align, centerVertically: Boolean) {
        val paint = textPaint(color, preferredSize, typeface, align)
        while (paint.textSize > 12f && paint.measureText(text) > width) paint.textSize -= 1f
        val baseline = if (centerVertically) y + (height - paint.fontMetrics.ascent - paint.fontMetrics.descent) / 2f
            else y - paint.fontMetrics.ascent
        canvas.drawText(text, x, baseline, paint)
    }

    private fun drawEllipsized(canvas: Canvas, text: String, x: Float, baseline: Float, width: Float, paint: Paint) {
        if (width <= 0f) return
        if (paint.measureText(text) <= width) canvas.drawText(text, x, baseline, paint)
        else {
            val suffix = "…"
            val count = paint.breakText(text, true, (width - paint.measureText(suffix)).coerceAtLeast(0f), null)
            canvas.drawText(text.take(count).trimEnd() + suffix, x, baseline, paint)
        }
    }

    private fun centerBaseline(height: Int, paint: Paint): Float =
        (height - paint.fontMetrics.ascent - paint.fontMetrics.descent) / 2f

    private fun textPaint(color: Int, size: Float, typeface: Int, align: Paint.Align) =
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            this.color = color
            textSize = size
            textAlign = align
            this.typeface = Typeface.create("sans-serif", typeface)
        }

    private fun palette(context: Context, style: String): Palette {
        val darkSystem = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        return when (style) {
            "paper" -> Palette(0xFFFAF9F5.toInt(), 0xFF1F2C25.toInt(), 0xFF586C61.toInt(),
                0xFFE1E4DC.toInt(), 0xFF287657.toInt(), 0xFFA65341.toInt())
            "sage" -> Palette(0xFFE4EDE5.toInt(), 0xFF1F362B.toInt(), 0xFF4F695A.toInt(),
                0xFFC5D7C9.toInt(), 0xFF267051.toInt(), 0xFF9A4B3B.toInt())
            "ink" -> Palette(0xFF222D28.toInt(), 0xFFF7F7F2.toInt(), 0xFFC4CFC7.toInt(),
                0xFF415249.toInt(), 0xFF85D6A9.toInt(), 0xFFF4A48F.toInt())
            else -> if (darkSystem) Palette(0xFF1D2520.toInt(), 0xFFF1F6F1.toInt(), 0xFFB5C2B8.toInt(),
                0xFF3F4B43.toInt(), 0xFF85D6A9.toInt(), 0xFFF4A48F.toInt())
            else Palette(0xFFFEFFFC.toInt(), 0xFF1F2C25.toInt(), 0xFF64756A.toInt(),
                0xFFE1E6DE.toInt(), 0xFF287657.toInt(), 0xFFA65341.toInt())
        }
    }
}
