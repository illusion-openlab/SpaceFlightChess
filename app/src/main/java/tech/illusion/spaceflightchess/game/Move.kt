package tech.illusion.spaceflightchess.game

/**
 * One candidate move: [team]'s piece at [pieceIndex], from [from] to [to]. Not yet applied.
 *
 * [groupPieceIndices] is every piece this move relocates. It is normally just [pieceIndex], but
 * design doc §6 forbids splitting a 僚机 ("僚机整体不可拆分单独移动其中一架离开该格——移动时以
 * '这一格的飞机整体'为单位选择"): when two or more of a team's own planes share a cell they move as
 * one unit, so [GameEngine.legalMoves] emits a single [Move] for the whole stack with every index in
 * it and [pieceIndex] as its representative. Callers that relocate or animate pieces must honour
 * [groupPieceIndices], not just [pieceIndex].
 *
 * [bounceWall] is set only for a 撞墙反弹 (see [GameEngine.destination]/[GameEngine.destinationAfterBlocking]):
 * the path value the plane advanced to before reversing — a blocked stack's own cell, one cell short
 * of a bigger opposing stack, or the finish itself on overshoot. `null` means [to] was reached by a
 * plain, single-direction walk. It exists purely so [movementWaypoints] can reconstruct the forward
 * leg of the bounce, which [from]/[to] alone cannot.
 */
data class Move(
    val team: Team,
    val pieceIndex: Int,
    val from: PieceState,
    val to: PieceState,
    val groupPieceIndices: List<Int> = listOf(pieceIndex),
    val bounceWall: Int? = null,
)

/**
 * The positions a piece visibly passes through while [move] plays out, in order, ending where [after]
 * says it stands. The renderer spends a fixed beat on each one, so a roll of 4 reads as four separate
 * steps along the printed cells instead of one glide across them.
 *
 * Distinguishes **walking** from **leaping**, because the rules do:
 *  - Dice movement walks the cells one at a time — forwards, or backwards when a 僚机 stack (or the
 *    finish, on overshoot) bounced the plane back (倒飞). A bounce first walks *forward* to
 *    [Move.bounceWall] — the wall it reflected off — then *backward* to [to], so the animation visits
 *    the wall instead of gliding straight from [from] to [to].
 *  - A colour jump (+4) or the drawn flight lane (+12) is a single leap, however far it travels — those
 *    are printed as jumps on the artwork, not as cells to be walked, and [after] is where they end.
 *  - Leaving or entering the hangar is one hop: a hangar pad and 待飞 are parking spots, not cells.
 *
 * Empty when the move relocates nothing, so callers must handle a no-op.
 */
fun movementWaypoints(move: Move, after: GameState): List<PieceState> {
    val from = move.from
    val to = move.to
    val steps = mutableListOf<PieceState>()
    if (from is PieceState.OnPath && to is PieceState.OnPath) {
        val wall = move.bounceWall
        if (wall != null) {
            (from.value + 1..wall).forEach { steps += PieceState.OnPath(it) }
            (wall - 1 downTo to.value).forEach { steps += PieceState.OnPath(it) }
        } else {
            val walk = if (to.value >= from.value) (from.value + 1)..to.value else (from.value - 1) downTo to.value
            walk.forEach { steps += PieceState.OnPath(it) }
        }
    } else if (from != to) {
        steps += to
    }
    val landed = after.pieces(move.team)[move.pieceIndex]
    if (landed != to) steps += landed
    return steps
}

/**
 * What actually happened when a [Move] was applied — the renderer's cue sheet for which
 * animation(s) to play (capture / pairing / flight / entering the home lane / finishing).
 *
 * [capturedTeam] is kept only for the common single-opponent case; [capturedPieces] is authoritative
 * when one landing sends pieces from more than one opponent home at once (rare, but it happens when
 * two opponents' lone planes already share the destination cell).
 */
data class MoveOutcome(
    val move: Move,
    val capturedTeam: Team? = null,
    val capturedPieceIndices: List<Int> = emptyList(),
    val capturedPieces: Map<Team, List<Int>> = emptyMap(),
    val pairedWithPieceIndex: Int? = null,
    val flightTriggered: Boolean = false,
    /** Landed on an own-colour cell and hopped to the next one — see [GameEngine.resolve]. */
    val colourHopped: Boolean = false,
    /** The colour jump / flight was available but a 僚机 stack sat on a cell it would have crossed. */
    val carryBlockedByStack: Boolean = false,
    val enteredHomeLane: Boolean = false,
    val finished: Boolean = false,
)
