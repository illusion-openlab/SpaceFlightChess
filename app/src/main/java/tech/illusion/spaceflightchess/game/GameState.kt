package tech.illusion.spaceflightchess.game

/**
 * The complete, immutable snapshot of one game in progress. [GameEngine] is the only thing that
 * produces new snapshots — this class itself has no turn rules baked in beyond "four pieces per
 * team", same framework-free split as [Track].
 */
data class GameState(
    val piecesByTeam: Map<Team, List<PieceState>>,
    val currentTeam: Team,
) {
    init {
        require(piecesByTeam.keys == Team.entries.toSet()) { "must have exactly one piece list per team" }
        require(piecesByTeam.values.all { it.size == PIECES_PER_TEAM }) {
            "each team must have exactly $PIECES_PER_TEAM pieces"
        }
    }

    fun pieces(team: Team): List<PieceState> = piecesByTeam.getValue(team)

    fun withPiece(team: Team, index: Int, newState: PieceState): GameState =
        copy(piecesByTeam = piecesByTeam + (team to pieces(team).toMutableList().also { it[index] = newState }))

    fun withCurrentTeam(team: Team): GameState = copy(currentTeam = team)

    /** Indices of [team]'s *other* pieces sharing a cell with its piece at [pieceIndex] (a pair, once this includes exactly one other). */
    /**
     * The team's other pieces occupying the same **cell** as [pieceIndex] — i.e. the rest of its 僚机.
     *
     * Deliberately cell-based rather than raw [PieceState] equality. Equality alone also matched 待飞
     * planes (all at path 0 but on four *different* hangar pads), home-lane planes and FINISHED planes
     * (all at path [Track.FINISH_PATH]), so it reported stacks that do not exist on the board. That is
     * the exact mis-encoding that shipped the "two 待飞 planes fly together" bug via a different code
     * path, so this predicate is kept honest even though [GameEngine.legalMoves] no longer relies on it.
     */
    fun teammatesSharing(team: Team, pieceIndex: Int): List<Int> {
        val target = pieces(team)[pieceIndex]
        if (target !is PieceState.OnPath) return emptyList()
        if (!Track.isOnSharedLoop(target.value) && !Track.isInHomeLane(target.value)) return emptyList()
        return pieces(team).indices.filter { it != pieceIndex && pieces(team)[it] == target }
    }

    /** (team, pieceIndex) of every *other* team's piece on the same absolute board cell as [team]'s piece at [pieceIndex]. */
    fun opponentsAt(team: Team, pieceIndex: Int): List<Pair<Team, Int>> {
        val absCell = Track.ringIndexOrNull(team, pieces(team)[pieceIndex]) ?: return emptyList()
        return Team.entries.filter { it != team }.flatMap { other ->
            pieces(other).indices
                .filter { Track.ringIndexOrNull(other, pieces(other)[it]) == absCell }
                .map { other to it }
        }
    }

    fun finishedPieceCount(team: Team): Int = pieces(team).count { it == PieceState.FINISHED }

    fun isTeamFinished(team: Team): Boolean = finishedPieceCount(team) == PIECES_PER_TEAM

    companion object {
        const val PIECES_PER_TEAM = 4

        fun newGame(currentTeam: Team = Team.RED): GameState = GameState(
            piecesByTeam = Team.entries.associateWith { List(PIECES_PER_TEAM) { PieceState.InHangar } },
            currentTeam = currentTeam,
        )
    }
}
