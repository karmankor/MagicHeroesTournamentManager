package cz.magicheroes.tournamentmanager.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TournamentRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: TournamentRepository

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = TournamentRepository(database)
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun playedMatchAddsPointsAndCorrectionUpdatesEloWithoutDuplicates() = runBlocking {
        repository.addPlayer("Ada", null)
        repository.addPlayer("Borek", null)
        val ids = database.tournamentDao().getPlayers().map { it.id }
        val tournamentId = repository.createAndStartTournament("Testovací turnaj", "2026-10-15", "", ids)

        // Match 1 is the first-round match in the reserved small bracket, not the grand final.
        repository.recordWinner(tournamentId, 1, ids[0])
        var ledger = database.tournamentDao().getPointsForTournament(tournamentId)
        assertEquals(2, ledger.sumOf { it.points })
        assertEquals(1, ledger.size)
        assertEquals(1016, database.tournamentDao().getPlayer(ids[0])?.eloRating)
        assertEquals(984, database.tournamentDao().getPlayer(ids[1])?.eloRating)

        // A correction replays history and replaces the ledger rather than appending points.
        repository.recordWinner(tournamentId, 1, ids[1])
        ledger = database.tournamentDao().getPointsForTournament(tournamentId)
        assertEquals(2, ledger.sumOf { it.points })
        assertEquals(1, ledger.size)
        assertTrue(ledger.all { it.playerId == ids[1] })
        assertEquals(984, database.tournamentDao().getPlayer(ids[0])?.eloRating)
        assertEquals(1016, database.tournamentDao().getPlayer(ids[1])?.eloRating)

        repository.finishTournament(tournamentId)
        assertEquals(TournamentEntity.STATUS_FINISHED, database.tournamentDao().getTournament(tournamentId)?.status)
    }

    @Test
    fun archivedPlayerAndHistoricRecordsRemainInDatabase() = runBlocking {
        repository.addPlayer("Člen", null)
        val player = database.tournamentDao().getPlayers().single()
        repository.archivePlayer(player.id)

        assertTrue(database.tournamentDao().getPlayers().isEmpty())
        assertEquals("Člen", database.tournamentDao().getPlayer(player.id)?.name)
        val archived = database.tournamentDao().getAllPlayersForBackup().single()
        assertTrue(archived.isArchived)
    }
    @Test
    fun lateEntrantFillsReservedSlotWithoutClearingPlayedResult() = runBlocking {
        listOf("Ada", "Borek", "Cyril").forEach { repository.addPlayer(it, null) }
        val ids = database.tournamentDao().getPlayers().map { it.id }
        val tournamentId = repository.createAndStartTournament("Pozdní hráč", "2026-10-16", "", ids.take(2))

        repository.recordWinner(tournamentId, 1, ids[0])
        repository.addPlayerToWaitlist(tournamentId, ids[2])
        repository.promoteNextWaitlistedPlayer(tournamentId)

        val matches = database.tournamentDao().getMatches(tournamentId).associateBy { it.matchKey }
        assertEquals(14, matches.size)
        assertEquals(ids[0], matches.getValue("W1_0").winnerId)
        assertEquals(ids[2], matches.getValue("W1_1").player1Id)
    }

    @Test
    fun manualOpeningSwapClearsOnlyNowInvalidResultsAndRecalculatesRating() = runBlocking {
        listOf("Ada", "Borek", "Cyril", "Dana").forEach { repository.addPlayer(it, null) }
        val ids = database.tournamentDao().getPlayers().map { it.id }
        val tournamentId = repository.createAndStartTournament("Ruční dvojice", "2026-10-16", "", ids)
        val matchNumbers = database.tournamentDao().getMatches(tournamentId).associate { it.matchKey to it.matchNumber }

        repository.recordWinner(tournamentId, matchNumbers.getValue("W1_0"), ids[0])
        repository.recordWinner(tournamentId, matchNumbers.getValue("W1_1"), ids[2])
        repository.swapOpeningEntrants(tournamentId, ids[0], ids[2], confirmed = true)

        val matches = database.tournamentDao().getMatches(tournamentId).associateBy { it.matchKey }
        org.junit.Assert.assertNull(matches.getValue("W1_0").winnerId)
        org.junit.Assert.assertNull(matches.getValue("W1_1").winnerId)
        assertEquals(ids[2], matches.getValue("W1_0").player1Id)
        assertEquals(ids[0], matches.getValue("W1_1").player1Id)
        assertEquals(0, database.tournamentDao().getPointsForTournament(tournamentId).sumOf { it.points })
        ids.forEach { playerId -> assertEquals(1000, database.tournamentDao().getPlayer(playerId)?.eloRating) }
    }

    @Test
    fun correctingEarlierWinnerClearsResultsWhoseMatchupChanged() = runBlocking {
        listOf("Ada", "Borek", "Cyril", "Dana").forEach { repository.addPlayer(it, null) }
        val ids = database.tournamentDao().getPlayers().map { it.id }
        val tournamentId = repository.createAndStartTournament("Korekce bracketu", "2026-10-15", "", ids)
        val matchNumbers = database.tournamentDao().getMatches(tournamentId).associate { it.matchKey to it.matchNumber }

        repository.recordWinner(tournamentId, matchNumbers.getValue("W1_0"), ids[0])
        repository.recordWinner(tournamentId, matchNumbers.getValue("W1_1"), ids[2])
        repository.recordWinner(tournamentId, matchNumbers.getValue("W2_0"), ids[0])
        repository.recordWinner(tournamentId, matchNumbers.getValue("L1_0"), ids[1])
        repository.recordWinner(tournamentId, matchNumbers.getValue("L2_0"), ids[1])
        assertEquals(ids[1], database.tournamentDao().getMatches(tournamentId).first { it.matchKey == "L2_0" }.winnerId)

        // The first round correction changes both semifinals' participants. Old results must not survive.
        repository.recordWinner(tournamentId, matchNumbers.getValue("W1_0"), ids[1])
        val corrected = database.tournamentDao().getMatches(tournamentId).associateBy { it.matchKey }
        org.junit.Assert.assertNull(corrected.getValue("W2_0").winnerId)
        org.junit.Assert.assertNull(corrected.getValue("L1_0").winnerId)
        org.junit.Assert.assertNull(corrected.getValue("L2_0").winnerId)
    }

}
