package tech.illusion.spaceflightchess.game

/** Where a single plane currently is. */
sealed class PieceState {
    /** Not yet launched. Needs [Track.LAUNCH_ROLL] to enter play, at path 0 on its own start cell. */
    data object InHangar : PieceState()

    /** `value` is the path described in [Track] — 0..[Track.FINISH_PATH]. */
    data class OnPath(val value: Int) : PieceState()

    companion object {
        val FINISHED = OnPath(Track.FINISH_PATH)
    }
}
