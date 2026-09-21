package tech.illusion.spaceflightchess.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.illusion.spaceflightchess.game.Team

class PlaneAssetsTest {

    @Test
    fun `每个阵营都有自己的模型文件，互不重复`() {
        val files = Team.entries.map { assetFileFor(it) }
        assertEquals(Team.entries.size, files.toSet().size)
        assertTrue(files.all { it.endsWith(".usdz") })
    }

    @Test
    fun `文件名是裸名，不带目录前缀也不带 scheme`() {
        Team.entries.forEach { team ->
            val file = assetFileFor(team)
            assertTrue("$team -> $file 不应含路径分隔符", !file.contains("/"))
            assertTrue("$team -> $file 不应含 scheme", !file.contains(":"))
        }
    }

    @Test
    fun `机头 yaw 偏移表覆盖全部四个阵营`() {
        assertEquals(Team.entries.toSet(), MODEL_YAW_OFFSET_DEG.keys)
    }

    @Test
    fun `实测值：红绿机头朝 +Z 无需偏移，蓝黄朝 -X 需要 90 度`() {
        // JUnit 4 的 assertEquals 对浮点必须带 delta——不带 delta 的那个重载已废弃，
        // Kotlin 下还会解析到装箱的 assertEquals(Object, Object)。
        assertEquals(0f, MODEL_YAW_OFFSET_DEG.getValue(Team.RED), 0f)
        assertEquals(0f, MODEL_YAW_OFFSET_DEG.getValue(Team.GREEN), 0f)
        assertEquals(90f, MODEL_YAW_OFFSET_DEG.getValue(Team.BLUE), 0f)
        assertEquals(90f, MODEL_YAW_OFFSET_DEG.getValue(Team.YELLOW), 0f)
    }
}
