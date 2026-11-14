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
 *
 * Canisters carry the film code vertically (E100, G200, P400, 400H ...) with the brand line small
 * underneath, matching the Ricoh GR custom firmware screen this app follows.
 */

fun DrawScope.drawPresetArt(preset: LutPreset) {
    if (preset.isBadge) drawBadgeArt(preset) else drawCanisterArt(preset)
}

private fun DrawScope.drawCanisterArt(preset: LutPreset) {
    val w = size.width
    val h = size.height
    val bodyWidth = w * 0.46f
    val bodyHeight = h * 0.66f
    val left = (w - bodyWidth) / 2f
    val top = (h - bodyHeight) / 2f + h * 0.01f
    val right = left + bodyWidth
    val centerX = left + bodyWidth * 0.5f

    // spool sticking out of the top cap
    drawRoundRect(
        color = Color(0xFF1E1E24),
        topLeft = Offset(left + bodyWidth * 0.33f, top - h * 0.05f),
        size = Size(bodyWidth * 0.34f, h * 0.065f),
        cornerRadius = CornerRadius(w * 0.02f, w * 0.02f),
    )

    // canister body with a cylindrical sheen
    drawRoundRect(
        brush = Brush.horizontalGradient(
            0f to Color(preset.bodyColor2),
            0.24f to Color(preset.bodyColor),
            0.60f to Color(preset.bodyColor),
            1f to Color(preset.bodyColor2),
            startX = left,
            endX = right,
        ),
        topLeft = Offset(left, top),
        size = Size(bodyWidth, bodyHeight),
        cornerRadius = CornerRadius(w * 0.05f, w * 0.05f),
    )

    // metal caps + colour band under the top cap
    val capColor = Color(0xFF35353C)
    drawRect(capColor, Offset(left, top), Size(bodyWidth, bodyHeight * 0.08f))
    drawRect(capColor, Offset(left, top + bodyHeight * 0.92f), Size(bodyWidth, bodyHeight * 0.08f))
    drawRect(
        color = Color(preset.bandColor),
        topLeft = Offset(left, top + bodyHeight * 0.08f),
        size = Size(bodyWidth, bodyHeight * 0.065f),
    )

    val canvas = drawContext.canvas.nativeCanvas

    // film code, printed vertically down the canister
    val codeTop = top + bodyHeight * 0.17f
    val codeBottom = top + bodyHeight * 0.84f
    val codeCenterY = (codeTop + codeBottom) * 0.5f
    var sizePx = h * 0.17f
    while (sizePx > h * 0.055f && measureText(preset.label, sizePx) > (codeBottom - codeTop)) {
        sizePx *= 0.93f
    }
    val codePaint = textPaint(Color(preset.inkColor), sizePx)
    canvas.save()
    canvas.rotate(-90f, centerX, codeCenterY)
    canvas.drawText(preset.label, centerX, codeCenterY + sizePx * 0.34f, codePaint)
    canvas.restore()

    // small brand line above the bottom cap
    val brandPaint = textPaint(Color(preset.inkColor).copy(alpha = 0.78f), h * 0.048f)
    canvas.drawText(preset.brand.uppercase(), centerX, top + bodyHeight * 0.895f, brandPaint)
}

private fun DrawScope.drawBadgeArt(preset: LutPreset) {
    val w = size.width
    val h = size.height
    val plateWidth = w * 0.64f
    val plateHeight = h * 0.64f
    val left = (w - plateWidth) / 2f
    val top = (h - plateHeight) / 2f
    val centerX = left + plateWidth * 0.5f
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
        val dotCenter = Offset(centerX, top + plateHeight * 0.24f)
        drawCircle(Color(preset.bandColor), radius = plateWidth * 0.125f, center = dotCenter)
        drawCircle(
            color = Color.White.copy(alpha = 0.9f),
            radius = plateWidth * 0.032f,
            center = dotCenter,
        )
        val wordPaint = textPaint(Color(preset.inkColor), plateHeight * 0.075f)
        canvas.drawText("LEICA", centerX, top + plateHeight * 0.43f, wordPaint)
    } else {
        val wordPaint = textPaint(Color(preset.bandColor), plateHeight * 0.20f)
        canvas.drawText("GR", centerX, top + plateHeight * 0.28f, wordPaint)
        val smallPaint = textPaint(Color(preset.inkColor).copy(alpha = 0.85f), plateHeight * 0.065f)
        canvas.drawText("RICOH", centerX, top + plateHeight * 0.42f, smallPaint)
    }

    // look code printed across the badge
    var codeSize = plateHeight * 0.145f
    while (codeSize > plateHeight * 0.05f && measureText(preset.label, codeSize) > plateWidth * 0.78f) {
        codeSize *= 0.9f
    }
    val codePaint = textPaint(Color(preset.inkColor), codeSize)
    canvas.drawText(preset.label, centerX, top + plateHeight * 0.74f, codePaint)
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

private fun measureText(text: String, sizePx: Float): Float {
    val paint = textPaint(Color.White, sizePx)
    return paint.measureText(text)
}
