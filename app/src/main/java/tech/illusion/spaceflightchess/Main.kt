package tech.illusion.spaceflightchess

import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.foundation.dsl.DefaultWindowContainer
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import com.pico.spatial.ui.foundation.dsl.Stage
import tech.illusion.spaceflightchess.content.BOARD_STAGE_ID
import tech.illusion.spaceflightchess.content.BoardStage
import tech.illusion.spaceflightchess.content.HangarWindow

// 默认空间容器是平面机库窗口（属性见 AndroidManifest.xml —— DefaultWindowContainer 这个 DSL
// 函数没有任何属性参数，属性只能写 manifest）。棋盘是一个非默认 Stage：只在这里声明 id 和内容，
// style 在 HangarWindow 调 openStage 时给（Stage() 没有 style 参数）。
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultWindowContainer {
            PicoTheme { HangarWindow() }
        }
        Stage(id = BOARD_STAGE_ID) {
            PicoTheme { BoardStage() }
        }
    }
