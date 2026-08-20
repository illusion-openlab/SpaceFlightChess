package tech.illusion.spaceflightchess.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackTest {

    @Test
    fun `cell 1 is the four measured ring indices, 13 apart`() {
        assertEquals(5, Track.cell1RingIndex(Team.RED))
        assertEquals(18, Track.cell1RingIndex(Team.YELLOW))
        assertEquals(31, Track.cell1RingIndex(Team.BLUE))
        assertEquals(44, Track.cell1RingIndex(Team.GREEN))
        // Evenly spaced around the 52-cell ring — four teams, 13 cells each.
        val spacing = Team.entries.map { Track.cell1RingIndex(it) }.sorted()
            .zipWithNext { a, b -> b - a }
        assertEquals(listOf(13, 13, 13), spacing)
    }

    @Test
    fun `待飞 is off the board entirely and rolling N from it lands on cell N`() {
        // The rule the player asked for: no standby cell on the board. A plane in 待飞 sits on its own
        // hangar pad, so it has no ring index at all and cannot be captured; the next roll of N puts
        // it on cell N exactly as the player counts cells.
        assertTrue(Track.isInStandby(Track.STANDBY_PATH))
        assertFalse("待飞 must not be a shared-loop cell", Track.isOnSharedLoop(Track.STANDBY_PATH))
        for (team in Team.entries) {
            assertNull(
                "$team in 待飞 must have no shared cell",
                Track.ringIndexOrNull(team, PieceState.OnPath(Track.STANDBY_PATH)),
            )
            assertEquals("$team cell 1", Track.cell1RingIndex(team), Track.ringIndex(team, 1))
            // Rolling 3 out of 待飞 must land on cell 3, i.e. two printed cells past cell 1.
            assertEquals(
                "$team rolling 3 from 待飞 must land on cell 3",
                (Track.cell1RingIndex(team) + 2) % Track.RING_SIZE,
                Track.ringIndex(team, Track.STANDBY_PATH + 3),
            )
        }
    }

    @Test
    fun `consecutive path values are always adjacent printed cells`() {
        // The "累加之前的6格" bug in its most direct form: one path step must never advance more than
        // one printed cell, at any point in the lap, for any team.
        for (team in Team.entries) {
            for (path in 1 until Track.LOOP_LAST_PATH) {
                val here = Track.ringIndex(team, path)
                val next = Track.ringIndex(team, path + 1)
                assertEquals(
                    "$team path $path -> ${path + 1} must step exactly one printed cell",
                    (here + 1) % Track.RING_SIZE,
                    next,
                )
            }
        }
    }

    @Test
    fun `a lap visits distinct printed cells and turns in at the measured home mouth`() {
        for (team in Team.entries) {
            val visited = (1..Track.LOOP_LAST_PATH).map { Track.ringIndex(team, it) }
            assertEquals("$team must not revisit a printed cell in one lap", visited.size, visited.toSet().size)
            assertEquals("$team home mouth", Track.ringIndex(team, Track.LOOP_LAST_PATH), Track.homeEntryRingIndex(team))
        }
        // Measured off the artwork: each team's home-lane mouth is its own colour at the middle of
        // the edge facing its home arm.
        assertEquals(2, Track.homeEntryRingIndex(Team.RED))
        assertEquals(15, Track.homeEntryRingIndex(Team.YELLOW))
        assertEquals(28, Track.homeEntryRingIndex(Team.BLUE))
        assertEquals(41, Track.homeEntryRingIndex(Team.GREEN))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `ring index is undefined once in the home lane`() {
        Track.ringIndex(Team.RED, Track.LOOP_LAST_PATH + 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `ring index is undefined in 待飞`() {
        Track.ringIndex(Team.RED, Track.STANDBY_PATH)
    }

    @Test
    fun `path range boundaries`() {
        assertTrue(Track.isInStandby(Track.STANDBY_PATH))
        assertFalse(Track.isInStandby(1))

        assertFalse(Track.isOnSharedLoop(Track.STANDBY_PATH))
        assertTrue(Track.isOnSharedLoop(1))
        assertTrue(Track.isOnSharedLoop(Track.LOOP_LAST_PATH))
        assertFalse(Track.isOnSharedLoop(Track.LOOP_LAST_PATH + 1))

        assertTrue(Track.isInHomeLane(Track.LOOP_LAST_PATH + 1))
        assertTrue(Track.isInHomeLane(Track.FINISH_PATH - 1))
        assertFalse(Track.isInHomeLane(Track.FINISH_PATH))
        assertFalse(Track.isInHomeLane(Track.LOOP_LAST_PATH))

        assertFalse(Track.isFinished(Track.FINISH_PATH - 1))
        assertTrue(Track.isFinished(Track.FINISH_PATH))
    }

    @Test
    fun `the home lane plus the finish is exactly HOME_LANE_SIZE cells, and the finish is the last one`() {
        // isInHomeLane excludes the finish itself (it's a distinct game-state category), so the
        // still-travelling lane is one cell short of HOME_LANE_SIZE; the finish makes up the 6th.
        val stillTravelling = (Track.LOOP_LAST_PATH + 1) until Track.FINISH_PATH
        assertEquals(Track.HOME_LANE_SIZE - 1, stillTravelling.count())
        assertEquals(56, Track.FINISH_PATH) // 待飞(0) + 50 loop + 6 lane, the 6th lane cell being home
    }

    @Test
    fun `every team's own flight square is the same path offset and lands on its own colour`() {
        // Measured: all four drawn lanes start on their owner's own-colour cell at path 18 and end 12
        // printed cells clockwise, still on the loop. Same offsets for every team — the strongest
        // independent check that the ring numbering is right.
        assertEquals(18, Track.FLIGHT_PATH)
        assertEquals(12, Track.FLIGHT_ADVANCE)
        assertTrue(Track.isFlightTrigger(Track.FLIGHT_PATH))
        assertEquals(30, Track.applyFlight(Track.FLIGHT_PATH))
        assertTrue(Track.isOnSharedLoop(Track.applyFlight(Track.FLIGHT_PATH)))

        // The four printed lane-start cells, one per colour.
        val starts = Team.entries.associateWith { Track.ringIndex(it, Track.FLIGHT_PATH) }
        assertEquals(22, starts.getValue(Team.RED))
        assertEquals(35, starts.getValue(Team.YELLOW))
        assertEquals(48, starts.getValue(Team.BLUE))
        assertEquals(9, starts.getValue(Team.GREEN))
        // Lanes are colour-coded, so a team crossing another team's flight cell must not fly.
        val redOverGreensFlightCell = (9 - Track.cell1RingIndex(Team.RED) + Track.RING_SIZE) % Track.RING_SIZE + 1
        assertFalse("RED crossing GREEN's flight cell must not trigger", Track.isFlightTrigger(redOverGreensFlightCell))
    }

    @Test
    fun `exactly one flight trigger per lap, and it is never a regression`() {
        for (team in Team.entries) {
            val triggers = (1..Track.LOOP_LAST_PATH).filter { Track.isFlightTrigger(it) }
            assertEquals("team $team should have exactly one own flight square per lap", 1, triggers.size)
            for (path in triggers) {
                assertTrue("team=$team path=$path must advance", Track.applyFlight(path) > path)
            }
        }
    }

    @Test
    fun `flight clamps at the finish rather than overshooting`() {
        assertEquals(Track.FINISH_PATH, Track.applyFlight(Track.FINISH_PATH - 1))
        assertEquals(Track.FINISH_PATH, Track.applyFlight(Track.FINISH_PATH))
    }

    @Test
    fun `ringIndexOrNull is null off the shared loop`() {
        assertNull(Track.ringIndexOrNull(Team.RED, PieceState.InHangar))
        assertNull(Track.ringIndexOrNull(Team.RED, PieceState.OnPath(Track.LOOP_LAST_PATH + 1)))
        assertNull(Track.ringIndexOrNull(Team.RED, PieceState.FINISHED))
        assertNull("待飞 has no shared cell", Track.ringIndexOrNull(Team.RED, PieceState.OnPath(Track.STANDBY_PATH)))
        assertEquals(Track.ringIndex(Team.RED, 5), Track.ringIndexOrNull(Team.RED, PieceState.OnPath(5)))
    }

    @Test
    fun `two teams meet on the same printed cell at the path offsets their spacing implies`() {
        // RED's cell 1 is ring 5; GREEN reaches ring 5 at path (5-44+52)+1=14. Capture logic depends
        // on exactly this kind of cross-team coincidence resolving to one shared ring index.
        assertEquals(Track.ringIndex(Team.RED, 1), Track.ringIndex(Team.GREEN, 14))
    }
}
