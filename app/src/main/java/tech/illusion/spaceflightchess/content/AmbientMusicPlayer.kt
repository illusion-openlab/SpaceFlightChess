package tech.illusion.spaceflightchess.content

import android.util.Log
import com.pico.spatial.core.ecs.AmbientAudioComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.LoadType
import com.pico.spatial.core.ecs.audio.AudioPlayerController
import com.pico.spatial.core.ecs.resource.AudioResource

private const val TAG = "SpaceFlightChess"

/**
 * Looping game background music — 「游戏背景音」. Owns exactly one loop, started once when the scene
 * attaches and stopped on teardown; there is no per-turn or per-move state to react to.
 *
 * [com.pico.spatial.core.ecs.ObjectAudioComponent] (what the dice and planes use) is the wrong
 * component here: its volume falls off with listener distance, so background music would fade as the
 * player leaned toward the board — exactly the kind of positional dependence music should not have.
 * `AmbientAudioComponent` is documented for precisely this case ("configure non-narrative sounds such
 * as background music... to prevent changes in the listener's viewpoint from affecting the listening
 * experience"), with `AmbientOrientationMode.ORIENTATION_ONLY` — its default — explicitly recommended
 * for "pure background music" in VR, as opposed to `POSITION_AND_ORIENTATION` for MR-anchored sources.
 * So this takes the component's default constructor rather than choosing a mode.
 */
class AmbientMusicPlayer {

    private var emitter: Entity? = null
    private var musicResource: AudioResource? = null
    private var musicAudio: AudioPlayerController? = null

    val isAttached: Boolean get() = emitter != null

    fun attachTo(parent: Entity) {
        if (isAttached) return
        val entity = Entity().also(parent::addChild)
        emitter = entity
        entity.components.set(AmbientAudioComponent(volume = MUSIC_VOLUME))

        val clip = runCatching {
            AudioResource.load(name = "BackgroundMusic", path = "asset://audio/background_music.mp3", loadType = LoadType.FROM_ASSETS)
        }.onFailure { Log.e(TAG, "audio load failed: asset://audio/background_music.mp3", it) }.getOrNull()
        musicResource = clip ?: return

        val controller = entity.prepareAudio(clip)
        // The SDK returns an invalid controller rather than throwing, so an unusable one would otherwise
        // present simply as "there is no music" with no signal as to why.
        if (!controller.valid) {
            Log.e(TAG, "audio prepare invalid: background music")
            return
        }
        musicAudio = controller
        controller.setLoop(true)
        controller.play()
    }

    /** Pauses the loop in place (position preserved) — for backgrounding, not for stopping the
     * loop for good; use [detach] for that. */
    fun pause() {
        musicAudio?.pause()
    }

    /** Resumes a loop previously paused by [pause]. A no-op if nothing was ever attached. */
    fun resume() {
        musicAudio?.resume()
    }

    fun detach() {
        musicAudio?.stop()
        musicAudio?.close()
        musicResource?.close()
        musicAudio = null
        musicResource = null
        emitter?.destroy()
        emitter = null
    }

    private companion object {
        /**
         * The clip measures -14.0 LUFS integrated, close to `plane_flight.mp3`'s raw loudness and hotter
         * than every other cue in the mix (see `PlaneAiTest`-adjacent audio notes in AGENTS.md for the
         * sibling measurements). Music should sit *under* the gameplay cues, not compete with them, so
         * this attenuates by ~18.4dB — to an effective ≈-32.4 LUFS, below the dice cue (~-25.2 LUFS
         * effective) and the attenuated flight bed (~-29.4 LUFS effective). A judged starting point, not
         * a measured optimum; adjust by ear.
         */
        const val MUSIC_VOLUME = 0.12f
    }
}
