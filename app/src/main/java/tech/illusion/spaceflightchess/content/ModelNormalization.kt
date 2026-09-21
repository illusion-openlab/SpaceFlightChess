package tech.illusion.spaceflightchess.content

import com.pico.spatial.core.ecs.BoundingBox
import com.pico.spatial.core.math.Vector3

/**
 * The floor a measured bounding box's longest edge is coerced to before it becomes a divisor, so a
 * degenerate/zero-size model can't produce a divide-by-zero (or an infinite scale). Shared by every
 * caller of [longestEdgeNormalization] — previously two separate constants under two different
 * names ([PlanePieceRenderer]'s own `MIN_MEASURABLE_DIMENSION_M` and
 * `HangarWindow`'s `MIN_MEASURABLE_TILE_DIMENSION_M`), now one.
 */
internal const val MIN_MEASURABLE_DIMENSION_M = 0.0001f

/**
 * Scales and recenters a loaded model so its longest edge becomes [targetLongestEdgeM] and its
 * visual centre lands on its own parent's origin.
 *
 * Shared by [PlanePieceRenderer.attachTo] (target: its own per-board-piece `TARGET_PLANE_SIZE_M`)
 * and `HangarWindow`'s `PlaneTile` (target: a per-tile physical size computed from the
 * `SpatialView`'s own pixel↔metre converter). Both display the same four `.usdz` plane assets,
 * whose raw bounding boxes differ from their visible geometry — and from each other, by roughly
 * 10-20x — badly enough that `SpatialModelView`'s `Resizability.FitInside` (which normalises
 * purely by bounding box, with no way to recenter) produced wildly different apparent sizes for
 * the four teams. This is the one piece of math that corrects for that; it used to be copied
 * verbatim into both call sites, which is exactly the kind of thing that goes stale in one place
 * and not the other — see `.superpowers/sdd/2026-09-21-hangar-window/task-3-report.md`'s round 4
 * numbers for the measured bounds that motivated this.
 *
 * **[bounds] must have been measured with `relativeTo` set to the model's own root entity, never
 * `null`.** `null` measures relative to the SpatialContainer/View (world/stage space), which bakes
 * in every ancestor transform above the model. [PlanePieceRenderer.attachTo] has the long comment
 * on the bug that produced: `relativeTo = null` silently sank every board piece roughly a metre
 * below its pivot, worst for the pieces needing the largest scale-up. That comment lives at the
 * call site, not here, because it is about how you must call this — not about what this function
 * does.
 *
 * @return `(scale, recenter)`. Apply `scale` uniformly to the model's own `TransformComponent`
 *   scale and `recenter` as its position — [recenter] is already pre-scaled, so no further
 *   multiplication is needed before setting it.
 */
internal fun longestEdgeNormalization(bounds: BoundingBox, targetLongestEdgeM: Float): Pair<Float, Vector3> {
    val maxDimension = maxOf(bounds.size.x, bounds.size.y, bounds.size.z)
        .coerceAtLeast(MIN_MEASURABLE_DIMENSION_M)
    val scale = targetLongestEdgeM / maxDimension
    val recenter = Vector3(-bounds.center.x * scale, -bounds.center.y * scale, -bounds.center.z * scale)
    return scale to recenter
}
