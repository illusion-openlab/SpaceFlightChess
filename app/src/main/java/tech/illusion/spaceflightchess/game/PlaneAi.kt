package tech.illusion.spaceflightchess.game

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Move chooser for the AI seats: a one-ply search whose leaf evaluator prices **everything in one
 * unit — the path unit (one printed cell of progress, so an average roll is 3.5 of them)**.
 *
 * Keeping a single unit is the whole design. It means every weight below is an argument that can be
 * checked against a rule constant ("the drawn flight lane is [Track.FLIGHT_ADVANCE] = 12 units, so a
 * flight is worth more than three average rolls"), and it means gain and loss are directly
 * comparable: `score = ourValue − Σ weightₒ × theirValue − risk + position`.
 *
 * Three structural decisions carry most of the strength, and each replaces something the previous
 * AI (frozen as `LegacyPlaneAi` in the test sources) got wrong:
 *
 *  1. **It evaluates the state [GameEngine.resolve] returns, never [Move.to].** [Move.to] is the
 *     *pre-carry* destination; the own-colour hop ([Track.colourHopTarget], +4) and the drawn flight
 *     lane ([Track.applyFlight], +12) are both applied inside [GameEngine.resolve] *after* the [Move]
 *     was built, as are captures. So a move to path 14 and a move to path 14 that immediately hops to
 *     18 are the *same* [Move] and the old comparator could not tell them apart — `AGENTS.md` records
 *     it as a known defect ("按飞行前的落点打分，所以可能主动放弃一次 +12 飞行收益"). Scoring the resolved
 *     state values both carries for free, with no trigger rule re-implemented anywhere in this file.
 *  2. **Threats are measured by asking the engine, not modelled.** [expectedCaptureLoss] runs
 *     [GameEngine.legalMoves] + [GameEngine.resolve] for every opponent and every face and reads
 *     [MoveOutcome.capturedPieces]. That inherits — exactly, with zero duplicated rules — the
 *     size-based immunity of 「少的不能吃多的」, a 倒飞 bounce arriving *backwards* onto us, an arrival
 *     carried in by a hop or the flight lane from 4 or 12 cells back, the exact-count finish rule, and
 *     "a boxed-in opponent is harmless because [GameEngine.roll] just passes the turn".
 *  3. **One hangar price does the work of five hand-written rules.** Because [pieceValue] charges
 *     [HANGAR_COST] for a hangared plane, the same subtraction prices a launch (+[HANGAR_COST] +
 *     [STANDBY_VALUE]), a capture we make (victim's path + [HANGAR_COST]), a capture we suffer, and
 *     the triple-6 penalty — symmetrically, in path units, with no special cases. There is therefore
 *     **no** separate launch bonus, capture bonus, re-entry-tempo constant or deployment table; those
 *     are three different names for this one number, and having them as separate terms is how an
 *     evaluator ends up counting the same effect twice.
 *
 * **Strength, measured** with the `SelfPlay` harness driving the real [GameEngine] (seat-swapped
 * pairs, seeded dice). Against the strongest alternative evaluator considered, over 600 games split
 * into a 300-game tuning range and a 300-game range never used for tuning: **336-264, 56.0%**
 * (56.3% tuning / 55.7% held out — so it is not seed-fitting), z ≈ 2.9. Against `LegacyPlaneAi` on
 * the held-out range: **259-41, 86.3%**, finished pieces 1634 vs 968. A mirror match of this
 * evaluator against itself splits exactly 50/50 with identical finished-piece counts, which pins both
 * determinism and the absence of seat asymmetry, and no game in any match hit the harness's roll cap.
 * Worst realistic decision (4 candidates, all 16 planes airborne, three opponents to scan) costs
 * **well under a millisecond** (0.56-0.79 ms across runs on this machine), against the ~0.4 s
 * `animateMove` already spends per AI move.
 *
 * Framework-free and pure — no Spatial SDK, no Android, no live engine, no state between calls — same
 * split as the rest of the `game` package.
 */
object PlaneAi {

    // ---------------------------------------------------------------------------------------------
    // Piece values. Unit: one path unit == one printed cell of progress. Winning needs
    // 4 * Track.FINISH_PATH = 228 of them (WinDetector.winner requires all four planes finished).
    // ---------------------------------------------------------------------------------------------

    /**
     * What a hangared plane is worth: far less than nothing. The single most important number here,
     * and the one first principles get badly wrong.
     *
     * "A launch spends a 6 that could have advanced 6 cells" suggests ~4, and the cheap estimate is
     * not merely imprecise — it loses: a 60-game probe scored 38.3% at 10 against the same opponent
     * this evaluator beats at 56%. The true cost is far higher for three compounding reasons: the win
     * needs **all four** planes home, so a hangared plane is ~57 units of mandatory work that has not
     * started and is gated behind a 1-in-6 [Track.LAUNCH_ROLL]; the team's rolls have fewer planes to
     * land on, so more of them are wasted outright; and a hangared plane can neither capture nor
     * block. Measured plateau at the final weights (300 games each): 20 → 56.0%, 26 → 56.3%,
     * 32 → 56.7%. A plateau that flat is a sign the metric is insensitive up there, not that 26 is
     * exact — treat anything in the twenties as equivalent.
     *
     * Note this is the *only* place the launch/capture/penalty economy is priced — see the class doc.
     */
    private const val HANGAR_COST = 26.0

    /**
     * 待飞 ([Track.STANDBY_PATH]) is worth a little more than nothing and much less than one cell.
     * The plane is off the shared loop so [Track.ringIndexOrNull] returns null for it and the capture
     * scan can never see it, and it is one roll from any of cells 1..6 — but it has banked no
     * distance, and *all* of the value of having launched is already in [HANGAR_COST].
     *
     * Deliberately small, and measured: raising it to 5.0 costs 10 points of win rate (52.0% → 41.7%
     * over 300 games), because a 待飞 plane valued above cell 1 makes the AI reluctant to actually fly
     * it onto the board. Dropping it to 0.0 costs nothing measurable, so 2.0 is the top of its range.
     */
    private const val STANDBY_VALUE = 2.0

    /**
     * Paid on top of the path value for a plane in its private home lane. The lane and the finish are
     * the only [Track.isSafeCell] cells, so turning in converts revocable progress into permanent
     * progress and stops [expectedCaptureLoss] charging that plane ever again.
     *
     * Sized as the couple of turns of accumulated capture risk a lone ring plane would still face —
     * [expectedCaptureLoss] prices exactly one opponent round, and this is the discounted tail of all
     * the rounds it cannot see. Measured flat from 4 to 8 (56.0% / 56.3%) and worse at 12 (54.3%), and
     * 12 shows exactly the symptom to watch for: too high and the AI turns in a 1-unit lane entry
     * ahead of a much better advance.
     */
    private const val LANE_SAFETY = 8.0

    /**
     * On top of [LANE_SAFETY] and the path value, for a finished plane. Finished pieces are the only
     * thing [WinDetector] counts; a finished plane is also the only state immune to the triple-6
     * penalty ([GameEngine.penaltyMoves] filters [Track.isFinished] but *not* the home lane, so a lane
     * plane can still be sent home); and it stops competing for the team's rolls, which the
     * exact-count rule makes a real cost. Measured (300 games each, at the pre-final weights, so read
     * the shape rather than the levels): 0 → 48.3%, 12 → 52.0%, 20 → 53.0%, 28 → 52.0%.
     */
    private const val FINISH_BONUS = 20.0

    /** Ending the game freezes the result, so nothing may outbid it. Makes the exact-count last step a hard preference. */
    private const val WIN_BONUS = 1_000.0

    // ---------------------------------------------------------------------------------------------
    // Weights. All in path units, so "0.35" means "worth about a third of one printed cell".
    // ---------------------------------------------------------------------------------------------

    /**
     * How much one of an opponent's path units counts against one of ours. A strict race would use
     * ~1/3 (three opponents, only one of whom can win); 1.0 each would be pure spite. 0.55 is
     * deliberately above the fair share, because the plane we can actually reach is usually the one
     * nearest and most dangerous to us.
     *
     * Measured, and unusually sharp for this term: 0.35 → 52.0%, 0.55 → 56.3%, 0.75 → 52.7%. The
     * reason it matters at all is that the opponent half of the differential is *constant across
     * candidates* unless a move captures — so what this really governs is when a capture is worth a
     * detour and which of two available captures to prefer.
     */
    private const val OPPONENT_SHARE = 0.55

    /** Only one opponent can win, so setting back the leader is worth more than setting back the tail. */
    private const val LEADER_BIAS = 0.50

    /**
     * Expected capture loss is weighted **above** face value on purpose. [expectedCaptureLoss] sees
     * exactly one opponent round, but a threatened cell tends to stay threatened: the opponent that
     * could not reach us this turn is closer next turn. Measured at the final weights: 1.1 → 54.3%,
     * 1.25 → 56.3%, 1.45 → 55.7%, 1.7 → 54.7%; and zeroing it scored 43.3% on a 60-game probe, the
     * single most expensive thing that can be done to this evaluator. That is the evidence that this
     * rule set — with no safe cells anywhere on the shared ring — really is a risk-management game.
     *
     * This is also the difficulty dial: drop it toward 0 for a weaker AI (at the cost of looking
     * careless rather than merely beatable).
     */
    private const val RISK_W = 1.25

    /**
     * Opponents act in sequence, so a threat three seats away is likelier to have been pre-empted by
     * the intervening moves. Partial compensation for scanning all three opponents against the same
     * static position. Measured: 0.7 → 56.0%, 0.85 → 56.3%, 1.0 (no decay) → 54.0%.
     */
    private const val THREAT_DECAY = 0.85

    /**
     * What a 僚机 is worth *beyond* what the other terms already see, which is the only thing it may
     * be paid for here. The tempo half of stacking (two planes advancing per roll, a carry counted
     * once per member — a flight taken by a 2-stack is 24 units) is already priced exactly by
     * [lookahead], because [GameEngine.resolve] folds every [Move.groupPieceIndices] member onto both
     * the destination and the carry destination and the lookahead reads the resolved state. The
     * one-round half of the immunity is already priced exactly by [expectedCaptureLoss], because
     * `resolveCellEffects` implements 「少的不能吃多的」 and the threat scan runs through
     * [GameEngine.resolve]. Paying for either of those here as well is the double-count this file
     * exists to avoid: a "tempo doubler" bonus stacked on a resolved-state lookahead charges the same
     * doubled pips twice.
     *
     * What is left, and all this pays for: immunity that holds *every* turn rather than only the next
     * one, and the standing to capture a group its own size. Measured at the final weights:
     * 6 → 55.7%, 8 → 56.3%, 10 → 56.0%, 13 → 55.3%; zeroing it scored 43.3% on a 60-game probe.
     *
     * Loop cells only. A lane stack gains nothing (the lane is already safe) and its real cost — two
     * planes now needing one shared exact count — is already visible to [lookahead] as faces with no
     * legal move.
     */
    private const val STACK_BONUS = 8.0

    /**
     * Per member past the second. Small, and honestly not measurable against the opponents tried
     * (0.0, 0.5 and 2.0 all land inside one standard error), but it prices a real ladder: a 3-stack is
     * immune to every 2-stack, so size buys strictly more than the flat bonus.
     */
    private const val STACK_EXTRA_MEMBER = 0.5

    /**
     * A 僚机 blocks **every** team's ordinary movement, our own trailing planes included — `AGENTS.md`
     * records that as a deliberate implementation choice ("阻挡对所有队伍的叠棋一视同仁（包括自家叠棋）"),
     * and only landing on the stack *exactly* is safe, because that is an arrival rather than a
     * passage. So the same expected-倒飞 arithmetic that would credit a blockade against an opponent is
     * charged against us, per piece, which is what stops [STACK_BONUS] from turning into
     * self-blockade. Measured: switching it off costs ~3 points (49.3% against 52.0% over 300 games
     * at the pre-final weights); at the final weights 0.2 / 0.35 / 0.6 are indistinguishable
     * (56.3% / 56.3% / 56.7%), so 0.35 is kept for the reason below rather than for strength.
     *
     * Damped well below face value because a blockade's bite is avoidable — whoever is blocked can
     * usually move a different plane — and because [lookahead] already prices our blocked options
     * exactly, one ply deep.
     *
     * The mirror-image term, *credit* for our stacks bouncing opponents, was built and measured and
     * then **deleted**: it changed no decision at all across 300 games (169-131 with and without) while
     * costing ~150 extra [GameEngine.destinationAfterBlocking] calls per candidate. See the class doc
     * on what a self-play harness should watch if a future opponent starts fortifying.
     */
    private const val SELF_BLOCK_W = 0.35

    /**
     * Discount on our own next roll. Three opponents act in between, so it is not banked — but it is
     * still one of the two most valuable terms here: zeroing it costs ~4 points (48.3% against 52.0%
     * over 300 games at the pre-final weights), and at the final weights 0.3 → 54.3%, 0.4 → 56.3%,
     * 0.5 → 55.7%. That is a consequence of how much [lookahead] subsumes — see its own doc.
     */
    private const val LOOKAHEAD_W = 0.40

    /**
     * ...unless this roll was a [Track.LAUNCH_ROLL], in which case [GameEngine] hands us the very next
     * roll and no opponent moves in between, so next-roll value is nearly banked.
     *
     * Honest status: reasoning, not evidence — 0.4, 0.75 and 1.0 all produced *identical* results on
     * a 60-game probe, because the detection is sound but conservative (see [chooseMove]) and fires
     * only on turns that already have a launch available.
     */
    private const val LOOKAHEAD_W_EXTRA_ROLL = 0.75

    /**
     * What a die face with **no** legal move costs, inside [lookahead]. A wasted turn is not worth
     * zero, it is worth about minus one average roll, and the exact-count finish rule makes wasted
     * turns easy to walk into: a plane at home-lane path `p` moves only on the single face
     * `Track.FINISH_PATH - p`, so a team parked entirely in the lane stalls. Priced at the mean face
     * for exactly that reason.
     *
     * Honest status: a correctness guard rather than a strength term — 0.0, 2.0, 3.5 and 6.0 all
     * produced identical results over 300 games, i.e. it never decided a move in any measured game.
     * Kept because the position it guards against (lane-locked, or every plane behind one stack) is
     * real, rare, and unrecoverable when it happens.
     */
    private const val DEAD_FACE_PIPS = 3.5

    /** 2+ own planes on one cell is a 僚机 ([Move.groupPieceIndices]). */
    private const val STACK_MIN = 2

    /** Scores are compared as milli-path-units so float noise can never decide a move. See [Candidate]. */
    private const val SCORE_TICKS = 1_000.0

    /**
     * Picks a move for the team that owns [legalMoves].
     *
     * The signature is deliberately unchanged: `BoardStage` and the `SelfPlay` test harness both hold
     * `PlaneAi::chooseMove` as a two-argument function value, and a third parameter — even a defaulted
     * one — stops that reference adapting where the function type has to be *inferred*
     * (`Team.entries.associateWith { PlaneAi::chooseMove }` stops compiling).
     *
     * **The triple-6 penalty set needs no special branch.** Those are ordinary [Move]s with
     * `to == PieceState.InHangar` ([GameEngine.penaltyMoves]), so the same evaluator scores them and
     * the least-negative one wins. That is strictly better than the old "surrender the least advanced
     * plane" rule, and it is better *for three separate reasons that all fall out of the one currency*:
     * a whole 僚机 goes home for one penalty, so the group's members are all charged (a 2-stack on
     * path 5 costs 2 × (5 + 26) = 62 while a lone plane on path 8 costs 34); a plane that is about to
     * be captured anyway is cheap to give up, because giving it up also removes its
     * [expectedCaptureLoss]; and a 待飞 plane is the cheapest of all, which is what the hand-written
     * rule was reaching for.
     *
     * `actsAgain` is a **sound one-way** test for "the engine will hand us the next roll": a hangared
     * plane can only move on a [Track.LAUNCH_ROLL], so a launch in the candidate set proves the roll
     * was a 6. It cannot prove the converse (a 6 with every plane airborne looks like any other roll),
     * so the failure mode is the conservative discount, never the optimistic one. A penalty set
     * correctly reports false: the engine ends the turn after a penalty regardless of the 6 that
     * caused it.
     */
    fun chooseMove(state: GameState, legalMoves: List<Move>): Move {
        require(legalMoves.isNotEmpty()) { "chooseMove requires at least one legal move" }
        // No decision to make, and the evaluator is the whole cost of an AI turn.
        if (legalMoves.size == 1) return legalMoves.first()
        val me = legalMoves.first().team
        require(legalMoves.all { it.team == me }) { "chooseMove expects the moves of a single team" }

        // Derived once, from the position as it stands, then held fixed while every candidate is
        // scored. Recomputing per candidate would let a move be judged on a yardstick it moved itself
        // and would make the candidates incomparable with each other.
        val weights = opponentWeights(state, me)
        val actsAgain = legalMoves.any { it.from == PieceState.InHangar }
        val base = positionValue(state, me, weights, actsAgain)

        var best = candidate(state, legalMoves[0], me, weights, actsAgain, base)
        for (index in 1 until legalMoves.size) {
            val next = candidate(state, legalMoves[index], me, weights, actsAgain, base)
            if (next.beats(best)) best = next
        }
        return best.move
    }

    /**
     * Every candidate with the score [chooseMove] maximises, best first — in path units, so "15.5"
     * reads as "worth fifteen and a half cells of progress". For tuning tests and for `BoardStage`'s
     * existing `Log.e(TAG, ...)` habit, so a questionable AI choice can be diagnosed from a logcat
     * capture instead of being re-derived by hand.
     */
    internal fun debugScores(state: GameState, legalMoves: List<Move>): List<Pair<Move, Double>> {
        if (legalMoves.isEmpty()) return emptyList()
        val me = legalMoves.first().team
        val weights = opponentWeights(state, me)
        val actsAgain = legalMoves.any { it.from == PieceState.InHangar }
        val base = positionValue(state, me, weights, actsAgain)
        return legalMoves
            .map { move ->
                val after = GameEngine.resolve(state, move).first
                move to (positionValue(after, me, weights, actsAgain) - base)
            }
            .sortedByDescending { it.second }
    }

    private fun candidate(
        state: GameState,
        move: Move,
        me: Team,
        weights: Map<Team, Double>,
        actsAgain: Boolean,
        base: Double,
    ): Candidate {
        // The RESOLVED state: captures, the colour hop and the flight lane are already applied here.
        val after = GameEngine.resolve(state, move).first
        val gain = positionValue(after, me, weights, actsAgain) - base
        return Candidate(
            move = move,
            score = (gain * SCORE_TICKS).roundToLong(),
            landedPath = pathOf(after.pieces(me)[move.pieceIndex]),
        )
    }

    private class Candidate(val move: Move, val score: Long, val landedPath: Int)

    /**
     * Strict "replaces the incumbent" test, written as an explicit `>` chain rather than a
     * `compareBy`/`maxWith` pair on purpose: `AGENTS.md` records that this file once shipped
     * `compareByDescending(...).thenByDescending(...)` fed to `maxWithOrNull`, a combination that
     * selects the *worst* element, so the AI reliably played its worst move. A spelled-out comparison
     * cannot be inverted by that mistake again.
     *
     * The chain is total for the move shapes the engine emits, so neither float noise nor the caller's
     * ordering can decide anything:
     *  1. score, quantised to milli-path-units, so two arithmetically equal positions reached by
     *     different float paths cannot pick different winners;
     *  2. the further-advanced landing, read from the **resolved** state rather than [Move.to] — which
     *     also keeps the documented "advance whoever leads" behaviour, since two planes advancing the
     *     same number of cells with nothing else to separate them score identically in every term;
     *  3. the lower [Move.pieceIndex]. [GameEngine.legalMoves] emits at most one move per stack cell,
     *     one per solo piece and one per hangared plane, each with a **distinct** representative index,
     *     so this key is already decisive for any list the engine produced and no "position in the
     *     input list" key is needed. Should a caller ever hand over two moves for the same piece, the
     *     `>`-only test means the earlier one keeps the tie, so the result is still a function of the
     *     list rather than of hash or float order.
     */
    private fun Candidate.beats(incumbent: Candidate): Boolean = when {
        score != incumbent.score -> score > incumbent.score
        landedPath != incumbent.landedPath -> landedPath > incumbent.landedPath
        else -> move.pieceIndex < incumbent.move.pieceIndex
    }

    // ---------------------------------------------------------------------------------------------
    // Leaf evaluation
    // ---------------------------------------------------------------------------------------------

    private fun positionValue(
        state: GameState,
        me: Team,
        weights: Map<Team, Double>,
        actsAgain: Boolean,
    ): Double {
        var value = positional(state, me, weights)
        value -= RISK_W * expectedCaptureLoss(state, me)
        value -= SELF_BLOCK_W * blockedPathUnits(state, me)
        value += stackBonus(state, me)
        value += (if (actsAgain) LOOKAHEAD_W_EXTRA_ROLL else LOOKAHEAD_W) * lookahead(state, me, weights)
        if (WinDetector.winner(state) == me) value += WIN_BONUS
        return value
    }

    /**
     * Progress, differenced against the opponents. This one term prices every capture in *both*
     * directions and the triple-6 penalty, because a captured plane lands in the hangar and
     * [pieceValue] already knows what that costs.
     */
    private fun positional(state: GameState, me: Team, weights: Map<Team, Double>): Double {
        var value = teamValue(state, me)
        for (team in Team.entries) {
            if (team == me) continue
            value -= weights.getValue(team) * teamValue(state, team)
        }
        return value
    }

    private fun teamValue(state: GameState, team: Team): Double = state.pieces(team).sumOf { pieceValue(it) }

    /**
     * One plane's worth in path units. Branch order matters: [Track.isFinished] is `>=`-based so it
     * must come first, and the 待飞 test must come before the plain loop value because path 0 is not
     * one cell of progress.
     */
    private fun pieceValue(piece: PieceState): Double {
        val path = (piece as? PieceState.OnPath)?.value ?: return -HANGAR_COST
        return when {
            Track.isFinished(path) -> path + LANE_SAFETY + FINISH_BONUS
            Track.isInHomeLane(path) -> path + LANE_SAFETY
            Track.isInStandby(path) -> STANDBY_VALUE
            else -> path.toDouble()
        }
    }

    /** What it costs us if this plane is sent home — its value plus the hangar it lands in. */
    private fun captureLoss(piece: PieceState): Double = pieceValue(piece) + HANGAR_COST

    /**
     * Per-opponent weight: [OPPONENT_SHARE] tilted towards whoever is closest to winning. Per-piece
     * progress is floored at zero so a team with everything still in the hangar (negative
     * [teamValue]) cannot invert the tilt, and the leader is floored at 1.0 so the opening position
     * does not divide by zero.
     */
    private fun opponentWeights(state: GameState, me: Team): Map<Team, Double> {
        val progress = Team.entries.associateWith { team ->
            state.pieces(team).sumOf { max(0.0, pieceValue(it)) }
        }
        val leader = max(1.0, Team.entries.maxOf { progress.getValue(it) })
        return Team.entries.filter { it != me }.associateWith { team ->
            val standing = progress.getValue(team) / leader
            OPPONENT_SHARE * ((1.0 - LEADER_BIAS) + LEADER_BIAS * 2.0 * standing)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Risk: what we expect to lose before our next turn, asked of the engine rather than modelled
    // ---------------------------------------------------------------------------------------------

    /**
     * Expected path units lost to capture before we act again.
     *
     * Per opponent and per face this asks [GameEngine.legalMoves] what that opponent could do and
     * [GameEngine.resolve] what it would cost us, so every awkward part of the rules is the engine's
     * answer and not a second implementation that could drift: size-based immunity in both directions
     * (our 僚机 is only in danger from a group at least as big, and a 3-stack is safe from a 2-stack),
     * a 倒飞 bounce landing *behind* the opponent onto us, an arrival carried in by a hop or the flight
     * lane, and a launch to 待飞 threatening nobody.
     *
     * Two deliberate refinements over the obvious version:
     *
     *  - **Per-plane accumulation, capped at certainty.** Threat is accumulated per *piece* and capped
     *    at 1.0, because two opponents cannot both send the same plane home. (Honest status: the cap
     *    never actually bound in 300 measured games — 0.6, 1.0 and 3.0 gave identical results — so it
     *    is an invariant guard, not a source of strength.)
     *  - **The extra roll a 6 grants is charged.** A 6 hands the opponent another roll, and that
     *    second roll is priced as one more average face. This costs no extra engine calls (it reuses
     *    the victim sets already computed) and is worth 3 points of win rate: switching it off drops
     *    56.3% to 53.3%. What it still misses is the chain that *starts* with a launch — the newly
     *    launched plane threatens only that opponent's own cells 1..6, six of the fifty cells of a lap,
     *    which is why the cheap version is enough.
     *
     * The remaining approximation is that each opponent is assumed to take its most damaging move
     * against *us*, although all three actually move in sequence and split their attention.
     * [THREAT_DECAY] pays for part of the sequencing. The pessimism about intent turns out to be
     * calibrated rather than paranoid, and that was measured: replacing "the most damaging reply" with
     * "the reply that maximises the opponent's own position" changed the result in not one game out of
     * 300. Under a hangar price of 26 a capture is nearly always the opponent's best move too, so the
     * two models pick the same reply.
     */
    private fun expectedCaptureLoss(state: GameState, me: Team): Double {
        // Nothing of ours is on the shared loop, so nothing of ours can be taken.
        if (state.pieces(me).none { Track.ringIndexOrNull(me, it) != null }) return 0.0

        val threat = DoubleArray(GameState.PIECES_PER_TEAM)
        // Reused across opponents: all six slots are rewritten for each one before they are read.
        val victimsByFace = arrayOfNulls<List<Int>>(DiceEngine.FACES + 1)
        val order = Team.entries
        val seat = order.indexOf(me)
        for (step in 1 until order.size) {
            val opponent = order[(seat + step) % order.size]
            if (!canReachSharedLoop(state, opponent)) continue

            for (face in 1..DiceEngine.FACES) {
                var worst = 0.0
                var victims: List<Int> = emptyList()
                for (reply in GameEngine.legalMoves(state, opponent, face)) {
                    val taken = GameEngine.resolve(state, reply).second.capturedPieces[me] ?: continue
                    var loss = 0.0
                    for (index in taken) loss += captureLoss(state.pieces(me)[index])
                    if (loss > worst) {
                        worst = loss
                        victims = taken
                    }
                }
                victimsByFace[face] = victims
            }

            val share = THREAT_DECAY.pow(step - 1) / DiceEngine.FACES
            for (face in 1..DiceEngine.FACES) {
                victimsByFace[face]?.forEach { threat[it] += share }
            }
            // The second roll a 6 buys, priced as one more ordinary face.
            val chainShare = share / (DiceEngine.FACES - 1)
            for (face in 1 until DiceEngine.FACES) {
                victimsByFace[face]?.forEach { threat[it] += chainShare }
            }
        }

        var total = 0.0
        state.pieces(me).forEachIndexed { index, piece ->
            if (threat[index] <= 0.0) return@forEachIndexed
            total += min(1.0, threat[index]) * captureLoss(piece)
        }
        return total
    }

    /**
     * Whether [team] has a plane that could stand on a shared-loop cell after one roll: 待飞, or a loop
     * cell short of its own mouth. A hangared plane can only reach 待飞 (which captures nothing), and a
     * plane at [Track.LOOP_LAST_PATH] or beyond can only move into its own private lane.
     */
    private fun canReachSharedLoop(state: GameState, team: Team): Boolean =
        state.pieces(team).any { it is PieceState.OnPath && it.value in Track.STANDBY_PATH until Track.LOOP_LAST_PATH }

    // ---------------------------------------------------------------------------------------------
    // 僚机: what the stack is worth, and what standing in front of our own planes costs
    // ---------------------------------------------------------------------------------------------

    private fun stackBonus(state: GameState, me: Team): Double {
        var total = 0.0
        // Keyed on the shared ring cell, not the raw path: keying stacks on path value is the
        // mis-encoding that shipped three separate times in this codebase (legalMoves,
        // GameState.teammatesSharing, penaltyMoves — see AGENTS.md), because several planes sit at
        // path 0 but on four *different* hangar pads. Track.ringIndexOrNull returns null for 待飞, the
        // lane and the finish, so it cannot make that mistake.
        val counted = ArrayList<Int>(GameState.PIECES_PER_TEAM)
        for (piece in state.pieces(me)) {
            val cell = Track.ringIndexOrNull(me, piece) ?: continue
            if (counted.contains(cell)) continue
            counted.add(cell)
            val size = countAt(state, me, cell)
            if (size >= STACK_MIN) total += STACK_BONUS + STACK_EXTRA_MEMBER * (size - STACK_MIN)
        }
        return total
    }

    /**
     * Expected path units our own planes forfeit to 倒飞 next roll — charged per piece, so a stack that
     * gets bounced is charged for both of its planes, which is what the position really costs.
     *
     * Computed by asking [GameEngine.destinationAfterBlocking] what actually happens on each face and
     * comparing with the unobstructed `path + face`, so the fiddly parts of the bounce rule stay the
     * engine's answers: only the first stack blocks, the bounce floors at cell 1, lane cells never
     * block, and every team's stack blocks including our own.
     *
     * Faces whose unobstructed destination would overshoot the finish are skipped: that move was never
     * legal ([GameEngine.legalMoves] requires an exact count), so being bounced instead is a gift.
     */
    private fun blockedPathUnits(state: GameState, me: Team): Double {
        var total = 0.0
        for (piece in state.pieces(me)) {
            val path = bounceablePathOrNull(piece) ?: continue
            var lost = 0.0
            for (face in 1..DiceEngine.FACES) {
                val unobstructed = path + face
                if (unobstructed > Track.FINISH_PATH) continue
                val actual = GameEngine.destinationAfterBlocking(state, me, path, face)
                if (actual < unobstructed) lost += (unobstructed - actual).toDouble()
            }
            total += lost / DiceEngine.FACES
        }
        return total
    }

    /** 待飞 and shared-loop planes can be bounced; hangared, lane and finished ones cannot. */
    private fun bounceablePathOrNull(piece: PieceState): Int? {
        val path = (piece as? PieceState.OnPath)?.value ?: return null
        return if (Track.isInStandby(path) || Track.isOnSharedLoop(path)) path else null
    }

    // ---------------------------------------------------------------------------------------------
    // One-ply lookahead over our own next roll
    // ---------------------------------------------------------------------------------------------

    /**
     * Expected [positional] gain from our own next roll, taking the best legal move on each face.
     *
     * Cheap, exact, and it subsumes a whole family of terms that would otherwise be written by hand
     * and would then have to be kept in step with the rules:
     *  - standing 1..6 short of [Track.FLIGHT_PATH] — a 1-in-6 shot at [Track.FLIGHT_ADVANCE] = +12,
     *    and only from a *direct* dice landing, since arriving on path 18 by colour hop spends the
     *    single trigger [GameEngine.resolve] allows per move and does not fly;
     *  - standing where the next own-colour cell (`path % 4 == 2`, 13 per lap) is inside 1..6, for +4;
     *  - a 僚机 getting either of those *once per member*, since `resolve` moves the whole group;
     *  - a carry a stack would cancel scoring nothing extra, because `resolve` applies
     *    [MoveOutcome.carryBlockedByStack] itself;
     *  - sitting an exact count from the finish, and having a capture set up (the opponent half of
     *    [positional] is inside this, so a capture available next turn is credited here — that is why
     *    there is no separate "offence" term);
     *  - keeping enough distinct groups that no face is wasted.
     *
     * A face with no legal move is charged [DEAD_FACE_PIPS] rather than counted as zero, because a
     * wasted turn is a real loss and not merely an absent gain.
     *
     * Optimistic in one respect: it takes the best reply per face, i.e. it assumes we still own the
     * position when the roll arrives. [LOOKAHEAD_W] discounts for that. The one thing it cannot see is
     * the two-roll manoeuvre — it credits *landing in* the flight window but not moving to the edge of
     * it — and an explicit hit-probability potential for [Track.FLIGHT_PATH] was built to fix that,
     * measured, and rejected: every weight from 4 to 18 units scored *worse* (53-56% against 56.3%),
     * because the near-term half is already in this term and the far half fights plain progress.
     */
    private fun lookahead(state: GameState, me: Team, weights: Map<Team, Double>): Double {
        val base = positional(state, me, weights)
        var total = 0.0
        for (face in 1..DiceEngine.FACES) {
            val moves = GameEngine.legalMoves(state, me, face)
            if (moves.isEmpty()) {
                total -= DEAD_FACE_PIPS
                continue
            }
            var best = -Double.MAX_VALUE
            for (move in moves) {
                val gain = positional(GameEngine.resolve(state, move).first, me, weights) - base
                if (gain > best) best = gain
            }
            total += best
        }
        return total / DiceEngine.FACES
    }

    // ---------------------------------------------------------------------------------------------
    // Shared helpers
    // ---------------------------------------------------------------------------------------------

    /** How many of [team]'s planes stand on shared ring cell [ringCell]. */
    private fun countAt(state: GameState, team: Team, ringCell: Int): Int =
        state.pieces(team).count { Track.ringIndexOrNull(team, it) == ringCell }

    private fun pathOf(piece: PieceState): Int = (piece as? PieceState.OnPath)?.value ?: -1
}
