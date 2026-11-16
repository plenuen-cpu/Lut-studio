package com.plenuen.lumen

import com.plenuen.lumen.core.EditRecipe
import com.plenuen.lumen.core.Param
import com.plenuen.lumen.llm.LlmRecipeDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Android는 `Regex`를 ICU로 컴파일한다.
 *
 * 데스크톱 JVM은 캐릭터 클래스 밖에 홀로 있는 `}`를 리터럴로 받아 주지만, ICU는 문법 오류
 * (`U_REGEX_RULE_SYNTAX`)로 본다. 그래서 JVM 단위 테스트를 전부 통과한 정규식 하나
 * (`LlmRecipeDecoder`의 `,\s*}`)가 폰에서만 `PatternSyntaxException`을 던져 v2.2.2가 실패했다.
 * 이 테스트는 앱 소스의 모든 `Regex(...)`/`toRegex()` 패턴을 그 규칙으로 검사한다.
 */
class IcuRegexAuditTest {

    @Test
    fun everyPatternInTheAppIsIcuSafe() {
        val roots = listOf(File("src/main/java"), File("app/src/main/java"))
        val root = roots.firstOrNull { it.isDirectory }
            ?: error("소스 폴더를 찾지 못했습니다: ${roots.map { it.absolutePath }}")

        val sources = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("Kotlin 소스가 없습니다", sources.size > 10)

        val problems = mutableListOf<String>()
        var checked = 0
        for (file in sources) {
            for (pattern in patternsIn(file.readText())) {
                checked++
                val problem = icuProblem(pattern)
                if (problem != null) problems += "${file.name}: <$pattern> — $problem"
                // ICU보다 먼저, JVM 파서로도 최소한의 문법 검사를 한다.
                val jvm = runCatching { java.util.regex.Pattern.compile(pattern) }.exceptionOrNull()
                if (jvm != null) problems += "${file.name}: <$pattern> — JVM: ${jvm.message}"
            }
        }
        assertTrue("Regex( 호출을 ${checked}개만 찾았습니다 — 소스 파싱이 빗나갔습니다", checked >= 4)
        assertTrue("ICU에서 터질 정규식이 있습니다:\n" + problems.joinToString("\n"), problems.isEmpty())
    }


    @Test
    fun theCheckerRejectsThePatternThatBrokeV222() {
        // v2.2.2 `LlmRecipeDecoder` 228행: 캐릭터 클래스 밖에 그대로 쓴 '}'. JVM은 통과, ICU는 오류.
        assertNotNull(icuProblem(",\\s*}"))
        assertNotNull(icuProblem("{"))
        // 이번에 문자열 연산으로 바꾼 패턴들과 남은 정규식들은 모두 통과해야 한다.
        assertNull(icuProblem(",\\s*([}\\]])"))
        assertNull(icuProblem("'([^'\\\\]*)'\\s*:"))
        assertNull(icuProblem("([{,]\\s*)([A-Za-z_가-힣][A-Za-z0-9_가-힣]*)\\s*:"))
        assertNull(icuProblem(":\\s*'([^']*)'"))
        assertNull(icuProblem("<\\|[^<>|]{1,24}\\|>"))
        assertNull(icuProblem("([0-9]+(?:\\.[0-9]+)?)\\s*(?:도|°)"))
        assertNull(icuProblem("```[a-zA-Z]*"))
    }

    /** `Regex("...")` 호출에서 문자열 인자를 이어 붙여 실제 패턴을 만든다. */
    private fun patternsIn(source: String): List<String> {
        val out = mutableListOf<String>()
        var from = 0
        while (true) {
            val at = source.indexOf("Regex(", from)
            if (at < 0) return out
            val open = at + "Regex".length
            val close = matchingParen(source, open)
            if (close < 0) {
                from = open
                continue
            }
            joinedLiterals(source.substring(open + 1, close))?.let { out += it }
            from = close + 1
        }
    }

    private fun matchingParen(text: String, open: Int): Int {
        var depth = 0
        var i = open
        var inString = false
        while (i < text.length) {
            val ch = text[i]
            if (inString) {
                when {
                    ch == '\\' -> i++
                    ch == '"' -> inString = false
                }
                i++
                continue
            }
            when (ch) {
                '"' -> inString = true
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return -1
    }

    /** 호출 인자에 있는 문자열 리터럴(일반·여러 줄)을 모두 이어 붙인다(없으면 null). */
    private fun joinedLiterals(args: String): String? {
        val text = StringBuilder()
        var found = false
        var i = 0
        while (i < args.length) {
            if (args.startsWith("\"\"\"", i)) {
                val end = args.indexOf("\"\"\"", i + 3)
                if (end < 0) return null
                found = true
                text.append(args, i + 3, end)
                i = end + 3
                continue
            }
            if (args[i] != '"') {
                i++
                continue
            }
            found = true
            i++
            while (i < args.length && args[i] != '"') {
                val c = args[i]
                if (c != '\\') {
                    text.append(c)
                    i++
                    continue
                }
                i++
                if (i >= args.length) break
                when (val esc = args[i]) {
                    'n' -> text.append('\n')
                    't' -> text.append('\t')
                    'r' -> text.append('\r')
                    '\\' -> text.append('\\')
                    '"' -> text.append('"')
                    '\'' -> text.append('\'')
                    '$' -> text.append('$')
                    'u' -> {
                        val hex = args.substring(i + 1, minOf(i + 5, args.length))
                        text.append(hex.toInt(16).toChar())
                        i += 4
                    }
                    else -> text.append(esc)
                }
                i++
            }
            i++ // 닫는 따옴표
        }
        return if (found) text.toString() else null
    }

    /**
     * ICU 문법으로 본 문제점. 이스케이프되지 않은 `{`/`}`(캐릭터 클래스 밖), 닫히지 않은 그룹·클래스.
     *
     * ICU는 `{`가 유효한 반복자가 아니면 오류다(`regexcst.txt`의 `term` 상태 → `doRuleError`).
     */
    private fun icuProblem(pattern: String): String? {
        var i = 0
        var inClass = false
        var groups = 0
        while (i < pattern.length) {
            val ch = pattern[i]
            if (ch == '\\') {
                i += 2
                continue
            }
            if (inClass) {
                if (ch == ']') inClass = false
                i++
                continue
            }
            when (ch) {
                '[' -> inClass = true
                '(' -> groups++
                ')' -> {
                    groups--
                    if (groups < 0) return "'(' 없이 닫는 ')' (index $i)"
                }
                '{' -> {
                    val end = quantifierEnd(pattern, i)
                    if (end < 0) return "이스케이프 안 된 '{' (index $i)"
                    i = end + 1
                    continue
                }
                '}' -> return "이스케이프 안 된 '}' (index $i)"
            }
            i++
        }
        if (inClass) return "닫히지 않은 문자 클래스"
        if (groups != 0) return "닫히지 않은 '(' $groups 개"
        return null
    }

    /** `{n}`, `{n,}`, `{n,m}` 이면 닫는 `}` 위치, 아니면 -1. */
    private fun quantifierEnd(pattern: String, open: Int): Int {
        var i = open + 1
        var digits = 0
        while (i < pattern.length && pattern[i].isDigit()) {
            i++
            digits++
        }
        if (digits == 0) return -1
        if (i < pattern.length && pattern[i] == ',') {
            i++
            while (i < pattern.length && pattern[i].isDigit()) i++
        }
        return if (i < pattern.length && pattern[i] == '}') i else -1
    }
}

/**
 * 작은 모델이 실제로 내놓는 지저분한 답들. 어떤 모양이 와도 예외 없이 읽거나 조용히 포기해야 한다.
 *
 * v2.2.2에서 사용자가 본 `PatternSyntaxException`은 이 경로에서 나왔다.
 */
class MessyModelAnswerTest {

    @Test
    fun everyShapeOfAnswerIsReadWithoutThrowing() {
        val answers = listOf(
            // 깔끔한 JSON
            """{"label":"차분하게","summary":"채도를 낮췄어요","adjust":{"saturation":-0.3,"contrast":-0.2}}""",
            // 마크다운 코드블록
            "```json\n{\"label\":\"필름\",\"adjust\":{\"grain\":0.5,\"fade\":0.3}}\n```",
            // 앞뒤 잡설 + 코드블록
            "좋습니다! 아래처럼 적용할게요.\n```\n{\"adjust\":{\"saturation\":0.2,\"contrast\":0.1}}\n```\n필요하면 말씀해 주세요.",
            // 잘린 답(닫는 중괄호 없음)
            "{\"label\":\"차분하게\",\"adjust\":{\"saturation\":-0.3,\"contrast\":-0.1",
            // 쉼표가 남은 답
            "{\"label\":\"차분하게\",\"adjust\":{\"saturation\":-0.3,\"contrast\":-0.1,},}",
            // 작은따옴표 + 따옴표 없는 키
            "```json\n{adjust:{'exposure':0.3, temperature:0.2, 'highlights':-0.15,},}\n```",
            // 문자열 안의 중괄호·쉼표·아포스트로피
            "{\"label\":\"빈티지 {필름} 톤, 따뜻하게\",\"adjust\":{\"grain\":0.4,\"fade\":0.2}}",
            "{\"label\":\"it's warm\",\"adjust\":{\"temperature\":0.3,\"saturation\":0.1}}",
            // 문자열이 잘린 답
            "{\"label\":\"차분하",
            // 중괄호만 있는 답
            "{",
            "{}",
            // JSON이 아닌 답
            "죄송합니다만 잘 모르겠습니다.",
            "",
            // 채팅 템플릿 토큰이 새어 나온 답
            "<|im_start|>assistant\n{\"adjust\":{\"grain\":0.4,\"fade\":0.2}}<|im_end|>",
        )
        for (answer in answers) {
            val decoded = runCatching { LlmRecipeDecoder.decode(answer) }
            assertTrue("예외가 나면 안 됩니다: <$answer> → ${decoded.exceptionOrNull()}", decoded.isSuccess)
        }
    }

    @Test
    fun labelWithBracesAndCommasSurvives() {
        val decoded = LlmRecipeDecoder.decode(
            "{\"label\":\"빈티지 {필름}, 차분하게\",\"adjust\":{\"grain\":0.4,\"fade\":0.2,}}",
        )
        assertNotNull(decoded)
        assertEquals("빈티지 {필름}, 차분하게", decoded!!.label)
        val recipe = EditRecipe.NEUTRAL.apply(decoded.delta)
        assertTrue(recipe[Param.GRAIN] > 0f)
    }

    @Test
    fun trailingCommasAreDroppedButCommasInsideStringsAreKept() {
        val tidy = LlmRecipeDecoder.stripTrailingCommas(
            "{\"label\":\"채도, 대비\",\"adjust\":{\"saturation\":-0.3, \"contrast\":-0.2 ,}}",
        )
        assertEquals("{\"label\":\"채도, 대비\",\"adjust\":{\"saturation\":-0.3, \"contrast\":-0.2 }}", tidy)
        assertEquals("[1,2]", LlmRecipeDecoder.stripTrailingCommas("[1,2,]"))
    }
}
