package com.plenuen.lumen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 편집 화면 하단 도구 줄의 배치를 고정한다.
 *
 * v2.2.0에서 `[✨ 자동]`이 붙은 뒤, 보정 도구 4개와 `[저장]`·`[다른 사진]`을 한 줄에 그대로 넣어
 * 360dp 폰에서 오른쪽 두 버튼이 화면 밖으로 밀려 사라졌다(v2.2.3 신고). v2.2.4는 앞쪽 도구만
 * 가로로 스크롤시키고 저장·새 사진은 오른쪽 고정 자리에 둔다. 이 테스트는 그 경계를 지킨다 —
 * 저장·새 사진이 다시 스크롤되는 쪽으로 들어가면 좁은 화면에서 또 사라진다.
 */
class EditorLayoutTest {

    private fun editorSource(): String {
        val roots = listOf(
            File("src/main/java/com/plenuen/lumen/ui/EditorScreen.kt"),
            File("app/src/main/java/com/plenuen/lumen/ui/EditorScreen.kt"),
        )
        return roots.firstOrNull { it.isFile }?.readText()
            ?: error("EditorScreen.kt를 찾지 못했습니다: ${roots.map { it.absolutePath }}")
    }

    @Test
    fun saveAndNewPhotoStayOutsideTheScrollableToolRow() {
        val src = editorSource()

        val bar = src.indexOf("// 하단 도구")
        assertTrue("하단 도구 줄을 찾지 못했습니다", bar >= 0)

        val scroller = src.indexOf("horizontalScroll(rememberScrollState())", bar)
        assertTrue("보정 도구 줄의 가로 스크롤이 사라졌습니다", scroller > bar)
        // 스크롤되는 쪽은 보정 도구만 담는다.
        val open = src.indexOf('{', scroller)
        assertTrue("스크롤 줄의 본문을 찾지 못했습니다", open > scroller)
        val close = matchingBrace(src, open)
        assertTrue("스크롤 줄의 닫는 괄호를 찾지 못했습니다", close > open)
        val scrollable = src.substring(open, close)

        listOf("✨ 자동", "자동 수평·수직", "필름 룩", "슬라이더").forEach { label ->
            assertTrue("보정 도구 <$label>이 스크롤 줄에서 빠졌습니다", scrollable.contains(label))
        }
        listOf("저장", "새 사진").forEach { label ->
            assertFalse(
                "저장·새 사진은 스크롤되는 쪽에 있으면 좁은 화면에서 다시 밀려납니다: <$label>",
                scrollable.contains(label),
            )
        }

        // 오른쪽 고정 자리: [새 사진] 다음에 [저장=내보내기].
        val fixed = src.substring(close)
        val newPhoto = fixed.indexOf("\"새 사진\"")
        assertTrue("고정 자리에 [새 사진] 버튼이 없습니다", newPhoto >= 0)
        val export = fixed.indexOf("viewModel::export")
        assertTrue("고정 자리에 [저장] 내보내기 버튼이 없습니다", export > newPhoto)
        assertTrue(
            "[저장] 버튼의 라벨이 사라졌습니다",
            fixed.indexOf("\"저장\"", export) > export,
        )
        // 새 사진 버튼은 사진을 다시 고르는 콜백을 그대로 부른다(버튼 선언 안쪽에 있다).
        assertTrue(
            "[새 사진]이 갤러리 선택으로 이어지지 않습니다",
            fixed.substring((newPhoto - 800).coerceAtLeast(0), newPhoto).contains("onPickPhoto"),
        )
    }

    @Test
    fun sliderPanelLeavesThePhotoRoom() {
        val src = editorSource()

        // v2.2.5: 고정 300dp 패널 → 최대 높이 상수(170~190dp).
        assertFalse(
            "슬라이더 패널이 다시 고정 300dp로 돌아갔습니다 — 사진이 그만큼 작아집니다",
            src.contains("Modifier.fillMaxWidth().height(300.dp)"),
        )
        val maxHeight = Regex("SLIDER_PANEL_MAX_HEIGHT = (\\d+)\\.dp").find(src)
        assertNotNull("슬라이더 패널의 최대 높이 상수가 사라졌습니다", maxHeight)
        val value = maxHeight!!.groupValues[1].toInt()
        assertTrue(
            "슬라이더 패널 최대 높이 ${value}dp — 190dp를 넘으면 사진 영역이 다시 좁아집니다",
            value in 150..190,
        )
        assertTrue(
            "패널이 최대 높이를 실제로 쓰지 않습니다",
            src.contains("heightIn(max = SLIDER_PANEL_MAX_HEIGHT)"),
        )

        // 한 줄 카테고리 칩으로 그룹을 고르고, 고른 그룹 하나만 펼친다.
        assertTrue("카테고리 칩 줄이 없습니다", src.contains("entry.chip"))
        assertTrue("그룹 칩이 ParamGroup 전체를 돌지 않습니다", src.contains("ParamGroup.entries[index]"))
        // 사진을 가리는 두 패널이 동시에 서지 않는다.
        assertTrue(
            "필름 룩과 슬라이더 패널을 동시에 열 수 있습니다 — 둘이 서면 사진이 절반으로 줄어듭니다",
            src.contains("if (showLuts) viewModel.closeSliders()"),
        )
    }

    @Test
    fun historyAndNoteCollapseWhileAPanelIsOpen() {
        val src = editorSource()

        assertTrue(
            "패널 열림 여부를 한 값으로 관리하지 않습니다",
            src.contains("val panelOpen = state.showSliders || showLuts"),
        )
        val collapse = src.indexOf("if (!panelOpen) {")
        assertTrue("히스토리 줄을 접는 조건이 사라졌습니다", collapse > 0)
        // 접히는 쪽은 히스토리 칩 줄이고, 한 줄 짜리 빠른 요청은 남는다.
        val collapsed = src.substring(collapse, src.indexOf("// 빠른 요청", collapse))
        assertTrue("히스토리 칩 줄이 접히지 않습니다", collapsed.contains("state.steps"))
        assertTrue("빠른 요청 줄이 사라졌습니다", src.contains("items(QUICK_PROMPTS)"))

        // 내레이션 카드는 한 줄 모드로 줄인다.
        assertTrue("보정 내역 카드가 한 줄로 접히지 않습니다", src.contains("dense = panelOpen"))
        assertTrue("한 줄 모드가 카드에 없습니다", src.contains("maxLines = if (dense) 1 else 3"))
    }

    /** [open] 위치의 `{`와 짝이 맞는 `}`의 위치. 문자열 안의 중괄호는 세지 않는다. */
    private fun matchingBrace(src: String, open: Int): Int {
        var depth = 0
        var i = open
        var inString = false
        while (i < src.length) {
            val c = src[i]
            when {
                inString && c == '\\' -> i++          // 이스케이프: 다음 글자는 건너뛴다
                c == '"' -> inString = !inString
                inString -> Unit
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return -1
    }
}
