package tech.illusion.spaceflightchess.game

/**
 * Pure interpretation of a [GameState]'s piece positions in terms of who's winning — no rules
 * about turns or dice live here, that is [GameEngine]'s job.
 */
object WinDetector {
    /** The team with all four pieces finished, if any. [GameEngine] stops the game the moment this first appears. */
    fun winner(state: GameState): Team? = Team.entries.firstOrNull { state.isTeamFinished(it) }

    /**
     * All four teams ranked by finished-piece count, most-finished first. Ties keep [Team]'s
     * declared order — per the design doc, only the outright winner's rank matters (it is
     * rank 1 by definition of ending the game the moment it appears); the remaining three are
     * ranked by "how far they got", not by any notion of arrival order.
     */
    fun ranking(state: GameState): List<Team> =
        Team.entries.sortedByDescending { state.finishedPieceCount(it) }
}
