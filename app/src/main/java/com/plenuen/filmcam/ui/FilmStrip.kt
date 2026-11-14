package com.plenuen.filmcam.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.plenuen.filmcam.data.LutPreset

/**
 * 'Films in use' strip: a dark 35mm film band with sprocket holes, one canister per look and a
 * yellow focus frame around the selected slot (the Ricoh GR custom firmware screen this follows).
 */
@Composable
fun FilmStrip(
    presets: List<LutPreset>,
    selectedIdx: Int,
    onSelect: (LutPreset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(presets.firstOrNull()?.idx, selectedIdx) {
        val index = presets.indexOfFirst { it.idx == selectedIdx }
        if (index >= 0) {
            listState.animateScrollToItem(index)
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(3.dp))
            .background(FilmPalette.Strip)
            .drawBehind { drawSprocketHoles() },
    ) {
        LazyRow(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(presets, key = { it.idx }) { preset ->
                FilmSlot(
                    preset = preset,
                    selected = preset.idx == selectedIdx,
                    onClick = { onSelect(preset) },
                )
            }
        }
    }
}

private fun DrawScope.drawSprocketHoles() {
    val holeWidth = 9.dp.toPx()
    val holeHeight = 5.dp.toPx()
    val gap = 8.dp.toPx()
    val margin = 3.dp.toPx()
    val radius = CornerRadius(1.5.dp.toPx(), 1.5.dp.toPx())
    var x = 3.dp.toPx()
    while (x + holeWidth < size.width) {
        drawRoundRect(
            color = FilmPalette.Hole,
            topLeft = Offset(x, margin),
            size = Size(holeWidth, holeHeight),
            cornerRadius = radius,
        )
        drawRoundRect(
            color = FilmPalette.Hole,
            topLeft = Offset(x, size.height - margin - holeHeight),
            size = Size(holeWidth, holeHeight),
            cornerRadius = radius,
        )
        x += holeWidth + gap
    }
}

@Composable
private fun FilmSlot(
    preset: LutPreset,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(3.dp)
    Box(
        modifier = Modifier
            .width(76.dp)
            .fillMaxHeight()
            .clip(shape)
            .background(if (selected) Color(0xFF1D1A0E) else FilmPalette.Slot)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) FilmPalette.Accent else FilmPalette.SlotIdle,
                shape = shape,
            )
            .clickable { onClick() },
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 4.dp, bottom = 13.dp),
        ) {
            drawPresetArt(preset)
        }
        Text(
            text = "${preset.slot}",
            color = if (selected) FilmPalette.Accent else FilmPalette.TextDim,
            fontSize = 8.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 2.dp),
        )
    }
}
