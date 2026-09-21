package tech.illusion.spaceflightchess.content

import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.tracking.hmd.HMDTrackingProvider
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.gesture.TargetEntity
import com.pico.spatial.ui.foundation.gesture.detectSpatialPointerEvent
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import tech.illusion.spaceflightchess.game.BoardGeometry
import tech.illusion.spaceflightchess.game.GameEngine
import tech.illusion.spaceflightchess.game.GameEvent
import tech.illusion.spaceflightchess.game.GameState
import tech.illusion.spaceflightchess.game.Move
import tech.illusion.spaceflightchess.game.movementWaypoints
import tech.illusion.spaceflightchess.game.MoveOutcome
import tech.illusion.spaceflightchess.game.Phase
import tech.illusion.spaceflightchess.game.PieceState
import tech.illusion.spaceflightchess.game.PlaneAi
import tech.illusion.spaceflightchess.game.Team
import tech.illusion.spaceflightchess.game.Track

private const val TAG = "SpaceFlightChess"

private const val START_PANEL = "start"
private const val HUD_PANEL = "hud"
private const val RESULT_PANEL = "result"
private const val EXIT_CONFIRM_PANEL = "exitConfirm"

/**
 * Unwraps a Compose [Context] down to the hosting [ComponentActivity] — `LocalContext.current`
 * inside [SpatialView]'s content isn't guaranteed to already be the bare Activity (it can arrive
 * wrapped, same as anywhere else in Android), so this walks the [ContextWrapper] chain rather than
 * assuming a direct cast. Needed to reach `onBackPressedDispatcher` for the exit-confirm handling
 * in [BoardStage].
 */
private tailrec fun Context.findComponentActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findComponentActivity()
    else -> null
}

/**
 * Everything the game needs: the board rig, the two renderers ([BoardRenderer] for the static
 * board, [PlanePieceRenderer] for the 16 pieces), the turn loop, and the three panels.
 *
 * **The human picks a faction on the start panel** — [GameEngine] itself is turn-agnostic (see its
 * class doc); [humanTeam] is the one piece of state this composable adds on top to know who to
 * treat as "you" versus AI, and [BoardGeometry.configureSeat] is told the same choice so the whole
 * board (art + every piece's position) rotates to put that faction's hangar at the seat position.
 *
 * **The rig sits below and in front of the player, not centred under their own feet** —
 * `RIG_HEIGHT_M` used to be a fixed guess at eye height with the board floating chest-high right in
 * front of the face (near edge only ~7.5cm away once you account for the board's own width), which
 * read as standing with your nose against a wall rather than looking down at a table. That was
 * fixed by tracking the HMD's own height (via [HMDTrackingProvider]) minus
 * [BOARD_HEIGHT_BELOW_HEAD_M] — but centring the board's *horizontal* position on the player too
 * traded one bad placement for another: standing centred over a 0.95m-wide board puts the player
 * literally in the middle of it, unable to see it as a whole without stepping back (which the
 * tracked rig would then just follow, chasing them). The rig's XZ position is now the HMD's own XZ
 * *plus* [BOARD_FORWARD_OFFSET_M] in whatever horizontal direction the player was facing at scene
 * start (from the HMD pose's own rotation, not just its position), so the board sits in front of
 * them like a real tabletop they're standing at the edge of, not one they're standing on top of. A
 * static fallback pose (facing world -Z, an arbitrary but fixed choice — there is no "facing
 * direction" to read before the first HMD sample arrives) covers the brief window before that first
 * sample, and the emulator, where HMD tracking may never produce data at all.
 */
@Composable
fun BoardStage() {
    val engine = remember { GameEngine() }
    val boardRenderer = remember { BoardRenderer() }
    val pieceRenderer = remember { PlanePieceRenderer() }
    val dieRenderer = remember { DieRenderer() }
    val musicPlayer = remember { AmbientMusicPlayer() }
    val eventSoundPlayer = remember { EventSoundPlayer() }
    val rig = remember { Entity() }
    val hmdTrackingProvider = remember { HMDTrackingProvider() }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val navigator = LocalSpatialNavigator.current

    var phase by remember { mutableStateOf(engine.phase) }
    var currentTeam by remember { mutableStateOf(engine.state.currentTeam) }
    var humanTeam by remember { mutableStateOf(Team.RED) }
    var lastRoll by remember { mutableStateOf<Int?>(null) }
    var aiThinking by remember { mutableStateOf(false) }
    /**
     * One turn at a time, end to end: 「等到本轮玩家行棋结束后再到下一个玩家」.
     *
     * Held from the moment a roll or a move is committed until that player's plane has finished walking
     * and the new state has been published — *not* just while the die tumbles. `GameEngine.applyMove`
     * advances `currentTeam` the instant it is called, so without this covering the animation the next
     * player was free to act while the previous plane was still in the air. That produced exactly the
     * reported mess: B rolled during A's walk, B's plane reached the shared cell first, A landed
     * afterwards, and A was then captured out from under its own still-running animation.
     *
     * Both paths (`rollAndAdvance` for the die, the piece tap for a human move) must take it, and both
     * must release it in a `finally` — a stuck gate silently bricks the game.
     */
    var isTurnBusy by remember { mutableStateOf(false) }
    var isSeating by remember { mutableStateOf(false) }

    /** Which team the die is currently parked in front of; drives [syncDieSlot]. */
    var dieSlotTeam by remember { mutableStateOf<Team?>(null) }
    var ranking by remember { mutableStateOf<List<Team>>(emptyList()) }

    /**
     * Whether the exit-confirm panel is up — see the `OnBackPressedCallback` below for why it
     * exists. Every other panel's own visibility condition is AND-ed with `!showExitConfirm` so
     * nothing else occupies the same anchor position at once, and the AI-turn driver and the human
     * tap handler both gate on it too so nothing moves on the board while the player is deciding.
     */
    var showExitConfirm by remember { mutableStateOf(false) }
    val activity = remember(context) { context.findComponentActivity() }

    /** Bumped on every state change so the render loop knows to re-sync piece positions. */
    var revision by remember { mutableIntStateOf(0) }

    fun publish() {
        phase = engine.phase
        currentTeam = engine.state.currentTeam
        engine.drainEvents().forEach { event ->
            when (event) {
                is GameEvent.Rolled -> lastRoll = event.value
                is GameEvent.Moved ->
                    if (event.outcome.capturedPieces.isNotEmpty() && eventSoundPlayer.isAttached) {
                        eventSoundPlayer.playCapture()
                    }
                is GameEvent.Won -> {
                    ranking = event.ranking
                    if (eventSoundPlayer.isAttached) {
                        if (event.ranking.first() == humanTeam) eventSoundPlayer.playVictory() else eventSoundPlayer.playDefeat()
                    }
                }
                is GameEvent.TurnPassed -> lastRoll = null
                is GameEvent.TripleSix ->
                    Log.e(TAG, "triple six: ${event.team} must send a plane home")
                else -> Unit
            }
        }
        revision++
        if (pieceRenderer.isAttached) pieceRenderer.render(engine.state)
    }

    // ── debug breadcrumbs ───────────────────────────────────────────────────
    // Added after a real-device report ("rolled 6, launched a plane, it ended up several cells
    // away") that turned out to be a UX/animation issue, not a logic bug — but the only way to
    // tell the two apart from a bug report alone is a full trail of what the engine actually did.
    // Filter with `adb logcat -s SpaceFlightChess:E` (this file already logs everything at `.e` —
    // see `scene ready` below — so a single filter catches init, rolls, and moves together).
    fun logRoll(team: Team, value: Int?) {
        Log.e(TAG, "roll: team=$team value=$value phaseAfter=${engine.phase} currentTeamAfter=${engine.state.currentTeam}")
    }

    fun logMove(source: String, move: Move, outcome: MoveOutcome?) {
        val absCell = (move.to as? PieceState.OnPath)?.value
            ?.takeIf { Track.isOnSharedLoop(it) }
            ?.let { Track.ringIndex(move.team, it) }
        Log.e(
            TAG,
            "move[$source]: team=${move.team} piece=${move.pieceIndex} from=${move.from} to=${move.to} " +
                "absCell=$absCell outcome=$outcome phaseAfter=${engine.phase} currentTeamAfter=${engine.state.currentTeam}",
        )
    }

    /** The die's resting spot for [team], lifted to sit on the board surface rather than inside it. */
    fun dieSlotFor(team: Team): Vector3 {
        val slot = BoardGeometry.dieSlotPosition(team)
        return Vector3(slot.x, DIE_REST_HEIGHT_M, slot.z)
    }

    /**
     * Keeps the die in front of whoever is up next: 「每回合骰子默认放到本轮玩家或 Ai 的面前棋盘上」.
     *
     * Called after every state change rather than driven off an event, so it self-corrects no matter how
     * the turn moved (ordinary pass, extra roll on a 6, triple-six penalty, or a restart).
     */
    suspend fun syncDieSlot() {
        if (!dieRenderer.isAttached) return
        val team = engine.state.currentTeam
        if (team == dieSlotTeam) return
        dieSlotTeam = team
        dieRenderer.glideToRest(dieSlotFor(team))
    }

    /**
     * Rolls for [GameEngine.state]'s current team, plays the physical die tumbling to that value
     * (see [DieRenderer.tumbleTo]) before anything else reacts to it, and — if it is an AI team —
     * picks and applies its move once the die has settled. `isTurnBusy` blocks a second trigger (a
     * stray extra tap, or the AI's own `LaunchedEffect`) from overlapping this one's tumble.
     */
    suspend fun rollAndAdvance() {
        if (isTurnBusy) return
        isTurnBusy = true
        // try/finally, not straight-line: tumbleTo plus a walk of up to a second per cell suspend for
        // several seconds, and
        // anything that cancels this coroutine in that window used to leave isTurnBusy stuck true —
        // which silently bricked the game, because every later roll and every die tap short-circuit
        // on that flag and nothing else ever resets it.
        try {
            val team = engine.state.currentTeam
            val value = engine.roll()
            logRoll(team, value)
            if (value != null) {
                if (dieRenderer.isAttached) dieRenderer.throwTo(value)
                if (engine.phase == Phase.AWAITING_MOVE && team != humanTeam) {
                    val move = PlaneAi.chooseMove(engine.state, engine.legalMovesForPendingRoll())
                    val outcome = engine.applyMove(move)
                    logMove("ai", move, outcome)
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
            }
        } finally {
            publish()
            isTurnBusy = false
        }
        syncDieSlot()
    }

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

    /**
     * Applies the player's faction choice: animates the board (art + every hangar'd piece) rotating
     * to seat them, then actually starts. All 16 pieces are still `InHangar` at this point — the
     * start panel only shows during [Phase.SETUP], before [GameEngine.startGame] — so "rotate the
     * board" and "move every piece to its new hangar slot" are the same instant in game-state terms;
     * this just spreads that instant across [SEAT_ANIMATION_STEPS] interpolated frames instead of
     * snapping straight there, which is what made the previous board/piece rotation read as pieces
     * teleporting into a same-looking-but-wrong-team hangar rather than the board turning to face you.
     */
    fun startWithFaction(team: Team) {
        // Guard against a second tap on 开始游戏 while the first seat animation is still running:
        // two overlapping animations would interpolate the same pivots from different start points
        // and fight frame by frame.
        if (engine.phase != Phase.SETUP || isSeating) return
        isSeating = true
        humanTeam = team
        coroutineScope.launch {
          try {
            val fromYaw = BoardGeometry.seatRotationDegrees
            val fromHangars = Team.entries.associateWith { t ->
                (0 until GameState.PIECES_PER_TEAM).map { BoardGeometry.hangarPosition(t, it) }
            }

            BoardGeometry.configureSeat(team)

            val toYaw = BoardGeometry.seatRotationDegrees
            val toHangars = Team.entries.associateWith { t ->
                (0 until GameState.PIECES_PER_TEAM).map { BoardGeometry.hangarPosition(t, it) }
            }

            for (step in 1..SEAT_ANIMATION_STEPS) {
                val ease = smoothStep(step.toFloat() / SEAT_ANIMATION_STEPS)
                boardRenderer.updateSeatRotation(lerpAngleDegrees(fromYaw, toYaw, ease))
                for (t in Team.entries) {
                    for (index in 0 until GameState.PIECES_PER_TEAM) {
                        val from = fromHangars.getValue(t)[index]
                        val to = toHangars.getValue(t)[index]
                        pieceRenderer.setPivotPosition(
                            t,
                            index,
                            Vector3(lerp(from.x, to.x, ease), PlanePieceRenderer.PIECE_LIFT_M, lerp(from.z, to.z, ease)),
                        )
                    }
                }
                delay(SEAT_ANIMATION_FRAME_MS)
            }

            engine.startGame()
            publish()
            // The seat rotation just moved every slot, so re-place the die for whoever starts.
            dieSlotTeam = engine.state.currentTeam
            dieRenderer.setRestPosition(dieSlotFor(engine.state.currentTeam))
          } finally {
            isSeating = false
          }
        }
    }

    // ── the AI's turn ────────────────────────────────────────────────────────
    // One long-lived polling driver, deliberately *not* a state-keyed effect.
    //
    // This has now been wrong twice in two different ways, both because the driver was keyed:
    // keyed on `(phase, currentTeam)` it never re-fired after a 6 (a 6 grants the same team another
    // roll, so both keys come back bit-for-bit identical and Compose does not restart the effect);
    // re-keyed on a `revision` counter it fired reliably but became cancellable *mid-roll*, because
    // every publish() bumps that key, and a cancellation landing inside the ~1.1s of die-tumble plus
    // slide left `isTurnBusy` stuck true and bricked the game outright.
    //
    // Keying on Unit removes the whole class of problem: the coroutine is created once, is only
    // cancelled on dispose, and re-checks the turn state on its own clock instead of relying on a
    // key changing. 20Hz polling is free at this scale and cannot miss a transition.
    LaunchedEffect(Unit) {
        while (true) {
            val aiTurn = engine.phase == Phase.AWAITING_ROLL &&
                engine.state.currentTeam != humanTeam &&
                !isTurnBusy &&
                !showExitConfirm // freeze the AI while the exit-confirm panel is up
            if (!aiTurn) {
                delay(TURN_POLL_MS)
                continue
            }
            try {
                aiThinking = true
                delay(AI_THINK_DELAY_MS) // a flat pause reads better than an instant move - see design doc ch.3
                rollAndAdvance()
            } finally {
                aiThinking = false
            }
        }
    }

    // `DefaultStage` (see `mainApp`) is full immersion with no window chrome at all — no caption
    // bar, no close button. Confirmed on real hardware: several different physical controller
    // inputs (at least a single press of one button, and a double-press of another) all get
    // translated by PICO OS into a plain KEYCODE_BACK `KeyEvent` delivered to the foreground app
    // exactly like a phone's Back button. Left unhandled, `ComponentActivity`'s default back
    // handling calls `finish()` immediately, silently killing the whole session mid-game — almost
    // certainly what got reported as a crash during store review of a sibling app. This registers
    // our own callback on the stock AndroidX dispatcher so that input asks for confirmation instead.
    DisposableEffect(activity) {
        if (activity == null) return@DisposableEffect onDispose {}
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                showExitConfirm = true
            }
        }
        activity.onBackPressedDispatcher.addCallback(callback)
        onDispose { callback.remove() }
    }

    DisposableEffect(hmdTrackingProvider) {
        hmdTrackingProvider.start()
        onDispose { hmdTrackingProvider.stop() }
    }

    DisposableEffect(boardRenderer, pieceRenderer, dieRenderer, musicPlayer, eventSoundPlayer) {
        onDispose {
            boardRenderer.detach()
            pieceRenderer.detach()
            dieRenderer.detach()
            musicPlayer.detach()
            eventSoundPlayer.detach()
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, musicPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> musicPlayer.pause()
                Lifecycle.Event.ON_RESUME -> musicPlayer.resume()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    SpatialView(
        modifier = Modifier.pointerInput(engine) {
            detectSpatialPointerEvent(context = context, targetedToEntity = TargetEntity.hit(rig)) { events ->
                var handled = false
                events.forEach { info ->
                    if (!info.isDownEvent()) return@forEach
                    // Every guard below reads `engine.*` rather than the Compose snapshots (`phase`,
                    // `currentTeam`): those only refresh on the next recomposition, so during the
                    // seconds a walk takes they still describe the previous turn — which silently
                    // swallowed the die tap at exactly the "you rolled a 6, roll again" moment.
                    if (dieRenderer.isDie(info.targetedEntity)) {
                        if (engine.phase == Phase.AWAITING_ROLL && engine.state.currentTeam == humanTeam && !isTurnBusy && !showExitConfirm) {
                            coroutineScope.launch { rollAndAdvance() }
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
                    val picked = pieceRenderer.pieceFor(info.targetedEntity) ?: return@forEach
                    val (team, pieceIndex) = picked
                    // `currentTeam == humanTeam` is the load-bearing one: without it, tapping your
                    // own plane during an AI team's die tumble applied *that team's* pending move
                    // (phase is already AWAITING_MOVE by then, and the lookup below used to match on
                    // piece index alone), so the AI's plane moved and yours did not.
                    if (engine.phase != Phase.AWAITING_MOVE ||
                        engine.state.currentTeam != humanTeam ||
                        team != humanTeam ||
                        isTurnBusy ||
                        showExitConfirm
                    ) {
                        Log.e(
                            TAG,
                            "piece tap ignored: tappedTeam=$team pieceIndex=$pieceIndex phase=${engine.phase} " +
                                "currentTeam=${engine.state.currentTeam} humanTeam=$humanTeam isTurnBusy=$isTurnBusy",
                        )
                        return@forEach
                    }
                    val move = engine.legalMovesForPendingRoll().firstOrNull {
                        it.team == humanTeam && pieceIndex in it.groupPieceIndices
                    }
                    if (move == null) {
                        Log.e(
                            TAG,
                            "piece tap: no legal move for team=$team pieceIndex=$pieceIndex " +
                                "legalMoves=${engine.legalMovesForPendingRoll()}",
                        )
                        return@forEach
                    }
                    // The gate is taken *synchronously*, before applyMove and before the launch: two
                    // taps in the same frame would both pass the guard above if it were set inside the
                    // coroutine. See [isTurnBusy] for what it protects.
                    isTurnBusy = true
                    val outcome = engine.applyMove(move)
                    logMove("human", move, outcome)
                    coroutineScope.launch {
                        // try/finally, not straight-line: the walk now suspends for a second per cell,
                        // and anything cancelling this coroutine in that window would leave the gate
                        // stuck true, which bricks the game — every roll and tap short-circuits on it.
                        try {
                            if (pieceRenderer.isAttached) {
                                pieceRenderer.animateMove(
                                    move.team,
                                    move.groupPieceIndices,
                                    engine.state,
                                    move.from,
                                    movementWaypoints(move, engine.state),
                                )
                            }
                        } finally {
                            publish()
                            isTurnBusy = false
                        }
                        syncDieSlot()
                    }
                    handled = true
                }
                handled
            }
        },
        attachments = {
            AttachmentPanel(id = START_PANEL) {
                if (phase == Phase.SETUP && !showExitConfirm) {
                    StartPanel(onStart = ::startWithFaction)
                }
            }
            AttachmentPanel(id = HUD_PANEL) {
                if (phase != Phase.SETUP && phase != Phase.GAME_OVER && !showExitConfirm) {
                    GameHud(
                        currentTeam = currentTeam,
                        isHumanTurn = currentTeam == humanTeam,
                        lastRoll = lastRoll,
                        isRollEnabled = phase == Phase.AWAITING_ROLL && currentTeam == humanTeam && !isTurnBusy,
                        aiThinking = aiThinking,
                    )
                }
            }
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
        },
        initial = { content, attachments ->
            try {
                content.addEntity(rig)
                // Remembered so the die's rest spot can be placed on the same axis the board was —
                // otherwise the two would disagree whenever the HMD sample arrives (or doesn't).
                var resolvedForward = Vector3.FORWARD
                rig.components[TransformComponent::class.java]?.setPosition(
                    Vector3(
                        resolvedForward.x * BOARD_FORWARD_OFFSET_M,
                        FALLBACK_HEAD_HEIGHT_M - BOARD_HEIGHT_BELOW_HEAD_M,
                        resolvedForward.z * BOARD_FORWARD_OFFSET_M,
                    ),
                )

                // Correct the fallback pose to the *real* head position/height as soon as a sample
                // arrives. Bounded wait: HMD tracking needs Full Space (should already be true here)
                // and real hardware — the emulator may never produce a sample, and this must not
                // block the rest of scene setup (models, board, first publish()) if it doesn't.
                withTimeoutOrNull(HMD_POSE_WAIT_MS) {
                    val pose = hmdTrackingProvider.dataFlow.first().hmdPose
                    val forward = horizontalForward(pose.rotation)
                    resolvedForward = forward
                    rig.components[TransformComponent::class.java]?.setPosition(
                        Vector3(
                            pose.position.x + forward.x * BOARD_FORWARD_OFFSET_M,
                            pose.position.y - BOARD_HEIGHT_BELOW_HEAD_M,
                            pose.position.z + forward.z * BOARD_FORWARD_OFFSET_M,
                        ),
                    )
                } ?: Log.e(TAG, "no HMD pose within ${HMD_POSE_WAIT_MS}ms, keeping the fallback board pose")

                boardRenderer.attachTo(rig)
                pieceRenderer.attachTo(rig)
                dieRenderer.attachTo(rig)
                musicPlayer.attachTo(rig)
                eventSoundPlayer.attachTo(rig)
                // The die sits ON the board, in front of whoever's turn it is — the empty pocket beside
                // that team's own hangar (BoardGeometry.dieSlotPosition). It follows the turn, so this is
                // only the initial placement; syncDieSlot moves it from then on.
                dieRenderer.setRestPosition(dieSlotFor(engine.state.currentTeam))

                PANEL_IDS.forEachIndexed { index, id ->
                    attachments.entity(id)?.let { entity ->
                        rig.addChild(entity)
                        entity.components[TransformComponent::class.java]?.setPosition(PANEL_OFFSETS[index])
                    }
                }

                publish()
                Log.e(TAG, "scene ready, phase=${engine.phase}")
            } catch (t: Throwable) {
                Log.e(TAG, "SpatialView.initial failed", t)
            }
        },
    )
}

private val PANEL_IDS = listOf(START_PANEL, HUD_PANEL, RESULT_PANEL, EXIT_CONFIRM_PANEL)

// The rig itself now sits at table height, forward-offset toward the board's near edge rather than
// centred under the player (see the class doc), so panels need their own additional up-and-forward
// offset on top of that to stay comfortably readable instead of hovering at floor level right where
// the player is standing. Untested against a real head — see AGENTS.md follow-up.
private val PANEL_OFFSETS = listOf(
    Vector3(0f, 0.55f, -0.35f),
    // Centred, like the other two. It used to sit at x = +0.42, which put the turn readout 42cm off to
    // the player's right instead of over the lane facing them: 「这个游戏面板应该与玩家对面的航道对齐」.
    // The panels are children of the rig, whose local +X is the seated player's right, so x = 0 is the
    // player's own centre line — and because the board is square and the rig is aligned to the seat,
    // that line runs straight up the middle of the lane opposite them.
    Vector3(0f, 0.45f, -0.35f),
    Vector3(0f, 0.55f, -0.35f),
    // Exit-confirm shares the same centred anchor as start/result — the three are mutually exclusive
    // by their own visibility conditions, so there is never a clash.
    Vector3(0f, 0.55f, -0.35f),
)

/** How far below the player's own head the board sits — the user's explicit request. */
private const val BOARD_HEIGHT_BELOW_HEAD_M = 0.60f

/**
 * How far, horizontally, the board's centre sits from the player — in whatever direction they were
 * facing when the scene started (see [horizontalForward]) — instead of directly under their own
 * feet. The board art is [tech.illusion.spaceflightchess.content.BoardRenderer]'s `BOARD_ART_SIZE_M`
 * (0.95m) square, so its centre-to-corner reach is ~0.67m and its half-width is ~0.48m.
 *
 * Raised from 0.55m to 0.85m after the player reported still feeling inside the board: at 0.55m the
 * near edge sat only ~7cm in front of them and the footprint still surrounded their feet, so the
 * board read as something they were standing in rather than at. At 0.85m the near edge is ~0.37m
 * away — clear of the player — while the far edge stays ~1.33m out, within comfortable ray reach.
 * Still a judged value, not a measured one; needs a real head to confirm.
 */
private const val BOARD_FORWARD_OFFSET_M = 0.85f

/**
 * Flattens [rotation] onto the horizontal plane and returns the direction it faces, normalized —
 * the offset in [BOARD_FORWARD_OFFSET_M] only makes sense as a horizontal (XZ) placement, and using
 * the raw 3D forward vector would tip the board's placement up/down with however the player happened
 * to be tilting their head at scene start. Falls back to [Vector3.FORWARD] in the (practically
 * unreachable outside of looking near-straight up/down) case where the flattened vector is too short
 * to normalize meaningfully.
 *
 * **Negated relative to [Quat.rotateVector]'s raw output** — a real-headset report ("the board
 * ended up behind me") confirmed `rotation.rotateVector(Vector3.FORWARD)` actually points *backward*
 * relative to where the player is looking (the HMD pose's rotation convention doesn't match this
 * SDK's own [Vector3.FORWARD] the way `rotateVector`'s name suggests — the same class of
 * sign/handedness surprise this project already hit once with the board-texture yaw). Negating here
 * keeps [BOARD_FORWARD_OFFSET_M] itself, and every call site, reading as a plain "forward" distance.
 */
private fun horizontalForward(rotation: Quat): Vector3 {
    val forward = rotation.rotateVector(Vector3.FORWARD)
    val flattened = Vector3(-forward.x, 0f, -forward.z)
    return if (flattened.length() > MIN_HORIZONTAL_FORWARD_LENGTH) flattened.normalize() else Vector3.FORWARD
}

private const val MIN_HORIZONTAL_FORWARD_LENGTH = 0.05f

/** Generic adult eye height, used only until a real HMD sample arrives (or on the emulator, where one may never). */
private const val FALLBACK_HEAD_HEIGHT_M = 1.65f
private const val HMD_POSE_WAIT_MS = 1000L
private const val AI_THINK_DELAY_MS = 700L

/** How often the turn driver re-checks whose turn it is. See the AI-turn LaunchedEffect. */
private const val TURN_POLL_MS = 50L

/**
 * How high the die floats above the board surface at rest. It now sits ON the board (in the current
 * team's own pocket) rather than at hand height beside the player, so this is just enough clearance to
 * keep it from intersecting the printed plane.
 */
private const val DIE_REST_HEIGHT_M = 0.018f

// ── seat-change animation (see `startWithFaction`) ──────────────────────────
private const val SEAT_ANIMATION_STEPS = 30
private const val SEAT_ANIMATION_FRAME_MS = 16L
