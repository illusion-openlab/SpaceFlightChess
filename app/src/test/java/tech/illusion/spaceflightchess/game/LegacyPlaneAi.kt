package tech.illusion.spaceflightchess.game

/**
 * The AI as it shipped on 2026-08-18, frozen as a **baseline opponent** for [SelfPlay].
 *
 * Kept verbatim rather than deleted so "the new AI is stronger" is a measured claim instead of an
 * opinion: [PlaneAiStrengthTest] plays the live [PlaneAi] against this and reports the win rate.
 * Do not "improve" this file — its whole value is that it does not change.
 *
 * Its logic is a four-key lexicographic priority: capture > escape a capture risk > launch > advance
 * the furthest-along piece. Its known blind spot is the last key: it ranks `Move.to`, which is the
 * *pre-carry* destination, so it cannot see the colour hop (+4) or the flight lane (+12) that
 * [GameEngine.resolve] applies afterwards.
 */
object LegacyPlaneAi {

    fun chooseMove(state: GameState, legalMoves: List<Move>): Move {
        require(legalMoves.isNotEmpty()) { "chooseMove requires at least one legal move" }
        if (legalMoves.all { it.to == PieceState.InHangar }) {
            return legalMoves.minByOrNull { pathValueOf(it.from) } ?: legalMoves.first()
        }
        val comparator = compareBy<Move> { capturesOpponent(state, it) }
            .thenBy { escapesCaptureRisk(state, it) }
            .thenBy { it.from == PieceState.InHangar }
            .thenBy { pathValueOf(it.to) }
        return legalMoves.maxWithOrNull(comparator) ?: legalMoves.first()
    }

    private fun pathValueOf(state: PieceState): Int = (state as? PieceState.OnPath)?.value ?: -1

    private fun capturesOpponent(state: GameState, move: Move): Boolean =
        GameEngine.resolve(state, move).second.capturedTeam != null

    private fun escapesCaptureRisk(state: GameState, move: Move): Boolean {
        if (!isAtRisk(state, move.team, move.from)) return false
        val (after, _) = GameEngine.resolve(state, move)
        return !isAtRisk(after, move.team, after.pieces(move.team)[move.pieceIndex])
    }

    private fun isAtRisk(state: GameState, team: Team, pieceState: PieceState): Boolean {
        val cell = Track.ringIndexOrNull(team, pieceState) ?: return false
        if (state.pieces(team).count { Track.ringIndexOrNull(team, it) == cell } >= 2) return false
        return Team.entries.filter { it != team }.any { otherTeam ->
            state.pieces(otherTeam).any { opponentPiece ->
                opponentPiece is PieceState.OnPath && (1..6).any { roll ->
                    val newPath = opponentPiece.value + roll
                    newPath <= Track.FINISH_PATH &&
                        Track.ringIndexOrNull(otherTeam, PieceState.OnPath(newPath)) == cell
                }
            }
        }
    }
}
