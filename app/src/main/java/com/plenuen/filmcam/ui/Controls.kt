package com.plenuen.filmcam.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeomSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.plenuen.filmcam.FrameRatio
import com.plenuen.filmcam.data.PresetCatalog
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun BrandChips(
    selected: String,
    counts: Map<String, Int>,
    total: Int,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(PresetCatalog.FILTERS) { brand ->
            val active = brand == selected
            val label = if (brand == PresetCatalog.BRAND_ALL) {
                "$brand $total"
            } else {
                "$brand ${counts[brand] ?: 0}"
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (active) FilmPalette.Accent else Color.Transparent)
                    .border(
                        width = 1.dp,
                        color = if (active) FilmPalette.Accent else FilmPalette.Line,
                        shape = RoundedCornerShape(50),
                    )
                    .clickable { onSelect(brand) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Text(
                    text = label,
                    color = if (active) Color(0xFF141414) else FilmPalette.TextDim,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
fun IntensityRow(
    value: Float,
    percent: Int,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "LUT",
            color = FilmPalette.TextDim,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.width(6.dp))
        Slider(
            value = value,
            onValueChange = onChange,
            colors = SliderDefaults.colors(
                thumbColor = FilmPalette.Accent,
                activeTrackColor = FilmPalette.Accent,
                inactiveTrackColor = FilmPalette.Line,
            ),
            modifier = Modifier
                .weight(1f)
                .height(26.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "$percent%",
            color = FilmPalette.Accent,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
fun RatioChip(ratio: FrameRatio, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .border(1.dp, FilmPalette.Line, RoundedCornerShape(4.dp))
            .clickable { onClick() }
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Text(
            text = ratio.label,
            color = FilmPalette.TextDim,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
fun ShutterButton(enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .border(2.dp, Color.White.copy(alpha = if (enabled) 0.9f else 0.4f), CircleShape)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(if (enabled) Color.White else Color(0xFF5A5A5A)),
        )
    }
}

@Composable
fun LensSwitchButton(isFront: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(FilmPalette.Slot)
            .border(1.dp, FilmPalette.Line, CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Canvas(modifier = Modifier.size(22.dp)) {
            drawFlipIcon(if (isFront) Color(0xFFF5C518) else Color(0xFFEDEDED))
        }
    }
}

private fun DrawScope.drawFlipIcon(color: Color) {
    val stroke = 1.8.dp.toPx()
    val inset = stroke
    drawArc(
        color = color,
        startAngle = 60f,
        sweepAngle = 250f,
        useCenter = false,
        style = Stroke(width = stroke, cap = StrokeCap.Round),
        topLeft = Offset(inset, inset),
        size = GeomSize(size.width - inset * 2, size.height - inset * 2),
    )
    val cx = size.width / 2f
    val cy = size.height / 2f
    val radius = (size.width - inset * 2) / 2f
    val angle = Math.toRadians(60.0)
    val tipX = cx + radius * cos(angle).toFloat()
    val tipY = cy + radius * sin(angle).toFloat()
    val tangent = Math.toRadians(60.0 + 90.0)
    val dx = cos(tangent).toFloat() * 3.2.dp.toPx()
    val dy = sin(tangent).toFloat() * 3.2.dp.toPx()
    val nx = -dy
    val ny = dx
    drawLine(color, Offset(tipX - dx + nx, tipY - dy + ny), Offset(tipX, tipY), strokeWidth = stroke * 1.6f, cap = StrokeCap.Round)
    drawLine(color, Offset(tipX - dx - nx, tipY - dy - ny), Offset(tipX, tipY), strokeWidth = stroke * 1.6f, cap = StrokeCap.Round)
}

@Composable
fun RecentThumb(uri: Uri?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val bitmap = rememberThumbnail(uri)
    Box(
        modifier = modifier
            .size(50.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(FilmPalette.Slot)
            .border(1.dp, FilmPalette.Line, RoundedCornerShape(8.dp))
            .clickable(enabled = bitmap != null) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "최근 사진",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(text = "—", color = FilmPalette.TextDim, fontSize = 12.sp)
        }
    }
}

@Composable
private fun rememberThumbnail(uri: Uri?): Bitmap? {
    val context = LocalContext.current
    return remember(uri) {
        if (uri == null) return@remember null
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.loadThumbnail(uri, Size(256, 256), null)
            } else {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val options = BitmapFactory.Options().apply { inSampleSize = 4 }
                    BitmapFactory.decodeStream(stream, null, options)
                }
            }
        }.getOrNull()
    }
}

@Composable
fun RecentPhotoDialog(uri: Uri, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val bitmap = remember(uri) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(stream, null, bounds)
                val options = BitmapFactory.Options().apply { inSampleSize = 2 }
                context.contentResolver.openInputStream(uri)?.use { second ->
                    BitmapFactory.decodeStream(second, null, options)
                }
            }
        }.getOrNull()
    }
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black)
                .clickable { onDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "저장한 사진",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text(
                    text = "사진을 불러올 수 없습니다.",
                    color = FilmPalette.Text,
                    modifier = Modifier.padding(24.dp),
                )
            }
        }
    }
}

@Composable
fun DevelopingOverlay(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0x99000000)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = FilmPalette.Accent, strokeWidth = 2.dp)
            Spacer(Modifier.height(14.dp))
            Text(
                text = "현상 중…",
                color = FilmPalette.Text,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}
