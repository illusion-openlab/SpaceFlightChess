# 机库窗口：平面窗口入口 + SpatialModelView 选机 + 开局进沉浸

日期：2026-09-21
分支：`feat/hangar-window`（从 `main` 起）
状态：设计已确认

## 1. 目标

把开局流程从「一进应用就是全沉浸棋盘、在棋盘上贴的面板里点色块选阵营」改成：

1. 应用启动进入一个**平面窗口**（机库）
2. 窗口里用 `SpatialModelView` **展示四架真实飞机模型**代替四个颜色色块，点飞机选阵营
3. 点「开始游戏」才 `openStage` 进入沉浸棋盘，进去就是已就座的对局

## 2. 现状与约束

### 2.1 现状

- `Main.kt` 只声明了一个 `DefaultStage`，应用启动即全沉浸
- 选阵营是 `Panels.kt` 的 `StartPanel`，四个 `FactionSwatch` 纯色圆点，贴在棋盘上（`AttachmentPanel(START_PANEL)`，仅 `Phase.SETUP` 期间显示）
- 选完阵营走 `BoardStage.startWithFaction`：一段 `SEAT_ANIMATION_STEPS` 帧的插值动画，把棋盘和全部 16 架待飞棋子转到玩家席位，然后 `engine.startGame()`
- 退出是 `ExitConfirmPanel` → `activity?.finish()`，直接杀掉应用
- 四架飞机模型已在 `app/src/main/assets/models/{red,yellow,blue,green}_plane.usdz`

### 2.2 已核实的平台事实

| 事实 | 来源 | 影响 |
| --- | --- | --- |
| Planar 窗口**可以**显示 3D 内容，但深度固定 640dp 不可改，超出部分被系统截断 | `spatial-design_foundation_window.md` | 「平面窗口 + 3D 飞机」方案成立，但深度是硬预算 |
| `SpatialModelView` 读资产最大 bbox 自动缩放适配容器 | `spatial-design_art-&-design_asset-creation.md` | 四架飞机会被自动拉到相近大小，不必手工配缩放 |
| 但 `Resizability` 的 **API 默认值是 `None`** | `foundation-6.0.0-sources.jar` | 必须显式传 `FitInside`，否则拿不到上面那条自动适配 |
| 同窗口放多个模型需控面数/贴图（建议单模型 ~1500 面、1024² 贴图），否则「加载延迟或窗口卡顿」 | 同上 | 四模型同屏是官方点名的风险场景 |
| `LoadedModel.entity` 是 `internal` | `SpatialModelView.kt` | **拿不到 Entity**：模型不能转、不能换材质、不能播动画 |
| 四个 `.usdz` 机头朝向不一致：红/绿朝 +Z，蓝/黄朝 −X | `PlanePieceRenderer.MODEL_YAW_OFFSET_DEG`（当初实测并记录） | 棋盘上靠给 Entity 加 yaw 修正；窗口里没有 Entity，这条路断了 |
| 单模型真机加载约 1.7s | `PlanePieceRenderer` 注释（真机实测） | 窗口需要加载占位态 |
| `Source.assets(path)`（复数） | `Source.kt` | 别写成 `Source.asset` |
| `Stage()` DSL 没有 style 参数 | SpaceCube `Main.kt` 注释 | style 只能在 `openStage` 时给 |
| 主窗口的系统毛玻璃只能在 manifest 关，DSL 侧无对应参数 | SpaceCube manifest 注释 | `materialbackground=0` 写 manifest |
| `restoreWindowContainer` 的 KDoc：只能在有 stage 打开时使用 | SpaceCube `GamePage.returnToConfigWindow` 注释 | 回窗口时 restore 必须在 closeStage **之前** |

### 2.3 参照先例

`SpaceCube`（同工作区）已跑通完全相同的形态：`DefaultWindowContainer` 配置窗 → `openStage(id, style, bundle)` → 全沉浸 GamePage → `restoreWindowContainer` + `closeStage` 回窗口。本设计直接沿用它的结构和它踩过的坑。

### 2.4 唯一未验证项

`rotate3D(degree, axis, pivot)` 作用在 `SpatialModelView` 上时，转的是内嵌的 3D 模型，还是只转承载它的那块面板 quad。SDK 文档只说「把该 composable 渲染进一个独立 graphics layer」，没有说明对内嵌 3D 内容的语义；工作区内无先例。**必须上设备截图验证。**

## 3. 设计

### 3.1 容器架构

`Main.kt`：

```kotlin
fun mainApp(scope: SpatialAppScope) = with(scope) {
    DefaultWindowContainer { PicoTheme { HangarWindow() } }
    Stage(id = BOARD_STAGE_ID) { PicoTheme { BoardStage(bundle) } }
}
```

`AndroidManifest.xml`：`LaunchActivity` 下的 4 条 `pico.spatial.stage.*` meta-data 整体替换为：

| meta-data | 值 | 理由 |
| --- | --- | --- |
| `pico.spatial.windowcontainer.id` | `SpaceFlightChessHangarWindow` | 必须与 `Containers.kt` 的常量逐字一致；对不上不报错，只是 minimize/restore 静默失效 |
| `pico.spatial.windowcontainer.style` | `1`（Planar） | 用户明确要平面窗口；官方确认 Planar 可承载 3D 内容 |
| `pico.spatial.windowcontainer.defaultsize` | 初值 `1040x620`，门禁 B 截图后微调 | 只给宽×高，Planar 深度固定 640dp 写不了 |
| `pico.spatial.windowcontainer.materialbackground` | `0` | 关系统毛玻璃。两个共面半透明层是真机摩尔纹的已知诱因 |

Stage 的 style 挪到 `openStage(style = StageStyle.Mixed)`，与现 manifest 的 `stage.style="1"` 等价。

新增 `content/Containers.kt`，存 `HANGAR_WINDOW_ID` 与 `BOARD_STAGE_ID` 两个常量。

### 3.1.1 需要提升可见性的既有符号

`PlanePieceRenderer` 里两个 `private` 的按队常量现在有了第二个消费方（机库窗口），需要提到可共享的位置。二者都是「资产的固有属性」而非渲染器的内部细节，搬到一个中立位置（如 `content/PlaneAssets.kt`）比让窗口去 `internal` 访问渲染器更合适：

- `assetFileFor(team)`：`Team` → `.usdz` 文件名
- `MODEL_YAW_OFFSET_DEG`：实测的每资产机头 yaw 偏移

搬动时**原样保留那段记录测量过程的注释**（它是这些数字唯一的依据）。

### 3.2 机库窗口（新文件 `content/HangarWindow.kt`）

布局（四架并排）：

```
┌─────────── 空间飞行棋 ───────────┐
│            选择你的战机              │
│  ┏━━━━┓  ┌────┐  ┌────┐  ┌────┐  │
│  ┃ ✈  ┃  │ ✈  │  │ ✈  │  │ ✈  │  │
│  ┗━━━━┛  └────┘  └────┘  └────┘  │
│    红方     黄方    蓝方    绿方    │
│   你是红方，对手是其余三个 AI       │
│        [玩法]      [ 开始游戏 ]      │
└─────────────────────────────────┘
```

`PlaneTile(team, isSelected, onClick)`：

- 一张可点卡片 + 选中态边框（沿用 `StartPanel` 现有的 `PicoTheme.colorScheme.labelPrimary` 3dp 边框语言）
- 内嵌 `SpatialModelView(source = Source.assets("models/${assetFileFor(team)}"), resizability = Resizability.FitInside)`
- 蓝/黄两片额外加 `rotate3D(90f, RotationAxis3D.Y)` 修机头，角度直接取自 `MODEL_YAW_OFFSET_DEG`
- `ModelLoadingState.Loading` 显示占位（该色的纯色圆点 + 「载入中」），`Error` 显示纯色圆点兜底——加载失败不能让格子变成空白，否则和「没这个阵营」视觉上无法区分

窗口其余内容：标题、「选择你的战机」、选中说明文字、「玩法」按钮、「开始游戏」按钮。玩法说明沿用现有 `HowtoOverlay`（`BasicSheet`）与其文案，原样搬到窗口侧。

开局：

```kotlin
navigator.openStage(
    id = BOARD_STAGE_ID,
    style = StageStyle.Mixed,
    bundle = Bundle().apply { putString(TEAM_BUNDLE_KEY, selected.name) },
)
```

照抄 SpaceCube 的 `launching` / `stageOpened` / `isFocused` 三态机：防连点（同帧两次 `openStage`），并在 `NotAllowed`/`Error` 时把 `launching` 置回。**返回值必须分支处理**——`openStage` 失败在画面上与「棋盘还在建」完全一样，只记日志不回滚会让窗口永久卡在加载态、按钮永不可点（窗口从未失焦，`isFocused` 的 effect 也就永不再触发）。

### 3.3 BoardStage 的改动

| # | 改动 | 理由 |
| --- | --- | --- |
| 1 | 签名改 `BoardStage(bundle: Bundle?)`，解析 `TEAM_BUNDLE_KEY` 得 `humanTeam`，解析失败退回 `Team.RED` | 阵营改由窗口侧决定 |
| 2 | 删 `AttachmentPanel(START_PANEL)` 分支；删 `Panels.kt` 的 `StartPanel` 与 `FactionSwatch`；`HowtoOverlay`、`HowtoBadge`、`labelFor`、`swatchFor` 移至窗口侧（`swatchFor` 仍要用于加载占位/失败兜底的纯色圆点） | 窗口完全取代 StartPanel |
| 3 | 删就座动画与 `isSeating`：`BoardGeometry.configureSeat(team)` 改在 `initial` 建棋盘之前调用，一进 Stage 就是已就座的棋盘 | 那段动画存在的唯一理由是掩盖「棋子瞬移到看起来一样但队伍不对的机库」，阵营前置后前提不成立 |
| 4 | 进 Stage 后立刻 `engine.startGame()` | 不再有 SETUP 交互阶段 |
| 5 | `ResultPanel` 的「再来一局」改为 `restart(); startGame()`（同阵营重开）；新增第二个按钮「返回机库」走 3.4 的回窗口路径 | **`GameEngine.restart()` 把 phase 打回 `SETUP`**，而 StartPanel 已删除——不处理会让游戏卡死在一个没有任何 UI 的 SETUP 阶段 |
| 6 | `ExitConfirmPanel` 的 `onExit` 从 `activity?.finish()` 改为 3.4 的回窗口路径 | 有窗口可回之后，杀掉整个应用不再是合理的退出语义 |

### 3.4 窗口 / Stage 的最小化与恢复

- 进 Stage 后：`minimizeWindowContainer(HANGAR_WINDOW_ID)`
- 回机库：`restoreWindowContainer(HANGAR_WINDOW_ID)` **然后**在 `Dispatchers.Main.immediate` 上 `closeStage()`
  - 顺序不可交换：先 `closeStage` 的话，被最小化的窗口再没有任何 API 能叫回来
  - 必须 `Dispatchers.Main.immediate`：`restoreWindowContainer` 可能当场拆掉这份组合，而 `rememberCoroutineScope()` 的 scope 随组合一起取消；默认调度下 `closeStage()` 会排在取消之后而永远不执行，漏掉的全沉浸 Stage 会让下次 `openStage` 直接 `NotAllowed`
- `openStage` / `closeStage` / `restoreWindowContainer` 三个返回值**全部记日志**：这几步失败在画面上和「什么都没发生」完全一样，截图判不出来

## 4. 验证

| 项 | 方法 | 判据 |
| --- | --- | --- |
| 逻辑回归 | `./gradlew testDebugUnitTest` | 56+ 个纯逻辑单测全绿（本次不碰 `game/`） |
| 窗口渲染 | 门禁 B：模拟器截图 | 四架飞机都加载出来，没有空白格 |
| **rotate3D 语义** | 同一张截图 | 蓝/黄机头朝向与红/绿一致 = 成功；整张卡片歪掉或飞机跑出卡片边界 = 它转的是面板不是模型 |
| 深度截断 | 同一张截图 | 飞机没有被削平或切掉 |
| 开局链路 | logcat | `openStage(...) -> Allowed`，棋盘出现且已就座于所选阵营 |
| 回机库链路 | logcat + 截图 | `restoreWindowContainer -> ...` 与 `closeStage() returned` 都出现，窗口重新可见 |
| 窗口卡顿 | 主观 + 加载耗时日志 | 四模型并行加载不冻结窗口 |

### 4.1 rotate3D 失败时的回退

若判据判定 `rotate3D` 转的是面板而非模型：四个格子整体改用 `SpatialView` + ECS（照 SpaceCube `ConfigPage` 的形态，自己 `Entity.loadSuspend` 挂到 `Entity` 上），直接复用已实测的 `MODEL_YAW_OFFSET_DEG`。这是整体替换，不做「两个格子用 A 两个用 B」的混合。

## 5. 明确不做

- **局域网联机入口**：`net/` 的联机代码在 `feat/lan-multiplayer` 分支上，本分支从 `main` 起，不含也不碰它。联机大厅将来若落地，机库窗口是它的自然归宿，届时在 `HangarWindow` 上增量即可
- **飞机持续旋转展示**：`LoadedModel.entity` 是 `internal` 拿不到；用 `rotate3D` 逐帧转意味着每帧重建 graphics layer
- **模型资产返工**：不为修朝向去重新导出 `.usdz`（`usdpython` 在本机已不可用，改资产需走 Spatial Editor，成本远高于 3.4 的两条代码路径）
