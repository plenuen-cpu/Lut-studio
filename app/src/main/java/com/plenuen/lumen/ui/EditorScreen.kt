package com.plenuen.lumen.ui

import android.opengl.GLSurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.plenuen.lumen.core.Param
import com.plenuen.lumen.core.ParamGroup
import com.plenuen.lumen.data.LutPreset
import com.plenuen.lumen.gl.PhotoRenderer
import com.plenuen.lumen.gl.RenderSpec
import com.plenuen.lumen.llm.LlmModels

private val QUICK_PROMPTS = listOf(
    "부드럽게", "따뜻하게", "영화처럼", "필름처럼", "청량하게", "흑백으로", "예쁘게", "수평 맞춰줘",
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

    Scaffold(
        containerColor = LumenPalette.Background,
        snackbarHost = { SnackbarHost(snackbar) },
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
                            if (state.loaded) "${state.imageWidth}×${state.imageHeight} · 비파괴 편집"
                            else "자연어로 사진 보정",
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
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
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
            LaunchedEffect(state.recipe, state.compare, state.imageWidth, state.imageHeight) {
                renderer.updateSpec(
                    RenderSpec(state.recipe, state.imageWidth, state.imageHeight, state.compare),
                )
                glView?.requestRender()
            }
            LaunchedEffect(state.lutBytes) {
                renderer.setLut(state.lutBytes)
                glView?.requestRender()
            }

            // 적용된 보정 칩
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (state.chips.isEmpty() && state.loaded) {
                    item { Text("보정 없음", color = LumenPalette.TextDim, fontSize = 11.sp) }
                }
                items(state.chips) { chip ->
                    AssistChip(onClick = { }, label = { Text(chip, fontSize = 11.sp) })
                }
                if (state.recipe.lutIndex() >= 0 && state.lut != null) {
                    item { AssistChip(onClick = viewModel::toggleSliders, label = { Text("룩 ${state.lut?.display ?: ""}", fontSize = 11.sp) }) }
                }
            }

            // 빠른 요청
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

            // 하단 도구
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(52.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(onClick = { viewModel.autoStraighten() }, enabled = state.loaded) {
                    Text("자동 수평·수직", fontSize = 12.sp)
                }
                FilledTonalButton(onClick = { showLuts = !showLuts }, enabled = state.loaded) {
                    Text("필름 룩", fontSize = 12.sp)
                }
                FilledTonalButton(onClick = viewModel::toggleSliders, enabled = state.loaded) {
                    Text("슬라이더", fontSize = 12.sp)
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = viewModel::export, enabled = state.loaded) {
                    Text("저장", fontSize = 12.sp)
                }
                IconButton(onClick = onPickPhoto) {
                    Icon(Icons.Filled.Add, contentDescription = "다른 사진")
                }
            }

            if (showLuts) {
                LutStrip(
                    presets = state.presets,
                    selected = state.recipe.lutIndex(),
                    onSelect = { preset ->
                        viewModel.selectLut(preset)
                    },
                    intensity = state.recipe[Param.LUT_INTENSITY],
                    onIntensity = { viewModel.setParam(Param.LUT_INTENSITY, it) },
                )
            }

            if (state.showSliders) {
                SliderPanel(
                    recipe = state.recipe,
                    onParam = viewModel::setParam,
                    onAutoStraighten = viewModel::autoStraighten,
                )
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

@Composable
private fun SliderPanel(
    recipe: com.plenuen.lumen.core.EditRecipe,
    onParam: (Param, Float) -> Unit,
    onAutoStraighten: () -> Unit,
) {
    Surface(color = LumenPalette.Panel, modifier = Modifier.fillMaxWidth().height(300.dp)) {
        androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            ParamGroup.entries.forEach { group ->
                item(key = "header-${group.name}") {
                    Text(
                        group.label,
                        color = LumenPalette.Accent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                    )
                }
                val params = Param.sliders.filter { it.group == group }
                items(params.size) { index ->
                    val param = params[index]
                    SliderRow(param, recipe[param], onParam)
                }
                if (group == ParamGroup.GEOMETRY) {
                    item(key = "straighten-reset") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = onAutoStraighten) { Text("자동으로 다시 맞추기", fontSize = 11.sp) }
                            TextButton(onClick = { onParam(Param.STRAIGHTEN_DEG, 0f) }) {
                                Text("수평 0°", fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SliderRow(param: Param, value: Float, onParam: (Param, Float) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(param.label, fontSize = 12.sp, color = LumenPalette.Text, modifier = Modifier.weight(1f))
            Text(param.display(value), fontSize = 11.sp, color = LumenPalette.TextDim)
        }
        Slider(
            value = param.fraction(value),
            onValueChange = { onParam(param, param.fromFraction(it)) },
            valueRange = 0f..1f,
            modifier = Modifier.height(22.dp),
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
        Column(modifier = Modifier.padding(vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp)) {
                Text("필름 룩 57종", fontSize = 12.sp, color = LumenPalette.Accent, modifier = Modifier.weight(1f))
                Text("강도 ${(intensity * 100).toInt()}%", fontSize = 11.sp, color = LumenPalette.TextDim)
            }
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
            ) {
                item {
                    FilterChip(
                        selected = selected < 0,
                        onClick = { onSelect(null) },
                        label = { Text("없음", fontSize = 11.sp) },
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
                    )
                }
            }
            Slider(
                value = intensity,
                onValueChange = onIntensity,
                valueRange = 0f..1f,
                modifier = Modifier.padding(horizontal = 12.dp).height(20.dp),
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
            Column {
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
