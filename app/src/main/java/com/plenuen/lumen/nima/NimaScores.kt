package com.plenuen.lumen.nima

/**
 * Input/output arithmetic for the on-device NIMA model.
 *
 * The model is a MobileNet trained on AVA: it takes 224×224 RGB with each channel mapped to
 * `[-1, 1]` and returns a softmax over ten buckets, one for each score 1…10. The number shown to the
 * user is the weighted mean of that distribution — the same number the FrameCritic app and its web
 * version show, so a score means the same thing in both apps.
 *
 * Nothing here touches Android, so the arithmetic can be unit tested on the JVM.
 */
object NimaScores {

    const val INPUT_SIZE = 224
    const val BUCKETS = 10
    const val CHANNELS = 3

    /** Number of floats the model expects: 1 × 224 × 224 × 3. */
    const val INPUT_FLOATS = INPUT_SIZE * INPUT_SIZE * CHANNELS

    /**
     * 224×224로 줄이고 `[-1, 1]`로 정규화한 NHWC 입력을 만든다.
     *
     * 크기가 다르면 이중선형(bilinear)으로 줄인다. 프리뷰용 900px 사본이든 224px 렌더 결과든
     * 같은 함수를 지나므로, 후보 비교와 화면 점수가 같은 자(尺)로 재진다.
     */
    fun toInput(argb: IntArray, width: Int, height: Int): FloatArray {
        val out = FloatArray(INPUT_FLOATS)
        if (width <= 0 || height <= 0 || argb.isEmpty()) return out
        val scaleX = width.toFloat() / INPUT_SIZE
        val scaleY = height.toFloat() / INPUT_SIZE
        var index = 0
        for (y in 0 until INPUT_SIZE) {
            val sy = ((y + 0.5f) * scaleY - 0.5f).coerceIn(0f, height - 1f)
            val y0 = sy.toInt()
            val y1 = (y0 + 1).coerceAtMost(height - 1)
            val fy = sy - y0
            for (x in 0 until INPUT_SIZE) {
                val sx = ((x + 0.5f) * scaleX - 0.5f).coerceIn(0f, width - 1f)
                val x0 = sx.toInt()
                val x1 = (x0 + 1).coerceAtMost(width - 1)
                val fx = sx - x0
                val top = blend(argb[y0 * width + x0], argb[y0 * width + x1], fx)
                val bottom = blend(argb[y1 * width + x0], argb[y1 * width + x1], fx)
                val pixel = blend(top, bottom, fy)
                out[index++] = (((pixel shr 16) and 0xFF) / 127.5f) - 1f
                out[index++] = (((pixel shr 8) and 0xFF) / 127.5f) - 1f
                out[index++] = ((pixel and 0xFF) / 127.5f) - 1f
            }
        }
        return out
    }

    /** 가중 평균 Σ i·pᵢ (i = 1…10). 분포가 비어 있으면 null. */
    fun expectedScore(probabilities: FloatArray): Float? {
        if (probabilities.size < BUCKETS) return null
        var sum = 0f
        var weighted = 0f
        for (i in 0 until BUCKETS) {
            val p = probabilities[i]
            if (!p.isFinite() || p < 0f) continue
            sum += p
            weighted += (i + 1) * p
        }
        if (sum <= 1e-6f) return null
        val mean = weighted / sum
        return if (mean.isFinite()) mean.coerceIn(1f, 10f) else null
    }

    /** 분포의 표준편차 — 모델이 얼마나 확신하는지. */
    fun deviation(probabilities: FloatArray, mean: Float): Float {
        if (probabilities.size < BUCKETS) return 0f
        var sum = 0f
        var variance = 0f
        for (i in 0 until BUCKETS) {
            val p = probabilities[i]
            if (!p.isFinite() || p < 0f) continue
            sum += p
        }
        if (sum <= 1e-6f) return 0f
        for (i in 0 until BUCKETS) {
            val p = probabilities[i] / sum
            val d = (i + 1) - mean
            variance += d * d * p
        }
        return kotlin.math.sqrt(variance.coerceAtLeast(0f))
    }

    /** 출력 텐서가 NaN이거나 합이 1이 아니어도 10구간 확률분포로 정리한다. */
    fun normalise(values: FloatArray): FloatArray {
        val clean = FloatArray(BUCKETS)
        var sum = 0f
        for (i in 0 until BUCKETS) {
            val value = values.getOrNull(i) ?: 0f
            if (value.isFinite() && value > 0f) {
                clean[i] = value
                sum += value
            }
        }
        if (sum <= 0f) {
            clean.fill(1f / BUCKETS)
            return clean
        }
        for (i in 0 until BUCKETS) clean[i] /= sum
        return clean
    }

    /** 한 자리 소수 표기: 4.85 → "4.85", 5.0 → "5.00". */
    fun format(score: Float?): String =
        if (score == null) "–" else String.format(java.util.Locale.US, "%.2f", score)

    private fun blend(a: Int, b: Int, t: Float): Int {
        if (t <= 0f) return a
        if (t >= 1f) return b
        val ar = (a shr 16) and 0xFF
        val ag = (a shr 8) and 0xFF
        val ab = a and 0xFF
        val br = (b shr 16) and 0xFF
        val bg = (b shr 8) and 0xFF
        val bb = b and 0xFF
        val r = (ar + (br - ar) * t).toInt().coerceIn(0, 255)
        val g = (ag + (bg - ag) * t).toInt().coerceIn(0, 255)
        val bl = (ab + (bb - ab) * t).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }
}
