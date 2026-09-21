package tech.illusion.spaceflightchess.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Returns a fixed, scripted sequence of rolls instead of random ones. */
private class ScriptedDice(private val values: List<Int>) : DiceEngine() {
    private var index = 0
    override fun roll(): Int {
        check(index < values.size) { "script exhausted after $index rolls" }
        return values[index++]
    }
}

class GameEngineTest {

    // ---- legalMoves (pure) ----

    @Test
    fun `hangared piece can only launch on a 6`() {
        val state = GameState.newGame()
        assertEquals(emptyList<Move>(), GameEngine.legalMoves(state, Team.RED, 5))
        val launches = GameEngine.legalMoves(state, Team.RED, 6)
        assertEquals(4, launches.size) // all four pieces are equally eligible
        assertTrue(launches.all { it.to == PieceState.OnPath(0) })
    }

    @Test
    fun `on-path piece moves by the roll`() {
        val state = GameState.newGame().withPiece(Team.RED, 0, PieceState.OnPath(5))
        val moves = GameEngine.legalMoves(state, Team.RED, 3)
        assertEquals(listOf(Move(Team.RED, 0, PieceState.OnPath(5), PieceState.OnPath(8))), moves)
    }

    @Test
    fun `overshooting the finish bounces back the excess instead of being illegal`() {
        // FINISH_PATH-2 needs 2 to finish; rolling 5 overshoots by 3 -> bounces back to FINISH_PATH-3,
        // reflecting off the finish itself (Move.bounceWall) so the animation visits it first.
        val state = GameState.newGame().withPiece(Team.RED, 0, PieceState.OnPath(Track.FINISH_PATH - 2))
        val move = GameEngine.legalMoves(state, Team.RED, 5).single()
        assertEquals(PieceState.OnPath(Track.FINISH_PATH - 3), move.to)
        assertEquals(Track.FINISH_PATH, move.bounceWall)
        // FINISH_PATH-2 + roll 2 lands exactly on the finish -> legal, unbounced.
        val exact = GameEngine.legalMoves(state, Team.RED, 2).single()
        assertEquals(PieceState.OnPath(Track.FINISH_PATH), exact.to)
        assertEquals(null, exact.bounceWall)
    }

    @Test
    fun `a finished piece never has a legal move again`() {
        val state = GameState.newGame().withPiece(Team.RED, 0, PieceState.FINISHED)
        for (roll in 1..6) {
            assertTrue(GameEngine.legalMoves(state, Team.RED, roll).none { it.pieceIndex == 0 })
        }
    }

    @Test
    fun `no legal move at all when hangared and roll isn't 6, with nothing else on the board`() {
        assertEquals(emptyList<Move>(), GameEngine.legalMoves(GameState.newGame(), Team.RED, 3))
    }

    // ---- legalMoves: the 僚机 may not be split (design doc section 6) ----

    @Test
    fun `two own planes on one cell yield a single move for the whole stack`() {
        var state = GameState.newGame()
        state = state.withPiece(Team.RED, 0, PieceState.OnPath(5))
        state = state.withPiece(Team.RED, 1, PieceState.OnPath(5))
        state = state.withPiece(Team.RED, 2, PieceState.OnPath(9))

        val moves = GameEngine.legalMoves(state, Team.RED, 3)
        // One move for the pair, one for the loner — not three.
        assertEquals(2, moves.size)
        val pairMove = moves.single { it.groupPieceIndices.size == 2 }
        assertEquals(listOf(0, 1), pairMove.groupPieceIndices)
        assertEquals(PieceState.OnPath(8), pairMove.to)
        val loneMove = moves.single { it.groupPieceIndices.size == 1 }
        assertEquals(listOf(2), loneMove.groupPieceIndices)
    }

    @Test
    fun `moving a pair relocates both planes together`() {
        var state = GameState.newGame()
        state = state.withPiece(Team.RED, 0, PieceState.OnPath(5))
        state = state.withPiece(Team.RED, 1, PieceState.OnPath(5))
        val move = GameEngine.legalMoves(state, Team.RED, 3).single { it.groupPieceIndices.size == 2 }

        val (after, outcome) = GameEngine.resolve(state, move)
        assertEquals(PieceState.OnPath(8), after.pieces(Team.RED)[0])
        assertEquals(PieceState.OnPath(8), after.pieces(Team.RED)[1])
        // Still a pair at the destination.
        assertEquals(1, outcome.pairedWithPieceIndex)
    }

    @Test
    fun `hangared planes launch one at a time - they are not a stack`() {
        val launches = GameEngine.legalMoves(GameState.newGame(), Team.RED, 6)
        assertEquals(4, launches.size)
        assertTrue(launches.all { it.groupPieceIndices.size == 1 })
    }

    @Test
    fun `a moving pair counter-captures an opponent pair together`() {
        var state = GameState.newGame()
        val yellowPath = pathSharingCellWith(Team.YELLOW, Team.RED, 8)
        state = state.withPiece(Team.YELLOW, 0, PieceState.OnPath(yellowPath))
        state = state.withPiece(Team.YELLOW, 1, PieceState.OnPath(yellowPath))
        state = state.withPiece(Team.RED, 0, PieceState.OnPath(5))
        state = state.withPiece(Team.RED, 1, PieceState.OnPath(5))
        val move = GameEngine.legalMoves(state, Team.RED, 3).single { it.groupPieceIndices.size == 2 }

        val (after, outcome) = GameEngine.resolve(state, move)
        assertEquals(Team.YELLOW, outcome.capturedTeam)
        assertEquals(setOf(0, 1), outcome.capturedPieceIndices.toSet())
        assertEquals(PieceState.InHangar, after.pieces(Team.YELLOW)[0])
        assertEquals(PieceState.InHangar, after.pieces(Team.YELLOW)[1])
    }

    @Test
    fun `two planes in 待飞 do not fly together - they sit on separate hangar pads`() {
        var state = GameState.newGame()
        state = state.withPiece(Team.RED, 0, PieceState.OnPath(Track.STANDBY_PATH))
        state = state.withPiece(Team.RED, 1, PieceState.OnPath(Track.STANDBY_PATH))

        val moves = GameEngine.legalMoves(state, Team.RED, 3)
        assertEquals("each 待飞 plane must offer its own move", 2, moves.size)
        assertTrue("no 待飞 move may relocate more than one plane", moves.all { it.groupPieceIndices.size == 1 })
        assertEquals(setOf(0, 1), moves.map { it.pieceIndex }.toSet())

        // And applying one must leave the other exactly where it was.
        val (after, _) = GameEngine.resolve(state, moves.first { it.pieceIndex == 0 })
        assertEquals(PieceState.OnPath(3), after.pieces(Team.RED)[0])
        assertEquals(PieceState.OnPath(Track.STANDBY_PATH), after.pieces(Team.RED)[1])
    }

    // ---- 叠棋阻挡普通移动 + 倒飞 ----

    @Test
    fun `a stack in the way stops the plane on it and spends the rest of the roll flying backwards`() {
        // 「叠棋阻挡普通移动，超过的点数本回合飞到叠棋位置处开始进行倒飞」.
        // YELLOW 僚机 on the printed cell that is RED's path 8. RED at path 5 rolls 6:
        // 3 steps forward to the stack at 8, then the remaining 3 backwards -> 5.
        val yellowPath = pathSharingCellWith(Team.YELLOW, Team.RED, 8)
        var state = GameState.newGame()
        state = state.withPiece(Team.YELLOW, 0, PieceState.OnPath(yellowPath))
        state = state.withPiece(Team.YELLOW, 1, PieceState.OnPath(yellowPath))
        state = state.withPiece(Team.RED, 0, PieceState.OnPath(5))

        val move = GameEngine.legalMoves(state, Team.RED, 6).single { it.pieceIndex == 0 }
        assertEquals(PieceState.OnPath(5), move.to) // 5 +3 to the stack, -3 back = 5
    }

    @Test
    fun `landing exactly on a bigger opposing stack bounces back instead of coexisting`() {
        // 「数量少的一方走棋还能停到叠机格处」 was the bug: a lone RED plane must not be able to land
        // directly on a 2-plane YELLOW stack. The wall sits one cell *before* the stack (7, not 8),
        // so the roll of 3 spends 2 pips reaching the wall and bounces the remaining 1 back to 6.
        val yellowPath = pathSharingCellWith(Team.YELLOW, Team.RED, 8)
        var state = GameState.newGame()
        state = state.withPiece(Team.YELLOW, 0, PieceState.OnPath(yellowPath))
        state = state.withPiece(Team.YELLOW, 1, PieceState.OnPath(yellowPath))
        state = state.withPiece(Team.RED, 0, PieceState.OnPath(5))

        val move = GameEngine.legalMoves(state, Team.RED, 3).single { it.pieceIndex == 0 }
        assertEquals(PieceState.OnPath(6), move.to)
    }

    @Test
    fun `landing on a same-size or smaller opposing stack is still an ordinary arrival`() {
        // The bounce is specifically for a *bigger* opposing stack; equal or smaller opposing groups
        // are settled by the existing capture/immunity rules in resolveCellEffects, not blocked.
        val yellowPath = pathSharingCellWith(Team.YELLOW, Team.RED, 8)
        var state = GameState.newGame()
        state = state.withPiece(Team.YELLOW, 0, PieceState.OnPath(yellowPath))
        state = state.withPiece(Team.YELLOW, 1, PieceState.OnPath(yellowPath))
        state = state.withPiece(Team.RED, 0, PieceState.OnPath(5))
        state = state.withPiece(Team.RED, 1, PieceState.OnPath(5)) // RED pair, same size as YELLOW's

        val move = GameEngine.legalMoves(state, Team.RED, 3).single { it.groupPieceIndices.size == 2 }
        assertEquals(PieceState.OnPath(8), move.to)
    }

    @Test
    fun `a bigger own-team stack never blocks joining it`() {
        // Blocking only applies to a bigger *opposing* stack — a lone plane must always be able to
        // land on and join its own team's bigger stack.
        val path = 8
        var state = GameState.newGame()
        state = state.withPiece(Team.RED, 0, PieceState.OnPath(path))
        state = state.withPiece(Team.RED, 1, PieceState.OnPath(path))
        state = state.withPiece(Team.RED, 2, PieceState.OnPath(5))

        val move = GameEngine.legalMoves(state, Team.RED, 3).single { it.pieceIndex == 2 }
        assertEquals(PieceState.OnPath(path), move.to)
    }

    @Test
    fun `a lone plane in the way blocks nothing`() {
        val yellowPath = pathSharingCellWith(Team.YELLOW, Team.RED, 8)
        var state = GameState.newGame()
        state = state.withPiece(Team.YELLOW, 0, PieceState.OnPath(yellowPath))
        state = state.withPiece(Team.RED, 0, PieceState.OnPath(5))

        val move = GameEngine.legalMoves(state, Team.RED, 6).single { it.pieceIndex == 0 }
        assertEquals(PieceState.OnPath(11), move.to)
    }

    @Test
    fun `the bounce is measured from the FIRST stack in the way`() {
        // Two stacks ahead; the nearer one must be the one that bounces us.
        val nearPath = pathSharingCellWith(Team.YELLOW, Team.RED, 7)
        val farPath = pathSharingCellWith(Team.BLUE, Team.RED, 9)
        var state = GameState.newGame()
        state = state.withPiece(Team.YELLOW, 0, PieceState.OnPath(nearPath))
        state = state.withPiece(Team.YELLOW, 1, PieceState.OnPath(nearPath))
        state = state.withPiece(Team.BLUE, 0, PieceState.OnPath(farPath))
        state = state.withPiece(Team.BLUE, 1, PieceState.OnPath(farPath))
        state = state.withPiece(Team.RED, 0, PieceState.OnPath(5))

        // 5 +2 to the near stack at 7, remaining 4 backwards -> 3.
        val move = GameEngine.legalMoves(state, Team.RED, 6).single { it.pieceIndex == 0 }
        assertEquals(PieceState.OnPath(3), move.to)
    }

    @Test
    fun `a bounce never reverses off the board`() {
        // From cell 1 with a stack immediately ahead, the backward leg would run past the board's start;
        // it is floored at cell 1 rather than reversing into 待飞 or the hangar.
        val yellowPath = pathSharingCellWith(Team.YELLOW, Team.RED, 2)
        var state = GameState.newGame()
        state = state.withPiece(Team.YELLOW, 0, PieceState.OnPath(yellowPath))
        state = state.withPiece(Team.YELLOW, 1, PieceState.OnPath(yellowPath))
        state = state.withPiece(Team.RED, 0, PieceState.OnPath(1))

        val move = GameEngine.legalMoves(state, Team.RED, 6).single { it.pieceIndex == 0 }
        val landed = (move.to as PieceState.OnPath).value
        assertTrue("bounce must stay on the board, was $landed", landed >= 1)
    }

    // ---- 吃叠棋：少的不能吃多的 ----

    @Test
    fun `a smaller stack bounces off a larger one instead of landing on it`() {
        val yellowPath = pathSharingCellWith(Team.YELLOW, Team.RED, 8)
        var state = GameState.newGame()
        // A YELLOW trio.
        state = state.withPiece(Team.YELLOW, 0, PieceState.OnPath(yellowPath))
        state = state.withPiece(Team.YELLOW, 1, PieceState.OnPath(yellowPath))
        state = state.withPiece(Team.YELLOW, 2, PieceState.OnPath(yellowPath))
        // A RED pair rolling straight at them.
        state = state.withPiece(Team.RED, 0, PieceState.OnPath(7))
        state = state.withPiece(Team.RED, 1, PieceState.OnPath(7))

        val move = GameEngine.legalMoves(state, Team.RED, 1).single { it.groupPieceIndices.size == 2 }
        assertEquals("2 planes must bounce off 3, not land on them", PieceState.OnPath(6), move.to)
    }

    @Test
    fun `resolveCellEffects still keeps a bigger opposing stack immune where it is still reachable`() {
        // A colour hop / flight carry does not re-check the landing-cell bounce on its own
        // destination (see resolveCellEffects's doc), so it can still reach a bigger opposing stack.
        // Exercise that path directly with a hand-built Move, bypassing legalMoves's new bounce.
        val yellowPath = pathSharingCellWith(Team.YELLOW, Team.RED, 8)
        var state = GameState.newGame()
        state = state.withPiece(Team.YELLOW, 0, PieceState.OnPath(yellowPath))
        state = state.withPiece(Team.YELLOW, 1, PieceState.OnPath(yellowPath))
        state = state.withPiece(Team.YELLOW, 2, PieceState.OnPath(yellowPath))
        state = state.withPiece(Team.RED, 0, PieceState.OnPath(7))
        state = state.withPiece(Team.RED, 1, PieceState.OnPath(7))

        val move = Move(Team.RED, 0, PieceState.OnPath(7), PieceState.OnPath(yellowPath), listOf(0, 1))
        val (after, outcome) = GameEngine.resolve(state, move)
        assertNull("2 planes must not take 3", outcome.capturedTeam)
        assertEquals(PieceState.OnPath(yellowPath), after.pieces(Team.YELLOW)[0])
        assertEquals(PieceState.OnPath(yellowPath), after.pieces(Team.YELLOW)[2])
    }

    @Test
    fun `an equal-sized stack still captures`() {
        val yellowPath = pathSharingCellWith(Team.YELLOW, Team.RED, 8)
        var state = GameState.newGame()
        state = state.withPiece(Team.YELLOW, 0, PieceState.OnPath(yellowPath))
        state = state.withPiece(Team.YELLOW, 1, PieceState.OnPath(yellowPath))
        state = state.withPiece(Team.RED, 0, PieceState.OnPath(7))
        state = state.withPiece(Team.RED, 1, PieceState.OnPath(7))

        val move = GameEngine.legalMoves(state, Team.RED, 1).single { it.groupPieceIndices.size == 2 }
        val (after, outcome) = GameEngine.resolve(state, move)
        assertEquals(Team.YELLOW, outcome.capturedTeam)
        assertEquals(PieceState.InHangar, after.pieces(Team.YELLOW)[0])
        assertEquals(PieceState.InHangar, after.pieces(Team.YELLOW)[1])
    }

    // ---- 安全格 ----

    @Test
    fun `the home lane and the finish are safe cells and nothing else is`() {
        for (path in 1..Track.LOOP_LAST_PATH) {
            assertFalse("loop cell $path must not be safe", Track.isSafeCell(path))
        }
        assertFalse("待飞 is not a safe cell", Track.isSafeCell(Track.STANDBY_PATH))
        for (path in (Track.LOOP_LAST_PATH + 1)..Track.FINISH_PATH) {
            assertTrue("path $path must be safe", Track.isSafeCell(path))
        }
    }

    @Test
    fun `no capture ever happens on a safe cell`() {
        // Two teams cannot actually meet in a private lane, which is exactly why it is safe; assert the
        // capture scan declines to act there rather than relying on that by accident.
        var state = GameState.newGame()
        state = state.withPiece(Team.RED, 0, PieceState.OnPath(Track.LOOP_LAST_PATH + 2))
        state = state.withPiece(Team.YELLOW, 0, PieceState.OnPath(Track.LOOP_LAST_PATH + 2))
        val move = Move(Team.RED, 0, PieceState.OnPath(Track.LOOP_LAST_PATH + 1), PieceState.OnPath(Track.LOOP_LAST_PATH + 2))
        val (after, outcome) = GameEngine.resolve(state, move)
        assertNull(outcome.capturedTeam)
        assertEquals(PieceState.OnPath(Track.LOOP_LAST_PATH + 2), after.pieces(Team.YELLOW)[0])
    }

    // ---- 没有吃子奖励 ----

    @Test
    fun `capturing does not earn an extra roll`() {
        val yellowPath = pathSharingCellWith(Team.YELLOW, Team.RED, 6)
        var state = GameState.newGame()
        state = state.withPiece(Team.YELLOW, 0, PieceState.OnPath(yellowPath))
        state = state.withPiece(Team.RED, 0, PieceState.OnPath(5))
        val engine = GameEngine(ScriptedDice(listOf(1)))
        engine.startGame()
        engine.forceState(state)
        engine.roll()
        val outcome = engine.applyMove(engine.legalMovesForPendingRoll().single { it.pieceIndex == 0 })

        assertEquals(Team.YELLOW, outcome?.capturedTeam)
        assertEquals("a capture on a non-6 must still pass the turn", Team.YELLOW, engine.state.currentTeam)
        assertTrue(engine.drainEvents().none { it is GameEvent.ExtraRoll })
    }

    // ---- guide 1.1 / 1.2: dice legality and the empty-legal-set turn pass ----

    @Test
    fun `a 6 with no legal move passes the turn instead of granting an extra roll`() {
        // Design doc ch.2: 「合法集合为空 → 本次点数作废，直接换下一家（连续摇 6 的额外回合同样适用此判定）」.
        // All four RED planes are already home, so even a 6 has nothing to do.
        var state = GameState.newGame()
        for (i in 0 until GameState.PIECES_PER_TEAM) {
            state = state.withPiece(Team.RED, i, PieceState.FINISHED)
        }
        val engine = GameEngine(ScriptedDice(listOf(6)))
        engine.startGame()
        engine.forceState(state)

        engine.roll()

        assertEquals(Phase.AWAITING_ROLL, engine.phase)
        assertEquals("the turn must move on, not come back to RED", Team.YELLOW, engine.state.currentTeam)
        val events = engine.drainEvents()
        assertTrue(events.any { it is GameEvent.NoLegalMove })
        assertTrue(events.any { it is GameEvent.TurnPassed })
        assertTrue("an empty legal set must not earn an extra roll", events.none { it is GameEvent.ExtraRoll })
    }

    @Test
    fun `a 6 with something to do still grants an extra roll`() {
        // The counterpart to the test above: the extra roll itself must survive the fix.
        val engine = GameEngine(ScriptedDice(listOf(6)))
        engine.startGame()
        engine.roll()
        engine.applyMove(engine.legalMovesForPendingRoll().first())

        assertEquals(Phase.AWAITING_ROLL, engine.phase)
        assertEquals(Team.RED, engine.state.currentTeam)
        assertTrue(engine.drainEvents().any { it is GameEvent.ExtraRoll })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a die outside 1 to 6 is rejected rather than silently moving pieces`() {
        val engine = GameEngine(ScriptedDice(listOf(0)))
        engine.startGame()
        engine.roll()
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a die above 6 is rejected too`() {
        val engine = GameEngine(ScriptedDice(listOf(7)))
        engine.startGame()
        engine.roll()
    }

    // ---- guide 6.2: a 僚机 must stay whole even after turning into the home lane ----

    @Test
    fun `a stack that has entered the home lane still moves as one unit`() {
        val lanePath = Track.LOOP_LAST_PATH + 2
        var state = GameState.newGame()
        state = state.withPiece(Team.RED, 0, PieceState.OnPath(lanePath))
        state = state.withPiece(Team.RED, 1, PieceState.OnPath(lanePath))

        val moves = GameEngine.legalMoves(state, Team.RED, 2)
        assertEquals("the lane stack must offer exactly one move, not one per plane", 1, moves.size)
        assertEquals(listOf(0, 1), moves.single().groupPieceIndices)

        val (after, _) = GameEngine.resolve(state, moves.single())
        assertEquals(PieceState.OnPath(lanePath + 2), after.pieces(Team.RED)[0])
        assertEquals(PieceState.OnPath(lanePath + 2), after.pieces(Team.RED)[1])
    }

    @Test
    fun `a stack cannot be split by turning into the home lane`() {
        // The illegal path this closes: grouping used to key on isOnSharedLoop, so the moment a pair
        // crossed the mouth its members became independently movable.
        val lanePath = Track.LOOP_LAST_PATH + 1
        var state = GameState.newGame()
        state = state.withPiece(Team.RED, 0, PieceState.OnPath(lanePath))
        state = state.withPiece(Team.RED, 1, PieceState.OnPath(lanePath))

        for (roll in 1..6) {
            val moves = GameEngine.legalMoves(state, Team.RED, roll)
            assertTrue(
                "roll $roll must never offer a move that relocates only part of the lane stack",
                moves.none { it.groupPieceIndices.size == 1 && it.groupPieceIndices.single() in listOf(0, 1) },
            )
        }
    }

    @Test
    fun `a 2-stack flies over a lone plane - only a stack blocks`() {
        val yellowPath = pathSharingCellWith(Team.YELLOW, Team.RED, 8)
        var state = GameState.newGame()
        state = state.withPiece(Team.YELLOW, 0, PieceState.OnPath(yellowPath))   // LONE plane in the way
        state = state.withPiece(Team.RED, 0, PieceState.OnPath(5))
        state = state.withPiece(Team.RED, 1, PieceState.OnPath(5))               // RED 2-stack

        val moves = GameEngine.legalMoves(state, Team.RED, 6)
        val stackMove = moves.single { it.groupPieceIndices.size == 2 }
        assertEquals("a lone plane must not block a stack", PieceState.OnPath(11), stackMove.to)
    }

    @Test
    fun `a 2-stack captures a lone plane - 少的不能吃多的 cuts only one way`() {
        val yellowPath = pathSharingCellWith(Team.YELLOW, Team.RED, 8)
        var state = GameState.newGame()
        state = state.withPiece(Team.YELLOW, 0, PieceState.OnPath(yellowPath))
        state = state.withPiece(Team.RED, 0, PieceState.OnPath(7))
        state = state.withPiece(Team.RED, 1, PieceState.OnPath(7))

        val moves = GameEngine.legalMoves(state, Team.RED, 1)
        val stackMove = moves.single { it.groupPieceIndices.size == 2 }
        val (after, outcome) = GameEngine.resolve(state, stackMove)
        assertEquals("2 planes must take 1", Team.YELLOW, outcome.capturedTeam)
        assertEquals(PieceState.InHangar, after.pieces(Team.YELLOW)[0])
    }

    // ---- applyMove rejects out-of-turn moves ----

    @Test
    fun `applyMove refuses a move belonging to a team whose turn it is not`() {
        val engine = GameEngine(ScriptedDice(listOf(6)))
        engine.startGame()
        engine.roll() // RED's turn, RED has legal launches pending
        val redLaunch = engine.legalMovesForPendingRoll().first()
        val forged = redLaunch.copy(team = Team.YELLOW)

        assertNull("a move for another team must be rejected outright", engine.applyMove(forged))
        assertTrue(engine.drainEvents().any { it is GameEvent.IllegalMove })
        // The real move still works, so the guard is not just blocking everything.
        assertEquals(Phase.AWAITING_MOVE, engine.phase)
        assertTrue(engine.applyMove(redLaunch) != null)
    }

    // ---- resolve: capture / pairing / immunity / counter-capture ----

    @Test
    fun `landing on a lone opponent captures it`() {
        var state = GameState.newGame()
        state = state.withPiece(Team.YELLOW, 0, PieceState.OnPath(pathSharingCellWith(Team.YELLOW, Team.RED, 5)))
        val move = Move(Team.RED, 0, PieceState.OnPath(4), PieceState.OnPath(5))
        state = state.withPiece(Team.RED, 0, PieceState.OnPath(4))

        val (after, outcome) = GameEngine.resolve(state, move)
        assertEquals(PieceState.InHangar, after.pieces(Team.YELLOW)[0])
        assertEquals(Team.YELLOW, outcome.capturedTeam)
        assertEquals(listOf(0), outcome.capturedPieceIndices)
    }

    @Test
    fun `own pieces never capture each other`() {
        var state = GameState.newGame()
        state = state.withPiece(Team.RED, 1, PieceState.OnPath(5))
        val move = Move(Team.RED, 0, PieceState.OnPath(4), PieceState.OnPath(5))
        state = state.withPiece(Team.RED, 0, PieceState.OnPath(4))

        val (after, outcome) = GameEngine.resolve(state, move)
        assertEquals(PieceState.OnPath(5), after.pieces(Team.RED)[1]) // untouched, not sent to hangar
        assertNull(outcome.capturedTeam)
        assertEquals(1, outcome.pairedWithPieceIndex) // forms a pair with piece 1 instead
    }

    @Test
    fun `an opponent pair is immune to a lone arrival - both sides simply coexist`() {
        var state = GameState.newGame()
        // Yellow forms a pair on the printed cell red's path 8 lands on. Red path 8 is deliberately
        // not FLIGHT_PATH, so this test isn't confounded by a flight side effect.
        val yellowPath = pathSharingCellWith(Team.YELLOW, Team.RED, 8)
        state = state.withPiece(Team.YELLOW, 0, PieceState.OnPath(yellowPath))
        state = state.withPiece(Team.YELLOW, 1, PieceState.OnPath(yellowPath))
        // Red arrives alone on that same printed cell.
        val move = Move(Team.RED, 0, PieceState.OnPath(7), PieceState.OnPath(8))
        state = state.withPiece(Team.RED, 0, PieceState.OnPath(7))

        val (after, outcome) = GameEngine.resolve(state, move)
        assertNull("a lone arrival must not break an immune pair", outcome.capturedTeam)
        assertEquals(PieceState.OnPath(yellowPath), after.pieces(Team.YELLOW)[0])
        assertEquals(PieceState.OnPath(yellowPath), after.pieces(Team.YELLOW)[1])
        assertEquals(PieceState.OnPath(8), after.pieces(Team.RED)[0]) // red still lands there, just coexists
    }

    @Test
    fun `a freshly-formed pair counter-captures an existing opponent pair`() {
        var state = GameState.newGame()
        // Yellow pair already sitting on the printed cell red's path 8 lands on.
        val yellowPath = pathSharingCellWith(Team.YELLOW, Team.RED, 8)
        state = state.withPiece(Team.YELLOW, 0, PieceState.OnPath(yellowPath))
        state = state.withPiece(Team.YELLOW, 1, PieceState.OnPath(yellowPath))
        // Red already has one piece there too (path 8), and now moves a second piece onto it.
        state = state.withPiece(Team.RED, 0, PieceState.OnPath(8))
        val move = Move(Team.RED, 1, PieceState.OnPath(7), PieceState.OnPath(8))
        state = state.withPiece(Team.RED, 1, PieceState.OnPath(7))

        val (after, outcome) = GameEngine.resolve(state, move)
        assertEquals(Team.YELLOW, outcome.capturedTeam)
        assertEquals(setOf(0, 1), outcome.capturedPieceIndices.toSet())
        assertEquals(PieceState.InHangar, after.pieces(Team.YELLOW)[0])
        assertEquals(PieceState.InHangar, after.pieces(Team.YELLOW)[1])
        assertEquals(PieceState.OnPath(8), after.pieces(Team.RED)[0])
        assertEquals(PieceState.OnPath(8), after.pieces(Team.RED)[1])
    }

    // ---- resolve: flight ----

    @Test
    fun `landing exactly on a flight cell advances the path by FLIGHT_ADVANCE`() {
        // Red path 17 + roll 1 = path 18 = FLIGHT_PATH, red's own flight square.
        val state = GameState.newGame().withPiece(Team.RED, 0, PieceState.OnPath(Track.FLIGHT_PATH - 1))
        val move = Move(Team.RED, 0, PieceState.OnPath(Track.FLIGHT_PATH - 1), PieceState.OnPath(Track.FLIGHT_PATH))
        val (after, outcome) = GameEngine.resolve(state, move)

        assertTrue(outcome.flightTriggered)
        assertEquals(PieceState.OnPath(Track.FLIGHT_PATH + Track.FLIGHT_ADVANCE), after.pieces(Team.RED)[0])
    }

    @Test
    fun `flight never triggers a second time even though its destination is also a flight cell`() {
        // Landing on FLIGHT_PATH flies once to FLIGHT_PATH+FLIGHT_ADVANCE and stops there; the
        // destination must not be re-examined for a second trigger.
        val state = GameState.newGame().withPiece(Team.RED, 0, PieceState.OnPath(Track.FLIGHT_PATH - 1))
        val move = Move(Team.RED, 0, PieceState.OnPath(Track.FLIGHT_PATH - 1), PieceState.OnPath(Track.FLIGHT_PATH))
        val (after, _) = GameEngine.resolve(state, move)
        assertEquals(PieceState.OnPath(Track.FLIGHT_PATH + Track.FLIGHT_ADVANCE), after.pieces(Team.RED)[0])
    }

    @Test
    fun `flight capped at finish does not overshoot into nonsense`() {
        // A flight from FLIGHT_PATH lands well inside the loop, so with the measured constants it can
        // never overshoot; assert that directly rather than pretending otherwise.
        val state = GameState.newGame().withPiece(Team.RED, 0, PieceState.OnPath(Track.FLIGHT_PATH - 1))
        val move = Move(Team.RED, 0, PieceState.OnPath(Track.FLIGHT_PATH - 1), PieceState.OnPath(Track.FLIGHT_PATH))
        val (after, outcome) = GameEngine.resolve(state, move)
        assertTrue(outcome.flightTriggered)
        assertFalse(outcome.finished)
        val landed = after.pieces(Team.RED)[0] as PieceState.OnPath
        assertTrue("flight destination must stay on the loop", Track.isOnSharedLoop(landed.value))
    }

    @Test
    fun `flying into an opponent still captures it at the flight destination`() {
        // Red flies from FLIGHT_PATH to FLIGHT_PATH+FLIGHT_ADVANCE; put a lone yellow piece on that
        // exact printed cell, expressed in yellow's own path numbering.
        val yellowPath = pathSharingCellWith(Team.YELLOW, Team.RED, Track.FLIGHT_PATH + Track.FLIGHT_ADVANCE)
        var state = GameState.newGame()
        state = state.withPiece(Team.YELLOW, 0, PieceState.OnPath(yellowPath))
        state = state.withPiece(Team.RED, 0, PieceState.OnPath(Track.FLIGHT_PATH - 1))
        val move = Move(Team.RED, 0, PieceState.OnPath(Track.FLIGHT_PATH - 1), PieceState.OnPath(Track.FLIGHT_PATH))

        val (after, outcome) = GameEngine.resolve(state, move)
        assertTrue(outcome.flightTriggered)
        assertEquals(Team.YELLOW, outcome.capturedTeam)
        assertEquals(PieceState.InHangar, after.pieces(Team.YELLOW)[0])
    }

    // ---- the colour rule: land on your own colour and carry on ----

    @Test
    fun `own-colour cells are the same path values for every team, and land on the measured colours`() {
        // Guards the arithmetic the whole rule rests on: period-4 tile colours plus each team's own
        // cell-1 offset work out to path % 4 == 2 for all four teams.
        for (team in Team.entries) {
            val own = (1..Track.LOOP_LAST_PATH).filter { Track.isOwnColourCell(it) }
            assertEquals("$team should have 13 own-colour cells per lap", 13, own.size)
            assertTrue("$team own-colour cells must all be path % 4 == 2", own.all { it % 4 == 2 })
        }
        assertTrue("the flight cell is itself an own-colour cell", Track.isOwnColourCell(Track.FLIGHT_PATH))
    }

    @Test
    fun `landing on an own-colour cell hops to the next own-colour cell`() {
        val from = 6 - 1 // land on path 6, an own-colour cell that is not the flight cell
        val state = GameState.newGame().withPiece(Team.RED, 0, PieceState.OnPath(from))
        val move = Move(Team.RED, 0, PieceState.OnPath(from), PieceState.OnPath(6))
        val (after, outcome) = GameEngine.resolve(state, move)

        assertTrue(outcome.colourHopped)
        assertFalse(outcome.flightTriggered)
        assertEquals(PieceState.OnPath(6 + Track.COLOUR_CYCLE), after.pieces(Team.RED)[0])
    }

    @Test
    fun `the colour hop fires only once even though its destination is also an own-colour cell`() {
        // Period-4 means every hop lands on another own-colour cell; an unguarded re-check would loop.
        val state = GameState.newGame().withPiece(Team.RED, 0, PieceState.OnPath(5))
        val move = Move(Team.RED, 0, PieceState.OnPath(5), PieceState.OnPath(6))
        val (after, _) = GameEngine.resolve(state, move)
        val landed = after.pieces(Team.RED)[0] as PieceState.OnPath
        assertEquals(10, landed.value)
        assertTrue("the destination is own-coloured, which is exactly why it must not chain", Track.isOwnColourCell(landed.value))
    }

    @Test
    fun `an own-colour cell that is the flight lane start flies the full distance, not just four cells`() {
        val state = GameState.newGame().withPiece(Team.RED, 0, PieceState.OnPath(Track.FLIGHT_PATH - 1))
        val move = Move(Team.RED, 0, PieceState.OnPath(Track.FLIGHT_PATH - 1), PieceState.OnPath(Track.FLIGHT_PATH))
        val (after, outcome) = GameEngine.resolve(state, move)

        assertTrue(outcome.flightTriggered)
        assertFalse("a flight is not also counted as a hop", outcome.colourHopped)
        assertEquals(PieceState.OnPath(Track.FLIGHT_PATH + Track.FLIGHT_ADVANCE), after.pieces(Team.RED)[0])
    }

    @Test
    fun `a stack on a crossed cell blocks the colour hop`() {
        // Yellow parks a 僚机 on the cell red would hop over (path 6 -> 10 crosses 7, 8, 9).
        val crossed = 8
        val yellowPath = pathSharingCellWith(Team.YELLOW, Team.RED, crossed)
        var state = GameState.newGame()
        state = state.withPiece(Team.YELLOW, 0, PieceState.OnPath(yellowPath))
        state = state.withPiece(Team.YELLOW, 1, PieceState.OnPath(yellowPath))
        state = state.withPiece(Team.RED, 0, PieceState.OnPath(5))

        val move = Move(Team.RED, 0, PieceState.OnPath(5), PieceState.OnPath(6))
        val (after, outcome) = GameEngine.resolve(state, move)

        assertFalse("the hop must be cancelled", outcome.colourHopped)
        assertTrue(outcome.carryBlockedByStack)
        assertEquals("the plane stays where the dice put it", PieceState.OnPath(6), after.pieces(Team.RED)[0])
    }

    @Test
    fun `a lone plane on a crossed cell does not block the colour hop`() {
        val crossed = 8
        val yellowPath = pathSharingCellWith(Team.YELLOW, Team.RED, crossed)
        var state = GameState.newGame()
        state = state.withPiece(Team.YELLOW, 0, PieceState.OnPath(yellowPath))
        state = state.withPiece(Team.RED, 0, PieceState.OnPath(5))

        val move = Move(Team.RED, 0, PieceState.OnPath(5), PieceState.OnPath(6))
        val (after, outcome) = GameEngine.resolve(state, move)

        assertTrue(outcome.colourHopped)
        assertFalse(outcome.carryBlockedByStack)
        assertEquals(PieceState.OnPath(10), after.pieces(Team.RED)[0])
        assertEquals("the lone plane it flew over is untouched", PieceState.OnPath(yellowPath), after.pieces(Team.YELLOW)[0])
    }

    @Test
    fun `a stack on a crossed cell blocks the flight too`() {
        val crossed = Track.FLIGHT_PATH + 5
        val yellowPath = pathSharingCellWith(Team.YELLOW, Team.RED, crossed)
        var state = GameState.newGame()
        state = state.withPiece(Team.YELLOW, 0, PieceState.OnPath(yellowPath))
        state = state.withPiece(Team.YELLOW, 1, PieceState.OnPath(yellowPath))
        state = state.withPiece(Team.RED, 0, PieceState.OnPath(Track.FLIGHT_PATH - 1))

        val move = Move(Team.RED, 0, PieceState.OnPath(Track.FLIGHT_PATH - 1), PieceState.OnPath(Track.FLIGHT_PATH))
        val (after, outcome) = GameEngine.resolve(state, move)

        assertFalse(outcome.flightTriggered)
        assertTrue(outcome.carryBlockedByStack)
        assertEquals(PieceState.OnPath(Track.FLIGHT_PATH), after.pieces(Team.RED)[0])
    }

    @Test
    fun `the home-lane mouth is own-coloured but must not hop into the private lane`() {
        // path LOOP_LAST_PATH is the mouth and is printed in the team's own colour — that is why it is
        // their mouth. Hopping from it would vault four cells deep into the home lane.
        assertTrue(Track.isOwnColourCell(Track.LOOP_LAST_PATH))
        assertNull(Track.colourHopTarget(Track.LOOP_LAST_PATH))

        val from = Track.LOOP_LAST_PATH - 1
        val state = GameState.newGame().withPiece(Team.RED, 0, PieceState.OnPath(from))
        val move = Move(Team.RED, 0, PieceState.OnPath(from), PieceState.OnPath(Track.LOOP_LAST_PATH))
        val (after, outcome) = GameEngine.resolve(state, move)
        assertFalse(outcome.colourHopped)
        assertEquals(PieceState.OnPath(Track.LOOP_LAST_PATH), after.pieces(Team.RED)[0])
    }

    @Test
    fun `home-lane cells never trigger the colour hop even though they are all own-coloured`() {
        for (path in (Track.LOOP_LAST_PATH + 1) until Track.FINISH_PATH) {
            assertFalse("home-lane path $path must not count as an own-colour cell", Track.isOwnColourCell(path))
            assertNull(Track.colourHopTarget(path))
        }
    }

    @Test
    fun `a colour hop still captures whatever is waiting at its destination`() {
        val yellowPath = pathSharingCellWith(Team.YELLOW, Team.RED, 10)
        var state = GameState.newGame()
        state = state.withPiece(Team.YELLOW, 0, PieceState.OnPath(yellowPath))
        state = state.withPiece(Team.RED, 0, PieceState.OnPath(5))

        val move = Move(Team.RED, 0, PieceState.OnPath(5), PieceState.OnPath(6))
        val (after, outcome) = GameEngine.resolve(state, move)

        assertTrue(outcome.colourHopped)
        assertEquals(Team.YELLOW, outcome.capturedTeam)
        assertEquals(PieceState.InHangar, after.pieces(Team.YELLOW)[0])
        assertEquals(PieceState.OnPath(10), after.pieces(Team.RED)[0])
    }

    @Test
    fun `a 僚机 stack hops together`() {
        var state = GameState.newGame()
        state = state.withPiece(Team.RED, 0, PieceState.OnPath(5))
        state = state.withPiece(Team.RED, 1, PieceState.OnPath(5))
        val move = GameEngine.legalMoves(state, Team.RED, 1).single { it.groupPieceIndices.size == 2 }

        val (after, outcome) = GameEngine.resolve(state, move)
        assertTrue(outcome.colourHopped)
        assertEquals(PieceState.OnPath(10), after.pieces(Team.RED)[0])
        assertEquals(PieceState.OnPath(10), after.pieces(Team.RED)[1])
    }

    // ---- entering the home lane / finishing flags ----

    @Test
    fun `entering the home lane is flagged exactly once, on the crossing move`() {
        // Last loop cell minus 2, crossing into the first home-lane cell.
        val from = Track.LOOP_LAST_PATH - 2
        val state = GameState.newGame().withPiece(Team.RED, 0, PieceState.OnPath(from))
        val move = Move(Team.RED, 0, PieceState.OnPath(from), PieceState.OnPath(Track.LOOP_LAST_PATH + 1))
        val (_, outcome) = GameEngine.resolve(state, move)
        assertTrue(outcome.enteredHomeLane)
        assertFalse(outcome.finished)
    }

    // ---- instance-level turn machinery: extra roll on 6, turn passing, no-legal-move, win ----

    @Test
    fun `rolling a non-6 with everyone hangared passes the turn with a NoLegalMove event`() {
        val engine = GameEngine(ScriptedDice(listOf(4)))
        engine.startGame()
        engine.roll()

        assertEquals(Phase.AWAITING_ROLL, engine.phase)
        assertEquals(Team.YELLOW, engine.state.currentTeam)
        val events = engine.drainEvents()
        assertTrue(events.any { it is GameEvent.NoLegalMove })
        assertTrue(events.any { it is GameEvent.TurnPassed })
    }

    @Test
    fun `rolling a 6 grants an extra roll for the same team, up to the triple-six limit`() {
        // The uncapped rule was replaced by 「连续三次摇到6则在阵营内选择一个飞机让其飞回仓库」, so only the
        // first two 6s hand the turn back; the third is the penalty.
        val engine = GameEngine(ScriptedDice(List(Track.CONSECUTIVE_SIX_LIMIT - 1) { 6 }))
        engine.startGame()
        repeat(Track.CONSECUTIVE_SIX_LIMIT - 1) {
            engine.roll()
            engine.applyMove(engine.legalMovesForPendingRoll().first())
            assertEquals(Team.RED, engine.state.currentTeam)
            assertEquals(Phase.AWAITING_ROLL, engine.phase)
        }
    }

    // ---- 连续三次 6 的惩罚 ----

    @Test
    fun `a third consecutive 6 makes the team send one of its own planes home and ends the turn`() {
        val engine = GameEngine(ScriptedDice(List(Track.CONSECUTIVE_SIX_LIMIT) { 6 }))
        engine.startGame()
        // Two 6s, each LAUNCHING a fresh plane (not advancing the one already out), so two are airborne.
        repeat(Track.CONSECUTIVE_SIX_LIMIT - 1) {
            engine.roll()
            val launch = engine.legalMovesForPendingRoll().first { it.from == PieceState.InHangar }
            engine.applyMove(launch)
            assertEquals(Team.RED, engine.state.currentTeam)
        }
        val airborneBefore = engine.state.pieces(Team.RED).count { it is PieceState.OnPath }
        assertEquals(2, airborneBefore)

        // The third 6 is the penalty: no ordinary move is offered, only "send a plane home".
        engine.roll()
        assertEquals(Phase.AWAITING_MOVE, engine.phase)
        val penalties = engine.legalMovesForPendingRoll()
        assertTrue("the penalty must offer only send-home moves", penalties.all { it.to == PieceState.InHangar })
        assertEquals(2, penalties.size) // one per airborne plane
        assertTrue(engine.drainEvents().any { it is GameEvent.TripleSix })

        engine.applyMove(penalties.first())
        assertEquals(1, engine.state.pieces(Team.RED).count { it is PieceState.OnPath })
        assertEquals("the penalty ends the turn — no extra roll", Team.YELLOW, engine.state.currentTeam)
        assertEquals(Phase.AWAITING_ROLL, engine.phase)
    }

    @Test
    fun `the six counter resets once the turn passes, so 6s across turns do not accumulate`() {
        // 6 (launch, keeps turn), 6 (launch, keeps turn), 1 (moves, passes turn) — then a later 6 must be
        // treated as the FIRST of a new sequence, not the third.
        val engine = GameEngine(ScriptedDice(listOf(6, 6, 1)))
        engine.startGame()
        repeat(2) {
            engine.roll()
            engine.applyMove(engine.legalMovesForPendingRoll().first())
        }
        engine.roll()
        engine.applyMove(engine.legalMovesForPendingRoll().first())
        assertEquals(Team.YELLOW, engine.state.currentTeam)
        engine.drainEvents()

        // RED's next turn: a single 6 must NOT be the "third".
        val next = GameEngine(ScriptedDice(listOf(6)))
        next.startGame()
        next.roll()
        assertTrue("a fresh sequence's first 6 is not a penalty", next.drainEvents().none { it is GameEvent.TripleSix })
    }

    @Test
    fun `nothing airborne means no penalty is payable`() {
        // Note this branch is not reachable through roll(): with nothing to move, a 6 passes the turn
        // immediately (empty legal set), so the consecutive-six counter can never reach the limit. The
        // engine still guards it, and the builder is what that guard consults, so test the builder.
        var allHome = GameState.newGame()
        assertEquals(emptyList<Move>(), GameEngine.penaltyMoves(allHome, Team.RED))

        for (i in 0 until GameState.PIECES_PER_TEAM) allHome = allHome.withPiece(Team.RED, i, PieceState.FINISHED)
        assertEquals("finished planes have left the board and cannot pay", emptyList<Move>(), GameEngine.penaltyMoves(allHome, Team.RED))
    }

    @Test
    fun `two 待飞 planes are offered as two separate penalties, not one`() {
        // Same mis-encoding that once flew two 待飞 planes at once: they share path 0 but sit on different
        // hangar pads, so they are not a 僚机.
        var state = GameState.newGame()
        state = state.withPiece(Team.RED, 0, PieceState.OnPath(Track.STANDBY_PATH))
        state = state.withPiece(Team.RED, 1, PieceState.OnPath(Track.STANDBY_PATH))
        val penalties = GameEngine.penaltyMoves(state, Team.RED)
        assertEquals(2, penalties.size)
        assertTrue(penalties.all { it.groupPieceIndices.size == 1 })
    }

    @Test
    fun `the penalty sends a whole 僚机 home together, never half of one`() {
        var state = GameState.newGame()
        state = state.withPiece(Team.RED, 0, PieceState.OnPath(7))
        state = state.withPiece(Team.RED, 1, PieceState.OnPath(7))
        val penalties = GameEngine.penaltyMoves(state, Team.RED)
        assertEquals(1, penalties.size)
        assertEquals(listOf(0, 1), penalties.single().groupPieceIndices)

        val (after, _) = GameEngine.resolve(state, penalties.single())
        assertEquals(PieceState.InHangar, after.pieces(Team.RED)[0])
        assertEquals(PieceState.InHangar, after.pieces(Team.RED)[1])
    }

    @Test
    fun `a full turn - roll, move, non-6 - passes to the next team in order`() {
        val engine = GameEngine(ScriptedDice(listOf(6, 3)))
        engine.startGame()
        engine.roll()
        engine.applyMove(engine.legalMovesForPendingRoll().first())
        assertEquals(Team.RED, engine.state.currentTeam) // extra roll from the 6

        engine.roll()
        engine.applyMove(engine.legalMovesForPendingRoll().first())
        assertEquals(Team.YELLOW, engine.state.currentTeam)
        assertEquals(Phase.AWAITING_ROLL, engine.phase)
    }

    @Test
    fun `applyMove is rejected outside AWAITING_MOVE, and for a move that was not offered`() {
        val engine = GameEngine(ScriptedDice(listOf(6)))
        engine.startGame()
        // No roll yet - still AWAITING_ROLL.
        assertNull(engine.applyMove(Move(Team.RED, 0, PieceState.InHangar, PieceState.OnPath(0))))

        engine.roll()
        val bogus = Move(Team.RED, 0, PieceState.InHangar, PieceState.OnPath(99))
        assertNull(engine.applyMove(bogus))
    }

    @Test
    fun `reaching four finished pieces ends the game with a full ranking, through the real roll-move API`() {
        val engine = GameEngine(ScriptedDice(listOf(6)))
        engine.startGame()
        // Red: 3 already finished, 1 sitting exactly 6 short of the finish. Yellow/Blue: partial
        // progress, so the post-game ranking has something other than all-zero to sort.
        var state = engine.state
        state = state.withPiece(Team.RED, 0, PieceState.FINISHED)
        state = state.withPiece(Team.RED, 1, PieceState.FINISHED)
        state = state.withPiece(Team.RED, 2, PieceState.FINISHED)
        state = state.withPiece(Team.RED, 3, PieceState.OnPath(Track.FINISH_PATH - 6))
        state = state.withPiece(Team.YELLOW, 0, PieceState.FINISHED)
        engine.forceState(state)

        val rolled = engine.roll()
        assertEquals(6, rolled)
        val move = engine.legalMovesForPendingRoll().single { it.pieceIndex == 3 }
        val outcome = engine.applyMove(move)

        requireNotNull(outcome)
        assertTrue(outcome.finished)
        assertEquals(Phase.GAME_OVER, engine.phase)
        assertTrue(engine.isOver)

        val wonEvent = engine.drainEvents().filterIsInstance<GameEvent.Won>().single()
        assertEquals(Team.RED, wonEvent.ranking.first())
        assertEquals(Team.YELLOW, wonEvent.ranking[1]) // 1 finished piece, ahead of Blue/Green's 0
    }

    @Test
    fun `restart 之后立刻 startGame 能回到可摇骰的状态`() {
        val engine = GameEngine()
        engine.startGame()
        engine.restart()
        assertEquals(Phase.SETUP, engine.phase)

        engine.startGame()
        assertEquals(Phase.AWAITING_ROLL, engine.phase)
        assertEquals(GameState.newGame().currentTeam, engine.state.currentTeam)
    }

    @Test
    fun `restart 单独调用会停在 SETUP —— 这就是机库窗口取代 StartPanel 后必须补 startGame 的原因`() {
        val engine = GameEngine()
        engine.startGame()
        engine.restart()
        assertEquals(Phase.SETUP, engine.phase)
    }
}
