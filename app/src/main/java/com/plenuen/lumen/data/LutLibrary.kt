package com.plenuen.lumen.data

import android.content.Context
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.Inflater

/**
 * Loads the packaged preset catalog and 3D LUT data.
 *
 * `assets/luts/lut_residuals.bin` is the zlib-compressed Lorenzo residual blob of presets 0..56;
 * each LUT is decoded the first time it is selected and kept afterwards (14739 bytes each).
 */
class LutLibrary(context: Context) {

    val presets: List<LutPreset>
    private val residuals: ByteArray
    private val cache = ConcurrentHashMap<Int, ByteArray>()

    init {
        val assets = context.applicationContext.assets
        val catalogJson = assets.open(CATALOG).use { it.readBytes().toString(Charsets.UTF_8) }
        presets = PresetCatalog.parse(catalogJson)
        residuals = inflate(assets.open(RESIDUALS).use { it.readBytes() })
        val needed = presets.size * PresetCatalog.LUT_BYTES
        require(residuals.size >= needed) {
            "residual blob too small: ${residuals.size} < $needed"
        }
    }

    fun lutFor(preset: LutPreset): ByteArray = cache[preset.idx] ?: run {
        val decoded = LorenzoDecoder.decode(residuals, preset.idx)
        cache.putIfAbsent(preset.idx, decoded) ?: decoded
    }

    fun presetsFor(brand: String): List<LutPreset> =
        if (brand == PresetCatalog.BRAND_ALL) presets else presets.filter { it.brand == brand }

    private fun inflate(packed: ByteArray): ByteArray {
        val inflater = Inflater()
        inflater.setInput(packed)
        val out = ByteArrayOutputStream(packed.size * 5)
        val buffer = ByteArray(64 * 1024)
        try {
            while (!inflater.finished()) {
                val read = inflater.inflate(buffer)
                if (read == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                } else {
                    out.write(buffer, 0, read)
                }
            }
        } finally {
            inflater.end()
        }
        return out.toByteArray()
    }

    companion object {
        const val CATALOG = "luts/presets.json"
        const val RESIDUALS = "luts/lut_residuals.bin"
    }
}
