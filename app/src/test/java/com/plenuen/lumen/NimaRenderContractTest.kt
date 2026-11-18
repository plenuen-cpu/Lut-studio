package com.plenuen.lumen

import com.plenuen.lumen.engine.PixelSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The offscreen renderer the guided search scores candidates with.
 *
 * Two P1 review findings on PR #1 live here, both of the kind a JVM test cannot reach through EGL:
 *
 *  1. `renderPixels()` hands back the session's **reusable** buffer, and the baseline kept that
 *     reference. Every later candidate then overwrote the "original" frame, so pixel change read as
 *     zero and the baseline's clipping/skin drifted with the last render. [PixelSample] now copies,
 *     which is what [aSampleKeepsItsOwnPixelsWhenTheRenderBufferIsReused] pins down.
 *  2. `NimaRenderSession.render()` hardcoded `maskReady = false`, so subject/background/sky
 *     corrections were drawn un-masked during selection and only appeared in the preview and export.
 *     That wiring has no JVM entry point (it needs a GL context), so it is checked as a source
 *     contract, the same way [EditorLayoutTest] pins the layout it cannot render.
 */
class NimaRenderContractTest {

    private fun source(relative: String): String {
        val roots = listOf(File(relative), File("app/$relative"))
        return roots.firstOrNull { it.isFile }?.readText()
            ?: error("소스를 찾지 못했습니다: ${roots.map { it.absolutePath }}")
    }

    private fun bodyOf(src: String, signature: String): String {
        val start = src.indexOf(signature)
        assertTrue("$signature 를 찾지 못했습니다", start >= 0)
        val open = src.indexOf('{', start)
        var depth = 0
        for (index in open until src.length) {
            when (src[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(open, index + 1)
                }
            }
        }
        error("$signature 의 본문이 닫히지 않았습니다")
    }

    @Test
    fun aSampleKeepsItsOwnPixelsWhenTheRenderBufferIsReused() {
        val reusable = IntArray(4) { 0xFFA0A0A0.toInt() }
        val sample = PixelSample(reusable, 2, 2)
        val lumaBefore = sample.meanLuma

        // 다음 후보 렌더: 세션이 같은 배열을 제자리에서 덮어쓴다.
        for (index in reusable.indices) reusable[index] = 0xFF000000.toInt()

        assertEquals(
            "기준선 픽셀이 다음 후보 렌더에 덮어써지면 안 된다",
            0xFFA0A0A0.toInt(),
            sample.argb[0],
        )
        assertEquals("기준선 밝기도 그대로여야 한다", lumaBefore, sample.meanLuma, 1e-6f)
        assertEquals("덮어쓴 검은 픽셀이 섞이면 안 된다", 0, sample.argb.count { it == 0xFF000000.toInt() })
    }

    @Test
    fun theRenderSessionDrawsWithTheMaskItWasGiven() {
        val session = source("src/main/java/com/plenuen/lumen/nima/NimaRenderSession.kt")

        assertFalse(
            "오프스크린 렌더가 다시 maskReady=false로 고정되면 영역 보정이 평가에서 사라진다",
            session.contains("maskReady = false"),
        )
        assertTrue(
            "RenderSpec은 세션이 가진 마스크 상태를 그대로 넘겨야 한다",
            session.contains("maskReady = hasMask"),
        )
        assertTrue("마스크 업로드 상태를 보관해야 한다", session.contains("var hasMask"))

        // 마스크는 사진을 연 뒤에 도착할 수 있으므로, 열린 세션에 올리는 경로가 있어야 한다.
        val update = bodyOf(session, "fun updateMask(")
        assertTrue("새 마스크를 마스크 텍스처(textures[3])에 올려야 한다", update.contains("textures[3]"))
        assertTrue("마스크가 없으면 다시 꺼야 한다", update.contains("hasMask = false"))
        assertTrue("마스크가 있으면 켜야 한다", update.contains("hasMask = true"))
        assertTrue("열리지 않은 세션에는 올릴 수 없다", update.contains("framebuffer == 0"))
    }

    @Test
    fun theEvaluatorAndTheEditorPassTheMaskIntoTheOffscreenPass() {
        val evaluator = source("src/main/java/com/plenuen/lumen/nima/NimaEvaluator.kt")
        assertTrue("평가기가 렌더 세션에 열 때 마스크를 넘겨야 한다", evaluator.contains("open(bitmap, mask)"))
        val update = bodyOf(evaluator, "fun updateMask(")
        assertTrue("늦게 온 마스크를 세션에 넘겨야 한다", update.contains("updateMask(mask)"))
        assertTrue("세션이 닫혀 있으면 실패를 알려야 한다", update.contains("?: return false"))

        val editor = source("src/main/java/com/plenuen/lumen/ui/EditorViewModel.kt")
        val ensure = bodyOf(editor, "private suspend fun ensureEvaluator(")
        assertTrue("평가기를 재사용할 때 마스크가 바뀌었는지 봐야 한다", ensure.contains("existing.maskBitmap !== mask"))
        assertTrue("바뀐 마스크를 올려야 한다", ensure.contains("existing.updateMask(mask)"))
    }
}
