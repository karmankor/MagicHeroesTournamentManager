package cz.magicheroes.tournamentmanager.domain

import cz.magicheroes.tournamentmanager.data.MatchEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EloRatingTest {
    @Test fun playersStartAtOneThousand() {
        val ratings = EloRating.recalculate(listOf(1L, 2L), emptyList())
        assertEquals(1000, ratings[1L])
        assertEquals(1000, ratings[2L])
    }

    @Test fun equalRatedWinnerGainsSixteenPoints() {
        val match = MatchEntity(tournamentId = 1, matchNumber = 1, matchKey = "G", roundNumber = 1,
            branch = "FINAL", player1Id = 1, player2Id = 2, winnerId = 1, selectedWinnerId = 1, completedAt = 100)
        val ratings = EloRating.recalculate(listOf(1L, 2L), listOf(match))
        assertEquals(1016, ratings[1L])
        assertEquals(984, ratings[2L])
    }

    @Test fun beatingHigherRatedOpponentIsWorthMore() {
        val upset = EloRating.changeFor(1000, 1200, won = true)
        val expectedWin = EloRating.changeFor(1000, 800, won = true)
        assertTrue(upset > expectedWin)
        assertTrue(upset > 0)
    }

    @Test fun correctedResultCanBeReplayedWithoutDoubleCounting() {
        val original = MatchEntity(tournamentId = 1, matchNumber = 1, matchKey = "G", roundNumber = 1,
            branch = "FINAL", player1Id = 1, player2Id = 2, winnerId = 2, selectedWinnerId = 2, completedAt = 100)
        val ratings = EloRating.recalculate(listOf(1L, 2L), listOf(original))
        assertEquals(984, ratings[1L])
        assertEquals(1016, ratings[2L])
    }
}
