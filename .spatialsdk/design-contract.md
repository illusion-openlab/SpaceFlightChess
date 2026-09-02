# 设计契约增量 — SpaceFlightChess「玩法」入口按钮 + 说明弹层

> 本文件首次创建，起点即本次「玩法」入口按钮 + 说明弹层增量。本增量只覆盖 `StartPanel` 新增的"玩法"入口按钮与说明弹层，不描述 `GameHud`/`ExitConfirmPanel`/`ResultPanel`，也不改动 `StartPanel` 现有任何元素的位置或逻辑。

## 0. 元信息

- 应用名 / applicationId：SpaceFlightChess / `tech.illusion.spaceflightchess`
- 设计源类型：Gate A 准备阶段的通用规格（跨多个项目统一投放的"玩法"入口+说明弹层规格），本增量是该通用规格落到本项目真实布局后的具体化产物，末段视觉规格由用户在门禁 A 阶段做了最终确认与调整（详见 §2.1 的颜色角色变更说明）
- 设计源位置：门禁 A 阶段设计增量对话 + 用户最终确认的按钮视觉规格；对照代码 `app/src/main/java/tech/illusion/spaceflightchess/content/Panels.kt`（`StartPanel`）
- 契约版本：v1（本文件首次落地，随本次实现一起提交）
- 用户确认时间：2026-08-26 —— 已通过门禁 A 确认，本次实现即该确认版本的落地

## 1. 面板清单（新增行为，非新增面板）

| 面板 id | 容器类型 | 面板尺寸 | 层级/父面板 | 出现时机 |
|---|---|---|---|---|
| start_page（= `StartPanel`，`AttachmentPanel(id=START_PANEL)`） | `SpatialView` 内的 `AttachmentPanel`（非独立 `WindowContainer`/`Stage`） | 360×280dp（**沿用现状，不放大**） | `rig` 子节点 | `GameEngine.phase == SETUP` 时常驻 |

`start_page` 本身就是全部"开始页容器"——它不是某个更大窗口里的一张卡片，`GlassPanel(360,280)` 的 `Box` 就是唯一可用的舞台。本增量不新建 `AttachmentPanel`，"玩法"入口按钮与弹层都作为 `StartPanel` 内部 `GlassPanel` 这个 `Box` 的新增同级子项（与现有 `Column` 平级），不改变容器类型，不改变 `AttachmentPanel` 数量。`GlassPanel` 私有辅助函数为此新增了一个可选 `modifier` 参数、并把 `content` 的类型从 `@Composable () -> Unit` 改为 `@Composable BoxScope.() -> Unit`，使同级子项可以用 `Modifier.align`/`matchParentSize`；这是唯一涉及既有共享代码的改动，对 `GameHud`/`ExitConfirmPanel`/`ResultPanel` 三处既有调用完全源码兼容（它们不使用 `BoxScope` 接收者，行为不变）。

**架构风险核实结果**：`AttachmentPanel` 未观察到把内容尺寸收紧到 Compose 测量边界的问题；弹层卡片 340×248dp 完全落在 360×280dp 边界内（四周各留 10–16dp），实现阶段未发现需要额外处理的 surface 尺寸问题。

## 2. 元素表

沿用项目 `Panels.kt` 已验证使用的 `PicoTheme` 语义角色：`labelPrimary`/`labelSecondary`/`labelPrimaryLight`/`fillPrimary`/`fillTertiary`/`headlineSmall`/`bodyMedium`/`labelLarge`（沿用或新增使用）；新增用到、在 design-6.0.0 源码中核实存在的角色/类型：`titleSmall`、`labelSmall`、`labelMedium`、`bodyMediumMultiline`。颜色角色全部走 `PicoTheme.colorScheme.*`，字号全部走 `PicoTheme.typography.*`，禁止 Material/Material3。

### 2.1 start_page 新增：玩法入口按钮

| id | 类型 | 父容器 | 锚点 | 偏移 | 尺寸 | 颜色角色 | 字体角色 | 圆角/形状 |
|---|---|---|---|---|---|---|---|---|
| howtoEntryButton | `Button`（`ButtonDefaults.Min`，**不传自定义 `ButtonSize`**——本工作区已有踩坑记录"自定义 ButtonSize 会静默丢圆角"） | start_page（`StartPanel` 的 `GlassPanel` Box，与现有 `Column` 同级） | 右上（`Alignment.TopEnd`） | -10,10 | wrap×wrap（带 leadingIcon 后 SDK 自动扩到最小 71×32dp） | 容器色 `fillPrimary.copy(alpha≈0.3f)`；内容色（图标+文字）`labelPrimaryLight`，接近满不透明度；**无描边** | `labelSmall` | SDK 默认（`Min` 尺寸自带圆角 token，不额外指定） |

**颜色角色的最终确认变更（相对 Gate A 草案的修正）**：Gate A 草案曾以 `fillTertiary`/`labelSecondary` 模拟"次要/ghost"观感。用户在门禁 A 最终确认阶段改为更贴近主 CTA 的方案：容器色直接复用"开始游戏"主按钮实际解析到的颜色角色（`Button()` 不传 `colors` 时，SDK 默认解析为 `containerColor=fillPrimary, contentColor=labelPrimaryLight`，已用 `design-6.0.0-sources.jar` 的 `ButtonDefaults.defaultButtonColors` 核实），但把容器色 alpha 降到 0.3 做"同色但浅淡"的柔和填充；内容色仍用 `labelPrimaryLight`（与主按钮文字同一角色），保持接近满不透明度使文字/图标清晰可辨；不加 `border`/描边，纯靠"浅色底 + 鲜艳文字图标"制造存在感。

`howtoEntryButton` 的 `leadingIcon` 是一个直径 18dp 的圆形徽标（`fillPrimary.copy(alpha≈0.68f)` 底色 + `labelPrimaryLight` 文字 `Text("?")` 居中），紧跟 `Text("玩法", style=labelSmall, color=labelPrimaryLight)`。项目内没有任何图标资源、也不引入 Material 图标库。

### 2.2 start_page 新增：玩法说明弹层（点击 `howtoEntryButton` 后叠加显示，`StartPanel` 内 `GlassPanel` Box 的同级子项，声明顺序在 `Column`/`howtoEntryButton` 之后，绘制层级最上）

| id | 类型 | 父容器 | 锚点 | 尺寸 | 内边距 | 颜色角色 | 字体角色 | 圆角/形状 |
|---|---|---|---|---|---|---|---|---|
| howtoOverlayScrim | `Box` | start_page | 填满（`Modifier.matchParentSize()`） | 360×280（与宿主 `GlassPanel` 完全同尺寸） | - | `labelPrimary.copy(alpha≈0.55f)`（半透明遮罩） | - | - |
| howtoPanelCard | `GlassPanel`（复用项目已有的 `backgroundMaterial(true, Material.Regular)` 玻璃质感私有组件，与其它面板同一视觉语言） | start_page（叠在 scrim 之上，同级） | 居中 | 340×248dp（固定） | 16,14 | 无额外纯色背景（完全靠 `GlassPanel` 自带材质） | - | 18dp（复用 `PanelShape` 私有常量） |
| howtoTitleRow | `Row` | howtoPanelCard | 上，横向铺满 | fill×wrap | - | - | - | - |
| howtoTitleText（"玩法说明"） | `Text` | howtoTitleRow | 左，占满剩余宽度（`weight(1f)`） | fill×wrap | - | `labelPrimary` | `titleSmall` | - |
| howtoCloseButton（"×"） | `IconButton`（`IconButtonDefaults.Min`，38×32dp） | howtoTitleRow | 右 | SDK 默认 | - | `fillTertiary`（背景）/`labelSecondary`（文字） | `labelMedium` | SDK 默认 |
| howtoBodyScroll | `Column`（`verticalScroll(rememberScrollState())`） | howtoPanelCard | 标题行以下，铺满剩余空间（`weight(1f)`） | fill×fill（超出内容滚动，不裁切文字） | - | - | - | - |
| howtoIntroSectionTitle（"简介"） | `Text` | howtoBodyScroll | 左 | wrap×wrap | 下 6 | `labelPrimary` | `titleSmall` | - |
| howtoIntroBody | `Text`（原文见 §4，逐字不改） | howtoBodyScroll | 左，铺满宽度 | fill×wrap | 下 14 | `labelSecondary` | `bodyMediumMultiline` | - |
| howtoControlsSectionTitle（"操作"） | `Text` | howtoBodyScroll | 左 | wrap×wrap | 下 6 | `labelPrimary` | `titleSmall` | - |
| howtoControlsItem | `Text` | howtoBodyScroll | 左，铺满宽度 | fill×wrap | 下 14 | `labelSecondary` | `bodyMediumMultiline` | - |
| howtoRulesSectionTitle（"规则"） | `Text` | howtoBodyScroll | 左 | wrap×wrap | 下 6 | `labelPrimary` | `titleSmall` | - |
| howtoRulesItem ×7 | `Text`×7（1.–7. 顺序逐条渲染） | howtoBodyScroll | 左，铺满宽度 | fill×wrap | 条目间距 6，末条下 4 | `labelSecondary` | `bodyMediumMultiline` | - |

**两条硬性指针规则（已实现，沿用本工作区"覆盖层两条必做"记录）**：
1. `howtoOverlayScrim` 用 `Modifier.pointerInput(Unit) { detectTapGestures { onDismiss() } }` 消费点击并触发关闭——点击卡片以外任意空白处等效于点 `howtoCloseButton`。
2. `howtoPanelCard`（`GlassPanel` 调用时传入的 `modifier`）也用 `Modifier.pointerInput(Unit) { detectTapGestures { } }` 消费一次点击但不做任何动作，防止卡片内部空白处的点击穿透到 scrim 被误判为"点了空白处"。

## 3. 状态清单

| 元素 id | 状态 | 视觉变化 |
|---|---|---|
| howtoEntryButton | 默认 | 元素表默认视觉（`fillPrimary@0.3`/`labelPrimaryLight`） |
| howtoEntryButton | hover | SDK 内建 `spatialHoverEffect`（`Button` 组件自带） |
| howtoEntryButton | 点击 | `onClick` 内 `if (!showHowto) showHowto = true` 防重入判断 |
| howtoCloseButton | 默认/hover | 同 `howtoEntryButton`，SDK 内建 hover 反馈 |
| howtoOverlayScrim | 点击（含卡片外任意坐标） | 关闭弹层，效果与点 `howtoCloseButton` 完全一致 |
| howtoPanelCard | 无独立"选中"态 | 仅消费自身点击，不做视觉状态切换 |
| howtoBodyScroll | 内容溢出 340×248 卡片可视高度 | 依赖 `verticalScroll` 自然滚动 |
| 弹层 ↔ 阶段切换 | 玩法弹层开着时用户理论上触发进入下一阶段的动作 | scrim 已物理拦截对"开始游戏"按钮的点击，此路径不可达；`StartPanel` 的"开始游戏" `onClick` 内仍防御性地先置 `showHowto = false` 再调用 `onStart`，保证这条不变量即使未来实现变化也成立 |
| 弹层 ↔ 退出确认弹层 | 二者互斥 | `showHowto` 状态完全 scoped 在 `StartPanel` 内部；`StartPanel` 本身只在 `phase == Phase.SETUP && !showExitConfirm` 时才会被组合，`showExitConfirm` 一旦为真整个 `StartPanel`（含玩法弹层）连同其 `remember` 状态一起被移出组合，天然互斥，无需额外协调代码 |

## 4. 文案清单（权威来源——以下三段原文逐字照抄，禁止改写/转述/精简）

| 元素 id | 文案 |
|---|---|
| howtoEntryButton | 玩法 |
| howtoTitleText | 玩法说明 |
| howtoCloseButton | × |
| howtoIntroSectionTitle | 简介 |
| howtoIntroBody | 见下方【简介】原文 |
| howtoControlsSectionTitle | 操作 |
| howtoControlsItem | 见下方【操作】原文 |
| howtoRulesSectionTitle | 规则 |
| howtoRulesItem ×7 | 见下方【规则】原文，按原有 1.–7. 顺序逐条渲染，不重新编号、不合并、不拆分 |

**【简介】**

选一个颜色阵营,轮流掷骰子推进你的四架飞机,目标是抢在另外三个 AI 对手之前,把自己的四架飞机全部飞抵终点。

**【操作】**

- 隔空捏合：对准想选的东西——棋盘上的骰子模型、自己的飞机棋子、或面板上的按钮,捏合手指确认。

**【规则】**

1. 桌面大小的棋盘飘在你面前,你执一色(红/黄/蓝/绿),另外三色全部由 AI 对战。
2. 飞机一开始停在机库里,只有摇到 6 点才能起飞上路;已经在路上的飞机不管摇到几点都有地方可走,不会出现"这个点数没法用"的情况。
3. 轮到你时,点一下棋盘上的骰子模型让它转出点数,再点你想动的那架飞机完成这一步;如果几架僚机叠在一起,点其中任意一架就会带着整组一起走。
4. 摇到 6 可以再摇一次,不封顶——但如果同一轮连续摇出三个 6,系统会强制你交出一架在路上的飞机送回机库,重新等 6 才能再出发。
5. 踩到单独一架对方棋子会把它吃回机库,对方得重新摇 6 才能再起飞;但如果对方两架以上叠在一起且数量比你多,你反而进不去,会被弹回来时的路上。
6. 己方两架以上棋子叠在同一格会结成一支僚机小队,之后要整体一起挪动,也更不容易被对手吃掉。
7. 踩上自己阵营的专属颜色格会触发一次额外的跳跃,运气好还能一路跳上一段"飞行捷径",直接往前跳出一大截;回家的最后一段小道和终点则是绝对安全区,谁都进不去,也吃不到你。

## 5. 不做清单（YAGNI 边界）

- 不改动 `StartPanel` 现有任何元素（标题"空间飞行棋"、"选择你的阵营"提示、4 色 `FactionSwatch` 选择器、"你是XX方…"提示、"开始游戏"按钮）的位置、尺寸、逻辑或样式
- 不改动 `GameHud` / `ExitConfirmPanel` / `ResultPanel`，本增量只作用于 `StartPanel`
- 不引入 Material/Material3 图标库或任何第三方图标资源；入口按钮与关闭按钮的"图标"用文字符号占位（"?" / "×"）
- 不规定弹层显隐的转场动画曲线（只有"点击显示 / 点击 scrim 或关闭按钮隐藏"两个开关态，无 alpha/scale 过渡动画）
- 不做"是否已读过玩法说明"的持久化记忆（不写 SharedPreferences/DataStore/文件），每次回到 `SETUP` 阶段弹层都从"未展开"状态开始
- 不放大宿主 `AttachmentPanel`/`GlassPanel` 的 360×280dp 尺寸去迁就更宽的弹层

## 6. 截图验证计划（Gate B 阶段使用）

| 序号 | 状态描述 | 到达方式 | 本图核对的元素 id |
|---|---|---|---|
| 1 | `start_page` 默认态，弹层未打开 | 启动应用等待渲染，不做任何点击 | howtoEntryButton（确认出现在右上角、不与阵营选择器/开始按钮重叠、不遮挡任何现有元素；容器色应明显比"开始游戏"浅淡但同色系，文字图标清晰可辨、无描边） |
| 2 | `start_page` 玩法弹层展开态 | 点击 `howtoEntryButton`（单次确定性点击，坐标固定在卡片右上角） | howtoOverlayScrim（背景应明显压暗）、howtoPanelCard、howtoTitleText（"玩法说明"）、howtoCloseButton（"×"）、howtoIntroSectionTitle/Body、howtoControlsSectionTitle/Item、howtoRulesSectionTitle/Item（至少能看到"简介"+"操作"标题，规则列表允许因卡片高度有限需要滚动，截图能看到部分即可） |

**风险提示（沿用本工作区已有记录）**：模拟器 `adb shell input tap` 对小尺寸角标按钮（71×32dp 的入口按钮、38×32dp 的关闭按钮）命中率存疑，如果 Gate B 阶段模拟器点不中，应转真机验证或明确记为"不可判定—待真机验证"，不得仅凭"应该点得中"就宣称已通过截图验证。

# risks

- SDK 6.0.0 的 Button 组件没有 style/variant 参数，也没有预设的『次要按钮』色板（已用 design-6.0.0-sources.jar 逐行核实，非猜测）；实现改为显式传 `colors=ButtonDefaults.buttonColors(containerColor=fillPrimary.copy(alpha=0.3f), contentColor=labelPrimaryLight)`，与"开始游戏"主按钮同一颜色角色、低透明度容器色，不假设存在某个 SecondaryButton 组件或 secondary 角色。
- `ButtonColors` 的构造函数是 `internal constructor`（design-6.0.0-sources.jar 已核实），应用代码不能直接 `ButtonColors(...)`，必须通过 `ButtonDefaults.buttonColors(...)` / `IconButtonDefaults.iconButtonColors(...)` 这两个公开 composable 工厂函数取色。
- `ButtonSize` 只能用四档预设（Max/Regular/Small/Min）之一，绝不能传自定义 `ButtonSize` 实例——本工作区已有踩坑记录『自定义 ButtonSize 会静默丢圆角』。本增量选用 `ButtonDefaults.Min`。
- 项目内完全没有图标资源、没有捆绑图标库，也不能引入 Material 图标；入口按钮和关闭按钮的『图标』用 `Text("?")`/`Text("×")` 占位。
- 宿主 `StartPanel` 的 `GlassPanel` 只有 360×280dp；弹层收窄到 340×248dp 塞进现有容器，没有改动 `AttachmentPanel`/`SpatialView` 的宿主尺寸。
- 覆盖层两条硬性指针规则（scrim 必须消费点击、卡片自身也必须消费点击避免误关闭穿透）均已实现（`howtoOverlayScrim`/`howtoPanelCard` 的 `pointerInput(Unit){ detectTapGestures {...} }`）。
- 模拟器指针注入对小尺寸角标按钮命中率存疑，Gate B 截图验证阶段如果 adb tap 点不中，应转真机验证或明确标注『不可判定』。

## 附录：弹层容器改为 BasicSheet（2026-08-27，真机摩尔纹修复）

用户真机反馈：`start_page` 的玩法弹层在真机上出现摩尔纹。根因是原实现把 scrim（`Box.matchParentSize().background(...)`）和 `GlassPanel`（`backgroundMaterial(Material.Regular)`）两个几乎共面的半透明/材质平面叠在同一个 `GlassPanel` Box 内——这是双目立体渲染下摩尔纹的经典触发条件（两个近似同深度的纹理化半透明面）。

**改动**：`HowtoOverlay` 从 `private fun BoxScope.HowtoOverlay(...)`（手写 scrim + `pointerInput{detectTapGestures}` 消费点击的 `GlassPanel`）改为 `private fun HowtoOverlay(onDismiss) = BasicSheet(onDismissRequest = onDismiss) { GlassPanel(340, 248) {...} }`，沿用本工作区 Overwinter 项目已验证的 `BasicSheet` 用法。`BasicSheet` 是 SDK 原生弹层容器，走 `SpatialDialogDelegate` 自己的窗口，原生处理点外关闭/点内不关闭，不再需要手写 scrim 和两处 `pointerInput`。`StartPanel` 里 `if (showHowto) HowtoOverlay(...)` 的调用点从 `GlassPanel` 内容 lambda 内部移到 `GlassPanel(...)` 闭合之后的同级语句（与 Overwinter 的调用约定一致）。

弹层内部视觉内容（标题、关闭按钮、简介/操作/规则文本）未改动，§2.2 的元素表在视觉规格上仍然有效；仅"容器类型"从"`GlassPanel` Box 的同级子项"变为"`BasicSheet` 独立窗口"。

**验证现状**：编译通过；4 轮模拟器 install/launch 均无崩溃、无 ANR，`start_page` 默认态（弹层未展开）截图与既有验证一致。弹层展开态未能在模拟器上截图确认——`adb shell input tap` 在窗口首次出现后的短暂时间窗口内始终未命中或未能稳定复现该窗口（尝试了 2s/4s/6s 等待与 `pm clear` 两种路径，均未能在弹层可见时刻同步截图），与本文件 §6 已预先记录的『模拟器角标按钮命中率存疑』风险一致。摩尔纹本身是真机专属的立体渲染现象，即使模拟器截图成功也无法证实/证伪，**这一项必须由用户在真机上复核**。
