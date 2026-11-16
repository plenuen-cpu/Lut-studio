package com.plenuen.lumen.ui

import android.opengl.GLSurfaceView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.plenuen.lumen.LumenDiagnostics
import com.plenuen.lumen.core.HslBand
import com.plenuen.lumen.core.Param
import com.plenuen.lumen.core.ParamGroup
import com.plenuen.lumen.data.LutPreset
import com.plenuen.lumen.gl.PhotoRenderer
import com.plenuen.lumen.gl.RenderSpec
import com.plenuen.lumen.llm.LlmModels
import com.plenuen.lumen.nima.NimaScores

private val QUICK_PROMPTS = listOf(
    "자동 보정", "NIMA 높여줘", "부드럽게", "따뜻하게", "영화처럼", "필름처럼", "청량하게", "흑백으로", "예쁘게", "샤하게",
    "꾸안꾸", "포인트 있게", "눈에 띄게", "너무 화려하지는 않게", "수평 맞춰줘",
    "인물만 화사하게", "배경만 어둡게", "하늘만 파랗게", "빨강만 살리고 초록은 차분하게",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    viewModel: EditorViewModel,
    onPickPhoto: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val renderer = remember { PhotoRenderer() }
    var glView by remember { mutableStateOf<GLSurfaceView?>(null) }
    var prompt by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var showLuts by remember { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    // 키보드가 올라온 동안에는 입력창 바로 위까지만 남긴다: 프리뷰가 최대한 보이게.
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    val keyboardVisible = imeBottom > 0
    // 보정 패널(슬라이더·필름 룩)이 열린 동안은 아래 위젯을 접어 사진 자리를 지킨다.
    val panelOpen = state.showSliders || showLuts

    Scaffold(
        containerColor = LumenPalette.Background,
        // 인셋은 아래 Column이 ime·navigationBars를 함께 보며 직접 잡는다.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            // 키보드가 올라와도 안내가 가려지지 않게 같은 인셋을 준다.
            SnackbarHost(
                snackbar,
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .windowInsetsPadding(WindowInsets.ime),
            )
        },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = LumenPalette.Background,
                    titleContentColor = LumenPalette.Text,
                ),
                title = {
                    Column {
                        Text("루멘", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                        Text(
                            if (state.loaded) {
                                "${state.imageWidth}×${state.imageHeight} · NIMA ${NimaScores.format(state.nimaScore)}"
                            } else {
                                "자연어로 사진 보정"
                            },
                            fontSize = 11.sp,
                            color = LumenPalette.TextDim,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::undo, enabled = state.canUndo) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "되돌리기")
                    }
                    IconButton(onClick = viewModel::redo, enabled = state.canRedo) {
                        Icon(Icons.Filled.Refresh, contentDescription = "다시 실행")
                    }
                    IconButton(onClick = viewModel::reset, enabled = state.loaded) {
                        Icon(Icons.Filled.Delete, contentDescription = "초기화")
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "설정")
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // 키보드(IME)와 내비게이션 바 중 큰 쪽만큼 아래를 띄운다. 둘 다 더하면
                // 키보드 위에 내비게이션 바 높이만큼 빈 틈이 생긴다.
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Color.Black)
                        .pointerInput(state.loaded) {
                            detectTapGestures(
                                onLongPress = {
                                    if (state.loaded) viewModel.setCompare(true)
                                },
                                onPress = {
                                    tryAwaitRelease()
                                    viewModel.setCompare(false)
                                },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context ->
                            GLSurfaceView(context).apply {
                                setEGLContextClientVersion(3)
                                setRenderer(renderer)
                                renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                                glView = this
                            }
                        },
                    )
                    if (!state.loaded) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("사진을 골라 주세요", color = LumenPalette.TextDim)
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = onPickPhoto) { Text("갤러리에서 열기") }
                        }
                    }
                    if (state.loaded && !state.compare) {
                        // 사진 위에 세로선을 그리고, 선을 좌우로 끌어 원본과 보정을 비교한다.
                        SplitCompareOverlay(
                            imageWidth = state.imageWidth,
                            imageHeight = state.imageHeight,
                            splitX = state.splitX,
                            onDrag = viewModel::nudgeSplit,
                            visible = state.split,
                        )
                        FilterChip(
                            selected = state.split,
                            onClick = { viewModel.toggleSplit() },
                            label = { Text(if (state.split) "세로 비교 중" else "세로 비교", fontSize = 11.sp) },
                            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                        )
                        if (state.maskReady) {
                            FilterChip(
                                selected = state.maskPreview,
                                onClick = { viewModel.toggleMaskPreview() },
                                label = { Text("마스크 보기", fontSize = 11.sp) },
                                modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                            )
                        }
                    }
                    if (state.compare) {
                        Surface(
                            color = Color.Black.copy(alpha = 0.55f),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.align(Alignment.TopCenter).padding(8.dp),
                        ) {
                            Text(
                                "원본",
                                color = LumenPalette.Text,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                    }
                    if (state.busy != null) {
                        Surface(
                            color = Color.Black.copy(alpha = 0.6f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(modifier = Modifier.width(16.dp).height(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(state.busy ?: "", fontSize = 12.sp, color = LumenPalette.Text)
                            }
                        }
                    }
                }

                LaunchedEffect(state.preview) {
                    state.preview?.let {
                        renderer.setImage(it)
                        glView?.requestRender()
                    }
                }
                LaunchedEffect(
                    state.recipe, state.compare, state.imageWidth, state.imageHeight,
                    state.split, state.splitX, state.maskReady, state.maskPreview,
                ) {
                    renderer.updateSpec(
                        RenderSpec(
                            recipe = state.recipe,
                            imageWidth = state.imageWidth,
                            imageHeight = state.imageHeight,
                            compare = state.compare,
                            splitEnabled = state.split,
                            splitX = state.splitX,
                            maskReady = state.maskReady,
                            maskPreview = state.maskPreview,
                        ),
                    )
                    glView?.requestRender()
                }
                LaunchedEffect(state.mask) {
                    renderer.setMask(state.mask)
                    glView?.requestRender()
                }
                LaunchedEffect(state.lutBytes) {
                    renderer.setLut(state.lutBytes)
                    glView?.requestRender()
                }

                if (!keyboardVisible) {
                    // 적용된 보정 히스토리 — 칩 하나가 보정 한 단계다. 누르면 그 단계만 빠지고
                    // 나머지 단계는 그대로 다시 합산된다(맨 아래 '원본'은 남는다).
                    // 보정 패널이 열려 있는 동안에는 이 줄을 접어 사진 자리를 내어 준다(v2.2.5).
                    if (!panelOpen) {
                        LazyRow(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (state.steps.isEmpty() && state.loaded) {
                                item { Text("보정 없음", color = LumenPalette.TextDim, fontSize = 11.sp) }
                            }
                            items(state.steps, key = { it.id }) { step ->
                                if (step.isOrigin) {
                                    AssistChip(
                                        onClick = { },
                                        label = { Text(step.title, fontSize = 11.sp) },
                                    )
                                } else {
                                    InputChip(
                                        selected = false,
                                        onClick = { viewModel.removeStep(step.id) },
                                        label = { Text(step.title, fontSize = 11.sp) },
                                        trailingIcon = {
                                            Icon(
                                                Icons.Filled.Close,
                                                contentDescription = "이 보정 지우기",
                                                modifier = Modifier.width(14.dp).height(14.dp),
                                                tint = LumenPalette.TextDim,
                                            )
                                        },
                                    )
                                }
                            }
                            if (state.recipe.lutIndex() >= 0 && state.lut != null) {
                                item { AssistChip(onClick = viewModel::toggleSliders, label = { Text("룩 ${state.lut?.display ?: ""}", fontSize = 11.sp) }) }
                            }
                        }
                        if (state.loaded && state.steps.size > 2) {
                            Text(
                                "보정 칩을 누르면 그 단계만 지워져요 · 상단 ← 되돌리기",
                                fontSize = 10.sp,
                                color = LumenPalette.TextDim,
                                modifier = Modifier.padding(start = 16.dp),
                            )
                        }
                    }

                    // 빠른 요청 — 한 줄만 차지한다.
                    LazyRow(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(QUICK_PROMPTS) { text ->
                            FilterChip(
                                selected = false,
                                onClick = { viewModel.submitPrompt(text) },
                                label = { Text(text, fontSize = 11.sp) },
                            )
                        }
                    }
                }

                // 최근 보정 내역 (입력창 바로 위). 패널이 열려 있는 동안은 한 줄로 접는다.
                state.note?.let { note ->
                    ChangeCard(
                        note = note,
                        onDismiss = viewModel::dismissNote,
                        onSettings = { showSettings = true },
                        dense = panelOpen,
                    )
                }

                // 입력창
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("예: \"조금 더 따뜻하고 부드럽게\"", fontSize = 13.sp) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            viewModel.submitPrompt(prompt)
                            prompt = ""
                        }),
                    )
                    Spacer(Modifier.width(6.dp))
                    IconButton(
                        onClick = {
                            viewModel.submitPrompt(prompt)
                            prompt = ""
                        },
                        enabled = state.loaded && prompt.isNotBlank(),
                    ) {
                        Icon(Icons.Filled.Send, contentDescription = "적용", tint = LumenPalette.Accent)
                    }
                }

                if (!keyboardVisible) {
                    // 하단 도구
                    //
                    // 저장·새 사진은 오른쪽 고정 자리에 둔다. v2.2.0에서 [✨ 자동]이 붙은 뒤로는
                    // 보정 도구 4개 + 두 버튼을 한 줄에 다 넣으면 360dp 폰에서 오른쪽 두 버튼이
                    // 화면 밖으로 밀려 아예 보이지 않았다(v2.2.3 신고). 이제 앞쪽 도구만 옆으로
                    // 스크롤되고, 저장·새 사진은 어떤 폭에서도 제자리에 남는다.
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(52.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            // NIMA 기반 자동 보정: 누르면 점수 변화와 바뀐 파라미터가 카드에 뜬다.
                            Button(
                                onClick = viewModel::autoEnhance,
                                enabled = state.loaded && !state.nimaBusy,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                Text(if (state.nimaBusy) "자동…" else "✨ 자동", fontSize = 12.sp)
                            }
                            FilledTonalButton(
                                onClick = { viewModel.autoStraighten() },
                                enabled = state.loaded,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                Text("자동 수평·수직", fontSize = 12.sp)
                            }
                            FilledTonalButton(
                                onClick = {
                                    showLuts = !showLuts
                                    // 두 패널이 같이 서면 사진이 다시 작아진다: 한 번에 하나만 연다.
                                    if (showLuts) viewModel.closeSliders()
                                },
                                enabled = state.loaded,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                Text("필름 룩", fontSize = 12.sp)
                            }
                            FilledTonalButton(
                                onClick = {
                                    viewModel.toggleSliders()
                                    if (!state.showSliders) showLuts = false
                                },
                                enabled = state.loaded,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                Text("슬라이더", fontSize = 12.sp)
                            }
                            // 도구가 더 늘어나도 마지막 버튼이 오른쪽 끝에 붙어 잘리지 않게.
                            Spacer(Modifier.width(4.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        // ── 고정 자리: 새 사진 · 저장 ──
                        OutlinedButton(
                            onClick = onPickPhoto,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("새 사진", fontSize = 12.sp)
                        }
                        Spacer(Modifier.width(6.dp))
                        Button(
                            onClick = viewModel::export,
                            enabled = state.loaded,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        ) {
                            Text("저장", fontSize = 12.sp)
                        }
                    }

                    if (showLuts) {
                        LutStrip(
                            presets = state.presets,
                            selected = state.recipe.lutIndex(),
                            onSelect = { preset -> viewModel.selectLut(preset) },
                            intensity = state.recipe[Param.LUT_INTENSITY],
                            onIntensity = { viewModel.setParam(Param.LUT_INTENSITY, it) },
                        )
                    }

                    if (state.showSliders) {
                        SliderPanel(
                            recipe = state.recipe,
                            maskReady = state.maskReady,
                            maskPreview = state.maskPreview,
                            maskMethod = state.maskMethod,
                            onParam = viewModel::setParam,
                            onAutoStraighten = viewModel::autoStraighten,
                            onMaskToggle = viewModel::toggleMask,
                            onMaskPreview = viewModel::toggleMaskPreview,
                        )
                    }
                }
            }
        }
    }

    if (showSettings) {
        LlmSettingsDialog(
            state = state,
            onDismiss = { showSettings = false },
            onToggle = viewModel::setLlmEnabled,
            onSelectModel = viewModel::selectModel,
            onDownload = viewModel::downloadModel,
            onDelete = viewModel::deleteModel,
        )
    }
}

/**
 * Draggable horizontal Before/After line.
 *
 * The GL view letterboxes the photo inside its own viewport, so the line is positioned with the same
 * aspect-fit arithmetic the renderer uses — otherwise the line and the shader's split drift apart on
 * photos whose shape differs from the screen.
 */
/**
 * Draggable vertical Before/After line.
 *
 * The GL view letterboxes the photo inside its own viewport, so the line is positioned with the same
 * aspect-fit arithmetic the renderer uses — otherwise the line and the shader's split drift apart on
 * photos whose shape differs from the screen. The shader compares `vUv.x` against `uSplitX` and the
 * GL viewport is exactly the fitted photo rect, so `splitX` is measured across the photo, not the view.
 *
 * v2.2.5: the line is vertical (left = 원본, right = 보정) and drags horizontally.
 */
@Composable
private fun SplitCompareOverlay(
    imageWidth: Int,
    imageHeight: Int,
    splitX: Float,
    onDrag: (Float) -> Unit,
    visible: Boolean,
) {
    if (!visible) return
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val boxWidth = constraints.maxWidth.toFloat()
        val boxHeight = constraints.maxHeight.toFloat()
        if (imageWidth <= 0 || imageHeight <= 0 || boxWidth <= 0f || boxHeight <= 0f) {
            return@BoxWithConstraints
        }
        val imageAspect = imageWidth.toFloat() / imageHeight.toFloat()
        val boxAspect = boxWidth / boxHeight
        // 렌더러의 fit()과 같은 계산: 사진이 실제로 그려지는 사각형.
        val rectWidth = if (imageAspect > boxAspect) boxWidth else boxHeight * imageAspect
        val rectHeight = if (imageAspect > boxAspect) boxWidth / imageAspect else boxHeight
        val rectLeft = (boxWidth - rectWidth) / 2f
        val rectTop = (boxHeight - rectHeight) / 2f
        val lineX = rectLeft + rectWidth * splitX.coerceIn(0f, 1f)
        val density = LocalDensity.current
        val rectTopDp = with(density) { rectTop.toDp() }
        // 사진 윗변이 화면 위에 붙어 있으면 위쪽 칩(마스크 보기·세로 비교)과 겹치므로 배지를 한 줄 내린다.
        val badgeTop = if (rectTopDp < 44.dp) rectTopDp + 44.dp else rectTopDp + 8.dp

        val bandWidth = 48.dp
        Box(
            modifier = Modifier
                .width(bandWidth)
                .height(with(density) { rectHeight.toDp() })
                .offset(
                    x = with(density) { lineX.toDp() } - bandWidth / 2,
                    y = rectTopDp,
                )
                .pointerInput(rectWidth) {
                    detectHorizontalDragGestures { change, dragAmount ->
                        change.consume()
                        // 사진 폭에 대한 비율(0f..1f)로 넘긴다. 셰이더의 vUv.x와 같은 기준이다.
                        if (rectWidth > 1f) onDrag(dragAmount / rectWidth)
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val x = size.width / 2f
                drawLine(
                    color = Color.Black.copy(alpha = 0.55f),
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 3.dp.toPx(),
                )
                drawLine(
                    color = Color.White.copy(alpha = 0.95f),
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 1.5.dp.toPx(),
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .width(18.dp)
                    .height(44.dp)
                    .background(Color.White.copy(alpha = 0.92f), RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text("↔", color = Color.Black, fontSize = 10.sp)
            }
        }

        // 원본 / 보정 배지: 사진 사각형의 좌·우 상단. 선보다 위에 그린다.
        Box(
            modifier = Modifier
                .offset(x = with(density) { rectLeft.toDp() }, y = badgeTop)
                .width(with(density) { rectWidth.toDp() })
                .height(with(density) { rectHeight.toDp() }),
        ) {
            SplitBadge("원본", Modifier.align(Alignment.TopStart).padding(horizontal = 8.dp))
            SplitBadge("보정", Modifier.align(Alignment.TopEnd).padding(horizontal = 8.dp))
        }
    }
}

@Composable
private fun SplitBadge(text: String, modifier: Modifier = Modifier) {
    Surface(color = Color.Black.copy(alpha = 0.62f), shape = RoundedCornerShape(4.dp), modifier = modifier) {
        Text(
            text,
            color = Color.White,
            fontSize = 9.sp,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun ChangeCard(
    note: EditorViewModel.Note,
    onDismiss: () -> Unit,
    onSettings: () -> Unit,
    /** 패널이 열려 있을 때의 한 줄 모드. 사진 자리를 지키려고 캡션과 줄 수를 접는다. */
    dense: Boolean = false,
) {
    Surface(
        color = LumenPalette.PanelRaised.copy(alpha = 0.94f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = if (dense) 1.dp else 2.dp),
    ) {
        Row(
            modifier = Modifier.padding(
                start = 12.dp,
                end = 6.dp,
                top = if (dense) 3.dp else 8.dp,
                bottom = if (dense) 3.dp else 8.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                if (!dense) {
                    Text(note.caption, fontSize = 10.sp, color = LumenPalette.Accent)
                    Spacer(Modifier.height(2.dp))
                }
                Text(
                    note.text,
                    fontSize = if (dense) 11.sp else 12.sp,
                    color = LumenPalette.Text,
                    maxLines = if (dense) 1 else 3,
                )
            }
            if (!dense) {
                if (note.suggestDownload) {
                    TextButton(onClick = onSettings) { Text("설정", fontSize = 11.sp) }
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "닫기", tint = LumenPalette.TextDim)
                }
            } else {
                // 한 줄 모드에서는 48dp 버튼이 줄 높이를 되돌려 놓으므로 작은 ×만 둔다.
                Text(
                    "×",
                    fontSize = 15.sp,
                    color = LumenPalette.TextDim,
                    modifier = Modifier
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/** 슬라이더 패널이 차지하는 최대 높이. 그만큼 사진 프리뷰(weight 1f)가 남는다(v2.2.5). */
private val SLIDER_PANEL_MAX_HEIGHT = 176.dp

/**
 * 보정 슬라이더 패널 — 한 줄 카테고리 칩 + 고른 그룹 하나만 펼치는 스크롤.
 *
 * 예전에는 8개 그룹의 머리글과 슬라이더를 한 LazyColumn에 모두 쌓아 고정 300dp를 차지했고, 그만큼
 * 사진 프리뷰가 쪼그라들었다(사용자 신고: "슬라이드 하면 작업 사진 영역이 너무 작아지고?").
 * 지금은 그룹 칩 한 줄(약 34dp) + 그룹 내용 스크롤로 바꾸고, 패널 전체를 [SLIDER_PANEL_MAX_HEIGHT]로
 * 묶어 사진이 항상 화면의 큰 몫을 차지하게 한다.
 */
@Composable
private fun SliderPanel(
    recipe: com.plenuen.lumen.core.EditRecipe,
    maskReady: Boolean,
    maskPreview: Boolean,
    maskMethod: String,
    onParam: (Param, Float) -> Unit,
    onAutoStraighten: () -> Unit,
    onMaskToggle: (Boolean) -> Unit,
    onMaskPreview: () -> Unit,
) {
    var bandIndex by remember { mutableStateOf(0) }
    var groupName by remember { mutableStateOf(ParamGroup.TONE.name) }
    val group = ParamGroup.entries.firstOrNull { it.name == groupName } ?: ParamGroup.TONE
    Surface(
        color = LumenPalette.Panel,
        modifier = Modifier.fillMaxWidth().heightIn(max = SLIDER_PANEL_MAX_HEIGHT),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            LazyRow(
                modifier = Modifier.fillMaxWidth().height(32.dp).padding(top = 2.dp),
                contentPadding = PaddingValues(horizontal = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(ParamGroup.entries.size) { index ->
                    val entry = ParamGroup.entries[index]
                    FilterChip(
                        selected = group == entry,
                        onClick = { groupName = entry.name },
                        label = { Text(entry.chip, fontSize = 10.sp) },
                        modifier = Modifier.height(28.dp),
                    )
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
            ) {
                when (group) {
                    ParamGroup.REGION -> {
                        Text(
                            if (maskReady) "마스크: $maskMethod" else "마스크 준비 중… (인물·하늘 인식)",
                            fontSize = 10.sp,
                            color = LumenPalette.TextDim,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FilterChip(
                                selected = recipe[Param.MASK_ON] >= 0.5f,
                                onClick = { onMaskToggle(recipe[Param.MASK_ON] < 0.5f) },
                                label = { Text("영역 보정", fontSize = 11.sp) },
                                modifier = Modifier.height(28.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            FilterChip(
                                selected = maskPreview,
                                onClick = onMaskPreview,
                                enabled = maskReady,
                                label = { Text("마스크 보기", fontSize = 11.sp) },
                                modifier = Modifier.height(28.dp),
                            )
                        }
                        Param.sliders.filter { it.group == group }.forEach { param ->
                            SliderRow(param, recipe[param], onParam)
                        }
                    }
                    ParamGroup.HSL -> {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(HslBand.entries.size) { index ->
                                val band = HslBand.entries[index]
                                FilterChip(
                                    selected = bandIndex == index,
                                    onClick = { bandIndex = index },
                                    modifier = Modifier.height(28.dp),
                                    label = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                modifier = Modifier
                                                    .width(10.dp)
                                                    .height(10.dp)
                                                    .background(Color(band.swatch), RoundedCornerShape(5.dp)),
                                            )
                                            Spacer(Modifier.width(5.dp))
                                            Text(band.label, fontSize = 11.sp)
                                        }
                                    },
                                )
                            }
                        }
                        val band = HslBand.entries[bandIndex.coerceIn(0, HslBand.entries.size - 1)]
                        band.params.forEach { param -> SliderRow(param, recipe[param], onParam) }
                        TextButton(
                            onClick = { band.params.forEach { onParam(it, 0f) } },
                            contentPadding = PaddingValues(horizontal = 4.dp),
                        ) {
                            Text("${band.label} 초기화", fontSize = 11.sp)
                        }
                    }
                    ParamGroup.GEOMETRY -> {
                        Param.sliders.filter { it.group == group }.forEach { param ->
                            SliderRow(param, recipe[param], onParam)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = onAutoStraighten, contentPadding = PaddingValues(horizontal = 4.dp)) {
                                Text("자동으로 다시 맞추기", fontSize = 11.sp)
                            }
                            TextButton(onClick = { onParam(Param.STRAIGHTEN_DEG, 0f) }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                                Text("수평 0°", fontSize = 11.sp)
                            }
                        }
                    }
                    else -> {
                        Param.sliders.filter { it.group == group }.forEach { param ->
                            SliderRow(param, recipe[param], onParam)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SliderRow(param: Param, value: Float, onParam: (Param, Float) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                param.label,
                fontSize = 11.sp,
                color = LumenPalette.Text,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            Text(param.display(value), fontSize = 10.sp, color = LumenPalette.TextDim)
        }
        // 트랙을 얇게: 한 줄 높이를 22dp → 20dp로 줄이고 패딩을 없앴다.
        Slider(
            value = param.fraction(value),
            onValueChange = { onParam(param, param.fromFraction(it)) },
            valueRange = 0f..1f,
            modifier = Modifier.fillMaxWidth().height(20.dp),
        )
    }
}

@Composable
private fun LutStrip(
    presets: List<LutPreset>,
    selected: Int,
    onSelect: (LutPreset?) -> Unit,
    intensity: Float,
    onIntensity: (Float) -> Unit,
) {
    Surface(color = LumenPalette.Panel, modifier = Modifier.fillMaxWidth()) {
        // 룩 줄도 사진 자리를 지키게 최소 높이로: 머리글 한 줄 + 칩 한 줄 + 강도 슬라이더.
        Column(modifier = Modifier.padding(vertical = 3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp)) {
                Text("필름 룩 57종", fontSize = 11.sp, color = LumenPalette.Accent, modifier = Modifier.weight(1f))
                Text("강도 ${(intensity * 100).toInt()}%", fontSize = 10.sp, color = LumenPalette.TextDim)
            }
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
            ) {
                item {
                    FilterChip(
                        selected = selected < 0,
                        onClick = { onSelect(null) },
                        label = { Text("없음", fontSize = 11.sp) },
                        modifier = Modifier.height(28.dp),
                    )
                }
                items(presets.size) { index ->
                    val preset = presets[index]
                    FilterChip(
                        selected = selected == preset.idx,
                        onClick = { onSelect(preset) },
                        label = {
                            Text(
                                "${preset.slot}. ${preset.display}",
                                fontSize = 11.sp,
                                maxLines = 1,
                            )
                        },
                        modifier = Modifier.height(28.dp),
                    )
                }
            }
            Slider(
                value = intensity,
                onValueChange = onIntensity,
                valueRange = 0f..1f,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(18.dp),
            )
        }
    }
}

@Composable
private fun LlmSettingsDialog(
    state: EditorViewModel.UiState,
    onDismiss: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onSelectModel: (com.plenuen.lumen.llm.LlmModel) -> Unit,
    onDownload: (com.plenuen.lumen.llm.LlmModel) -> Unit,
    onDelete: (com.plenuen.lumen.llm.LlmModel) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("로컬 LLM", fontSize = 16.sp) },
        text = {
            val context = LocalContext.current
            var failures by remember { mutableStateOf(LumenDiagnostics.read(context)) }
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "인터넷 없이 기기에서 문장을 읽습니다. 모델은 필요할 때만 내려받고, 사진은 어디로도 나가지 않습니다.",
                    fontSize = 12.sp,
                    color = LumenPalette.TextDim,
                )
                Spacer(Modifier.height(10.dp))
                LlmModels.all.forEach { model ->
                    val downloaded = model.id in state.llm.downloadedIds
                    val selected = state.llm.modelId == model.id
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(model.title, fontSize = 13.sp, color = LumenPalette.Text)
                            Text("${model.sizeMb}MB · ${model.note}", fontSize = 10.sp, color = LumenPalette.TextDim)
                            if (state.llm.downloadProgress != null && selected) {
                                LinearProgressIndicator(
                                    progress = { state.llm.downloadProgress ?: 0f },
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                )
                            }
                        }
                        when {
                            downloaded -> IconButton(onClick = { onDelete(model) }) {
                                Icon(Icons.Filled.Check, contentDescription = "삭제", tint = LumenPalette.Accent)
                            }
                            state.llm.downloadProgress != null -> CircularProgressIndicator(
                                modifier = Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp,
                            )
                            else -> TextButton(onClick = { onSelectModel(model); onDownload(model) }) {
                                Text("받기", fontSize = 12.sp)
                            }
                        }
                    }
                    if (selected) {
                        Text("선택됨", fontSize = 10.sp, color = LumenPalette.AccentDim)
                    }
                }
                if (state.llm.status.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(state.llm.status, fontSize = 11.sp, color = LumenPalette.TextDim)
                }
                // 현장 보고 대응: 앱이 닫혔거나 요청을 처리하지 못했으면 그 이유가 여기 남아 있다.
                failures?.let { log ->
                    Spacer(Modifier.height(12.dp))
                    Text("최근 오류 기록", fontSize = 12.sp, color = LumenPalette.Accent)
                    Text(
                        log.takeLast(1200),
                        fontSize = 9.sp,
                        color = LumenPalette.TextDim,
                        maxLines = 16,
                    )
                    Row {
                        TextButton(onClick = {
                            runCatching {
                                val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                                clipboard?.setPrimaryClip(
                                    android.content.ClipData.newPlainText("Lumen 오류 기록", log),
                                )
                            }
                        }) { Text("복사", fontSize = 11.sp) }
                        TextButton(onClick = {
                            LumenDiagnostics.clear(context)
                            failures = null
                        }) { Text("기록 지우기", fontSize = 11.sp) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onToggle(!state.llm.enabled) }) {
                Text(if (state.llm.enabled) "자동 해석 끄기" else "자동 해석 켜기", fontSize = 12.sp)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("닫기", fontSize = 12.sp) } },
    )
}
