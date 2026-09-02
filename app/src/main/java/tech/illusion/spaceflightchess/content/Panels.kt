package tech.illusion.spaceflightchess.content

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.IconButton
import com.pico.spatial.ui.design.IconButtonDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.windows.BasicSheet
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import tech.illusion.spaceflightchess.game.Team

private val PanelShape = RoundedCornerShape(18.dp)

@Composable
private fun GlassPanel(
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
private fun swatchFor(team: Team): Color = when (team) {
    // design-style: fixed-figma-color - mirrors PlanePieceRenderer's Color4 disc colours
    Team.RED -> Color(0xFFE83333)
    Team.YELLOW -> Color(0xFFFACC24)
    Team.BLUE -> Color(0xFF3373F2)
    Team.GREEN -> Color(0xFF40B359)
}

private fun labelFor(team: Team): String = when (team) {
    Team.RED -> "红方"
    Team.YELLOW -> "黄方"
    Team.BLUE -> "蓝方"
    Team.GREEN -> "绿方"
}

/** A single tappable faction swatch — a filled circle in that team's colour, ringed when selected. */
@Composable
private fun FactionSwatch(team: Team, isSelected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(swatchFor(team))
            .then(
                if (isSelected) {
                    Modifier.border(width = 3.dp, color = PicoTheme.colorScheme.labelPrimary, shape = CircleShape)
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick),
    )
}

@Composable
fun StartPanel(onStart: (Team) -> Unit) {
    var selected by remember { mutableStateOf(Team.RED) }
    // Whether the "玩法" (how-to-play) overlay is up. Scoped to this composable, not BoardStage's
    // showExitConfirm, because it never needs to survive past this panel: HowtoOverlay is a
    // BasicSheet now (its own modal window, see that composable's doc), so 开始游戏 is physically
    // unreachable while it's open without this composable doing anything extra, and the moment the
    // phase leaves SETUP this whole panel (and therefore this state) is torn down by BoardStage's
    // `if (phase == Phase.SETUP && ...)` gate — mutual exclusion with the exit-confirm panel falls
    // out of that same gate for free.
    var showHowto by remember { mutableStateOf(false) }
    GlassPanel(width = 360, height = 280) {
        Column(
            modifier = Modifier.fillMaxSize().padding(vertical = 20.dp, horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "空间飞行棋",
                color = PicoTheme.colorScheme.labelPrimary,
                style = PicoTheme.typography.headlineSmall,
            )
            Spacer(Modifier.size(14.dp))
            Text(
                text = "选择你的阵营",
                color = PicoTheme.colorScheme.labelSecondary,
                style = PicoTheme.typography.bodyMedium,
            )
            Spacer(Modifier.size(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Team.entries.forEach { team ->
                    FactionSwatch(team = team, isSelected = team == selected, onClick = { selected = team })
                }
            }
            Spacer(Modifier.size(10.dp))
            Text(
                text = "你是${labelFor(selected)}，对手是其余三个 AI",
                color = PicoTheme.colorScheme.labelSecondary,
                style = PicoTheme.typography.bodyMedium,
            )
            Spacer(Modifier.weight(1f))
            Button(onClick = {
                // Defensive belt-and-suspenders: the scrim already makes this unreachable while
                // showHowto is true, but this keeps "no overlay survives into the next phase" true
                // even if that ever changes.
                showHowto = false
                onStart(selected)
            }) {
                Text("开始游戏", style = PicoTheme.typography.labelLarge)
            }
        }

        // ── "玩法" entry button — a same-level sibling of the Column above, floated to the card's
        // top-right corner (fully vacant today, unlike the crowded centre column). This SDK version's
        // Button has no style/variant parameter and no predefined "secondary" color role (checked
        // against design-6.0.0-sources.jar), so the muted look is produced by reusing 开始游戏's own
        // fillPrimary/labelPrimaryLight roles rather than inventing a different one: a low-alpha fill
        // keeps it from competing with the primary CTA while staying visually related to it.
        Button(
            onClick = { if (!showHowto) showHowto = true },
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 10.dp),
            size = ButtonDefaults.Min,
            colors = ButtonDefaults.buttonColors(
                containerColor = PicoTheme.colorScheme.fillPrimary.copy(alpha = 0.3f),
                contentColor = PicoTheme.colorScheme.labelPrimaryLight,
            ),
            leadingIcon = { HowtoBadge() },
        ) {
            Text(
                text = "玩法",
                color = PicoTheme.colorScheme.labelPrimaryLight,
                style = PicoTheme.typography.labelSmall,
            )
        }
    }
    if (showHowto) {
        HowtoOverlay(onDismiss = { showHowto = false })
    }
}

/** The "?" badge inside [StartPanel]'s 玩法 entry button — a filled dot, no icon asset needed. */
@Composable
private fun HowtoBadge() {
    Box(
        modifier = Modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(PicoTheme.colorScheme.fillPrimary.copy(alpha = 0.68f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "?",
            color = PicoTheme.colorScheme.labelPrimaryLight,
            style = PicoTheme.typography.labelSmall,
        )
    }
}

/**
 * The 玩法说明 overlay — a [BasicSheet] now, not a hand-rolled scrim + same-level [GlassPanel]
 * sibling. The old version drew two flat, near-coplanar surfaces (the scrim and the card) directly
 * in `StartPanel`'s own Box, which read fine in the emulator but moiré'd on real hardware — two
 * textured/translucent planes sitting almost on top of each other in a stereo compositor is exactly
 * the shape of bug that produces (real-device feedback, not caught by any screenshot). `BasicSheet`
 * is the SDK's own modal primitive (confirmed via decompilation elsewhere in this workspace to run
 * through `SpatialDialogDelegate`, its own window rather than a same-depth Compose overlay), so it
 * doesn't have that failure mode, and it gets the "点外部关闭 / 点内部不误关闭" behavior for free —
 * no more hand-written scrim `pointerInput` or a second consume-only one on the card itself.
 */
@Composable
private fun HowtoOverlay(onDismiss: () -> Unit) = BasicSheet(onDismissRequest = onDismiss) {
    GlassPanel(width = 340, height = 248) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "玩法说明",
                    modifier = Modifier.weight(1f),
                    color = PicoTheme.colorScheme.labelPrimary,
                    style = PicoTheme.typography.titleSmall,
                )
                IconButton(
                    onClick = onDismiss,
                    size = IconButtonDefaults.Min,
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = PicoTheme.colorScheme.fillTertiary,
                        contentColor = PicoTheme.colorScheme.labelSecondary,
                    ),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "×",
                            color = PicoTheme.colorScheme.labelSecondary,
                            style = PicoTheme.typography.labelMedium,
                        )
                    }
                }
            }
            Spacer(Modifier.size(10.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = "简介",
                    color = PicoTheme.colorScheme.labelPrimary,
                    style = PicoTheme.typography.titleSmall,
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    text = HOWTO_INTRO_TEXT,
                    modifier = Modifier.fillMaxWidth(),
                    color = PicoTheme.colorScheme.labelSecondary,
                    style = PicoTheme.typography.bodyMediumMultiline,
                )
                Spacer(Modifier.size(14.dp))

                Text(
                    text = "操作",
                    color = PicoTheme.colorScheme.labelPrimary,
                    style = PicoTheme.typography.titleSmall,
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    text = HOWTO_CONTROLS_TEXT,
                    modifier = Modifier.fillMaxWidth(),
                    color = PicoTheme.colorScheme.labelSecondary,
                    style = PicoTheme.typography.bodyMediumMultiline,
                )
                Spacer(Modifier.size(14.dp))

                Text(
                    text = "规则",
                    color = PicoTheme.colorScheme.labelPrimary,
                    style = PicoTheme.typography.titleSmall,
                )
                Spacer(Modifier.size(6.dp))
                HOWTO_RULES_TEXT.forEachIndexed { index, rule ->
                    Text(
                        text = rule,
                        modifier = Modifier.fillMaxWidth(),
                        color = PicoTheme.colorScheme.labelSecondary,
                        style = PicoTheme.typography.bodyMediumMultiline,
                    )
                    Spacer(Modifier.size(if (index == HOWTO_RULES_TEXT.lastIndex) 4.dp else 6.dp))
                }
            }
        }
    }
}

// ── 玩法说明 copy — authoritative, verbatim; do not rewrite/summarize/renumber. ──────────────────
private const val HOWTO_INTRO_TEXT =
    "选一个颜色阵营,轮流掷骰子推进你的四架飞机,目标是抢在另外三个 AI 对手之前,把自己的四架飞机全部飞抵终点。"

private const val HOWTO_CONTROLS_TEXT =
    "- 隔空捏合：对准想选的东西——棋盘上的骰子模型、自己的飞机棋子、或面板上的按钮,捏合手指确认。"

private val HOWTO_RULES_TEXT = listOf(
    "1. 桌面大小的棋盘飘在你面前,你执一色(红/黄/蓝/绿),另外三色全部由 AI 对战。",
    "2. 飞机一开始停在机库里,只有摇到 6 点才能起飞上路;已经在路上的飞机不管摇到几点都有地方可走,不会出现\"这个点数没法用\"的情况。",
    "3. 轮到你时,点一下棋盘上的骰子模型让它转出点数,再点你想动的那架飞机完成这一步;如果几架僚机叠在一起,点其中任意一架就会带着整组一起走。",
    "4. 摇到 6 可以再摇一次,不封顶——但如果同一轮连续摇出三个 6,系统会强制你交出一架在路上的飞机送回机库,重新等 6 才能再出发。",
    "5. 踩到单独一架对方棋子会把它吃回机库,对方得重新摇 6 才能再起飞;但如果对方两架以上叠在一起且数量比你多,你反而进不去,会被弹回来时的路上。",
    "6. 己方两架以上棋子叠在同一格会结成一支僚机小队,之后要整体一起挪动,也更不容易被对手吃掉。",
    "7. 踩上自己阵营的专属颜色格会触发一次额外的跳跃,运气好还能一路跳上一段\"飞行捷径\",直接往前跳出一大截;回家的最后一段小道和终点则是绝对安全区,谁都进不去,也吃不到你。",
)

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
 * intercepts — `DefaultStage` has no window chrome to fall back on, so without that callback the
 * input silently killed the whole session via `Activity.finish()` (see `BoardStage.findComponentActivity`).
 * Same layout as [StartPanel] / [ResultPanel]; 取消 dismisses, 退出 actually exits.
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
fun ResultPanel(ranking: List<Team>, humanTeam: Team, onPlayAgain: () -> Unit) {
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
            Button(onClick = onPlayAgain) {
                Text("再来一局", style = PicoTheme.typography.labelLarge)
            }
        }
    }
}
