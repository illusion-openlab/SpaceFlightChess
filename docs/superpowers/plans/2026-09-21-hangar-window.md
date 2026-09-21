# 机库窗口 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 SpaceFlightChess 的开局流程从「启动即全沉浸、在棋盘面板上点色块选阵营」改成「平面机库窗口里用 `SpatialModelView` 展示四架飞机选阵营 → 点开始游戏 `openStage` 进沉浸棋盘」。

**Architecture:** `Main.kt` 从单一 `DefaultStage` 改为「`DefaultWindowContainer`（机库窗口，Planar）+ 具名 `Stage`（棋盘）」双容器。阵营经 `openStage` 的 `Bundle` 传入 Stage，棋盘一建出来就是已就座状态，不再有 SETUP 交互阶段。退出与「返回机库」走 `restoreWindowContainer` → `closeStage`。

**Tech Stack:** Kotlin / Jetpack Compose / PICO Spatial SDK 6.0.0（`com.pico.spatial.ui.*`、`com.pico.spatial.core.*`）/ JUnit 4 / Gradle

设计文档：`docs/superpowers/specs/2026-09-21-hangar-window-design.md`

## Global Constraints

- 包名 `tech.illusion.spaceflightchess`；本次新增文件全部落在 `content` 包
- **所有 2D UI 必须用 SpatialUI（`com.pico.spatial.ui.*`）并包在 `PicoTheme` 里；禁止 `androidx.compose.material` / `material3` / `MaterialTheme`**
- `Source.assets(path)` —— **复数**，别写成 `Source.asset`
- `SpatialModelView` 的 `resizability` **API 默认值是 `None`**，必须显式传 `Resizability.FitInside` 才有「读 bbox 自动适配容器」
- `LoadedModel.entity` 是 `internal`：拿不到 Entity，模型不能转、不能换材质、不能播动画
- Planar 窗口深度**固定 640dp 不可改**，超出部分被系统截断
- 窗口 id 必须与 `AndroidManifest.xml` 的 `pico.spatial.windowcontainer.id` **逐字一致**；对不上不报错，只是 minimize/restore 静默失效
- `restoreWindowContainer` 必须在 `closeStage` **之前**调用，且 `closeStage` 必须在 `Dispatchers.Main.immediate` 上启动
- `openStage` / `closeStage` / `restoreWindowContainer` / `minimizeWindowContainer` 的返回值**全部记日志**——这几步失败在画面上和「什么都没发生」完全一样
- `Stage()` DSL 没有 style 参数，style 只能在 `openStage(style = ...)` 时给
- 单元测试用 JUnit 4（`org.junit.Test` / `org.junit.Assert.*`），测试名用反引号中文/英文短句，与 `app/src/test/java/tech/illusion/spaceflightchess/game/` 现有风格一致
- **不要写读取源码树文件的测试**：`File` 读的文件不在 Gradle 依赖图里，只改那些文件时测试会静默 up-to-date 跳过

## File Structure

| 文件 | 职责 | 任务 |
| --- | --- | --- |
| `content/PlaneAssets.kt`（新建） | 四个 `.usdz` 的固有属性：文件名映射 + 实测机头 yaw 偏移。渲染器与机库窗口共用 | 1 |
| `content/PlanePieceRenderer.kt`（改） | 删掉自己的两份 private 副本，改用 `PlaneAssets` | 1 |
| `content/Containers.kt`（新建） | 容器 id 常量、阵营 bundle key、纯函数 `teamFromBundleValue` | 2 |
| `content/HangarWindow.kt`（新建） | 机库窗口全部 UI：四架飞机选机、玩法弹层、开始游戏 | 3 |
| `Main.kt`（改） | 容器声明：`DefaultWindowContainer` + `Stage` | 3、5 |
| `AndroidManifest.xml`（改） | stage meta-data → windowcontainer meta-data | 3 |
| `content/Panels.kt`（改） | `labelFor`/`swatchFor` 去 private 供窗口复用；`HowtoOverlay`/`HowtoBadge` 迁出；`ResultPanel` 加「返回机库」；删 `StartPanel`/`FactionSwatch` | 3、4、5 |
| `content/BoardStage.kt`（改） | 接 bundle、删 StartPanel 与就座动画、退出改回机库 | 4、5 |

> **对设计文档的一处修正：** spec §3.3 #2 写的是把 `labelFor` 移到窗口侧，但 `ResultPanel` 也在用它。改为：`labelFor` 与 `swatchFor` **留在 `Panels.kt`，仅去掉 `private`**（同包 `content` 下 `HangarWindow.kt` 可直接引用），只迁 `HowtoOverlay` 与 `HowtoBadge`。

---

### Task 1: 抽出共享的飞机资产元数据

把 `PlanePieceRenderer` 里两个 private 的按队常量提到中立位置——机库窗口是它们的第二个消费方。纯重构，不改任何行为。

**Files:**
- Create: `app/src/main/java/tech/illusion/spaceflightchess/content/PlaneAssets.kt`
- Modify: `app/src/main/java/tech/illusion/spaceflightchess/content/PlanePieceRenderer.kt`
- Test: `app/src/test/java/tech/illusion/spaceflightchess/content/PlaneAssetsTest.kt`

**Interfaces:**
- Consumes: `tech.illusion.spaceflightchess.game.Team`（既有 enum，四个值 `RED`/`YELLOW`/`BLUE`/`GREEN`）
- Produces:
  - `internal fun assetFileFor(team: Team): String` —— 返回 `"red_plane.usdz"` 这类裸文件名（不含 `models/` 前缀、不含 `asset://` scheme）
  - `internal val MODEL_YAW_OFFSET_DEG: Map<Team, Float>` —— 四个键齐全

- [ ] **Step 1: 写失败的测试**

创建 `app/src/test/java/tech/illusion/spaceflightchess/content/PlaneAssetsTest.kt`：

```kotlin
package tech.illusion.spaceflightchess.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.illusion.spaceflightchess.game.Team

class PlaneAssetsTest {

    @Test
    fun `每个阵营都有自己的模型文件，互不重复`() {
        val files = Team.entries.map { assetFileFor(it) }
        assertEquals(Team.entries.size, files.toSet().size)
        assertTrue(files.all { it.endsWith(".usdz") })
    }

    @Test
    fun `文件名是裸名，不带目录前缀也不带 scheme`() {
        Team.entries.forEach { team ->
            val file = assetFileFor(team)
            assertTrue("$team -> $file 不应含路径分隔符", !file.contains("/"))
            assertTrue("$team -> $file 不应含 scheme", !file.contains(":"))
        }
    }

    @Test
    fun `机头 yaw 偏移表覆盖全部四个阵营`() {
        assertEquals(Team.entries.toSet(), MODEL_YAW_OFFSET_DEG.keys)
    }

    @Test
    fun `实测值：红绿机头朝 +Z 无需偏移，蓝黄朝 -X 需要 90 度`() {
        assertEquals(0f, MODEL_YAW_OFFSET_DEG.getValue(Team.RED))
        assertEquals(0f, MODEL_YAW_OFFSET_DEG.getValue(Team.GREEN))
        assertEquals(90f, MODEL_YAW_OFFSET_DEG.getValue(Team.BLUE))
        assertEquals(90f, MODEL_YAW_OFFSET_DEG.getValue(Team.YELLOW))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
./gradlew testDebugUnitTest --tests "tech.illusion.spaceflightchess.content.PlaneAssetsTest"
```

预期：编译失败，`Unresolved reference: assetFileFor` / `MODEL_YAW_OFFSET_DEG`。

- [ ] **Step 3: 创建 `PlaneAssets.kt`**

从 `PlanePieceRenderer.kt` 剪切两段（**连注释原样搬过来**——那段 KDoc 是这些数字唯一的依据）。新文件内容：

```kotlin
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
```

- [ ] **Step 4: 从 `PlanePieceRenderer.kt` 删掉两份副本**

删除该文件里的 `private fun assetFileFor(team: Team): String = when (team) { ... }` 整段（约在第 733 行），以及 `private val MODEL_YAW_OFFSET_DEG: Map<Team, Float> = mapOf(...)` 连同它上方那段 KDoc（约在第 300–318 行，KDoc 已搬到新文件）。

调用点不用改：两个符号同包同名，去掉 `private` 后解析到新文件。**如果编译器报未解析**，说明删过头了——检查是否连调用点一起删掉了。

- [ ] **Step 5: 跑测试 + 编译确认通过**

```bash
./gradlew testDebugUnitTest --tests "tech.illusion.spaceflightchess.content.PlaneAssetsTest" && ./gradlew assembleDebug
```

预期：4 个测试 PASS，APK 构建成功。

- [ ] **Step 6: 跑全量单测确认无回归**

```bash
./gradlew testDebugUnitTest
```

预期：全绿（既有 56+ 个 `game/` 测试不受影响）。

- [ ] **Step 7: 提交**

```bash
git add app/src/main/java/tech/illusion/spaceflightchess/content/PlaneAssets.kt \
        app/src/main/java/tech/illusion/spaceflightchess/content/PlanePieceRenderer.kt \
        app/src/test/java/tech/illusion/spaceflightchess/content/PlaneAssetsTest.kt
git commit -m "refactor: 把飞机资产元数据抽到 PlaneAssets，供机库窗口复用"
```

---

### Task 2: 容器常量与阵营 bundle 解析

纯 Kotlin，不接线，不改行为。单独成任务是因为它有自己的测试周期，且后面两个任务都依赖它。

**Files:**
- Create: `app/src/main/java/tech/illusion/spaceflightchess/content/Containers.kt`
- Test: `app/src/test/java/tech/illusion/spaceflightchess/content/ContainersTest.kt`

**Interfaces:**
- Consumes: `tech.illusion.spaceflightchess.game.Team`
- Produces:
  - `const val HANGAR_WINDOW_ID: String = "SpaceFlightChessHangarWindow"`
  - `const val BOARD_STAGE_ID: String = "SpaceFlightChessBoardStage"`
  - `const val TEAM_BUNDLE_KEY: String = "humanTeam"`
  - `fun teamFromBundleValue(value: String?): Team` —— 解析失败一律退回 `Team.RED`

> **为什么解析函数收 `String?` 而不是 `Bundle?`**：`Bundle` 是 Android 类，JVM 单测里拿不到。调用方自己做 `bundle?.getString(TEAM_BUNDLE_KEY)`，把纯逻辑留在可测的这一侧。`Containers.kt` **不要 import 任何 `android.*`**。

- [ ] **Step 1: 写失败的测试**

创建 `app/src/test/java/tech/illusion/spaceflightchess/content/ContainersTest.kt`：

```kotlin
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
```

- [ ] **Step 2: 跑测试确认失败**

```bash
./gradlew testDebugUnitTest --tests "tech.illusion.spaceflightchess.content.ContainersTest"
```

预期：编译失败，`Unresolved reference: teamFromBundleValue`。

- [ ] **Step 3: 创建 `Containers.kt`**

```kotlin
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
```

- [ ] **Step 4: 跑测试确认通过**

```bash
./gradlew testDebugUnitTest --tests "tech.illusion.spaceflightchess.content.ContainersTest"
```

预期：4 个测试 PASS。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/tech/illusion/spaceflightchess/content/Containers.kt \
        app/src/test/java/tech/illusion/spaceflightchess/content/ContainersTest.kt
git commit -m "feat: 机库窗口/棋盘 Stage 的容器常量与阵营 bundle 解析"
```

---

### Task 3: 机库窗口 + 双容器架构

本任务结束时应用启动进入平面机库窗口，点「开始游戏」能进棋盘。**这是唯一未知项 `rotate3D` 的验证点，刻意前置。**

此时 `BoardStage` 还没消费 bundle，进去仍会看到原来的 `StartPanel` 再选一次阵营——这是合法的中间态，Task 5 才拆掉。

**Files:**
- Create: `app/src/main/java/tech/illusion/spaceflightchess/content/HangarWindow.kt`
- Modify: `app/src/main/java/tech/illusion/spaceflightchess/Main.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/tech/illusion/spaceflightchess/content/Panels.kt`（`labelFor`/`swatchFor` 去 private；`HowtoOverlay`/`HowtoBadge` 迁出）

**Interfaces:**
- Consumes: Task 1 的 `assetFileFor(team)` 与 `MODEL_YAW_OFFSET_DEG`；Task 2 的 `HANGAR_WINDOW_ID` / `BOARD_STAGE_ID` / `TEAM_BUNDLE_KEY`；`Panels.kt` 既有的 `labelFor(team)` 与 `swatchFor(team)`
- Produces: `@Composable fun HangarWindow()` —— 无参数，自己从 `LocalSpatialNavigator` 取导航器

- [ ] **Step 1: 把 `Panels.kt` 的两个按队辅助函数去 private**

在 `app/src/main/java/tech/illusion/spaceflightchess/content/Panels.kt` 里把这两行的 `private` 去掉（`HangarWindow.kt` 同包，去掉即可引用）：

```kotlin
// 改前
private fun swatchFor(team: Team): Color = when (team) {
private fun labelFor(team: Team): String = when (team) {

// 改后
internal fun swatchFor(team: Team): Color = when (team) {
internal fun labelFor(team: Team): String = when (team) {
```

两个函数体、注释都不动。`ResultPanel` 仍在用 `labelFor`，所以它们**留在 `Panels.kt`**。

- [ ] **Step 2: 把 `HowtoOverlay` 与 `HowtoBadge` 从 `Panels.kt` 迁到 `HangarWindow.kt`**

这两个 composable 连同它们的 KDoc **原样剪切**（`HowtoOverlay` 那段解释「为什么是 BasicSheet 而不是手写 scrim」的注释是真机摩尔纹教训的唯一记录，必须保留），粘到下一步新建的 `HangarWindow.kt` 里，可见性保持 `private`。

`HowtoOverlay` 用到了 `GlassPanel`，而 `GlassPanel` 目前是 `Panels.kt` 的 private——把 `GlassPanel` 也改成 `internal`（同样只改可见性，函数体不动）。

- [ ] **Step 3: 创建 `HangarWindow.kt`**

```kotlin
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
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
```

紧接着把 Step 2 剪下来的 `HowtoOverlay` 与 `HowtoBadge` 两个 composable 原样粘在这个文件末尾。

**它们还需要下面这些 import**（上面的 import 块里没有，漏掉会编译失败）：

```kotlin
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.pico.spatial.ui.design.IconButton
import com.pico.spatial.ui.design.IconButtonDefaults
import com.pico.spatial.ui.design.windows.BasicSheet
```

粘完后回到 `Panels.kt`，把那边因为迁出而不再使用的 import 删掉（至少 `BasicSheet`、`IconButton`、`IconButtonDefaults`、`rememberScrollState`、`verticalScroll` 这几个——`fillMaxWidth` 是否还有别处在用要逐个确认）。**Kotlin 对未使用的 import 既不报 error 也不报 warning，编译通过证明不了 import 干净，只能手工核对。**

- [ ] **Step 4: 改 `Main.kt`**

整份文件替换为：

```kotlin
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
```

- [ ] **Step 5: 改 `AndroidManifest.xml`**

把 `.platform.LaunchActivity` 下那一整块 `<!-- DefaultStage Configuration -->` 到最后一条 `pico.spatial.stage.immersion_max` 的 meta-data（含中间所有 stage 注释）**整体替换**为：

```xml
            <!-- DefaultWindowContainer Configuration -->

            <!-- 必填：窗口唯一 id。必须与 content/Containers.kt 的 HANGAR_WINDOW_ID 逐字一致 —— 
                 minimizeWindowContainer / restoreWindowContainer 就是按这个字符串定位窗口的，
                 对不上不报错，只会静默什么都不发生。 -->
            <meta-data
                android:name="pico.spatial.windowcontainer.id"
                android:value="SpaceFlightChessHangarWindow" />

            <!-- 窗口形态：'1' = Planar，'2' = Volumetric。
                 选 Planar：机库是一块以 2D 排版为主的选择面板，四架飞机是点缀而非主体。官方文档
                 （spatial-design_foundation_window.md）明确 Planar 可以承载 3D 内容，但深度固定
                 640dp 不可改、超出部分被系统截断——MODEL_BOX_DP 就是按这个预算定的。 -->
            <meta-data
                android:name="pico.spatial.windowcontainer.style"
                android:value="1" />

            <!-- 宽x高，单位 dp。容纳标题 + 四个 180dp 格子（间距 20dp）+ 底部按钮行。
                 Planar 的深度固定 640dp，写不了也不用写。门禁 B 第 1 轮截图后可微调。 -->
            <meta-data
                android:name="pico.spatial.windowcontainer.defaultsize"
                android:value="1040x620" />

            <!-- 关掉系统毛玻璃底板：两个共面半透明层是真机摩尔纹的已知诱因。主窗口的毛玻璃
                 只能在 manifest 关，DSL 侧没有对应参数。 -->
            <meta-data
                android:name="pico.spatial.windowcontainer.materialbackground"
                android:value="0" />
```

`<activity>` 的其他属性、`<intent-filter>`、以及 `<application>` 层的三条 meta-data 都不动。

- [ ] **Step 6: 构建**

```bash
./gradlew assembleDebug
```

预期：BUILD SUCCESSFUL。若报 `Unresolved reference: rotate3D`，检查 import 是 `com.pico.spatial.ui.foundation.effect3d.rotate3D`，轴常量是 `com.pico.spatial.ui.foundation.geometry.RotationAxis3D.Y`。

- [ ] **Step 7: 门禁 B —— 上模拟器截图验证**

按 `spatial-design-first-build` 技能的门禁 B 流程：取设备锁 → 装包 → 启动 → 截图 → **立刻停应用并释放锁**。

判据（这一步是本计划唯一未知项的裁决点）：

| 观察 | 结论 |
| --- | --- |
| 四架飞机都渲染出来，蓝/黄机头朝向与红/绿一致 | `rotate3D` 作用于内嵌模型，**方案成立**，继续 Task 4 |
| 四架都出来但蓝/黄朝向反了（差 180°） | 方向对、符号错：把 `MODEL_YAW_OFFSET_DEG` 在窗口侧的用法改成取负（`-MODEL_YAW_OFFSET_DEG.getValue(team)`），**不要改 `PlaneAssets.kt` 的表**（棋盘侧在用同一张表且已验证正确），重截一次 |
| 整张卡片歪掉，或飞机跑到卡片外 | `rotate3D` 转的是面板不是模型 → 执行下面的回退 |
| 格子里是纯色圆点 + 「模型加载失败」 | 资产路径问题：核对 `Source.assets("models/…")` 不带 `asset://` 前缀，且 `app/src/main/assets/models/` 下四个文件都在 |
| 飞机被削平或明显切掉一截 | 撞上 Planar 640dp 深度上限：调小 `MODEL_BOX_DP` 后重截 |

**回退方案（仅当判定 `rotate3D` 转的是面板）：** 把 `PlaneTile` 里的 `SpatialModelView` 整体换成 `SpatialView` + ECS，照 `SpaceCube` 的 `app/src/main/java/tech/illusion/spacecube/content/ConfigPage.kt` 第 181–236 行的形态（`SpatialView(initial = { content, _ -> ... })` 里 `content.addEntity(root)`，自己 `Entity.loadSuspend(uriString = "asset://models/…")` 挂到 root 下，再给 root 的 `TransformComponent` 设 yaw）。四个格子**整体替换**，不做「两个用 A 两个用 B」的混合。回退属于同一个任务，完成后仍走本步骤的截图判据。

- [ ] **Step 8: 提交**

```bash
git add app/src/main/java/tech/illusion/spaceflightchess/content/HangarWindow.kt \
        app/src/main/java/tech/illusion/spaceflightchess/content/Panels.kt \
        app/src/main/java/tech/illusion/spaceflightchess/Main.kt \
        app/src/main/AndroidManifest.xml
git commit -m "feat: 平面机库窗口，用 SpatialModelView 展示四架飞机选阵营"
```

---

### Task 4: 退出与返回机库，修掉「再来一局」

先落退出路径再删 StartPanel，顺序刻意如此：`GameEngine.restart()` 会把 phase 打回 `SETUP`，如果先删 StartPanel 再修 restart，中间会出现「再来一局后卡在无 UI 的 SETUP」这个坏掉的中间态。

**Files:**
- Modify: `app/src/main/java/tech/illusion/spaceflightchess/content/BoardStage.kt`
- Modify: `app/src/main/java/tech/illusion/spaceflightchess/content/Panels.kt`
- Test: `app/src/test/java/tech/illusion/spaceflightchess/game/GameEngineTest.kt`（追加）

**Interfaces:**
- Consumes: Task 2 的 `HANGAR_WINDOW_ID`
- Produces: `ResultPanel(ranking: List<Team>, humanTeam: Team, onPlayAgain: () -> Unit, onBackToHangar: () -> Unit)` —— **签名新增第 4 个参数**

- [ ] **Step 1: 写失败的测试**

在 `app/src/test/java/tech/illusion/spaceflightchess/game/GameEngineTest.kt` 末尾（类的右花括号之前）追加：

```kotlin
    @Test
    fun `restart 之后立刻 startGame 能回到可摇骰的状态`() {
        val engine = GameEngine()
        engine.startGame()
        engine.restart()
        assertEquals(Phase.SETUP, engine.phase)

        engine.startGame()
        assertEquals(Phase.AWAITING_ROLL, engine.phase)
        assertEquals(GameState.newGame().currentTeam, engine.state.currentTeam)
    }

    @Test
    fun `restart 单独调用会停在 SETUP —— 这就是机库窗口取代 StartPanel 后必须补 startGame 的原因`() {
        val engine = GameEngine()
        engine.startGame()
        engine.restart()
        assertEquals(Phase.SETUP, engine.phase)
    }
```

若该测试文件顶部尚未 import `Phase` / `GameState`，补上（同包，通常无需 import；编译器报错时再加）。

- [ ] **Step 2: 跑测试确认失败**

```bash
./gradlew testDebugUnitTest --tests "tech.illusion.spaceflightchess.game.GameEngineTest"
```

预期：两个新测试里至少第一个 FAIL 或编译失败。**如果两个都直接 PASS**，说明 `GameEngine` 的语义已经符合预期——这是可能的（`startGame()` 本来就只在 `SETUP` 下生效）。那就把它们当作固化这一行为的回归测试，直接进入 Step 3，并在提交信息里说明。

- [ ] **Step 3: 给 `ResultPanel` 加「返回机库」按钮**

在 `Panels.kt` 里把 `ResultPanel` 改为：

```kotlin
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
```

- [ ] **Step 4: 在 `BoardStage` 里加 `returnToHangar()` 并接到两个出口**

在 `BoardStage.kt` 顶部 import 区补上：

```kotlin
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import kotlinx.coroutines.Dispatchers
```

在 `fun BoardStage()` 体内、`val coroutineScope = rememberCoroutineScope()` 那一行下面加：

```kotlin
    val navigator = LocalSpatialNavigator.current
```

在 `fun startWithFaction(team: Team)` 定义之前插入：

```kotlin
    /**
     * 回到机库窗口的唯一出口。顺序不可交换：`restoreWindowContainer` 的 KDoc 写着「can only be
     * used when there is a stage open」，先 `closeStage` 的话，被最小化的机库窗口就再也没有 API
     * 能叫回来了。
     *
     * 必须 `Dispatchers.Main.immediate`（不是 `coroutineScope.launch` 的默认调度）：
     * `restoreWindowContainer` 有可能当场把这份组合拆掉，而 `rememberCoroutineScope()` 的 scope
     * 随组合一起取消——默认调度下这个 launch 只是「排队等下一次派发」，排在取消后面就永远不会跑，
     * `closeStage()` 和它的日志一起消失，画面上跟「什么都没发生」完全一样；真实后果是漏掉一个
     * 全沉浸 Stage，下次 `openStage` 直接 `NotAllowed`。
     *
     * 两个返回值都记日志：这两步失败在画面上和「什么都没发生」一模一样，截图判不出来。
     */
    fun returnToHangar() {
        val restored = navigator.restoreWindowContainer(HANGAR_WINDOW_ID)
        Log.i(TAG, "restoreWindowContainer($HANGAR_WINDOW_ID) -> $restored")
        coroutineScope.launch(Dispatchers.Main.immediate) {
            navigator.closeStage()
            Log.i(TAG, "closeStage() returned")
        }
    }
```

然后把两个 `AttachmentPanel` 的回调改掉：

```kotlin
            AttachmentPanel(id = RESULT_PANEL) {
                if (phase == Phase.GAME_OVER && !showExitConfirm) {
                    ResultPanel(
                        ranking = ranking,
                        humanTeam = humanTeam,
                        onPlayAgain = {
                            engine.restart()
                            // restart() 把 phase 打回 SETUP，而选阵营的 StartPanel 已经搬去机库
                            // 窗口了——不立刻 startGame 的话，游戏会卡死在一个没有任何 UI 的
                            // SETUP 阶段。
                            engine.startGame()
                            publish()
                            // 重开后轮到谁变了，把骰子挪回那一队的空位（与 syncDieSlot 的滑行
                            // 不同，这里要的是立刻就位）。
                            dieSlotTeam = engine.state.currentTeam
                            dieRenderer.setRestPosition(dieSlotFor(engine.state.currentTeam))
                        },
                        onBackToHangar = ::returnToHangar,
                    )
                }
            }
            AttachmentPanel(id = EXIT_CONFIRM_PANEL) {
                if (showExitConfirm) {
                    ExitConfirmPanel(
                        onCancel = { showExitConfirm = false },
                        onExit = ::returnToHangar,
                    )
                }
            }
```

`Panels.kt` 里 `ExitConfirmPanel` 的文案「退出游戏？/当前进度将不会保存/退出」保持不变——回机库确实会丢掉当前进度，语义仍然成立。

- [ ] **Step 5: 跑测试 + 构建**

```bash
./gradlew testDebugUnitTest && ./gradlew assembleDebug
```

预期：全部测试 PASS，BUILD SUCCESSFUL。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/tech/illusion/spaceflightchess/content/BoardStage.kt \
        app/src/main/java/tech/illusion/spaceflightchess/content/Panels.kt \
        app/src/test/java/tech/illusion/spaceflightchess/game/GameEngineTest.kt
git commit -m "feat: 退出与结算都回到机库窗口，再来一局不再停在 SETUP"
```

---

### Task 5: BoardStage 消费 bundle，拆掉 StartPanel 与就座动画

**Files:**
- Modify: `app/src/main/java/tech/illusion/spaceflightchess/content/BoardStage.kt`
- Modify: `app/src/main/java/tech/illusion/spaceflightchess/Main.kt`
- Modify: `app/src/main/java/tech/illusion/spaceflightchess/content/Panels.kt`

**Interfaces:**
- Consumes: Task 2 的 `TEAM_BUNDLE_KEY` 与 `teamFromBundleValue(String?)`
- Produces: `@Composable fun BoardStage(bundle: Bundle?)` —— **签名从无参改为收一个 `Bundle?`**

- [ ] **Step 1: 改 `BoardStage` 签名并解析阵营**

在 `BoardStage.kt` 顶部 import 区补上 `import android.os.Bundle`。

把 `fun BoardStage() {` 改成：

```kotlin
fun BoardStage(bundle: Bundle?) {
```

把 `var humanTeam by remember { mutableStateOf(Team.RED) }` 改成：

```kotlin
    // 阵营由机库窗口经 openStage 的 Bundle 送进来。解析失败退回红方——见 teamFromBundleValue 的
    // KDoc：坐错席位远好过棋盘整个建不出来。
    var humanTeam by remember { mutableStateOf(teamFromBundleValue(bundle?.getString(TEAM_BUNDLE_KEY))) }
```

- [ ] **Step 2: 在 `initial` 里就座并开局**

在 `initial = { content, attachments ->` 的 `try {` 之后、`content.addEntity(rig)` 之前插入：

```kotlin
                // 阵营在进 Stage 之前就定了，所以这里直接把棋盘配置成已就座——不再有「棋盘转向
                // 你」的过渡动画。那段动画存在的唯一理由是掩盖「棋子瞬移到看起来一样但队伍不对的
                // 机库」，而现在渲染器还没 attach，根本没有可见的中间状态需要掩盖。
                //
                // 必须在 boardRenderer / pieceRenderer 的 attachTo 之前：它们建实体时就会读
                // BoardGeometry 的席位相关几何。
                BoardGeometry.configureSeat(humanTeam)
                engine.startGame()
```

- [ ] **Step 3: 删掉 `startWithFaction`、`isSeating` 与就座动画常量**

在 `BoardStage.kt` 里删除：

1. `var isSeating by remember { mutableStateOf(false) }` 整行
2. `fun startWithFaction(team: Team) { ... }` 整个函数，**连同它上方那段以 `/**` 开头、讲 "Applies the player's faction choice" 的 KDoc**
3. 文件末尾的三行：

```kotlin
// ── seat-change animation (see `startWithFaction`) ──────────────────────────
private const val SEAT_ANIMATION_STEPS = 30
private const val SEAT_ANIMATION_FRAME_MS = 16L
```

**不要动** `AnimationMath.kt` 里的 `lerp` / `smoothStep` / `lerpAngleDegrees`——棋子行走动画还在用。

- [ ] **Step 4: 删掉 START_PANEL**

在 `BoardStage.kt` 里：

1. 删 `private const val START_PANEL = "start"` 整行
2. 删这一整块：

```kotlin
            AttachmentPanel(id = START_PANEL) {
                if (phase == Phase.SETUP && !showExitConfirm) {
                    StartPanel(onStart = ::startWithFaction)
                }
            }
```

3. 把 `PANEL_IDS` 改成（去掉 `START_PANEL`）：

```kotlin
private val PANEL_IDS = listOf(HUD_PANEL, RESULT_PANEL, EXIT_CONFIRM_PANEL)
```

4. 把 `PANEL_OFFSETS` 的**第一个元素删掉**——它是与 `PANEL_IDS` 按下标一一对应的（`initial` 里 `PANEL_IDS.forEachIndexed { index, id -> ... PANEL_OFFSETS[index] }`），少删或多删都会让面板挂到错误的偏移上。删后应剩三项，且 HUD 那项（带「这个游戏面板应该与玩家对面的航道对齐」注释的 `Vector3(0f, 0.45f, -0.35f)`）排在第一：

```kotlin
private val PANEL_OFFSETS = listOf(
    // Centred, like the other two. It used to sit at x = +0.42, which put the turn readout 42cm off to
    // the player's right instead of over the lane facing them: 「这个游戏面板应该与玩家对面的航道对齐」。
    // The panels are children of the rig, whose local +X is the seated player's right, so x = 0 is the
    // player's own centre line — and because the board is square and the rig is aligned to the seat,
    // that line runs straight up the middle of the lane opposite them.
    Vector3(0f, 0.45f, -0.35f),
    Vector3(0f, 0.55f, -0.35f),
    // Exit-confirm shares the same centred anchor as result — the two are mutually exclusive
    // by their own visibility conditions, so there is never a clash.
    Vector3(0f, 0.55f, -0.35f),
)
```

**保留** AI 驱动循环里的 `engine.phase == Phase.SETUP` 守卫和 HUD 的 `phase != Phase.SETUP` 条件：`restart()` 仍会瞬时经过 SETUP，这两处仍在保护那个窗口。

- [ ] **Step 5: 从 `Panels.kt` 删掉 `StartPanel` 与 `FactionSwatch`**

删除 `@Composable fun StartPanel(onStart: (Team) -> Unit) { ... }` 整个函数（连同 KDoc），以及 `@Composable private fun FactionSwatch(team: Team, isSelected: Boolean, onClick: () -> Unit) { ... }` 整个函数和它的单行 KDoc。

`swatchFor` **不要删**——`HangarWindow.kt` 的 `TileFallback` 在用。`labelFor` 也不要删——`ResultPanel` 在用。

删完后清理 `Panels.kt` 顶部因此不再使用的 import（很可能是 `androidx.compose.foundation.border` 与 `androidx.compose.foundation.shape.CircleShape`；`background` / `clickable` 是否还有别的用处要逐个确认）。**注意：Kotlin 对未使用的 import 不报 warning 也不报 error，编译通过证明不了 import 干净，只能手工核对每一个。**

- [ ] **Step 6: 改 `Main.kt` 把 bundle 传进去**

```kotlin
        Stage(id = BOARD_STAGE_ID) {
            // bundle 来自 StageScope（继承 SpatialContainerScope），承载 openStage 传来的阵营。
            PicoTheme { BoardStage(bundle) }
        }
```

- [ ] **Step 7: 构建 + 全量单测**

```bash
./gradlew assembleDebug && ./gradlew testDebugUnitTest
```

预期：BUILD SUCCESSFUL，全部测试 PASS。若报 `Unresolved reference: StartPanel`，说明 Step 4 的 AttachmentPanel 块没删干净。

- [ ] **Step 8: 门禁 B —— 完整链路截图验证**

取锁 → 装包 → 启动 → 依次验证 → **停应用 + 释放锁**。

| 环节 | 判据 |
| --- | --- |
| 启动 | 进入机库窗口，四架飞机可见 |
| 选一个**非红**阵营（如蓝方）后点开始游戏 | logcat 出现 `openStage(SpaceFlightChessBoardStage) -> Allowed` 与 `minimizeWindowContainer(...) -> ...` |
| 棋盘出现 | 一进去就是对局态（没有选阵营面板），且**就座于蓝方**——这一条验证 bundle 真的传到了，红方是解析失败时的退回值，用红方测等于没测 |
| 按返回键 → 退出确认 → 退出 | logcat 出现 `restoreWindowContainer(...) -> ...` **和** `closeStage() returned` 两行，机库窗口重新可见 |

日志用 `adb logcat -a`（**必须带 `-a`**：一个控制字节就会让 grep 把 logcat 当二进制静默跳过，零输出会被误判成「应用没打日志」）。

- [ ] **Step 9: 提交**

```bash
git add app/src/main/java/tech/illusion/spaceflightchess/content/BoardStage.kt \
        app/src/main/java/tech/illusion/spaceflightchess/content/Panels.kt \
        app/src/main/java/tech/illusion/spaceflightchess/Main.kt
git commit -m "feat: 棋盘从 bundle 取阵营，拆掉 StartPanel 与就座动画"
```

- [ ] **Step 10: 更新项目 `AGENTS.md`**

在 `AGENTS.md` 的「当前状态」一节记录：入口已改为平面机库窗口（`HangarWindow`），阵营经 `openStage` 的 Bundle 传入棋盘 Stage，`StartPanel` 与就座动画已移除；并写明 `rotate3D` 对 `SpatialModelView` 的实测结论（Task 3 Step 7 得到的那一条）。

```bash
git add AGENTS.md
git commit -m "docs: AGENTS.md 记录机库窗口入口与 rotate3D 实测结论"
```

---

## 未决与风险

| 项 | 状态 |
| --- | --- |
| `rotate3D` 对 `SpatialModelView` 内嵌 3D 内容的语义 | **Task 3 Step 7 裁决**，回退方案已写在同一步 |
| 四模型同屏的加载耗时/卡顿（官方建议单模型 ~1500 面、1024² 贴图；红机 1.6MB 最大） | Task 3 Step 7 顺带观察；真出问题时第一嫌疑是红机资产 |
| `defaultsize` 1040x620 是估值 | Task 3 Step 7 截图后微调 |
| `MODEL_BOX_DP` 132 是按 640dp 深度预算估的 | Task 3 Step 7 截图后微调 |
| 局域网联机入口 | 本计划不涉及；`net/` 在 `feat/lan-multiplayer` 分支上 |
