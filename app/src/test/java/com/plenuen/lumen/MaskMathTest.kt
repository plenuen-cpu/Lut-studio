package com.plenuen.lumen

import com.plenuen.lumen.image.MaskMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 영역 마스크 수학: 페더링, 하늘 판별, 폴백 피사체 추정, 텍스처 패킹.
 *
 * 실기기 GPU·모델을 이 환경에서 돌릴 수 없으므로, 셰이더에 들어가기 전 순수 계산은 합성
 * 사진으로 직접 고정한다.
 */
class MaskMathTest {

    private fun frame(width: Int, height: Int, colorAt: (Int, Int) -> Int): IntArray =
        IntArray(width * height) { index -> colorAt(index % width, index / width) }

    private fun argb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun featherSpreadsAnEdgeWithoutChangingTheExtremes() {
        val width = 32
        val height = 8
        val mask = FloatArray(width * height) { index -> if (index % width < width / 2) 1f else 0f }
        val feathered = MaskMath.feather(mask, width, height, radius = 4)
        assertEquals(mask.size, feathered.size)
        assertTrue("왼쪽 안쪽은 그대로 1", feathered[4 * width + 2] > 0.9f)
        assertTrue("오른쪽 안쪽은 그대로 0", feathered[4 * width + 30] < 0.1f)
        val edge = feathered[4 * width + width / 2]
        assertTrue("경계는 중간값으로 번진다: $edge", edge in 0.15f..0.85f)
        feathered.forEach { assertTrue(it in 0f..1f) }
    }

    @Test
    fun featheringAnEmptyMaskKeepsItEmpty() {
        val mask = FloatArray(16 * 16)
        val feathered = MaskMath.feather(mask, 16, 16, radius = 5)
        assertTrue(feathered.all { it == 0f })
    }

    @Test
    fun blueTopHalfIsReadAsSkyAndTheBottomIsNot() {
        val width = 64
        val height = 48
        val pixels = frame(width, height) { _, y ->
            if (y < height / 2) argb(100, 150, 230) else argb(110, 105, 95)
        }
        val sky = MaskMath.skyMask(pixels, width, height)
        val top = sky[4 * width + width / 2]
        val bottom = sky[(height - 4) * width + width / 2]
        assertTrue("위쪽은 하늘: $top", top > 0.6f)
        assertTrue("아래쪽 땅은 하늘 아님: $bottom", bottom < 0.1f)
        assertTrue(MaskMath.coverage(sky) in 0.2f..0.6f)
    }

    @Test
    fun anIndoorSceneWithoutSkyProducesNoSkyMask() {
        val width = 48
        val height = 48
        val pixels = frame(width, height) { x, y ->
            if ((x / 4 + y / 4) % 2 == 0) argb(120, 110, 100) else argb(70, 65, 60)
        }
        val sky = MaskMath.skyMask(pixels, width, height)
        assertTrue("하늘이 없다: ${MaskMath.coverage(sky)}", MaskMath.coverage(sky) < 0.01f)
    }

    @Test
    fun skyBehindTheSubjectIsRemovedByTheSubjectMask() {
        val width = 64
        val height = 48
        val pixels = frame(width, height) { _, y ->
            if (y < height / 2) argb(100, 150, 230) else argb(110, 105, 95)
        }
        val subject = FloatArray(width * height) { index ->
            val x = index % width
            val y = index / width
            if (x in 24..40 && y in 4..28) 1f else 0f
        }
        val sky = MaskMath.skyMask(pixels, width, height, subject)
        val hole = sky[16 * width + 32]
        val beside = sky[16 * width + 4]
        assertTrue("피사체 자리는 하늘에서 빠진다: $hole", hole < 0.2f)
        assertTrue("피사체 옆은 그대로 하늘: $beside", beside > 0.5f)
    }

    @Test
    fun flatPhotoHasAlmostNoSalientSubject() {
        val width = 40
        val height = 40
        val pixels = frame(width, height) { _, _ -> argb(120, 120, 120) }
        val subject = MaskMath.heuristicSubject(pixels, width, height)
        assertTrue("단색 사진: ${MaskMath.coverage(subject)}", MaskMath.coverage(subject) < 0.08f)
    }

    @Test
    fun aColourfulCentreBecomesTheFallbackSubject() {
        val width = 60
        val height = 60
        val pixels = frame(width, height) { x, y ->
            val inCentre = x in 20..40 && y in 16..44
            if (inCentre) argb(200, 60, 50) else argb(160, 160, 158)
        }
        val subject = MaskMath.heuristicSubject(pixels, width, height)
        val centre = subject[30 * width + 30]
        val corner = subject[2 * width + 2]
        assertTrue("가운데가 피사체: $centre", centre > 0.4f)
        assertTrue("모서리는 배경: $corner", corner < 0.2f)
        assertTrue("가운데가 더 높다", centre > corner)
    }

    @Test
    fun packingPutsSubjectInRedAndSkyInGreen() {
        val width = 4
        val height = 2
        val subject = floatArrayOf(1f, 0.5f, 0f, 0f, 0.25f, 0f, 0f, 0f)
        val sky = floatArrayOf(0f, 0f, 0.75f, 0f, 0f, 1f, 0f, 0f)
        val packed = MaskMath.pack(subject, sky, width, height)
        val first = packed[0]
        assertEquals(255, (first shr 16) and 0xFF)
        assertEquals(0, (first shr 8) and 0xFF)
        assertEquals(191, (packed[2] shr 8) and 0xFF)
        assertEquals(255, (packed[5] shr 8) and 0xFF)
        assertEquals("B는 쓰지 않는다", 0, packed[5] and 0xFF)
        assertEquals(255, (packed[5] shr 24) and 0xFF)
    }

    @Test
    fun coverageAndFractionHelpersAgree() {
        val mask = floatArrayOf(1f, 1f, 0f, 0f)
        assertEquals(0.5f, MaskMath.coverage(mask), 1e-4f)
        assertEquals(0.5f, MaskMath.fractionAbove(mask), 1e-4f)
        assertEquals(0f, MaskMath.coverage(FloatArray(0)), 1e-4f)
    }
}
