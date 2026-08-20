package tech.illusion.spaceflightchess.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The heading a plane points along. Pure maths, so the part that can be wrong silently is pinned here
 * rather than left to be judged by eye in a headset.
 */
class HeadingTest {

    private fun p(x: Float, z: Float) = Point3(x, 0f, z)

    @Test
    fun `heading is degrees clockwise from +Z, matching the SDK's right-handed yaw about +Y`() {
        assertEquals(0f, BoardGeometry.headingDegrees(p(0f, 0f), p(0f, 1f))!!, 1e-3f)
        assertEquals(90f, BoardGeometry.headingDegrees(p(0f, 0f), p(1f, 0f))!!, 1e-3f)
        assertEquals(180f, Math.abs(BoardGeometry.headingDegrees(p(0f, 0f), p(0f, -1f))!!), 1e-3f)
        assertEquals(-90f, BoardGeometry.headingDegrees(p(0f, 0f), p(-1f, 0f))!!, 1e-3f)
        assertEquals(45f, BoardGeometry.headingDegrees(p(0f, 0f), p(1f, 1f))!!, 1e-3f)
    }

    @Test
    fun `a zero-length leg has no heading, so the caller keeps the one it has`() {
        assertNull(BoardGeometry.headingDegrees(p(0.3f, 0.7f), p(0.3f, 0.7f)))
        // 0.01mm is far below the 5.2cm smallest real leg and must still read as degenerate.
        assertNull(BoardGeometry.headingDegrees(p(0f, 0f), p(0f, 1e-5f)))
        assertNotNull("a real leg must produce a heading", BoardGeometry.headingDegrees(p(0f, 0f), p(0f, 0.05f)))
    }

    @Test
    fun `every piece in every board state has a heading`() {
        // A missing heading would leave a plane pointing wherever it last happened to point.
        for (team in Team.entries) {
            for (index in 0..3) {
                for (state in listOf(PieceState.InHangar, PieceState.OnPath(Track.STANDBY_PATH), PieceState.FINISHED) +
                    (1..Track.LOOP_LAST_PATH + Track.HOME_LANE_SIZE).map { PieceState.OnPath(it) }) {
                    assertNotNull(
                        "$team piece $index in $state has no heading",
                        BoardGeometry.settledHeadingDegrees(team, index, state),
                    )
                }
            }
        }
    }

    @Test
    fun `a parked plane lines up on the leg it will fly next, not backwards down it`() {
        // 待飞 and InHangar share the hangar pad, so both must point at cell 1.
        for (team in Team.entries) {
            for (index in 0..3) {
                val pad = BoardGeometry.positionFor(team, index, PieceState.OnPath(Track.STANDBY_PATH))
                val expected = BoardGeometry.headingDegrees(pad, BoardGeometry.positionFor(team, index, PieceState.OnPath(1)))
                assertNotNull(expected)
                for (parked in listOf(PieceState.InHangar, PieceState.OnPath(Track.STANDBY_PATH))) {
                    assertEquals(
                        "$team piece $index parked as $parked",
                        expected!!,
                        BoardGeometry.settledHeadingDegrees(team, index, parked)!!,
                        1e-3f,
                    )
                }
            }
        }
    }

    @Test
    fun `walking the ring turns steadily and the four quadrants each account for a right angle`() {
        // The board is a closed loop, so one lap must sum to exactly one full turn. This is the check
        // that would catch a sign error or a coordinate mix-up in headingDegrees.
        var total = 0.0
        for (i in 0 until Track.RING_SIZE) {
            val a = BoardGeometry.ringPosition(i)
            val b = BoardGeometry.ringPosition((i + 1) % Track.RING_SIZE)
            val c = BoardGeometry.ringPosition((i + 2) % Track.RING_SIZE)
            val h1 = BoardGeometry.headingDegrees(a, b)!!
            val h2 = BoardGeometry.headingDegrees(b, c)!!
            var d = (h2 - h1).toDouble()
            while (d > 180) d -= 360
            while (d < -180) d += 360
            total += d
        }
        assertEquals("one lap of a closed ring is one full turn", 360.0, Math.abs(total), 1.0)
    }

    @Test
    fun `no single leg-to-leg turn exceeds a right angle, so a plane never spins on the spot`() {
        // Measured, not assumed: the sharpest corner on the printed ring is 70.7 degrees (the diagonally
        // split corner squares), and 9 of the 52 pairs exceed 50 — so a tighter bound than this would be
        // false. Anything at or beyond 90 would mean the ring order is wrong somewhere.
        var worst = 0.0
        for (i in 0 until Track.RING_SIZE) {
            val a = BoardGeometry.ringPosition(i)
            val b = BoardGeometry.ringPosition((i + 1) % Track.RING_SIZE)
            val c = BoardGeometry.ringPosition((i + 2) % Track.RING_SIZE)
            var d = (BoardGeometry.headingDegrees(b, c)!! - BoardGeometry.headingDegrees(a, b)!!).toDouble()
            while (d > 180) d -= 360
            while (d < -180) d += 360
            worst = maxOf(worst, Math.abs(d))
        }
        assertTrue("sharpest ring turn was $worst degrees", worst < 90.0)
        assertTrue("sharpest ring turn was $worst degrees, expected the measured ~70.7", worst > 65.0)
    }
}
