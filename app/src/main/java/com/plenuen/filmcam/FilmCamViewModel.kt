package com.plenuen.filmcam

import android.app.Application
import android.net.Uri
import androidx.camera.core.CameraSelector
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.plenuen.filmcam.data.LutLibrary
import com.plenuen.filmcam.data.LutPreset
import com.plenuen.filmcam.data.PresetCatalog
import com.plenuen.filmcam.image.Developer
import com.plenuen.filmcam.image.PhotoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

enum class FrameRatio(val ratio: Float, val label: String) {
    RATIO_3_2(1.5f, "3:2"),
    RATIO_4_3(4f / 3f, "4:3"),
    RATIO_16_9(16f / 9f, "16:9"),
    RATIO_1_1(1f, "1:1"),
}

class FilmCamViewModel(application: Application) : AndroidViewModel(application) {

    val library = LutLibrary(application)

    var selectedIdx by mutableStateOf(0)
        private set

    var intensity by mutableStateOf(1f)
        private set

    var filter by mutableStateOf(PresetCatalog.BRAND_ALL)
        private set

    var frameRatio by mutableStateOf(FrameRatio.RATIO_3_2)
        private set

    var lensFacing by mutableStateOf(CameraSelector.LENS_FACING_BACK)
        private set

    var lastPhoto by mutableStateOf<Uri?>(null)
        private set

    var photoCount by mutableStateOf(0)
        private set

    var developing by mutableStateOf(false)
        private set

    var message by mutableStateOf<String?>(null)
        private set

    var showRecent by mutableStateOf(false)
        private set

    val presets: List<LutPreset> get() = library.presets

    val preset: LutPreset get() = library.presets.first { it.idx == selectedIdx }

    val visiblePresets: List<LutPreset> get() = library.presetsFor(filter)

    val intensityPercent: Int get() = (intensity * 100f).roundToInt()

    fun selectPreset(idx: Int) {
        if (library.presets.none { it.idx == idx }) return
        selectedIdx = idx
    }

    fun selectPreset(preset: LutPreset) = selectPreset(preset.idx)

    fun updateFilter(brand: String) {
        filter = brand
        if (brand != PresetCatalog.BRAND_ALL && preset.brand != brand) {
            library.presetsFor(brand).firstOrNull()?.let { selectPreset(it.idx) }
        }
    }

    fun updateIntensity(value: Float) {
        intensity = value.coerceIn(0f, 1f)
    }

    fun cycleRatio() {
        val values = FrameRatio.values()
        frameRatio = values[(frameRatio.ordinal + 1) % values.size]
    }

    fun toggleLens() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
    }

    fun clearMessage() {
        message = null
    }

    fun showMessage(text: String) {
        message = text
    }

    fun openRecent() {
        if (lastPhoto != null) showRecent = true
    }

    fun closeRecent() {
        showRecent = false
    }

    fun capture(jpeg: ByteArray, rotationDegrees: Int, mirror: Boolean) {
        if (developing) return
        val look = preset
        val lookIntensity = intensity
        val ratio = frameRatio.ratio
        viewModelScope.launch {
            developing = true
            try {
                val bitmap = withContext(Dispatchers.Default) {
                    val lut = library.lutFor(look)
                    Developer.develop(jpeg, rotationDegrees, mirror, ratio, lut, lookIntensity)
                }
                val uri = withContext(Dispatchers.IO) { PhotoStore.save(getApplication(), bitmap) }
                bitmap.recycle()
                lastPhoto = uri
                photoCount += 1
            } catch (t: Throwable) {
                message = t.message ?: "사진을 저장하지 못했습니다."
            } finally {
                developing = false
            }
        }
    }
}
