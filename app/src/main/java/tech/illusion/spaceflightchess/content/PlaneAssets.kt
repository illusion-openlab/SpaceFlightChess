package tech.illusion.spaceflightchess.content

import tech.illusion.spaceflightchess.game.Team

/**
 * 四个飞机 `.usdz` 资产的固有属性。
 *
 * 放在这里而不是 [PlanePieceRenderer] 里，是因为机库窗口（[HangarWindow]）是第二个消费方：
 * 它同样需要按队拿到文件名，也同样需要修正机头朝向。这些是资产本身的属性，不是某个渲染器的
 * 内部细节。
 */
internal fun assetFileFor(team: Team): String = when (team) {
    Team.RED -> "red_plane.usdz"
    Team.YELLOW -> "yellow_plane.usdz"
    Team.BLUE -> "blue_plane.usdz"
    Team.GREEN -> "green_plane.usdz"
}

/**
 * Each asset's own nose direction, expressed as the yaw that brings it to heading 0 (+Z).
 *
 * Measured, not guessed: the four `.usdz` payloads were converted to ASCII USD and read. All four
 * stages are Y-up at `metersPerUnit = 0.01`. Red's propeller joints sit at z=+11.71 with the tail
 * joint at z=−3.86, and green's `Prop` mesh at z=+4.4 against its `BackWing` at z=−5.7 — both noses
 * on **+Z**. Blue's `Propeller_M_Plane_0` sits at x=−70.4 against `BackFlap` at x=+142.0, and
 * yellow's trail mesh likewise runs along −X — both noses on **−X**, hence +90°.
 *
 * Same spirit as [DieRenderer]'s measured `VALUE_TO_ROTATION` table: per-asset constants belong in
 * one labelled place, with the measurement recorded next to them.
 */
internal val MODEL_YAW_OFFSET_DEG: Map<Team, Float> = mapOf(
    Team.RED to 0f,
    Team.GREEN to 0f,
    Team.BLUE to 90f,
    Team.YELLOW to 90f,
)
