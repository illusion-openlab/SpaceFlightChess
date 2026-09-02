# 局域网联机对战（含 AI 替补）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 SpaceFlightChess 支持同一局域网内最多 4 台 PICO 设备同场对局，空缺座位与掉线玩家由现有 `PlaneAi` 替补。

**Architecture:** 房主权威 + 事件广播 + 各端确定性重放 + 每步校验和。房主持有真骰子并裁决全部请求，客户端只镜像广播、永不本地先行。`BoardStage` 里四个回合来源（本地点击 / AI driver / 网络广播）收敛成一条命令队列 + 一个串行执行器 + 一个 `isTurnBusy` 门禁。

**Tech Stack:** Kotlin、Android `NsdManager`（mDNS 服务发现）、`java.net.Socket`/`ServerSocket`（TCP 直连）、Kotlin 协程 `Channel`、PICO Spatial SDK + SpatialUI Compose、JUnit 4。**不新增任何 Gradle 依赖**——服务发现与网络全部用 Android/JDK 系统 API。

**设计文档：** `docs/superpowers/specs/2026-09-02-lan-multiplayer-design.md`（实现中遇到本计划未覆盖的判断，以设计文档为准）

## Global Constraints

- **`net/` 包零 Android 依赖。** 只能 import `tech.illusion.spaceflightchess.game.*` 和 Kotlin 标准库。任何 `android.*` import 都是错误——那会让它无法 JVM 单测。持有 `NsdManager`/`Socket` 的类一律放 `content/`。
- **SpatialUI 强制，Material 禁止。** 所有 2D UI 用 `com.pico.spatial.ui.*` 包在 `PicoTheme` 里。禁止 `androidx.compose.material`、`androidx.compose.material3`、`MaterialTheme`。颜色走 `PicoTheme.colorScheme.*`，字号走 `PicoTheme.typography.*`。
- **不改 `GameEngine` 的规则或既有公开 API。** 本计划只对它做两处加法：Task 5 给 `forceState` 加一个默认参数，Task 11 加一个只读属性 `consecutiveSixesForSync`。两处都不改动任何现有行为。
- **共享常量只有一份**（`net/RoomState.kt`）：`SERVICE_TYPE = "_spaceflightchess._tcp."`、`MATCH_PORT = 47780`、`MAX_CLIENTS = 3`、`RECONNECT_GRACE_S = 15`。禁止在任何别处重复这些字面量。
- **「第一个空位」一律指 `Team.entries` 的声明序**：`RED → YELLOW → BLUE → GREEN`。
- **测试用 JUnit 4**：`import org.junit.Test` + `org.junit.Assert.*`，测试方法名用反引号中文/英文描述，与 `WinDetectorTest` 等现有测试同风格。
- **日志一律 `Log.e(TAG, ...)`，TAG = `"SpaceFlightChess"`**（沿用本项目单 tag 约定，`adb logcat -s SpaceFlightChess:E` 一条命令捞全）。真机上 `screencap` 失败，日志是唯一取证手段。
- **构建/测试命令**（每次新开 Bash 调用都要重新 export，这两个变量不跨调用持续）：
  ```bash
  export PICO_HOME='/Users/zohar/Library/PICO/sdk'
  export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
  ./gradlew testDebugUnitTest
  ./gradlew assembleDebug
  ```
- **每个任务结束都要跑全量单测**（现有 85+ 个必须保持全绿），不只跑新加的那几个。
- 工作目录为 `SpaceFlightChess/`（本仓库根），所有路径相对于它。

---

## 文件结构

新增：

| 文件 | 职责 |
|---|---|
| `app/src/main/java/tech/illusion/spaceflightchess/net/RoomState.kt` | 座位模型（`Occupant`/`Presence`/`RoomState`）、共享常量、`EndReason`、`BattleFlowState`、座位表编解码 |
| `app/src/main/java/tech/illusion/spaceflightchess/net/StateChecksum.kt` | `GameState` → FNV-1a 校验和 |
| `app/src/main/java/tech/illusion/spaceflightchess/net/RelayDice.kt` | `DiceEngine` 子类，可被强制成指定点数 |
| `app/src/main/java/tech/illusion/spaceflightchess/net/SeatAssigner.kt` | 落座 / 换座 / 离座 / 开局固化 / 状态变更，全纯函数 |
| `app/src/main/java/tech/illusion/spaceflightchess/net/MatchMessage.kt` | 线协议编解码 |
| `app/src/main/java/tech/illusion/spaceflightchess/net/TurnCommand.kt` | 回合命令三件套 |
| `app/src/main/java/tech/illusion/spaceflightchess/content/LanMatchHost.kt` | NSD 注册 + `ServerSocket` + 至多 3 条连接 + 广播 |
| `app/src/main/java/tech/illusion/spaceflightchess/content/LanMatchClient.kt` | NSD 发现 + `Socket` + 请求发送 + 重连重试 |
| `app/src/main/java/tech/illusion/spaceflightchess/content/LobbyPanels.kt` | 大厅 / 房间列表 / 数字键盘三个面板 |
| `tools/lan_peer.py` | Mac 侧陪练脚本，扮演对端 |

测试：`RoomStateTest`、`StateChecksumTest`、`RelayDiceTest`、`SeatAssignerTest`、`MatchMessageTest`、`MirrorReplayTest`（全部在 `app/src/test/java/tech/illusion/spaceflightchess/net/`，`MirrorReplayTest` 放 `game/` 因为它 fork 了 `SelfPlay`）。

修改：`BoardStage.kt`、`Panels.kt`、`GameEngine.kt`（一行）、`AndroidManifest.xml`、`AGENTS.md`、`.spatialsdk/design-contract.md`。

---

### Task 1: 确定性重放的两块基石（`StateChecksum` + `RelayDice`）

整套设计成立的前提是「同一串骰子点数 + 同一串走子下标 ⇒ 同一个局面」。这个任务造出验证它所需的两件工具：一个能把局面压成一个整数的校验和，和一个能被外部喂点数的骰子。

**Files:**
- Create: `app/src/main/java/tech/illusion/spaceflightchess/net/StateChecksum.kt`
- Create: `app/src/main/java/tech/illusion/spaceflightchess/net/RelayDice.kt`
- Test: `app/src/test/java/tech/illusion/spaceflightchess/net/StateChecksumTest.kt`
- Test: `app/src/test/java/tech/illusion/spaceflightchess/net/RelayDiceTest.kt`

**Interfaces:**
- Consumes: `GameState`、`PieceState`、`Team`、`DiceEngine`（均已存在）
- Produces:
  - `StateChecksum.of(state: GameState): Int`
  - `class RelayDice(random: Random = Random.Default) : DiceEngine(random)`，可变属性 `var forced: Int?`

- [ ] **Step 1: 写失败的测试 —— StateChecksumTest**

创建 `app/src/test/java/tech/illusion/spaceflightchess/net/StateChecksumTest.kt`：

```kotlin
package tech.illusion.spaceflightchess.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import tech.illusion.spaceflightchess.game.GameState
import tech.illusion.spaceflightchess.game.PieceState
import tech.illusion.spaceflightchess.game.Team

class StateChecksumTest {

    @Test
    fun `同一个局面算出同一个值`() {
        assertEquals(StateChecksum.of(GameState.newGame()), StateChecksum.of(GameState.newGame()))
    }

    @Test
    fun `挪动任意一架飞机都会改变校验和`() {
        val base = GameState.newGame()
        val moved = base.withPiece(Team.BLUE, 2, PieceState.OnPath(7))
        assertNotEquals(StateChecksum.of(base), StateChecksum.of(moved))
    }

    @Test
    fun `相邻两格必须算出不同的值`() {
        val a = GameState.newGame().withPiece(Team.RED, 0, PieceState.OnPath(7))
        val b = GameState.newGame().withPiece(Team.RED, 0, PieceState.OnPath(8))
        assertNotEquals(StateChecksum.of(a), StateChecksum.of(b))
    }

    @Test
    fun `当前队伍不同就算棋子完全一样也要不同`() {
        val red = GameState.newGame(currentTeam = Team.RED)
        val blue = GameState.newGame(currentTeam = Team.BLUE)
        assertNotEquals(StateChecksum.of(red), StateChecksum.of(blue))
    }

    @Test
    fun `同一队里两架飞机换位置会改变校验和`() {
        // 下标顺序必须参与计算：否则「0 号在 3 格、1 号在 9 格」和反过来会撞成同一个值，
        // 而这两个局面在僚机判定和动画归属上是不同的。
        val a = GameState.newGame()
            .withPiece(Team.GREEN, 0, PieceState.OnPath(3))
            .withPiece(Team.GREEN, 1, PieceState.OnPath(9))
        val b = GameState.newGame()
            .withPiece(Team.GREEN, 0, PieceState.OnPath(9))
            .withPiece(Team.GREEN, 1, PieceState.OnPath(3))
        assertNotEquals(StateChecksum.of(a), StateChecksum.of(b))
    }

    @Test
    fun `待飞与在机库是不同的状态`() {
        val hangar = GameState.newGame()
        val standby = hangar.withPiece(Team.RED, 0, PieceState.OnPath(0))
        assertNotEquals(StateChecksum.of(hangar), StateChecksum.of(standby))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
./gradlew testDebugUnitTest --tests '*StateChecksumTest*'
```

预期：编译失败，`Unresolved reference: StateChecksum`。

- [ ] **Step 3: 实现 StateChecksum**

创建 `app/src/main/java/tech/illusion/spaceflightchess/net/StateChecksum.kt`：

```kotlin
package tech.illusion.spaceflightchess.net

import tech.illusion.spaceflightchess.game.GameState
import tech.illusion.spaceflightchess.game.PieceState
import tech.illusion.spaceflightchess.game.Team

/**
 * 把一个 [GameState] 压成一个整数，用来在联机对局里发现「四端已经分叉」。
 *
 * 房主每广播一步棋就附带一个本地算出的值，客户端 apply 完自己算一遍对比；对不上就请求全量 SYNC。
 *
 * **刻意不用 `GameState.hashCode()`。** data class 自动生成的 hashCode 没有跨版本的稳定性承诺，
 * 而这个值要在两台设备之间比较；更要紧的是，改了 `GameState` 的字段顺序或加一个无关字段就会
 * 静默改变它的取值，而没有任何测试会因此变红。这里写死 FNV-1a，算法固定、可单测、改动必被发现。
 *
 * 参与计算的是：当前队伍，以及 16 架飞机按「队伍声明序 × 棋子下标」的固定顺序展开的位置。
 * 下标顺序是必须的——同队两架飞机交换位置是两个不同的局面（僚机判定与动画归属都不同）。
 */
object StateChecksum {

    /** 2166136261 的 Int 表示。 */
    private const val FNV_OFFSET_BASIS = -2128831035
    private const val FNV_PRIME = 16777619

    /** 机库不是格子，用一个任何 path 都取不到的负数表示，避免和 `OnPath(0)`（待飞）撞上。 */
    private const val IN_HANGAR_CODE = -1

    fun of(state: GameState): Int {
        var hash = FNV_OFFSET_BASIS
        fun mix(value: Int) {
            var remaining = value
            repeat(4) {
                hash = (hash xor (remaining and 0xFF)) * FNV_PRIME
                remaining = remaining ushr 8
            }
        }
        mix(state.currentTeam.ordinal)
        for (team in Team.entries) {
            for (piece in state.pieces(team)) {
                mix(
                    when (piece) {
                        is PieceState.InHangar -> IN_HANGAR_CODE
                        is PieceState.OnPath -> piece.value
                    },
                )
            }
        }
        return hash
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
./gradlew testDebugUnitTest --tests '*StateChecksumTest*'
```

预期：6 个测试全部 PASS。

- [ ] **Step 5: 写失败的测试 —— RelayDiceTest**

创建 `app/src/test/java/tech/illusion/spaceflightchess/net/RelayDiceTest.kt`：

```kotlin
package tech.illusion.spaceflightchess.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RelayDiceTest {

    @Test
    fun `没有强制值时表现得和普通骰子一样`() {
        val dice = RelayDice(Random(42))
        repeat(200) { assertTrue(dice.roll() in 1..6) }
    }

    @Test
    fun `强制值会被原样返回`() {
        val dice = RelayDice(Random(42))
        dice.forced = 6
        assertEquals(6, dice.roll())
    }

    @Test
    fun `强制值只生效一次之后回落到随机`() {
        // 这是客户端的核心用法：每收到一条 ROLLED 就喂一个值、摇一次。若强制值粘住不放，
        // 客户端之后每一次 roll 都会返回同一个点数，和房主立刻分叉。
        val dice = RelayDice(Random(42))
        dice.forced = 3
        assertEquals(3, dice.roll())
        val plain = RelayDice(Random(42))
        assertEquals(plain.roll(), dice.roll())
    }

    @Test
    fun `连续喂值每次都取到对应的那个`() {
        val dice = RelayDice(Random(1))
        listOf(6, 6, 2, 5, 1).forEach { fed ->
            dice.forced = fed
            assertEquals(fed, dice.roll())
        }
    }
}
```

- [ ] **Step 6: 跑测试确认失败**

```bash
export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
./gradlew testDebugUnitTest --tests '*RelayDiceTest*'
```

预期：编译失败，`Unresolved reference: RelayDice`。

- [ ] **Step 7: 实现 RelayDice**

创建 `app/src/main/java/tech/illusion/spaceflightchess/net/RelayDice.kt`：

```kotlin
package tech.illusion.spaceflightchess.net

import tech.illusion.spaceflightchess.game.DiceEngine
import kotlin.random.Random

/**
 * 一颗可以被外部指定下一次点数的骰子。
 *
 * 三种角色都用它构造 `GameEngine`，区别只在有没有喂值：
 *  - 单机 / 房主：[forced] 始终为 null，[roll] 落到真随机，房主再把摇出来的值广播出去。
 *  - 客户端：每收到一条 `ROLLED` 就把点数写进 [forced] 再调 `engine.roll()`，
 *    于是客户端的 engine 走的是和房主完全相同的一串输入。
 *
 * [forced] **取用即清空**是关键语义：客户端不能让某个点数粘住，否则之后每一次摇骰都返回同一个值，
 * 立刻和房主分叉。`RelayDiceTest` 专门钉住了这一条。
 *
 * `DiceEngine` 本来就是 `open` 且构造注入的（单测早就在用脚本化子类），所以这里不需要改动 `game/` 包。
 */
class RelayDice(random: Random = Random.Default) : DiceEngine(random) {

    /** 下一次 [roll] 要返回的点数；取用后自动清空。null 表示交给真随机。 */
    var forced: Int? = null

    override fun roll(): Int {
        val next = forced
        forced = null
        return next ?: super.roll()
    }
}
```

- [ ] **Step 8: 跑全量测试**

```bash
export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
./gradlew testDebugUnitTest
```

预期：现有 85+ 个测试 + 新增 10 个全部 PASS。

- [ ] **Step 9: 提交**

```bash
git add app/src/main/java/tech/illusion/spaceflightchess/net/ app/src/test/java/tech/illusion/spaceflightchess/net/
git commit -m "feat(net): 状态校验和与可中继骰子

联机确定性重放的两块基石：StateChecksum 把局面压成一个整数用于分叉检测，
RelayDice 让客户端用房主广播的点数驱动本地 engine。"
```

---

### Task 2: 镜像重放测试 —— 验证整套设计赖以成立的假设

这个任务不产出任何生产代码，只产出一个测试。它的价值在于：**如果「确定性重放」这条假设在某个规则分支上不成立，必须在这里被抓住，而不是在两台设备对着一局打了十分钟之后。** 如果这个测试红了，回头改设计文档，不要继续往下做。

**Files:**
- Create: `app/src/test/java/tech/illusion/spaceflightchess/game/MirrorReplayTest.kt`
- Read first: `app/src/test/java/tech/illusion/spaceflightchess/game/SelfPlay.kt`（本测试 fork 它的驱动循环）

**Interfaces:**
- Consumes: `StateChecksum.of`、`RelayDice`（Task 1）、`GameEngine`、`PlaneAi.chooseMove`
- Produces: 无生产代码。为后续任务确立「`MOVED` 只传代表棋子下标就够」这条结论的证据。

- [ ] **Step 1: 写测试**

创建 `app/src/test/java/tech/illusion/spaceflightchess/game/MirrorReplayTest.kt`：

```kotlin
package tech.illusion.spaceflightchess.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.illusion.spaceflightchess.net.RelayDice
import tech.illusion.spaceflightchess.net.StateChecksum
import kotlin.random.Random

/**
 * 在一个 JVM 进程里同时跑「房主」和「客户端」两个 [GameEngine]，验证联机设计的核心假设：
 *
 *   同一串骰子点数 + 同一串走子代表下标 ⇒ 两端必然推出同一个局面。
 *
 * 房主用真骰子跑一整局，每摇一次就把点数、每走一步就把代表棋子下标交给客户端 engine，
 * 客户端不做任何自己的判断。每一步之后比对 [StateChecksum]。
 *
 * 覆盖面来自随机自对弈本身：几百局里必然会打到摇 6 连击、三连 6 罚回机库、僚机整体移动、
 * 撞墙反弹、同色格跳跃、飞行捷径、归家小道精确点数。这些分支不需要手写用例去凑。
 *
 * 这个测试红了意味着设计前提不成立（协议要改成传完整 Move 或全量状态），不要靠改测试绕过去。
 */
class MirrorReplayTest {

    /** 一局对弈里房主发出的一条广播。就是线协议 ROLLED / MOVED 两条消息的纯数据形态。 */
    private sealed interface Broadcast {
        data class Rolled(val team: Team, val value: Int) : Broadcast
        data class Moved(val team: Team, val pieceIndex: Int, val checksum: Int) : Broadcast
    }

    private class Mirror {
        val dice = RelayDice(Random(0))
        val engine = GameEngine(dice)

        init {
            engine.startGame()
        }

        /** 客户端侧：完全按房主的广播驱动，自己不做任何选择。 */
        fun consume(broadcast: Broadcast) {
            when (broadcast) {
                is Broadcast.Rolled -> {
                    dice.forced = broadcast.value
                    engine.roll()
                    engine.drainEvents()
                }
                is Broadcast.Moved -> {
                    val move = engine.legalMovesForPendingRoll()
                        .firstOrNull { it.team == broadcast.team && it.pieceIndex == broadcast.pieceIndex }
                    assertTrue(
                        "客户端在候选里找不到房主选的那一步：team=${broadcast.team} " +
                            "pieceIndex=${broadcast.pieceIndex} 候选=${engine.legalMovesForPendingRoll()}",
                        move != null,
                    )
                    engine.applyMove(move!!)
                    engine.drainEvents()
                }
            }
        }
    }

    private fun playOneMirroredGame(seed: Int) {
        val host = GameEngine(DiceEngine(Random(seed)))
        host.startGame()
        val client = Mirror()
        var rolls = 0

        while (!host.isOver && rolls < SelfPlay.ROLL_CAP) {
            when (host.phase) {
                Phase.AWAITING_ROLL -> {
                    val team = host.state.currentTeam
                    val value = host.roll() ?: break
                    rolls++
                    host.drainEvents()
                    client.consume(Broadcast.Rolled(team, value))
                    assertEquals(
                        "摇骰后分叉：seed=$seed roll#$rolls team=$team value=$value",
                        StateChecksum.of(host.state),
                        StateChecksum.of(client.engine.state),
                    )
                    assertEquals("摇骰后 phase 不一致：seed=$seed", host.phase, client.engine.phase)
                }

                Phase.AWAITING_MOVE -> {
                    val moves = host.legalMovesForPendingRoll()
                    assertTrue("房主处于 AWAITING_MOVE 却没有候选：seed=$seed", moves.isNotEmpty())
                    val team = host.state.currentTeam
                    val chosen = PlaneAi.chooseMove(host.state, moves)
                    host.applyMove(chosen)
                    host.drainEvents()
                    client.consume(Broadcast.Moved(team, chosen.pieceIndex, StateChecksum.of(host.state)))
                    assertEquals(
                        "走子后分叉：seed=$seed team=$team pieceIndex=${chosen.pieceIndex}",
                        StateChecksum.of(host.state),
                        StateChecksum.of(client.engine.state),
                    )
                    assertEquals("走子后 phase 不一致：seed=$seed", host.phase, client.engine.phase)
                }

                else -> break
            }
        }

        assertEquals(
            "终局分叉：seed=$seed",
            StateChecksum.of(host.state),
            StateChecksum.of(client.engine.state),
        )
        assertEquals("终局排名不一致：seed=$seed", host.isOver, client.engine.isOver)
    }

    @Test
    fun `一局镜像重放全程不分叉`() {
        playOneMirroredGame(seed = 1)
    }

    @Test
    fun `三百局随机自对弈全程不分叉`() {
        (0 until 300).forEach { playOneMirroredGame(seed = it) }
    }

    @Test
    fun `代表棋子下标在候选列表里唯一`() {
        // 协议只传一个下标就能还原整个 Move，前提是候选列表里代表下标不重复。
        // 机库起飞 / 僚机叠放 / 单机三条分支的棋子集合互不重叠，这里把它钉死。
        var checked = 0
        for (seed in 0 until 100) {
            val engine = GameEngine(DiceEngine(Random(seed)))
            engine.startGame()
            var rolls = 0
            while (!engine.isOver && rolls < 400) {
                if (engine.phase == Phase.AWAITING_MOVE) {
                    val moves = engine.legalMovesForPendingRoll()
                    val indices = moves.map { it.pieceIndex }
                    assertEquals("候选里出现了重复的代表下标：seed=$seed moves=$moves", indices.size, indices.toSet().size)
                    checked++
                    engine.applyMove(PlaneAi.chooseMove(engine.state, moves))
                    engine.drainEvents()
                    continue
                }
                if (engine.phase != Phase.AWAITING_ROLL) break
                engine.roll() ?: break
                rolls++
                engine.drainEvents()
            }
        }
        assertTrue("没有真的检查到任何候选列表", checked > 1000)
    }
}
```

- [ ] **Step 2: 跑测试**

```bash
export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
./gradlew testDebugUnitTest --tests '*MirrorReplayTest*'
```

预期：3 个测试全部 PASS。

**如果任何一个失败：停下来，不要继续 Task 3。** 失败信息会指出是哪个 seed、哪一步、摇骰后还是走子后分叉。把那个 seed 单独拎出来复现，定位是 `legalMoves` 的顺序不稳定、还是某条规则依赖了 `GameEngine` 的内部计数器。定位结果要回写进设计文档的「同步模型的选型」一节，可能需要把协议从「传下标」改成「传完整 Move」。

- [ ] **Step 3: 跑全量测试**

```bash
export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
./gradlew testDebugUnitTest
```

预期：全绿。

- [ ] **Step 4: 提交**

```bash
git add app/src/test/java/tech/illusion/spaceflightchess/game/MirrorReplayTest.kt
git commit -m "test: 镜像重放验证确定性——300 局自对弈两端零分叉

钉住联机设计的核心假设：同一串点数+同一串代表下标必然推出同一局面，
以及代表棋子下标在候选列表里唯一（协议只传一个整数的前提）。"
```

---

### Task 3: 座位模型与共享常量（`RoomState`）

房间的全部状态：四个座位各自是谁、连接状况如何。这是协议、大厅 UI、AI 托管判定三方共用的词汇表，所以先定它。

**Files:**
- Create: `app/src/main/java/tech/illusion/spaceflightchess/net/RoomState.kt`
- Test: `app/src/test/java/tech/illusion/spaceflightchess/net/RoomStateTest.kt`

**Interfaces:**
- Consumes: `Team`
- Produces（后续任务全都依赖这些名字）：
  - `enum class Presence { ONLINE, DROPPED, AI_TAKEOVER }`
  - `sealed interface Occupant`：`Occupant.Empty`、`Occupant.Ai`、`Occupant.Human(name: String, isHost: Boolean, presence: Presence)`
  - `data class RoomState(seats: Map<Team, Occupant>)`，方法 `occupant(Team)`、`firstEmpty(): Team?`、`humanCount(): Int`、`isFull(): Boolean`、`isDrivenByAi(Team): Boolean`、`hostTeam(): Team?`、`withOccupant(Team, Occupant): RoomState`、`encode(): String`
  - `RoomState.Companion.empty()`、`RoomState.Companion.decode(String): RoomState?`、`RoomState.Companion.sanitizeName(String): String`
  - `sealed interface BattleFlowState`：`SinglePlayer`、`Discovering(rooms)`、`ManualEntry`、`Connecting`、`InLobby(room, myTeam, isHost, localAddress)`、`InMatch(room, myTeam, isHost)`、`Ended(reason)`
  - `data class DiscoveredRoom(id: String, displayName: String)`
  - `object EndReason`
  - 顶层常量 `SERVICE_TYPE`、`MATCH_PORT`、`MAX_CLIENTS`、`RECONNECT_GRACE_S`

- [ ] **Step 1: 写失败的测试**

创建 `app/src/test/java/tech/illusion/spaceflightchess/net/RoomStateTest.kt`：

```kotlin
package tech.illusion.spaceflightchess.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.illusion.spaceflightchess.game.Team

class RoomStateTest {

    private fun roomWithHost(): RoomState =
        RoomState.empty().withOccupant(Team.RED, Occupant.Human("Zohar", isHost = true, presence = Presence.ONLINE))

    @Test
    fun `空房间四个座位全是空的`() {
        val room = RoomState.empty()
        Team.entries.forEach { assertEquals(Occupant.Empty, room.occupant(it)) }
        assertEquals(0, room.humanCount())
        assertFalse(room.isFull())
    }

    @Test
    fun `第一个空位按 Team 声明序取`() {
        assertEquals(Team.RED, RoomState.empty().firstEmpty())
        assertEquals(Team.YELLOW, roomWithHost().firstEmpty())
    }

    @Test
    fun `座位全被占时没有第一个空位`() {
        var room = RoomState.empty()
        Team.entries.forEach { room = room.withOccupant(it, Occupant.Human("P$it", isHost = it == Team.RED, presence = Presence.ONLINE)) }
        assertNull(room.firstEmpty())
        assertTrue(room.isFull())
        assertEquals(4, room.humanCount())
    }

    @Test
    fun `AI 座位不计入真人数但也不是空位`() {
        val room = roomWithHost().withOccupant(Team.YELLOW, Occupant.Ai)
        assertEquals(1, room.humanCount())
        assertEquals(Team.BLUE, room.firstEmpty())
    }

    @Test
    fun `AI 驱动的座位包括固化的 AI 和托管中的真人`() {
        var room = roomWithHost().withOccupant(Team.YELLOW, Occupant.Ai)
        room = room.withOccupant(Team.BLUE, Occupant.Human("B", isHost = false, presence = Presence.AI_TAKEOVER))
        room = room.withOccupant(Team.GREEN, Occupant.Human("G", isHost = false, presence = Presence.DROPPED))

        assertFalse(room.isDrivenByAi(Team.RED))       // 在线真人
        assertTrue(room.isDrivenByAi(Team.YELLOW))     // 固化的 AI
        assertTrue(room.isDrivenByAi(Team.BLUE))       // 托管中
        // 掉线但还没超时的座位不由 AI 驱动——那是「等他重连」的窗口，不是托管。
        assertFalse(room.isDrivenByAi(Team.GREEN))
        // 空位在开局前也不由 AI 驱动，它要等 lockForStart 固化成 Ai 才算。
        assertFalse(RoomState.empty().isDrivenByAi(Team.RED))
    }

    @Test
    fun `hostTeam 找出房主坐哪`() {
        assertEquals(Team.RED, roomWithHost().hostTeam())
        assertNull(RoomState.empty().hostTeam())
    }

    @Test
    fun `编解码往返还原完整房间`() {
        var room = roomWithHost()
        room = room.withOccupant(Team.YELLOW, Occupant.Human("PICO-4821", isHost = false, presence = Presence.AI_TAKEOVER))
        room = room.withOccupant(Team.BLUE, Occupant.Ai)
        assertEquals(room, RoomState.decode(room.encode()))
    }

    @Test
    fun `空房间也能编解码往返`() {
        assertEquals(RoomState.empty(), RoomState.decode(RoomState.empty().encode()))
    }

    @Test
    fun `编码里不含空格——它必须是一个协议字段`() {
        val room = roomWithHost().withOccupant(Team.YELLOW, Occupant.Human("My Headset", isHost = false, presence = Presence.ONLINE))
        assertFalse(room.encode().contains(' '))
    }

    @Test
    fun `垃圾输入解码成 null 而不是抛异常`() {
        listOf("", "   ", "GARBAGE", "RED:X", "RED:H", "PURPLE:E|YELLOW:E|BLUE:E|GREEN:E", "RED:E|YELLOW:E")
            .forEach { assertNull("应该拒绝: $it", RoomState.decode(it)) }
    }

    @Test
    fun `名字里的分隔符会被替换掉`() {
        assertEquals("My_Headset", RoomState.sanitizeName("My Headset"))
        assertEquals("a_b_c", RoomState.sanitizeName("a|b:c"))
        assertEquals("PLAYER", RoomState.sanitizeName("   "))
        assertEquals(20, RoomState.sanitizeName("X".repeat(50)).length)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
./gradlew testDebugUnitTest --tests '*RoomStateTest*'
```

预期：编译失败，`Unresolved reference: RoomState`。

- [ ] **Step 3: 实现 RoomState.kt**

创建 `app/src/main/java/tech/illusion/spaceflightchess/net/RoomState.kt`：

```kotlin
package tech.illusion.spaceflightchess.net

import tech.illusion.spaceflightchess.game.Team

/** NSD 服务类型。房主注册它，客户端浏览它。 */
const val SERVICE_TYPE = "_spaceflightchess._tcp."

/**
 * 每个房主绑定、每次手动加入拨号的固定端口。
 *
 * 用固定端口而不是 `ServerSocket(0)` 的临时端口，只有一个理由：玩家在头显里从对方屏幕上读一个地址
 * 敲进数字键盘，不该还要再敲五位端口号。被自动发现的房间不需要这条（NSD 自己带端口），所以这个选择
 * 完全是为了手动路径。
 *
 * 代价：同一台设备开不了两个房间；房主结束一局后要立刻重新绑定同一端口，所以 [LanMatchHost] 绑定前
 * 必须设 `reuseAddress`——否则上一个 socket 留在 `TIME_WAIT` 会让本次会话的第二次建房直接失败。
 */
const val MATCH_PORT = 47780

/** 四席减去房主自己。 */
const val MAX_CLIENTS = 3

/**
 * 轮到某个掉线玩家时，在交给 AI 托管之前等他重连多久。
 *
 * 放在这里而不是任何一侧的私有常量里：房主用它做倒计时，两侧都用它渲染「等待 XXX 重连 Ns」。
 * 两份私有副本靠运气保持一致是 SpaceGomoku 记录过的坑。判断值，可调。
 */
const val RECONNECT_GRACE_S = 15

/** 一个真人座位的连接状况。 */
enum class Presence {
    /** 连着，由他本人操作。 */
    ONLINE,

    /** 连接断了，但还没到「轮到他且等超时」那一步，对局照常进行。 */
    DROPPED,

    /** 等待窗口耗尽，这一席现在由 AI 驾驶；他重连回来会回到 [ONLINE]。 */
    AI_TAKEOVER,
}

/** 一个座位上坐着谁。 */
sealed interface Occupant {
    /** 没人。开局前可以被任何人坐进来；房主点开始时会被固化成 [Ai]。 */
    data object Empty : Occupant

    /** 开局那一刻由空位固化而来的 AI。整局不会再变回空位。 */
    data object Ai : Occupant

    data class Human(val name: String, val isHost: Boolean, val presence: Presence = Presence.ONLINE) : Occupant
}

/**
 * 四个座位的完整状态。不可变，每次变更返回新实例——房主是唯一的修改者，客户端只接收它的广播。
 *
 * 刻意不含任何 `Socket`/连接对象：这个类型要能被编码进协议、被单测构造、被 Compose 当状态读，
 * 三者都不该碰网络句柄。[LanMatchHost] 自己维护 `Team -> Socket` 的映射。
 */
data class RoomState(val seats: Map<Team, Occupant>) {

    init {
        require(seats.keys == Team.entries.toSet()) { "必须恰好四个座位，每个队伍一个" }
    }

    fun occupant(team: Team): Occupant = seats.getValue(team)

    /** 按 [Team] 声明序（红→黄→蓝→绿）的第一个空位；满了返回 null。 */
    fun firstEmpty(): Team? = Team.entries.firstOrNull { seats.getValue(it) is Occupant.Empty }

    fun humanCount(): Int = seats.values.count { it is Occupant.Human }

    fun isFull(): Boolean = firstEmpty() == null

    /**
     * 这一席现在该由 AI 出手吗？
     *
     * [Presence.DROPPED] 刻意返回 false：那是「等他重连」的窗口，不是托管。只有窗口耗尽转成
     * [Presence.AI_TAKEOVER] 才轮到 AI。空位也返回 false——它要等 `SeatAssigner.lockForStart`
     * 固化成 [Occupant.Ai] 才算数。
     */
    fun isDrivenByAi(team: Team): Boolean = when (val seat = seats.getValue(team)) {
        is Occupant.Ai -> true
        is Occupant.Human -> seat.presence == Presence.AI_TAKEOVER
        is Occupant.Empty -> false
    }

    fun hostTeam(): Team? = Team.entries.firstOrNull { (seats.getValue(it) as? Occupant.Human)?.isHost == true }

    fun withOccupant(team: Team, occupant: Occupant): RoomState = copy(seats = seats + (team to occupant))

    /**
     * 编成协议里的**一个字段**——因此绝不能含空格（协议按空格分字段）。
     *
     * 形如 `RED:H:Zohar:host:on|YELLOW:E|BLUE:A|GREEN:H:PICO-4821:guest:ai`
     */
    fun encode(): String = Team.entries.joinToString("|") { team ->
        when (val seat = seats.getValue(team)) {
            is Occupant.Empty -> "${team.name}:E"
            is Occupant.Ai -> "${team.name}:A"
            is Occupant.Human -> {
                val role = if (seat.isHost) "host" else "guest"
                val presence = when (seat.presence) {
                    Presence.ONLINE -> "on"
                    Presence.DROPPED -> "off"
                    Presence.AI_TAKEOVER -> "ai"
                }
                "${team.name}:H:${sanitizeName(seat.name)}:$role:$presence"
            }
        }
    }

    companion object {
        private const val NAME_MAX = 20
        private const val NAME_FALLBACK = "PLAYER"

        fun empty(): RoomState = RoomState(Team.entries.associateWith { Occupant.Empty })

        /**
         * 把设备型号变成一个能安全放进协议字段的名字：去掉空格和两个分隔符，截断到 [NAME_MAX]。
         * 全是空白则回落到 [NAME_FALLBACK]，避免大厅里出现一个没有名字的座位。
         */
        fun sanitizeName(raw: String): String {
            val cleaned = raw.trim().map { ch ->
                if (ch == ' ' || ch == '|' || ch == ':' || ch.code < 0x20) '_' else ch
            }.joinToString("")
            if (cleaned.isBlank() || cleaned.all { it == '_' }) return NAME_FALLBACK
            return cleaned.take(NAME_MAX)
        }

        /** [encode] 的逆运算。任何解析不出来的输入返回 null——这是从 socket 来的不可信数据。 */
        fun decode(text: String): RoomState? {
            val chunks = text.trim().split("|")
            if (chunks.size != Team.entries.size) return null
            val seats = mutableMapOf<Team, Occupant>()
            for (chunk in chunks) {
                val parts = chunk.split(":")
                val team = Team.entries.firstOrNull { it.name == parts.getOrNull(0) } ?: return null
                if (seats.containsKey(team)) return null
                val occupant = when (parts.getOrNull(1)) {
                    "E" -> if (parts.size == 2) Occupant.Empty else return null
                    "A" -> if (parts.size == 2) Occupant.Ai else return null
                    "H" -> {
                        if (parts.size != 5) return null
                        val isHost = when (parts[3]) {
                            "host" -> true
                            "guest" -> false
                            else -> return null
                        }
                        val presence = when (parts[4]) {
                            "on" -> Presence.ONLINE
                            "off" -> Presence.DROPPED
                            "ai" -> Presence.AI_TAKEOVER
                            else -> return null
                        }
                        Occupant.Human(parts[2], isHost, presence)
                    }
                    else -> return null
                }
                seats[team] = occupant
            }
            return RoomState(seats)
        }
    }
}

/**
 * 局域网发现到的一个房间。刻意不含 `NsdServiceInfo`——那是 Android 类型，这个文件不碰。
 *
 * **没有人数字段，这是相对设计文档的一处有意偏离。** 设计文档里房间列表写的是 `n/4`，但连上之前
 * 拿不到人数：NSD 只通告服务名和端口，要带人数就得把它写进 TXT 记录，而 `NsdManager` 改属性必须
 * 先 unregister 再 register——每次有人进出房间都断一次广播，代价远大于一个数字的价值。
 * 列表只显示房间名，人数进大厅才知道。
 */
data class DiscoveredRoom(val id: String, val displayName: String)

/** 联机流程当前走到哪一步，驱动 BoardStage 显示哪个面板。 */
sealed interface BattleFlowState {
    /** 默认。没有任何连接，玩的是单机。 */
    data object SinglePlayer : BattleFlowState

    /** 客户端：NSD 发现进行中，展示已找到的房间。 */
    data class Discovering(val rooms: List<DiscoveredRoom>) : BattleFlowState

    /**
     * 客户端：正在数字键盘上手输房主地址。
     *
     * 这个状态期间必须**停掉 NSD 发现**：否则发现回调会不断推 [Discovering] 覆盖掉玩家正在输的界面。
     */
    data object ManualEntry : BattleFlowState

    /** 客户端：socket 连接进行中。 */
    data object Connecting : BattleFlowState

    /**
     * 大厅。房主和客户端共用同一个面板，用 [isHost] 决定底部是「开始/解散」还是「等待房主开始/离开」。
     * [localAddress] 只有房主非空，形如 `"192.168.1.23"`，显示给对方手输用。
     */
    data class InLobby(
        val room: RoomState,
        val myTeam: Team,
        val isHost: Boolean,
        val localAddress: String?,
    ) : BattleFlowState

    /** 对局进行中，大厅面板收起，正常游戏 HUD 恢复。 */
    data class InMatch(val room: RoomState, val myTeam: Team, val isHost: Boolean) : BattleFlowState

    /** 对局结束（房主解散、房主离开、自己退出）。 */
    data class Ended(val reason: String) : BattleFlowState
}

/**
 * 对局结束的全部原因，集中一处。
 *
 * [BattleFlowState.Ended] 携带的就是 UI 原样渲染的这些文案；房主和客户端都会产出其中几条，
 * 措辞必须一致，所以不散落成两边的字符串字面量。
 */
object EndReason {
    const val HOST_LEFT = "房主已离开"
    const val ROOM_CLOSED = "房主解散了房间"
    const val ROOM_FULL = "房间已满"
    const val CONNECT_FAILED = "无法连接到房间"
    const val RESOLVE_FAILED = "无法解析房间地址"
    const val DISCOVERY_FAILED = "无法搜索房间"
    const val HOSTING_FAILED = "无法创建房间"
    const val LEFT_BY_PLAYER = "已离开房间"
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
./gradlew testDebugUnitTest --tests '*RoomStateTest*'
```

预期：11 个测试全部 PASS。

- [ ] **Step 5: 跑全量测试并提交**

```bash
export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
./gradlew testDebugUnitTest
git add app/src/main/java/tech/illusion/spaceflightchess/net/RoomState.kt app/src/test/java/tech/illusion/spaceflightchess/net/RoomStateTest.kt
git commit -m "feat(net): 座位模型、联机流程状态与共享常量

四席房间的不可变状态 + 可编进协议单字段的编解码；SERVICE_TYPE/MATCH_PORT/
MAX_CLIENTS/RECONNECT_GRACE_S 集中一处，避免两侧各存一份靠运气一致。"
```

---

### Task 4: 座位裁决逻辑（`SeatAssigner`）

谁能坐哪、换座给不给、开局时空位怎么变 AI、掉线怎么标记——全是纯函数，房主是唯一调用方。

**Files:**
- Create: `app/src/main/java/tech/illusion/spaceflightchess/net/SeatAssigner.kt`
- Test: `app/src/test/java/tech/illusion/spaceflightchess/net/SeatAssignerTest.kt`

**Interfaces:**
- Consumes: `RoomState`、`Occupant`、`Presence`、`Team`（Task 3）
- Produces:
  - `SeatAssigner.host(room: RoomState, team: Team, name: String): RoomState`
  - `SeatAssigner.join(room: RoomState, name: String): Pair<RoomState, Team>?`（null = 满了）
  - `SeatAssigner.requestSeat(room: RoomState, from: Team, to: Team): RoomState`（不允许则原样返回）
  - `SeatAssigner.leave(room: RoomState, team: Team): RoomState`
  - `SeatAssigner.rejoin(room: RoomState, team: Team, name: String): Pair<RoomState, Team>?`
  - `SeatAssigner.lockForStart(room: RoomState): RoomState`
  - `SeatAssigner.setPresence(room: RoomState, team: Team, presence: Presence): RoomState`

- [ ] **Step 1: 写失败的测试**

创建 `app/src/test/java/tech/illusion/spaceflightchess/net/SeatAssignerTest.kt`：

```kotlin
package tech.illusion.spaceflightchess.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.illusion.spaceflightchess.game.Team

class SeatAssignerTest {

    private fun hosted(team: Team = Team.RED): RoomState =
        SeatAssigner.host(RoomState.empty(), team, "Zohar")

    @Test
    fun `房主落在自己选的颜色上并被标记为 host`() {
        val room = hosted(Team.BLUE)
        val seat = room.occupant(Team.BLUE) as Occupant.Human
        assertEquals("Zohar", seat.name)
        assertTrue(seat.isHost)
        assertEquals(Presence.ONLINE, seat.presence)
        assertEquals(Team.BLUE, room.hostTeam())
    }

    @Test
    fun `加入者落在第一个空位`() {
        val (room, team) = SeatAssigner.join(hosted(Team.RED), "PICO-4821")!!
        assertEquals(Team.YELLOW, team)
        assertEquals("PICO-4821", (room.occupant(Team.YELLOW) as Occupant.Human).name)
        assertTrue((room.occupant(Team.YELLOW) as Occupant.Human).isHost.not())
    }

    @Test
    fun `房间满了就拒绝加入`() {
        var room = hosted()
        repeat(3) { room = SeatAssigner.join(room, "P$it")!!.first }
        assertTrue(room.isFull())
        assertNull(SeatAssigner.join(room, "第五个人"))
    }

    @Test
    fun `换到空位成功且原座位空出来`() {
        val (joined, mine) = SeatAssigner.join(hosted(Team.RED), "Me")!!
        val moved = SeatAssigner.requestSeat(joined, from = mine, to = Team.GREEN)
        assertEquals(Occupant.Empty, moved.occupant(mine))
        assertEquals("Me", (moved.occupant(Team.GREEN) as Occupant.Human).name)
    }

    @Test
    fun `换到已被占的位原样返回——先到先得由房主裁决`() {
        val (joined, mine) = SeatAssigner.join(hosted(Team.RED), "Me")!!
        assertEquals(joined, SeatAssigner.requestSeat(joined, from = mine, to = Team.RED))
    }

    @Test
    fun `换到自己已经坐着的位原样返回`() {
        val (joined, mine) = SeatAssigner.join(hosted(Team.RED), "Me")!!
        assertEquals(joined, SeatAssigner.requestSeat(joined, from = mine, to = mine))
    }

    @Test
    fun `从一个没坐人的位置发起换座会被忽略`() {
        val room = hosted(Team.RED)
        assertEquals(room, SeatAssigner.requestSeat(room, from = Team.GREEN, to = Team.YELLOW))
    }

    @Test
    fun `换座保留 host 标记`() {
        val moved = SeatAssigner.requestSeat(hosted(Team.RED), from = Team.RED, to = Team.GREEN)
        assertTrue((moved.occupant(Team.GREEN) as Occupant.Human).isHost)
        assertEquals(Team.GREEN, moved.hostTeam())
    }

    @Test
    fun `离开把座位彻底空出来`() {
        val (joined, mine) = SeatAssigner.join(hosted(Team.RED), "Me")!!
        assertEquals(Occupant.Empty, SeatAssigner.leave(joined, mine).occupant(mine))
    }

    @Test
    fun `开局时空位固化成 AI 真人和已有 AI 都不动`() {
        var room = hosted(Team.RED)
        room = SeatAssigner.join(room, "Guest")!!.first          // YELLOW
        room = SeatAssigner.setPresence(room, Team.YELLOW, Presence.DROPPED)
        val locked = SeatAssigner.lockForStart(room)

        assertTrue(locked.occupant(Team.RED) is Occupant.Human)
        assertEquals(Presence.DROPPED, (locked.occupant(Team.YELLOW) as Occupant.Human).presence)
        assertEquals(Occupant.Ai, locked.occupant(Team.BLUE))
        assertEquals(Occupant.Ai, locked.occupant(Team.GREEN))
    }

    @Test
    fun `开局固化是幂等的`() {
        val once = SeatAssigner.lockForStart(hosted())
        assertEquals(once, SeatAssigner.lockForStart(once))
    }

    @Test
    fun `setPresence 只改连接状态不改名字和 host 标记`() {
        val dropped = SeatAssigner.setPresence(hosted(Team.RED), Team.RED, Presence.AI_TAKEOVER)
        val seat = dropped.occupant(Team.RED) as Occupant.Human
        assertEquals("Zohar", seat.name)
        assertTrue(seat.isHost)
        assertEquals(Presence.AI_TAKEOVER, seat.presence)
    }

    @Test
    fun `setPresence 对空位和 AI 位是空操作`() {
        val room = hosted(Team.RED).withOccupant(Team.YELLOW, Occupant.Ai)
        assertEquals(room, SeatAssigner.setPresence(room, Team.BLUE, Presence.DROPPED))
        assertEquals(room, SeatAssigner.setPresence(room, Team.YELLOW, Presence.DROPPED))
    }

    @Test
    fun `重连回原座位会恢复在线`() {
        var room = hosted(Team.RED)
        room = SeatAssigner.join(room, "Guest")!!.first
        room = SeatAssigner.setPresence(room, Team.YELLOW, Presence.AI_TAKEOVER)

        val (rejoined, team) = SeatAssigner.rejoin(room, Team.YELLOW, "Guest")!!
        assertEquals(Team.YELLOW, team)
        assertEquals(Presence.ONLINE, (rejoined.occupant(Team.YELLOW) as Occupant.Human).presence)
    }

    @Test
    fun `原座被别人占了就安排到别的空位`() {
        var room = hosted(Team.RED)
        room = SeatAssigner.join(room, "Guest")!!.first                 // YELLOW
        room = SeatAssigner.setPresence(room, Team.YELLOW, Presence.DROPPED)
        room = SeatAssigner.requestSeat(room, from = Team.RED, to = Team.BLUE) // 房主让开红位
        room = room.withOccupant(Team.YELLOW, Occupant.Empty)
        room = SeatAssigner.join(room, "别人")!!.first                   // 别人坐进 YELLOW

        val (rejoined, team) = SeatAssigner.rejoin(room, Team.YELLOW, "Guest")!!
        assertEquals(Team.RED, team)   // YELLOW 被占，落到声明序里下一个空位
        assertEquals("Guest", (rejoined.occupant(Team.RED) as Occupant.Human).name)
    }

    @Test
    fun `原座被占且房间已满则重连失败`() {
        var room = hosted(Team.RED)
        repeat(3) { room = SeatAssigner.join(room, "P$it")!!.first }
        assertNull(SeatAssigner.rejoin(room, Team.YELLOW, "谁"))
    }

    @Test
    fun `重连到自己那个还标着掉线的座位不会被当成被占`() {
        var room = hosted(Team.RED)
        repeat(3) { room = SeatAssigner.join(room, "P$it")!!.first }   // 满员
        room = SeatAssigner.setPresence(room, Team.BLUE, Presence.AI_TAKEOVER)
        val (rejoined, team) = SeatAssigner.rejoin(room, Team.BLUE, "P1")!!
        assertEquals(Team.BLUE, team)
        assertEquals(Presence.ONLINE, (rejoined.occupant(Team.BLUE) as Occupant.Human).presence)
        assertNotNull(rejoined.hostTeam())
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
./gradlew testDebugUnitTest --tests '*SeatAssignerTest*'
```

预期：编译失败，`Unresolved reference: SeatAssigner`。

- [ ] **Step 3: 实现 SeatAssigner**

创建 `app/src/main/java/tech/illusion/spaceflightchess/net/SeatAssigner.kt`：

```kotlin
package tech.illusion.spaceflightchess.net

import tech.illusion.spaceflightchess.game.Team

/**
 * 座位的全部裁决规则，纯函数。
 *
 * **只有房主调用这里。** 客户端发出 `SEAT_REQ` 之后绝不预先改自己的座位显示，等房主广播回来的
 * 完整座位表才更新——两个人同时点同一个空位时先到先得，后到的那次调用会因为位子已被占而原样返回，
 * 于是不需要任何冲突协商逻辑。
 */
object SeatAssigner {

    /** 房主开房：坐进他在开始页选的那个颜色。 */
    fun host(room: RoomState, team: Team, name: String): RoomState =
        room.withOccupant(team, Occupant.Human(RoomState.sanitizeName(name), isHost = true))

    /**
     * 新加入者落进 [RoomState.firstEmpty]（`Team` 声明序）。房间满了返回 null，
     * 调用方据此回 `ENDED 房间已满` 并断开这条连接。
     *
     * 刻意不让加入者自己指定颜色：他一进来就有位置，之后再用 [requestSeat] 换。房主可能随时点开始，
     * 「还没选完就开局」是个真实会发生的坑。
     */
    fun join(room: RoomState, name: String): Pair<RoomState, Team>? {
        val team = room.firstEmpty() ?: return null
        return room.withOccupant(team, Occupant.Human(RoomState.sanitizeName(name), isHost = false)) to team
    }

    /**
     * 把 [from] 上的人挪到 [to]。目标不空、来源没人坐、或者两者相同，一律原样返回——
     * 调用方无论成败都广播一次完整座位表，客户端只认那个结果。
     */
    fun requestSeat(room: RoomState, from: Team, to: Team): RoomState {
        if (from == to) return room
        val mover = room.occupant(from) as? Occupant.Human ?: return room
        if (room.occupant(to) !is Occupant.Empty) return room
        return room.withOccupant(from, Occupant.Empty).withOccupant(to, mover)
    }

    fun leave(room: RoomState, team: Team): RoomState = room.withOccupant(team, Occupant.Empty)

    /**
     * 重连：优先回 [preferred]，被别人占了就落到第一个空位，都没有则返回 null（回 `ENDED 房间已满`）。
     *
     * 「被别人占了」的判定要看名字：一个标着 [Presence.DROPPED] / [Presence.AI_TAKEOVER] 的座位仍然
     * 是**他自己的**座位，不是别人的——满员局里的重连全靠这一条才不会被误判成房间已满。
     */
    fun rejoin(room: RoomState, preferred: Team, name: String): Pair<RoomState, Team>? {
        val clean = RoomState.sanitizeName(name)
        val current = room.occupant(preferred)
        val isStillMine = current is Occupant.Empty ||
            (current is Occupant.Human && current.name == clean && current.presence != Presence.ONLINE)
        if (isStillMine) {
            val isHost = (current as? Occupant.Human)?.isHost ?: false
            return room.withOccupant(preferred, Occupant.Human(clean, isHost, Presence.ONLINE)) to preferred
        }
        return join(room, clean)
    }

    /**
     * 房主点开始：此刻仍空着的座位固化成 AI。真人座位（包括已掉线的）一概不动——
     * 掉线的人还在他的等待窗口里，不该在开局这一步就被顶掉。
     *
     * 幂等：已经是 AI 的座位再跑一次不变。
     */
    fun lockForStart(room: RoomState): RoomState =
        Team.entries.fold(room) { acc, team ->
            if (acc.occupant(team) is Occupant.Empty) acc.withOccupant(team, Occupant.Ai) else acc
        }

    /** 只改真人座位的连接状态，名字和 host 标记保持不变。空位和 AI 位上是空操作。 */
    fun setPresence(room: RoomState, team: Team, presence: Presence): RoomState {
        val seat = room.occupant(team) as? Occupant.Human ?: return room
        if (seat.presence == presence) return room
        return room.withOccupant(team, seat.copy(presence = presence))
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
./gradlew testDebugUnitTest --tests '*SeatAssignerTest*'
```

预期：17 个测试全部 PASS。

- [ ] **Step 5: 跑全量测试并提交**

```bash
export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
./gradlew testDebugUnitTest
git add app/src/main/java/tech/illusion/spaceflightchess/net/SeatAssigner.kt app/src/test/java/tech/illusion/spaceflightchess/net/SeatAssignerTest.kt
git commit -m "feat(net): 座位裁决纯逻辑

落座/换座/离座/重连/开局固化/连接状态六件事，房主唯一调用方。
换座失败原样返回，配合「客户端不预先改显示」把同抢一个位子的冲突消解掉。"
```

---

### Task 5: 线协议（`MatchMessage`）+ `forceState` 的一行改动

一行一条纯文本、空格分字段。**纯文本不只是图省事**：它让 Task 7 那个 Mac 侧的 Python 陪练脚本成为可能，而那个脚本是「只有一台真机」条件下唯一的端到端验证手段。

**Files:**
- Create: `app/src/main/java/tech/illusion/spaceflightchess/net/MatchMessage.kt`
- Modify: `app/src/main/java/tech/illusion/spaceflightchess/game/GameEngine.kt`（`forceState` 签名）
- Test: `app/src/test/java/tech/illusion/spaceflightchess/net/MatchMessageTest.kt`

**Interfaces:**
- Consumes: `RoomState`/`Occupant`/`Presence`（Task 3）、`GameState`、`PieceState`、`Team`
- Produces:
  - `sealed interface MatchMessage`，成员见下方实现
  - `MatchMessage.encode(): String`、`MatchMessage.Companion.decode(line: String): MatchMessage?`
  - `GameEngine.forceState(newState: GameState, consecutiveSixes: Int = 0)`

- [ ] **Step 1: 写失败的测试**

创建 `app/src/test/java/tech/illusion/spaceflightchess/net/MatchMessageTest.kt`：

```kotlin
package tech.illusion.spaceflightchess.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import tech.illusion.spaceflightchess.game.GameState
import tech.illusion.spaceflightchess.game.PieceState
import tech.illusion.spaceflightchess.game.Team

class MatchMessageTest {

    private fun roundTrip(message: MatchMessage) {
        assertEquals(message, MatchMessage.decode(message.encode()))
    }

    private fun sampleRoom(): RoomState = RoomState.empty()
        .withOccupant(Team.RED, Occupant.Human("Zohar", isHost = true, presence = Presence.ONLINE))
        .withOccupant(Team.YELLOW, Occupant.Human("PICO-4821", isHost = false, presence = Presence.AI_TAKEOVER))
        .withOccupant(Team.BLUE, Occupant.Ai)

    private fun sampleState(): GameState = GameState.newGame(currentTeam = Team.GREEN)
        .withPiece(Team.RED, 0, PieceState.OnPath(0))
        .withPiece(Team.RED, 1, PieceState.OnPath(37))
        .withPiece(Team.BLUE, 3, PieceState.FINISHED)

    @Test
    fun `每种房主消息都能往返`() {
        roundTrip(MatchMessage.Welcome(Team.BLUE, sampleRoom()))
        roundTrip(MatchMessage.Lobby(sampleRoom()))
        roundTrip(MatchMessage.Start(sampleRoom()))
        roundTrip(MatchMessage.Rolled(Team.YELLOW, 6))
        roundTrip(MatchMessage.Moved(Team.GREEN, 2, -123456789))
        roundTrip(MatchMessage.SeatUpdate(Team.RED, Presence.DROPPED))
        roundTrip(MatchMessage.Sync(sampleState(), consecutiveSixes = 2, room = sampleRoom()))
        roundTrip(MatchMessage.Ended(EndReason.HOST_LEFT))
    }

    @Test
    fun `每种客户端消息都能往返`() {
        roundTrip(MatchMessage.Join("PICO-4821"))
        roundTrip(MatchMessage.SeatRequest(Team.GREEN))
        roundTrip(MatchMessage.RollRequest)
        roundTrip(MatchMessage.MoveRequest(3))
        roundTrip(MatchMessage.ResyncRequest)
        roundTrip(MatchMessage.Leave)
    }

    @Test
    fun `Sync 还原出完全相同的局面`() {
        val decoded = MatchMessage.decode(MatchMessage.Sync(sampleState(), 2, sampleRoom()).encode())
        val sync = decoded as MatchMessage.Sync
        assertEquals(sampleState(), sync.state)
        assertEquals(2, sync.consecutiveSixes)
        assertEquals(StateChecksum.of(sampleState()), StateChecksum.of(sync.state))
    }

    @Test
    fun `编码出的每一行都不含换行——换行是消息定界符`() {
        listOf(
            MatchMessage.Sync(sampleState(), 0, sampleRoom()),
            MatchMessage.Welcome(Team.RED, sampleRoom()),
            MatchMessage.Ended(EndReason.ROOM_FULL),
        ).forEach { assertFalse(it.encode().contains('\n')) }
    }

    @Test
    fun `名字里的空格在 Join 里也会被清掉`() {
        val decoded = MatchMessage.decode(MatchMessage.Join("My Headset").encode()) as MatchMessage.Join
        assertEquals("My_Headset", decoded.name)
    }

    @Test
    fun `垃圾输入一律解码成 null 而不抛异常`() {
        listOf(
            "", "   ", "\n", "GARBAGE", "ROLLED", "ROLLED RED", "ROLLED RED x",
            "ROLLED PURPLE 3", "MOVED RED 2", "MOVED RED x 1", "SEAT RED nope",
            "SYNC", "SYNC RED 0", "SYNC RED x pieces room", "WELCOME RED", "MOVE_REQ", "MOVE_REQ x",
            "JOIN", "SEAT_REQ", "SEAT_REQ PURPLE", "ROLL_REQ extra", "LEAVE extra",
        ).forEach { assertNull("应该拒绝: <$it>", MatchMessage.decode(it)) }
    }

    @Test
    fun `点数越界的 Rolled 被拒绝`() {
        // 骰子只有 1..6。GameEngine.roll 内部对越界值是 require 抛异常，所以绝不能让它流进去。
        listOf("ROLLED RED 0", "ROLLED RED 7", "ROLLED RED -1").forEach {
            assertNull("应该拒绝: $it", MatchMessage.decode(it))
        }
    }

    @Test
    fun `棋子下标越界的 Moved 与 MoveRequest 被拒绝`() {
        listOf("MOVED RED 4 12", "MOVED RED -1 12", "MOVE_REQ 4", "MOVE_REQ -1").forEach {
            assertNull("应该拒绝: $it", MatchMessage.decode(it))
        }
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
./gradlew testDebugUnitTest --tests '*MatchMessageTest*'
```

预期：编译失败，`Unresolved reference: MatchMessage`。

- [ ] **Step 3: 实现 MatchMessage**

创建 `app/src/main/java/tech/illusion/spaceflightchess/net/MatchMessage.kt`：

```kotlin
package tech.illusion.spaceflightchess.net

import tech.illusion.spaceflightchess.game.DiceEngine
import tech.illusion.spaceflightchess.game.GameState
import tech.illusion.spaceflightchess.game.PieceState
import tech.illusion.spaceflightchess.game.Team

/**
 * 局域网联机的线协议：一行一条，空格分字段，不引序列化库。
 *
 * 纯文本不只是图省事——它让 `tools/lan_peer.py` 那个 Mac 侧的陪练脚本成为可能，而在只有一台真机的
 * 条件下，那个脚本是唯一能端到端验证协议往返、掉线托管、重连的手段。
 *
 * [decode] 对任何解析不出来的输入返回 null 而不是抛异常：从 socket 读到的是不可信输入，一行垃圾
 * 不该让读循环整个崩掉。[encode] 产出的字符串保证不含换行（换行是消息定界符）。
 */
sealed interface MatchMessage {

    // ── 房主 → 客户端 ─────────────────────────────────────────────────────────
    /** 连上后的第一条：你坐哪，以及当前完整座位表。 */
    data class Welcome(val yourTeam: Team, val room: RoomState) : MatchMessage

    /** 有人加入/离开/换座后重发整张座位表。客户端只认这个，不预先改自己的显示。 */
    data class Lobby(val room: RoomState) : MatchMessage

    /** 房主点了开始，空位已固化成 AI。 */
    data class Start(val room: RoomState) : MatchMessage

    /** 权威骰子结果。客户端写进 `RelayDice.forced` 再调 `engine.roll()`。 */
    data class Rolled(val team: Team, val value: Int) : MatchMessage

    /**
     * 走了哪一步。[pieceIndex] 是候选 `Move` 的**代表**棋子下标——`legalMoves` 是纯函数且代表下标
     * 在候选里唯一（`MirrorReplayTest` 钉住了这条），所以一个整数就能还原整个 `Move`。
     * [checksum] 是房主 apply 之后算的，客户端 apply 完对比，不一致就发 [ResyncRequest]。
     */
    data class Moved(val team: Team, val pieceIndex: Int, val checksum: Int) : MatchMessage

    /** 某个座位的连接状态变了（掉线 / 转 AI 托管 / 回归）。 */
    data class SeatUpdate(val team: Team, val presence: Presence) : MatchMessage

    /**
     * 全量对齐。只在房主处于 `Phase.AWAITING_ROLL`（回合之间）时发送，因为接收方是用
     * `GameEngine.forceState` 落地的，而它的语义就是跳到回合起点。
     *
     * [consecutiveSixes] 必须带：漏了它，在某人连摇 6 的中途同步会让客户端的计数归零而房主没有，
     * 之后房主触发三连 6 罚回机库、客户端不会，两边永久分叉。
     */
    data class Sync(val state: GameState, val consecutiveSixes: Int, val room: RoomState) : MatchMessage

    data class Ended(val reason: String) : MatchMessage

    // ── 客户端 → 房主 ─────────────────────────────────────────────────────────
    data class Join(val name: String) : MatchMessage
    data class SeatRequest(val team: Team) : MatchMessage
    data object RollRequest : MatchMessage
    data class MoveRequest(val pieceIndex: Int) : MatchMessage
    data object ResyncRequest : MatchMessage
    data object Leave : MatchMessage

    fun encode(): String = when (this) {
        is Welcome -> "WELCOME ${yourTeam.name} ${room.encode()}"
        is Lobby -> "LOBBY ${room.encode()}"
        is Start -> "START ${room.encode()}"
        is Rolled -> "ROLLED ${team.name} $value"
        is Moved -> "MOVED ${team.name} $pieceIndex $checksum"
        is SeatUpdate -> "SEAT ${team.name} ${encodePresence(presence)}"
        is Sync -> "SYNC ${state.currentTeam.name} $consecutiveSixes ${encodePieces(state)} ${room.encode()}"
        is Ended -> "ENDED $reason"
        is Join -> "JOIN ${RoomState.sanitizeName(name)}"
        is SeatRequest -> "SEAT_REQ ${team.name}"
        RollRequest -> "ROLL_REQ"
        is MoveRequest -> "MOVE_REQ $pieceIndex"
        ResyncRequest -> "RESYNC_REQ"
        Leave -> "LEAVE"
    }

    companion object {
        private const val IN_HANGAR_TOKEN = "H"

        fun decode(line: String): MatchMessage? {
            val parts = line.trim().split(" ")
            if (parts.isEmpty() || parts[0].isEmpty()) return null
            return runCatching {
                when (parts[0]) {
                    "WELCOME" -> exactly(parts, 3) {
                        Welcome(team(parts[1]) ?: return null, RoomState.decode(parts[2]) ?: return null)
                    }
                    "LOBBY" -> exactly(parts, 2) { Lobby(RoomState.decode(parts[1]) ?: return null) }
                    "START" -> exactly(parts, 2) { Start(RoomState.decode(parts[1]) ?: return null) }
                    "ROLLED" -> exactly(parts, 3) {
                        val value = parts[2].toInt()
                        if (value !in 1..DiceEngine.FACES) return null
                        Rolled(team(parts[1]) ?: return null, value)
                    }
                    "MOVED" -> exactly(parts, 4) {
                        val index = parts[2].toInt()
                        if (index !in 0 until GameState.PIECES_PER_TEAM) return null
                        Moved(team(parts[1]) ?: return null, index, parts[3].toInt())
                    }
                    "SEAT" -> exactly(parts, 3) {
                        SeatUpdate(team(parts[1]) ?: return null, presence(parts[2]) ?: return null)
                    }
                    "SYNC" -> exactly(parts, 5) {
                        Sync(
                            state = decodePieces(parts[3], team(parts[1]) ?: return null) ?: return null,
                            consecutiveSixes = parts[2].toInt(),
                            room = RoomState.decode(parts[4]) ?: return null,
                        )
                    }
                    // 结束原因是给人读的文案，可能含空格，所以它吃掉本行剩下的全部内容。
                    "ENDED" -> if (parts.size >= 2) Ended(parts.drop(1).joinToString(" ")) else null
                    "JOIN" -> exactly(parts, 2) { Join(parts[1]) }
                    "SEAT_REQ" -> exactly(parts, 2) { SeatRequest(team(parts[1]) ?: return null) }
                    "ROLL_REQ" -> exactly(parts, 1) { RollRequest }
                    "MOVE_REQ" -> exactly(parts, 2) {
                        val index = parts[1].toInt()
                        if (index !in 0 until GameState.PIECES_PER_TEAM) return null
                        MoveRequest(index)
                    }
                    "RESYNC_REQ" -> exactly(parts, 1) { ResyncRequest }
                    "LEAVE" -> exactly(parts, 1) { Leave }
                    else -> null
                }
            }.getOrNull()
        }

        /** 字段数必须精确匹配：多一个字段说明发的人和收的人对协议的理解不一致，宁可丢弃也不猜。 */
        private inline fun <T> exactly(parts: List<String>, count: Int, build: () -> T?): T? =
            if (parts.size == count) build() else null

        private fun team(token: String): Team? = Team.entries.firstOrNull { it.name == token }

        private fun encodePresence(presence: Presence): String = when (presence) {
            Presence.ONLINE -> "on"
            Presence.DROPPED -> "off"
            Presence.AI_TAKEOVER -> "ai"
        }

        private fun presence(token: String): Presence? = when (token) {
            "on" -> Presence.ONLINE
            "off" -> Presence.DROPPED
            "ai" -> Presence.AI_TAKEOVER
            else -> null
        }

        /** `RED:H,H,3,50|YELLOW:...|BLUE:...|GREEN:...`，`H` = 在机库，数字 = path 值。 */
        private fun encodePieces(state: GameState): String = Team.entries.joinToString("|") { team ->
            val pieces = state.pieces(team).joinToString(",") { piece ->
                when (piece) {
                    is PieceState.InHangar -> IN_HANGAR_TOKEN
                    is PieceState.OnPath -> piece.value.toString()
                }
            }
            "${team.name}:$pieces"
        }

        private fun decodePieces(token: String, currentTeam: Team): GameState? {
            val chunks = token.split("|")
            if (chunks.size != Team.entries.size) return null
            val byTeam = mutableMapOf<Team, List<PieceState>>()
            for (chunk in chunks) {
                val head = chunk.substringBefore(':', missingDelimiterValue = "")
                val tail = chunk.substringAfter(':', missingDelimiterValue = "")
                val team = team(head) ?: return null
                if (byTeam.containsKey(team)) return null
                val cells = tail.split(",")
                if (cells.size != GameState.PIECES_PER_TEAM) return null
                byTeam[team] = cells.map { cell ->
                    if (cell == IN_HANGAR_TOKEN) PieceState.InHangar
                    else PieceState.OnPath(cell.toIntOrNull() ?: return null)
                }
            }
            return GameState(piecesByTeam = byTeam, currentTeam = currentTeam)
        }
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
./gradlew testDebugUnitTest --tests '*MatchMessageTest*'
```

预期：8 个测试全部 PASS。

- [ ] **Step 5: 给 `forceState` 加上 `consecutiveSixes` 参数**

修改 `app/src/main/java/tech/illusion/spaceflightchess/game/GameEngine.kt`，把 `forceState` 改成：

```kotlin
    /**
     * Jumps straight to [newState] mid-[Phase.AWAITING_ROLL], skipping the normal turn machinery —
     * for tests, the on-device verification harness, and the LAN match's `SYNC` message.
     *
     * [consecutiveSixes] defaults to 0 (the old, unconditional behaviour) so existing callers are
     * unaffected. `SYNC` must pass the real count: a resync landing in the middle of someone's run
     * of 6s would otherwise zero the receiver's counter while the host keeps its own, and the host
     * would later fire the triple-six penalty when the receiver would not — a permanent divergence
     * from a single silently-dropped integer.
     */
    fun forceState(newState: GameState, consecutiveSixes: Int = 0) {
        state = newState
        phase = Phase.AWAITING_ROLL
        pendingRoll = null
        legalMovesCache = emptyList()
        this.consecutiveSixes = consecutiveSixes
        events.clear()
    }
```

注意 `this.consecutiveSixes = consecutiveSixes` 的 `this.` 前缀是必须的——参数名和属性名同名。

- [ ] **Step 6: 跑全量测试**

```bash
export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
./gradlew testDebugUnitTest
```

预期：全绿（`forceState` 的默认参数保证现有调用行为不变）。

- [ ] **Step 7: 提交**

```bash
git add app/src/main/java/tech/illusion/spaceflightchess/net/MatchMessage.kt \
        app/src/test/java/tech/illusion/spaceflightchess/net/MatchMessageTest.kt \
        app/src/main/java/tech/illusion/spaceflightchess/game/GameEngine.kt
git commit -m "feat(net): 线协议编解码 + forceState 带上连 6 计数

一行一条纯文本，垃圾输入返回 null 不抛异常，点数与棋子下标越界一律拒绝。
forceState 新增默认参数，让 SYNC 能真正把接收方拉回完全一致的状态。"
```

---

### Task 6: 命令队列重构（`TurnCommand` + `BoardStage`，单机行为不变）

**这是本计划风险最高的一个任务**，因为它改的是现有单机对局的回合驱动。`AGENTS.md` 记录了 `isTurnBusy` 门禁历史上出过两次问题（keyed effect 导致摇 6 后不再触发；门禁卡死把游戏整个锁死）。这次改写是为了让它更简单——**四个来源收敛成一条队列、一个执行器、一个门禁**——但必须靠单机回归验证兜住。

本任务结束时**还没有任何联机功能**，游戏行为应当和改之前逐帧一致。

**Files:**
- Create: `app/src/main/java/tech/illusion/spaceflightchess/net/TurnCommand.kt`
- Modify: `app/src/main/java/tech/illusion/spaceflightchess/content/BoardStage.kt`

**Interfaces:**
- Consumes: `RelayDice`（Task 1）、`StateChecksum`（Task 1）、`GameEngine`、`PlaneAi`、`movementWaypoints`
- Produces（Task 11/12 依赖）：
  - `sealed interface TurnCommand`：`Roll(team: Team, value: Int?)`、`Apply(team: Team, pieceIndex: Int)`、`Resync(state: GameState, consecutiveSixes: Int)`
  - `BoardStage` 内部：`val commands = Channel<TurnCommand>(Channel.UNLIMITED)`、`suspend fun execute(command: TurnCommand)`
  - `BoardStage` 内部两个广播钩子（本任务里恒为 null，Task 11 接上）：
    `var onRolled: ((Team, Int) -> Unit)?`、`var onMoved: ((Team, Int, Int) -> Unit)?`

- [ ] **Step 1: 创建 TurnCommand**

创建 `app/src/main/java/tech/illusion/spaceflightchess/net/TurnCommand.kt`：

```kotlin
package tech.illusion.spaceflightchess.net

import tech.illusion.spaceflightchess.game.GameState
import tech.illusion.spaceflightchess.game.Team

/**
 * 「推进一个回合」的一步，不管它从哪来。
 *
 * `BoardStage` 里四个来源——本地骰子点击、本地飞机点击、AI driver、网络广播——全都塞进同一条
 * `Channel<TurnCommand>`，由唯一一个串行执行器消费。这样 `isTurnBusy` 门禁、动画播放、`publish()`
 * 只有一份实现，回合串行化的正确性只有一个地方需要看对。
 *
 * 在此之前这三件事在 `rollAndAdvance()`、飞机点击处理器、AI driver 里各写了一遍，而那个门禁历史上
 * 出过两次问题（见 AGENTS.md）。
 */
sealed interface TurnCommand {

    /**
     * 为 [team] 摇一次骰子。
     *
     * [value] 为 null 表示交给本地真骰子（单机与房主）；非 null 表示用房主广播来的点数（客户端），
     * 执行器会把它写进 `RelayDice.forced` 再调 `engine.roll()`。
     */
    data class Roll(val team: Team, val value: Int?) : TurnCommand

    /**
     * 应用 [team] 的一步棋。[pieceIndex] 是候选 `Move` 的**代表**棋子下标，执行器在
     * `engine.legalMovesForPendingRoll()` 里查回完整的 `Move`（含 `groupPieceIndices` 与 `bounceWall`）。
     *
     * 只传下标而不传整个 `Move`，让本地点击和网络广播用完全相同的形状，执行器不需要区分来源。
     */
    data class Apply(val team: Team, val pieceIndex: Int) : TurnCommand

    /** 客户端专用：房主补来的全量状态，直接落地，不播动画。 */
    data class Resync(val state: GameState, val consecutiveSixes: Int) : TurnCommand
}
```

- [ ] **Step 2: 在 BoardStage 里换掉骰子实例并加上队列与钩子**

在 `BoardStage()` 顶部，把 `val engine = remember { GameEngine() }` 换成：

```kotlin
    // 三种角色（单机 / 房主 / 客户端）共用同一颗骰子：单机和房主永不设 forced，落到真随机；
    // 客户端每收到一条 ROLLED 就写进 forced 再摇。这样 engine 的构造在三种角色下完全一致。
    val dice = remember { RelayDice() }
    val engine = remember { GameEngine(dice) }

    /**
     * 回合命令的唯一入口。UNLIMITED 而不是 rendezvous：塞命令的一方（点击处理器、网络读循环）
     * 绝不能被正在播动画的执行器阻塞住——那会把 socket 读循环也一起卡住。
     */
    val commands = remember { Channel<TurnCommand>(Channel.UNLIMITED) }

    /** 房主用来广播的钩子，Task 11 接上；单机与客户端恒为 null。 */
    var onRolled by remember { mutableStateOf<((Team, Int) -> Unit)?>(null) }
    var onMoved by remember { mutableStateOf<((Team, Int, Int) -> Unit)?>(null) }
```

需要新增的 import：

```kotlin
import kotlinx.coroutines.channels.Channel
import tech.illusion.spaceflightchess.net.RelayDice
import tech.illusion.spaceflightchess.net.StateChecksum
import tech.illusion.spaceflightchess.net.TurnCommand
```

- [ ] **Step 3: 用单一执行器替换 `rollAndAdvance`**

删除现有的 `suspend fun rollAndAdvance()`（`BoardStage.kt` 约 216–249 行），换成：

```kotlin
    /**
     * 唯一一处推进回合的地方：应用一步、广播、播动画、刷新。
     *
     * 由下方那个 `for (command in commands)` 循环串行消费，所以任意两条命令绝不会重叠执行。
     * `isTurnBusy` 依然保留，但它现在只用来**挡输入**（点击处理器与 AI driver 读它），不再承担
     * 「防止两条推进路径互相插队」的职责——那件事现在由队列本身保证。
     *
     * 重复命令是安全的、不需要额外的同步门禁：一次双击会塞进两条 Apply，第二条在
     * `legalMovesForPendingRoll()` 已经清空的候选里找不到对应的 Move，记一行日志就返回。
     * 同理，重复的 Roll 会在 `phase != AWAITING_ROLL` 时让 `engine.roll()` 返回 null。
     * 这比改之前那个「在点击处理器里同步地把 isTurnBusy 置 true」的写法更难出错。
     */
    suspend fun execute(command: TurnCommand) {
        isTurnBusy = true
        try {
            when (command) {
                is TurnCommand.Roll -> {
                    val team = engine.state.currentTeam
                    if (team != command.team) {
                        Log.e(TAG, "roll command dropped: for=${command.team} currentTeam=$team")
                        return
                    }
                    dice.forced = command.value
                    val value = engine.roll()
                    logRoll(team, value)
                    if (value == null) return
                    // 先广播、后播动画：反过来的话客户端永远比房主晚一整个动画时长，
                    // 而走子动画是逐格 1 秒，几个回合就累积成明显滞后。
                    onRolled?.invoke(team, value)
                    if (dieRenderer.isAttached) dieRenderer.throwTo(value)
                }

                is TurnCommand.Apply -> {
                    val move = engine.legalMovesForPendingRoll()
                        .firstOrNull { it.team == command.team && it.pieceIndex == command.pieceIndex }
                    if (move == null) {
                        Log.e(
                            TAG,
                            "apply command dropped: team=${command.team} pieceIndex=${command.pieceIndex} " +
                                "phase=${engine.phase} legalMoves=${engine.legalMovesForPendingRoll()}",
                        )
                        return
                    }
                    val outcome = engine.applyMove(move)
                    logMove("cmd", move, outcome)
                    if (outcome == null) return
                    // 校验和必须在 applyMove 之后算——它描述的是这一步之后的局面。
                    onMoved?.invoke(command.team, command.pieceIndex, StateChecksum.of(engine.state))
                    if (pieceRenderer.isAttached) {
                        pieceRenderer.animateMove(
                            move.team,
                            move.groupPieceIndices,
                            engine.state,
                            move.from,
                            movementWaypoints(move, engine.state),
                        )
                    }
                }

                is TurnCommand.Resync -> {
                    engine.forceState(command.state, command.consecutiveSixes)
                    Log.e(TAG, "resynced: currentTeam=${engine.state.currentTeam} sixes=${command.consecutiveSixes}")
                }
            }
        } finally {
            // try/finally 而不是直线代码：throwTo 加上每格一秒的行走会挂起好几秒，
            // 这个窗口里任何取消都会让门禁停在 true，那会静默地把游戏锁死。
            publish()
            isTurnBusy = false
        }
        syncDieSlot()
    }

    // 唯一的命令消费者。Keyed on Unit：只在 dispose 时取消，不会被任何状态变化重启——
    // AGENTS.md 记录过 keyed effect 在这里踩的两个坑。
    LaunchedEffect(Unit) {
        for (command in commands) {
            execute(command)
        }
    }
```

- [ ] **Step 4: 把 AI driver 改成往队列里塞命令**

替换现有的 AI 轮询 `LaunchedEffect(Unit)`（`BoardStage.kt` 约 313–334 行）：

```kotlin
    // AI 驱动。一个长期存活的轮询循环，刻意不是状态键控的 effect——见上方 execute 的注释与 AGENTS.md。
    //
    // 和改之前的区别只有一处：它不再自己调用 rollAndAdvance 和 applyMove，而是往队列里塞命令，
    // 于是 AI 的回合和人类的回合、以及后来的网络广播，走的是完全相同的一条执行路径。
    LaunchedEffect(Unit) {
        while (true) {
            if (isTurnBusy || showExitConfirm || engine.phase == Phase.SETUP || engine.isOver) {
                delay(TURN_POLL_MS)
                continue
            }
            val team = engine.state.currentTeam
            if (!isAiSeat(team)) {
                delay(TURN_POLL_MS)
                continue
            }
            when (engine.phase) {
                Phase.AWAITING_ROLL -> {
                    aiThinking = true
                    delay(AI_THINK_DELAY_MS) // 一段平直的停顿比瞬间出手好读——见设计文档第三章
                    commands.send(TurnCommand.Roll(team, value = null))
                    aiThinking = false
                }
                Phase.AWAITING_MOVE -> {
                    val moves = engine.legalMovesForPendingRoll()
                    if (moves.isEmpty()) {
                        delay(TURN_POLL_MS)
                    } else {
                        val move = PlaneAi.chooseMove(engine.state, moves)
                        commands.send(TurnCommand.Apply(team, move.pieceIndex))
                    }
                }
                else -> delay(TURN_POLL_MS)
            }
            delay(TURN_POLL_MS)
        }
    }
```

在 `BoardStage()` 里（`publish()` 定义之后、`execute` 之前）加上这个判定函数。**本任务里它只认单机规则**，Task 11 会把它接到 `RoomState.isDrivenByAi`：

```kotlin
    /**
     * 这一席现在该由 AI 出手吗？
     *
     * 单机：除了本地玩家之外的三席都是 AI。
     * 联机（Task 11 接入）：房主按 `RoomState.isDrivenByAi` 判定，客户端恒为 false——客户端上
     * AI driver 完全不启动，它只被动接收房主的广播。
     */
    fun isAiSeat(team: Team): Boolean = team != humanTeam
```

- [ ] **Step 5: 把两个点击处理器改成往队列里塞命令**

在 `SpatialView` 的 `detectSpatialPointerEvent` 块里，骰子分支改为：

```kotlin
                    if (dieRenderer.isDie(info.targetedEntity)) {
                        if (engine.phase == Phase.AWAITING_ROLL && engine.state.currentTeam == humanTeam && !isTurnBusy && !showExitConfirm) {
                            commands.trySend(TurnCommand.Roll(humanTeam, value = null))
                        } else {
                            Log.e(
                                TAG,
                                "die tap ignored: phase=${engine.phase} currentTeam=${engine.state.currentTeam} " +
                                    "humanTeam=$humanTeam isTurnBusy=$isTurnBusy",
                            )
                        }
                        handled = true
                        return@forEach
                    }
```

飞机分支里，从 `isTurnBusy = true` 那一行开始到 `syncDieSlot()` 结束的整段（约 20 行）替换为：

```kotlin
                    // 不再同步地抢门禁、也不再自己 applyMove/播动画：塞进队列，由唯一的执行器处理。
                    // 同一帧里的第二次点击会塞进第二条命令，执行器发现候选已空、记一行日志丢弃。
                    commands.trySend(TurnCommand.Apply(humanTeam, move.pieceIndex))
                    handled = true
```

（`val move = engine.legalMovesForPendingRoll().firstOrNull { ... }` 与它的 null 检查保持原样——本地仍然要先在候选里查一次，才能把玩家点的那架飞机映射到僚机组的**代表**下标。）

- [ ] **Step 6: 编译并跑全量单测**

```bash
export PICO_HOME='/Users/zohar/Library/PICO/sdk'
export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
./gradlew testDebugUnitTest && ./gradlew assembleDebug
```

预期：单测全绿，APK 构建成功。

- [ ] **Step 7: 单机回归验证（模拟器实跑一局）**

这一步不能省：单测覆盖不到 `BoardStage`，而这次改的正是回合驱动。

```bash
export PICO_HOME='/Users/zohar/Library/PICO/sdk'
export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
bash ../.claude/skills/spatial-design-first-build/scripts/device-lock.sh acquire "lan-task6" 120 emulator-5554
pico-cli app install app/build/outputs/apk/debug/app-debug.apk --device emulator-5554
adb -s emulator-5554 logcat -c
pico-cli app launch tech.illusion.spaceflightchess --device emulator-5554
```

戴上/看着模拟器画面走完这些，然后捞日志：

```bash
adb -s emulator-5554 logcat -d -s SpaceFlightChess:E | tail -200
pico-cli app stop tech.illusion.spaceflightchess --device emulator-5554
bash ../.claude/skills/spatial-design-first-build/scripts/device-lock.sh release "lan-task6" emulator-5554
```

日志里逐条核对（`grep -a`，不加 `-a` 的话一个控制字节就会让 grep 把整份日志当二进制静默跳过、输出零行）：

- 每一次 `roll:` 后面跟着一次 `move[cmd]:` 或一次换手，没有卡住不动的回合
- 摇到 6 之后**同一个队伍**又出现一次 `roll:`（额外回合仍然生效——这是 keyed-effect 那个坑的复现条件）
- 连续几个回合下来 AI 一直在出手，没有出现「HUD 永远显示对手思考中」
- 没有任何 `apply command dropped` / `roll command dropped`（正常对局里不该出现）

**如果发现回合卡住：** 先看 `isTurnBusy` 有没有停在 true——`execute` 的 `finally` 是唯一的释放点，检查有没有哪条 `return` 绕过了它（`return` 在 `try` 里是安全的，`finally` 仍会跑；但 `execute` 之外的早退不会）。

- [ ] **Step 8: 提交**

```bash
git add app/src/main/java/tech/illusion/spaceflightchess/net/TurnCommand.kt \
        app/src/main/java/tech/illusion/spaceflightchess/content/BoardStage.kt
git commit -m "refactor(stage): 回合驱动收敛成一条命令队列

本地点击、AI driver（以及后续的网络广播）全部塞进同一条 Channel，由唯一的
串行执行器消费。isTurnBusy 从此只负责挡输入，不再兼管路径互斥。单机行为不变。"
```

---

### Task 7: Mac 侧陪练脚本（`tools/lan_peer.py`）

**这个任务要排在网络类之前做完**，因为它是 Task 8/9 的验证手段。用户只有一台 PICO 真机，模拟器与真机之间联机不通、两个模拟器之间 mDNS 也不通，所以「真机 + Mac 陪练」是唯一能端到端验证协议的路子。

脚本同时是一个协议一致性检查器：收到不认识的消息就报错退出。

**Files:**
- Create: `tools/lan_peer.py`

**Interfaces:**
- Consumes: Task 5 定义的线协议（文本层面，不 import 任何 Kotlin）
- Produces: 三个命令行子命令 `host`、`join`、`browse`

- [ ] **Step 1: 写脚本**

创建 `tools/lan_peer.py`：

```python
#!/usr/bin/env python3
"""SpaceFlightChess 局域网联机的陪练对端。

存在的理由：验证联机需要两个对端，而这台机器上只有一台 PICO 真机（模拟器与真机之间
QEMU NAT 双向不通，两个模拟器之间 mDNS 也不通）。协议是一行一条纯文本，所以 Mac 可以
直接扮演另一端。

它也是协议一致性检查器：收到任何不认识的消息就报错退出，而不是安静忽略。

用法：
  # 扮演客户端，连到真机开的房间
  python3 tools/lan_peer.py join 192.168.1.23

  # 扮演房主，等真机加入（真机侧用「手动输入地址」填这台 Mac 的 IP）
  python3 tools/lan_peer.py host

  # 只看看局域网里有哪些房间（等价于 dns-sd -B）
  python3 tools/lan_peer.py browse

按 Ctrl-C 直接杀掉即可模拟掉线；对端应该进入等待重连/AI 托管。
"""

import argparse
import random
import socket
import subprocess
import sys
import threading
import time

MATCH_PORT = 47780
SERVICE_TYPE = "_spaceflightchess._tcp"
TEAMS = ["RED", "YELLOW", "BLUE", "GREEN"]
PIECES_PER_TEAM = 4

HOST_MESSAGES = {"WELCOME", "LOBBY", "START", "ROLLED", "MOVED", "SEAT", "SYNC", "ENDED"}
CLIENT_MESSAGES = {"JOIN", "SEAT_REQ", "ROLL_REQ", "MOVE_REQ", "RESYNC_REQ", "LEAVE"}


def log(direction, line):
    print(f"{time.strftime('%H:%M:%S')} {direction} {line}", flush=True)


class Peer:
    """一条 TCP 连接上的收发。协议是按行的，所以读用带缓冲的行拆分。"""

    def __init__(self, sock):
        self.sock = sock
        self.buffer = b""

    def send(self, line):
        log("→", line)
        self.sock.sendall((line + "\n").encode("utf-8"))

    def lines(self):
        while True:
            while b"\n" in self.buffer:
                raw, self.buffer = self.buffer.split(b"\n", 1)
                line = raw.decode("utf-8", errors="replace").strip()
                if line:
                    log("←", line)
                    yield line
            chunk = self.sock.recv(4096)
            if not chunk:
                print("对端关闭了连接", flush=True)
                return
            self.buffer += chunk


def check_known(line, allowed):
    """协议一致性检查：收到词表外的消息就是双方对协议的理解不一致，立刻报错。"""
    verb = line.split(" ")[0]
    if verb not in allowed:
        print(f"!! 未知消息类型 {verb!r}（本端允许：{sorted(allowed)}）", file=sys.stderr, flush=True)
        sys.exit(2)
    return verb


def parse_seats(spec):
    """`RED:H:Zohar:host:on|YELLOW:E|...` → {team: 描述字符串}。"""
    seats = {}
    for chunk in spec.split("|"):
        parts = chunk.split(":")
        team = parts[0]
        kind = parts[1] if len(parts) > 1 else "?"
        if kind == "E":
            seats[team] = "空"
        elif kind == "A":
            seats[team] = "AI"
        elif kind == "H" and len(parts) == 5:
            seats[team] = f"{parts[2]}({parts[3]},{parts[4]})"
        else:
            print(f"!! 座位表字段无法解析：{chunk!r}", file=sys.stderr, flush=True)
            sys.exit(2)
    if set(seats) != set(TEAMS):
        print(f"!! 座位表缺队伍：{spec!r}", file=sys.stderr, flush=True)
        sys.exit(2)
    return seats


def print_seats(spec):
    seats = parse_seats(spec)
    print("   座位：" + "  ".join(f"{t}={seats[t]}" for t in TEAMS), flush=True)


def run_client_session(peer, name):
    """扮演一个加入者：自动落座、每轮到自己就摇骰、随便挑一架飞机走。"""
    peer.send(f"JOIN {name}")
    my_team = None
    in_match = False

    for line in peer.lines():
        verb = check_known(line, HOST_MESSAGES)
        parts = line.split(" ")

        if verb == "WELCOME":
            my_team = parts[1]
            print(f"   我坐 {my_team}", flush=True)
            print_seats(parts[2])
        elif verb in ("LOBBY", "START"):
            print_seats(parts[1])
            if verb == "START":
                in_match = True
                print("   对局开始", flush=True)
        elif verb == "SEAT":
            print(f"   座位状态变更：{parts[1]} → {parts[2]}", flush=True)
        elif verb == "SYNC":
            print(f"   收到全量同步：currentTeam={parts[1]} sixes={parts[2]}", flush=True)
            print_seats(parts[4])
            in_match = True
        elif verb == "ENDED":
            print(f"   对局结束：{' '.join(parts[1:])}", flush=True)
            return
        elif verb == "ROLLED":
            # 轮到我了：房主刚播报了我的点数，接着我该挑一架飞机。
            if in_match and parts[1] == my_team:
                time.sleep(0.8)
                peer.send(f"MOVE_REQ {random.randrange(PIECES_PER_TEAM)}")
        elif verb == "MOVED":
            # 下一轮如果还是我，就再摇一次（摇到 6 会有额外回合）。
            if in_match:
                time.sleep(0.5)
                peer.send("ROLL_REQ")

    print("读循环结束", flush=True)


def run_host_session(peer):
    """扮演房主：接受加入、随机摇骰、把对方的请求确认回去。

    刻意不实现真正的规则——它只需要产生格式合法的广播，让真机侧的解析、
    队列消费、动画播放跑起来。棋局是否讲道理由真机侧的 engine 自己判断。
    """
    seats = {t: "E" for t in TEAMS}
    seats["RED"] = "H:MacPeer:host:on"
    guest_team = None

    def seat_spec():
        return "|".join(f"{t}:{seats[t]}" for t in TEAMS)

    for line in peer.lines():
        verb = check_known(line, CLIENT_MESSAGES)
        parts = line.split(" ")

        if verb == "JOIN":
            guest_team = next((t for t in TEAMS if seats[t] == "E"), None)
            if guest_team is None:
                peer.send("ENDED 房间已满")
                return
            seats[guest_team] = f"H:{parts[1]}:guest:on"
            peer.send(f"WELCOME {guest_team} {seat_spec()}")
            peer.send(f"LOBBY {seat_spec()}")
            print("   3 秒后开始对局……", flush=True)
            threading.Timer(3.0, lambda: start_match(peer, seats, seat_spec)).start()
        elif verb == "SEAT_REQ":
            target = parts[1]
            if seats[target] == "E" and guest_team:
                seats[guest_team], seats[target] = "E", seats[guest_team]
                guest_team = target
            peer.send(f"LOBBY {seat_spec()}")
        elif verb == "ROLL_REQ":
            peer.send(f"ROLLED {guest_team} {random.randrange(1, 7)}")
        elif verb == "MOVE_REQ":
            # 校验和填 0：真机侧对不上会发 RESYNC_REQ，这本身就是一次有用的验证。
            peer.send(f"MOVED {guest_team} {parts[1]} 0")
        elif verb == "RESYNC_REQ":
            pieces = "|".join(f"{t}:" + ",".join(["H"] * PIECES_PER_TEAM) for t in TEAMS)
            peer.send(f"SYNC RED 0 {pieces} {seat_spec()}")
        elif verb == "LEAVE":
            print("   对方离开了", flush=True)
            return


def start_match(peer, seats, seat_spec):
    for team in TEAMS:
        if seats[team] == "E":
            seats[team] = "A"
    peer.send(f"START {seat_spec()}")


def cmd_host(args):
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(("0.0.0.0", MATCH_PORT))
    server.listen(1)
    ip = socket.gethostbyname(socket.gethostname())
    print(f"在 {ip}:{MATCH_PORT} 等待加入（真机侧用「手动输入地址」填这个 IP）", flush=True)

    registrar = subprocess.Popen(
        ["dns-sd", "-R", "MacPeer", SERVICE_TYPE, ".", str(MATCH_PORT)],
        stdout=subprocess.DEVNULL,
    )
    try:
        conn, addr = server.accept()
        print(f"{addr} 连上来了", flush=True)
        run_host_session(Peer(conn))
    finally:
        registrar.terminate()
        server.close()


def cmd_join(args):
    sock = socket.create_connection((args.address, MATCH_PORT), timeout=10)
    print(f"已连上 {args.address}:{MATCH_PORT}", flush=True)
    run_client_session(Peer(sock), args.name)


def cmd_browse(args):
    print(f"浏览 {SERVICE_TYPE}（Ctrl-C 停止）……", flush=True)
    subprocess.run(["dns-sd", "-B", SERVICE_TYPE])


def main():
    parser = argparse.ArgumentParser(description="SpaceFlightChess 局域网陪练对端")
    sub = parser.add_subparsers(dest="command", required=True)

    join = sub.add_parser("join", help="扮演客户端连到一个房间")
    join.add_argument("address", help="房主的 IPv4 地址")
    join.add_argument("--name", default="MacPeer")
    join.set_defaults(func=cmd_join)

    host = sub.add_parser("host", help="扮演房主等待加入")
    host.set_defaults(func=cmd_host)

    browse = sub.add_parser("browse", help="列出局域网里广播的房间")
    browse.set_defaults(func=cmd_browse)

    args = parser.parse_args()
    try:
        args.func(args)
    except KeyboardInterrupt:
        print("\n被 Ctrl-C 中断（对端应当进入等待重连）", flush=True)


if __name__ == "__main__":
    main()
```

- [ ] **Step 2: 冒烟测试脚本本身**

```bash
chmod +x tools/lan_peer.py
python3 tools/lan_peer.py --help
python3 tools/lan_peer.py join --help
```

预期：打印帮助，无 traceback。

- [ ] **Step 3: 验证 `dns-sd` 可用（Task 14 的 NSD 验证依赖它）**

```bash
which dns-sd && dns-sd -B _spaceflightchess._tcp &
sleep 3 && kill %1
```

预期：`dns-sd` 存在（macOS 自带 Bonjour），命令能启动且不报错。此时还没有任何房间，列表为空是正常的。

- [ ] **Step 4: 提交**

```bash
git add tools/lan_peer.py
git commit -m "test(tools): Mac 侧局域网陪练对端

只有一台真机的条件下唯一的端到端验证手段：扮演房主或客户端跑完整协议，
Ctrl-C 即可模拟掉线。同时是协议一致性检查器，收到词表外的消息直接报错退出。"
```

---

### Task 8: 房主端网络（`LanMatchHost`）

NSD 注册、`ServerSocket` 监听、至多 3 条客户端连接、按连接读请求、向全体广播。**它不持有 `GameEngine`**——权威 engine 只有 `BoardStage` 里那一个，这个类只做传输和协议。

**Files:**
- Create: `app/src/main/java/tech/illusion/spaceflightchess/content/LanMatchHost.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `MatchMessage`、`RoomState`、`SeatAssigner`、`Occupant`、`Presence`、常量（Task 3–5）
- Produces（Task 11 依赖）：
  - `class LanMatchHost(context: Context, onRoom: (RoomState) -> Unit, onRequest: (Team, MatchMessage) -> Unit, onFailure: (String) -> Unit)`
  - `var room: RoomState`（只读用途，房主自己维护）
  - `fun start(scope: CoroutineScope, hostTeam: Team, hostName: String)`
  - `fun broadcast(message: MatchMessage)`
  - `fun sendTo(team: Team, message: MatchMessage)`
  - `fun lockSeatsForStart(): RoomState`
  - `fun localAddress(): String`
  - `fun cancel()`
  - 后续任务会在同一个类上追加：Task 13 给构造函数加 `onNeedsResync: (Team) -> Unit`，Task 14 加 `var inMatch: Boolean` 并重写 `markDropped`。本任务先按上面的签名落地即可。

- [ ] **Step 1: 加网络权限**

修改 `app/src/main/AndroidManifest.xml`，在现有 `<uses-permission android:name="com.picovr.permission.HAND_TRACKING" />` 下面加：

```xml
    <!-- 局域网联机：TCP 直连 + NSD（mDNS）服务发现。全部是同一局域网内的直连，不访问互联网。 -->
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <!-- NsdManager 的发现侧需要设备接收组播帧；不声明它，discoverServices 在部分设备上静默零结果。 -->
    <uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE" />
```

- [ ] **Step 2: 实现 LanMatchHost**

创建 `app/src/main/java/tech/illusion/spaceflightchess/content/LanMatchHost.kt`：

```kotlin
package tech.illusion.spaceflightchess.content

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.illusion.spaceflightchess.game.Team
import tech.illusion.spaceflightchess.net.EndReason
import tech.illusion.spaceflightchess.net.MATCH_PORT
import tech.illusion.spaceflightchess.net.MAX_CLIENTS
import tech.illusion.spaceflightchess.net.MatchMessage
import tech.illusion.spaceflightchess.net.Occupant
import tech.illusion.spaceflightchess.net.Presence
import tech.illusion.spaceflightchess.net.RoomState
import tech.illusion.spaceflightchess.net.SERVICE_TYPE
import tech.illusion.spaceflightchess.net.SeatAssigner
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import kotlin.random.Random

private const val TAG = "SpaceFlightChess"

/**
 * 房主侧的传输层：NSD 注册、`ServerSocket` 监听、至多 [MAX_CLIENTS] 条连接、按行收发。
 *
 * **刻意不持有 `GameEngine`。** 权威 engine 只有 `BoardStage` 里那一个；这个类收到请求就通过
 * [onRequest] 回调交出去，由 `BoardStage` 裁决后再调 [broadcast]。这和 SpaceGomoku 的
 * `LanMatchHost`（自带一个 engine）是有意的分歧——那样 AI driver、动画、门禁就得在两处各写一遍。
 *
 * 座位表由本类维护（它才知道谁连着、谁断了），每次变更都通过 [onRoom] 推给 `BoardStage`。
 *
 * 全部回调都在主线程上触发（读循环里用 `withContext(Dispatchers.Main)` 切回来），因为它们最终
 * 会写 Compose 状态和往 `TurnCommand` 队列里塞命令。
 */
class LanMatchHost(
    private val context: Context,
    private val onRoom: (RoomState) -> Unit,
    private val onRequest: (Team, MatchMessage) -> Unit,
    private val onFailure: (String) -> Unit,
) {

    var room: RoomState = RoomState.empty()
        private set

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var serverSocket: ServerSocket? = null
    private var scope: CoroutineScope? = null
    private var acceptJob: Job? = null

    /** 每个已入座的远程玩家对应一条连接。断线时移除，重连时重新放进来。 */
    private val connections = mutableMapOf<Team, Socket>()
    private val writers = mutableMapOf<Team, OutputStreamWriter>()

    /**
     * 掉线过的玩家名字 → 他原来的座位，用于重连时优先送回原位。
     * 不做身份令牌：局域网内是可信的两台设备，这条和「不做加密鉴权」是同一个前提。
     */
    private val lastSeatByName = mutableMapOf<String, Team>()

    fun start(scope: CoroutineScope, hostTeam: Team, hostName: String) {
        this.scope = scope
        room = SeatAssigner.host(RoomState.empty(), hostTeam, hostName)
        onRoom(room)

        val server = try {
            ServerSocket().apply {
                // 固定端口意味着上一局留下的 socket 可能还在 TIME_WAIT——不设这个，
                // 同一次会话里的第二次「创建房间」会直接失败。
                reuseAddress = true
                bind(InetSocketAddress(MATCH_PORT))
            }
        } catch (t: Throwable) {
            Log.e(TAG, "host: bind $MATCH_PORT failed", t)
            onFailure(EndReason.HOSTING_FAILED)
            return
        }
        serverSocket = server
        registerService("${Build.MODEL}-${Random.nextInt(1000, 9999)}")
        acceptJob = scope.launch(Dispatchers.IO) { acceptLoop(server) }
        Log.e(TAG, "host: listening on ${localAddress()}:$MATCH_PORT")
    }

    /**
     * 一直 accept。**不因为满员就停止监听**：满员时接进来的连接会被回一条 `ENDED 房间已满`
     * 然后关掉，而掉线的玩家重连时用的正是同一条 accept 路径——停掉它，重连就永远连不上。
     */
    private suspend fun acceptLoop(server: ServerSocket) {
        while (true) {
            val socket = try {
                server.accept()
            } catch (t: Throwable) {
                Log.e(TAG, "host: accept ended", t)
                return
            }
            Log.e(TAG, "host: accepted ${socket.inetAddress}")
            scope?.launch(Dispatchers.IO) { readLoop(socket) }
        }
    }

    /**
     * 一条连接的生命周期：先等一条 `JOIN` 才算入座，之后把每条请求交给 [onRequest]。
     * 读到 null（对端关闭）或抛 IOException 都走同一条断线路径。
     */
    private suspend fun readLoop(socket: Socket) {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
        var seat: Team? = null
        try {
            val first = reader.readLine() ?: return
            val join = MatchMessage.decode(first) as? MatchMessage.Join ?: run {
                Log.e(TAG, "host: first line was not JOIN: $first")
                return
            }
            seat = withContext(Dispatchers.Main) { admit(socket, join.name) } ?: return

            while (true) {
                val line = reader.readLine() ?: break
                val message = MatchMessage.decode(line) ?: run {
                    Log.e(TAG, "host: undecodable line from $seat: $line")
                    null
                } ?: continue
                Log.e(TAG, "host: ← $seat $line")
                withContext(Dispatchers.Main) { onRequest(seat!!, message) }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "host: read loop for $seat ended", t)
        } finally {
            runCatching { socket.close() }
            val dropped = seat
            if (dropped != null) {
                withContext(Dispatchers.Main) { markDropped(dropped) }
            }
        }
    }

    /** 主线程：给新连接安排座位并回 WELCOME + 广播 LOBBY。满员则回 ENDED 并关闭。 */
    private fun admit(socket: Socket, rawName: String): Team? {
        val name = RoomState.sanitizeName(rawName)
        val preferred = lastSeatByName[name]
        val result = if (preferred != null) {
            SeatAssigner.rejoin(room, preferred, name)
        } else {
            SeatAssigner.join(room, name)
        }
        if (result == null) {
            Log.e(TAG, "host: rejecting $name, room full")
            runCatching {
                OutputStreamWriter(socket.getOutputStream()).apply {
                    write(MatchMessage.Ended(EndReason.ROOM_FULL).encode() + "\n")
                    flush()
                }
                socket.close()
            }
            return null
        }
        val (updated, team) = result
        room = updated
        lastSeatByName[name] = team
        connections[team]?.let { old -> runCatching { old.close() } }
        connections[team] = socket
        writers[team] = OutputStreamWriter(socket.getOutputStream())
        Log.e(TAG, "host: $name seated at $team (humans=${room.humanCount()})")

        sendTo(team, MatchMessage.Welcome(team, room))
        broadcast(MatchMessage.Lobby(room))
        onRoom(room)
        return team
    }

    /**
     * 主线程：某条连接断了。座位保留、标成 [Presence.DROPPED]，对局照常进行——
     * 只有轮到他且等待窗口耗尽，`BoardStage` 才会把它推到 [Presence.AI_TAKEOVER]。
     */
    private fun markDropped(team: Team) {
        connections.remove(team)
        writers.remove(team)
        val seat = room.occupant(team)
        if (seat !is Occupant.Human) return
        // 已经在 AI 托管里的座位不要退回 DROPPED——那会让它重新获得一次等待窗口。
        if (seat.presence == Presence.AI_TAKEOVER) return
        room = SeatAssigner.setPresence(room, team, Presence.DROPPED)
        Log.e(TAG, "host: $team dropped")
        broadcast(MatchMessage.SeatUpdate(team, Presence.DROPPED))
        broadcast(MatchMessage.Lobby(room))
        onRoom(room)
    }

    /** 主线程：由 `BoardStage` 调用，改动座位表（换座、托管、回归）后广播出去。 */
    fun updateRoom(newRoom: RoomState) {
        room = newRoom
        broadcast(MatchMessage.Lobby(room))
        onRoom(room)
    }

    /** 房主点开始：空位固化成 AI。返回固化后的座位表供 `BoardStage` 用。 */
    fun lockSeatsForStart(): RoomState {
        room = SeatAssigner.lockForStart(room)
        broadcast(MatchMessage.Start(room))
        onRoom(room)
        return room
    }

    fun broadcast(message: MatchMessage) {
        val line = message.encode()
        Log.e(TAG, "host: → ALL $line")
        writers.keys.toList().forEach { team -> write(team, line) }
    }

    fun sendTo(team: Team, message: MatchMessage) {
        val line = message.encode()
        Log.e(TAG, "host: → $team $line")
        write(team, line)
    }

    private fun write(team: Team, line: String) {
        val writer = writers[team] ?: return
        // 写在调用线程上（主线程）。一行几十字节写进本地 socket 缓冲区不会阻塞到可感知的程度，
        // 而为四条连接各起一个 writer 协程 + outbox 通道，是这个消息量撑不起的复杂度。
        runCatching {
            writer.write(line + "\n")
            writer.flush()
        }.onFailure {
            Log.e(TAG, "host: write to $team failed", it)
            markDropped(team)
        }
    }

    /** 本机在局域网里的 IPv4，显示给对方手输。拿不到就回一个明确的占位串而不是空。 */
    fun localAddress(): String = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it.address.size == 4 }
            ?.hostAddress
    }.getOrNull() ?: "地址不可用"

    private fun registerService(roomName: String) {
        val info = NsdServiceInfo().apply {
            serviceName = roomName
            serviceType = SERVICE_TYPE
            port = MATCH_PORT
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.e(TAG, "host: NSD registered as ${info.serviceName}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                // 注册失败不等于房间不可用：手动输入地址那条路仍然通。所以只记日志，不结束房间。
                Log.e(TAG, "host: NSD registration failed code=$errorCode (手动输入地址仍可加入)")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        registrationListener = listener
        runCatching { nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { Log.e(TAG, "host: registerService threw", it) }
    }

    /** 关掉一切。`BoardStage` 的 `onDispose` 与「解散房间」都要调用。 */
    fun cancel() {
        registrationListener?.let { listener ->
            runCatching { nsdManager.unregisterService(listener) }
            registrationListener = null
        }
        acceptJob?.cancel()
        acceptJob = null
        connections.values.forEach { runCatching { it.close() } }
        connections.clear()
        writers.clear()
        runCatching { serverSocket?.close() }
        serverSocket = null
        room = RoomState.empty()
        lastSeatByName.clear()
        Log.e(TAG, "host: cancelled")
    }
}
```

- [ ] **Step 3: 编译**

```bash
export PICO_HOME='/Users/zohar/Library/PICO/sdk'
export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
./gradlew assembleDebug && ./gradlew testDebugUnitTest
```

预期：构建成功，单测全绿（本任务不新增单测——这个类持有 `NsdManager`/`Socket`，和 `TableSurfaceScanner` 一样靠设备验证覆盖）。

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/tech/illusion/spaceflightchess/content/LanMatchHost.kt app/src/main/AndroidManifest.xml
git commit -m "feat(net): 房主端传输层

NSD 注册 + ServerSocket + 至多 3 条连接 + 按行收发。不持有 GameEngine——
权威 engine 只有 BoardStage 那一个，本类只做传输与座位表维护。
accept 循环满员也不停，因为掉线重连走的是同一条路径。"
```

---

### Task 9: 客户端网络（`LanMatchClient`）

NSD 发现、连接（发现列表或手输地址两条路）、按行读广播、发请求、断线后无限重试重连。

**Files:**
- Create: `app/src/main/java/tech/illusion/spaceflightchess/content/LanMatchClient.kt`

**Interfaces:**
- Consumes: `MatchMessage`、`RoomState`、`DiscoveredRoom`、常量（Task 3–5）
- Produces（Task 12 依赖）：
  - `class LanMatchClient(context: Context, onRooms: (List<DiscoveredRoom>) -> Unit, onMessage: (MatchMessage) -> Unit, onConnectionChanged: (Boolean) -> Unit, onFailure: (String) -> Unit)`
  - `fun startDiscovery(scope: CoroutineScope)`、`fun stopDiscovery()`
  - `fun join(scope: CoroutineScope, room: DiscoveredRoom, myName: String)`
  - `fun joinManually(scope: CoroutineScope, address: String, myName: String)`
  - `fun send(message: MatchMessage)`
  - `fun cancel()`
  - `LanMatchClient.Companion.isValidAddress(text: String): Boolean`

- [ ] **Step 1: 写地址校验的单测**

数字键盘每按一次就要判断「连接」按钮能不能点亮，这是这个类里唯一不需要真 socket 就能测的部分。

创建 `app/src/test/java/tech/illusion/spaceflightchess/net/AddressValidationTest.kt`：

```kotlin
package tech.illusion.spaceflightchess.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.illusion.spaceflightchess.content.LanMatchClient

class AddressValidationTest {

    @Test
    fun `完整的四段点分十进制是合法的`() {
        listOf("192.168.1.23", "10.0.2.16", "127.0.0.1", "0.0.0.0", "255.255.255.255")
            .forEach { assertTrue(it, LanMatchClient.isValidAddress(it)) }
    }

    @Test
    fun `半截地址不合法——按键过程中按钮必须是灰的`() {
        listOf("", "192", "192.", "192.168", "192.168.1", "192.168.1.")
            .forEach { assertFalse(it, LanMatchClient.isValidAddress(it)) }
    }

    @Test
    fun `越界的段不合法`() {
        listOf("256.1.1.1", "1.1.1.256", "999.0.0.1", "1.1.1.1000")
            .forEach { assertFalse(it, LanMatchClient.isValidAddress(it)) }
    }

    @Test
    fun `主机名和 IPv6 一律拒绝`() {
        // 刻意比 InetAddress.getByName 严格：那个会去解析主机名，把半截输入变成主线程上的 DNS 阻塞。
        listOf("localhost", "pico.local", "::1", "192.168.1.a", "192 168 1 1")
            .forEach { assertFalse(it, LanMatchClient.isValidAddress(it)) }
    }

    @Test
    fun `段数多了少了都不合法`() {
        listOf("1.1.1.1.1", "1.1.1", "....").forEach { assertFalse(it, LanMatchClient.isValidAddress(it)) }
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
./gradlew testDebugUnitTest --tests '*AddressValidationTest*'
```

预期：编译失败，`Unresolved reference: LanMatchClient`。

- [ ] **Step 3: 实现 LanMatchClient**

创建 `app/src/main/java/tech/illusion/spaceflightchess/content/LanMatchClient.kt`：

```kotlin
package tech.illusion.spaceflightchess.content

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.illusion.spaceflightchess.net.DiscoveredRoom
import tech.illusion.spaceflightchess.net.EndReason
import tech.illusion.spaceflightchess.net.MATCH_PORT
import tech.illusion.spaceflightchess.net.MatchMessage
import tech.illusion.spaceflightchess.net.RoomState
import tech.illusion.spaceflightchess.net.SERVICE_TYPE
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket

private const val TAG = "SpaceFlightChess"

/** 重连重试的间隔。局域网内一次 connect 失败很快返回，几秒一试既不吵也不慢。 */
private const val RECONNECT_RETRY_MS = 3000L

/** 单次 connect 的超时。比重试间隔短，保证重试节奏是由上面那个常量决定的。 */
private const val CONNECT_TIMEOUT_MS = 2000

/**
 * 客户端侧的传输层：NSD 发现、连接、按行读房主的广播、发请求、断线后无限重试。
 *
 * 和房主一样不持有 `GameEngine`——收到的消息通过 [onMessage] 交给 `BoardStage`，由它决定往
 * `TurnCommand` 队列里塞什么。全部回调都切回主线程。
 *
 * **重连不设总时长上限、也不弹「继续等待/结束对局」**：掉线只会让自己这一席在轮到时被 AI 托管，
 * 不阻塞其他三个人，所以没有必要逼玩家做那个选择（这是和 SpaceGomoku 的一处刻意分歧）。
 */
class LanMatchClient(
    private val context: Context,
    private val onRooms: (List<DiscoveredRoom>) -> Unit,
    private val onMessage: (MatchMessage) -> Unit,
    private val onConnectionChanged: (Boolean) -> Unit,
    private val onFailure: (String) -> Unit,
) {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val discovered = mutableMapOf<String, NsdServiceInfo>()

    private var socket: Socket? = null
    private var writer: OutputStreamWriter? = null
    private var sessionJob: Job? = null

    /** 连上一次之后缓存下来，重连时不再走发现流程。 */
    private var lastAddress: String? = null
    private var myName: String = "PLAYER"

    // ── 发现 ────────────────────────────────────────────────────────────────
    fun startDiscovery(scope: CoroutineScope) {
        stopDiscovery()
        discovered.clear()
        onRooms(emptyList())
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.e(TAG, "client: discovery started")
            }

            override fun onServiceFound(info: NsdServiceInfo) {
                Log.e(TAG, "client: found ${info.serviceName}")
                resolve(info)
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                discovered.remove(info.serviceName)
                scope.launch { publishRooms() }
            }

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "client: start discovery failed code=$errorCode")
                onFailure(EndReason.DISCOVERY_FAILED)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        discoveryListener = listener
        runCatching { nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure {
                Log.e(TAG, "client: discoverServices threw", it)
                onFailure(EndReason.DISCOVERY_FAILED)
            }
    }

    private fun resolve(info: NsdServiceInfo) {
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "client: resolve failed for ${info.serviceName} code=$errorCode")
            }

            override fun onServiceResolved(resolved: NsdServiceInfo) {
                discovered[resolved.serviceName] = resolved
                publishRooms()
            }
        }
        runCatching { nsdManager.resolveService(info, listener) }
            .onFailure { Log.e(TAG, "client: resolveService threw", it) }
    }

    private fun publishRooms() {
        onRooms(discovered.values.map { DiscoveredRoom(it.serviceName, it.serviceName) })
    }

    /**
     * 停止发现。进入手动输入界面时必须调用——留着它会不断推新的房间列表，
     * 把玩家正在输的那个界面从状态上覆盖掉。
     */
    fun stopDiscovery() {
        discoveryListener?.let { listener ->
            runCatching { nsdManager.stopServiceDiscovery(listener) }
            discoveryListener = null
        }
    }

    // ── 连接 ────────────────────────────────────────────────────────────────
    fun join(scope: CoroutineScope, room: DiscoveredRoom, myName: String) {
        val info = discovered[room.id] ?: run {
            onFailure(EndReason.RESOLVE_FAILED)
            return
        }
        val address = info.host?.hostAddress ?: run {
            onFailure(EndReason.RESOLVE_FAILED)
            return
        }
        // 端口用服务通告里的那个而不是常量：房主目前固定绑 MATCH_PORT，但发现路径本来就带端口，
        // 用它就不会因为将来改了端口策略而失效。
        connect(scope, address, info.port.takeIf { it > 0 } ?: MATCH_PORT, myName)
    }

    fun joinManually(scope: CoroutineScope, address: String, myName: String) {
        stopDiscovery()
        connect(scope, address, MATCH_PORT, myName)
    }

    private fun connect(scope: CoroutineScope, address: String, port: Int, name: String) {
        lastAddress = address
        myName = RoomState.sanitizeName(name)
        sessionJob?.cancel()
        sessionJob = scope.launch(Dispatchers.IO) { session(address, port, isReconnect = false) }
    }

    /**
     * 一次连接的完整生命周期，失败即重试。
     *
     * [isReconnect] 只影响首次失败的表现：第一次加入失败要立刻告诉玩家「无法连接到房间」并退回，
     * 而对局中掉线后的重试是后台无限进行的，不该每三秒弹一次错误。
     */
    private suspend fun session(address: String, port: Int, isReconnect: Boolean) {
        while (true) {
            val connected = try {
                Socket().apply { connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS) }
            } catch (t: Throwable) {
                Log.e(TAG, "client: connect to $address:$port failed", t)
                if (!isReconnect) {
                    withContext(Dispatchers.Main) { onFailure(EndReason.CONNECT_FAILED) }
                    return
                }
                delay(RECONNECT_RETRY_MS)
                continue
            }

            socket = connected
            writer = OutputStreamWriter(connected.getOutputStream())
            Log.e(TAG, "client: connected to $address:$port")
            withContext(Dispatchers.Main) { onConnectionChanged(true) }
            send(MatchMessage.Join(myName))

            try {
                val reader = BufferedReader(InputStreamReader(connected.getInputStream()))
                while (true) {
                    val line = reader.readLine() ?: break
                    Log.e(TAG, "client: ← $line")
                    val message = MatchMessage.decode(line)
                    if (message == null) {
                        Log.e(TAG, "client: undecodable line: $line")
                        continue
                    }
                    withContext(Dispatchers.Main) { onMessage(message) }
                    if (message is MatchMessage.Ended) {
                        runCatching { connected.close() }
                        return
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "client: read loop ended", t)
            }

            runCatching { connected.close() }
            socket = null
            writer = null
            withContext(Dispatchers.Main) { onConnectionChanged(false) }
            Log.e(TAG, "client: disconnected, retrying in ${RECONNECT_RETRY_MS}ms")
            delay(RECONNECT_RETRY_MS)
            // 之后的每一轮都是重连：不再因为一次失败就报错退出，无限重试直到成功或玩家主动离开。
        }
    }

    fun send(message: MatchMessage) {
        val line = message.encode()
        val out = writer ?: run {
            Log.e(TAG, "client: dropped (not connected) → $line")
            return
        }
        Log.e(TAG, "client: → $line")
        runCatching {
            out.write(line + "\n")
            out.flush()
        }.onFailure { Log.e(TAG, "client: write failed", it) }
    }

    fun cancel() {
        stopDiscovery()
        sessionJob?.cancel()
        sessionJob = null
        runCatching { socket?.close() }
        socket = null
        writer = null
        discovered.clear()
        lastAddress = null
        Log.e(TAG, "client: cancelled")
    }

    companion object {
        /**
         * 数字键盘上输的这串东西能不能拿去连接——即「连接」按钮是不是该点亮。
         *
         * 刻意比 `InetAddress.getByName` 严格：那个会解析主机名，会把半截输入 "192.168" 变成
         * 主线程上的一次阻塞 DNS 查询。键盘只能产出数字和点，四段十进制就是全部合法域。
         */
        fun isValidAddress(text: String): Boolean {
            val parts = text.split('.')
            if (parts.size != 4) return false
            return parts.all { part ->
                part.isNotEmpty() && part.length <= 3 && part.all(Char::isDigit) && part.toInt() <= 255
            }
        }
    }
}
```

- [ ] **Step 4: 跑测试确认通过并编译**

```bash
export PICO_HOME='/Users/zohar/Library/PICO/sdk'
export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
./gradlew testDebugUnitTest && ./gradlew assembleDebug
```

预期：`AddressValidationTest` 的 5 个测试 PASS，全量单测全绿，APK 构建成功。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/tech/illusion/spaceflightchess/content/LanMatchClient.kt \
        app/src/test/java/tech/illusion/spaceflightchess/net/AddressValidationTest.kt
git commit -m "feat(net): 客户端传输层

NSD 发现 + 两条加入路径（列表 / 手输地址）+ 按行读广播 + 断线无限重试。
手动输入时停掉发现，否则发现回调会把玩家正在输的界面覆盖掉。"
```

---

### Task 10: 大厅 UI（门禁 A 先行）

新增三个面板。**必须先过 `spatial-design-first-build` 的门禁 A**：把元素表写进设计契约、由用户逐项确认，然后才动 UI 代码。

**Files:**
- Modify: `.spatialsdk/design-contract.md`（新增 v2 增量）
- Create: `app/src/main/java/tech/illusion/spaceflightchess/content/LobbyPanels.kt`
- Modify: `app/src/main/java/tech/illusion/spaceflightchess/content/Panels.kt`（`StartPanel` 加两个入口）

**Interfaces:**
- Consumes: `RoomState`、`Occupant`、`Presence`、`DiscoveredRoom`、`BattleFlowState`、`LanMatchClient.isValidAddress`
- Produces（Task 11/12 依赖）：
  - `@Composable fun LobbyPanel(room: RoomState, myTeam: Team, isHost: Boolean, localAddress: String?, onPickSeat: (Team) -> Unit, onStart: () -> Unit, onLeave: () -> Unit)`
  - `@Composable fun RoomListPanel(rooms: List<DiscoveredRoom>, onPick: (DiscoveredRoom) -> Unit, onManualEntry: () -> Unit, onCancel: () -> Unit)` —— 每行只显示 `displayName`，**不显示人数**（见 `DiscoveredRoom` 的类注释：连上之前拿不到）
  - `@Composable fun ManualAddressPanel(onConnect: (String) -> Unit, onCancel: () -> Unit)`
  - `StartPanel` 签名改为 `StartPanel(onStart: (Team) -> Unit, onCreateRoom: (Team) -> Unit, onFindRoom: () -> Unit)`

- [ ] **Step 1: 写设计契约增量并请用户确认（门禁 A，阻塞）**

在 `.spatialsdk/design-contract.md` 末尾追加一节 `# 设计契约增量 v2 — 局域网联机大厅`，按现有 v1 的表格格式写清楚：

- **面板清单**：三个新 `AttachmentPanel`（`lobby` / `roomList` / `manualAddress`），沿用 `GlassPanel`，尺寸建议 `LobbyPanel` 400×320dp（四行座位 + 底部按钮行）、`RoomListPanel` 360×300dp、`ManualAddressPanel` 320×360dp（地址行 + 4×3 键盘）。
- **元素表**：每个面板逐元素列 id / 类型 / 父容器 / 锚点 / 尺寸 / 颜色角色 / 字体角色 / 圆角，颜色只用 `PicoTheme.colorScheme.*`，字体只用 `PicoTheme.typography.*`。座位色块复用 `Panels.kt` 现有的 `swatchFor(team)`。
- **状态清单**：座位行的四种状态（本地玩家 / 远程在线 / 远程掉线 / 远程 AI 托管 / 空 / AI）各自的视觉差异；「开始游戏」在房主/非房主下的启用与替换文案；键盘「连接」按钮在地址不合法时的禁用态（`enabled = false`，**不要传自定义 `ButtonSize`**——本工作区记录过自定义 `ButtonSize` 会静默丢圆角）。
- **文案**：逐字写死，包括 `EndReason` 里那几条。

写完后**停下来把这一节交给用户逐项确认**，不要直接开始写 UI 代码。用户确认后在契约里记下确认日期。

- [ ] **Step 2: 实现 LobbyPanels.kt**

创建 `app/src/main/java/tech/illusion/spaceflightchess/content/LobbyPanels.kt`，按门禁 A 确认过的元素表实现三个 composable。**硬性约束**：

- 只用 `com.pico.spatial.ui.*` + `PicoTheme`，禁止任何 `androidx.compose.material*` import。
- 复用 `Panels.kt` 里已有的私有 `GlassPanel`（把它从 `private` 改成 `internal` 以便跨文件使用，或把三个新面板直接写进 `Panels.kt`——二选一，在实现时按文件大小决定；`Panels.kt` 已 417 行，倾向新文件 + `internal`）。
- 座位行的行高不要用 `ListItem` 的 `trailingContent`——本工作区记录过它有 144px 行高下限，四行会放不下。用单行 `Row` + `Arrangement.SpaceBetween`。
- 数字键盘是自绘的 4×3 `Button` 网格（`1-9`、`.`、`0`、`⌫`），不依赖系统 IME。

座位行的状态到文案的映射（这部分是纯逻辑，直接写死在文件里）：

```kotlin
/** 一行座位右侧显示什么。四种真人状态、空位、AI 各一种说法。 */
internal fun seatLabel(occupant: Occupant, isMe: Boolean): String = when (occupant) {
    is Occupant.Empty -> "空 · 开局补 AI"
    is Occupant.Ai -> "AI"
    is Occupant.Human -> {
        val who = if (isMe) "你" else occupant.name
        val host = if (occupant.isHost) "（房主）" else ""
        val suffix = when (occupant.presence) {
            Presence.ONLINE -> ""
            Presence.DROPPED -> " · 已断线"
            Presence.AI_TAKEOVER -> " · AI 托管中"
        }
        "$who$host$suffix"
    }
}
```

- [ ] **Step 3: 给 seatLabel 补单测**

创建 `app/src/test/java/tech/illusion/spaceflightchess/net/SeatLabelTest.kt`：

```kotlin
package tech.illusion.spaceflightchess.net

import org.junit.Assert.assertEquals
import org.junit.Test
import tech.illusion.spaceflightchess.content.seatLabel

class SeatLabelTest {

    @Test
    fun `空位标明开局会补 AI`() {
        assertEquals("空 · 开局补 AI", seatLabel(Occupant.Empty, isMe = false))
    }

    @Test
    fun `固化的 AI 就写 AI`() {
        assertEquals("AI", seatLabel(Occupant.Ai, isMe = false))
    }

    @Test
    fun `自己那一行写你而不是设备名`() {
        assertEquals("你（房主）", seatLabel(Occupant.Human("Zohar", isHost = true), isMe = true))
    }

    @Test
    fun `别人写设备名`() {
        assertEquals("PICO-4821", seatLabel(Occupant.Human("PICO-4821", isHost = false), isMe = false))
    }

    @Test
    fun `掉线和托管是两种不同的说法`() {
        val dropped = Occupant.Human("B", isHost = false, presence = Presence.DROPPED)
        val takeover = Occupant.Human("B", isHost = false, presence = Presence.AI_TAKEOVER)
        assertEquals("B · 已断线", seatLabel(dropped, isMe = false))
        assertEquals("B · AI 托管中", seatLabel(takeover, isMe = false))
    }
}
```

- [ ] **Step 4: 改 StartPanel 加两个入口**

修改 `app/src/main/java/tech/illusion/spaceflightchess/content/Panels.kt` 的 `StartPanel`：

```kotlin
fun StartPanel(
    onStart: (Team) -> Unit,
    onCreateRoom: (Team) -> Unit,
    onFindRoom: () -> Unit,
) {
```

把现在那句「你是XX，对手是其余三个 AI」的说明文案保留，在「开始游戏」按钮下方新增一行两个按钮：

```kotlin
            Spacer(Modifier.size(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { showHowto = false; onCreateRoom(selected) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PicoTheme.colorScheme.fillPrimary.copy(alpha = 0.3f),
                        contentColor = PicoTheme.colorScheme.labelPrimaryLight,
                    ),
                ) {
                    Text("创建房间", style = PicoTheme.typography.labelLarge)
                }
                Button(
                    onClick = { showHowto = false; onFindRoom() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PicoTheme.colorScheme.fillPrimary.copy(alpha = 0.3f),
                        contentColor = PicoTheme.colorScheme.labelPrimaryLight,
                    ),
                ) {
                    Text("查找房间", style = PicoTheme.typography.labelLarge)
                }
            }
```

「创建房间」把当前选中的阵营带过去——房主直接坐进他选的那个颜色。

- [ ] **Step 5: 在 BoardStage 里挂上三个新面板**

`BoardStage.kt` 顶部加三个 id 常量：

```kotlin
private const val LOBBY_PANEL = "lobby"
private const val ROOM_LIST_PANEL = "roomList"
private const val MANUAL_ADDRESS_PANEL = "manualAddress"
```

`PANEL_IDS` 与 `PANEL_OFFSETS` 两个列表都要追加对应项（两者按下标一一对应，长度必须相等，漏掉一个会让新面板挂在原点）。三个新面板与 `START_PANEL` 互斥出现，可以复用它的偏移量。

先用**假数据**把三个面板渲染出来（`BattleFlowState` 暂时用一个本地 `remember` 的常量驱动），本任务只验布局，接线在 Task 11/12。

- [ ] **Step 6: 编译 + 单测 + 模拟器截图验证（门禁 B 的第一次）**

```bash
export PICO_HOME='/Users/zohar/Library/PICO/sdk'
export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
./gradlew testDebugUnitTest && ./gradlew assembleDebug
bash ../.claude/skills/spatial-design-first-build/scripts/device-lock.sh acquire "lan-task10" 120 emulator-5554
pico-cli app install app/build/outputs/apk/debug/app-debug.apk --device emulator-5554
pico-cli app launch tech.illusion.spaceflightchess --device emulator-5554
sleep 8
adb -s emulator-5554 shell dumpsys activity activities | grep -a mFocusedApp   # 确认前台真的是本应用
adb -s emulator-5554 exec-out screencap -p > .spatialsdk/ui-verify/lobby-01.png
pico-cli app stop tech.illusion.spaceflightchess --device emulator-5554
bash ../.claude/skills/spatial-design-first-build/scripts/device-lock.sh release "lan-task10" emulator-5554
```

用 `adb exec-out screencap` 而不是 `pico-cli capture screenshot`：本工作区记录过在模拟器上前者能拍到空间内容而后者拍不到。截图可能是陈旧帧（实测差过 ≥6 秒），所以启动后要等够时间，并且和日志时间戳交叉核对。

看截图核对：四行座位是否都在框内不被裁切、右侧状态文字是否被挤成两行、底部按钮行是否溢出、数字键盘 4×3 是否方正。

- [ ] **Step 7: 提交**

```bash
git add .spatialsdk/design-contract.md app/src/main/java/tech/illusion/spaceflightchess/content/LobbyPanels.kt \
        app/src/main/java/tech/illusion/spaceflightchess/content/Panels.kt \
        app/src/main/java/tech/illusion/spaceflightchess/content/BoardStage.kt \
        app/src/test/java/tech/illusion/spaceflightchess/net/SeatLabelTest.kt \
        .spatialsdk/ui-verify/
git commit -m "feat(ui): 大厅、房间列表、数字键盘三个面板

设计契约 v2 已过门禁 A。座位行用单行 Row + SpaceBetween 而不是 ListItem，
避开 trailingContent 的 144px 行高下限（四行放不下）。"
```

---

### Task 11: 接线房主角色

把 `LanMatchHost` 接进 `BoardStage`：建房、大厅、开局、裁决请求、广播。做完这一步，**用 Task 7 的陪练脚本以客户端身份连进来就能打完一整局**。

**Files:**
- Modify: `app/src/main/java/tech/illusion/spaceflightchess/content/BoardStage.kt`

**Interfaces:**
- Consumes: `LanMatchHost`（Task 8）、`LobbyPanel`（Task 10）、`TurnCommand`/`execute`（Task 6）
- Produces（Task 12/13 依赖）：`BoardStage` 内部状态 `var flow by remember { mutableStateOf<BattleFlowState>(BattleFlowState.SinglePlayer) }`、`var myTeam`、`var isHost`

- [ ] **Step 1: 加联机状态**

在 `BoardStage()` 里，`humanTeam` 附近加：

```kotlin
    /** 联机流程走到哪一步。SinglePlayer 时整套联机逻辑全部休眠。 */
    var flow by remember { mutableStateOf<BattleFlowState>(BattleFlowState.SinglePlayer) }

    /** 房主维护的座位表；客户端从 LOBBY/START 广播里拿。单机时恒为 empty。 */
    var room by remember { mutableStateOf(RoomState.empty()) }

    // 房主建房后直接进 InLobby（面板上只有他自己），不需要一个单独的「已建房、还没人来」状态。
    val isHost = (flow as? BattleFlowState.InLobby)?.isHost == true ||
        (flow as? BattleFlowState.InMatch)?.isHost == true

    val isOnline = flow !is BattleFlowState.SinglePlayer
```

`humanTeam` 在联机模式下就是「我坐哪」，沿用同一个变量——渲染层、座位旋转、HUD 全都已经在读它，不要再引入第二个概念。

- [ ] **Step 2: 创建 LanMatchHost 实例并接回调**

```kotlin
    val lanHost = remember {
        LanMatchHost(
            context = context,
            onRoom = { updated ->
                room = updated
                // 大厅期间把新座位表推进 flow，让 LobbyPanel 重绘。
                val current = flow
                if (current is BattleFlowState.InLobby) flow = current.copy(room = updated)
                if (current is BattleFlowState.InMatch) flow = current.copy(room = updated)
            },
            onRequest = { seat, message -> handleClientRequest(seat, message) },
            onFailure = { reason -> flow = BattleFlowState.Ended(reason) },
        )
    }
```

- [ ] **Step 3: 写请求裁决函数**

放在 `execute` 定义之后（它要用 `commands`）：

```kotlin
    /**
     * 房主对一条客户端请求的裁决。**唯一的权威判断点。**
     *
     * 三条硬性校验：现在是不是那个阶段、请求方是不是当前队伍、请求的走法在不在候选里。
     * 任何一条不过就静默丢弃并记日志——局域网内是可信设备，不因为一次校验失败就断连
     * （客户端本来就在发送前用本地 engine 自我把关过，真触发说明有竞态，不是恶意）。
     */
    fun handleClientRequest(seat: Team, message: MatchMessage) {
        when (message) {
            is MatchMessage.SeatRequest -> {
                if (flow !is BattleFlowState.InLobby) {
                    Log.e(TAG, "host: seat request from $seat outside lobby, ignored")
                    return
                }
                lanHost.updateRoom(SeatAssigner.requestSeat(lanHost.room, from = seat, to = message.team))
            }

            MatchMessage.RollRequest -> {
                if (engine.phase != Phase.AWAITING_ROLL || engine.state.currentTeam != seat) {
                    Log.e(
                        TAG,
                        "host: roll request rejected: from=$seat phase=${engine.phase} " +
                            "currentTeam=${engine.state.currentTeam}",
                    )
                    return
                }
                commands.trySend(TurnCommand.Roll(seat, value = null))
            }

            is MatchMessage.MoveRequest -> {
                if (engine.phase != Phase.AWAITING_MOVE || engine.state.currentTeam != seat) {
                    Log.e(
                        TAG,
                        "host: move request rejected: from=$seat phase=${engine.phase} " +
                            "currentTeam=${engine.state.currentTeam}",
                    )
                    return
                }
                val legal = engine.legalMovesForPendingRoll().any { it.pieceIndex == message.pieceIndex }
                if (!legal) {
                    Log.e(TAG, "host: move request $seat/${message.pieceIndex} not in candidates, ignored")
                    return
                }
                commands.trySend(TurnCommand.Apply(seat, message.pieceIndex))
            }

            MatchMessage.ResyncRequest -> {
                // 只在回合之间补：forceState 的语义就是跳到回合起点，中途同步会丢掉待决点数。
                if (engine.phase != Phase.AWAITING_ROLL) {
                    Log.e(TAG, "host: resync deferred, phase=${engine.phase}")
                    pendingResyncFor += seat
                    return
                }
                lanHost.sendTo(seat, MatchMessage.Sync(engine.state, engine.consecutiveSixesForSync, lanHost.room))
            }

            MatchMessage.Leave -> lanHost.updateRoom(SeatAssigner.leave(lanHost.room, seat))

            else -> Log.e(TAG, "host: unexpected message from $seat: ${message.encode()}")
        }
    }
```

这里用到两个还不存在的东西，本步一并加上：

1. `var pendingResyncFor by remember { mutableStateOf(setOf<Team>()) }` —— 等回合间隙再补 SYNC 的队伍集合，声明在 `flow` 附近。
2. `GameEngine` 需要暴露连 6 计数供 `SYNC` 用。在 `GameEngine.kt` 里把 `private var consecutiveSixes = 0` 保持不变，新增一个只读暴露：

```kotlin
    /**
     * 连摇 6 的当前计数，供联机 `SYNC` 携带。
     *
     * 单独暴露一个只读属性而不是把字段改成 public：写入仍然只能通过 [roll]/[forceState]/[restart]，
     * 外部拿到的是一份快照，不可能从别处把状态机的这个计数器改坏。
     */
    val consecutiveSixesForSync: Int get() = consecutiveSixes
```

- [ ] **Step 4: 在回合间隙补发 SYNC，并广播每一步**

把 Task 6 里那两个恒为 null 的钩子接上。在 `LaunchedEffect(Unit)`（命令消费循环）之前加：

```kotlin
    LaunchedEffect(isHost, isOnline) {
        onRolled = if (isOnline && isHost) {
            { team, value -> lanHost.broadcast(MatchMessage.Rolled(team, value)) }
        } else null
        onMoved = if (isOnline && isHost) {
            { team, pieceIndex, checksum -> lanHost.broadcast(MatchMessage.Moved(team, pieceIndex, checksum)) }
        } else null
    }
```

在 `execute` 的 `finally` 之后、`syncDieSlot()` 之前插入延迟补发：

```kotlin
        // 回合之间才补 SYNC——中途同步会丢掉待决点数与候选列表。
        if (isHost && pendingResyncFor.isNotEmpty() && engine.phase == Phase.AWAITING_ROLL) {
            val message = MatchMessage.Sync(engine.state, engine.consecutiveSixesForSync, lanHost.room)
            pendingResyncFor.forEach { lanHost.sendTo(it, message) }
            pendingResyncFor = emptySet()
        }
```

- [ ] **Step 5: 让 AI driver 认联机的座位规则**

把 Task 6 里那个 `isAiSeat` 改成：

```kotlin
    fun isAiSeat(team: Team): Boolean = when {
        // 客户端上 AI driver 完全不启动：它只被动接收房主的广播。
        isOnline && !isHost -> false
        isOnline -> room.isDrivenByAi(team)
        else -> team != humanTeam
    }
```

- [ ] **Step 6: 接上 StartPanel 的「创建房间」与大厅面板**

```kotlin
            AttachmentPanel(id = START_PANEL) {
                if (phase == Phase.SETUP && flow is BattleFlowState.SinglePlayer && !showExitConfirm) {
                    StartPanel(
                        onStart = ::startWithFaction,
                        onCreateRoom = { team ->
                            humanTeam = team
                            lanHost.start(coroutineScope, team, Build.MODEL)
                            flow = BattleFlowState.InLobby(
                                room = lanHost.room,
                                myTeam = team,
                                isHost = true,
                                localAddress = lanHost.localAddress(),
                            )
                        },
                        onFindRoom = { /* Task 12 接上 */ },
                    )
                }
            }
            AttachmentPanel(id = LOBBY_PANEL) {
                val lobby = flow as? BattleFlowState.InLobby
                if (lobby != null && !showExitConfirm) {
                    LobbyPanel(
                        room = lobby.room,
                        myTeam = lobby.myTeam,
                        isHost = lobby.isHost,
                        localAddress = lobby.localAddress,
                        onPickSeat = { target ->
                            if (lobby.isHost) {
                                lanHost.updateRoom(SeatAssigner.requestSeat(lanHost.room, lobby.myTeam, target))
                                // 房主换座后自己的颜色也变了。
                                lanHost.room.hostTeam()?.let { humanTeam = it; flow = lobby.copy(room = lanHost.room, myTeam = it) }
                            }
                            // 客户端分支在 Task 12
                        },
                        onStart = {
                            if (lobby.isHost) {
                                val locked = lanHost.lockSeatsForStart()
                                room = locked
                                flow = BattleFlowState.InMatch(locked, humanTeam, isHost = true)
                                startWithFaction(humanTeam)
                            }
                        },
                        onLeave = {
                            lanHost.broadcast(MatchMessage.Ended(EndReason.ROOM_CLOSED))
                            lanHost.cancel()
                            flow = BattleFlowState.SinglePlayer
                        },
                    )
                }
            }
```

`startWithFaction` 已有的 `if (engine.phase != Phase.SETUP || isSeating) return` 守卫保证它只跑一次，联机开局复用它一行不用改——转桌动画、`configureSeat`、`engine.startGame()` 全都照旧。

- [ ] **Step 7: dispose 清理**

在现有那个清理 renderer 的 `DisposableEffect` 里加一行 `lanHost.cancel()`，确保切后台/退出时 socket 和 NSD 注册不残留。

- [ ] **Step 8: 编译、单测、用陪练脚本验一局**

```bash
export PICO_HOME='/Users/zohar/Library/PICO/sdk'
export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
./gradlew testDebugUnitTest && ./gradlew assembleDebug
```

装到真机上（真机和 Mac 必须在同一路由器下），建房，读屏幕上显示的 IP，然后在 Mac 上：

```bash
python3 tools/lan_peer.py join <真机显示的IP>
```

预期在脚本输出里依次看到：`WELCOME`（带自己的队伍）→ `LOBBY` → 真机点开始后 `START`（空位已变 `A`）→ 之后每个回合的 `ROLLED` / `MOVED`。脚本自动摇骰和走子，真机侧应该能看到那一席的飞机在动。

同时捞真机日志交叉核对（`grep -a` 必须加，否则 logcat 里一个控制字节就会让 grep 静默输出零行）：

```bash
adb -s <真机序列号> logcat -d | grep -a SpaceFlightChess | tail -120
```

核对：`host: accepted` → `host: seated at` → `host: → ALL ROLLED ...` → `host: ← <team> MOVE_REQ ...` 这条链完整，且没有 `move request rejected` 之类的意外拒绝。

- [ ] **Step 9: 提交**

```bash
git add app/src/main/java/tech/illusion/spaceflightchess/content/BoardStage.kt \
        app/src/main/java/tech/illusion/spaceflightchess/game/GameEngine.kt
git commit -m "feat(stage): 接线房主角色

建房、大厅、开局固化 AI、裁决客户端请求、广播每一步。SYNC 只在回合间隙补发。
AI driver 改读 RoomState.isDrivenByAi，客户端上完全不启动。"
```

---

### Task 12: 接线客户端角色

**Files:**
- Modify: `app/src/main/java/tech/illusion/spaceflightchess/content/BoardStage.kt`

**Interfaces:**
- Consumes: `LanMatchClient`（Task 9）、`RoomListPanel`/`ManualAddressPanel`（Task 10）、`TurnCommand`（Task 6）

- [ ] **Step 1: 创建 LanMatchClient 实例并接消息处理**

```kotlin
    val lanClient = remember {
        LanMatchClient(
            context = context,
            onRooms = { rooms ->
                if (flow is BattleFlowState.Discovering) flow = BattleFlowState.Discovering(rooms)
            },
            onMessage = { message -> handleHostMessage(message) },
            onConnectionChanged = { connected ->
                if (!connected && flow is BattleFlowState.InMatch) {
                    Log.e(TAG, "client: link down, retrying in background")
                }
            },
            onFailure = { reason -> flow = BattleFlowState.Ended(reason) },
        )
    }
```

- [ ] **Step 2: 写房主消息的处理函数**

```kotlin
    /**
     * 客户端消费房主的广播。**本地点击永远不入队**——棋盘状态只由这里驱动，
     * 客户端绝不自己先走一步，这是不分叉的核心不变式。
     */
    fun handleHostMessage(message: MatchMessage) {
        when (message) {
            is MatchMessage.Welcome -> {
                humanTeam = message.yourTeam
                room = message.room
                flow = BattleFlowState.InLobby(message.room, message.yourTeam, isHost = false, localAddress = null)
            }

            is MatchMessage.Lobby -> {
                room = message.room
                val current = flow
                if (current is BattleFlowState.InLobby) {
                    // 房主可能把我挪了座；以广播为准，不信本地。
                    val mine = message.room.seats.entries
                        .firstOrNull { (_, occupant) ->
                            occupant is Occupant.Human && !occupant.isHost && occupant.name == RoomState.sanitizeName(Build.MODEL)
                        }?.key ?: current.myTeam
                    humanTeam = mine
                    flow = current.copy(room = message.room, myTeam = mine)
                }
                if (current is BattleFlowState.InMatch) flow = current.copy(room = message.room)
            }

            is MatchMessage.Start -> {
                room = message.room
                flow = BattleFlowState.InMatch(message.room, humanTeam, isHost = false)
                startWithFaction(humanTeam)
            }

            is MatchMessage.Rolled -> commands.trySend(TurnCommand.Roll(message.team, message.value))

            is MatchMessage.Moved -> {
                commands.trySend(TurnCommand.Apply(message.team, message.pieceIndex))
                expectedChecksum = message.checksum
            }

            is MatchMessage.SeatUpdate -> {
                room = SeatAssigner.setPresence(room, message.team, message.presence)
                val current = flow
                if (current is BattleFlowState.InMatch) flow = current.copy(room = room)
                if (current is BattleFlowState.InLobby) flow = current.copy(room = room)
            }

            is MatchMessage.Sync -> {
                room = message.room
                commands.trySend(TurnCommand.Resync(message.state, message.consecutiveSixes))
                flow = BattleFlowState.InMatch(message.room, humanTeam, isHost = false)
            }

            is MatchMessage.Ended -> {
                lanClient.cancel()
                flow = BattleFlowState.Ended(message.reason)
            }

            else -> Log.e(TAG, "client: unexpected message from host: ${message.encode()}")
        }
    }
```

配套加一个状态：`var expectedChecksum by remember { mutableStateOf<Int?>(null) }`。

- [ ] **Step 3: 在执行器里比对校验和**

在 `execute` 的 `TurnCommand.Apply` 分支里，`onMoved?.invoke(...)` 之后加：

```kotlin
                    // 客户端侧：房主刚告诉过我这一步之后局面该长什么样。对不上说明已经分叉，
                    // 立刻要一次全量同步——房主会等到回合间隙再补。
                    val expected = expectedChecksum
                    if (expected != null) {
                        expectedChecksum = null
                        val actual = StateChecksum.of(engine.state)
                        if (actual != expected) {
                            Log.e(TAG, "client: CHECKSUM MISMATCH expected=$expected actual=$actual, requesting resync")
                            lanClient.send(MatchMessage.ResyncRequest)
                        }
                    }
```

- [ ] **Step 4: 本地点击改成发请求（不入队）**

在两个点击处理器里，联机客户端走另一条分支：

```kotlin
                    // 骰子
                    if (dieRenderer.isDie(info.targetedEntity)) {
                        val myTurn = engine.phase == Phase.AWAITING_ROLL &&
                            engine.state.currentTeam == humanTeam && !isTurnBusy && !showExitConfirm
                        if (myTurn) {
                            if (isOnline && !isHost) {
                                // 不本地摇：等房主的 ROLLED 回来才动。awaitingHost 只是防连点的
                                // 网络卫生，不承担正确性——房主侧的 phase 校验才是那道门。
                                if (!awaitingHost) {
                                    awaitingHost = true
                                    lanClient.send(MatchMessage.RollRequest)
                                }
                            } else {
                                commands.trySend(TurnCommand.Roll(humanTeam, value = null))
                            }
                        } else {
                            Log.e(TAG, "die tap ignored: phase=${engine.phase} currentTeam=${engine.state.currentTeam} humanTeam=$humanTeam isTurnBusy=$isTurnBusy")
                        }
                        handled = true
                        return@forEach
                    }
```

飞机分支同理，`commands.trySend(TurnCommand.Apply(humanTeam, move.pieceIndex))` 换成：

```kotlin
                    if (isOnline && !isHost) {
                        lanClient.send(MatchMessage.MoveRequest(move.pieceIndex))
                    } else {
                        commands.trySend(TurnCommand.Apply(humanTeam, move.pieceIndex))
                    }
                    handled = true
```

加状态 `var awaitingHost by remember { mutableStateOf(false) }`，在 `handleHostMessage` 收到 `Rolled` 与 `Moved` 时清掉（`awaitingHost = false`）。

- [ ] **Step 5: 接上「查找房间」与两个面板**

`StartPanel` 的 `onFindRoom` 改成：

```kotlin
                        onFindRoom = {
                            flow = BattleFlowState.Discovering(emptyList())
                            lanClient.startDiscovery(coroutineScope)
                        },
```

加两个面板：

```kotlin
            AttachmentPanel(id = ROOM_LIST_PANEL) {
                val discovering = flow as? BattleFlowState.Discovering
                if (discovering != null && !showExitConfirm) {
                    RoomListPanel(
                        rooms = discovering.rooms,
                        onPick = { picked ->
                            flow = BattleFlowState.Connecting
                            lanClient.stopDiscovery()
                            lanClient.join(coroutineScope, picked, Build.MODEL)
                        },
                        onManualEntry = {
                            lanClient.stopDiscovery()   // 不停会不断推 Discovering 覆盖掉输入界面
                            flow = BattleFlowState.ManualEntry
                        },
                        onCancel = { lanClient.cancel(); flow = BattleFlowState.SinglePlayer },
                    )
                }
            }
            AttachmentPanel(id = MANUAL_ADDRESS_PANEL) {
                if (flow is BattleFlowState.ManualEntry && !showExitConfirm) {
                    ManualAddressPanel(
                        onConnect = { address ->
                            flow = BattleFlowState.Connecting
                            lanClient.joinManually(coroutineScope, address, Build.MODEL)
                        },
                        onCancel = { lanClient.cancel(); flow = BattleFlowState.SinglePlayer },
                    )
                }
            }
```

大厅面板的 `onPickSeat` 客户端分支：

```kotlin
                            } else {
                                // 不预先改自己的显示：等房主的 LOBBY 广播回来才变。
                                lanClient.send(MatchMessage.SeatRequest(target))
                            }
```

`onLeave` 客户端分支：`lanClient.send(MatchMessage.Leave); lanClient.cancel(); flow = BattleFlowState.SinglePlayer`。

- [ ] **Step 6: dispose 清理**

在清理 `lanHost.cancel()` 的同一个 `DisposableEffect` 里加 `lanClient.cancel()`。

- [ ] **Step 7: 编译、单测、用陪练脚本反过来验一局**

```bash
export PICO_HOME='/Users/zohar/Library/PICO/sdk'
export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
./gradlew testDebugUnitTest && ./gradlew assembleDebug
```

Mac 上先起房主：

```bash
python3 tools/lan_peer.py host      # 会打印本机 IP
```

真机上「查找房间」——**先确认 NSD 列表里能不能看到 `MacPeer`**（脚本用 `dns-sd -R` 注册了服务）。看不到就走「手动输入地址」填 Mac 的 IP。两条路都要试。

预期：真机进大厅 → 3 秒后脚本发 `START` → 真机进对局 → 真机点骰子，脚本收到 `ROLL_REQ` 并回 `ROLLED` → 真机的骰子动画播放、飞机走动。

**校验和会对不上**（脚本填的是 0），所以日志里应该看到 `client: CHECKSUM MISMATCH ... requesting resync` 和随后的 `RESYNC_REQ`——这不是 bug，正是分叉检测和重同步路径被真实触发了一次的证据。

- [ ] **Step 8: 提交**

```bash
git add app/src/main/java/tech/illusion/spaceflightchess/content/BoardStage.kt
git commit -m "feat(stage): 接线客户端角色

本地点击只发请求不入队，棋盘状态只由房主广播驱动。每步比对校验和，
对不上就请求全量 SYNC。手动输入地址前停掉 NSD 发现。"
```

---

### Task 13: 掉线、AI 托管与重连

前面的任务已经让 `LanMatchHost` 在连接断开时把座位标成 `DROPPED`。这个任务补上剩下的一半：**轮到掉线玩家时的 15 秒等待窗口，超时转 AI 托管，重连后从下一回合起交还控制权。**

**Files:**
- Modify: `app/src/main/java/tech/illusion/spaceflightchess/content/BoardStage.kt`
- Modify: `app/src/main/java/tech/illusion/spaceflightchess/content/Panels.kt`（HUD 加等待提示）

- [ ] **Step 1: 加等待窗口的状态与驱动**

在 `BoardStage()` 里加：

```kotlin
    /** 当前正在等谁重连、还剩几秒。null 表示没有人在等待窗口里。 */
    var waitingFor by remember { mutableStateOf<Pair<Team, Int>?>(null) }
```

新增一个只在房主上跑的轮询驱动，放在 AI driver 旁边：

```kotlin
    // 掉线玩家的等待窗口。只有房主跑——它是唯一知道每条连接死活的一方。
    //
    // 关键设计：**只在轮到他时才等**。别人的回合他掉着线，对局全速进行，其他三个人完全无感。
    // 这正是「短暂等待后再交给 AI」相对「立即托管」的意义：一次 WiFi 瞬断不白白损失一个回合。
    LaunchedEffect(isHost, isOnline) {
        if (!isOnline || !isHost) return@LaunchedEffect
        while (true) {
            val team = engine.state.currentTeam
            val seat = room.occupant(team)
            val isWaiting = seat is Occupant.Human && seat.presence == Presence.DROPPED
            if (!isWaiting || engine.isOver || engine.phase == Phase.SETUP) {
                if (waitingFor != null) waitingFor = null
                delay(TURN_POLL_MS)
                continue
            }
            for (remaining in RECONNECT_GRACE_S downTo 1) {
                val still = room.occupant(team)
                if (still !is Occupant.Human || still.presence != Presence.DROPPED) break
                if (engine.state.currentTeam != team) break
                waitingFor = team to remaining
                delay(1000)
            }
            waitingFor = null
            // 窗口耗尽且他还没回来 → 转 AI 托管。AI driver 下一轮轮询就会接手，
            // 因为 RoomState.isDrivenByAi 对 AI_TAKEOVER 返回 true。
            val after = room.occupant(team)
            if (after is Occupant.Human && after.presence == Presence.DROPPED && engine.state.currentTeam == team) {
                Log.e(TAG, "host: $team grace expired, AI takes over")
                lanHost.updateRoom(SeatAssigner.setPresence(lanHost.room, team, Presence.AI_TAKEOVER))
                lanHost.broadcast(MatchMessage.SeatUpdate(team, Presence.AI_TAKEOVER))
            }
        }
    }
```

- [ ] **Step 2: 重连后交还控制权**

`LanMatchHost.admit` 已经通过 `SeatAssigner.rejoin` 把重连者的 presence 置回 `ONLINE`，并广播了 `LOBBY`。补上一条显式的 `SEAT` 广播和一次 SYNC，在 `admit` 的末尾（`onRoom(room)` 之前）加：

```kotlin
        // 重连回来的人不能信自己断线前的内存状态（断线那一刻很可能正好错过一条消息）。
        // 标记他等一次全量同步，由 BoardStage 在下一个回合间隙补发。
        broadcast(MatchMessage.SeatUpdate(team, Presence.ONLINE))
        onNeedsResync(team)
```

给 `LanMatchHost` 的构造函数加一个回调参数 `private val onNeedsResync: (Team) -> Unit`，`BoardStage` 传 `{ seat -> pendingResyncFor += seat }`。

**不打断进行中的那一回合**：如果 AI 已经开始摇了就让 AI 打完——`isDrivenByAi` 在下一次 AI driver 轮询时才重新求值，而那已经是下一回合了。这是自然的结果，不需要额外代码。

- [ ] **Step 3: HUD 显示等待提示**

`GameHud` 加一个参数 `waitingFor: Pair<Team, Int>? = null`，非 null 时在现有内容上方多显示一行：

```kotlin
        waitingFor?.let { (team, seconds) ->
            Text(
                text = "等待${labelFor(team)}方重连 ${seconds}s",
                color = PicoTheme.colorScheme.labelSecondary,
                style = PicoTheme.typography.bodyMedium,
            )
        }
```

`BoardStage` 的 `AttachmentPanel(id = HUD_PANEL)` 里把 `waitingFor = waitingFor` 传下去。

- [ ] **Step 4: 房主掉线时客户端结束对局**

客户端的重试是无限的，但如果房主的进程整个没了，重试会一直失败。在 `LanMatchClient.session` 的重试循环里加一个计数：连续失败超过一定次数（`HOST_GONE_RETRIES = 20`，约一分钟）且曾经连上过，就报 `EndReason.HOST_LEFT` 并停止：

```kotlin
    private var consecutiveFailures = 0
```

在 connect 失败的 catch 块里，`isReconnect` 分支改成：

```kotlin
                consecutiveFailures++
                if (consecutiveFailures >= HOST_GONE_RETRIES) {
                    Log.e(TAG, "client: host unreachable after $consecutiveFailures tries, giving up")
                    withContext(Dispatchers.Main) { onFailure(EndReason.HOST_LEFT) }
                    return
                }
                delay(RECONNECT_RETRY_MS)
                continue
```

连接成功时 `consecutiveFailures = 0`。文件顶部加 `private const val HOST_GONE_RETRIES = 20`。

- [ ] **Step 5: 编译、单测、用陪练脚本验掉线路径**

```bash
export PICO_HOME='/Users/zohar/Library/PICO/sdk'
export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
./gradlew testDebugUnitTest && ./gradlew assembleDebug
```

真机建房，Mac 上 `python3 tools/lan_peer.py join <IP>`，开局跑几个回合后**按 Ctrl-C 杀掉脚本**。

预期时间线（对着真机日志逐条核对）：

1. 立刻：`host: <team> dropped`，广播 `SEAT <team> off`。对局继续，其他座位照常出手。
2. 轮到那一席时：HUD 出现「等待XX方重连 15s」并逐秒递减。
3. 15 秒后：`host: <team> grace expired, AI takes over`，广播 `SEAT <team> ai`，AI 接手摇骰走子。
4. 重新跑一次 `python3 tools/lan_peer.py join <IP>`：`host: accepted` → `host: MacPeer seated at <同一个 team>`（`lastSeatByName` 生效，回到原座）→ `SEAT <team> on` → 下一个回合间隙收到 `SYNC`。
5. 再下一个属于他的回合，控制权已经回到脚本手上（脚本会自己发 `ROLL_REQ`）。

另外单独验一次「房主掉线」：Mac 上 `python3 tools/lan_peer.py host`，真机加入并开局后 Ctrl-C 杀掉脚本，真机应在约一分钟后显示「房主已离开」并回到开始页。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/tech/illusion/spaceflightchess/content/BoardStage.kt \
        app/src/main/java/tech/illusion/spaceflightchess/content/LanMatchHost.kt \
        app/src/main/java/tech/illusion/spaceflightchess/content/LanMatchClient.kt \
        app/src/main/java/tech/illusion/spaceflightchess/content/Panels.kt
git commit -m "feat(net): 掉线等待窗口、AI 托管与重连交还

只在轮到掉线玩家时才等 15s，其他人的回合全速进行。超时转 AI 托管，
重连回原座并在回合间隙补 SYNC，从下一回合起交还控制权。"
```

---

### Task 14: 结算回大厅 与 大厅内掉线释放座位

设计文档里的两条，前面的任务都还没覆盖：

1. **联机结算后回大厅而不是直接重开**——座位表保留，可以换座、可以等新人加入，房主再点开始。复用同一套大厅 UI，比单独做一条「联机版再来一局」路径少一整块代码。
2. **大厅里有人掉线，座位要真正空出来让别人能坐**——`LanMatchHost.markDropped` 现在无条件把座位标成 `DROPPED`，那是**对局中**的正确行为（保住他的位子等他回来）；但在大厅里没有对局要保，那个位子应该直接释放。

**Files:**
- Modify: `app/src/main/java/tech/illusion/spaceflightchess/content/LanMatchHost.kt`
- Modify: `app/src/main/java/tech/illusion/spaceflightchess/content/BoardStage.kt`
- Modify: `app/src/main/java/tech/illusion/spaceflightchess/content/Panels.kt`（`ResultPanel`）

**Interfaces:**
- Consumes: `LanMatchHost`（Task 8/13）、`BattleFlowState`（Task 3）、`SeatAssigner`（Task 4）
- Produces: `LanMatchHost.inMatch: Boolean`（可写，由 `BoardStage` 在开局/结算时切换）；`ResultPanel` 新增参数 `isOnline: Boolean`、`isHost: Boolean`、`onBackToLobby: () -> Unit`

- [ ] **Step 1: 让 LanMatchHost 知道现在是不是在对局中**

在 `LanMatchHost` 里加：

```kotlin
    /**
     * 现在是否在对局中。决定一条连接断开时座位的去向：
     *  - 对局中 → 标成 [Presence.DROPPED]，保住他的位子等他重连（Task 13 的等待窗口在这上面跑）
     *  - 在大厅 → 直接释放成空位，别人可以坐进去。大厅里没有对局要保，占着位子只会挡住新人。
     *
     * 由 `BoardStage` 在 [lockSeatsForStart] 之后置 true、结算回大厅时置 false。
     */
    var inMatch: Boolean = false
```

把 `markDropped` 改成：

```kotlin
    private fun markDropped(team: Team) {
        connections.remove(team)
        writers.remove(team)
        val seat = room.occupant(team) as? Occupant.Human ?: return
        if (!inMatch) {
            Log.e(TAG, "host: $team left the lobby, seat freed")
            room = SeatAssigner.leave(room, team)
        } else {
            // 已经在 AI 托管里的座位不要退回 DROPPED——那会让它重新获得一次等待窗口。
            if (seat.presence == Presence.AI_TAKEOVER) return
            room = SeatAssigner.setPresence(room, team, Presence.DROPPED)
            Log.e(TAG, "host: $team dropped mid-match")
            broadcast(MatchMessage.SeatUpdate(team, Presence.DROPPED))
        }
        broadcast(MatchMessage.Lobby(room))
        onRoom(room)
    }
```

在 `lockSeatsForStart()` 里加一行 `inMatch = true`，在 `cancel()` 里加一行 `inMatch = false`。

- [ ] **Step 2: 结算面板加「回大厅」**

`ResultPanel` 签名改成：

```kotlin
fun ResultPanel(
    ranking: List<Team>,
    humanTeam: Team,
    onPlayAgain: () -> Unit,
    isOnline: Boolean = false,
    isHost: Boolean = false,
    onBackToLobby: () -> Unit = {},
)
```

按钮区按三种情况分支（默认参数保证单机调用一个字不用改）：

```kotlin
            when {
                !isOnline -> Button(onClick = onPlayAgain) {
                    Text("再来一局", style = PicoTheme.typography.labelLarge)
                }
                isHost -> Button(onClick = onBackToLobby) {
                    Text("回到房间", style = PicoTheme.typography.labelLarge)
                }
                else -> Text(
                    text = "等待房主开始新一局",
                    color = PicoTheme.colorScheme.labelSecondary,
                    style = PicoTheme.typography.bodyMedium,
                )
            }
```

只有房主能发起，是权威模型的直接推论——避免「两个人同时点该以谁为准」这种本来不需要存在的协调问题。

- [ ] **Step 3: 房主回大厅**

`BoardStage` 的 `AttachmentPanel(id = RESULT_PANEL)` 改成：

```kotlin
            AttachmentPanel(id = RESULT_PANEL) {
                if (phase == Phase.GAME_OVER && !showExitConfirm) {
                    ResultPanel(
                        ranking = ranking,
                        humanTeam = humanTeam,
                        onPlayAgain = { engine.restart(); publish() },
                        isOnline = isOnline,
                        isHost = isHost,
                        onBackToLobby = {
                            engine.restart()
                            publish()
                            lanHost.inMatch = false
                            // 座位表原样保留：AI 位仍是 AI，掉线的人仍占着他的位子；
                            // 大厅里可以换座、可以等新人，房主再点一次开始。
                            flow = BattleFlowState.InLobby(
                                room = lanHost.room,
                                myTeam = humanTeam,
                                isHost = true,
                                localAddress = lanHost.localAddress(),
                            )
                            lanHost.broadcast(MatchMessage.Lobby(lanHost.room))
                        },
                    )
                }
            }
```

- [ ] **Step 4: 客户端跟着回大厅**

房主回大厅时会广播一条 `LOBBY`。客户端在 `handleHostMessage` 的 `Lobby` 分支里补一条：对局已结束时收到 `LOBBY`，说明房主开了新一轮，跟着回大厅。在 `is MatchMessage.Lobby ->` 分支开头加：

```kotlin
                if (engine.isOver) {
                    engine.restart()
                    publish()
                    flow = BattleFlowState.InLobby(message.room, humanTeam, isHost = false, localAddress = null)
                    room = message.room
                    return
                }
```

之后房主点开始会广播 `START`，客户端已有的 `Start` 分支会走 `startWithFaction` 进新一局。`engine.restart()` 把 phase 退回 `SETUP`，所以 `startWithFaction` 的 `if (engine.phase != Phase.SETUP) return` 守卫不会挡住它。

- [ ] **Step 5: 编译、单测、陪练验证**

```bash
export PICO_HOME='/Users/zohar/Library/PICO/sdk'
export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
./gradlew testDebugUnitTest && ./gradlew assembleDebug
```

两件事分开验：

**A. 大厅掉线释放座位**：真机建房，Mac `python3 tools/lan_peer.py join <IP>`，**在真机点开始之前**Ctrl-C 杀掉脚本。真机大厅里那一行应该变回「空 · 开局补 AI」，日志有 `host: <team> left the lobby, seat freed`。再跑一次脚本，应该能重新坐进来。

**B. 结算回大厅**：打一局到分出胜负（想快就用四个 AI——房主一个人开局，其余三席自动补 AI），点「回到房间」，确认回到大厅且座位表保留，再点开始能正常进入新一局。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/tech/illusion/spaceflightchess/content/LanMatchHost.kt \
        app/src/main/java/tech/illusion/spaceflightchess/content/BoardStage.kt \
        app/src/main/java/tech/illusion/spaceflightchess/content/Panels.kt
git commit -m "feat(net): 结算回大厅 + 大厅内掉线直接释放座位

联机结算只有房主能发起新一局，回大厅而不是原地重开，座位表保留。
掉线的座位去向按 inMatch 分流：对局中保住等重连，大厅里直接空出来让新人坐。"
```

---

### Task 15: 端到端验证、单机回归与文档

**Files:**
- Modify: `AGENTS.md`
- Modify: `.spatialsdk/ui-verification-log.md`
- Create: `.spatialsdk/ui-verify/lan-*.png`

- [ ] **Step 1: 单机回归——确认联机没有破坏原有玩法**

模拟器上完整走一局单机（选阵营 → 开始游戏 → 打到分出胜负或至少 20 个回合），确认：摇 6 有额外回合、僚机一起走、被吃回机库、归家小道精确点数、结算面板排名正确。这一层是 Task 6 那次回合驱动重构的最终兜底。

```bash
export PICO_HOME='/Users/zohar/Library/PICO/sdk'
export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
bash ../.claude/skills/spatial-design-first-build/scripts/device-lock.sh acquire "lan-task15" 120 emulator-5554
pico-cli app install app/build/outputs/apk/debug/app-debug.apk --device emulator-5554
adb -s emulator-5554 logcat -c
pico-cli app launch tech.illusion.spaceflightchess --device emulator-5554
# 玩完之后：
adb -s emulator-5554 logcat -d | grep -a SpaceFlightChess | tail -300
pico-cli app stop tech.illusion.spaceflightchess --device emulator-5554
bash ../.claude/skills/spatial-design-first-build/scripts/device-lock.sh release "lan-task15" emulator-5554
```

- [ ] **Step 2: 验证 NSD 的注册侧（真机 → Mac）**

真机建房，Mac 上：

```bash
dns-sd -B _spaceflightchess._tcp
```

预期：几秒内列出真机的房间名（`<设备型号>-<四位随机数>`）。看不到就检查真机与 Mac 是否在同一路由器、以及路由器有没有开 AP 隔离。

- [ ] **Step 3: 验证 NSD 的发现侧（Mac → 真机）**

```bash
dns-sd -R "MacRoom" _spaceflightchess._tcp . 47780
```

真机点「查找房间」，预期列表里出现 `MacRoom`。

**这两步是自动发现路径唯一的验证机会**——两个模拟器之间组播不通，一台模拟器 + 一台真机也不通。

- [ ] **Step 4: 尝试第 4 层验证手段（两个模拟器 + adb 端口串接）**

这是设计文档里标注的**未验证假设**。如果通了，以后的日常回归就不用占用真机；不通就记录下来，永久依赖第 3 层。

```bash
# 假设两个模拟器分别是 emulator-5554（房主）和 emulator-5556（客户端）
adb -s emulator-5554 forward tcp:47780 tcp:47780     # 宿主机 47780 → 房主模拟器
adb -s emulator-5556 reverse tcp:47780 tcp:47780     # 客户端模拟器 47780 → 宿主机
```

然后客户端模拟器上「手动输入地址」填 `127.0.0.1`。把结果（通 / 不通 + 具体报错）写进 `AGENTS.md`。

- [ ] **Step 5: 截图存档（门禁 B）**

在模拟器上把三个新面板各截一张，存进 `.spatialsdk/ui-verify/`，并在 `.spatialsdk/ui-verification-log.md` 追加本轮记录（日期、面板、结论）。用 `adb -s <设备> exec-out screencap -p > <文件>`，启动后等够 8 秒再截（截图可能是陈旧帧，实测差过 ≥6 秒）。

- [ ] **Step 6: 更新 AGENTS.md**

在 `AGENTS.md` 末尾追加一节 `## 2026-09-02 — 局域网联机对战（含 AI 替补）`，写清楚：

- 房主权威 + 确定性重放 + 每步校验和这个模型，以及「`DiceEngine` 是唯一随机源」这条它赖以成立的事实
- 命令队列重构：四个来源、一个执行器、一个门禁，以及 `isTurnBusy` 职责的变化（只挡输入，不再管路径互斥）
- 三种角色下 `BoardStage` 的分支位置
- 掉线只在轮到他时才等 15 秒，其他人不受影响
- **一处相对设计文档的有意偏离**：房间列表**不显示 `n/4` 人数**。连上之前拿不到人数，要带就得写进 NSD 的 TXT 记录，而 `NsdManager` 改属性必须先 unregister 再 register——每次有人进出都断一次广播，代价远大于一个数字的价值
- **验证手段与已知空白**：单测 + 镜像重放覆盖确定性；`tools/lan_peer.py` 覆盖端到端协议；`dns-sd` 覆盖 NSD 两侧；第 4 层端口串接的实测结论；**真机↔真机之间的真实无线时序仍未验证，需要第二台头显**
- 陪练脚本的用法（三个子命令各一行）

- [ ] **Step 7: 提交**

```bash
git add AGENTS.md .spatialsdk/
git commit -m "docs: 联机功能的验证记录与已知空白

记录房主权威+确定性重放模型、命令队列重构、陪练脚本用法，
以及真机↔真机无线时序这一块确定留白的验证空白。"
```

---

## 交付说明（实现完成后要如实告诉用户的）

- **已验证**：协议编解码、座位裁决、确定性重放（300 局镜像自对弈零分叉）、单机回归、三个新面板的布局、NSD 注册与发现两侧、完整协议往返与掉线托管重连（经 Mac 陪练脚本）。
- **未验证**：两台 PICO 真机之间的真实无线时序——包括真实 WiFi 抖动下的重连表现、四人同场时的广播延迟累积、以及两台头显各自播动画的实际观感差。这需要第二台头显，本次条件不具备。
- **一处偏离设计文档**：房间列表不显示人数（理由见上，已在 `DiscoveredRoom` 的类注释和 `AGENTS.md` 里记录）。
- **可调的判断值**：`RECONNECT_GRACE_S = 15`（等待窗口）、`RECONNECT_RETRY_MS = 3000`（重试间隔）、`HOST_GONE_RETRIES = 20`（判定房主离开的阈值）。三个都在戴上头显实际用过之后才好定。
