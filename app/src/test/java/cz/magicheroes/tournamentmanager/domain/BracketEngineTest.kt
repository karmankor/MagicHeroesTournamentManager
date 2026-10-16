package cz.magicheroes.tournamentmanager.domain

import cz.magicheroes.tournamentmanager.data.MatchEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BracketEngineTest {
    private fun entrants(count: Int) = (1..count).map { Entrant(it.toLong(), "Hráč $it", null, null) }

    @Test fun eightPlayersCreateFixedDoubleEliminationGraph() {
        val matches = BracketEngine.createPlan(10, entrants(8))
        assertEquals(14, matches.size) // 7 winner branch + 6 loser branch + grand final
        assertEquals(4, matches.count { it.branch == "WINNERS" && it.roundNumber == 1 })
        assertEquals(6, matches.count { it.branch == "LOSERS" })
        assertEquals(1, matches.count { it.isTournamentFinal })
        assertTrue(matches.filter { it.branch == "WINNERS" && it.roundNumber == 1 }.all { it.player1Id != null && it.player2Id != null })
    }

    @Test fun oddEntrantIsWaitingAndNeverAutoAdvanced() {
        val matches = BracketEngine.createPlan(11, entrants(7))
        val solo = matches.first { it.matchKey == "W1_3" }
        assertNotNull(solo.player1Id)
        assertEquals(null, solo.player2Id)
        assertEquals("WAITING_FOR_OPPONENT", solo.status)
        assertEquals(null, solo.winnerId)
    }

    @Test fun graphIsAcyclicAndEveryNodeCanBeOrdered() {
        val matches = BracketEngine.createPlan(12, entrants(32))
        assertEquals(62, matches.size)
        assertEquals(matches.size, BracketEngine.topologicalOrder(matches).size)
        assertTrue(matches.all { it.nextWinnerMatchNumber == null || matches.any { m -> m.matchNumber == it.nextWinnerMatchNumber } })
        assertTrue(matches.all { it.nextLoserMatchNumber == null || matches.any { m -> m.matchNumber == it.nextLoserMatchNumber } })
    }

    @Test fun bracketsUsePreparedSmallMediumAndLargeCapacities() {
        val small = BracketEngine.createPlan(20, entrants(2))
        assertEquals(14, small.size)
        assertEquals(4, small.count { it.branch == "WINNERS" && it.roundNumber == 1 })
        assertEquals(3, small.count { it.branch == "WINNERS" && it.roundNumber == 1 && it.player2Id == null })

        val medium = BracketEngine.createPlan(21, entrants(9))
        assertEquals(30, medium.size)
        assertEquals(8, medium.count { it.branch == "WINNERS" && it.roundNumber == 1 })

        val large = BracketEngine.createPlan(22, entrants(17))
        assertEquals(62, large.size)
        assertEquals(16, large.count { it.branch == "WINNERS" && it.roundNumber == 1 })
    }

    @Test fun duplicatePlayerIdsAreRejected() {
        val list = listOf(Entrant(1, "A", null, null), Entrant(1, "A2", null, null))
        try {
            BracketEngine.createPlan(1, list)
            throw AssertionError("Expected validation error")
        } catch (expected: IllegalArgumentException) {
            assertNotEquals("", expected.message)
        }
    }
    @Test fun changingEitherEntrantInvalidatesOldMatchResult() {
        val previous = MatchEntity(
            tournamentId = 3,
            matchNumber = 5,
            matchKey = "W2_0",
            roundNumber = 2,
            branch = "WINNERS",
            player1Id = 1,
            player2Id = 2,
            selectedWinnerId = 1,
            winnerId = 1
        )
        assertTrue(BracketEngine.hasSameEntrants(previous, 1L, 2L))
        assertTrue(!BracketEngine.hasSameEntrants(previous, 1L, 3L))
        assertTrue(!BracketEngine.hasSameEntrants(previous, 3L, 2L))
        assertTrue(!BracketEngine.hasSameEntrants(previous, null, 2L))
    }

    @Test fun allEntrantCountsWithinLimitBuildAcyclicGraphs() {
        for (count in 2..32) {
            val matches = BracketEngine.createPlan(30L + count, entrants(count))
            assertEquals(matches.size, BracketEngine.topologicalOrder(matches).size)
            assertEquals(matches.size, matches.map { it.matchNumber }.distinct().size)
            assertTrue(matches.all { it.player1Id == null || it.player1Id in (1L..count.toLong()) })
            assertTrue(matches.all { it.player2Id == null || it.player2Id in (1L..count.toLong()) })
        }
    }

}
