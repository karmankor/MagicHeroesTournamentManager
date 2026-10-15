package cz.magicheroes.tournamentmanager.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ScoringRulesTest {
    @Test fun normalWinGivesTwoPoints() {
        assertEquals(listOf(2 to "Výhra v zápase"), ScoringRules.pointsForWinner(true, false))
    }

    @Test fun finalWinGivesTwelvePointsIncludingBonus() {
        assertEquals(
            listOf(2 to "Výhra ve finále", 10 to "Bonus za vítězství v turnaji"),
            ScoringRules.pointsForWinner(true, true)
        )
    }

    @Test fun noMatchAdvanceAwardsNoPoints() {
        assertEquals(emptyList<Pair<Int, String>>(), ScoringRules.pointsForWinner(false, false))
    }
}
