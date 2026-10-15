package cz.magicheroes.tournamentmanager.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TournamentDao {
    @Query("SELECT * FROM clans ORDER BY name COLLATE NOCASE")
    fun observeClans(): Flow<List<ClanEntity>>

    @Query("SELECT * FROM clans ORDER BY name COLLATE NOCASE")
    suspend fun getClans(): List<ClanEntity>

    @Query("SELECT * FROM clans WHERE id = :id LIMIT 1")
    suspend fun getClan(id: Long): ClanEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertClan(clan: ClanEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreClans(items: List<ClanEntity>)

    @Query("DELETE FROM clans")
    suspend fun clearClans()

    @Update
    suspend fun updateClan(clan: ClanEntity)

    @Query("DELETE FROM clans WHERE id = :id")
    suspend fun deleteClan(id: Long)

    @Query("SELECT * FROM players WHERE isArchived = 0 ORDER BY name COLLATE NOCASE")
    fun observePlayers(): Flow<List<PlayerEntity>>

    @Query("SELECT * FROM players ORDER BY name COLLATE NOCASE")
    fun observeAllPlayersIncludingArchived(): Flow<List<PlayerEntity>>

    @Query("SELECT * FROM players WHERE isArchived = 0 ORDER BY name COLLATE NOCASE")
    suspend fun getPlayers(): List<PlayerEntity>

    @Query("SELECT * FROM players ORDER BY id")
    suspend fun getAllPlayersForBackup(): List<PlayerEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restorePlayers(items: List<PlayerEntity>)

    @Query("DELETE FROM players")
    suspend fun clearPlayers()

    @Query("SELECT * FROM players WHERE id = :id LIMIT 1")
    suspend fun getPlayer(id: Long): PlayerEntity?

    @Insert
    suspend fun insertPlayer(player: PlayerEntity): Long

    @Update
    suspend fun updatePlayer(player: PlayerEntity)

    @Query("UPDATE players SET isArchived = 1 WHERE id = :id")
    suspend fun archivePlayer(id: Long)

    @Query("UPDATE players SET clanId = NULL WHERE clanId = :clanId")
    suspend fun removePlayersFromClan(clanId: Long)

    @Query("SELECT * FROM tournaments ORDER BY dateIso DESC, id DESC")
    fun observeTournaments(): Flow<List<TournamentEntity>>

    @Query("SELECT * FROM tournaments ORDER BY dateIso DESC, id DESC")
    suspend fun getTournaments(): List<TournamentEntity>

    @Query("SELECT * FROM tournaments WHERE id = :id LIMIT 1")
    suspend fun getTournament(id: Long): TournamentEntity?

    @Query("SELECT * FROM tournaments ORDER BY id")
    suspend fun getAllTournamentsForBackup(): List<TournamentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreTournaments(items: List<TournamentEntity>)

    @Query("DELETE FROM tournaments")
    suspend fun clearTournaments()

    @Insert
    suspend fun insertTournament(tournament: TournamentEntity): Long

    @Update
    suspend fun updateTournament(tournament: TournamentEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertParticipants(items: List<TournamentParticipantEntity>)

    @Query("SELECT * FROM tournament_participants WHERE tournamentId = :tournamentId ORDER BY seed")
    fun observeParticipants(tournamentId: Long): Flow<List<TournamentParticipantEntity>>

    @Query("SELECT * FROM tournament_participants WHERE tournamentId = :tournamentId ORDER BY seed")
    suspend fun getParticipants(tournamentId: Long): List<TournamentParticipantEntity>

    @Query("SELECT * FROM tournament_participants ORDER BY tournamentId, seed")
    fun observeAllParticipants(): Flow<List<TournamentParticipantEntity>>

    @Query("SELECT * FROM tournament_participants ORDER BY tournamentId, seed")
    suspend fun getAllParticipantsForBackup(): List<TournamentParticipantEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreParticipants(items: List<TournamentParticipantEntity>)

    @Query("DELETE FROM tournament_participants")
    suspend fun clearParticipants()

    @Insert
    suspend fun insertMatches(items: List<MatchEntity>)

    @Update
    suspend fun updateMatch(match: MatchEntity)

    @Query("SELECT * FROM matches WHERE tournamentId = :tournamentId ORDER BY matchNumber")
    fun observeMatches(tournamentId: Long): Flow<List<MatchEntity>>

    @Query("SELECT * FROM matches WHERE tournamentId = :tournamentId ORDER BY matchNumber")
    suspend fun getMatches(tournamentId: Long): List<MatchEntity>

    @Query("SELECT * FROM matches ORDER BY completedAt")
    fun observeAllMatches(): Flow<List<MatchEntity>>

    @Query("SELECT * FROM matches ORDER BY id")
    suspend fun getAllMatchesForBackup(): List<MatchEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreMatches(items: List<MatchEntity>)

    @Query("DELETE FROM matches")
    suspend fun clearMatches()

    @Query("DELETE FROM point_transactions WHERE tournamentId = :tournamentId")
    suspend fun deleteTournamentPoints(tournamentId: Long)

    @Insert
    suspend fun insertPointTransactions(items: List<PointTransactionEntity>)

    @Query("SELECT * FROM point_transactions ORDER BY createdAt")
    fun observeAllPointTransactions(): Flow<List<PointTransactionEntity>>

    @Query("SELECT * FROM point_transactions ORDER BY id")
    suspend fun getAllPointTransactionsForBackup(): List<PointTransactionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restorePointTransactions(items: List<PointTransactionEntity>)

    @Query("DELETE FROM point_transactions")
    suspend fun clearPointTransactions()

    @Query("SELECT * FROM point_transactions WHERE tournamentId = :tournamentId ORDER BY createdAt")
    suspend fun getPointsForTournament(tournamentId: Long): List<PointTransactionEntity>
}
