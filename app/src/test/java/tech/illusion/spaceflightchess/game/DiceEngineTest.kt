package tech.illusion.spaceflightchess.game

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class DiceEngineTest {

    @Test
    fun `roll is always in 1 to 6`() {
        val dice = DiceEngine(Random(seed = 42))
        repeat(1000) {
            val value = dice.roll()
            assertTrue("rolled $value", value in 1..6)
        }
    }

    @Test
    fun `same seed produces the same sequence - deterministic for tests`() {
        val a = DiceEngine(Random(seed = 7)).let { d -> List(20) { d.roll() } }
        val b = DiceEngine(Random(seed = 7)).let { d -> List(20) { d.roll() } }
        assertTrue(a == b)
    }
}
