package tech.illusion.spaceflightchess.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validates the measuring instrument before any conclusion is drawn with it. A harness that silently
 * stalls, or that quietly favours a seat, would make the AI comparison meaningless in a way that looks
 * like a real result.
 */
class SelfPlayHarnessTest {

    @Test
    fun `a mirror match is exactly balanced, which pins the seat-swap logic`() {
        // Both sides run the same algorithm, so seed S plays out identically whether or not the seats are
        // swapped: the same team wins both times, and it is the challenger in exactly one of them. Any
        // deviation from 50% means the swap is wrong or the strategies are being consulted asymmetrically.
        val r = SelfPlay.match(LegacyPlaneAi::chooseMove, LegacyPlaneAi::chooseMove, games = 40)
        println("HARNESS mirror: $r")
        assertEquals("mirror match must split exactly", r.challengerWins, r.baselineWins)
        assertEquals("every game must be decided the same way both times", 0, r.draws % 2)
    }

    @Test
    fun `games actually finish, so a win rate means something`() {
        var capped = 0
        var noWinner = 0
        val rollCounts = mutableListOf<Int>()
        for (seed in 0 until 40) {
            val r = SelfPlay.play(seed, Team.entries.associateWith { LegacyPlaneAi::chooseMove })
            if (r.hitRollCap) capped++
            if (r.winner == null) noWinner++
            rollCounts += r.rolls
        }
        println("HARNESS rolls: min=${rollCounts.min()} median=${rollCounts.sorted()[rollCounts.size / 2]} max=${rollCounts.max()} capped=$capped noWinner=$noWinner")
        assertEquals("no game should hit the roll cap", 0, capped)
        assertEquals("every game should produce a winner", 0, noWinner)
    }

    @Test
    fun `the challenger is measurably stronger than the AI it replaced`() {
        // The claim "the AI got smarter", as a number. Everything here is seeded and deterministic, so
        // this is a fixed assertion, not a flaky statistical one.
        //
        // Measured over the wider ranges used during tuning: 86.0% on seeds 0..149 (which the weights were
        // swept against) and 86.3% on the held-out 500..649 — the two agreeing is what rules out
        // seed-fitting. This test uses a shorter held-out slice to keep the suite quick, and asserts a
        // floor well under the measured value so an honest small regression is still visible as a drop in
        // the printed rate rather than a red build for noise.
        val r = SelfPlay.match(PlaneAi::chooseMove, LegacyPlaneAi::chooseMove, 500..599)
        println("STRENGTH new vs legacy: $r")
        assertTrue("new AI should beat the one it replaced decisively, got $r", r.challengerWinRate > 0.75)
        assertTrue("new AI should also get more planes home, got $r", r.challengerFinishedPieces > r.baselineFinishedPieces)
    }

    @Test
    fun `move choice is deterministic and independent of the order legalMoves arrives in`() {
        // The renderer and the tests both assume a fixed answer; and a score-based chooser is easy to write
        // so that argument order leaks into the result through a max-by tie.
        var state = GameState.newGame()
        state = state.withPiece(Team.RED, 0, PieceState.OnPath(9))
        state = state.withPiece(Team.RED, 1, PieceState.OnPath(24))
        state = state.withPiece(Team.YELLOW, 0, PieceState.OnPath(30))
        val moves = GameEngine.legalMoves(state, Team.RED, 4)
        assertTrue("fixture must offer a choice", moves.size > 1)
        val first = PlaneAi.chooseMove(state, moves)
        repeat(20) { assertEquals("repeated calls must agree", first, PlaneAi.chooseMove(state, moves)) }
        assertEquals("reversed input must not change the answer", first, PlaneAi.chooseMove(state, moves.reversed()))
    }
}
