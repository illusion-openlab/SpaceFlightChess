package tech.illusion.spaceflightchess.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cadence contract: 「从棋盘上移动时按照每隔1秒的动画时长进行移动」. The renderer spends one beat per
 * waypoint, so "no teleporting" is exactly "one waypoint per printed cell" — asserted here rather than
 * left to a screenshot, because a skipped cell is invisible at speed and the earlier 28-vs-52-cell bug
 * was this same class of mistake.
 */
class MovementWaypointsTest {

    private fun stateWith(vararg pieces: Triple<Team, Int, PieceState>): GameState =
        pieces.fold(GameState.newGame()) { acc, (t, i, p) -> acc.withPiece(t, i, p) }

    @Test
    fun `dice movement is one waypoint per printed cell, in order`() {
        val move = Move(Team.RED, 0, PieceState.OnPath(3), PieceState.OnPath(7))
        val after = stateWith(Team.RED to 0 via PieceState.OnPath(7))
        assertEquals(
            listOf(4, 5, 6, 7).map { PieceState.OnPath(it) },
            movementWaypoints(move, after),
        )
    }

    @Test
    fun `every walked step is an adjacent printed cell for every team`() {
        // The invariant that a skipped cell would break: consecutive waypoints are neighbours on the ring.
        for (team in Team.entries) {
            for (from in 1..(Track.LOOP_LAST_PATH - 6)) {
                val to = from + 6
                val move = Move(team, 0, PieceState.OnPath(from), PieceState.OnPath(to))
                val walked = movementWaypoints(move, stateWith(team to 0 via PieceState.OnPath(to)))
                assertEquals("$team $from->$to must be 6 steps", 6, walked.size)
                walked.map { (it as PieceState.OnPath).value }.zipWithNext { a, b ->
                    assertEquals(
                        "$team step $a->$b must be one printed cell",
                        (Track.ringIndex(team, a) + 1) % Track.RING_SIZE,
                        Track.ringIndex(team, b),
                    )
                }
            }
        }
    }

    @Test
    fun `leaving the hangar into 待飞 is a single hop, and taking off from 待飞 walks the cells`() {
        val launch = Move(Team.RED, 0, PieceState.InHangar, PieceState.OnPath(Track.STANDBY_PATH))
        assertEquals(
            listOf(PieceState.OnPath(Track.STANDBY_PATH)),
            movementWaypoints(launch, stateWith(Team.RED to 0 via PieceState.OnPath(Track.STANDBY_PATH))),
        )
        // 待飞 is a hangar pad, not a cell, so rolling 3 out of it walks cells 1, 2, 3.
        val takeoff = Move(Team.RED, 0, PieceState.OnPath(Track.STANDBY_PATH), PieceState.OnPath(3))
        assertEquals(
            listOf(1, 2, 3).map { PieceState.OnPath(it) },
            movementWaypoints(takeoff, stateWith(Team.RED to 0 via PieceState.OnPath(3))),
        )
    }

    @Test
    fun `a colour jump is one leap appended after the walk, not four more walked cells`() {
        // Land on an own-colour cell so resolve() carries the piece on; the jump is printed as a jump.
        val landing = 6
        assertTrue("path $landing must be an own-colour cell", Track.isOwnColourCell(landing))
        val move = Move(Team.RED, 0, PieceState.OnPath(3), PieceState.OnPath(landing))
        val (after, outcome) = GameEngine.resolve(stateWith(Team.RED to 0 via PieceState.OnPath(3)), move)
        assertTrue("this fixture must actually hop", outcome.colourHopped)

        val walked = movementWaypoints(move, after)
        assertEquals(listOf(4, 5, 6).map { PieceState.OnPath(it) }, walked.dropLast(1))
        assertEquals("the jump is a single waypoint", after.pieces(Team.RED)[0], walked.last())
    }

    @Test
    fun `the flight lane is one leap, not twelve walked cells`() {
        val move = Move(Team.RED, 0, PieceState.OnPath(Track.FLIGHT_PATH - 2), PieceState.OnPath(Track.FLIGHT_PATH))
        val before = stateWith(Team.RED to 0 via PieceState.OnPath(Track.FLIGHT_PATH - 2))
        val (after, _) = GameEngine.resolve(before, move)
        val walked = movementWaypoints(move, after)

        // Two walked cells for the roll of 2, then exactly one waypoint for wherever the carry ended.
        assertEquals(
            listOf(Track.FLIGHT_PATH - 1, Track.FLIGHT_PATH).map { PieceState.OnPath(it) },
            walked.dropLast(1),
        )
        assertEquals(after.pieces(Team.RED)[0], walked.last())
        assertEquals("a carry must never be walked cell by cell", 3, walked.size)
    }

    @Test
    fun `a bounce off a passed-over stack walks forward to the wall, then backwards to the landing cell`() {
        // 「叠棋阻挡普通移动，超过的点数本回合飞到叠棋位置处开始进行倒飞」 — the animation must actually visit
        // the wall (the blocking stack's own cell) before reversing, not glide straight to the landing cell.
        // Move.bounceWall is what makes the forward leg reconstructible (from/to alone cannot).
        val move = Move(Team.RED, 0, PieceState.OnPath(5), PieceState.OnPath(7), bounceWall = 9)
        val walked = movementWaypoints(move, stateWith(Team.RED to 0 via PieceState.OnPath(7)))
        assertEquals(listOf(6, 7, 8, 9, 8, 7).map { PieceState.OnPath(it) }, walked)
    }

    @Test
    fun `a bounce off a bigger opposing stack's landing cell stops one cell short of it`() {
        // The landing-cell wall sits one cell *before* the blocked stack (the plane can never enter
        // that cell at all), unlike a merely-passed-over stack whose wall is the stack's own cell.
        val move = Move(Team.RED, 0, PieceState.OnPath(5), PieceState.OnPath(6), bounceWall = 7)
        val walked = movementWaypoints(move, stateWith(Team.RED to 0 via PieceState.OnPath(6)))
        assertEquals(listOf(6, 7, 6).map { PieceState.OnPath(it) }, walked)
    }

    @Test
    fun `overshooting the finish walks forward to the finish, then backwards into the home lane`() {
        val from = Track.FINISH_PATH - 2
        val to = Track.FINISH_PATH - 3
        val move = Move(Team.RED, 0, PieceState.OnPath(from), PieceState.OnPath(to), bounceWall = Track.FINISH_PATH)
        val walked = movementWaypoints(move, stateWith(Team.RED to 0 via PieceState.OnPath(to)))
        assertEquals(
            listOf(from + 1, Track.FINISH_PATH, from + 1, from, to).map { PieceState.OnPath(it) },
            walked,
        )
    }

    @Test
    fun `a triple-six penalty flies home in one hop`() {
        val move = Move(Team.RED, 0, PieceState.OnPath(12), PieceState.InHangar)
        val walked = movementWaypoints(move, stateWith(Team.RED to 0 via PieceState.InHangar))
        assertEquals(listOf(PieceState.InHangar), walked)
    }

    @Test
    fun `a whole 僚机 stack shares one walk`() {
        val move = Move(Team.RED, 0, PieceState.OnPath(5), PieceState.OnPath(8), groupPieceIndices = listOf(0, 1))
        val after = stateWith(
            Team.RED to 0 via PieceState.OnPath(8),
            Team.RED to 1 via PieceState.OnPath(8),
        )
        assertEquals(listOf(6, 7, 8).map { PieceState.OnPath(it) }, movementWaypoints(move, after))
    }

    private infix fun Pair<Team, Int>.via(state: PieceState) = Triple(first, second, state)
}
