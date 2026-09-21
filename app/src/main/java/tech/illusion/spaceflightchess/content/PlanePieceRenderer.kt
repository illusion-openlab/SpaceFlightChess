package tech.illusion.spaceflightchess.content

import android.util.Log
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.LoadType
import com.pico.spatial.core.ecs.ObjectAudioComponent
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.animation.AnimationPlaybackController
import com.pico.spatial.core.ecs.audio.AudioPlayerController
import com.pico.spatial.core.ecs.resource.AnimationResource
import com.pico.spatial.core.ecs.resource.AudioResource
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.EulerAngles
import com.pico.spatial.core.math.Vector3
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import tech.illusion.spaceflightchess.game.BoardGeometry
import tech.illusion.spaceflightchess.game.GameState
import tech.illusion.spaceflightchess.game.PieceState
import tech.illusion.spaceflightchess.game.Team
import tech.illusion.spaceflightchess.game.Track

/**
 * Owns all 16 plane entities (4 teams x 4 pieces) and keeps their positions in step with a
 * [GameState]. The plane model itself is the piece — no separate colour-identity disc; picking
 * (tap-to-select) hangs off the pivot directly instead of off a disc's own collision volume.
 *
 * **Why every plane gets its own real entity, unlike `SpaceGomoku`'s GPU-instanced stones.** These
 * are imported `.usdz` assets with baked hand-painted textures (see the design doc, ch.3) — there
 * is no shared, tintable material to instance, and only 16 entities is nowhere near the ~60
 * active-entity Stage budget that instancing exists to protect in the first place.
 *
 * **Why a pivot entity per piece.** The raw loaded model's own origin/scale is asset-specific and
 * wrong for a board piece (see `spatial-sdk_ecs_get-the-bounding-box-of-an-entity.md`'s FAQ on
 * origin-vs-bounds-centre mismatches) and real-world aircraft scale is nowhere near tabletop size.
 * That one-time correction (measured via `getVisualBounds` right after load, before anything else
 * touches its transform) lives on the model *child*'s own [TransformComponent] and is set once;
 * the *pivot*'s transform is the only thing [render] ever touches afterwards, so the two concerns
 * — "how do I stand this specific asset up straight and the right size" vs "where is this piece on
 * the board right now" — never fight over the same transform.
 */
private const val TAG = "SpaceFlightChess"

class PlanePieceRenderer {

    /**
     * [model] is the loaded `.usdz` root under [pivot]; [highlight] is a lazily created emissive disc
     * under [pivot], enabled only while this plane is in 待飞. [animations] is whatever embedded
     * animation this model actually turned out to have, paired with the entity that owns it.
     */
    private class Piece(
        val pivot: Entity,
        val model: Entity,
        var highlight: Entity? = null,
        var animations: List<Pair<Entity, AnimationResource>> = emptyList(),
        /**
         * The controller each currently-playing [animations] entry got back from `playAnimation`, so
         * [stopAnimations] can call its own `.stop()` directly rather than relying solely on
         * `entity.stopAllAnimations()`. See [startAnimations] for why both are used.
         */
        var animationControllers: List<AnimationPlaybackController> = emptyList(),
        /**
         * Last yaw written to the pivot. Held here rather than read back from the transform: the SDK
         * stores rotation as a quaternion, so `eulerAngles` can round-trip to an equivalent-but-different
         * triple and interpolating from that produces a visible twitch.
         */
        var yawDegrees: Float = 0f,
        /**
         * One bare child entity per sound, each with its own [ObjectAudioComponent] and controller.
         *
         * Two emitters rather than two controllers on the pivot: the SDK documents `prepareAudio` as
         * "entity must carry an audio component" but says nothing about whether one entity may hold two
         * players at once, and the 待飞 cue can still be sounding when the walk begins. A child per
         * sound is correct under either semantics, and still spatialised to the plane because the child
         * inherits the pivot's transform — the same trick the highlight disc already uses.
         */
        var flightEmitter: Entity? = null,
        var flightAudio: AudioPlayerController? = null,
        var standbyEmitter: Entity? = null,
        var standbyAudio: AudioPlayerController? = null,
    )

    private var root: Entity? = null
    private val pieces = HashMap<Pair<Team, Int>, Piece>()

    /** Loaded once and shared by all 16 planes; each plane gets its own controller. Closed in [detach]. */
    private var flightSound: AudioResource? = null
    private var standbySound: AudioResource? = null

    /** Populated as each pivot is created — same "each interactive entity carries its own identity" pattern as `SpaceGomoku`'s `BoardPicker`. */
    private val pivotToKey = HashMap<Entity, Pair<Team, Int>>()

    /** Pieces with a slide in flight; [render] must not fight them. See [animateMove]. */
    private val animating = HashSet<Pair<Team, Int>>()

    /** Pieces currently between cells — they keep their model animation running. See [applyAnimationState]. */
    private val flying = HashSet<Pair<Team, Int>>()

    /** Whose model animation we have actually started, so start/stop stays idempotent. */
    private val animationsOn = HashSet<Pair<Team, Int>>()

    /** Whose flight sound is playing, so [applyFlightSound] stays idempotent. */
    private val flightSoundOn = HashSet<Pair<Team, Int>>()

    /** Per-piece animation generation, so a newer slide supersedes an older one. See [animateMove]. */
    private val generations = HashMap<Pair<Team, Int>, Int>()

    /** Pieces currently wearing the 待飞 look, so [applyStandbyVisual] only acts on transitions. */
    private val standbyVisual = HashSet<Pair<Team, Int>>()

    val isAttached: Boolean get() = root != null

    suspend fun attachTo(parent: Entity) {
        if (isAttached) return
        root = parent

        var sinceYield = 0
        flightSound = loadSound("PlaneFlight", "asset://audio/plane_flight.mp3")
        standbySound = loadSound("PlaneStandby", "asset://audio/plane_standby.mp3")

        for (team in Team.entries) {
            // One real `.usdz` load per team (parsing a model + its textures is the slow part —
            // real-device evidence showed ~1.7s each, and 16 sequential loads would take the best
            // part of half a minute). The other three pieces are `clone()`s of the same loaded
            // entity, which is far cheaper than re-parsing the same file three more times.
            val source = Entity.loadSuspend(uriString = "asset://models/${assetFileFor(team)}")
            val probePivot = Entity().also(parent::addChild)
            probePivot.addChild(source)

            // Measured immediately after load, before any custom transform is applied, so the
            // numbers are unaffected by whatever this method is about to set.
            //
            // `relativeTo` MUST be `source` itself, not `null` — passing `null` measures bounds
            // relative to the SpatialContainer (world/stage space), which bakes in this entity's
            // entire ancestor chain, including the rig's own elevation (RIG_HEIGHT_M, ~1.15m). That
            // silently turned "recenter the model on its own pivot" into "recenter the model roughly
            // 1m below its pivot" for every team, worst for the two teams whose source assets happen
            // to need the largest scale-up (small originals get scaled up the most, so the same
            // ~1.15m error, multiplied by that scale, became tens of centimetres of real drop —
            // enough to sink through the board or the floor; the two teams needing the least scale-up
            // only drifted a couple of millimetres, easy to mistake for "close enough").
            val bounds = source.getVisualBounds(source)
            val (scale, recenter) = longestEdgeNormalization(bounds, TARGET_PLANE_SIZE_M)

            for (index in 0 until GameState.PIECES_PER_TEAM) {
                val pivot = if (index == 0) probePivot else Entity().also(parent::addChild)
                val model = if (index == 0) {
                    source
                } else {
                    val clone = source.clone(cloneOptions = Entity.CloneOptions(recursive = true, shouldShareMaterialInstance = true))
                        ?: error("Entity.clone returned null for team $team piece $index")
                    pivot.addChild(clone)
                    clone
                }

                model.components[TransformComponent::class.java]?.apply {
                    setScaleVector(Vector3(scale, scale, scale))
                    setPosition(recenter)
                }

                // Picking hangs off the pivot itself now (there's no disc to carry it): a box sized
                // to the plane's own target footprint, not the model's raw (asset-specific) bounds.
                pivot.components.set(InteractableComponent())
                pivot.components.set(
                    CollisionComponent(
                        collisionShape = listOf(ShapeResource.createBox(Vector3(TARGET_PLANE_SIZE_M, TARGET_PLANE_SIZE_M, TARGET_PLANE_SIZE_M))),
                        physicsMaterial = PhysicsMaterialResource(),
                    ),
                )

                // Discover the model's embedded animations now rather than on first 待飞: it is a
                // one-time cost during the already-slow load phase, and it means the per-team count
                // shows up in logcat at startup as evidence, instead of only after someone happens to
                // roll a 6. Offline inspection of the four assets found a SkelAnimation in red_plane
                // and animated node transforms (propellers) in the other three, so the two authoring
                // styles are both expected here.
                val piece = Piece(pivot, model, animations = discoverAnimations(model))
                attachSounds(piece, team, index)
                Log.e(TAG, "model animations: team=$team piece=$index found=${piece.animations.size}")
                pieces[team to index] = piece
                pivotToKey[pivot] = team to index

                if (++sinceYield >= LOAD_BATCH) {
                    sinceYield = 0
                    yield()
                }
            }
        }
    }

    /** The (team, pieceIndex) a picked piece's pivot entity stands for, or `null` if it is not one of ours. */
    fun pieceFor(entity: Entity?): Pair<Team, Int>? = entity?.let(pivotToKey::get)

    /**
     * Moves every piece to where [state] says it is, instantly — this is how captures and
     * hangar-returns land, and it is the authority for anything [animateMove] is not currently
     * moving.
     *
     * **Pieces with an animation in flight are deliberately skipped.** `BoardStage.publish()` calls
     * this on every state change, including changes that happen while a slide is still playing; left
     * unguarded it would hard-snap the sliding piece to its destination and the still-running lerp
     * would then yank it back, so a slide read as a teleport-plus-stutter. The animation writes the
     * final position itself when it finishes.
     */
    fun render(state: GameState) {
        for ((key, piece) in pieces) {
            val (team, index) = key
            // The 待飞 visual state is driven from game state on every render, but only *transitions*
            // do work — playAnimation must not be re-issued on every publish().
            applyStandbyVisual(key, piece, team, isStandby(team, index, state))
            applyAnimationState(key, piece, isStandby(team, index, state) || key in flying)
            applyFlightSound(key, piece, key in flying && isOnBoard(team, index, state))
            if (key in animating) continue
            piece.pivot.components[TransformComponent::class.java]?.setPosition(targetPosition(team, index, state))
            applyYaw(piece, team, BoardGeometry.settledHeadingDegrees(team, index, state.pieces(team)[index]))
        }
    }

    /**
     * Loads one shared clip, reporting failure as evidence instead of a silent mute.
     *
     * The path needs the `asset://` scheme. The SDK docs contradict each other here — the resource
     * management page says the path must be "relative to the /assets directory", while the
     * ObjectAudioComponent page passes `asset://…` with the same `LoadType.FROM_ASSETS`. The bare
     * relative form is rejected at runtime with
     * `ResourceLoadingException {INVALID_ARGS, Illegal file type}`, so the scheme form is the real
     * contract — and it matches how this project already loads models.
     */
    private fun loadSound(name: String, path: String): AudioResource? =
        runCatching { AudioResource.load(name = name, path = path, loadType = LoadType.FROM_ASSETS) }
            .onFailure { Log.e(TAG, "audio load failed: $path", it) }
            .getOrNull()

    /**
     * Gives [piece] its two sound emitters. Each is a bare child of the pivot carrying its own
     * [ObjectAudioComponent], so the sound is spatialised at the plane and follows it as it walks.
     *
     * `prepareAudio` rather than `playAudio`: the controller is wanted now, the sound is not — flight
     * noise starts when the plane actually moves and the 待飞 cue when it is called up. The SDK returns
     * an *invalid* controller instead of throwing when preparation fails, so `valid` is checked here and
     * logged; otherwise a missing asset would present as "the game is just silent".
     */
    private fun attachSounds(piece: Piece, team: Team, index: Int) {
        emitterFor(piece, flightSound, "flight", team, index)?.let { (entity, controller) ->
            piece.flightEmitter = entity
            piece.flightAudio = controller
        }
        emitterFor(piece, standbySound, "standby", team, index)?.let { (entity, controller) ->
            piece.standbyEmitter = entity
            piece.standbyAudio = controller
        }
    }

    private fun emitterFor(
        piece: Piece,
        resource: AudioResource?,
        label: String,
        team: Team,
        index: Int,
    ): Pair<Entity, AudioPlayerController>? {
        val clip = resource ?: return null
        val emitter = Entity().also(piece.pivot::addChild)
        emitter.components.set(ObjectAudioComponent())
        val controller = emitter.prepareAudio(clip)
        if (!controller.valid) {
            Log.e(TAG, "audio prepare invalid: $label team=$team piece=$index")
            return null
        }
        return emitter to controller
    }

    /**
     * On a printed cell, a home-lane cell or the finish — i.e. actually flying, as opposed to parked on
     * its hangar pad (either `InHangar` or 待飞, which share the same pad).
     *
     * Gates the engine noise. Being called up to 待飞 is still a *move* as far as [animateMove] is
     * concerned (the plane hops from its pad to the raised 待飞 pose), so without this the launch hop
     * played the flight loop on top of the 待飞 cue: 「飞机从机库待机时不要播放飞机飞行声音，只播放待机声音」.
     */
    /**
     * Points [piece] along [headingDegrees]. Only ever a yaw — one non-zero Euler component — because the
     * SDK composes extrinsically as `M_yaw(Y) * M_pitch(X) * M_roll(Z)`, so a lone yaw is a clean rotation
     * about world +Y and cannot interact with anything else.
     *
     * **Not negated.** `BoardRenderer` has to negate its seat yaw, and it is tempting to copy that here.
     * The reason it must is that `BoardGeometry.rotate(A)` is a clockwise-from-above turn, i.e. `R_y(−A)`;
     * a heading measured in BoardGeometry's own coordinates is therefore already in the SDK's yaw sense.
     * Negating it would fly every plane backwards.
     */
    private fun applyYaw(piece: Piece, team: Team, headingDegrees: Float?) {
        val heading = headingDegrees ?: return
        val yaw = heading + MODEL_YAW_OFFSET_DEG.getValue(team)
        piece.yawDegrees = yaw
        piece.pivot.components[TransformComponent::class.java]?.eulerAngles =
            EulerAngles(pitch = 0f, yaw = yaw, roll = 0f)
    }

    private fun isOnBoard(team: Team, index: Int, state: GameState): Boolean {
        val piece = state.pieces(team)[index]
        return piece is PieceState.OnPath && !Track.isInStandby(piece.value)
    }

    private fun isStandby(team: Team, index: Int, state: GameState): Boolean {
        val piece = state.pieces(team)[index]
        return piece is PieceState.OnPath && Track.isInStandby(piece.value)
    }

    /**
     * The 待飞 look: the plane is called out of the hangar by a 6 but stays on its own pad, so the
     * state has to read purely visually — scaled up, lifted [STANDBY_LIFT_M], sitting on a glowing
     * team-coloured disc, with whatever animation the model itself ships (propeller spin on three of
     * the four assets, a skeletal clip on the fourth) running.
     *
     * Idempotent: called from [render] on every state change, but only acts when the flag actually
     * flips, because `playAnimation` restarts a clip and re-issuing it every publish would stutter.
     */
    private fun applyStandbyVisual(key: Pair<Team, Int>, piece: Piece, team: Team, standby: Boolean) {
        if (standby == (key in standbyVisual)) return
        val transform = piece.pivot.components[TransformComponent::class.java]
        if (standby) {
            standbyVisual += key
            transform?.setScaleVector(Vector3(STANDBY_SCALE, STANDBY_SCALE, STANDBY_SCALE))
            highlightFor(piece, team).enabled = true
            playStandbySound(piece)
        } else {
            standbyVisual -= key
            transform?.setScaleVector(Vector3.ONE)
            piece.highlight?.enabled = false
        }
    }

    /**
     * The single authority for whether a piece's model animation is running, because two independent
     * things want it: 待飞 (parked but called up, propeller spinning) and being in flight between cells.
     * Driving both from one idempotent setter is what stops them cancelling each other — `publish()`
     * calls [render] mid-move, and while [applyStandbyVisual] owned the stop it killed the flight
     * animation the instant a plane left 待飞, which is the very turn it starts moving.
     *
     * Idempotent on purpose: `playAnimation` restarts a clip from frame 0, so re-issuing it on every
     * publish would leave the propeller visibly stuttering.
     */
    /**
     * Engine noise while the plane is between cells — 「飞机飞行时使用该声音（绑定到飞机上）」. Loops, because
     * a walk lasts a second per cell and the clip is shorter than a long move.
     *
     * Idempotent for the same reason as [applyAnimationState]: `render` runs on every publish, and
     * restarting the clip each time would machine-gun it.
     */
    private fun applyFlightSound(key: Pair<Team, Int>, piece: Piece, wanted: Boolean) {
        if (wanted == (key in flightSoundOn)) return
        if (wanted) {
            flightSoundOn += key
            val controller = piece.flightAudio ?: return
            controller.setLoop(true)
            controller.play()
        } else {
            flightSoundOn -= key
            piece.flightAudio?.stop()
        }
    }

    /** 「飞机从机库转为等待状态时播放该声音」 — a one-shot, fired on the transition into 待飞. */
    private fun playStandbySound(piece: Piece) {
        piece.standbyAudio?.let {
            it.seekTo(0)
            it.play()
        }
    }

    private fun applyAnimationState(key: Pair<Team, Int>, piece: Piece, wanted: Boolean) {
        if (wanted == (key in animationsOn)) return
        Log.e(TAG, "animation ${if (wanted) "start" else "stop"}: team=${key.first} piece=${key.second} clips=${piece.animations.size}")
        if (wanted) {
            animationsOn += key
            startAnimations(piece)
        } else {
            animationsOn -= key
            stopAnimations(piece)
        }
    }

    /** Lazily builds the glow disc. Unlit + additive so it reads as a glow rather than a painted plate. */
    private fun highlightFor(piece: Piece, team: Team): Entity {
        piece.highlight?.let { return it }
        val material = UnlitMaterial.create(BlendingMode.ADD).apply { setBaseColor(glowColourFor(team)) }
        val mesh = MeshResource.createCylinder(height = HIGHLIGHT_THICKNESS_M, radius = HIGHLIGHT_RADIUS_M)
        val disc = ModelEntity(mesh, material).also(piece.pivot::addChild)
        // Just under the plane, in pivot-local space, so it follows the lift and the scale-up.
        disc.components[TransformComponent::class.java]?.setPosition(Vector3(0f, -HIGHLIGHT_DROP_M, 0f))
        piece.highlight = disc
        return disc
    }

    /**
     * Plays every animation the model ships with. Deliberately searches the whole hierarchy rather
     * than assuming a shape: measured offline, exactly one of the four assets (red) carries a
     * `SkelAnimation`, while green/blue/yellow instead animate node transforms (propellers) — those
     * are two different authoring styles and the resources hang off different entities. Whatever is
     * found is cached on first use; the count is logged so a model that ships no animation at all
     * shows up as evidence in logcat instead of silently doing nothing.
     *
     * Keeps the returned [com.pico.spatial.core.ecs.animation.AnimationPlaybackController] for each
     * clip — see [stopAnimations] for why.
     */
    private fun startAnimations(piece: Piece) {
        piece.animationControllers = piece.animations.map { (entity, resource) -> entity.playAnimation(resource) }
    }

    /**
     * Stops every animation this piece started — on **each entity that owns one**, not just the model
     * root, *and* through the specific controller [startAnimations] got back for each one.
     *
     * The per-entity sweep was a real bug fix already: [startAnimations] plays on every owning entity
     * (23 of them on the yellow asset), but the stop used to be a single `model.stopAllAnimations()`.
     * Three of the four assets animate node transforms hanging off *descendants* of the model root, so
     * their propellers never stopped — the plane kept spinning after it left 待飞, all the way onto the
     * board and back into the hangar when it was captured, which is where the player noticed it.
     *
     * Red's asset is the one exception (a `SkelAnimation`, not a node-transform propeller — see
     * [startAnimations]), and it kept spinning even after the entity-sweep fix, on the same "captured
     * and returned to hangar" symptom. `AnimationPlayConfig`'s documented default transition mode for
     * skeletal clips is CROSSFADE (plain node-transform clips default to COMPOSE), so
     * `entity.stopAllAnimations()` alone stopping a skeletal clip mid-crossfade is exactly the kind of
     * case this project has no direct evidence for either way. Calling `.stop()` on the controller
     * `playAnimation` actually returned is the SDK's own documented per-playback control surface for
     * this — strictly more targeted than "stop everything this entity is doing" — so it is used here
     * defensively, on top of (not instead of) the existing entity-level sweep.
     *
     * The model root is cleared as well, defensively: whether `stopAllAnimations` cascades to children
     * is not documented either way, so this does not rely on it in either direction.
     */
    private fun stopAnimations(piece: Piece) {
        piece.animationControllers.forEach {
            it.stop()
            // Evidence, not a fix: if the SDK does not honour `stop()` for this controller (e.g. a
            // skeletal clip mid-CROSSFADE), this is the one place that can show it in logcat instead
            // of only being visible as "the plane is still spinning" on screen.
            if (it.isPlaying()) Log.e(TAG, "animation stop: controller still reports isPlaying=true after stop()")
            it.close()
        }
        piece.animationControllers = emptyList()
        piece.animations.forEach { (entity, _) -> entity.stopAllAnimations() }
        piece.model.stopAllAnimations()
    }

    /**
     * Every animation this model ships with, paired with the entity that owns it. Searches the whole
     * hierarchy rather than assuming a shape: the four assets use two different authoring styles
     * (one skeletal clip, three sets of animated node transforms) and the resources hang off
     * different entities in each case.
     */
    private fun discoverAnimations(model: Entity): List<Pair<Entity, AnimationResource>> {
        val found = mutableListOf<Pair<Entity, AnimationResource>>()
        for (entity in listOf(model) + model.findSkinnedMeshEntity().toList() + descendantsOf(model)) {
            entity.getAnimationResources().forEach { found += entity to it }
        }
        return found.distinct()
    }

    private fun descendantsOf(entity: Entity): List<Entity> {
        val out = mutableListOf<Entity>()
        val stack = ArrayDeque(entity.getChildren().toList())
        while (stack.isNotEmpty()) {
            val next = stack.removeFirst()
            out += next
            stack.addAll(next.getChildren().toList())
        }
        return out
    }

    /**
     * Slides [team]'s pieces [indices] from wherever their pivots currently sit to where [state] says
     * they now are — hangar pad to 待飞区, or ring cell to ring cell — instead of snapping. Takes a
     * list because a 僚机 moves as one unit (see [tech.illusion.spaceflightchess.game.Move]), so a
     * whole stack has to slide together.
     *
     * Leaves every other piece untouched; callers should still call `render` afterwards to pick up
     * captures/hangar-returns caused by the same move.
     *
     * **Animation ownership**: each piece carries a generation counter. Starting a new animation for
     * a piece bumps its generation, and the older loop notices it no longer owns the piece and drops
     * out without writing another frame — so two overlapping moves on the same piece can't fight
     * over its transform, and a cancelled animation can't leave [animating] populated forever.
     */
    suspend fun animateMove(
        team: Team,
        indices: List<Int>,
        state: GameState,
        from: PieceState,
        waypoints: List<PieceState>,
    ) {
        // Tripwire, not a fix: `BoardStage.isTurnBusy` is what actually serialises turns
        // (「等到本轮玩家行棋结束后再到下一个玩家」). If another team is still mid-walk when this starts,
        // that gate has a hole, and the symptom — two planes converging on one cell, the later arrival
        // captured out from under its own animation — is nearly impossible to read back from a bug
        // report. Fail loudly in logcat instead.
        val trespassers = flying.filter { it.first != team }
        if (trespassers.isNotEmpty()) {
            Log.e(TAG, "TURN OVERLAP: $team started moving while $trespassers were still in flight")
        }
        val tracks = indices.mapNotNull { index ->
            val key = team to index
            val transform = pieces[key]?.pivot?.components?.get(TransformComponent::class.java) ?: return@mapNotNull null
            val generation = (generations[key] ?: 0) + 1
            generations[key] = generation
            animating += key
            flying += key
            pieces[key]?.let {
                applyAnimationState(key, it, true)
                applyFlightSound(key, it, isOnBoard(team, index, state))
            }
            SlideTrack(
                key,
                generation,
                transform,
                targetPosition(team, index, state),
                startYaw = pieces[key]?.yawDegrees ?: 0f,
            )
        }
        if (tracks.isEmpty()) return

        val startedAt = System.nanoTime()
        try {
            // The last waypoint uses the piece's real settled position (co-occupant offset, 待飞 lift and
            // all); the ones before it are cell centres the plane is merely passing over, carrying its
            // final lateral offset so a 僚机 stack stays tellable apart in transit.
            // `from` prepended so hop 0 has an origin to take its heading from: without it the first hop
            // would inherit the heading of the *previous* leg, which is up to 70.7° off at a board corner.
            val legs = listOf(from) + waypoints
            for ((hop, waypoint) in waypoints.withIndex()) {
                val last = hop == waypoints.lastIndex
                for (tr in tracks) {
                    tr.from = tr.transform.position
                    tr.to = if (last) tr.settled else waypointPosition(team, tr.key.second, waypoint, state)
                    // Carry the previous hop's heading forward. Reading the piece's stored yaw here
                    // instead would be stale — it is only written once the whole walk finishes — so every
                    // hop after the first would snap back to the pre-move heading.
                    tr.fromYaw = tr.toYaw
                    val index = tr.key.second
                    val heading = BoardGeometry.headingDegrees(
                        BoardGeometry.positionFor(team, index, legs[hop]),
                        BoardGeometry.positionFor(team, index, legs[hop + 1]),
                    )
                    // Cell centres, not the interpolated positions: a degenerate leg (a hop that does not
                    // move on the board, e.g. into 待飞) keeps the heading it already had.
                    tr.toYaw = heading?.plus(MODEL_YAW_OFFSET_DEG.getValue(team)) ?: tr.fromYaw
                }
                if (!playHop(tracks)) return
            }
            for (tr in tracks) {
                if (generations[tr.key] != tr.generation) continue
                tr.transform.position = tr.settled
                tr.transform.eulerAngles = EulerAngles(pitch = 0f, yaw = tr.toYaw, roll = 0f)
                pieces[tr.key]?.yawDegrees = tr.toYaw
            }
            // Kept in the build on purpose: the requested pacing is 「每隔1秒」, and this is the only place
            // the *actual* per-cell beat is observable. One line per move, so it stays cheap.
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            val perHop = if (waypoints.isEmpty()) 0 else elapsedMs / waypoints.size
            Log.e(
                TAG,
                "walk: team=$team indices=$indices hops=${waypoints.size} " +
                    "elapsedMs=$elapsedMs perHopMs=$perHop path=${waypoints.map { describe(it) }}",
            )
        } finally {
            // Only release the pieces this call still owns; a superseding animation keeps its own.
            for (tr in tracks) {
                if (generations[tr.key] != tr.generation) continue
                animating -= tr.key
                flying -= tr.key
                // 「停到棋格上时停止模型动画」 — unless it is parked in 待飞, whose own look owns the spin.
                // A captured plane is `InHangar` here, so this is also what stops a plane that was sent
                // home mid-walk from spinning on its pad.
                pieces[tr.key]?.let {
                    applyAnimationState(tr.key, it, isStandby(team, tr.key.second, state))
                    applyFlightSound(tr.key, it, false)
                }
            }
        }
    }

    /** One waypoint-to-waypoint hop, spread over [CELL_STEP_MS]. False once no track is ours any more. */
    private suspend fun playHop(tracks: List<SlideTrack>): Boolean {
        for (frame in 1..CELL_STEP_FRAMES) {
            val t = smoothStep(frame.toFloat() / CELL_STEP_FRAMES)
            var stillOwned = false
            for (tr in tracks) {
                if (generations[tr.key] != tr.generation) continue
                stillOwned = true
                tr.transform.position = Vector3(
                    lerp(tr.from.x, tr.to.x, t),
                    lerp(tr.from.y, tr.to.y, t),
                    lerp(tr.from.z, tr.to.z, t),
                )
                // Banks into the turn over the same beat as the move, taking the short way round.
                tr.transform.eulerAngles =
                    EulerAngles(pitch = 0f, yaw = lerpAngleDegrees(tr.fromYaw, tr.toYaw, t), roll = 0f)
            }
            if (!stillOwned) return false
            delay(MOVE_ANIMATION_FRAME_MS)
        }
        return true
    }

    /** Compact waypoint label for the walk log — cell number, 待飞, or the hangar. */
    private fun describe(state: PieceState): String = when {
        state is PieceState.OnPath && Track.isInStandby(state.value) -> "待飞"
        state is PieceState.OnPath -> state.value.toString()
        else -> "机库"
    }

    /** A cell the plane is passing over: its centre, plus this piece's own settled lateral offset. */
    private fun waypointPosition(team: Team, index: Int, waypoint: PieceState, state: GameState): Vector3 {
        val point = BoardGeometry.positionFor(team, index, waypoint)
        val offset = BoardGeometry.coOccupantOffset(team, index, state)
        return Vector3(point.x + offset.x, PIECE_LIFT_M + offset.y, point.z + offset.z)
    }

    private class SlideTrack(
        val key: Pair<Team, Int>,
        val generation: Int,
        val transform: TransformComponent,
        /** Where the piece ends up once every waypoint has been walked. */
        val settled: Vector3,
        startYaw: Float,
    ) {
        var from: Vector3 = settled
        var to: Vector3 = settled
        var fromYaw: Float = startYaw
        var toYaw: Float = startYaw
    }

    /**
     * Where a piece's pivot belongs, including how pieces that share one printed cell are separated.
     *
     * A piece alone on its cell sits **exactly** on the cell centre. That is not a nicety: the previous
     * version offset every piece by `JITTER[index % 4]` unconditionally, which put a lone plane 1.7cm
     * off centre (29% of the 5.9cm cell pitch) and left two 僚机 stack-mates 2.4cm apart (40% of the
     * pitch). Two planes that far apart read as *neighbouring cells*, so a stack landing on a lone plane
     * looked like it had missed even though the engine had captured correctly — the reported "叠棋2个无法
     * 飞跃单机以及吃单机" was this drawing bug, not a rule bug (`GameEngineTest` covers both rules).
     *
     * Pieces that genuinely share a cell separate by *height* first, because any lateral fan wide enough
     * to tell 5cm planes apart is a large fraction of the cell pitch and reintroduces the ambiguity. A
     * small lateral radius rides along so a stack is still countable from above. The arrangement
     * itself lives in [BoardGeometry.coOccupantOffset] so it is unit-testable without the SDK.
     */
    private fun targetPosition(team: Team, index: Int, state: GameState): Vector3 {
        val piece = state.pieces(team)[index]
        val point = BoardGeometry.positionFor(team, index, piece)
        val lift = if (isStandby(team, index, state)) PIECE_LIFT_M + STANDBY_LIFT_M else PIECE_LIFT_M

        val offset = BoardGeometry.coOccupantOffset(team, index, state)
        return Vector3(point.x + offset.x, lift + offset.y, point.z + offset.z)
    }

    fun detach() {
        // Controllers and resources are not owned by the entity tree, so destroying pivots does not
        // release them — the SDK requires closing audio resources by hand to free their memory.
        pieces.values.forEach {
            it.flightAudio?.stop()
            it.flightAudio?.close()
            it.standbyAudio?.stop()
            it.standbyAudio?.close()
            it.animationControllers.forEach { controller ->
                controller.stop()
                controller.close()
            }
        }
        flightSound?.close()
        standbySound?.close()
        flightSound = null
        standbySound = null
        flightSoundOn.clear()
        animationsOn.clear()
        flying.clear()
        pieces.values.forEach { it.pivot.destroy() }
        pieces.clear()
        pivotToKey.clear()
        animating.clear()
        generations.clear()
        standbyVisual.clear()
        root = null
    }

    /** Matches `Panels.kt`'s swatches so the glow reads as "this team". */
    private fun glowColourFor(team: Team): Color4 = when (team) {
        Team.RED -> Color4(0.91f, 0.20f, 0.20f, 1f)
        Team.YELLOW -> Color4(0.98f, 0.80f, 0.14f, 1f)
        Team.BLUE -> Color4(0.20f, 0.45f, 0.95f, 1f)
        Team.GREEN -> Color4(0.25f, 0.70f, 0.35f, 1f)
    }

    companion object {
        private const val LOAD_BATCH = 4

        /** Longest edge a loaded plane is scaled to, regardless of the source asset's original real-world scale. */
        private const val TARGET_PLANE_SIZE_M = 0.05f

        /** How high a piece's pivot sits above the board surface at rest, before any standby/co-occupant lift. */
        private const val PIECE_LIFT_M = 0.01f

        /**
         * 「飞机飞行时的声音音量太大了调小一点」. `plane_flight.mp3` is a hot master — -9.4 LUFS integrated and
         * clipping at +0.1 dBFS once a move runs 4 cells or more — against `dice_throw.mp3` at -25.2 LUFS,
         * so at unity gain the engine bed buried everything else, and up to four 僚机 planes can sound at
         * once. 0.10 puts the bed roughly 20 dB down, just under the dice cue.
         */
        private const val FLIGHT_VOLUME = 0.10f

        /** `plane_standby.mp3` measures -8.4 LUFS, nearly as hot as the flight bed, but it is a short cue. */
        private const val STANDBY_VOLUME = 0.45f

        /**
         * Where the flight bed starts. 3000ms is frame-aligned to the file's 24ms MP3 grid (frame 125) and
         * lands past the clip's ramp-in, leaving 7032ms — more than the 6944ms worst-case move (7 hops at
         * 992ms), so a single move never needs the loop, which stays on only as a safety net.
         */
        private const val FLIGHT_CUE_MS = 3000L

        /** 待飞 look — the user asked for a 3cm rise plus a scale-up and a highlight. */
        private const val STANDBY_LIFT_M = 0.03f
        private const val STANDBY_SCALE = 1.6f
        private const val HIGHLIGHT_RADIUS_M = 0.032f
        private const val HIGHLIGHT_THICKNESS_M = 0.002f
        private const val HIGHLIGHT_DROP_M = 0.012f

        /**
         * One printed cell per second: 「从棋盘上移动时按照每隔1秒的动画时长进行移动」. A roll of 4 therefore
         * takes 4s and reads as four distinct steps. Every caller awaits [animateMove], so the turn
         * machine, the die hand-off and the tap guards all stretch with it — this is the one knob to
         * turn if the pacing needs changing.
         */
        private const val CELL_STEP_MS = 1000L
        private const val MOVE_ANIMATION_FRAME_MS = 16L
        private const val CELL_STEP_FRAMES = (CELL_STEP_MS / MOVE_ANIMATION_FRAME_MS).toInt()
    }
}
