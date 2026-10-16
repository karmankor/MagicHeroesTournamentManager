package cz.magicheroes.tournamentmanager.data

import android.content.ContentResolver
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import androidx.room.withTransaction
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/** Versioned, offline backup of all database tables. Restore validates the whole file
 * before replacing any local data, then applies the replacement in one Room transaction. */
object DatabaseBackup {
    private const val FORMAT = "MagicHeroesTournamentManagerBackup"
    private const val VERSION = 1
    private const val MAX_BYTES = 25 * 1024 * 1024

    suspend fun export(database: AppDatabase, resolver: ContentResolver, uri: Uri) {
        val dao = database.tournamentDao()
        val snapshot = database.withTransaction {
            BackupSnapshot(
                clans = dao.getClans(),
                players = dao.getAllPlayersForBackup(),
                tournaments = dao.getAllTournamentsForBackup(),
                participants = dao.getAllParticipantsForBackup(),
                matches = dao.getAllMatchesForBackup(),
                points = dao.getAllPointTransactionsForBackup()
            )
        }
        val root = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("createdAt", System.currentTimeMillis())
            .put("clans", JSONArray().apply { snapshot.clans.forEach { put(it.toJson()) } })
            .put("players", JSONArray().apply { snapshot.players.forEach { put(it.toJson()) } })
            .put("tournaments", JSONArray().apply { snapshot.tournaments.forEach { put(it.toJson()) } })
            .put("participants", JSONArray().apply { snapshot.participants.forEach { put(it.toJson()) } })
            .put("matches", JSONArray().apply { snapshot.matches.forEach { put(it.toJson()) } })
            .put("points", JSONArray().apply { snapshot.points.forEach { put(it.toJson()) } })
        val bytes = root.toString(2).toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "Záloha je příliš velká (limit je 25 MB)." }
        resolver.openOutputStream(uri, "wt").use { output ->
            requireNotNull(output) { "Soubor zálohy se nepodařilo otevřít pro zápis." }
            output.write(bytes)
            output.flush()
        }
    }

    suspend fun restore(database: AppDatabase, resolver: ContentResolver, uri: Uri) {
        val bytes = resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Vybraný soubor se nepodařilo otevřít." }
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= MAX_BYTES) { "Soubor zálohy překračuje limit 25 MB." }
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        }
        val root = try {
            JSONObject(String(bytes, StandardCharsets.UTF_8))
        } catch (error: Exception) {
            throw IllegalArgumentException("Soubor není platná JSON záloha Magic Heroes.", error)
        }
        require(root.optString("format") == FORMAT) { "Soubor nepatří aplikaci Magic Heroes Tournament Manager." }
        require(root.optInt("version", -1) == VERSION) { "Tato verze zálohy není podporována." }

        val snapshot = try {
            BackupSnapshot(
                clans = root.getJSONArray("clans").mapObjects { it.toClan() },
                players = root.getJSONArray("players").mapObjects { it.toPlayer() },
                tournaments = root.getJSONArray("tournaments").mapObjects { it.toTournament() },
                participants = root.getJSONArray("participants").mapObjects { it.toParticipant() },
                matches = root.getJSONArray("matches").mapObjects { it.toMatch() },
                points = root.getJSONArray("points").mapObjects { it.toPoint() }
            )
        } catch (error: Exception) {
            throw IllegalArgumentException("Záloha je poškozená nebo jí chybí potřebná data.", error)
        }
        validate(snapshot)

        val dao = database.tournamentDao()
        database.withTransaction {
            // No rows are removed until parsing and validation above have succeeded.
            dao.clearPointTransactions()
            dao.clearMatches()
            dao.clearParticipants()
            dao.clearTournaments()
            dao.clearPlayers()
            dao.clearClans()
            if (snapshot.clans.isNotEmpty()) dao.restoreClans(snapshot.clans)
            if (snapshot.players.isNotEmpty()) dao.restorePlayers(snapshot.players)
            if (snapshot.tournaments.isNotEmpty()) dao.restoreTournaments(snapshot.tournaments)
            if (snapshot.participants.isNotEmpty()) dao.restoreParticipants(snapshot.participants)
            if (snapshot.matches.isNotEmpty()) dao.restoreMatches(snapshot.matches)
            if (snapshot.points.isNotEmpty()) dao.restorePointTransactions(snapshot.points)
        }
    }

    private fun validate(s: BackupSnapshot) {
        fun unique(values: List<Long>, label: String) {
            require(values.all { it > 0 } && values.distinct().size == values.size) { "Záloha obsahuje neplatná nebo duplicitní ID ($label)." }
        }
        unique(s.clans.map { it.id }, "klany")
        unique(s.players.map { it.id }, "hráči")
        unique(s.tournaments.map { it.id }, "turnaje")
        unique(s.matches.map { it.id }, "zápasy")
        unique(s.points.map { it.id }, "body")
        require(s.clans.map { it.name }.distinct().size == s.clans.size) { "Záloha obsahuje duplicitní názvy klanů." }
        require(s.matches.map { it.tournamentId to it.matchNumber }.distinct().size == s.matches.size) { "Záloha obsahuje duplicitní čísla zápasů." }
        require(s.participants.map { it.tournamentId to it.playerId }.distinct().size == s.participants.size) { "Záloha obsahuje duplicitní účastníky turnaje." }

        val clanIds = s.clans.map { it.id }.toSet()
        val playerIds = s.players.map { it.id }.toSet()
        val tournamentIds = s.tournaments.map { it.id }.toSet()
        val participantsByTournament = s.participants.groupBy { it.tournamentId }
        val matchesByTournament = s.matches.groupBy { it.tournamentId }
        val matchIds = s.matches.map { it.id }.toSet()
        require(s.players.all { it.clanId == null || it.clanId in clanIds }) { "Některý hráč odkazuje na neexistující klan." }
        require(s.participants.all { it.tournamentId in tournamentIds && it.playerId in playerIds }) { "Záloha obsahuje účastníka bez hráče nebo turnaje." }
        require(s.matches.all { it.tournamentId in tournamentIds }) { "Záloha obsahuje zápas bez turnaje." }
        require(s.matches.all { m ->
            val enrolled = participantsByTournament[m.tournamentId].orEmpty().map { it.playerId }.toSet()
            listOfNotNull(m.player1Id, m.player2Id, m.selectedWinnerId, m.winnerId, m.loserId).all { it in enrolled }
        }) { "Záloha obsahuje zápas s hráčem, který nepatří do daného turnaje." }
        require(s.matches.all { m ->
            val ownNumbers = matchesByTournament[m.tournamentId].orEmpty().map { it.matchNumber }.toSet()
            (m.nextWinnerMatchNumber == null || m.nextWinnerMatchNumber in ownNumbers) &&
                (m.nextLoserMatchNumber == null || m.nextLoserMatchNumber in ownNumbers)
        }) { "Některý zápas odkazuje na neexistující navazující zápas." }
        require(s.tournaments.all { t -> t.winnerId == null || t.winnerId in participantsByTournament[t.id].orEmpty().map { it.playerId }.toSet() }) {
            "Záloha obsahuje vítěze, který není účastníkem daného turnaje."
        }
        require(s.points.all { it.playerId in playerIds && it.tournamentId in tournamentIds && (it.matchId == null || it.matchId in matchIds) }) {
            "Záloha obsahuje bodový záznam bez odpovídajícího hráče, turnaje nebo zápasu."
        }
        require(s.points.all { it.playerId in participantsByTournament[it.tournamentId].orEmpty().map { p -> p.playerId }.toSet() }) {
            "Bodový záznam patří hráči, který nebyl účastníkem daného turnaje."
        }
    }

    private data class BackupSnapshot(
        val clans: List<ClanEntity>,
        val players: List<PlayerEntity>,
        val tournaments: List<TournamentEntity>,
        val participants: List<TournamentParticipantEntity>,
        val matches: List<MatchEntity>,
        val points: List<PointTransactionEntity>
    )

    private fun ClanEntity.toJson() = JSONObject()
        .put("id", id).put("name", name).put("createdAt", createdAt)
    private fun PlayerEntity.toJson() = JSONObject()
        .put("id", id).put("name", name).putLongOrNull("clanId", clanId)
        .put("isArchived", isArchived).put("createdAt", createdAt)
    private fun TournamentEntity.toJson() = JSONObject()
        .put("id", id).put("name", name).put("dateIso", dateIso).put("note", note).put("status", status)
        .putLongOrNull("winnerId", winnerId).put("createdAt", createdAt).putLongOrNull("finishedAt", finishedAt)
    private fun TournamentParticipantEntity.toJson() = JSONObject()
        .put("tournamentId", tournamentId).put("playerId", playerId).put("playerNameSnapshot", playerNameSnapshot)
        .putLongOrNull("clanIdSnapshot", clanIdSnapshot).putStringOrNull("clanNameSnapshot", clanNameSnapshot).put("seed", seed).put("isQueued", isQueued)
    private fun MatchEntity.toJson() = JSONObject()
        .put("id", id).put("tournamentId", tournamentId).put("matchNumber", matchNumber).put("matchKey", matchKey)
        .put("roundNumber", roundNumber).put("branch", branch).put("status", status)
        .putLongOrNull("player1Id", player1Id).putStringOrNull("player1NameSnapshot", player1NameSnapshot)
        .putLongOrNull("player2Id", player2Id).putStringOrNull("player2NameSnapshot", player2NameSnapshot)
        .putLongOrNull("selectedWinnerId", selectedWinnerId).putLongOrNull("winnerId", winnerId).putLongOrNull("loserId", loserId)
        .putIntOrNull("nextWinnerMatchNumber", nextWinnerMatchNumber).putIntOrNull("winnerTargetSlot", winnerTargetSlot)
        .putIntOrNull("nextLoserMatchNumber", nextLoserMatchNumber).putIntOrNull("loserTargetSlot", loserTargetSlot)
        .put("isTournamentFinal", isTournamentFinal).put("byeConfirmed", byeConfirmed).putLongOrNull("completedAt", completedAt)
    private fun PointTransactionEntity.toJson() = JSONObject()
        .put("id", id).put("playerId", playerId).put("tournamentId", tournamentId)
        .putLongOrNull("matchId", matchId).putIntOrNull("matchNumber", matchNumber).put("points", points)
        .put("reason", reason).putLongOrNull("clanIdSnapshot", clanIdSnapshot)
        .putStringOrNull("clanNameSnapshot", clanNameSnapshot).put("createdAt", createdAt)

    private fun JSONObject.toClan() = ClanEntity(getLong("id"), getString("name"), getLong("createdAt"))
    private fun JSONObject.toPlayer() = PlayerEntity(
        id = getLong("id"), name = getString("name"), clanId = longOrNull("clanId"),
        isArchived = getBoolean("isArchived"), createdAt = getLong("createdAt")
    )
    private fun JSONObject.toTournament() = TournamentEntity(
        id = getLong("id"), name = getString("name"), dateIso = getString("dateIso"), note = getString("note"),
        status = getString("status"), winnerId = longOrNull("winnerId"), createdAt = getLong("createdAt"),
        finishedAt = longOrNull("finishedAt")
    )
    private fun JSONObject.toParticipant() = TournamentParticipantEntity(
        tournamentId = getLong("tournamentId"), playerId = getLong("playerId"),
        playerNameSnapshot = getString("playerNameSnapshot"), clanIdSnapshot = longOrNull("clanIdSnapshot"),
        clanNameSnapshot = stringOrNull("clanNameSnapshot"), seed = getInt("seed"), isQueued = optBoolean("isQueued", false)
    )
    private fun JSONObject.toMatch() = MatchEntity(
        id = getLong("id"), tournamentId = getLong("tournamentId"), matchNumber = getInt("matchNumber"),
        matchKey = getString("matchKey"), roundNumber = getInt("roundNumber"), branch = getString("branch"), status = getString("status"),
        player1Id = longOrNull("player1Id"), player1NameSnapshot = stringOrNull("player1NameSnapshot"),
        player2Id = longOrNull("player2Id"), player2NameSnapshot = stringOrNull("player2NameSnapshot"),
        selectedWinnerId = longOrNull("selectedWinnerId"), winnerId = longOrNull("winnerId"), loserId = longOrNull("loserId"),
        nextWinnerMatchNumber = intOrNull("nextWinnerMatchNumber"), winnerTargetSlot = intOrNull("winnerTargetSlot"),
        nextLoserMatchNumber = intOrNull("nextLoserMatchNumber"), loserTargetSlot = intOrNull("loserTargetSlot"),
        isTournamentFinal = getBoolean("isTournamentFinal"), byeConfirmed = getBoolean("byeConfirmed"), completedAt = longOrNull("completedAt")
    )
    private fun JSONObject.toPoint() = PointTransactionEntity(
        id = getLong("id"), playerId = getLong("playerId"), tournamentId = getLong("tournamentId"),
        matchId = longOrNull("matchId"), matchNumber = intOrNull("matchNumber"), points = getInt("points"),
        reason = getString("reason"), clanIdSnapshot = longOrNull("clanIdSnapshot"),
        clanNameSnapshot = stringOrNull("clanNameSnapshot"), createdAt = getLong("createdAt")
    )

    private fun JSONObject.putLongOrNull(key: String, value: Long?) = put(key, value ?: JSONObject.NULL)
    private fun JSONObject.putIntOrNull(key: String, value: Int?) = put(key, value ?: JSONObject.NULL)
    private fun JSONObject.putStringOrNull(key: String, value: String?) = put(key, value ?: JSONObject.NULL)
    private fun JSONObject.longOrNull(key: String): Long? = if (isNull(key)) null else getLong(key)
    private fun JSONObject.intOrNull(key: String): Int? = if (isNull(key)) null else getInt(key)
    private fun JSONObject.stringOrNull(key: String): String? = if (isNull(key)) null else getString(key)
    private inline fun <reified T> JSONArray.mapObjects(noinline transform: (JSONObject) -> T): List<T> =
        (0 until length()).map { transform(getJSONObject(it)) }
}
