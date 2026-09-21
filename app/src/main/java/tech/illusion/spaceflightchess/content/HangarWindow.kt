package tech.illusion.spaceflightchess.content

import android.os.Bundle
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.IconButton
import com.pico.spatial.ui.design.IconButtonDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.windows.BasicSheet
import com.pico.spatial.ui.foundation.content.Model
import com.pico.spatial.ui.foundation.content.ModelLoadingState
import com.pico.spatial.ui.foundation.content.Resizability
import com.pico.spatial.ui.foundation.content.Source
import com.pico.spatial.ui.foundation.content.SpatialModelView
import com.pico.spatial.ui.foundation.effect3d.rotate3D
import com.pico.spatial.ui.foundation.geometry.RotationAxis3D
import com.pico.spatial.ui.platform.LocalSpatialContainerStateManager
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import com.pico.spatial.ui.platform.containers.OpenStageResult
import com.pico.spatial.ui.platform.containers.StageStyle
import kotlinx.coroutines.launch
import tech.illusion.spaceflightchess.game.Team

private const val HANGAR_LOG_TAG = "SpaceFlightChessHangar"

private val TileShape = RoundedCornerShape(20.dp)

/** 每个选机格子的边长。四格并排 + 间距要落在 manifest 的 defaultsize 宽度里。 */
private const val TILE_SIZE_DP = 180

/**
 * 格子内留给 3D 模型的方框边长。刻意小于 [TILE_SIZE_DP]：`Resizability.FitInside` 把模型最长边
 * 缩到容器最短边，留出的余量是为了让选中态边框和机身之间有呼吸，同时把模型前伸的深度控制在
 * Planar 窗口 640dp 的硬上限内（超出会被系统直接截断）。
 */
private const val MODEL_BOX_DP = 132

/**
 * 机库窗口——应用的平面入口。
 *
 * 用 [SpatialModelView] 直接展示四架真实飞机模型代替原来的四个纯色圆点。注意 `resizability` 的
 * API 默认值是 [Resizability.None]，必须显式传 [Resizability.FitInside] 才有官方文档说的
 * 「读资产 bbox 自动适配容器」。
 */
@Composable
fun HangarWindow() {
    val navigator = LocalSpatialNavigator.current
    val scope = rememberCoroutineScope()

    var selected by remember { mutableStateOf(Team.RED) }
    var showHowto by remember { mutableStateOf(false) }
    var launching by remember { mutableStateOf(false) }
    // 只在 openStage 真的成功过之后置 true。单纯的窗口重新获得焦点是环境信号（看别处再看回来
    // 也会触发），不能单独当作「从棋盘返回了」的判据。
    var stageOpened by remember { mutableStateOf(false) }

    val isFocused by LocalSpatialContainerStateManager.current.isFocused
    LaunchedEffect(isFocused) {
        if (!isFocused || !stageOpened) return@LaunchedEffect
        stageOpened = false
        launching = false
        Log.i(HANGAR_LOG_TAG, "hangar window focused again after stage")
    }

    fun startGame() {
        // 双保险：按钮本身已经 enabled = !launching，这里再拦一次，避免快速连点在同一帧内
        // 发出两次 openStage。
        if (launching) return
        launching = true
        showHowto = false
        val team = selected
        scope.launch {
            val result = navigator.openStage(
                id = BOARD_STAGE_ID,
                // Mixed：虚拟内容始终渲染，环境光完全来自真实房间的 VST。这是改造前默认 Stage
                // 用的同一个 style（原 manifest 的 pico.spatial.stage.style="1"），迁到非默认
                // Stage 后只能在这里给：Stage() 这个 DSL 函数没有 style 参数。
                style = StageStyle.Mixed,
                bundle = Bundle().apply { putString(TEAM_BUNDLE_KEY, team.name) },
            )
            // 返回值必须分支处理，不能只记日志就丢掉：openStage 失败在画面上和「棋盘还在建」
            // 完全一样，没有 launching 的正确回滚，失败之后这个窗口会永远卡在「进入中」、按钮
            // 永远不可点——因为窗口从没失去过焦点，isFocused 的 effect 也就永远不会再触发。
            when (result) {
                is OpenStageResult.Allowed -> {
                    stageOpened = true
                    Log.i(HANGAR_LOG_TAG, "openStage($BOARD_STAGE_ID) -> $result")
                    val minimized = navigator.minimizeWindowContainer(HANGAR_WINDOW_ID)
                    Log.i(HANGAR_LOG_TAG, "minimizeWindowContainer($HANGAR_WINDOW_ID) -> $minimized")
                }
                is OpenStageResult.NotAllowed, is OpenStageResult.Error -> {
                    launching = false
                    Log.w(HANGAR_LOG_TAG, "openStage($BOARD_STAGE_ID) failed -> $result")
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "空间飞行棋",
                color = PicoTheme.colorScheme.labelPrimary,
                style = PicoTheme.typography.headlineMedium,
            )
            Spacer(Modifier.size(6.dp))
            Text(
                text = "选择你的战机",
                color = PicoTheme.colorScheme.labelSecondary,
                style = PicoTheme.typography.bodyLarge,
            )
            Spacer(Modifier.size(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Team.entries.forEach { team ->
                    PlaneTile(
                        team = team,
                        isSelected = team == selected,
                        onClick = { if (!launching) selected = team },
                    )
                }
            }
            Spacer(Modifier.size(16.dp))
            Text(
                text = "你是${labelFor(selected)}，对手是其余三个 AI",
                color = PicoTheme.colorScheme.labelSecondary,
                style = PicoTheme.typography.bodyMedium,
            )
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(
                    onClick = { if (!showHowto) showHowto = true },
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
                Button(onClick = ::startGame, enabled = !launching) {
                    Text(
                        text = if (launching) "进入中…" else "开始游戏",
                        style = PicoTheme.typography.labelLarge,
                    )
                }
            }
        }
    }

    if (showHowto) {
        HowtoOverlay(onDismiss = { showHowto = false })
    }
}

/**
 * 一个阵营的选机格子：一张可点卡片，中间是这一队飞机的真实 3D 模型。
 *
 * [rotate3D] 修正机头：四个 `.usdz` 的机头朝向不一致（见 [MODEL_YAW_OFFSET_DEG] 的实测注释）。
 * 棋盘上是给 Entity 加 yaw 偏移修正的，但 `SpatialModelView` 的 `LoadedModel.entity` 是
 * `internal`，窗口里拿不到 Entity，只能走 Compose 侧的这个修饰符。红/绿传 0f 是空操作，保留是
 * 为了四个格子走同一条代码路径。
 *
 * 加载中/失败都回退到该队的纯色圆点：空白格子和「没有这个阵营」在视觉上无法区分。
 */
@Composable
private fun PlaneTile(team: Team, isSelected: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(TILE_SIZE_DP.dp)
                .clip(TileShape)
                .background(PicoTheme.colorScheme.fillTertiary)
                .then(
                    if (isSelected) {
                        Modifier.border(width = 3.dp, color = PicoTheme.colorScheme.labelPrimary, shape = TileShape)
                    } else {
                        Modifier
                    },
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            SpatialModelView(
                source = Source.assets("models/${assetFileFor(team)}"),
                modifier = Modifier
                    .size(MODEL_BOX_DP.dp)
                    .rotate3D(MODEL_YAW_OFFSET_DEG.getValue(team), RotationAxis3D.Y),
                resizability = Resizability.FitInside,
            ) { state ->
                when (state) {
                    is ModelLoadingState.Success -> Model(state.model)
                    is ModelLoadingState.Loading -> TileFallback(team = team, note = "载入中")
                    is ModelLoadingState.Error -> {
                        Log.w(HANGAR_LOG_TAG, "model load failed for $team: ${state.reason}")
                        TileFallback(team = team, note = "模型加载失败")
                    }
                }
            }
        }
        Spacer(Modifier.size(8.dp))
        Text(
            text = labelFor(team),
            color = if (isSelected) PicoTheme.colorScheme.labelPrimary else PicoTheme.colorScheme.labelSecondary,
            style = PicoTheme.typography.bodyMedium,
        )
    }
}

/** 模型不可用时的兜底：该队的纯色圆点 + 一行说明，复用 [swatchFor] 的既有配色。 */
@Composable
private fun TileFallback(team: Team, note: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(swatchFor(team)),
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = note,
            color = PicoTheme.colorScheme.labelSecondary,
            style = PicoTheme.typography.labelSmall,
        )
    }
}

/**
 * The "?" badge used by both [StartPanel]'s and [HangarWindow]'s 玩法 entry buttons — a filled
 * dot, no icon asset needed. `internal`, not `private`: [StartPanel] in `Panels.kt` still calls
 * this (Task 5 removes that call site, not this task — see the brief's intermediate-state note),
 * and a top-level `private` declaration is file-scoped in Kotlin, so a same-package caller in
 * another file cannot see it.
 */
@Composable
internal fun HowtoBadge() {
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
 *
 * `internal`, not `private`, for the same reason as [HowtoBadge]: [StartPanel] in `Panels.kt`
 * still calls this until Task 5.
 */
@Composable
internal fun HowtoOverlay(onDismiss: () -> Unit) = BasicSheet(onDismissRequest = onDismiss) {
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
