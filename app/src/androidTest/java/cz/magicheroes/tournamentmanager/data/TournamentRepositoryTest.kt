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
    fun finalVictoryAddsTwelvePointsAndCorrectionDoesNotDuplicatePoints() = runBlocking {
        repository.addPlayer("Ada", null)
        repository.addPlayer("Borek", null)
        val ids = database.tournamentDao().getPlayers().map { it.id }
        val tournamentId = repository.createAndStartTournament("Testovací turnaj", "2026-10-15", "", ids)

        repository.recordWinner(tournamentId, 1, ids[0])
        var ledger = database.tournamentDao().getPointsForTournament(tournamentId)
        assertEquals(12, ledger.sumOf { it.points }) // +2 for winning the final, +10 bonus
        assertEquals(2, ledger.size)

        // Recording/correcting the result rebuilds the ledger, it does not append duplicates.
        repository.recordWinner(tournamentId, 1, ids[1])
        ledger = database.tournamentDao().getPointsForTournament(tournamentId)
        assertEquals(12, ledger.sumOf { it.points })
        assertTrue(ledger.all { it.playerId == ids[1] })
        assertEquals(ids[1], database.tournamentDao().getTournament(tournamentId)?.winnerId)

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
