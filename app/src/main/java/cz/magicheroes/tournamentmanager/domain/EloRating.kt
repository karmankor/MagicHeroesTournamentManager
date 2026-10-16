package cz.magicheroes.tournamentmanager.domain

import cz.magicheroes.tournamentmanager.data.MatchEntity
import kotlin.math.pow
import kotlin.math.roundToInt

/** Replays real head-to-head results from a stable baseline to avoid double-counting corrections. */
object EloRating {
    const val INITIAL_RATING = 1000
    const val K_FACTOR = 32
    private const val SCALE = 400.0

    fun recalculate(playerIds: Collection<Long>, matches: List<MatchEntity>): Map<Long, Int> {
        val ratings = playerIds.distinct().associateWith { INITIAL_RATING }.toMutableMap()
        val playedMatches = matches.asSequence()
            .filter { match ->
                val one = match.player1Id
                val two = match.player2Id
                val winner = match.winnerId
                one != null && two != null && one != two && winner != null &&
                    (winner == one || winner == two)
            }
            .sortedWith(
                compareBy<MatchEntity> { it.completedAt ?: Long.MIN_VALUE }
                    .thenBy { it.tournamentId }
                    .thenBy { it.matchNumber }
            )
        for (match in playedMatches) {
            val one = match.player1Id ?: continue
            val two = match.player2Id ?: continue
            val winner = match.winnerId ?: continue
            if (one !in ratings) ratings[one] = INITIAL_RATING
            if (two !in ratings) ratings[two] = INITIAL_RATING
            val ratingOne = ratings.getValue(one)
            val ratingTwo = ratings.getValue(two)
            val delta = changeFor(ratingOne, ratingTwo, won = winner == one)
            ratings[one] = ratingOne + delta
            ratings[two] = ratingTwo - delta
        }
        return ratings.toMap()
    }

    /** Positive means rating gain; a loss returns a negative number. */
    fun changeFor(playerRating: Int, opponentRating: Int, won: Boolean): Int {
        val expectedScore = 1.0 / (1.0 + 10.0.pow((opponentRating - playerRating) / SCALE))
        val actualScore = if (won) 1.0 else 0.0
        return (K_FACTOR * (actualScore - expectedScore)).roundToInt()
    }
}
