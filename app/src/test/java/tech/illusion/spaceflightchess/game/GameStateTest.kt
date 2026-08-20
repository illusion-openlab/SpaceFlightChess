package tech.illusion.spaceflightchess.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameStateTest {

    @Test
    fun `new game starts everyone in the hangar`() {
        val state = GameState.newGame()
        assertEquals(Team.RED, state.currentTeam)
        for (team in Team.entries) {
            assertEquals(List(4) { PieceState.InHangar }, state.pieces(team))
        }
    }

    @Test
    fun `withPiece only changes the targeted slot`() {
        val state = GameState.newGame().withPiece(Team.RED, 2, PieceState.OnPath(5))
        assertEquals(PieceState.InHangar, state.pieces(Team.RED)[0])
        assertEquals(PieceState.InHangar, state.pieces(Team.RED)[1])
        assertEquals(PieceState.OnPath(5), state.pieces(Team.RED)[2])
        assertEquals(PieceState.InHangar, state.pieces(Team.RED)[3])
        // other teams untouched
        assertEquals(List(4) { PieceState.InHangar }, state.pieces(Team.YELLOW))
    }

    @Test
    fun `teammatesSharing finds the other piece on the same path, not the hangar`() {
        var state = GameState.newGame()
        state = state.withPiece(Team.RED, 0, PieceState.OnPath(9))
        state = state.withPiece(Team.RED, 1, PieceState.OnPath(9))
        state = state.withPiece(Team.RED, 2, PieceState.OnPath(3))

        assertEquals(listOf(1), state.teammatesSharing(Team.RED, 0))
        assertEquals(listOf(0), state.teammatesSharing(Team.RED, 1))
        assertEquals(emptyList<Int>(), state.teammatesSharing(Team.RED, 2))
        // two pieces both InHangar must not count as "sharing" a cell
        assertEquals(emptyList<Int>(), state.teammatesSharing(Team.RED, 2))
    }

    @Test
    fun `opponentsAt only matches same absolute cell across teams, never in a private home lane`() {
        var state = GameState.newGame()
        state = state.withPiece(Team.RED, 0, PieceState.OnPath(5))
        state = state.withPiece(Team.YELLOW, 0, PieceState.OnPath(pathSharingCellWith(Team.YELLOW, Team.RED, 5)))

        val opponents = state.opponentsAt(Team.RED, 0)
        assertEquals(listOf(Team.YELLOW to 0), opponents)

        // Move yellow's piece into its own home lane at a path that maps to the same *hangar-relative*
        // number — must not be mistaken for sharing red's cell, since home lanes are private.
        state = state.withPiece(Team.YELLOW, 0, PieceState.OnPath(Track.LOOP_LAST_PATH + 1))
        assertEquals(emptyList<Pair<Team, Int>>(), state.opponentsAt(Team.RED, 0))
    }

    @Test
    fun `teammatesSharing ignores positions that are not a shared cell`() {
        // 待飞 planes all sit at path 0 but on four different hangar pads, and FINISHED planes all sit at
        // FINISH_PATH; neither is a cell two planes can share, so neither may be reported as a 僚机.
        var state = GameState.newGame()
        state = state.withPiece(Team.RED, 0, PieceState.OnPath(Track.STANDBY_PATH))
        state = state.withPiece(Team.RED, 1, PieceState.OnPath(Track.STANDBY_PATH))
        assertEquals(emptyList<Int>(), state.teammatesSharing(Team.RED, 0))

        state = state.withPiece(Team.RED, 2, PieceState.FINISHED)
        state = state.withPiece(Team.RED, 3, PieceState.FINISHED)
        assertEquals(emptyList<Int>(), state.teammatesSharing(Team.RED, 2))

        // A real shared cell, on the loop and in the home lane, still reports.
        var onBoard = GameState.newGame()
        onBoard = onBoard.withPiece(Team.RED, 0, PieceState.OnPath(7))
        onBoard = onBoard.withPiece(Team.RED, 1, PieceState.OnPath(7))
        assertEquals(listOf(1), onBoard.teammatesSharing(Team.RED, 0))

        var inLane = GameState.newGame()
        inLane = inLane.withPiece(Team.RED, 0, PieceState.OnPath(Track.LOOP_LAST_PATH + 2))
        inLane = inLane.withPiece(Team.RED, 1, PieceState.OnPath(Track.LOOP_LAST_PATH + 2))
        assertEquals(listOf(1), inLane.teammatesSharing(Team.RED, 0))
    }

    @Test
    fun `finishedPieceCount and isTeamFinished`() {
        var state = GameState.newGame()
        assertEquals(0, state.finishedPieceCount(Team.BLUE))
        assertTrue(!state.isTeamFinished(Team.BLUE))

        for (i in 0 until 4) {
            state = state.withPiece(Team.BLUE, i, PieceState.FINISHED)
        }
        assertEquals(4, state.finishedPieceCount(Team.BLUE))
        assertTrue(state.isTeamFinished(Team.BLUE))
    }
}
