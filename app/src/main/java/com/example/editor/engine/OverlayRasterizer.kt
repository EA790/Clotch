package com.example.editor.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.res.ResourcesCompat
import com.example.R
import com.example.editor.model.EditorFontChoice
import com.example.editor.model.TimelineSticker
import com.example.editor.model.TimelineTextOverlay
import java.io.File

object OverlayRasterizer {

    fun rasterizeTextOverlayToPng(
        context: Context,
        overlay: TimelineTextOverlay,
        canvasWidth: Int,
        canvasHeight: Int
    ): File {
        val dir = File(context.cacheDir, "editor_overlays").apply { if (!exists()) mkdirs() }
        val file = File(dir, "txt_${overlay.id}.png")

        val scaleFactor = canvasWidth.coerceAtLeast(720) / 1080f
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = overlay.colorArgb.toInt()
            textSize = (overlay.fontSizeSp * 2.4f * scaleFactor).coerceIn(28f, 150f)
            typeface = resolveTypeface(context, overlay.fontChoice)
            setShadowLayer(6f * scaleFactor, 0f, 3f * scaleFactor, Color.argb(180, 0, 0, 0))
        }

        val maxBoxW = (canvasWidth * 0.84f).toInt().coerceAtLeast(240)
        val layout = StaticLayout.Builder
            .obtain(overlay.text, 0, overlay.text.length, textPaint, maxBoxW)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(4f * scaleFactor, 1.05f)
            .setIncludePad(false)
            .build()

        var maxLineW = 0f
        for (i in 0 until layout.lineCount) {
            maxLineW = maxOf(maxLineW, layout.getLineWidth(i))
        }
        val padH = (28f * scaleFactor).toInt()
        val padV = (16f * scaleFactor).toInt()
        val bmpW = (maxLineW.toInt() + padH * 2).coerceIn(64, canvasWidth)
        val bmpH = (layout.height + padV * 2).coerceIn(48, canvasHeight)

        val bitmap = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        if (overlay.hasBackgroundPill) {
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(190, 16, 23, 39)
                style = Paint.Style.FILL
            }
            val radius = 22f * scaleFactor
            canvas.drawRoundRect(
                RectF(0f, 0f, bmpW.toFloat(), bmpH.toFloat()),
                radius,
                radius,
                bgPaint
            )
        }

        canvas.save()
        val drawOffsetX = (bmpW - maxBoxW) / 2f
        canvas.translate(drawOffsetX, padV.toFloat())
        layout.draw(canvas)
        canvas.restore()

        file.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bitmap.recycle()
        return file
    }

    fun rasterizeStickerToPng(
        context: Context,
        sticker: TimelineSticker,
        canvasWidth: Int
    ): File {
        val dir = File(context.cacheDir, "editor_overlays").apply { if (!exists()) mkdirs() }
        val file = File(dir, "stk_${sticker.id}.png")

        val scaleFactor = canvasWidth.coerceAtLeast(720) / 1080f
        val sizePx = (160f * scaleFactor * sticker.scale).toInt().coerceIn(72, 460)
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = sizePx * 0.72f
            textAlign = Paint.Align.CENTER
        }
        val fm = paint.fontMetrics
        val baseline = sizePx / 2f - (fm.ascent + fm.descent) / 2f
        canvas.drawText(sticker.emoji, sizePx / 2f, baseline, paint)

        file.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bitmap.recycle()
        return file
    }

    private fun resolveTypeface(context: Context, choice: EditorFontChoice): Typeface {
        val resId = when (choice) {
            EditorFontChoice.SPACE_GROTESK -> R.font.space_grotesk
            EditorFontChoice.INTER -> R.font.inter
            EditorFontChoice.JETBRAINS_MONO -> R.font.jetbrains_mono
        }
        return runCatching {
            ResourcesCompat.getFont(context, resId) ?: Typeface.DEFAULT_BOLD
        }.getOrDefault(Typeface.DEFAULT_BOLD)
    }
}
