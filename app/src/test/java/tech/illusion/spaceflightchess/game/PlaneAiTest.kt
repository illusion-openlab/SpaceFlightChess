package tech.illusion.spaceflightchess.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaneAiTest {

    @Test
    fun `prefers a capturing move over a merely-advancing one`() {
        var state = GameState.newGame()
        // Yellow lone piece sitting exactly where red's path 5 lands.
        state = state.withPiece(Team.YELLOW, 0, PieceState.OnPath(pathSharingCellWith(Team.YELLOW, Team.RED, 5)))
        state = state.withPiece(Team.RED, 0, PieceState.OnPath(4))
        state = state.withPiece(Team.RED, 1, PieceState.OnPath(0))

        val captureMove = Move(Team.RED, 0, PieceState.OnPath(4), PieceState.OnPath(5))
        val plainMove = Move(Team.RED, 1, PieceState.OnPath(0), PieceState.OnPath(3))

        assertEquals(captureMove, PlaneAi.chooseMove(state, listOf(plainMove, captureMove)))
    }

    @Test
    fun `prefers escaping a capture risk over advancing a piece that was never at risk`() {
        var state = GameState.newGame()
        val yellowPath = 25
        val threatened = reachableRingCells(Team.YELLOW, yellowPath)
        // Put red's piece 0 on a cell yellow really can reach next turn, and derive the numbers from
        // the topology so this keeps testing the intent if the ring is ever re-measured.
        val atRiskPath = (1..Track.LOOP_LAST_PATH).first { Track.ringIndex(Team.RED, it) in threatened }
        val escapePath = (atRiskPath + 1..Track.LOOP_LAST_PATH).first { Track.ringIndex(Team.RED, it) !in threatened }
        state = state.withPiece(Team.YELLOW, 0, PieceState.OnPath(yellowPath))
        state = state.withPiece(Team.RED, 0, PieceState.OnPath(atRiskPath))
        state = state.withPiece(Team.RED, 1, PieceState.OnPath(20)) // never at risk from this yellow piece
        assertTrue("piece 1 must not be under threat for this test to mean anything", Track.ringIndex(Team.RED, 20) !in threatened)

        val escapeMove = Move(Team.RED, 0, PieceState.OnPath(atRiskPath), PieceState.OnPath(escapePath))
        val higherPathButIrrelevantMove = Move(Team.RED, 1, PieceState.OnPath(20), PieceState.OnPath(22))

        assertEquals(escapeMove, PlaneAi.chooseMove(state, listOf(higherPathButIrrelevantMove, escapeMove)))
    }

    @Test
    fun `a move that only shuffles within the same threat's reach gets no escape credit`() {
        var state = GameState.newGame()
        val yellowPath = 25
        val threatened = reachableRingCells(Team.YELLOW, yellowPath)
        val atRiskPath = (1..Track.LOOP_LAST_PATH).first { Track.ringIndex(Team.RED, it) in threatened }
        // A destination that is ALSO inside the same threat's reach, so it earns no escape credit.
        val stillAtRiskPath = (atRiskPath + 1..Track.LOOP_LAST_PATH).first { Track.ringIndex(Team.RED, it) in threatened }
        // The comparison piece must be BOTH unthreatened and further along than the at-risk one.
        //
        // NOTE (2026-08-18, the weighted evaluator): this assertion still holds, but no longer for the
        // reason originally written here. It used to say the decision "falls through to the advance-whoever-
        // leads tiebreak". It does not any more — the two moves get genuinely different scores, and the
        // safe piece wins on merit rather than on a tiebreak. The pure-tiebreak case is covered by
        // `otherwise advances whichever piece is furthest along`, which is an exact 4.0-vs-4.0 score tie.
        val safePath = (stillAtRiskPath + 1 until Track.LOOP_LAST_PATH).first { Track.ringIndex(Team.RED, it) !in threatened }
        state = state.withPiece(Team.YELLOW, 0, PieceState.OnPath(yellowPath))
        state = state.withPiece(Team.RED, 0, PieceState.OnPath(atRiskPath))
        state = state.withPiece(Team.RED, 1, PieceState.OnPath(safePath))
        assertTrue("the safe piece must lead for the tiebreak to be the deciding factor", safePath > atRiskPath)

        // Piece 0 shuffling within the same threat's reach is not a real escape, so the untouched,
        // never-at-risk piece 1 should win the tiebreak instead.
        val staysAtRisk = Move(Team.RED, 0, PieceState.OnPath(atRiskPath), PieceState.OnPath(stillAtRiskPath))
        val unrelatedAdvance = Move(Team.RED, 1, PieceState.OnPath(safePath), PieceState.OnPath(safePath + 1))

        assertEquals(unrelatedAdvance, PlaneAi.chooseMove(state, listOf(staysAtRisk, unrelatedAdvance)))
    }

    @Test
    fun `prefers launching a new plane over merely advancing when neither captures nor escapes risk`() {
        val state = GameState.newGame().withPiece(Team.RED, 1, PieceState.OnPath(10))
        val launch = Move(Team.RED, 0, PieceState.InHangar, PieceState.OnPath(0))
        val advance = Move(Team.RED, 1, PieceState.OnPath(10), PieceState.OnPath(16))

        assertEquals(launch, PlaneAi.chooseMove(state, listOf(advance, launch)))
    }

    @Test
    fun `otherwise advances whichever piece is furthest along`() {
        val state = GameState.newGame()
            .withPiece(Team.RED, 0, PieceState.OnPath(5))
            .withPiece(Team.RED, 1, PieceState.OnPath(20))

        val advanceLaggard = Move(Team.RED, 0, PieceState.OnPath(5), PieceState.OnPath(9))
        val advanceLeader = Move(Team.RED, 1, PieceState.OnPath(20), PieceState.OnPath(24))

        assertEquals(advanceLeader, PlaneAi.chooseMove(state, listOf(advanceLaggard, advanceLeader)))
    }

    @Test
    fun `a single legal move is always chosen, even if it is bad`() {
        val state = GameState.newGame()
        val onlyMove = Move(Team.RED, 0, PieceState.InHangar, PieceState.OnPath(0))
        assertEquals(onlyMove, PlaneAi.chooseMove(state, listOf(onlyMove)))
    }
}
