package tech.illusion.spaceflightchess.game

import kotlin.math.cos
import kotlin.math.sin

/** A position in the board's own local space, in metres. No Spatial SDK types — pure and testable, same split as [Track]. */
data class Point3(val x: Float, val y: Float, val z: Float)

/**
 * Converts [Track]/[PieceState] positions into physical coordinates that line up with the square
 * board artwork (`app/src/main/assets/textures/board_space.jpg`, applied as a texture in
 * [tech.illusion.spaceflightchess.content.BoardRenderer]).
 *
 * **Every table here is measured off the artwork's pixels, cell by cell, not derived from a formula
 * or rotated from one quadrant.** Two earlier versions of this file tried to be clever — first a
 * circular polar layout, then a 7-point-per-quadrant table rotated four ways plus a special case for
 * the launch cell — and both produced positions that looked plausible in isolation while being
 * visibly wrong on the board. The measured tables below are the ground truth; nothing infers a cell
 * position from another cell's.
 *
 * Measurement method (reproducible, see `AGENTS.md`): flood-fill every pale cream cell marker in the
 * 1254x1254 artwork (76 of them at >=500px), drop the four non-cell artifacts (a hangar frame glow,
 * a flight-lane arrowhead at 607px, and two off-board specks), then build a mutual-near-neighbour
 * graph at a 95px threshold. That splits cleanly into one 52-node cycle (the printed perimeter ring)
 * and the four 6-cell home lanes, because the gap from a ring cell to its home-lane mouth is 108px
 * while every intra-ring and intra-lane step is 67-89px. Walking the cycle and sampling each cell's
 * printed tile colour yields a perfect period-4 BLUE/GREEN/RED/GOLD sequence around all 52 cells —
 * an independent confirmation that the traced order is the real printed order, since any tracing
 * error would break the period.
 *
 * The ring is a 52-cell 飞行棋 loop, **not** the 28-cell loop the design doc assumed — see [Track]'s
 * class doc for why that mattered and what it broke.
 */
object BoardGeometry {
    /** Must match [tech.illusion.spaceflightchess.content.BoardRenderer]'s `BOARD_ART_SIZE_M`. */
    private const val BOARD_ART_SIZE_M = 0.95f
    private const val BOARD_ART_PX = 1254f
    private const val METERS_PER_PIXEL = BOARD_ART_SIZE_M / BOARD_ART_PX

    /** The artwork's own centre pixel — also the finish/home for every team. */
    private const val CENTER_PX = 627f

    /**
     * The 52 printed perimeter cells, in clockwise order, indexed exactly as [Track.ringIndex]
     * returns. Index 0 is arbitrary (the walk's start) but fixed; [Track]'s per-team standby
     * indices are expressed against this same numbering.
     *
     * Note the shape is not a plain square: each of the four corners dips inward around that team's
     * hangar (a 13-cell run of 5 straight edge cells plus 8 corner cells per quadrant), and four of
     * those corner squares are split by a diagonal divider into two independent half-cells — the
     * detail both earlier versions of this file missed, which is why they budgeted 7 cells per
     * quadrant instead of 13.
     */
    private val RING_PX = listOf(
        468.3f to 87.3f, 547.2f to 87.2f, 627.8f to 87.9f, 709.8f to 87.3f,
        787.5f to 87.3f, 869.6f to 119.3f, 896.6f to 196.4f, 896.2f to 270.5f,
        868.7f to 344.2f, 919.0f to 401.2f, 993.2f to 371.2f, 1061.3f to 370.8f,
        1137.5f to 406.5f, 1165.3f to 481.2f, 1164.8f to 556.0f, 1164.5f to 631.7f,
        1164.8f to 704.7f, 1164.8f to 779.1f, 1137.1f to 854.1f, 1061.1f to 881.7f,
        992.6f to 881.9f, 918.5f to 853.2f, 868.6f to 911.7f, 894.8f to 985.4f,
        895.4f to 1058.6f, 869.5f to 1138.2f, 787.4f to 1167.9f, 706.2f to 1169.1f,
        627.1f to 1167.5f, 546.6f to 1167.4f, 468.8f to 1167.8f, 387.7f to 1136.9f,
        358.4f to 1059.2f, 358.1f to 987.0f, 387.6f to 911.9f, 335.8f to 854.8f,
        261.0f to 882.0f, 192.3f to 882.0f, 117.1f to 853.6f, 87.8f to 778.5f,
        87.8f to 704.4f, 87.8f to 629.5f, 87.8f to 555.3f, 87.8f to 480.5f,
        116.8f to 406.1f, 192.2f to 371.2f, 261.3f to 371.2f, 335.4f to 401.1f,
        387.0f to 343.8f, 358.3f to 270.6f, 358.5f to 196.5f, 387.9f to 118.2f,
    )

    /**
     * Each team's own 6 home-lane cells, outermost (adjacent to its ring mouth) first. Measured per
     * team off its own printed arm of the centre cross rather than rotated from one team's, so the
     * artwork's small asymmetries do not accumulate.
     */
    private val HOME_LANE_PX = mapOf(
        Team.RED to listOf(
            627.2f to 196.3f, 627.2f to 271.5f, 627.2f to 352.3f,
            626.9f to 429.5f, 627.7f to 509.4f, 627.4f to 569.6f,
        ),
        Team.YELLOW to listOf(
            1066.9f to 631.4f, 994.8f to 630.9f, 921.0f to 630.5f,
            848.8f to 631.0f, 774.4f to 631.2f, 699.7f to 630.9f,
        ),
        Team.BLUE to listOf(
            626.7f to 1052.1f, 626.9f to 978.1f, 626.9f to 903.3f,
            626.9f to 828.9f, 627.0f to 754.2f, 626.5f to 694.2f,
        ),
        Team.GREEN to listOf(
            185.7f to 629.7f, 259.0f to 629.6f, 333.4f to 629.9f,
            405.6f to 629.9f, 480.9f to 630.2f, 553.4f to 630.3f,
        ),
    )

    /**
     * Where the die waits during a team's turn: the empty starfield pocket between that team's hangar
     * corner and the centre cross, i.e. the spot the player circled on the artwork.
     *
     * Measured off the annotated reference for YELLOW at (769, 1008) and expressed here as GREEN's
     * unrotated equivalent, so the other three fall out of the same 90-degree rotation the hangars use.
     * All four rotations were checked against the measured cell tables: each sits at least 114px from
     * the nearest printed cell and samples as dark starfield, so the die never covers printed artwork.
     */
    private val GREEN_DIE_SLOT_PX = 485f to 246f

    /** Green's own four hangar pad centres; the other three corners are this block rotated, which measures accurate to ~1.5px. */
    private val GREEN_HANGAR_PX = listOf(98f to 105f, 203f to 105f, 98f to 212f, 203f to 212f)

    private fun pixelToLocal(px: Float, py: Float): Point3 =
        Point3((px - CENTER_PX) * METERS_PER_PIXEL, 0f, (py - CENTER_PX) * METERS_PER_PIXEL)

    /** Rotates a local XZ point by `steps` * 90 degrees clockwise (as seen from above) around the shared centre. */
    private fun rotate(point: Point3, steps: Int): Point3 {
        val angle = steps * (Math.PI / 2.0)
        val cosA = cos(angle).toFloat()
        val sinA = sin(angle).toFloat()
        return Point3(point.x * cosA - point.z * sinA, 0f, point.x * sinA + point.z * cosA)
    }

    /** Clockwise order in the artwork is green -> red -> yellow -> blue; green is the unrotated reference (step 0). */
    private fun quadrantSteps(team: Team): Int = when (team) {
        Team.GREEN -> 0
        Team.RED -> 1
        Team.YELLOW -> 2
        Team.BLUE -> 3
    }

    /**
     * Extra whole-board rotation (in 90-degree steps) layered on top of the measured tables, so the
     * human's chosen faction's hangar lands at the near/seat quadrant instead of wherever the
     * artwork happens to draw that team. Set once via [configureSeat] when the player picks a
     * faction on the start panel; zero (no extra rotation) until then.
     */
    private var seatRotationSteps: Int = 0

    /** Yellow's own (unrotated) quadrant — bottom-right, i.e. the +X/+Z corner in local space, which [rotate]'s
     * sign convention places nearest a player standing in front of the board looking down/forward at it. */
    private const val SEAT_TARGET_STEPS = 2

    /**
     * Call once when the player picks [team] as their own faction (before the first position lookup
     * of the new game). Rotates the whole board — every team's positions, not just [team]'s — so
     * [team]'s hangar ends up at the near/seat quadrant.
     * [tech.illusion.spaceflightchess.content.BoardRenderer] must apply the matching visual rotation
     * via [seatRotationDegrees], or the artwork and the piece positions will disagree.
     */
    fun configureSeat(team: Team) {
        seatRotationSteps = (SEAT_TARGET_STEPS - quadrantSteps(team) + 4) % 4
    }

    /** The board-art plane's matching yaw, in degrees, for the current [configureSeat] choice. */
    val seatRotationDegrees: Float get() = seatRotationSteps * 90f

    /** [ringIndex] is 0..[Track.RING_SIZE]-1 as returned by [Track.ringIndex] — a direct table lookup, no arithmetic. */
    fun ringPosition(ringIndex: Int): Point3 {
        val (px, py) = RING_PX[ringIndex]
        return rotate(pixelToLocal(px, py), seatRotationSteps)
    }

    /** `slot` is 0..[Track.HOME_LANE_SIZE]-1, walking inward from this team's ring mouth. */
    fun homeLanePosition(team: Team, slot: Int): Point3 {
        val (px, py) = HOME_LANE_PX.getValue(team)[slot]
        return rotate(pixelToLocal(px, py), seatRotationSteps)
    }

    /** [index] is 0..3, one of green's four measured hangar-pad centres, rotated into the requested team's own corner. */
    fun hangarPosition(team: Team, index: Int): Point3 {
        val (px, py) = GREEN_HANGAR_PX[index]
        return rotate(pixelToLocal(px, py), (quadrantSteps(team) + seatRotationSteps) % 4)
    }

    /**
     * 待飞 stays on the plane's own hangar pad — there is no standby cell on the board (see [Track]).
     * The state is conveyed visually instead; `PlanePieceRenderer` lifts, scales, highlights and
     * animates the plane on top of this position.
     */
    fun standbyPosition(team: Team, pieceIndex: Int): Point3 = hangarPosition(team, pieceIndex)

    /**
     * The die's resting spot for [team] — in front of that team, clear of the printed cells. Follows the
     * seat rotation like everything else, so the seated human's own slot ends up nearest them.
     */
    fun dieSlotPosition(team: Team): Point3 {
        val (px, py) = GREEN_DIE_SLOT_PX
        return rotate(pixelToLocal(px, py), (quadrantSteps(team) + seatRotationSteps) % 4)
    }

    /**
     * Heading of a board-local direction, in degrees, `0° = +Z` and `+90° = +X` — the SDK's right-handed
     * yaw about +Y.
     *
     * Null when the two points are effectively the same spot, meaning "there is no new heading here, keep
     * the one you have". Real ring legs are 5.2–6.7cm, so the threshold only ever catches degenerate input.
     */
    fun headingDegrees(from: Point3, to: Point3): Float? {
        val dx = to.x - from.x
        val dz = to.z - from.z
        if (dx * dx + dz * dz < MIN_HEADING_LEG_SQ_M) return null
        return Math.toDegrees(kotlin.math.atan2(dx.toDouble(), dz.toDouble())).toFloat()
    }

    /** 0.1mm, squared — far below the smallest real leg, so this only rejects a zero-length direction. */
    private const val MIN_HEADING_LEG_SQ_M = 1e-8f

    /**
     * Where a *settled* piece points: along the leg it just flew, or — while parked on its own pad — along
     * the leg it will fly next.
     *
     * [positionFor] of 待飞 is the hangar pad, so `InHangar`, 待飞, ring cells, the home lane and the
     * finish all fall out of one formula with no special cases.
     *
     * Cell **centres** on purpose, never the renderer's `targetPosition`/`waypointPosition`: mixing a live
     * pivot position (which carries the previous state's co-occupant offset and its lift) with a bare
     * waypoint would bend the heading. Between two `positionFor` results the offset cancels exactly.
     */
    fun settledHeadingDegrees(team: Team, pieceIndex: Int, piece: PieceState): Float? {
        val path = (piece as? PieceState.OnPath)?.value ?: Track.STANDBY_PATH
        val (fromPath, toPath) = if (path <= Track.STANDBY_PATH) Track.STANDBY_PATH to 1 else (path - 1) to path
        return headingDegrees(
            positionFor(team, pieceIndex, PieceState.OnPath(fromPath)),
            positionFor(team, pieceIndex, PieceState.OnPath(toPath)),
        )
    }

    /**
     * Which physical spot a piece stands on, as an identity that two pieces share **iff** they are drawn
     * at the same place. The renderer uses it to decide whether a piece is alone on its cell (draw it
     * dead centre) or sharing it (fan it out) — see `PlanePieceRenderer.targetPosition`.
     *
     * The cross-team case is the one that has to be right: every team numbers its own path from its own
     * 待飞区, so RED path 8 and YELLOW path 21 can be the same printed cell and must key alike. Hangar
     * pads and 待飞 are per-piece parking spots, so they key uniquely and never fan out.
     */
    fun occupancyKey(team: Team, pieceIndex: Int, piece: PieceState): String = when (piece) {
        PieceState.InHangar -> "hangar:$team:$pieceIndex"
        is PieceState.OnPath -> when {
            Track.isInStandby(piece.value) -> "hangar:$team:$pieceIndex"
            Track.isOnSharedLoop(piece.value) -> "ring:${Track.ringIndex(team, piece.value)}"
            Track.isInHomeLane(piece.value) -> "lane:$team:${piece.value}"
            else -> "finish:$team"
        }
    }

    /**
     * How far a piece must be nudged off its cell centre so that pieces sharing that cell stay tellable
     * apart — `(0,0,0)` whenever the piece is alone, which is the case that matters.
     *
     * The renderer used to offset *every* piece by a fixed per-index jitter, alone or not. That put a
     * lone plane 1.7cm off centre — 29% of the 5.9cm cell pitch — and left two 僚机 stack-mates 2.4cm
     * apart, i.e. 40% of the pitch, so a stack read as two planes on *neighbouring* cells. Captures then
     * looked like misses even though the engine had resolved them correctly.
     *
     * Genuine co-occupants separate mainly by height ([CO_OCCUPANT_STEP_M]): a 僚机 stack should look
     * stacked, and any lateral fan wide enough to separate 5cm planes is a large fraction of the cell
     * pitch and brings the ambiguity straight back. [CO_OCCUPANT_RADIUS_M] only adds enough sideways
     * spread to count the planes from above.
     */
    fun coOccupantOffset(team: Team, pieceIndex: Int, state: GameState): Point3 {
        val key = occupancyKey(team, pieceIndex, state.pieces(team)[pieceIndex])
        val occupants = Team.entries.flatMap { other ->
            state.pieces(other).indices
                .filter { occupancyKey(other, it, state.pieces(other)[it]) == key }
                .map { other to it }
        }
        val rank = occupants.indexOf(team to pieceIndex)
        if (occupants.size <= 1 || rank < 0) return Point3(0f, 0f, 0f)
        val angle = 2.0 * Math.PI * rank / occupants.size
        return Point3(
            CO_OCCUPANT_RADIUS_M * kotlin.math.cos(angle).toFloat(),
            rank * CO_OCCUPANT_STEP_M,
            CO_OCCUPANT_RADIUS_M * kotlin.math.sin(angle).toFloat(),
        )
    }

    /** Sideways spread for pieces sharing a cell — small next to the 5.9cm cell pitch, on purpose. */
    const val CO_OCCUPANT_RADIUS_M = 0.008f

    /** Height between pieces sharing a cell. Does the real work of telling a 僚机 stack apart. */
    const val CO_OCCUPANT_STEP_M = 0.014f

    fun positionFor(team: Team, pieceIndex: Int, piece: PieceState): Point3 = when (piece) {
        PieceState.InHangar -> hangarPosition(team, pieceIndex)
        is PieceState.OnPath -> when {
            Track.isInStandby(piece.value) -> standbyPosition(team, pieceIndex)
            Track.isOnSharedLoop(piece.value) -> ringPosition(Track.ringIndex(team, piece.value))
            Track.isInHomeLane(piece.value) -> homeLanePosition(team, piece.value - Track.LOOP_LAST_PATH - 1)
            // Finished (path == Track.FINISH_PATH): the 6th and innermost home-lane cell is itself the
            // finish — there is no separate 7th "home" position past it, see [Track]'s class doc.
            else -> homeLanePosition(team, Track.HOME_LANE_SIZE - 1)
        }
    }
}
