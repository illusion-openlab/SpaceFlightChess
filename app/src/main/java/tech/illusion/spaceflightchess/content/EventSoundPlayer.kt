package tech.illusion.spaceflightchess.content

import android.util.Log
import com.pico.spatial.core.ecs.AmbientAudioComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.LoadType
import com.pico.spatial.core.ecs.audio.AudioPlayerController
import com.pico.spatial.core.ecs.resource.AudioResource

private const val TAG = "SpaceFlightChess"

/**
 * One-shot game-event cues: 「吃子时使用音效」／「玩家胜利结束时使用音效」／「玩家失败时使用音效」.
 *
 * `AmbientAudioComponent`, not `ObjectAudioComponent` (what the dice and planes use) — same reasoning
 * as [AmbientMusicPlayer]: these are cues the player must hear regardless of where they are looking
 * or standing on the board, not positional effects tied to a specific cell.
 *
 * Each cue gets its own child entity/component rather than three resources sharing one, matching how
 * [PlanePieceRenderer.attachSounds] and [DieRenderer] wire up their own multiple clips — nothing in
 * this project's audio code relies on, or has verified, one component driving more than one prepared
 * resource at a time.
 */
class EventSoundPlayer {

    private var root: Entity? = null
    private var captureAudio: AudioPlayerController? = null
    private var victoryAudio: AudioPlayerController? = null
    private var defeatAudio: AudioPlayerController? = null
    private val resources = mutableListOf<AudioResource>()

    val isAttached: Boolean get() = root != null

    fun attachTo(parent: Entity) {
        if (isAttached) return
        val entity = Entity().also(parent::addChild)
        root = entity
        captureAudio = emitter(entity, "CaptureHit", "audio/capture_hit.mp3", CAPTURE_VOLUME)
        victoryAudio = emitter(entity, "Victory", "audio/victory.mp3", VICTORY_VOLUME)
        defeatAudio = emitter(entity, "Defeat", "audio/defeat.mp3", DEFEAT_VOLUME)
    }

    /** 「吃子时使用该音效」 — fire once per move that captured at least one opponent's plane. */
    fun playCapture() = replay(captureAudio)

    /** 「玩家胜利结束时使用该音效」 — the human's own team was the one that finished first. */
    fun playVictory() = replay(victoryAudio)

    /** 「玩家失败时使用该音效」 — the game ended with some other team finishing first. */
    fun playDefeat() = replay(defeatAudio)

    /** Restarts from frame 0 every time, same as [PlanePieceRenderer]'s one-shot 待飞 cue: a cue that
     * fires again mid-playback (e.g. two captures close together) should retrigger, not queue. */
    private fun replay(controller: AudioPlayerController?) {
        controller?.seekTo(0)
        controller?.play()
    }

    fun detach() {
        listOf(captureAudio, victoryAudio, defeatAudio).forEach {
            it?.stop()
            it?.close()
        }
        resources.forEach { it.close() }
        resources.clear()
        captureAudio = null
        victoryAudio = null
        defeatAudio = null
        root?.destroy()
        root = null
    }

    private fun emitter(parent: Entity, name: String, path: String, volume: Float): AudioPlayerController? {
        val clip = runCatching {
            AudioResource.load(name = name, path = "asset://$path", loadType = LoadType.FROM_ASSETS)
        }.onFailure { Log.e(TAG, "audio load failed: asset://$path", it) }.getOrNull() ?: return null
        resources += clip

        val entity = Entity().also(parent::addChild)
        entity.components.set(AmbientAudioComponent(volume = volume))
        val controller = entity.prepareAudio(clip)
        // The SDK returns an invalid controller rather than throwing, so an unusable one would
        // otherwise present simply as "the cue never plays" with no signal as to why.
        if (!controller.valid) {
            Log.e(TAG, "audio prepare invalid: $name")
            return null
        }
        return controller
    }

    private companion object {
        /**
         * Raw measured loudness (ffmpeg `loudnorm` integrated): capture_hit -9.7 LUFS, victory -9.7
         * LUFS, defeat -11.4 LUFS — all considerably hotter than the ambient background music bed
         * (-14.0 LUFS raw, mixed down to ~0.12). These volumes are a judged starting point, not a
         * measured optimum, same honesty as [AmbientMusicPlayer.MUSIC_VOLUME]: capture is a frequent
         * in-play cue so it is damped more; victory/defeat each fire once, at the end of the game, with
         * nothing else competing, so they are left close to full loudness.
         */
        const val CAPTURE_VOLUME = 0.35f
        const val VICTORY_VOLUME = 0.6f
        const val DEFEAT_VOLUME = 0.6f
    }
}
