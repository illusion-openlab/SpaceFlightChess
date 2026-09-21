package tech.illusion.spaceflightchess.content

import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.LoadType
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.EulerAngles
import com.pico.spatial.core.math.Vector3

/**
 * The static board: just the space-art texture (`board_space.jpg`) on a flat plane. No
 * procedurally-drawn cell/lane/hangar tiles — the artwork already draws the loop, home lanes, and
 * hangars, and [tech.illusion.spaceflightchess.game.BoardGeometry] places pieces to line up with
 * it directly, so a second, separately-colored layer of tiles on top would only duplicate (and
 * visually clash with) what the texture already shows.
 */
class BoardRenderer {

    private var root: Entity? = null
    private var boardArtEntity: Entity? = null
    private val entities = ArrayList<Entity>()

    val isAttached: Boolean get() = root != null

    fun attachTo(parent: Entity) {
        if (isAttached) return
        root = parent
        attachBoardArt(parent)
    }

    private fun attachBoardArt(parent: Entity) {
        val texture = TextureResource.load(path = "textures/board_space.jpg", loadType = LoadType.FROM_ASSETS)
        val material = UnlitMaterial.create(BlendingMode.OPAQUE).apply { setBaseColorTexture(texture) }
        val mesh = MeshResource.createPlane(width = BOARD_ART_SIZE_M, height = BOARD_ART_SIZE_M)
        val entity = ModelEntity(mesh, material).also(parent::addChild)
        entity.components[TransformComponent::class.java]?.apply {
            position = Vector3(0f, -BOARD_ART_Y_DROP_M, 0f)
            // createPlane's mesh faces +Z by default; pitch it flat so it faces +Y (up), matching
            // the tabletop-from-above layout the rest of the board already uses.
            eulerAngles = EulerAngles(pitch = -90f, yaw = 0f, roll = 0f)
        }
        entities.add(entity)
        boardArtEntity = entity
    }

    /**
     * Re-orients the printed board to [tech.illusion.spaceflightchess.game.BoardGeometry]'s current
     * [tech.illusion.spaceflightchess.game.BoardGeometry.seatRotationDegrees] after the player picks
     * a seat — a single one-shot call right after [attachTo], since the seat is decided before the
     * Stage ever opens and there is no in-board seat-change transition left to interpolate through.
     * The artwork and the piece-position math must rotate together — pieces would otherwise keep
     * moving correctly relative to *each other* but sit on the wrong printed hangar/lane.
     *
     * [yawDegrees] is in [BoardGeometry]'s own rotation sense (its `rotate()`'s clockwise-from-above
     * convention). Confirmed on-device (real-headset screenshot) that applying that value directly
     * as this plane's Euler yaw spins the *printed art* the opposite way from the pieces once it is
     * combined with the `pitch = -90` that lays the plane flat: the plane's local Y axis (what yaw
     * turns about) is no longer world-up after that pitch, so the two rotations don't compose the
     * way a flat top-down spin would. Negating here — rather than changing [BoardGeometry]'s own
     * sign, which the piece math and its unit tests already rely on — keeps the fix local to this
     * one asset's orientation quirk.
     */
    fun updateSeatRotation(yawDegrees: Float) {
        boardArtEntity?.components?.get(TransformComponent::class.java)?.apply {
            eulerAngles = EulerAngles(pitch = -90f, yaw = -yawDegrees, roll = 0f)
        }
    }

    fun detach() {
        entities.forEach { it.destroy() }
        entities.clear()
        boardArtEntity = null
        root = null
    }

    private companion object {
        /** Square backdrop sized to comfortably cover the loop+hangar footprint. */
        const val BOARD_ART_SIZE_M = 0.95f

        /** Pieces sit at Y=0..PIECE_LIFT_M (see `PlanePieceRenderer`); keep the backdrop below that. */
        const val BOARD_ART_Y_DROP_M = 0.006f
    }
}
