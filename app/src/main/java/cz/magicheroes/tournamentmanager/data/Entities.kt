package cz.magicheroes.tournamentmanager.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "clans", indices = [Index(value = ["name"], unique = true)])
data class ClanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "players", indices = [Index(value = ["clanId"]), Index(value = ["isArchived"])])
data class PlayerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val clanId: Long? = null,
    val isArchived: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "tournaments", indices = [Index(value = ["dateIso"]), Index(value = ["status"])])
data class TournamentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val dateIso: String,
    val note: String = "",
    val status: String = STATUS_PLANNED,
    val winnerId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null
) {
    companion object {
        const val STATUS_PLANNED = "PLANNED"
        const val STATUS_ACTIVE = "ACTIVE"
        const val STATUS_FINISHED = "FINISHED"
    }
}

@Entity(
    tableName = "tournament_participants",
    primaryKeys = ["tournamentId", "playerId"],
    indices = [Index(value = ["playerId"]), Index(value = ["clanIdSnapshot"])]
)
data class TournamentParticipantEntity(
    val tournamentId: Long,
    val playerId: Long,
    val playerNameSnapshot: String,
    val clanIdSnapshot: Long? = null,
    val clanNameSnapshot: String? = null,
    val seed: Int
)

@Entity(
    tableName = "matches",
    indices = [Index(value = ["tournamentId", "matchNumber"], unique = true), Index(value = ["tournamentId"]), Index(value = ["player1Id"]), Index(value = ["player2Id"])]
)
data class MatchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tournamentId: Long,
    val matchNumber: Int,
    val matchKey: String,
    val roundNumber: Int,
    /** WINNERS, LOSERS or FINAL */
    val branch: String,
    val status: String = "WAITING_FOR_PLAYERS",
    val player1Id: Long? = null,
    val player1NameSnapshot: String? = null,
    val player2Id: Long? = null,
    val player2NameSnapshot: String? = null,
    /** Explicitly selected result; retained only while valid for this match. */
    val selectedWinnerId: Long? = null,
    /** Derived result rebuilt transactionally after every change. */
    val winnerId: Long? = null,
    val loserId: Long? = null,
    val nextWinnerMatchNumber: Int? = null,
    val winnerTargetSlot: Int? = null,
    val nextLoserMatchNumber: Int? = null,
    val loserTargetSlot: Int? = null,
    val isTournamentFinal: Boolean = false,
    val byeConfirmed: Boolean = false,
    val completedAt: Long? = null
)

@Entity(
    tableName = "point_transactions",
    indices = [Index(value = ["playerId"]), Index(value = ["tournamentId"]), Index(value = ["matchId"]), Index(value = ["clanIdSnapshot"])]
)
data class PointTransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playerId: Long,
    val tournamentId: Long,
    val matchId: Long? = null,
    val matchNumber: Int? = null,
    val points: Int,
    val reason: String,
    /** Clan at the time this result was recorded, not the player's current clan. */
    val clanIdSnapshot: Long? = null,
    val clanNameSnapshot: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)
