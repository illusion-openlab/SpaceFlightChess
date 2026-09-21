package tech.illusion.spaceflightchess.content

import tech.illusion.spaceflightchess.game.Team

/**
 * 机库窗口（默认空间容器）的 id。
 *
 * **必须与 `AndroidManifest.xml` 里 `pico.spatial.windowcontainer.id` 的值逐字一致** ——
 * `minimizeWindowContainer(id)` / `restoreWindowContainer(id)` 就是按这个字符串定位窗口的，
 * 对不上不会报错，只会静默什么都不发生。
 */
const val HANGAR_WINDOW_ID = "SpaceFlightChessHangarWindow"

/**
 * 棋盘 Stage 的 id。非默认 Stage 只需在 `mainApp` 的 DSL 里 `Stage(id = …)` 声明，
 * 不必写进 manifest。
 */
const val BOARD_STAGE_ID = "SpaceFlightChessBoardStage"

/** `openStage` 的 Bundle 里承载玩家阵营的键，值是 [Team] 的 `name`。 */
const val TEAM_BUNDLE_KEY = "humanTeam"

/**
 * 把 [TEAM_BUNDLE_KEY] 取出的原始字符串解析成 [Team]，任何解析不出来的情况都退回 [Team.RED]。
 *
 * 收 `String?` 而不是 `Bundle?` 是刻意的：`Bundle` 是 Android 类，JVM 单测里构造不出来。调用方
 * 自己做 `bundle?.getString(TEAM_BUNDLE_KEY)`，解析逻辑留在可测的这一侧。
 *
 * 退回红方而不是抛异常：阵营解析失败只会让玩家坐错席位，而抛异常会让整个棋盘建不出来——
 * 后者严重得多，且在画面上表现为一片空白，无从判断原因。
 */
fun teamFromBundleValue(value: String?): Team =
    Team.entries.firstOrNull { it.name == value } ?: Team.RED
