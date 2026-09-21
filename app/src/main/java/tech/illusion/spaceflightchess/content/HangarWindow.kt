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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.ViewCoordinateSpace
import com.pico.spatial.core.math.EulerAngles
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.IconButton
import com.pico.spatial.ui.design.IconButtonDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.windows.BasicSheet
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.platform.LocalSpatialContainerStateManager
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import com.pico.spatial.ui.platform.containers.OpenStageResult
import com.pico.spatial.ui.platform.containers.StageStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import tech.illusion.spaceflightchess.game.Team

private const val HANGAR_LOG_TAG = "SpaceFlightChessHangar"

private val TileShape = RoundedCornerShape(20.dp)

/** 每个选机格子的边长。四格并排 + 间距要落在 manifest 的 defaultsize 宽度里。 */
private const val TILE_SIZE_DP = 180

/**
 * 格子内留给 3D 模型的方框边长（同时是每个机位自己那个 `SpatialView` 的尺寸）。刻意小于
 * [TILE_SIZE_DP]：留出的余量是为了让选中态边框和机身之间有呼吸，同时把模型前伸的深度控制在
 * Planar 窗口 640dp 的硬上限内（超出会被系统直接截断）。
 */
private const val MODEL_BOX_DP = 132

/**
 * 飞机最长边相对 [MODEL_BOX_DP] 物理尺寸的填充比例。仿 [MODEL_BOX_DP] 相对 [TILE_SIZE_DP] 的
 * 留白思路，不贴边顶格——既给选中态视觉呼吸，也给我们自己的包围盒测量留一点误差余量。
 */
private const val TILE_MODEL_FILL_FRACTION = 0.8f

/**
 * 机库窗口——应用的平面入口。
 *
 * 每个机位用一个独立的 [SpatialView] + 手写 ECS 展示这一队的真实飞机模型，代替最初的
 * [com.pico.spatial.ui.foundation.content.SpatialModelView] 方案（见 [PlaneTile] 的 KDoc：
 * `SpatialModelView` 那条路径已经被 A/B 实验证伪，不是因为 `rotate3D`）。
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
            // 外层 try/catch 处理 openStage 本身抛异常的情况——不只是它正常返回
            // NotAllowed/Error。两者在画面上是同一个死结：没有回滚 launching，按钮就永远卡在
            // 「进入中…」，因为窗口从没失去过焦点，isFocused 的 effect 也就永远不会再触发。
            // CancellationException 单独放行，不当成失败处理：正常的协程取消（比如窗口被销毁）
            // 不该被当作「openStage 失败」写日志或改状态，且吞掉它会破坏结构化并发的取消传播。
            try {
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
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                launching = false
                Log.w(HANGAR_LOG_TAG, "openStage($BOARD_STAGE_ID) threw", t)
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
 * **不用 `SpatialModelView`。** 门禁 B 的 round1/round2 A/B 实验（把 `.rotate3D(...)` 整行删掉、
 * 其余字节不动、重新截图对比）证伪了最初的怀疑：红方机位的 yaw offset 是 0°，round1
 * 带着 `rotate3D(0f, …)` 和 round2 完全不带这个修饰符，两轮截图里红方飞机的偏移量肉眼不可分辨
 * ——去掉变量之后问题原样保留，说明病因不是 rotate3D。真正的病因是四个 `.usdz` 的包围盒比可见
 * 网格大得多、且中心不在网格上：`Resizability.FitInside` 是按包围盒去适配容器的，包围盒越偏、
 * 越虚胖，适配出来的可见飞机就越小、越偏，四个机位因此呈现出四种不同的偏移和视觉大小——这正是
 * `PlanePieceRenderer` 早就在棋盘侧遇到并修过的同一个坑（见其 `attachTo` 里那段 `relativeTo`
 * 的长注释），只是 `SpatialModelView` 的 `LoadedModel.entity` 是 `internal`，这一侧拿不到 Entity
 * 去做同样的测量+回中。换成 [SpatialView] + 手写 ECS 就有 Entity 了：真正复用棋盘那条已验证路径
 * ——量出的 bounds 交给两边共用的 [longestEdgeNormalization]（见其 KDoc），目标物理尺寸按
 * [TILE_MODEL_FILL_FRACTION] 贴合这个格子（用 [SpatialView] 自带的像素↔米换算器算，不手工标定
 * magic scale）→ yaw 直接写 [MODEL_YAW_OFFSET_DEG] 到实体的 [TransformComponent]。Entity 层面这
 * 才是真的在转：Compose 侧的 `rotate3D` 是图层变换，不会传到 `SpatialView` 托管的 ECS 内容里。
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
            val density = LocalDensity.current
            var tileRoot by remember { mutableStateOf<Entity?>(null) }
            var loaded by remember { mutableStateOf(false) }
            var loadFailed by remember { mutableStateOf(false) }

            SpatialView(
                modifier = Modifier.size(MODEL_BOX_DP.dp),
                initial = { content, _ ->
                    try {
                        val root = Entity()
                        content.addEntity(root)
                        // 立刻记下 root，在任何可能挂起或抛异常的代码之前——DisposableEffect 的
                        // onDispose 只销毁它能看到的 tileRoot，挂起点(Entity.loadSuspend)期间被
                        // dispose、或挂起点之后到赋值之前抛异常，都会让这个 root 停留在
                        // SpatialView 的内容树里却没人认领。loaded 仍然留在最后才置 true，因为它
                        // 只是「这一帧该不该显示 3D 内容/该不该显示回退占位」的 UI 判据。
                        tileRoot = root
                        root.components[TransformComponent::class.java]?.let {
                            it.eulerAngles = EulerAngles(pitch = 0f, yaw = MODEL_YAW_OFFSET_DEG.getValue(team), roll = 0f)
                        } ?: Log.w(HANGAR_LOG_TAG, "team=$team root has no TransformComponent, yaw not applied")

                        // SpatialView 自带的像素↔米换算器：把这个格子的 dp 尺寸换成它在真实空间里
                        // 的物理尺寸，而不是手工标定一个 magic scale factor（见
                        // spatialviewcontent-is-the-coordinate-converter 记忆条目）。
                        val boxPx = with(density) { MODEL_BOX_DP.dp.toPx() }
                        val boxSizeM = content.convertSize(
                            Vector3(boxPx, boxPx, 0f),
                            ViewCoordinateSpace.Local,
                            content.localSpatialCoordinateSpace,
                        )
                        val targetSizeM = minOf(boxSizeM.x, boxSizeM.y)
                            .coerceAtLeast(MIN_MEASURABLE_DIMENSION_M) * TILE_MODEL_FILL_FRACTION

                        // Entity.loadSuspend 是真正的挂起点：round 4 的设备日志量过 YELLOW +4.1s、
                        // BLUE +3.0s 才加载完，这期间窗口完全可能被关掉或这个格子被重新组合。
                        val source = Entity.loadSuspend(uriString = "asset://models/${assetFileFor(team)}")
                        root.addChild(source)
                        // relativeTo 必须是 source 自己，不能传 null——同 PlanePieceRenderer.attachTo
                        // 那段注释：null 是相对 SpatialView 的根实体测量的，会把这个 root 自身的
                        // 定位/yaw 也吃进测量结果，产出的回中向量就是错的。
                        val bounds = source.getVisualBounds(source)
                        val (scale, recenter) = longestEdgeNormalization(bounds, targetSizeM)
                        source.components[TransformComponent::class.java]?.apply {
                            setScaleVector(Vector3(scale, scale, scale))
                            setPosition(recenter)
                        } ?: Log.w(HANGAR_LOG_TAG, "team=$team source has no TransformComponent, scale/position not applied")

                        loaded = true
                    } catch (t: CancellationException) {
                        // 结构化并发：挂起点(loadSuspend)上的正常取消（窗口销毁、这个格子被移出
                        // 组合）不是「加载失败」，不能吞掉，否则既破坏取消传播，又会把一次正常
                        // 取消误记成 team=$team load failed——round 4 就是靠这条日志把 GREEN
                        // 的「没加载成功」和「加载成功但没显示出来」区分开的，一条会在正常取消时
                        // 也触发的日志做不了这件事。
                        throw t
                    } catch (t: Throwable) {
                        Log.w(HANGAR_LOG_TAG, "hangar plane load failed for $team", t)
                        loadFailed = true
                    }
                },
            )

            when {
                loadFailed -> TileFallback(team = team, note = "模型加载失败")
                !loaded -> TileFallback(team = team, note = "载入中")
            }

            DisposableEffect(Unit) {
                onDispose { tileRoot?.destroy() }
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
