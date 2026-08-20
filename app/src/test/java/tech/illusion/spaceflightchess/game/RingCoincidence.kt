package tech.illusion.spaceflightchess.game

/**
 * Test helper: the path value at which [team] stands on the same printed ring cell that [other]
 * occupies at [otherPath].
 *
 * Cross-team collision tests (capture, pairing, threat models) all need "put these two teams on one
 * physical cell", and every team numbers its own path from its own 待飞区. Spelling those
 * coincidences out as literals is how the old tests silently stopped testing anything when the ring
 * size changed: `yellow path 26` really meant "wherever red's path 5 is" under 28-cell math, and
 * under the measured 52-cell board it just points at an unrelated empty cell, so the assertions
 * passed vacuously or failed for the wrong reason. Deriving it keeps the intent stable.
 *
 * Throws if [team] never occupies that cell during its own lap (possible: each team traverses 51 of
 * the 52 printed cells, skipping the stretch behind its own home-lane mouth).
 */
internal fun pathSharingCellWith(team: Team, other: Team, otherPath: Int): Int {
    val ring = Track.ringIndex(other, otherPath)
    val path = (ring - Track.cell1RingIndex(team) + Track.RING_SIZE) % Track.RING_SIZE + 1
    require(path <= Track.LOOP_LAST_PATH) {
        "$team never stands on ring cell $ring (it lies past its own home-lane mouth)"
    }
    return path
}

/** Every printed ring cell [team] could land on next turn with a single roll from [path]. */
internal fun reachableRingCells(team: Team, path: Int): Set<Int> =
    (1..6).mapNotNull { roll ->
        val newPath = path + roll
        if (Track.isOnSharedLoop(newPath)) Track.ringIndex(team, newPath) else null
    }.toSet()
