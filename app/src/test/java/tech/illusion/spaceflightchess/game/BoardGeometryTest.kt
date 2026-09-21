package tech.illusion.spaceflightchess.game

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

class BoardGeometryTest {

    // BoardGeometry is a singleton with mutable seat-rotation state (see configureSeat) — reset it
    // around every test so one test's seat choice can never leak into another's. Yellow is the
    // seat-target team itself (see SEAT_TARGET_STEPS), so configuring yellow's seat is exactly the
    // "no extra rotation" baseline every other test in this file was written against.
    @Before
    @After
    fun resetSeat() {
        BoardGeometry.configureSeat(Team.YELLOW)
    }

    private fun radiusOf(p: Point3): Float = sqrt(p.x * p.x + p.z * p.z)

    private fun angleDegOf(p: Point3): Double = Math.toDegrees(atan2(p.z.toDouble(), p.x.toDouble()))

    private fun distance(a: Point3, b: Point3): Float = hypot(a.x - b.x, a.z - b.z)

    /** The measured artwork scale: 0.95m across 1254px. One printed cell step is ~74-88px. */
    private val metresPerPixel = 0.95f / 1254f

    @Test
    fun `the shared loop advances clockwise as a viewer looking at the board sees it`() {
        // BoardGeometry's local Z axis follows the board artwork's own pixel row (top of the image
        // toward negative Z), the same top-to-bottom "screen" sense the artwork is authored and
        // viewed in — not a bottom-up math-plot convention. In that screen sense, an *increasing*
        // atan2(z, x) angle is clockwise motion as a player actually watching the board sees it.
        for (cell in 0 until Track.RING_SIZE) {
            val from = angleDegOf(BoardGeometry.ringPosition(cell))
            val to = angleDegOf(BoardGeometry.ringPosition((cell + 1) % Track.RING_SIZE))
            var delta = to - from
            while (delta <= 0.0) delta += 360.0
            while (delta > 360.0) delta -= 360.0
            assertTrue(
                "cell $cell -> ${(cell + 1) % Track.RING_SIZE} should be a short clockwise step, was $delta degrees",
                delta in 0.0..180.0,
            )
        }
    }

    @Test
    fun `all 52 ring cells are distinct and inside the board artwork`() {
        val points = (0 until Track.RING_SIZE).map { BoardGeometry.ringPosition(it) }
        assertEquals(Track.RING_SIZE, points.toSet().size)
        // The square board-art plane is 0.95m wide (half-diagonal ~0.67m); every cell must land on
        // it, not off in empty space.
        for (point in points) {
            assertTrue("$point should sit within the board artwork's half-diagonal", radiusOf(point) < 0.68f)
        }
    }

    @Test
    fun `adjacent ring cells are always about one printed cell apart`() {
        // This is the geometric form of the "+6" bug: the old table had one 378px stride per quadrant
        // while every real neighbour step is 67-89px. Anything above ~1.5 cell widths is a skipped
        // cell, whatever the code says its index arithmetic is doing.
        val maxStep = 95f * metresPerPixel
        for (cell in 0 until Track.RING_SIZE) {
            val step = distance(
                BoardGeometry.ringPosition(cell),
                BoardGeometry.ringPosition((cell + 1) % Track.RING_SIZE),
            )
            assertTrue(
                "ring $cell -> ${(cell + 1) % Track.RING_SIZE} step was ${step}m, expected <= ${maxStep}m (one printed cell)",
                step <= maxStep,
            )
        }
    }

    @Test
    fun `待飞 keeps the plane on its own hangar pad, off the board`() {
        // The rule: 待飞 is not a board cell at all. Every piece index must map to its own hangar pad,
        // which is also what makes two 待飞 planes of one team distinguishable (they are on separate
        // pads, so they must not be treated as a 僚机 stack).
        for (team in Team.entries) {
            BoardGeometry.configureSeat(team)
            val pads = (0 until 4).map { BoardGeometry.hangarPosition(team, it) }
            for (index in 0 until 4) {
                val standby = BoardGeometry.positionFor(team, index, PieceState.OnPath(Track.STANDBY_PATH))
                assertEquals("$team piece $index in 待飞 must sit on its own hangar pad", pads[index], standby)
            }
            // And cell 1 must be somewhere else entirely — on the ring.
            val cell1 = BoardGeometry.positionFor(team, 0, PieceState.OnPath(1))
            assertEquals("$team cell 1 must be the measured ring cell", BoardGeometry.ringPosition(Track.cell1RingIndex(team)), cell1)
            assertTrue("$team cell 1 must not coincide with a hangar pad", pads.none { distance(it, cell1) < 1e-4f })
        }
    }

    @Test
    fun `every team's four hangar slots are distinct points`() {
        for (team in Team.entries) {
            val points = (0 until 4).map { BoardGeometry.hangarPosition(team, it) }
            assertEquals(4, points.toSet().size)
        }
    }

    @Test
    fun `home lane walks strictly inward from the ring mouth toward the centre, and the last cell is the finish`() {
        for (team in Team.entries) {
            var previousRadius = radiusOf(BoardGeometry.ringPosition(Track.homeEntryRingIndex(team)))
            for (slot in 0 until Track.HOME_LANE_SIZE) {
                val radius = radiusOf(BoardGeometry.homeLanePosition(team, slot))
                assertTrue(
                    "$team slot $slot should be closer to the centre than the previous step",
                    radius < previousRadius,
                )
                previousRadius = radius
            }
            // The 6th (last, innermost) home-lane slot IS the finish — no separate 7th position.
            assertEquals(
                BoardGeometry.homeLanePosition(team, Track.HOME_LANE_SIZE - 1),
                BoardGeometry.positionFor(team, 0, PieceState.FINISHED),
            )
        }
    }

    @Test
    fun `the home lane mouth is adjacent to its own ring cell`() {
        // If a team's measured lane were paired with the wrong ring mouth, the turn-in would visibly
        // jump across the board. One cell width is the tolerance.
        val maxStep = 130f * metresPerPixel
        for (team in Team.entries) {
            val mouth = BoardGeometry.ringPosition(Track.homeEntryRingIndex(team))
            val firstLaneCell = BoardGeometry.homeLanePosition(team, 0)
            val step = distance(mouth, firstLaneCell)
            assertTrue("$team ring mouth -> first home-lane cell was ${step}m", step <= maxStep)
        }
    }

    @Test
    fun `configureSeat sets the exact board-art yaw BoardRenderer must apply per team`() {
        // Numeric regression for the bug where `BoardStage` stopped calling
        // `BoardRenderer.updateSeatRotation` after the seat-change animation was deleted: the art
        // silently stayed at whatever yaw it last had instead of matching the seated team. YELLOW is
        // 0° (the unrotated seat-target quadrant itself), which is exactly why it was the one team
        // that accidentally looked correct even while the call was missing — the other three are the
        // ones that actually exercise this.
        assertEquals(90f, seatRotationDegreesFor(Team.RED))
        assertEquals(0f, seatRotationDegreesFor(Team.YELLOW))
        assertEquals(270f, seatRotationDegreesFor(Team.BLUE))
        assertEquals(180f, seatRotationDegreesFor(Team.GREEN))
    }

    private fun seatRotationDegreesFor(team: Team): Float {
        BoardGeometry.configureSeat(team)
        return BoardGeometry.seatRotationDegrees
    }

    @Test
    fun `configureSeat rotates the chosen team's hangar to the seat quadrant, and rotates every other team along with it`() {
        for (team in Team.entries) {
            BoardGeometry.configureSeat(team)
            val hangarCenter = (0 until 4).map { BoardGeometry.hangarPosition(team, it) }
                .let { pts -> Point3(pts.sumOf { it.x.toDouble() }.toFloat() / 4, 0f, pts.sumOf { it.z.toDouble() }.toFloat() / 4) }
            assertTrue("$team's hangar should sit in the +X/+Z seat quadrant, was $hangarCenter", hangarCenter.x > 0f && hangarCenter.z > 0f)

            // The other three teams keep their fixed clockwise order relative to the seated team —
            // only the whole board's orientation changes, never who is "next" after whom.
            val order = listOf(Team.GREEN, Team.RED, Team.YELLOW, Team.BLUE)
            val rotated = order.drop(order.indexOf(team)) + order.take(order.indexOf(team))
            for (i in rotated.indices) {
                val a = rotated[i]
                val b = rotated[(i + 1) % rotated.size]
                val angleA = angleDegOf(BoardGeometry.hangarPosition(a, 0))
                val angleB = angleDegOf(BoardGeometry.hangarPosition(b, 0))
                var delta = angleB - angleA
                while (delta <= 0.0) delta += 360.0
                assertTrue("$a -> $b should still be a clockwise step after seating $team", delta < 180.0)
            }
        }
    }

    @Test
    fun `seat rotation keeps the board rigid - every pairwise distance is preserved`() {
        // The seat rotation is a rigid rotation of the whole board; if it ever became a per-team
        // transform again (as it was when the launch cell had its own special case), relative
        // distances would change and pieces would drift off the printed cells.
        BoardGeometry.configureSeat(Team.YELLOW)
        val baseline = (0 until Track.RING_SIZE).map { BoardGeometry.ringPosition(it) }
        for (team in Team.entries) {
            BoardGeometry.configureSeat(team)
            val rotated = (0 until Track.RING_SIZE).map { BoardGeometry.ringPosition(it) }
            for (i in 0 until Track.RING_SIZE) {
                val a = distance(baseline[i], baseline[(i + 7) % Track.RING_SIZE])
                val b = distance(rotated[i], rotated[(i + 7) % Track.RING_SIZE])
                assertEquals("seating $team changed the board's own geometry at cell $i", a, b, 1e-4f)
            }
        }
    }

    @Test
    fun `each team finishes at its own last home-lane cell, not all on top of each other`() {
        BoardGeometry.configureSeat(Team.YELLOW)
        val finishes = Team.entries.associateWith { BoardGeometry.positionFor(it, 0, PieceState.FINISHED) }
        assertEquals("all four finishes must be distinct", 4, finishes.values.toSet().size)
        for (team in Team.entries) {
            assertEquals(
                "$team's finish should be its own last (innermost) home-lane cell",
                BoardGeometry.homeLanePosition(team, Track.HOME_LANE_SIZE - 1),
                finishes.getValue(team),
            )
        }
    }

    @Test
    fun `positionFor routes each Track range to the matching geometry helper`() {
        val team = Team.BLUE
        assertEquals(BoardGeometry.hangarPosition(team, 2), BoardGeometry.positionFor(team, 2, PieceState.InHangar))
        assertEquals(
            BoardGeometry.standbyPosition(team, 0),
            BoardGeometry.positionFor(team, 0, PieceState.OnPath(Track.STANDBY_PATH)),
        )
        assertEquals(
            BoardGeometry.ringPosition(Track.ringIndex(team, 5)),
            BoardGeometry.positionFor(team, 0, PieceState.OnPath(5)),
        )
        assertEquals(
            BoardGeometry.homeLanePosition(team, 2),
            BoardGeometry.positionFor(team, 0, PieceState.OnPath(Track.LOOP_LAST_PATH + 3)),
        )
        assertEquals(
            BoardGeometry.homeLanePosition(team, Track.HOME_LANE_SIZE - 1),
            BoardGeometry.positionFor(team, 0, PieceState.FINISHED),
        )
    }

    @Test
    fun `two teams on one printed cell share an occupancy key, and lone parking spots never do`() {
        // The renderer fans pieces out only when they share a key, so a stale key here is what makes a
        // lone plane sit off the cell circle (or a 僚机 stack read as two neighbouring cells).
        val redPath = 8
        val yellowPath = pathSharingCellWith(Team.YELLOW, Team.RED, redPath)
        assertEquals(
            "same printed cell must key alike across teams",
            BoardGeometry.occupancyKey(Team.RED, 0, PieceState.OnPath(redPath)),
            BoardGeometry.occupancyKey(Team.YELLOW, 3, PieceState.OnPath(yellowPath)),
        )
        assertNotEquals(
            "different printed cells must not key alike",
            BoardGeometry.occupancyKey(Team.RED, 0, PieceState.OnPath(redPath)),
            BoardGeometry.occupancyKey(Team.YELLOW, 3, PieceState.OnPath(yellowPath + 1)),
        )
    }

    @Test
    fun `hangar and 待飞 key per piece so a parked plane is never fanned off centre`() {
        val keys = Team.entries.flatMap { team ->
            (0..3).flatMap { i ->
                listOf(
                    BoardGeometry.occupancyKey(team, i, PieceState.InHangar),
                    BoardGeometry.occupancyKey(team, i, PieceState.OnPath(Track.STANDBY_PATH)),
                )
            }
        }
        // 待飞 sits on the plane's own hangar pad, so the two states key the same spot: 32 slots, 16 keys.
        assertEquals(16, keys.toSet().size)
    }

    @Test
    fun `home lane and finish are private to their team`() {
        val lane = Track.LOOP_LAST_PATH + 2
        assertNotEquals(
            BoardGeometry.occupancyKey(Team.RED, 0, PieceState.OnPath(lane)),
            BoardGeometry.occupancyKey(Team.BLUE, 0, PieceState.OnPath(lane)),
        )
        assertNotEquals(
            BoardGeometry.occupancyKey(Team.RED, 0, PieceState.OnPath(lane)),
            BoardGeometry.occupancyKey(Team.RED, 0, PieceState.OnPath(lane + 1)),
        )
        // All four of a team's finished planes pile on one point and must stack, not scatter.
        assertEquals(
            BoardGeometry.occupancyKey(Team.RED, 0, PieceState.FINISHED),
            BoardGeometry.occupancyKey(Team.RED, 3, PieceState.FINISHED),
        )
    }

    @Test
    fun `a plane alone on its cell sits exactly on the cell centre`() {
        // The reported bug: "单架飞机无法摆放在棋格圆中心上". Every lone plane, every reachable state, must
        // take a zero offset — a lone plane on a printed cell, in its hangar, in 待飞, and in its lane.
        var state = GameState.newGame()
        state = state.withPiece(Team.RED, 0, PieceState.OnPath(8))
        state = state.withPiece(Team.RED, 1, PieceState.OnPath(Track.STANDBY_PATH))
        state = state.withPiece(Team.RED, 2, PieceState.OnPath(Track.LOOP_LAST_PATH + 2))
        state = state.withPiece(Team.BLUE, 0, PieceState.OnPath(3))
        for (team in Team.entries) {
            for (i in 0..3) {
                val off = BoardGeometry.coOccupantOffset(team, i, state)
                assertEquals("$team piece $i x", 0f, off.x, 0f)
                assertEquals("$team piece $i y", 0f, off.y, 0f)
                assertEquals("$team piece $i z", 0f, off.z, 0f)
            }
        }
    }

    @Test
    fun `four planes parked in one hangar never fan out`() {
        // Hangar pads are four separate parking spots, so a full hangar is four lone planes.
        val state = GameState.newGame()
        for (i in 0..3) {
            val off = BoardGeometry.coOccupantOffset(Team.RED, i, state)
            assertEquals(0f, off.x, 0f)
            assertEquals(0f, off.y, 0f)
            assertEquals(0f, off.z, 0f)
        }
    }

    @Test
    fun `a 僚机 stack separates by height and stays well inside its own cell`() {
        var state = GameState.newGame()
        state = state.withPiece(Team.RED, 0, PieceState.OnPath(8))
        state = state.withPiece(Team.RED, 1, PieceState.OnPath(8))
        val a = BoardGeometry.coOccupantOffset(Team.RED, 0, state)
        val b = BoardGeometry.coOccupantOffset(Team.RED, 1, state)

        assertNotEquals("stack-mates must not occupy one point", a.y, b.y)
        assertEquals(BoardGeometry.CO_OCCUPANT_STEP_M, Math.abs(a.y - b.y), 1e-6f)

        // The regression that made a stack read as two neighbouring cells: lateral separation must stay
        // small next to the cell pitch. Measure the pitch here rather than trusting a literal.
        val pitch = (0 until Track.RING_SIZE).minOf { i ->
            val p = BoardGeometry.ringPosition(i)
            val q = BoardGeometry.ringPosition((i + 1) % Track.RING_SIZE)
            Math.hypot((p.x - q.x).toDouble(), (p.z - q.z).toDouble())
        }
        val lateralGap = Math.hypot((a.x - b.x).toDouble(), (a.z - b.z).toDouble())
        assertTrue(
            "stack-mates are ${"%.1f".format(lateralGap * 100)}cm apart on a ${"%.1f".format(pitch * 100)}cm pitch",
            lateralGap < pitch * 0.35,
        )
        for (off in listOf(a, b)) {
            assertTrue("must stay inside its own cell", Math.hypot(off.x.toDouble(), off.z.toDouble()) < pitch / 2)
        }
    }

    @Test
    fun `two teams sharing one printed cell are separated even at the same piece index`() {
        // The old per-index jitter gave RED piece 0 and YELLOW piece 0 the *same* offset, so one hid
        // inside the other exactly when it mattered most — the instant before a capture resolves.
        val redPath = 8
        var state = GameState.newGame()
        state = state.withPiece(Team.RED, 0, PieceState.OnPath(redPath))
        state = state.withPiece(Team.YELLOW, 0, PieceState.OnPath(pathSharingCellWith(Team.YELLOW, Team.RED, redPath)))
        val red = BoardGeometry.coOccupantOffset(Team.RED, 0, state)
        val yellow = BoardGeometry.coOccupantOffset(Team.YELLOW, 0, state)
        assertNotEquals("same index, same cell must still separate", red.x to red.z, yellow.x to yellow.z)
    }
}
