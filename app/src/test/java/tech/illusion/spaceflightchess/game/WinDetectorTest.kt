package tech.illusion.spaceflightchess.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WinDetectorTest {

    private fun finishTeam(state: GameState, team: Team): GameState =
        (0 until GameState.PIECES_PER_TEAM).fold(state) { acc, i -> acc.withPiece(team, i, PieceState.FINISHED) }

    @Test
    fun `no winner at the start`() {
        assertNull(WinDetector.winner(GameState.newGame()))
    }

    @Test
    fun `winner is whichever team has all four pieces home`() {
        val state = finishTeam(GameState.newGame(), Team.BLUE)
        assertEquals(Team.BLUE, WinDetector.winner(state))
    }

    @Test
    fun `ranking sorts by finished piece count, winner first`() {
        var state = GameState.newGame()
        state = finishTeam(state, Team.GREEN) // 4 finished
        state = state.withPiece(Team.YELLOW, 0, PieceState.FINISHED)
            .withPiece(Team.YELLOW, 1, PieceState.FINISHED) // 2 finished
        state = state.withPiece(Team.BLUE, 0, PieceState.FINISHED) // 1 finished
        // RED stays at 0 finished

        val ranking = WinDetector.ranking(state)
        assertEquals(listOf(Team.GREEN, Team.YELLOW, Team.BLUE, Team.RED), ranking)
    }

    @Test
    fun `equal finished counts keep declared Team order`() {
        // Nobody has finished anything - a tie across the board.
        val ranking = WinDetector.ranking(GameState.newGame())
        assertEquals(Team.entries.toList(), ranking)
    }
}
