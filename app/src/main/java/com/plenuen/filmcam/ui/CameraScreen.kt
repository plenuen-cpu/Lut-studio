package com.plenuen.filmcam.ui

import androidx.camera.core.CameraSelector
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.plenuen.filmcam.FilmCamViewModel
import com.plenuen.filmcam.camera.CameraController
import com.plenuen.filmcam.camera.GlPreviewView
import com.plenuen.filmcam.camera.PreviewSurfaceGateway
import com.plenuen.filmcam.data.PresetCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun CameraScreen(vm: FilmCamViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val gateway = remember(context) {
        PreviewSurfaceGateway(ContextCompat.getMainExecutor(context))
    }
    val controller = remember(lifecycleOwner) { CameraController(context, lifecycleOwner) }
    val glHolder = remember { mutableStateOf<GlPreviewView?>(null) }
    // The GL context can be lost while the app is in the background: the renderer then makes a new
    // SurfaceTexture (2nd time onwards) and the camera has to be handed the new surface again.
    val surfaceGenerations = remember { intArrayOf(0) }

    SideEffect {
        controller.onError = { message -> vm.showMessage(message) }
        controller.onCameraReady = { degrees ->
            glHolder.value?.lutRenderer?.rotationDegrees = degrees
        }
        glHolder.value?.lutRenderer?.let { renderer ->
            renderer.intensity = vm.intensity
            renderer.mirrorHorizontally = vm.lensFacing == CameraSelector.LENS_FACING_FRONT
        }
    }

    LaunchedEffect(vm.lensFacing) {
        controller.open(gateway, vm.lensFacing)
    }

    LaunchedEffect(vm.preset.idx) {
        val lut = withContext(Dispatchers.Default) { vm.library.lutFor(vm.preset) }
        glHolder.value?.lutRenderer?.setLut(lut)
    }

    val lifecycleObserver = remember {
        LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> glHolder.value?.onResume()
                Lifecycle.Event.ON_PAUSE -> glHolder.value?.onPause()
                else -> Unit
            }
        }
    }
    DisposableEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            controller.close()
            gateway.release()
        }
    }

    val counts = remember(vm.presets) { PresetCatalog.countByBrand(vm.presets) }

    Box(modifier = Modifier.fillMaxSize().background(FilmPalette.Background)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Header(vm)
            BrandChips(
                selected = vm.filter,
                counts = counts,
                total = vm.presets.size,
                onSelect = { brand -> vm.updateFilter(brand) },
            )
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(vm.frameRatio.ratio)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black)
                        .border(1.dp, FilmPalette.Line, RoundedCornerShape(8.dp)),
                ) {
                    AndroidView(
                        factory = { ctx ->
                            GlPreviewView(ctx).also { view ->
                                view.lutRenderer.onSurfaceTextureCreated = { texture ->
                                    view.post {
                                        gateway.onTextureReady(texture)
                                        surfaceGenerations[0] += 1
                                        if (surfaceGenerations[0] > 1) {
                                            controller.open(gateway, vm.lensFacing)
                                        }
                                    }
                                }
                                view.lutRenderer.requestFrame = { view.requestRender() }
                                view.lutRenderer.onGlError = { message ->
                                    view.post { vm.showMessage(message) }
                                }
                                gateway.onBufferSize = { width, height ->
                                    view.lutRenderer.sourceWidth = width
                                    view.lutRenderer.sourceHeight = height
                                }
                                glHolder.value = view
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    ViewfinderMarks()
                }
            }
            BottomDeck(
                vm = vm,
                onShutter = {
                    controller.takePicture(
                        onImage = { jpeg, rotation, mirror -> vm.capture(jpeg, rotation, mirror) },
                        onFailure = { message -> vm.showMessage(message) },
                    )
                },
            )
        }

        if (vm.developing) {
            DevelopingOverlay()
        }

        val message = vm.message
        if (message != null) {
            LaunchedEffect(message) {
                delay(3200)
                vm.clearMessage()
            }
            MessageBanner(message, modifier = Modifier.align(Alignment.TopCenter))
        }

        val photo = vm.lastPhoto
        if (vm.showRecent && photo != null) {
            RecentPhotoDialog(uri = photo, onDismiss = { vm.closeRecent() })
        }
    }
}

@Composable
private fun Header(vm: FilmCamViewModel) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "FilmCam",
            color = FilmPalette.Text,
            fontSize = 17.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(FilmPalette.Accent.copy(alpha = 0.16f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Text(
                text = "LIVE LUT",
                color = FilmPalette.Accent,
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = "${vm.presets.size} LOOKS",
            color = FilmPalette.TextDim,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun BottomDeck(vm: FilmCamViewModel, onShutter: () -> Unit) {
    val presets = remember(vm.filter, vm.presets) { vm.visiblePresets }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(FilmPalette.Panel)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "FILMS IN USE",
                color = FilmPalette.TextDim,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.weight(1f))
            RatioChip(vm.frameRatio) { vm.cycleRatio() }
            Spacer(Modifier.width(8.dp))
            Text(
                text = vm.preset.code,
                color = FilmPalette.Accent,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        Spacer(Modifier.height(6.dp))
        FilmStrip(
            presets = presets,
            selectedIdx = vm.preset.idx,
            onSelect = { preset -> vm.selectPreset(preset) },
            modifier = Modifier.height(112.dp),
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${vm.preset.slot}",
                color = FilmPalette.Accent,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = vm.preset.display,
                color = FilmPalette.Text,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(2.dp))
        IntensityRow(
            value = vm.intensity,
            percent = vm.intensityPercent,
            onChange = { value -> vm.updateIntensity(value) },
        )
        Spacer(Modifier.height(2.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                RecentThumb(uri = vm.lastPhoto, onClick = { vm.openRecent() })
            }
            ShutterButton(enabled = !vm.developing, onClick = onShutter)
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                LensSwitchButton(
                    isFront = vm.lensFacing == CameraSelector.LENS_FACING_FRONT,
                    onClick = { vm.toggleLens() },
                )
            }
        }
    }
}

@Composable
private fun ViewfinderMarks() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        drawCornerBrackets()
    }
}

private fun DrawScope.drawCornerBrackets() {
    val length = 16.dp.toPx()
    val inset = 10.dp.toPx()
    val stroke = 1.6.dp.toPx()
    val color = Color.White.copy(alpha = 0.35f)
    val left = inset
    val top = inset
    val right = size.width - inset
    val bottom = size.height - inset
    drawLine(color, Offset(left, top), Offset(left + length, top), stroke, StrokeCap.Round)
    drawLine(color, Offset(left, top), Offset(left, top + length), stroke, StrokeCap.Round)
    drawLine(color, Offset(right, top), Offset(right - length, top), stroke, StrokeCap.Round)
    drawLine(color, Offset(right, top), Offset(right, top + length), stroke, StrokeCap.Round)
    drawLine(color, Offset(left, bottom), Offset(left + length, bottom), stroke, StrokeCap.Round)
    drawLine(color, Offset(left, bottom), Offset(left, bottom - length), stroke, StrokeCap.Round)
    drawLine(color, Offset(right, bottom), Offset(right - length, bottom), stroke, StrokeCap.Round)
    drawLine(color, Offset(right, bottom), Offset(right, bottom - length), stroke, StrokeCap.Round)
}

@Composable
private fun MessageBanner(message: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xEE1B1B20))
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text(text = message, color = FilmPalette.Text, fontSize = 12.sp)
    }
}
