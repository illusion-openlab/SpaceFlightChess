package tech.illusion.spaceflightchess.game

import kotlin.random.Random

/**
 * A six-sided die. `open` so tests can drive [GameEngine] with a scripted subclass that returns an
 * exact sequence — a seeded [Random] only fixes the *sequence*, not which value comes out on a
 * given call, which turn-passing/extra-roll tests need to pin down precisely.
 */
open class DiceEngine(private val random: Random = Random.Default) {
    open fun roll(): Int = random.nextInt(1, FACES + 1)

    companion object {
        /** A die has six faces. [GameEngine.roll] asserts against this, per guide 1.1. */
        const val FACES = 6
    }
}
