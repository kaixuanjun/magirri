package com.magirri.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Lut3DTest {

    /** 官方规范里最小的 2x2x2 示例：红色变化最快。 */
    private val canonical = """
        LUT_3D_SIZE 2
        0.0 0.0 0.0
        1.0 0.0 0.0
        0.0 1.0 0.0
        1.0 1.0 0.0
        0.0 0.0 1.0
        1.0 0.0 1.0
        0.0 1.0 1.0
        1.0 1.0 1.0
    """.trimIndent()

    @Test
    fun parsesSizeWithoutSwallowingTheDigitInTheKeyword() {
        // LUT_3D_SIZE 里有个 "3"，只取关键字之后的数字
        val lut = Lut3D.parse(canonical)
        assertEquals(2, lut.size)
        assertEquals(2 * 2 * 2 * 3, lut.data.size)
    }

    @Test
    fun redVariesFastest() {
        val lut = Lut3D.parse(canonical)
        // 第 2 行是 (1,0,0) -> 索引 (b=0,g=0,r=1)
        val idx = ((0 * 2 + 0) * 2 + 1) * 3
        assertEquals(1f, lut.data[idx], 0f)
        assertEquals(0f, lut.data[idx + 1], 0f)
        assertEquals(0f, lut.data[idx + 2], 0f)
    }

    @Test
    fun honoursDomainMinMax() {
        val text = """
            LUT_3D_SIZE 2
            DOMAIN_MIN 0.1 0.1 0.1
            DOMAIN_MAX 0.6 0.6 0.6
            0 0 0
            1 0 0
            0 1 0
            1 1 0
            0 0 1
            1 0 1
            0 1 1
            1 1 1
        """.trimIndent()
        val lut = Lut3D.parse(text)
        // 输入 0.1 是域下限 -> 0；0.6 是上限 -> 1
        assertEquals(0f, lut.sampleAt(0.1f, 0.1f, 0.1f)[0], 1e-5f)
        assertEquals(1f, lut.sampleAt(0.6f, 0.6f, 0.6f)[2], 1e-5f)
        // 0.35 正好是中点
        assertEquals(0.5f, lut.sampleAt(0.35f, 0.35f, 0.35f)[1], 1e-4f)
    }

    @Test
    fun rejectsWrongDataCount() {
        try {
            Lut3D.parse("LUT_3D_SIZE 2\n0 0 0\n")
            throw AssertionError("应当抛出异常")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("数据量"))
        }
    }

    /** 真正的资产：单调、端点合理、尺寸正确。 */
    @Test
    fun bakedAssetsAreSane() {
        val dir = File("src/main/assets/luts")
        if (!dir.isDirectory) return // 单独跑测试时可能没有工作目录上下文
        val files = dir.listFiles { f -> f.name.endsWith(".cube") } ?: return
        assertTrue("内置 LUT 数量不对: ${files.size}", files.size >= 4)
        for (f in files) {
            val lut = Lut3D.parse(f.readText())
            assertEquals("${f.name} 尺寸", 33, lut.size)
            val g = lut.grayAxis(64)
            for (i in 1 until g.size) {
                assertTrue("${f.name} 灰轴不单调 @$i: ${g[i - 1]} -> ${g[i]}", g[i] >= g[i - 1] - 1e-4f)
            }
            assertTrue("${f.name} 黑位过高: ${g.first()}", g.first() < 0.05f)
            // LUT 里已经烘了「仿阿莱成品影调」：黑位抬到 ~0.03（不压死黑），
            // 白位到 ~0.87（不再是 ARRI 官方转换那种留余量的 0.74）。
            assertTrue("${f.name} 白位过低: ${g.last()}", g.last() > 0.7f)
            // 1/4 处落在深灰到中灰之间即可：v2 曲线在低码值更陡，v1 更平
            assertTrue("${f.name} 1/4 处异常: ${g[g.size / 4]}", g[g.size / 4] in 0.02f..0.85f)
            assertTrue("${f.name} 中灰异常: ${g[g.size / 2]}", g[g.size / 2] in 0.35f..0.85f)
        }
    }
}
