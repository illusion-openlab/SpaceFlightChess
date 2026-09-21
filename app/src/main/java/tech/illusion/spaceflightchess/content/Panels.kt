package tech.illusion.spaceflightchess.content

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import tech.illusion.spaceflightchess.game.Team

private val PanelShape = RoundedCornerShape(18.dp)

@Composable
internal fun GlassPanel(
    width: Int,
    height: Int,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .size(width.dp, height.dp)
            .clip(PanelShape)
            .backgroundMaterial(true, Material.Regular),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/** Fixed dot per team - the same colours as [PlanePieceRenderer]'s discs, expressed as a Compose [Color] since the 3D materials have no shared PicoTheme role to draw from either. */
internal fun swatchFor(team: Team): Color = when (team) {
    // design-style: fixed-figma-color - mirrors PlanePieceRenderer's Color4 disc colours
    Team.RED -> Color(0xFFE83333)
    Team.YELLOW -> Color(0xFFFACC24)
    Team.BLUE -> Color(0xFF3373F2)
    Team.GREEN -> Color(0xFF40B359)
}

internal fun labelFor(team: Team): String = when (team) {
    Team.RED -> "红方"
    Team.YELLOW -> "黄方"
    Team.BLUE -> "蓝方"
    Team.GREEN -> "绿方"
}

/**
 * Current turn and last roll. Rolling itself now happens by tapping the physical die
 * ([DieRenderer]) rather than a flat button — `isRollEnabled` only gates whether this panel's own
 * hint ("点击骰子" / "对手思考中…") tells the player it is their move to make.
 */
@Composable
fun GameHud(
    currentTeam: Team,
    isHumanTurn: Boolean,
    lastRoll: Int?,
    isRollEnabled: Boolean,
    aiThinking: Boolean,
) {
    GlassPanel(width = 360, height = 130) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(15.dp).clip(CircleShape).background(swatchFor(currentTeam)))
                Spacer(Modifier.size(10.dp))
                Text(
                    text = if (isHumanTurn) "轮到你 · ${labelFor(currentTeam)}" else "${labelFor(currentTeam)}回合",
                    color = PicoTheme.colorScheme.labelPrimary,
                    style = PicoTheme.typography.titleSmall,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = lastRoll?.let { "骰子：$it" } ?: "",
                    color = PicoTheme.colorScheme.labelSecondary,
                    style = PicoTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = when {
                        aiThinking -> "对手思考中…"
                        isRollEnabled -> "点击骰子摇一摇"
                        else -> ""
                    },
                    color = PicoTheme.colorScheme.labelSecondary,
                    style = PicoTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * Shown when the player presses the controller input `BoardStage`'s `OnBackPressedCallback`
 * intercepts — the named `Stage(id = BOARD_STAGE_ID)` has no window chrome to fall back on, so
 * without that callback the input silently killed the whole session via `Activity.finish()` (see
 * `BoardStage.findComponentActivity`). Same layout as [ResultPanel]; 取消 dismisses, 退出 calls
 * `BoardStage.returnToHangar()` — it returns to the hangar window, it does not exit the app.
 */
@Composable
fun ExitConfirmPanel(onCancel: () -> Unit, onExit: () -> Unit) {
    GlassPanel(width = 320, height = 200) {
        Column(
            modifier = Modifier.fillMaxSize().padding(vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "退出游戏？",
                color = PicoTheme.colorScheme.labelPrimary,
                style = PicoTheme.typography.headlineSmall,
            )
            Spacer(Modifier.size(10.dp))
            Text(
                text = "当前进度将不会保存",
                color = PicoTheme.colorScheme.labelSecondary,
                style = PicoTheme.typography.bodyMedium,
            )
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = onCancel) {
                    Text("取消", style = PicoTheme.typography.labelLarge)
                }
                Button(onClick = onExit) {
                    Text("退出", style = PicoTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
fun ResultPanel(
    ranking: List<Team>,
    humanTeam: Team,
    onPlayAgain: () -> Unit,
    onBackToHangar: () -> Unit,
) {
    GlassPanel(width = 320, height = 240) {
        Column(
            modifier = Modifier.fillMaxSize().padding(vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val winner = ranking.firstOrNull()
            Text(
                text = if (winner == humanTeam) "你赢了！" else "${winner?.let(::labelFor) ?: ""}获胜",
                color = PicoTheme.colorScheme.labelPrimary,
                style = PicoTheme.typography.headlineSmall,
            )
            Spacer(Modifier.size(10.dp))
            ranking.forEachIndexed { index, team ->
                Text(
                    text = "第${index + 1}名 · ${labelFor(team)}",
                    color = if (index == 0) PicoTheme.colorScheme.labelPrimary else PicoTheme.colorScheme.labelSecondary,
                    style = PicoTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.weight(1f))
            // 两个出口：同阵营再来一局，或回机库换阵营——阵营选择已经搬去机库窗口了，
            // 棋盘里没有别的地方能换。
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = onPlayAgain) {
                    Text("再来一局", style = PicoTheme.typography.labelLarge)
                }
                Button(
                    onClick = onBackToHangar,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PicoTheme.colorScheme.fillPrimary.copy(alpha = 0.3f),
                        contentColor = PicoTheme.colorScheme.labelPrimaryLight,
                    ),
                ) {
                    Text(
                        text = "返回机库",
                        color = PicoTheme.colorScheme.labelPrimaryLight,
                        style = PicoTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}
