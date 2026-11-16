package com.plenuen.lumen

import org.junit.Assert.assertFalse
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
