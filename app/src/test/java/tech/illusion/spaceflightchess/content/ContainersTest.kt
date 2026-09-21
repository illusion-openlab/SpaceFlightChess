package tech.illusion.spaceflightchess.content

import org.junit.Assert.assertEquals
import org.junit.Test
import tech.illusion.spaceflightchess.game.Team

class ContainersTest {

    @Test
    fun `每个阵营名都能原样解析回来`() {
        Team.entries.forEach { team ->
            assertEquals(team, teamFromBundleValue(team.name))
        }
    }

    @Test
    fun `缺少 bundle 值时退回红方`() {
        assertEquals(Team.RED, teamFromBundleValue(null))
    }

    @Test
    fun `无法识别的值退回红方而不是抛异常`() {
        assertEquals(Team.RED, teamFromBundleValue(""))
        assertEquals(Team.RED, teamFromBundleValue("PURPLE"))
        assertEquals(Team.RED, teamFromBundleValue("red"))
    }

    @Test
    fun `窗口 id 与 stage id 不相同`() {
        assertEquals(false, HANGAR_WINDOW_ID == BOARD_STAGE_ID)
    }
}
