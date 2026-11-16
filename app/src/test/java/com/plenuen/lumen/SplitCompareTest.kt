package com.plenuen.lumen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 세로 비교선(v2.2.5)을 고정한다.
 *
 * 사용자 요청: **"비교 슬라이드는 이미지에 세로로 그어져야 할 것 같아."** 이 테스트는
 *
 * ① 셰이더가 `vUv.x`를 `uSplitX`와 비교해 오른쪽만 보정 결과로 두는지,
 * ② 프로그램·`RenderSpec`·뷰모델·오버레이가 같은 축(x, 사진 폭 비율)을 쓰는지,
 * ③ 오버레이가 좌우 드래그(`detectHorizontalDragGestures`)인지,
 * ④ 가로 비교 시절의 `uSplitY`/`splitY` 잔재가 남지 않았는지
 *
 * 를 확인한다. 셰이더와 오버레이가 다른 축을 쓰면 선과 실제 경계가 어긋나므로, 한쪽만 바꾸는
 * 회귀를 여기서 막는다.
 */
class SplitCompareTest {

    private fun source(rel: String): String {
        val roots = listOf(File(rel), File("app/$rel"))
        return roots.firstOrNull { it.isFile }?.readText()
            ?: error("$rel 파일을 찾지 못했습니다: ${roots.map { it.absolutePath }}")
    }

    private fun shader(): String = source("src/main/java/com/plenuen/lumen/gl/ShaderSource.kt")

    private fun program(): String = source("src/main/java/com/plenuen/lumen/gl/PhotoProgram.kt")

    private fun screen(): String = source("src/main/java/com/plenuen/lumen/ui/EditorScreen.kt")

    private fun viewModel(): String = source("src/main/java/com/plenuen/lumen/ui/EditorViewModel.kt")

    @Test
    fun shaderSplitsTheImageOnTheVerticalAxis() {
        val src = shader()
        assertTrue("세로 분할 유니폼 uSplitX가 없습니다", src.contains("uniform float uSplitX"))
        assertFalse("가로 비교 유니폼 uSplitY가 남아 있습니다", src.contains("uSplitY"))

        val line = src.lines().firstOrNull { it.contains("step(uSplitX") }
        assertTrue("분할 계산 줄을 찾지 못했습니다", line != null)
        // vUv.x < uSplitX → 원본, vUv.x >= uSplitX → 보정 결과.
        assertTrue(
            "분할 비교가 x축이 아닙니다: $line",
            line!!.contains("step(uSplitX, vUv.x)"),
        )
        assertTrue("분할이 original과 c를 섞지 않습니다: $line", line.contains("mix(original, c"))
    }

    @Test
    fun programBindsSplitXUnderTheSameName() {
        val src = program()
        assertTrue("uSplitX 유니폼 이름이 등록되지 않았습니다", src.contains("\"uSplitX\""))
        assertTrue("uSplitX를 바인딩하지 않습니다", src.contains("loc(\"uSplitX\"), spec.splitX"))
        assertTrue("RenderSpec에 splitX가 없습니다", src.contains("val splitX: Float = 0.5f"))
        assertFalse("RenderSpec에 splitY가 남아 있습니다", src.contains("splitY"))
    }

    @Test
    fun previewOverlayDragsHorizontallyAcrossThePhotoWidth() {
        val src = screen()
        assertTrue("세로선 오버레이가 좌우 드래그를 쓰지 않습니다", src.contains("detectHorizontalDragGestures"))
        assertFalse("가로선 시절의 세로 드래그가 남아 있습니다", src.contains("detectVerticalDragGestures"))
        // 드래그 양은 사진 폭에 대한 비율 — 셰이더의 vUv.x와 같은 좌표계.
        assertTrue("드래그를 사진 폭으로 나누지 않습니다", src.contains("dragAmount / rectWidth"))
        // 뷰모델의 splitX를 그대로 그린다.
        assertTrue(src.contains("splitX = state.splitX"))
        // 좌/우 상단 배지.
        assertTrue("원본 배지가 없습니다", src.contains("SplitBadge(\"원본\""))
        assertTrue("보정 배지가 없습니다", src.contains("SplitBadge(\"보정\""))
        assertTrue("세로선 핸들 표시가 없습니다", src.contains("↔"))
    }

    @Test
    fun viewModelKeepsTheSplitInsideTheFrame() {
        val src = viewModel()
        assertTrue("UiState에 splitX가 없습니다", src.contains("val splitX: Float = 0.5f"))
        assertFalse("UiState에 splitY가 남아 있습니다", src.contains("splitY"))
        // 사진을 새로 열면 가운데부터, 드래그는 0.03~0.97로 묶인다.
        assertTrue(src.contains("splitX = 0.5f,"))
        assertTrue(src.contains("it.copy(splitX = (it.splitX + delta).coerceIn(0.03f, 0.97f))"))
    }

    @Test
    fun exportsNeverCarryTheSplit() {
        val exporter = source("src/main/java/com/plenuen/lumen/gl/OffscreenExporter.kt")
        assertTrue("내보내기는 분할선을 끄고 그려야 합니다", exporter.contains("splitEnabled = false"))
    }
}
