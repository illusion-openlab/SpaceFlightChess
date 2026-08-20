package tech.illusion.spaceflightchess.content

import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.InteractableComponent
import android.util.Log
import com.pico.spatial.core.ecs.LoadType
import com.pico.spatial.core.ecs.ObjectAudioComponent
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.audio.AudioPlayerController
import com.pico.spatial.core.ecs.resource.AudioResource
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.math.EulerAngles
import com.pico.spatial.core.math.Vector3
import kotlin.random.Random
import kotlinx.coroutines.delay

/** Same tag as the rest of the app so one logcat filter catches everything. */
private const val TAG = "SpaceFlightChess"

/**
 * The physical wooden die (`wooden_die.usdz`) that replaces the flat "摇骰子" button: tapping it
 * (same pivot + [CollisionComponent] + [InteractableComponent] pattern as [PlanePieceRenderer]'s
 * planes — see [BoardStage]'s pointer handler) rolls, and [tumbleTo] spins it through a few random
 * orientations before easing onto the rolled value's face-up pose, so both the human's and the
 * AI's rolls are visible on the die itself rather than only as HUD text.
 *
 * **[VALUE_TO_ROTATION] is measured, not assumed.** The `.usdz`'s pips are baked into a single
 * albedo texture with no per-face metadata this code can read, and its own export pipeline's
 * nested transforms make guessing a standard "1 up, 2 front, 3 right" layout unreliable. The table
 * was read directly off on-device screenshots of the die at three known single-axis rotations from
 * its identity pose (identity, `pitch=90`, `roll=90`); the other three faces follow from the
 * sum-to-7 rule every physical die satisfies. See the calibration note in `AGENTS.md`.
 */
class DieRenderer {

    private var root: Entity? = null
    private var pivot: Entity? = null

    /**
     * The throw cue, spatialised on the die. Kept on its own child emitter rather than the pivot so the
     * die's own transform animation (tumble out, hold, arc back) carries the sound with it.
     */
    /** This throw's resting yaw — see [restingPose]. Re-drawn per throw so no two landings look alike. */
    private var restYaw: Float = 0f

    private var throwSound: AudioResource? = null

    /**
     * Two emitters on the **same** clip, both sourced from the *same* span: 「骰子的音效只使用前三秒」.
     *
     * `dice_throw.mp3` is a sample-library file holding three separate rattle takes, not one cue —
     * audible content runs ~160–655ms (take 1), ~2746–3097ms (take 2, straddling the 3s mark), and
     * ~4805–5252ms (take 3), with near-silence between. Restricting playback to the file's first three
     * seconds rules out take 3 outright and take 2 (it only clears the 3s boundary by continuing past
     * it), leaving take 1 as the only complete take available — so both cues seek to the same spot in it.
     *
     * That still solves the original defect. The whole throw is only ~1892ms (tumble 736 + hold 900 +
     * arc back 256): playing the file from 0 and letting it run gave ~160ms of silent launch and, worse,
     * **no sound at the moment of impact** (tumbleAlong lands at 736ms, by which point take 1 has already
     * decayed to near-silence). Retriggering the same rattle at touchdown — a second emitter so the two
     * plays cannot cut each other off — puts audible content at both the moment the die leaves the hand
     * and the moment it lands, without ever reading past 3s of the file.
     */
    private var launchEmitter: Entity? = null
    private var launchAudio: AudioPlayerController? = null
    private var landEmitter: Entity? = null
    private var landAudio: AudioPlayerController? = null

    /** True while a throw is in flight, so a rest-position update does not teleport it mid-air. */
    private var throwing = false

    /**
     * Where the die rests between throws, in rig-local space: beside the player rather than on the
     * board. Set by [setRestPosition] once the rig's own facing is known, because "beside the player"
     * depends on which way they were looking at scene start (see `BoardStage`'s horizontalForward).
     */
    private var restPosition: Vector3 = DEFAULT_REST_POSITION

    val isAttached: Boolean get() = root != null

    /** Whether [entity] is this die's own tap target. */
    fun isDie(entity: Entity?): Boolean = entity != null && entity === pivot

    suspend fun attachTo(parent: Entity) {
        if (isAttached) return
        root = parent

        val die = Entity.loadSuspend(uriString = "asset://models/wooden_die.usdz")
        val newPivot = Entity().also(parent::addChild)
        newPivot.addChild(die)

        // Same recenter pattern as PlanePieceRenderer: relativeTo must be the entity itself, not
        // null, or the measured bounds pick up the whole rig/stage ancestor chain instead of the
        // model's own local footprint.
        val bounds = die.getVisualBounds(die)
        val maxDimension = maxOf(bounds.size.x, bounds.size.y, bounds.size.z).coerceAtLeast(MIN_MEASURABLE_DIMENSION_M)
        val scale = TARGET_DIE_SIZE_M / maxDimension
        die.components[TransformComponent::class.java]?.apply {
            setScaleVector(Vector3(scale, scale, scale))
            setPosition(Vector3(-bounds.center.x * scale, -bounds.center.y * scale, -bounds.center.z * scale))
        }

        newPivot.components[TransformComponent::class.java]?.apply {
            position = restPosition
            eulerAngles = VALUE_TO_ROTATION.getValue(RESTING_VALUE)
        }
        newPivot.components.set(InteractableComponent())
        newPivot.components.set(
            CollisionComponent(
                collisionShape = listOf(ShapeResource.createBox(Vector3(TARGET_DIE_SIZE_M, TARGET_DIE_SIZE_M, TARGET_DIE_SIZE_M))),
                physicsMaterial = PhysicsMaterialResource(),
            ),
        )
        throwSound = runCatching {
            AudioResource.load(name = "DiceThrow", path = "asset://audio/dice_throw.mp3", loadType = LoadType.FROM_ASSETS)
        }.onFailure { Log.e(TAG, "audio load failed: asset://audio/dice_throw.mp3", it) }.getOrNull()
        throwSound?.let { clip ->
            dieEmitter(newPivot, clip, "launch")?.let { (entity, controller) ->
                launchEmitter = entity
                launchAudio = controller
            }
            dieEmitter(newPivot, clip, "land")?.let { (entity, controller) ->
                landEmitter = entity
                landAudio = controller
            }
        }

        pivot = newPivot
    }

    /**
     * Moves the die's resting spot to [position] (rig-local) immediately. Used for the initial placement
     * before anyone has rolled; turn-to-turn changes should use [glideToRest] so the die is seen to move.
     */
    fun setRestPosition(position: Vector3) {
        restPosition = position
        if (throwing) return
        pivot?.components?.get(TransformComponent::class.java)?.setPosition(position)
    }

    /**
     * Slides the die to [position] and adopts it as the new resting spot — used when the turn passes, so
     * the die visibly travels to whoever is up next rather than blinking there.
     *
     * A no-op mid-throw: the throw owns the transform until it puts the die back down, and it re-reads
     * [restPosition] when it does, so a slot change during a throw still takes effect afterwards.
     */
    suspend fun glideToRest(position: Vector3) {
        val previous = restPosition
        restPosition = position
        val transform = pivot?.components?.get(TransformComponent::class.java) ?: return
        if (throwing) return
        for (step in 1..SLOT_GLIDE_STEPS) {
            val t = smoothStep(step.toFloat() / SLOT_GLIDE_STEPS)
            val lift = SLOT_GLIDE_ARC_M * 4f * t * (1f - t)
            transform.position = Vector3(
                lerp(previous.x, position.x, t),
                lerp(previous.y, position.y, t) + lift,
                lerp(previous.z, position.z, t),
            )
            delay(FRAME_MS)
        }
        transform.setPosition(position)
    }

    /**
     * Throws the die: it scales up, lights up, arcs from its resting spot beside the player to the
     * middle of the board while tumbling, lands showing [value], holds so the result is readable,
     * then arcs back to rest and dims.
     *
     * The return leg is not something the player asked for explicitly, but without it the die would
     * live on the centre pinwheel — covering the four teams' shared finish — and the tap target would
     * move after every roll. Returning it keeps both stable.
     */
    suspend fun throwTo(value: Int) {
        val transform = pivot?.components?.get(TransformComponent::class.java) ?: return
        throwing = true
        try {
            // 「投骰子时播放该声音」, in two cues so it tracks the motion. DICE_CUE_MS is frame-aligned to
            // the file's 24ms MP3 grid and lands ~8ms after take 1's measured onset (~160ms), so the
            // launch is not silent. Both cues seek to this same point in take 1 — see the class doc for
            // why: 「骰子的音效只使用前三秒」 rules out the other two takes.
            launchAudio?.let {
                it.setLoop(false)
                it.seekTo(DICE_CUE_MS)
                it.play()
            }
            setScale(THROW_SCALE)
            // A fresh resting yaw per throw. The face that ends up up is untouched: the SDK composes
            // extrinsically as M_yaw(Y) * M_pitch(X) * M_roll(Z), so yaw turns the die about *world* +Y,
            // which maps +Y to itself. Without it the landing pose for a given value was bit-identical
            // every time, so rolling the same number twice looked like the die had not moved at all —
            // a large part of why the (measurably fair) die "doesn't feel random".
            restYaw = Random.nextFloat() * 360f
            tumbleAlong(transform, restPosition, BOARD_CENTRE, value)
            // Touchdown. Take 1 has already decayed into silence by 736ms on its own — tumbleAlong's
            // duration comfortably outlasts the ~495ms rattle — so this retrigger is simply a second,
            // independent play of the same clip; there is nothing to stop or click against.
            landAudio?.let {
                it.setLoop(false)
                it.seekTo(DICE_CUE_MS)
                it.play()
            }
            delay(HOLD_AT_CENTRE_MS)
            arcBack(transform, BOARD_CENTRE, restPosition, value)
        } finally {
            // Cancellation can land anywhere in the throw; without this a cue would keep rattling on a
            // motionless die.
            launchAudio?.stop()
            landAudio?.stop()
            setScale(1f)
            transform.setPosition(restPosition)
            throwing = false
        }
    }

    /** One cue emitter: its own child entity and controller, so two takes of one clip can sound apart. */
    private fun dieEmitter(parent: Entity, clip: AudioResource, label: String): Pair<Entity, AudioPlayerController>? {
        val emitter = Entity().also(parent::addChild)
        emitter.components.set(ObjectAudioComponent())
        val controller = emitter.prepareAudio(clip)
        // The SDK returns an invalid controller rather than throwing, so an unusable one would otherwise
        // present simply as "the die is silent".
        if (!controller.valid) {
            Log.e(TAG, "audio prepare invalid: dice $label")
            return null
        }
        return emitter to controller
    }

    /**
     * [value]'s calibrated face-up pose, turned by this throw's [restYaw]. Yaw is the one channel
     * `VALUE_TO_ROTATION` leaves unused (every entry is a single-axis pitch or roll with yaw = 0), and
     * because the SDK's yaw is extrinsic about world +Y it cannot change which face points up.
     */
    private fun restingPose(value: Int): EulerAngles {
        val target = VALUE_TO_ROTATION.getValue(value)
        return EulerAngles(pitch = target.pitch, yaw = restYaw, roll = target.roll)
    }

    private fun setScale(scale: Float) {
        pivot?.components?.get(TransformComponent::class.java)?.setScaleVector(Vector3(scale, scale, scale))
    }

    /** Straight-line-plus-parabola travel with the tumble overlaid, ending on [value]'s face-up pose. */
    private suspend fun tumbleAlong(
        transform: TransformComponent,
        from: Vector3,
        to: Vector3,
        value: Int,
    ) {
        val target = VALUE_TO_ROTATION.getValue(value)
        val totalSteps = TUMBLE_WAYPOINTS * WAYPOINT_STEPS + SETTLE_STEPS
        var stepIndex = 0

        fun advancePosition() {
            stepIndex++
            val t = stepIndex.toFloat() / totalSteps
            // Parabolic arc: a real throw rises then falls rather than sliding along the table.
            val lift = THROW_ARC_HEIGHT_M * 4f * t * (1f - t)
            transform.position = Vector3(
                lerp(from.x, to.x, t),
                lerp(from.y, to.y, t) + lift,
                lerp(from.z, to.z, t),
            )
        }

        var spinFrom = transform.eulerAngles
        repeat(TUMBLE_WAYPOINTS) {
            val waypoint = EulerAngles(
                pitch = Random.nextFloat() * 360f,
                yaw = Random.nextFloat() * 360f,
                roll = Random.nextFloat() * 360f,
            )
            for (step in 1..WAYPOINT_STEPS) {
                val t = step.toFloat() / WAYPOINT_STEPS
                transform.eulerAngles = EulerAngles(
                    pitch = lerpAngleDegrees(spinFrom.pitch, waypoint.pitch, t),
                    yaw = lerpAngleDegrees(spinFrom.yaw, waypoint.yaw, t),
                    roll = lerpAngleDegrees(spinFrom.roll, waypoint.roll, t),
                )
                advancePosition()
                delay(FRAME_MS)
            }
            spinFrom = waypoint
        }

        for (step in 1..SETTLE_STEPS) {
            val t = smoothStep(step.toFloat() / SETTLE_STEPS)
            transform.eulerAngles = EulerAngles(
                pitch = lerpAngleDegrees(spinFrom.pitch, target.pitch, t),
                yaw = lerpAngleDegrees(spinFrom.yaw, restYaw, t),
                roll = lerpAngleDegrees(spinFrom.roll, target.roll, t),
            )
            advancePosition()
            delay(FRAME_MS)
        }
        transform.eulerAngles = restingPose(value)
        transform.setPosition(to)
    }

    /** Floats the die back to the player's side, keeping [value] face-up so the result stays readable. */
    private suspend fun arcBack(transform: TransformComponent, from: Vector3, to: Vector3, value: Int) {
        transform.eulerAngles = restingPose(value)
        for (step in 1..RETURN_STEPS) {
            val t = smoothStep(step.toFloat() / RETURN_STEPS)
            val lift = RETURN_ARC_HEIGHT_M * 4f * t * (1f - t)
            transform.position = Vector3(
                lerp(from.x, to.x, t),
                lerp(from.y, to.y, t) + lift,
                lerp(from.z, to.z, t),
            )
            delay(FRAME_MS)
        }
        transform.setPosition(to)
    }

    fun detach() {
        // Audio resources are not owned by the entity tree; the SDK requires closing them by hand.
        launchAudio?.stop()
        launchAudio?.close()
        landAudio?.stop()
        landAudio?.close()
        throwSound?.close()
        launchAudio = null
        landAudio = null
        throwSound = null
        pivot?.destroy()
        pivot = null
        root = null
    }

    private companion object {
        /**
         * Seek offset into `dice_throw.mp3`'s take 1 — the only take fully inside the file's first three
         * seconds, per the shared-source-point note above. 168ms is frame 7 on the file's 24ms MP3 grid,
         * landing ~8ms after take 1's measured onset (~160ms).
         */
        const val DICE_CUE_MS = 168L

        const val TARGET_DIE_SIZE_M = 0.05f
        const val MIN_MEASURABLE_DIMENSION_M = 0.0001f

        /** Fallback resting spot, used until `BoardStage` supplies a facing-aware one via [setRestPosition]. */
        val DEFAULT_REST_POSITION = Vector3(0.50f, TARGET_DIE_SIZE_M / 2f, 0.50f)

        /**
         * The board's own centre in rig-local space — the rig origin is the board centre.
         *
         * Height is [TARGET_DIE_SIZE_M]'s half-extent scaled by [THROW_SCALE], not the bare half-extent:
         * this point is only ever reached while the die is enlarged for the throw (`tumbleAlong`'s
         * destination and `arcBack`'s origin, both while [setScale] is holding [THROW_SCALE]), so a height
         * sized for the die's resting scale sank the enlarged die into the board by
         * `(THROW_SCALE - 1) * TARGET_DIE_SIZE_M / 2` ≈ 1.75cm — worst during the full
         * [HOLD_AT_CENTRE_MS], which is exactly when the die is meant to sit still and be read.
         */
        val BOARD_CENTRE = Vector3(0f, TARGET_DIE_SIZE_M / 2f * THROW_SCALE, 0f)

        /** Thrown-die look: bigger while it is in the air. 「去掉骰子高亮反馈」 removed the glow shell that used to accompany this. */
        private const val THROW_SCALE = 1.7f

        private const val THROW_ARC_HEIGHT_M = 0.16f
        private const val RETURN_ARC_HEIGHT_M = 0.10f
        private const val RETURN_STEPS = 16
        private const val HOLD_AT_CENTRE_MS = 900L

        /** How the die travels to the next team's slot when the turn passes. */
        private const val SLOT_GLIDE_STEPS = 20
        private const val SLOT_GLIDE_ARC_M = 0.06f

        /** What the die shows before the first roll — cosmetic only. */
        const val RESTING_VALUE = 1

        const val TUMBLE_WAYPOINTS = 4
        const val WAYPOINT_STEPS = 8
        const val SETTLE_STEPS = 14
        const val FRAME_MS = 16L

        /** Measured face-up rotation per rolled value — see the class doc. */
        val VALUE_TO_ROTATION: Map<Int, EulerAngles> = mapOf(
            1 to EulerAngles(pitch = 90f, yaw = 0f, roll = 0f),
            2 to EulerAngles(pitch = 180f, yaw = 0f, roll = 0f),
            3 to EulerAngles(pitch = 0f, yaw = 0f, roll = 90f),
            4 to EulerAngles(pitch = 0f, yaw = 0f, roll = -90f),
            5 to EulerAngles(pitch = 0f, yaw = 0f, roll = 0f),
            6 to EulerAngles(pitch = -90f, yaw = 0f, roll = 0f),
        )
    }
}
