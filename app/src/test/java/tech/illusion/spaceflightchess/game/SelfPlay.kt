package tech.illusion.spaceflightchess.game

import kotlin.random.Random

/** How a seat picks its move. Matches `PlaneAi.chooseMove`'s shape so either AI can be plugged in. */
internal typealias Strategy = (GameState, List<Move>) -> Move

/**
 * Headless whole-game driver, so AI strength is something the suite can measure rather than something
 * a human has to judge by watching four planes shuffle around a headset.
 *
 * Deliberately drives the real [GameEngine] — same `roll` / `legalMovesForPendingRoll` / `applyMove`
 * sequence `BoardStage` uses — so it exercises the shipped rules, extra rolls on a 6, the triple-six
 * penalty and all. Only the dice are substituted, for a seeded and therefore reproducible game.
 */
internal object SelfPlay {

    data class Result(
        val winner: Team?,
        val finished: Map<Team, Int>,
        val totalProgress: Map<Team, Int>,
        val rolls: Int,
        val hitRollCap: Boolean,
    )

    /**
     * A roll budget, not a turn budget. A stalled position (every plane blocked behind a stack, nobody
     * able to finish on an exact count) would otherwise spin forever; a capped game still reports
     * finished-piece counts, which is the tie-break the comparison actually uses.
     */
    const val ROLL_CAP = 4000

    fun play(seed: Int, strategies: Map<Team, Strategy>): Result {
        val engine = GameEngine(DiceEngine(Random(seed)))
        engine.startGame()
        var rolls = 0
        while (!engine.isOver && rolls < ROLL_CAP) {
            if (engine.phase == Phase.AWAITING_MOVE) {
                val moves = engine.legalMovesForPendingRoll()
                if (moves.isEmpty()) break // engine invariant broken; fail loudly in the caller
                val team = engine.state.currentTeam
                engine.applyMove(strategies.getValue(team)(engine.state, moves))
                continue
            }
            if (engine.phase != Phase.AWAITING_ROLL) break
            engine.roll() ?: break
            rolls++
            engine.drainEvents()
        }
        return Result(
            winner = WinDetector.winner(engine.state),
            finished = Team.entries.associateWith { engine.state.finishedPieceCount(it) },
            totalProgress = Team.entries.associateWith { team -> progressOf(engine.state, team) },
            rolls = rolls,
            hitRollCap = rolls >= ROLL_CAP,
        )
    }

    /** Total path distance travelled by a team — the score to compare when no one finished in time. */
    fun progressOf(state: GameState, team: Team): Int =
        state.pieces(team).sumOf { (it as? PieceState.OnPath)?.value ?: 0 }

    /**
     * Plays [games] pairs of games between [challenger] and [baseline]. Each seed is played twice with
     * the seat assignment swapped, because RED rolls first and the four hangars are not equivalent —
     * without the swap a win rate mostly measures seat luck.
     */
    fun match(challenger: Strategy, baseline: Strategy, games: Int): MatchResult =
        match(challenger, baseline, 0 until games)

    /**
     * As [match], but over an explicit seed range, so a weight chosen by sweeping one range can be
     * re-measured on a range that was never swept. Without that split a win rate can just be seed-fitting.
     */
    fun match(challenger: Strategy, baseline: Strategy, seeds: IntRange): MatchResult {
        var challengerWins = 0
        var baselineWins = 0
        var draws = 0
        var capped = 0
        var challengerFinished = 0
        var baselineFinished = 0
        val challengerSeats = listOf(Team.RED, Team.BLUE)
        val baselineSeats = listOf(Team.YELLOW, Team.GREEN)
        for (seed in seeds) {
            for (swap in listOf(false, true)) {
                val forChallenger = if (swap) baselineSeats else challengerSeats
                val strategies = Team.entries.associateWith { if (it in forChallenger) challenger else baseline }
                val r = play(seed, strategies)
                if (r.hitRollCap) capped++
                challengerFinished += forChallenger.sumOf { r.finished.getValue(it) }
                baselineFinished += Team.entries.filterNot { it in forChallenger }.sumOf { r.finished.getValue(it) }
                when {
                    r.winner == null -> draws++
                    r.winner in forChallenger -> challengerWins++
                    else -> baselineWins++
                }
            }
        }
        return MatchResult(
            challengerWins, baselineWins, draws, capped, challengerFinished, baselineFinished,
            games = seeds.count() * 2,
        )
    }

    data class MatchResult(
        val challengerWins: Int,
        val baselineWins: Int,
        val draws: Int,
        val capped: Int,
        val challengerFinishedPieces: Int,
        val baselineFinishedPieces: Int,
        val games: Int,
    ) {
        val decided: Int get() = challengerWins + baselineWins
        val challengerWinRate: Double get() = if (decided == 0) 0.0 else challengerWins.toDouble() / decided
        override fun toString(): String =
            "challenger $challengerWins - $baselineWins baseline (draws=$draws, capped=$capped, " +
                "games=$games, winRate=${"%.1f".format(challengerWinRate * 100)}%, " +
                "finishedPieces ${challengerFinishedPieces} vs ${baselineFinishedPieces})"
    }
}
