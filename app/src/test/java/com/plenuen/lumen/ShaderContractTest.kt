package com.plenuen.lumen

import com.plenuen.lumen.core.Param
import com.plenuen.lumen.gl.PhotoProgram
import com.plenuen.lumen.gl.ShaderSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The shader is the product: preview and export share it, and every new parameter has to reach it.
 *
 * A uniform that is declared in GLSL but never bound silently reads zero, and a binding whose uniform
 * does not exist silently does nothing — both look like "the slider stopped working" to the user.
 * These checks keep the Kotlin side ([PhotoProgram]) and the GLSL side in step, then hand the source
 * to `glslangValidator` when it is installed, because a typo in the fragment shader is not something
 * unit tests can catch any other way.
 */
class ShaderContractTest {

    private val fragment: String = ShaderSource.FRAGMENT
    private val vertex: String = ShaderSource.VERTEX

    @Test
    fun everyDeclaredUniformIsBoundAndEveryBindingIsDeclared() {
        val declared = Regex("""uniform\s+\w+\s+(\w+)(\[0])?""")
            .findAll(fragment)
            .map { it.groupValues[1] }
            .toSet()
        // 배열 유니폼은 GLSL에서 이름으로, 바인딩에서 [0]으로 적는다(uBandHSL ↔ uBandHSL[0]).
        val bound = PhotoProgram.UNIFORM_NAMES.map { it.substringBefore('[') }.toSet()

        val declaredButNotBound = declared - bound
        val boundButNotDeclared = bound - declared
        assertTrue("GLSL에만 있는 uniform: $declaredButNotBound", declaredButNotBound.isEmpty())
        assertTrue("Kotlin에만 있는 uniform: $boundButNotDeclared", boundButNotDeclared.isEmpty())
    }

    @Test
    fun everyRegionAndToneParameterHasAUniform() {
        val expected = mapOf(
            Param.SUBJECT_CONTRAST to "uSubContrast",
            Param.SUBJECT_STRUCTURE to "uSubStructure",
            Param.SUBJECT_SKIN_PROTECT to "uSubSkinProtect",
            Param.BACKGROUND_HIGHLIGHTS to "uBkgHighlights",
            Param.SKY_DETAIL to "uSkyDetail",
            Param.TONE_SHADOW_CURVE to "uToneShadow",
            Param.TONE_MID_CURVE to "uToneMid",
            Param.TONE_HIGH_CURVE to "uToneHigh",
        )
        for ((param, uniform) in expected) {
            assertTrue("$uniform 선언", fragment.contains("uniform float $uniform"))
            assertTrue("$uniform 바인딩", PhotoProgram.UNIFORM_NAMES.contains(uniform))
            assertTrue("${param.label} 기본값은 중립", param.default == 0f || param == Param.SUBJECT_SKIN_PROTECT)
        }
    }

    @Test
    fun theShaderIsGles3AndBalanced() {
        assertTrue("GLSL ES 3.0", fragment.trimStart().startsWith("#version 300 es"))
        assertTrue("정점 셰이더도 ES 3.0", vertex.trimStart().startsWith("#version 300 es"))
        var depth = 0
        fragment.forEach { ch ->
            if (ch == '{') depth++
            if (ch == '}') depth--
            assertTrue("중괄호가 음수", depth >= 0)
        }
        assertEquals("중괄호 짝", 0, depth)
        assertTrue("main이 있어야 한다", fragment.contains("void main()"))
    }

    @Test
    fun theRegionBlockUsesEdgeAwareBlending() {
        // v2.3: 마스크 경계의 기울기를 보고 보정 강도를 줄이는 코드가 실제로 있어야 한다.
        assertTrue("마스크 이웃 탭", fragment.contains("vec2 mN = texture(uMask"))
        assertTrue("경계 감쇠", fragment.contains("edgeDamp"))
        assertTrue("피부 보호 가중치", fragment.contains("skinWeight(c)"))
        assertTrue("배경 하이라이트 억제", fragment.contains("uBkgHighlights"))
        assertTrue("하늘 디테일", fragment.contains("uSkyDetail"))
    }

    @Test
    fun glslangAcceptsTheShaderWhenItIsInstalled() {
        val candidates = listOfNotNull(
            System.getenv("GLSLANG_VALIDATOR"),
            "/workspace/tmp/glsl/bin/glslang",
            "tools/glslang/bin/glslang",
        ).map { File(it) }.filter { it.canExecute() }
        assumeTrue("glslangValidator가 없어 실제 컴파일 검증은 건너뜁니다", candidates.isNotEmpty())
        val validator = candidates.first()

        val dir = File("build/shader-check").apply { mkdirs() }
        val vertexFile = File(dir, "lumen.vert").apply { writeText(vertex) }
        val fragmentFile = File(dir, "lumen.frag").apply { writeText(fragment) }

        val vertexRun = run(validator, vertexFile)
        assertTrue("정점 셰이더 컴파일 실패:\n${vertexRun.second}", vertexRun.first == 0)
        val fragmentRun = run(validator, fragmentFile)
        assertTrue("프래그먼트 셰이더 컴파일 실패:\n${fragmentRun.second}", fragmentRun.first == 0)
    }

    private fun run(validator: File, file: File): Pair<Int, String> {
        val process = ProcessBuilder(validator.absolutePath, "-S", if (file.extension == "frag") "frag" else "vert", file.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(30, TimeUnit.SECONDS)
        return process.exitValue() to output
    }
}
