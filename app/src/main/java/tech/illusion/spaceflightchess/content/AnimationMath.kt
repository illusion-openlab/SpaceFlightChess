package tech.illusion.spaceflightchess.content

/** Shared by [BoardStage]'s seat-change animation and [DieRenderer]'s roll tumble. */
internal fun lerp(from: Float, to: Float, t: Float): Float = from + (to - from) * t

/** Ease-in-out so an animated transform settles instead of stopping abruptly. */
internal fun smoothStep(t: Float): Float = t * t * (3f - 2f * t)

/** Interpolates between two angles the *short* way (e.g. 270 -> 0 turns +90, not -270). */
internal fun lerpAngleDegrees(from: Float, to: Float, t: Float): Float {
    var delta = (to - from) % 360f
    if (delta > 180f) delta -= 360f
    if (delta < -180f) delta += 360f
    return from + delta * t
}
