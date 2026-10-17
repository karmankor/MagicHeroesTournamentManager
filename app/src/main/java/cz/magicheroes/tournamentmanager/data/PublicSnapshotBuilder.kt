package cz.magicheroes.tournamentmanager.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * Explicit allow-list of fields safe for the public tournament site.
 * Deliberately excludes tournament notes and point-adjustment reasons.
 */
object PublicSnapshotBuilder {
    fun build(
        clans: List<ClanEntity>,
        players: List<PlayerEntity>,
        tournaments: List<TournamentEntity>,
        participants: List<TournamentParticipantEntity>,
        matches: List<MatchEntity>,
        pointTransactions: List<PointTransactionEntity>,
        clanPointAdjustments: List<ClanPointAdjustmentEntity>
    ): JSONObject {
        val playerById = players.associateBy { it.id }
        val clanById = clans.associateBy { it.id }
        val pointsByPlayer = pointTransactions.groupBy { it.playerId }
            .mapValues { (_, items) -> items.sumOf { it.points } }
        val clanPointsById = clans.associate { clan ->
            clan.id to (
                pointTransactions.filter { it.clanIdSnapshot == clan.id }.sumOf { it.points } +
                    clanPointAdjustments.filter { it.clanIdSnapshot == clan.id }.sumOf { it.points }
                )
        }

        fun idOrNull(id: Long?): Any = id ?: JSONObject.NULL
        fun nameFor(id: Long?, fallback: String? = null): Any =
            if (id == null) JSONObject.NULL else playerById[id]?.name ?: fallback ?: "Neznámý hráč"

        val publicPlayers = JSONArray().apply {
            players.forEach { player ->
                put(JSONObject()
                    .put("id", player.id)
                    .put("name", player.name)
                    .put("clanId", idOrNull(player.clanId))
                    .put("clanName", player.clanId?.let { clanById[it]?.name } ?: "Bez klanu")
                    .put("eloRating", player.eloRating)
                    .put("points", pointsByPlayer[player.id] ?: 0)
                    .put("archived", player.isArchived))
            }
        }

        val publicClans = JSONArray().apply {
            clans.forEach { clan ->
                put(JSONObject()
                    .put("id", clan.id)
                    .put("name", clan.name)
                    .put("points", clanPointsById[clan.id] ?: 0)
                    .put("memberCount", players.count { !it.isArchived && it.clanId == clan.id }))
            }
        }

        val publicTournaments = JSONArray().apply {
            tournaments.forEach { tournament ->
                put(JSONObject()
                    .put("id", tournament.id)
                    .put("name", tournament.name)
                    .put("dateIso", tournament.dateIso)
                    .put("status", tournament.status)
                    .put("winnerId", idOrNull(tournament.winnerId))
                    .put("winnerName", nameFor(tournament.winnerId))
                    .put("finishedAt", tournament.finishedAt ?: JSONObject.NULL))
            }
        }

        val publicParticipants = JSONArray().apply {
            participants.forEach { participant ->
                put(JSONObject()
                    .put("tournamentId", participant.tournamentId)
                    .put("playerId", participant.playerId)
                    .put("playerName", participant.playerNameSnapshot)
                    .put("clanName", participant.clanNameSnapshot ?: "Bez klanu")
                    .put("seed", participant.seed)
                    .put("queued", participant.isQueued)
                    .put("withdrawn", participant.isWithdrawn))
            }
        }

        val publicMatches = JSONArray().apply {
            matches.forEach { match ->
                val winnerFallback = when (match.winnerId) {
                    match.player1Id -> match.player1NameSnapshot
                    match.player2Id -> match.player2NameSnapshot
                    else -> null
                }
                val loserFallback = when (match.loserId) {
                    match.player1Id -> match.player1NameSnapshot
                    match.player2Id -> match.player2NameSnapshot
                    else -> null
                }
                put(JSONObject()
                    .put("tournamentId", match.tournamentId)
                    .put("matchNumber", match.matchNumber)
                    .put("matchKey", match.matchKey)
                    .put("roundNumber", match.roundNumber)
                    .put("branch", match.branch)
                    .put("status", match.status)
                    .put("player1Id", idOrNull(match.player1Id))
                    .put("player1Name", match.player1NameSnapshot ?: match.player1Id?.let { playerById[it]?.name } ?: "—")
                    .put("player2Id", idOrNull(match.player2Id))
                    .put("player2Name", match.player2NameSnapshot ?: match.player2Id?.let { playerById[it]?.name } ?: "—")
                    .put("winnerId", idOrNull(match.winnerId))
                    .put("winnerName", nameFor(match.winnerId, winnerFallback))
                    .put("loserId", idOrNull(match.loserId))
                    .put("loserName", nameFor(match.loserId, loserFallback))
                    .put("isTournamentFinal", match.isTournamentFinal)
                    .put("byeConfirmed", match.byeConfirmed)
                    .put("completedAt", match.completedAt ?: JSONObject.NULL))
            }
        }

        return JSONObject()
            .put("schemaVersion", 1)
            .put("publishedAt", Instant.now().toString())
            .put("players", publicPlayers)
            .put("clans", publicClans)
            .put("tournaments", publicTournaments)
            .put("participants", publicParticipants)
            .put("matches", publicMatches)
    }
}
