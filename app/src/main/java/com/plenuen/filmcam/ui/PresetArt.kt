package com.plenuen.filmcam.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import com.plenuen.filmcam.data.LutPreset

/**
 * Painted film strip artwork: a 35mm canister for film stocks, a brand badge for Leica / Ricoh GR.
 */

fun DrawScope.drawPresetArt(preset: LutPreset) {
    if (preset.isBadge) drawBadgeArt(preset) else drawCanisterArt(preset)
}

private fun DrawScope.drawCanisterArt(preset: LutPreset) {
    val w = size.width
    val h = size.height
    val bodyWidth = w * 0.46f
    val bodyHeight = h * 0.64f
    val left = (w - bodyWidth) / 2f
    val top = (h - bodyHeight) / 2f + h * 0.02f
    val radius = CornerRadius(w * 0.06f, w * 0.06f)

    // spool sticking out of the top cap
    drawRoundRect(
        color = Color(0xFF1E1E24),
        topLeft = Offset(left + bodyWidth * 0.33f, top - h * 0.055f),
        size = Size(bodyWidth * 0.34f, h * 0.07f),
        cornerRadius = CornerRadius(w * 0.02f, w * 0.02f),
    )

    // canister body with a cylindrical sheen
    drawRoundRect(
        brush = Brush.horizontalGradient(
            0f to Color(preset.bodyColor2),
            0.22f to Color(preset.bodyColor),
            0.62f to Color(preset.bodyColor),
            1f to Color(preset.bodyColor2),
            startX = left,
            endX = left + bodyWidth,
        ),
        topLeft = Offset(left, top),
        size = Size(bodyWidth, bodyHeight),
        cornerRadius = radius,
    )

    // metal caps
    val capColor = Color(0xFF33333A)
    drawRect(capColor, Offset(left, top), Size(bodyWidth, bodyHeight * 0.085f))
    drawRect(capColor, Offset(left, top + bodyHeight * 0.915f), Size(bodyWidth, bodyHeight * 0.085f))

    // colour band just under the cap
    drawRect(
        color = Color(preset.bandColor),
        topLeft = Offset(left, top + bodyHeight * 0.085f),
        size = Size(bodyWidth, bodyHeight * 0.075f),
    )

    // film code, printed vertically down the canister
    val labelCenterX = left + bodyWidth * 0.5f
    val labelCenterY = top + bodyHeight * 0.55f
    val maxLabelHeight = bodyHeight * 0.74f
    var sizePx = h * 0.155f
    while (sizePx > h * 0.055f && measureText(preset.label, sizePx, Color(preset.inkColor)) > maxLabelHeight) {
        sizePx *= 0.92f
    }
    val canvas = drawContext.canvas.nativeCanvas
    val labelPaint = textPaint(Color(preset.inkColor), sizePx)
    canvas.save()
    canvas.rotate(-90f, labelCenterX, labelCenterY)
    canvas.drawText(preset.label, labelCenterX, labelCenterY + sizePx * 0.34f, labelPaint)
    canvas.restore()

    // tiny brand line near the top of the canister
    val brandPaint = textPaint(Color(preset.inkColor).copy(alpha = 0.75f), h * 0.052f)
    val brandY = top + bodyHeight * 0.22f
    canvas.save()
    canvas.rotate(-90f, labelCenterX, brandY)
    canvas.drawText(preset.brand.uppercase(), labelCenterX, brandY, brandPaint)
    canvas.restore()
}

private fun DrawScope.drawBadgeArt(preset: LutPreset) {
    val w = size.width
    val h = size.height
    val plateWidth = w * 0.62f
    val plateHeight = h * 0.62f
    val left = (w - plateWidth) / 2f
    val top = (h - plateHeight) / 2f
    val radius = CornerRadius(w * 0.07f, w * 0.07f)

    drawRoundRect(
        color = Color(preset.bodyColor),
        topLeft = Offset(left, top),
        size = Size(plateWidth, plateHeight),
        cornerRadius = radius,
    )
    drawRoundRect(
        color = Color(0xFF3A3A44),
        topLeft = Offset(left, top),
        size = Size(plateWidth, plateHeight),
        cornerRadius = radius,
        style = Stroke(width = w * 0.012f),
    )

    val canvas = drawContext.canvas.nativeCanvas
    if (preset.brand == "Leica") {
        val dotCenter = Offset(left + plateWidth * 0.5f, top + plateHeight * 0.28f)
        drawCircle(Color(preset.bandColor), radius = plateWidth * 0.17f, center = dotCenter)
        drawCircle(
            color = Color.White.copy(alpha = 0.85f),
            radius = plateWidth * 0.05f,
            center = dotCenter,
        )
        val wordPaint = textPaint(Color(preset.inkColor), plateHeight * 0.11f)
        canvas.drawText(
            "LEICA",
            left + plateWidth * 0.5f,
            top + plateHeight * 0.50f,
            wordPaint,
        )
    } else {
        val wordPaint = textPaint(Color(preset.bandColor), plateHeight * 0.26f)
        canvas.drawText("GR", left + plateWidth * 0.5f, top + plateHeight * 0.36f, wordPaint)
        val smallPaint = textPaint(Color(preset.inkColor).copy(alpha = 0.8f), plateHeight * 0.085f)
        canvas.drawText("RICOH", left + plateWidth * 0.5f, top + plateHeight * 0.45f, smallPaint)
    }

    // look code, printed vertically like a badge stamp
    val maxHeight = plateHeight * 0.42f
    var sizePx = plateHeight * 0.16f
    while (sizePx > plateHeight * 0.055f && measureText(preset.label, sizePx, Color(preset.inkColor)) > maxHeight) {
        sizePx *= 0.9f
    }
    val codePaint = textPaint(Color(preset.inkColor), sizePx)
    val cx = left + plateWidth * 0.5f
    val cy = top + plateHeight * 0.72f
    canvas.save()
    canvas.rotate(-90f, cx, cy)
    canvas.drawText(preset.label, cx, cy + sizePx * 0.34f, codePaint)
    canvas.restore()
}

private fun textPaint(color: Color, sizePx: Float): android.graphics.Paint =
    android.graphics.Paint().apply {
        isAntiAlias = true
        this.color = color.toArgb()
        textSize = sizePx
        textAlign = android.graphics.Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(
            "sans-serif-condensed",
            android.graphics.Typeface.BOLD,
        )
        letterSpacing = 0.06f
    }

private fun measureText(text: String, sizePx: Float, color: Color): Float {
    val paint = textPaint(color, sizePx)
    return paint.measureText(text)
}
