package cz.magicheroes.tournamentmanager.domain

object ScoringRules {
    const val WIN_POINTS = 2
    const val TOURNAMENT_WIN_BONUS = 10

    /** A bye is progress in the bracket, not a played victory. */
    fun pointsForWinner(wasPlayed: Boolean, isTournamentFinal: Boolean): List<Pair<Int, String>> {
        if (!wasPlayed) return emptyList()
        return buildList {
            add(WIN_POINTS to if (isTournamentFinal) "Výhra ve finále" else "Výhra v zápase")
            if (isTournamentFinal) add(TOURNAMENT_WIN_BONUS to "Bonus za vítězství v turnaji")
        }
    }
}
