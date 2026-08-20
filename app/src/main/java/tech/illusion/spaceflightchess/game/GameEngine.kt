package tech.illusion.spaceflightchess.game

/** Where the game is: pre-game setup, waiting for a roll, waiting for a move, or over. */
enum class Phase {
    SETUP,
    AWAITING_ROLL,
    AWAITING_MOVE,
    GAME_OVER,
}

/** Things the renderer and HUD need to react to. Consumed via [GameEngine.drainEvents]. */
sealed interface GameEvent {
    data object Started : GameEvent
    data class Rolled(val team: Team, val value: Int) : GameEvent
    data class NoLegalMove(val team: Team, val value: Int) : GameEvent
    data class Moved(val outcome: MoveOutcome) : GameEvent
    data class ExtraRoll(val team: Team) : GameEvent
    data class TurnPassed(val from: Team, val to: Team) : GameEvent
    /** Third consecutive 6: [team] must send one of its own airborne planes back to the hangar. */
    data class TripleSix(val team: Team) : GameEvent
    data class Won(val ranking: List<Team>) : GameEvent
    data object Restarted : GameEvent
    data object IllegalMove : GameEvent
}

/**
 * Turn state machine over [GameState] plus an event queue for the presentation layer — same split
 * as `SpaceGomoku`'s `GameEngine`. Framework-free on purpose: no Spatial SDK, no Android, so the
 * whole thing (including AI look-ahead via the companion's pure [legalMoves]/[resolve]) is
 * unit-testable off-device.
 *
 * Both the human's turn and every AI turn are driven through the exact same three calls — [roll],
 * inspect [legalMovesForPendingRoll], [applyMove] — this class has no notion of "human" or "AI" at
 * all. `PlaneAi` only needs [legalMovesForPendingRoll] and [applyMove]; who calls them for which
 * [GameState.currentTeam] is the caller's (`BoardStage`'s) decision.
 */
class GameEngine(private val dice: DiceEngine = DiceEngine()) {

    var state: GameState = GameState.newGame()
        private set

    var phase: Phase = Phase.SETUP
        private set

    private var pendingRoll: Int? = null
    private var legalMovesCache: List<Move> = emptyList()

    /** How many 6s the current team has rolled back to back. See [Track.CONSECUTIVE_SIX_LIMIT]. */
    private var consecutiveSixes = 0

    private val events = ArrayList<GameEvent>()

    val isOver: Boolean get() = phase == Phase.GAME_OVER

    /** Hands over the queued events and clears it. */
    fun drainEvents(): List<GameEvent> {
        if (events.isEmpty()) return emptyList()
        val drained = ArrayList(events)
        events.clear()
        return drained
    }

    /** Leaves the pre-game setup screen. A no-op once already past it. */
    fun startGame() {
        if (phase != Phase.SETUP) return
        state = GameState.newGame()
        phase = Phase.AWAITING_ROLL
        consecutiveSixes = 0
        events.add(GameEvent.Started)
    }

    /**
     * Rolls for [GameState.currentTeam]. When the roll has no legal move at all, this auto-resolves
     * the rest of the turn-segment (extra roll on a 6, or pass to the next team) by itself — callers
     * never need to special-case "nothing to do with this roll".
     *
     * @return the rolled value, or `null` if called outside [Phase.AWAITING_ROLL].
     */
    fun roll(): Int? {
        if (phase != Phase.AWAITING_ROLL) {
            events.add(GameEvent.IllegalMove)
            return null
        }
        val team = state.currentTeam
        val value = dice.roll()
        // Backstop for guide 1.1 ("拒绝 0、7、负数…"). The shipped DiceEngine is 1..6, but it is `open` and
        // constructor-injected (the tests already substitute a scripted one), and an out-of-range value would
        // otherwise flow silently into legalMoves/resolve: a 0 produces zero-cell "moves" that can still be
        // carried 4 cells by the colour hop, and a negative value produces backward moves. That is a broken
        // die — a contract violation, not a legal game input — so fail loudly rather than corrupt the board.
        require(value in 1..DiceEngine.FACES) { "dice roll must be 1..${DiceEngine.FACES}, got $value" }
        events.add(GameEvent.Rolled(team, value))

        consecutiveSixes = if (value == Track.LAUNCH_ROLL) consecutiveSixes + 1 else 0
        if (consecutiveSixes >= Track.CONSECUTIVE_SIX_LIMIT) {
            consecutiveSixes = 0
            events.add(GameEvent.TripleSix(team))
            val penalties = penaltyMoves(state, team)
            if (penalties.isEmpty()) {
                // Nothing airborne to send home — the penalty simply cannot be paid, so the turn ends.
                passTurn(team)
            } else {
                // Reuses the ordinary "pick one of your planes" flow: the player taps a plane exactly as
                // they would to move it, and that plane goes home instead. No new phase or input path.
                pendingRoll = value
                legalMovesCache = penalties
                phase = Phase.AWAITING_MOVE
            }
            return value
        }

        val moves = legalMoves(state, team, value)
        if (moves.isEmpty()) {
            events.add(GameEvent.NoLegalMove(team, value))
            // The design doc is explicit (ch.2): 「合法集合为空 → 本次点数作废，直接换下一家（连续摇 6 的
            // 额外回合同样适用此判定）」, and guide 1.2 says the same. This used to route through
            // resolveEndOfRollSegment, which grants an extra roll on a 6 — so a 6 with nothing to move handed
            // the same team another roll instead of ending its turn, while rolls 1-5 in the identical situation
            // passed the turn. Same condition, two answers.
            passTurn(team)
        } else {
            pendingRoll = value
            legalMovesCache = moves
            phase = Phase.AWAITING_MOVE
        }
        return value
    }

    /** The legal moves for the roll just made — only non-empty while [phase] is [Phase.AWAITING_MOVE]. */
    fun legalMovesForPendingRoll(): List<Move> = legalMovesCache

    /**
     * Applies [move], which must be one of [legalMovesForPendingRoll]. Advances the turn (extra
     * roll on a 6, otherwise the next team) unless this move just won the game.
     *
     * @return what happened, or `null` if [move] was rejected (wrong phase, or not a legal move).
     */
    fun applyMove(move: Move): MoveOutcome? {
        // `move.team != state.currentTeam` is redundant with the cache check today (the cache only
        // ever holds the current team's moves) but is asserted anyway: the presentation layer looks
        // moves up by piece index, and a lookup that ignored the team was how a human tap could
        // apply an AI team's pending move. Rejecting it here means that class of bug cannot reach
        // the state machine again even if a caller's own guard is wrong.
        if (phase != Phase.AWAITING_MOVE || move.team != state.currentTeam || move !in legalMovesCache) {
            events.add(GameEvent.IllegalMove)
            return null
        }
        val roll = pendingRoll ?: return null
        val (newState, outcome) = resolve(state, move)
        state = newState
        pendingRoll = null
        legalMovesCache = emptyList()
        events.add(GameEvent.Moved(outcome))

        val winner = WinDetector.winner(state)
        if (winner != null) {
            phase = Phase.GAME_OVER
            events.add(GameEvent.Won(WinDetector.ranking(state)))
            return outcome
        }

        if (move.to == PieceState.InHangar) {
            // A triple-6 penalty is not a move that earns anything — the turn ends regardless of the 6.
            passTurn(move.team)
        } else {
            resolveEndOfRollSegment(move.team, roll)
        }
        return outcome
    }

    /**
     * Jumps straight to [newState] mid-[Phase.AWAITING_ROLL], skipping the normal turn machinery —
     * for tests and the on-device verification harness, which cannot drive real gesture input to
     * reach a specific position (same purpose as `SpaceGomoku`'s `GameEngine.replay`).
     */
    fun forceState(newState: GameState) {
        state = newState
        phase = Phase.AWAITING_ROLL
        pendingRoll = null
        legalMovesCache = emptyList()
        consecutiveSixes = 0
        events.clear()
    }

    /** Clears back to [Phase.SETUP] — a fresh [startGame] is required to actually play again. */
    fun restart() {
        state = GameState.newGame()
        phase = Phase.SETUP
        pendingRoll = null
        legalMovesCache = emptyList()
        consecutiveSixes = 0
        events.clear()
        events.add(GameEvent.Restarted)
    }

    /** A roll of [Track.LAUNCH_ROLL] grants the same team another roll, uncapped; anything else passes the turn on. */
    private fun resolveEndOfRollSegment(team: Team, roll: Int) {
        phase = Phase.AWAITING_ROLL
        if (roll == Track.LAUNCH_ROLL) {
            events.add(GameEvent.ExtraRoll(team))
        } else {
            passTurn(team)
        }
    }

    /** Ends [team]'s turn and hands play to the next team. */
    private fun passTurn(team: Team) {
        phase = Phase.AWAITING_ROLL
        consecutiveSixes = 0
        val next = nextTeam(team)
        state = state.withCurrentTeam(next)
        events.add(GameEvent.TurnPassed(team, next))
    }

    private fun nextTeam(team: Team): Team {
        val order = Team.entries
        return order[(order.indexOf(team) + 1) % order.size]
    }

    companion object {
        /**
         * Every legal [Move] for [team]'s pieces given [roll], with no side effects. A hangared
         * piece can only launch on [Track.LAUNCH_ROLL]; every on-path piece always has a move for any
         * roll — an unobstructed run either lands exactly on or short of the finish, or [destination]
         * bounces it back off the finish (overshoot) or off a bigger opposing stack (blocked), per the
         * player's 「撞墙反弹」 rule.
         */
        fun legalMoves(state: GameState, team: Team, roll: Int): List<Move> {
            val moves = mutableListOf<Move>()
            val pieces = state.pieces(team)

            // Hangared planes launch one at a time — planes waiting in the hangar are not a 僚机,
            // they are not on a cell at all.
            if (roll == Track.LAUNCH_ROLL) {
                pieces.forEachIndexed { index, piece ->
                    if (piece == PieceState.InHangar) {
                        moves += Move(team, index, piece, PieceState.OnPath(Track.STANDBY_PATH))
                    }
                }
            }

            // Planes actually on the shared loop are grouped by the cell they occupy, so a 僚机 (2+ of
            // our own planes on one cell) yields a single move for the whole stack — design doc §6,
            // see [Move].
            //
            // 待飞 and home-lane planes are deliberately NOT grouped. Grouping by raw path value used
            // to lump them together too, and for 待飞 that was a real bug the player hit: two planes
            // called out of the hangar by two 6s both sit at path 0, but they are on two *different*
            // hangar pads rather than one cell, so the next roll flew both of them at once.
            val stacked = mutableMapOf<Int, MutableList<Int>>()
            val solo = mutableListOf<Int>()
            pieces.forEachIndexed { index, piece ->
                if (piece !is PieceState.OnPath || piece.value == Track.FINISH_PATH) return@forEachIndexed
                // Group by cell for every on-board position — the shared loop AND the private home lane.
                // Keying this on isOnSharedLoop alone let a 僚机 split the moment it turned in: lane cells were
                // never grouped, so one member could then walk on alone, against guide 6.2's 「叠棋可以进入终点
                // 航道，但必须保持整体」. 待飞 stays out because two 待飞 planes sit on two different hangar pads
                // rather than one cell.
                if (Track.isOnSharedLoop(piece.value) || Track.isInHomeLane(piece.value)) {
                    stacked.getOrPut(piece.value) { mutableListOf() } += index
                } else {
                    solo += index
                }
            }
            stacked.forEach { (path, indices) ->
                val sorted = indices.sorted()
                val dest = destination(state, team, path, roll)
                moves += Move(team, sorted.first(), PieceState.OnPath(path), PieceState.OnPath(dest.path), sorted, dest.wall)
            }
            solo.forEach { index ->
                val path = (pieces[index] as PieceState.OnPath).value
                val dest = destination(state, team, path, roll)
                moves += Move(team, index, PieceState.OnPath(path), PieceState.OnPath(dest.path), bounceWall = dest.wall)
            }
            return moves
        }

        /**
         * The destination for one piece. Always returns a destination — every roll now resolves to
         * *somewhere* for an on-path piece, per the player's 「撞墙反弹」 rule: a roll that would run
         * into a bigger opposing stack, or past the finish, bounces back the leftover pips instead of
         * being rejected outright.
         *
         * Blocking is resolved first ([destinationAfterBlocking]): if it bounces the plane back, that
         * is already short of the finish by construction, so the exact-count rule below never applies
         * to it. Only an unobstructed run can reach or pass the finish, so only it needs the
         * overshoot check.
         */
        private fun destination(state: GameState, team: Team, path: Int, roll: Int): Destination {
            val bounce = blockedBounce(state, team, path, roll)
            if (bounce != null) return Destination(bounce.landing, bounce.wall)
            val unobstructed = path + roll
            if (unobstructed <= Track.FINISH_PATH) return Destination(unobstructed, wall = null)
            // Overshot the finish: bounce back off it the same way a blocked stack bounces — advance
            // to the wall (here, the finish itself) then spend the leftover pips going backwards.
            // Only reachable from inside the home lane (path > LOOP_LAST_PATH), because
            // LOOP_LAST_PATH + max roll (6) == FINISH_PATH exactly, so a piece still on the shared
            // loop can never overshoot.
            return Destination(2 * Track.FINISH_PATH - unobstructed, wall = Track.FINISH_PATH)
        }

        /** [path] is where the piece ends up; [wall] is set only when it got there by bouncing. */
        private data class Destination(val path: Int, val wall: Int?)

        /**
         * Applies [move] to [state] and reports what happened: capture, pairing, flight, entering
         * the home lane, finishing. Pure — used by [GameEngine.applyMove], by tests, and by
         * `PlaneAi`'s look-ahead (simulating a candidate move without touching a live engine).
         */
        fun resolve(state: GameState, move: Move): Pair<GameState, MoveOutcome> {
            // The whole 僚机 stack moves as one unit (design doc §6) — every index in the group lands
            // on the same destination, so the pairing they already had is preserved by construction.
            var newState = move.groupPieceIndices.fold(state) { acc, idx -> acc.withPiece(move.team, idx, move.to) }
            var outcome = MoveOutcome(move)

            resolveCellEffects(newState, move.team, move.pieceIndex, outcome).let {
                newState = it.first
                outcome = it.second
            }

            // The colour rule, fired at most ONCE per move, off the *landing* cell only.
            //
            // Landing on a cell of your own colour carries the plane onward: if that cell is the start
            // of your drawn flight lane it flies the full [Track.FLIGHT_ADVANCE], otherwise it hops to
            // the next own-colour cell ([Track.colourHopTarget]). Exactly one trigger, deliberately:
            // every hop destination is *also* an own-colour cell (that is what period-4 means), so a
            // re-check would recurse forever, and a flight destination is own-coloured too.
            //
            // Either move is cancelled if a 僚机 stack sits on a cell it would pass over — the
            // player's stated exception ("存在叠机在行经的格子上时则不能采用该操作").
            val landed = newState.pieces(move.team)[move.pieceIndex]
            if (landed is PieceState.OnPath) {
                val flying = Track.isFlightTrigger(landed.value)
                val carriedTo = if (flying) Track.applyFlight(landed.value) else Track.colourHopTarget(landed.value)
                val blocked = carriedTo != null && stackOnTraversedCell(newState, move.team, landed.value, carriedTo)
                if (carriedTo != null && !blocked) {
                    newState = move.groupPieceIndices.fold(newState) { acc, idx ->
                        acc.withPiece(move.team, idx, PieceState.OnPath(carriedTo))
                    }
                    // The pair that formed on the pre-carry cell may not exist on the destination, so
                    // let resolveCellEffects recompute it from scratch rather than carrying it over.
                    outcome = outcome.copy(
                        flightTriggered = flying,
                        colourHopped = !flying,
                        pairedWithPieceIndex = null,
                    )
                    resolveCellEffects(newState, move.team, move.pieceIndex, outcome).let {
                        newState = it.first
                        outcome = it.second
                    }
                } else if (blocked) {
                    outcome = outcome.copy(carryBlockedByStack = true)
                }
            }

            val finalPiece = newState.pieces(move.team)[move.pieceIndex]
            if (finalPiece is PieceState.OnPath) {
                val wasInHomeLane = (move.from as? PieceState.OnPath)?.value?.let(Track::isInHomeLane) ?: false
                if (!wasInHomeLane && Track.isInHomeLane(finalPiece.value)) {
                    outcome = outcome.copy(enteredHomeLane = true)
                }
                if (finalPiece.value == Track.FINISH_PATH) {
                    outcome = outcome.copy(finished = true)
                }
            }

            return newState to outcome
        }

        /**
         * The planes [team] may be forced to send home after a third consecutive 6 — every plane that is
         * actually airborne. Hangared planes are already home, and finished ones have left the board for
         * good, so neither can pay the penalty.
         *
         * Modelled as ordinary [Move]s whose destination is [PieceState.InHangar] so the whole existing
         * "offer a choice, player taps a plane" path works unchanged. A stack is offered as one move,
         * consistent with 僚机 moving as a unit — sending one home would split it.
         */
        fun penaltyMoves(state: GameState, team: Team): List<Move> {
            val pieces = state.pieces(team)
            val airborne = pieces.indices.filter { index ->
                val piece = pieces[index]
                piece is PieceState.OnPath && !Track.isFinished(piece.value)
            }
            // Grouped by CELL, on the same terms as legalMoves: planes sharing a loop or lane cell are a
            // 僚机 and go home together, but 待飞 planes are each on their own hangar pad and must stay
            // separate offers — grouping them by raw path (they all sit at path 0) would send several home
            // for one penalty.
            val stacked = mutableMapOf<Int, MutableList<Int>>()
            val solo = mutableListOf<Int>()
            airborne.forEach { index ->
                val path = (pieces[index] as PieceState.OnPath).value
                if (Track.isOnSharedLoop(path) || Track.isInHomeLane(path)) {
                    stacked.getOrPut(path) { mutableListOf() } += index
                } else {
                    solo += index
                }
            }
            return stacked.map { (path, indices) ->
                val sorted = indices.sorted()
                Move(team, sorted.first(), PieceState.OnPath(path), PieceState.InHangar, sorted)
            } + solo.map { index ->
                Move(team, index, pieces[index], PieceState.InHangar)
            }
        }

        /** 2+ planes of any one team standing on shared ring cell [ringIndex]. */
        private fun stackAtRing(state: GameState, ringIndex: Int): Boolean =
            Team.entries.any { other ->
                state.pieces(other).count { Track.ringIndexOrNull(other, it) == ringIndex } >= 2
            }

        /**
         * An *opposing* stack at [ringIndex] outnumbering [team]'s own moving group ([ownGroupSize]) —
         * "数量少的一方" from the mover's side. A same-size or smaller opposing group, or any size of
         * [team]'s own group, is not a blocker here: those are ordinary arrivals settled by the capture
         * and immunity rules in [resolveCellEffects].
         */
        private fun biggerOpposingStackAtRing(state: GameState, team: Team, ringIndex: Int, ownGroupSize: Int): Boolean =
            Team.entries.any { other ->
                other != team && state.pieces(other).count { Track.ringIndexOrNull(other, it) == ringIndex } > ownGroupSize
            }

        /** A resolved 撞墙反弹: [wall] is the path it reflected off, [landing] is where it ends up. */
        private data class Bounce(val wall: Int, val landing: Int)

        /**
         * Where [team]'s piece at [fromPath] ends up after rolling [roll] if a 僚机 stack blocks
         * passage — 「叠棋阻挡普通移动，超过的点数本回合飞到叠棋位置处开始进行倒飞」 — or `null` if nothing
         * blocks it. Only the first blocker is considered; a bounce that would cross a second stack is
         * not re-checked, and every bounce is floored at cell 1 because a plane cannot reverse back
         * into 待飞 (which is a hangar pad, not a board cell) or off the board.
         *
         * Two different cells are in play, with two different rules and two different pivots:
         *  - A cell merely **passed over** (any step short of the final one) is blocked by *any* stack
         *    of 2+, any team, own included ([stackAtRing]) — the plane advances all the way to that
         *    stack's own cell and spends whatever roll is left going backwards from there.
         *  - The **landing cell** (the final step) is blocked only when the opposing stack there
         *    outnumbers [team]'s own moving group ([biggerOpposingStackAtRing]) — "数量少的一方" cannot
         *    land there at all, not even a same-size or smaller opposing stack, and never the team's
         *    own stack. Because the plane truly cannot enter that cell, the wall sits one cell *before*
         *    it, not on it: the plane advances only as far as the wall, then spends whatever roll is
         *    left going backwards from there.
         */
        private fun blockedBounce(state: GameState, team: Team, fromPath: Int, roll: Int): Bounce? {
            val ownGroupSize = state.pieces(team).count { it is PieceState.OnPath && it.value == fromPath }
            for (step in 1..roll) {
                val crossed = fromPath + step
                if (!Track.isOnSharedLoop(crossed)) break
                val ring = Track.ringIndex(team, crossed)
                if (step < roll) {
                    if (stackAtRing(state, ring)) {
                        val remaining = roll - step
                        return Bounce(wall = crossed, landing = (crossed - remaining).coerceAtLeast(1))
                    }
                    continue
                }
                // The landing step itself: unlike a cell merely passed over, the plane cannot enter a
                // cell held by a bigger opposing stack at all, so the wall sits one cell *before* it
                // (not on it) — the plane advances only as far as that wall, then spends whatever roll
                // is left going backwards from there.
                if (biggerOpposingStackAtRing(state, team, ring, ownGroupSize)) {
                    val wall = crossed - 1
                    val remaining = roll - (wall - fromPath)
                    return Bounce(wall = wall, landing = (wall - remaining).coerceAtLeast(1))
                }
            }
            return null
        }

        /** Where [team]'s piece at [fromPath] actually ends up after rolling [roll]. See [blockedBounce]. */
        fun destinationAfterBlocking(state: GameState, team: Team, fromPath: Int, roll: Int): Int =
            blockedBounce(state, team, fromPath, roll)?.landing ?: (fromPath + roll)

        /**
         * Whether a 僚机 stack (2+ planes of any one team) sits on a cell strictly between [fromPath]
         * and [toPath] along [team]'s own path — the player's exception to the colour jump and the
         * flight: "存在叠机在行经的格子上时则不能采用该操作".
         *
         * Only the cells *passed over* are examined, not the destination. Arriving on a stack is
         * already a defined situation (see [resolveCellEffects]: an opponent pair is immune to a lone
         * arrival and coexists with it), so treating the destination as a blocker too would change a
         * settled rule rather than implement this one.
         */
        private fun stackOnTraversedCell(state: GameState, team: Team, fromPath: Int, toPath: Int): Boolean {
            for (path in (fromPath + 1) until toPath) {
                val ring = if (Track.isOnSharedLoop(path)) Track.ringIndex(team, path) else continue
                val stacked = Team.entries.any { other ->
                    state.pieces(other).count { Track.ringIndexOrNull(other, it) == ring } >= 2
                }
                if (stacked) return true
            }
            return false
        }

        /**
         * Capture and pairing at [team]'s piece [pieceIndex]'s *current* cell in [state] — a no-op
         * once that piece is off the shared loop (home lane / finished are always private).
         *
         * An opponent's lone piece is always captured. An opponent *pair* (2+ of the same team) is
         * immune unless [team] itself now also has 2+ pieces at this cell (a fresh pair beats an
         * existing one) — matching the design doc's "僚机免疫单机捕获... 可以被对方同样组成僚机的
         * 两架反吃". Landing alone next to an immune pair is legal and harmless: the pieces simply
         * coexist on the cell: the design doc only specifies capture immunity, not a "this cell is
         * blocked" rule, so this is the minimal reading of it.
         *
         * A normal roll never lands [team] on an opponent stack *bigger* than its own moving group —
         * [destinationAfterBlocking] bounces that away before a [Move] is even generated — so the
         * "coexist" case reached from here is same-size-or-smaller opposing stacks, or the colour-hop
         * / flight carry (which does not re-check the bounce rule on its own landing cell).
         */
        private fun resolveCellEffects(
            state: GameState,
            team: Team,
            pieceIndex: Int,
            outcomeSoFar: MoveOutcome,
        ): Pair<GameState, MoveOutcome> {
            var newState = state
            var outcome = outcomeSoFar
            val landedOn = newState.pieces(team)[pieceIndex]

            // Safe cells are never capture grounds, in either direction (guide 8.3:「吃子逻辑是否排除了
            // 安全格？」). Belt and braces: a safe cell has no shared ring index either, so ringIndexOrNull
            // below would bail anyway — but that is an emergent property of the coordinate encoding, and
            // this states the rule outright so a future encoding change cannot silently drop it.
            if ((landedOn as? PieceState.OnPath)?.value?.let(Track::isSafeCell) == true) {
                return newState to outcome
            }

            val destCell = Track.ringIndexOrNull(team, landedOn)
                ?: return newState to outcome

            val ownAtDest = newState.pieces(team).indices.filter {
                Track.ringIndexOrNull(team, newState.pieces(team)[it]) == destCell
            }
            val moverPaired = ownAtDest.size >= 2
            if (moverPaired && outcome.pairedWithPieceIndex == null) {
                outcome = outcome.copy(pairedWithPieceIndex = ownAtDest.first { it != pieceIndex })
            }

            for (otherTeam in Team.entries.filter { it != team }) {
                val otherAtDest = newState.pieces(otherTeam).indices.filter {
                    Track.ringIndexOrNull(otherTeam, newState.pieces(otherTeam)[it]) == destCell
                }
                if (otherAtDest.isEmpty()) continue
                // 「吃叠棋，少的不能吃多的」: a stack may only be taken by an equal or larger one. This
                // subsumes the old "a pair is immune to a lone arrival" rule (2 > 1) and additionally stops
                // a 2-stack from wiping out a 3- or 4-stack, which the old size-blind check allowed.
                if (otherAtDest.size > ownAtDest.size) continue // immune: fewer cannot capture more

                newState = otherAtDest.fold(newState) { acc, idx -> acc.withPiece(otherTeam, idx, PieceState.InHangar) }
                // capturedTeam/capturedPieceIndices keep the single-opponent shape earlier callers
                // expect; capturedPieces is the authoritative record when one landing sends pieces
                // from two different opponents home at once.
                outcome = outcome.copy(
                    capturedTeam = outcome.capturedTeam ?: otherTeam,
                    capturedPieceIndices = outcome.capturedPieceIndices + otherAtDest,
                    capturedPieces = outcome.capturedPieces + (otherTeam to (outcome.capturedPieces[otherTeam].orEmpty() + otherAtDest)),
                )
            }

            return newState to outcome
        }
    }
}
