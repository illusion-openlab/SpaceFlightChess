package tech.illusion.spaceflightchess.game

/** One team's colour on the board. Four fixed colours, no more, no fewer. */
enum class Team {
    RED, YELLOW, BLUE, GREEN
}

/**
 * Board topology and the path/ring-index math. Framework-free — no Spatial SDK, no Android — so the
 * whole thing is unit-testable off-device, same split as `SpaceGomoku`'s `Board`.
 *
 * **This models the board that is actually printed on `board_space.jpg`, which is a standard 52-cell
 * 飞行棋 ring — not the 28-cell ring the original design doc assumed.** The 28-cell figure was
 * written before the artwork existed and was never reconciled with it; mapping 28 abstract cells
 * onto 52 printed ones is impossible (52 is not a multiple of 28, and the printed per-team offset
 * is 13, not 7), and the attempt to do so is what produced the two bugs the player reported: a
 * launch that landed one cell too far, and a first post-launch move that visibly travelled six
 * cells too many. See [BoardGeometry] for the measured pixel tables and `AGENTS.md` for the
 * measurement method.
 *
 * A piece's progress is tracked as a single monotonic `path` value, not a ring index:
 *  - `path` == [STANDBY_PATH] (0): 待飞 — the plane has been called out of the hangar by a 6 and is
 *    running up on its own hangar pad, waiting for the next roll. **There is no standby cell on the
 *    board**: the plane stays physically on its pad and the state is shown visually instead (scaled
 *    up, lifted, highlighted, model animation playing — see `PlanePieceRenderer`). It is therefore
 *    off the shared loop and cannot capture or be captured.
 *  - `path` in 1..[LOOP_LAST_PATH]: on the shared loop, "cell N" in the player's own counting — so a
 *    plane in 待飞 that rolls N lands on cell N, exactly as the player counts it.
 *    [LOOP_LAST_PATH] is this team's home-lane mouth ([homeEntryRingIndex]). Convert to the shared
 *    physical cell with [ringIndex] — this range is the only place two different teams' pieces can
 *    occupy the same physical cell, so capture/pairing applies here and nowhere else.
 *  - `path` in [LOOP_LAST_PATH]+1..[FINISH_PATH]-1: in this team's own private home lane, still
 *    travelling. No other team's piece can ever be here, by construction.
 *  - `path` == [FINISH_PATH]: home. Done. This is also the 6th and innermost printed home-lane
 *    cell — there is no 7th cell beyond it. Landing there via the 6th step *is* arriving home, not
 *    a separate move.
 */
object Track {
    /** Printed perimeter cells on the artwork — measured, see [BoardGeometry.RING_PX]. */
    const val RING_SIZE = 52

    /** 待飞: called out of the hangar by a 6, still on its own pad, off the board. See the class doc. */
    const val STANDBY_PATH = 0

    /**
     * The last shared-loop path value — this team's home-lane mouth. Measured: from every team's own
     * cell 1, its home-lane mouth is exactly 49 printed cells clockwise (verified independently for
     * all four teams), so a lap is cell 1..cell 50 and each team traverses 50 of the 52 printed
     * cells, skipping the two that sit between its own mouth and its own cell 1.
     */
    const val LOOP_LAST_PATH = 50

    /** The printed home lane's cell count — the last of these ([FINISH_PATH]) is the finish itself. */
    const val HOME_LANE_SIZE = 6

    /**
     * 待飞 (path 0) + loop (1..50) + home lane (51..56), where 56 — the 6th home-lane cell — is home.
     * There is no 7th cell past it: [BoardGeometry]'s per-team home-lane table has exactly
     * [HOME_LANE_SIZE] measured positions, and the last one is where a finished plane sits.
     */
    const val FINISH_PATH = LOOP_LAST_PATH + HOME_LANE_SIZE

    const val LAUNCH_ROLL = 6

    /**
     * Rolling [LAUNCH_ROLL] this many times in a row costs the team a plane: it must send one of its
     * own airborne planes back to the hangar, and its turn ends. The counter is per turn-sequence and
     * resets whenever the turn passes.
     */
    const val CONSECUTIVE_SIX_LIMIT = 3

    /**
     * Safe cells: a plane standing here can never be captured.
     *
     * The set is the team's own home lane (and the finish). This has always been true in practice —
     * a lane cell has no shared ring index, so [ringIndexOrNull] returns null there and the capture
     * scan simply never sees it — but it was an emergent side effect of the coordinate encoding
     * rather than a stated rule. Naming it makes the guarantee explicit, checkable and testable
     * (guide 8.3: 「吃子逻辑是否排除了安全格？」), so a future change to the encoding cannot silently
     * remove it. Lanes are private, so no other colour can ever stand on a safe cell.
     */
    fun isSafeCell(path: Int): Boolean = isInHomeLane(path) || isFinished(path)

    /**
     * The one path value that sits on this team's own flight square, and how far the drawn lane
     * carries it. The artwork draws exactly four colour-coded dashed flight lanes, each starting on
     * a cell of one team's own colour and ending on another cell of that same colour 12 printed
     * cells clockwise. Measured against the ring table, every team's own lane start lands on path
     * 18 and its end on path 30 — the same offsets for all four teams, which is a strong independent
     * check that the ring numbering is right.
     *
     * Because the lanes are colour-coded, a plane only flies from *its own* colour's square: RED
     * passing over GREEN's flight cell is at a different path value, so it does not trigger.
     */
    const val FLIGHT_PATH = 18
    const val FLIGHT_ADVANCE = 12

    /**
     * The printed ring's tile colours run BLUE, GREEN, RED, GOLD with a perfect period of 4 (measured
     * — see [BoardGeometry]'s class doc), so the next cell of any given colour is always exactly
     * [COLOUR_CYCLE] cells on. That is what makes the player's colour rule expressible as arithmetic:
     * *land on a cell of your own colour and you jump to the next one of your colour.*
     *
     * Because each team's cell 1 is offset to its own hangar, the own-colour cells work out to the
     * **same path values for every team** — exactly `path % 4 == 2`, 13 of them per lap. That
     * four-way agreement (verified per team against the measured colour cycle) is another independent
     * check on the ring numbering, the same way [FLIGHT_PATH] landing on 18 for all four teams was.
     * Note [FLIGHT_PATH] is itself an own-colour cell, which matches the artwork drawing every flight
     * lane as starting on its owner's own colour.
     */
    const val COLOUR_CYCLE = 4
    private const val OWN_COLOUR_REMAINDER = 2

    /**
     * Whether [path] is one of this team's own-colour cells — the trigger for the colour jump.
     *
     * Restricted to the shared loop on purpose. Every cell of a team's *home lane* is printed in that
     * team's own colour too, so without this guard a plane would trigger the jump on every single
     * home-lane step; and the home-lane mouth (path [LOOP_LAST_PATH]) is own-coloured as well, which
     * is precisely why it is that team's mouth.
     */
    fun isOwnColourCell(path: Int): Boolean =
        isOnSharedLoop(path) && path % COLOUR_CYCLE == OWN_COLOUR_REMAINDER

    /**
     * The next own-colour cell after [path], or `null` if the jump would leave the shared loop.
     *
     * Returning null at the end of the lap is the guard that keeps the jump a ring mechanic: the
     * mouth at [LOOP_LAST_PATH] is own-coloured, and jumping from it would vault four cells deep into
     * the private home lane rather than turning in normally.
     */
    fun colourHopTarget(path: Int): Int? {
        if (!isOwnColourCell(path)) return null
        val target = path + COLOUR_CYCLE
        return if (isOnSharedLoop(target)) target else null
    }

    /**
     * Where each team's **cell 1** — its first step onto the board — sits in [BoardGeometry.RING_PX]'s
     * clockwise 0..51 numbering.
     *
     * Derived from the artwork, not chosen: the player's own rule is that the run starts on the
     * *previous* colour's triangle cell beside its hangar. Flood-fill detection of every printed
     * cell plus a cycle walk gives a ring whose tile colours run in a perfect period-4 cycle, and
     * the four triangles flanking the four hangars are printed #5 (green, beside red's hangar), #18
     * (red, beside yellow's), #31 (gold, beside blue's) and #44 (blue, beside green's) — each one
     * the previous player's colour, a four-way match to the rule.
     */
    private val cell1RingIndexByTeam = mapOf(
        Team.RED to 5,
        Team.YELLOW to 18,
        Team.BLUE to 31,
        Team.GREEN to 44,
    )

    fun cell1RingIndex(team: Team): Int = cell1RingIndexByTeam.getValue(team)

    /** The printed ring cell this team turns off the loop into its own home lane from. */
    fun homeEntryRingIndex(team: Team): Int = ringIndex(team, LOOP_LAST_PATH)

    /** 待飞 (path 0) is deliberately excluded: the plane is still on its own pad, not on the board. */
    fun isOnSharedLoop(path: Int): Boolean = path in 1..LOOP_LAST_PATH

    fun isInStandby(path: Int): Boolean = path == STANDBY_PATH

    fun isInHomeLane(path: Int): Boolean = path in (LOOP_LAST_PATH + 1) until FINISH_PATH

    fun isFinished(path: Int): Boolean = path >= FINISH_PATH

    /**
     * The shared physical cell (0..[RING_SIZE]-1) this team's [path] sits on. Only meaningful while
     * [isOnSharedLoop] — the home lane is private, there is no shared cell.
     */
    fun ringIndex(team: Team, path: Int): Int {
        require(isOnSharedLoop(path)) {
            "ringIndex is only defined on the shared loop (path 1..$LOOP_LAST_PATH), got $path"
        }
        return (cell1RingIndex(team) + path - 1) % RING_SIZE
    }

    /** Null in the hangar, the home lane, or finished — those are never on a shared cell. */
    fun ringIndexOrNull(team: Team, state: PieceState): Int? {
        val path = (state as? PieceState.OnPath)?.value ?: return null
        return if (isOnSharedLoop(path)) ringIndex(team, path) else null
    }

    /** Team-independent by construction — see [FLIGHT_PATH]. */
    fun isFlightTrigger(path: Int): Boolean = path == FLIGHT_PATH

    /**
     * Advances a landed-on-its-own-flight-square piece along its *own* path by [FLIGHT_ADVANCE], to
     * the printed cell the drawn lane points at. Because [FLIGHT_PATH] + [FLIGHT_ADVANCE] is well
     * inside the loop this can never overshoot the finish, but the clamp is kept so the function is
     * still total if the constants are ever retuned.
     *
     * Only ever applied once per move — callers must not re-check the landing cell for a second
     * trigger (the destination is another cell of the same colour, so an unguarded re-check would
     * recurse). See [GameEngine.resolve].
     */
    fun applyFlight(path: Int): Int = (path + FLIGHT_ADVANCE).coerceAtMost(FINISH_PATH)
}
