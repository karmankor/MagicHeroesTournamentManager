package cz.magicheroes.tournamentmanager.data

import androidx.room.withTransaction
import cz.magicheroes.tournamentmanager.domain.BracketEngine
import cz.magicheroes.tournamentmanager.domain.EloRating
import cz.magicheroes.tournamentmanager.domain.Entrant
import cz.magicheroes.tournamentmanager.domain.ScoringRules

class TournamentRepository(private val database: AppDatabase) {
    private val dao = database.tournamentDao()

    suspend fun addPlayer(name: String, clanId: Long?) {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "Zadej jméno hráče." }
        require(clean.length <= 60) { "Jméno může mít nejvýše 60 znaků." }
        if (clanId != null) require(dao.getClan(clanId) != null) { "Vybraný klan už neexistuje." }
        dao.insertPlayer(PlayerEntity(name = clean, clanId = clanId))
    }

    suspend fun updatePlayer(id: Long, name: String, clanId: Long?) {
        val old = dao.getPlayer(id) ?: error("Hráč už neexistuje.")
        val clean = name.trim()
        require(clean.isNotEmpty()) { "Zadej jméno hráče." }
        require(clean.length <= 60) { "Jméno může mít nejvýše 60 znaků." }
        if (clanId != null) require(dao.getClan(clanId) != null) { "Vybraný klan už neexistuje." }
        if (old.clanId != clanId) {
            val activeIds = dao.getTournaments().filter { it.status == TournamentEntity.STATUS_ACTIVE }.map { it.id }
            val isPlaying = activeIds.any { tournamentId -> dao.getParticipants(tournamentId).any { it.playerId == id && !it.isWithdrawn } }
            require(!isPlaying) { "Klan hráče nelze měnit během aktivního turnaje. Po jeho ukončení můžeš přestup provést." }
        }
        dao.updatePlayer(old.copy(name = clean, clanId = clanId))
    }

    /** Soft deletion retains all foreign IDs and historical results. */
    suspend fun archivePlayer(id: Long) {
        val activeTournamentIds = dao.getTournaments().filter { it.status == TournamentEntity.STATUS_ACTIVE }.map { it.id }
        activeTournamentIds.forEach { tournamentId ->
            require(dao.getParticipants(tournamentId).none { it.playerId == id && !it.isWithdrawn }) {
                "Hráče nelze archivovat během aktivního turnaje. Nejprve turnaj ukonči."
            }
        }
        dao.archivePlayer(id)
    }

    suspend fun addClan(name: String) {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "Zadej název klanu." }
        require(clean.length <= 50) { "Název klanu může mít nejvýše 50 znaků." }
        dao.insertClan(ClanEntity(name = clean))
    }

    suspend fun updateClan(id: Long, name: String) {
        val old = dao.getClan(id) ?: error("Klan už neexistuje.")
        val clean = name.trim()
        require(clean.isNotEmpty()) { "Zadej název klanu." }
        require(clean.length <= 50) { "Název klanu může mít nejvýše 50 znaků." }
        dao.updateClan(old.copy(name = clean))
    }

    suspend fun deleteClan(id: Long) = database.withTransaction {
        // Current players become clanless; historic participant and score snapshots remain intact.
        dao.removePlayersFromClan(id)
        dao.deleteClan(id)
    }

    suspend fun createAndStartTournament(
        name: String,
        dateIso: String,
        note: String,
        playerIds: List<Long>
    ): Long = database.withTransaction {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "Zadej název turnaje." }
        require(cleanName.length <= 100) { "Název turnaje může mít nejvýše 100 znaků." }
        val uniqueIds = playerIds.distinct()
        require(uniqueIds.size in 2..32) { "Vyber 2 až 32 různých hráčů." }

        val activeTournaments = dao.getTournaments().filter { it.status == TournamentEntity.STATUS_ACTIVE }
        val activePlayers = activeTournaments.flatMap { dao.getParticipants(it.id).filterNot { participant -> participant.isWithdrawn } }.map { it.playerId }.toSet()
        require(uniqueIds.none { it in activePlayers }) {
            "Některý z vybraných hráčů už je v jiném aktivním turnaji."
        }

        val playersById = dao.getPlayers().associateBy { it.id }
        val clansById = dao.getClans().associateBy { it.id }
        require(uniqueIds.all { playersById[it] != null }) { "Některý vybraný hráč už není dostupný." }

        val tournamentId = dao.insertTournament(
            TournamentEntity(name = cleanName, dateIso = dateIso, note = note.trim(), status = TournamentEntity.STATUS_ACTIVE)
        )
        val entrants = uniqueIds.mapIndexed { index, id ->
            val player = playersById.getValue(id)
            val clan = player.clanId?.let(clansById::get)
            TournamentParticipantEntity(
                tournamentId = tournamentId,
                playerId = player.id,
                playerNameSnapshot = player.name,
                clanIdSnapshot = clan?.id,
                clanNameSnapshot = clan?.name,
                seed = index + 1
            )
        }
        dao.insertParticipants(entrants)
        val bracketEntrants = entrants.map {
            Entrant(it.playerId, it.playerNameSnapshot, it.clanIdSnapshot, it.clanNameSnapshot)
        }
        dao.insertMatches(BracketEngine.createPlan(tournamentId, bracketEntrants))
        tournamentId
    }

    suspend fun addPlayerToWaitlist(tournamentId: Long, playerId: Long) = database.withTransaction {
        val tournament = dao.getTournament(tournamentId) ?: error("Turnaj neexistuje.")
        require(tournament.status == TournamentEntity.STATUS_ACTIVE) { "Do čekací fronty lze přidávat jen během aktivního turnaje." }
        val player = dao.getPlayer(playerId) ?: error("Hráč už neexistuje.")
        require(!player.isArchived) { "Archivovaného hráče nelze přidat do turnaje." }
        val participants = dao.getParticipants(tournamentId)
        require(participants.none { it.playerId == playerId }) { "Hráč už je účastníkem nebo čeká ve frontě." }
        val activeTournamentIds = dao.getTournaments().filter { it.status == TournamentEntity.STATUS_ACTIVE }.map { it.id }
        val alreadyPlaying = activeTournamentIds.any { id -> dao.getParticipants(id).any { it.playerId == playerId && !it.isWithdrawn } }
        require(!alreadyPlaying) { "Hráč už je přihlášený do jiného aktivního turnaje." }
        val clan = player.clanId?.let { dao.getClan(it) }
        dao.insertParticipants(listOf(TournamentParticipantEntity(
            tournamentId = tournamentId,
            playerId = player.id,
            playerNameSnapshot = player.name,
            clanIdSnapshot = clan?.id,
            clanNameSnapshot = clan?.name,
            seed = (participants.maxOfOrNull { it.seed } ?: 0) + 1,
            isQueued = true
        )))
    }

    suspend fun createPlayerInWaitlist(tournamentId: Long, name: String) = database.withTransaction {
        val tournament = dao.getTournament(tournamentId) ?: error("Turnaj neexistuje.")
        require(tournament.status == TournamentEntity.STATUS_ACTIVE) { "Do čekací fronty lze přidávat jen během aktivního turnaje." }
        val clean = name.trim()
        require(clean.isNotEmpty()) { "Zadej jméno hráče." }
        require(clean.length <= 60) { "Jméno může mít nejvýše 60 znaků." }
        val playerId = dao.insertPlayer(PlayerEntity(name = clean))
        val participants = dao.getParticipants(tournamentId)
        dao.insertParticipants(listOf(TournamentParticipantEntity(
            tournamentId = tournamentId,
            playerId = playerId,
            playerNameSnapshot = clean,
            seed = (participants.maxOfOrNull { it.seed } ?: 0) + 1,
            isQueued = true
        )))
    }

    suspend fun promoteNextWaitlistedPlayer(tournamentId: Long) = database.withTransaction {
        val tournament = dao.getTournament(tournamentId) ?: error("Turnaj neexistuje.")
        require(tournament.status == TournamentEntity.STATUS_ACTIVE) { "Hráče lze zařadit do bracketu jen během aktivního turnaje." }
        val participants = dao.getParticipants(tournamentId)
        val queued = participants.filter { it.isQueued }.minByOrNull { it.seed }
            ?: error("Čekací fronta je prázdná.")
        val active = participants.filterNot { it.isQueued || it.isWithdrawn }
        val matches = dao.getMatches(tournamentId)
        val openingMatches = matches.filter { it.branch == "WINNERS" && it.roundNumber == 1 }
        val capacity = openingMatches.size * 2
        val occupiedSeeds = active.map { it.seed }.toSet()
        val targetSeed = (1..capacity).firstOrNull { seed ->
            if (seed in occupiedSeeds) return@firstOrNull false
            val key = "W1_${(seed - 1) / 2}"
            val target = openingMatches.firstOrNull { it.matchKey == key } ?: return@firstOrNull false
            val slotPlayerId = if ((seed - 1) % 2 == 0) target.player1Id else target.player2Id
            slotPlayerId == null && target.winnerId == null && target.selectedWinnerId == null &&
                !target.byeConfirmed && target.status != "IN_PROGRESS"
        } ?: error("V bracketu teď není volné, neodehrané místo pro dalšího hráče.")
        dao.updateParticipant(queued.copy(isQueued = false, isWithdrawn = false, seed = targetSeed))
        rebuildBracket(tournamentId)
    }

    /** Swap the opening-round seeds of two players in different first-round matches. */
    suspend fun swapOpeningEntrants(tournamentId: Long, firstPlayerId: Long, secondPlayerId: Long, confirmed: Boolean) =
        database.withTransaction {
            require(firstPlayerId != secondPlayerId) { "Vyber dva různé hráče." }
            val tournament = dao.getTournament(tournamentId) ?: error("Turnaj neexistuje.")
            require(tournament.status == TournamentEntity.STATUS_ACTIVE || tournament.status == TournamentEntity.STATUS_FINISHED) {
                "Dvojice lze upravovat pouze v zahájeném turnaji."
            }
            val participants = dao.getParticipants(tournamentId)
            val active = participants.filterNot { it.isQueued || it.isWithdrawn }.sortedBy { it.seed }
            val first = active.firstOrNull { it.playerId == firstPlayerId } ?: error("První hráč není aktivním účastníkem turnaje.")
            val second = active.firstOrNull { it.playerId == secondPlayerId } ?: error("Druhý hráč není aktivním účastníkem turnaje.")
            val firstIndex = active.indexOf(first)
            val secondIndex = active.indexOf(second)
            require(firstIndex / 2 != secondIndex / 2) {
                "Vybraní hráči už jsou ve stejné úvodní dvojici. Vyber hráče z jiného zápasu."
            }
            val hasActivity = dao.getMatches(tournamentId).any {
                it.selectedWinnerId != null || it.winnerId != null || it.byeConfirmed || it.status == "IN_PROGRESS"
            }
            require(confirmed || !hasActivity) {
                "Výměna může ovlivnit již zapsané zápasy. Potvrď přepočítání pavouka."
            }
            dao.updateParticipant(first.copy(seed = second.seed))
            dao.updateParticipant(second.copy(seed = first.seed))
            rebuildBracket(tournamentId)
            rebuildPoints(tournamentId)
            rebuildEloRatings()
        }

    suspend fun withdrawPlayer(tournamentId: Long, playerId: Long) = database.withTransaction {
        val tournament = dao.getTournament(tournamentId) ?: error("Turnaj neexistuje.")
        require(tournament.status == TournamentEntity.STATUS_ACTIVE) { "Odstoupit lze pouze z aktivního turnaje." }
        val participant = dao.getParticipants(tournamentId).firstOrNull { it.playerId == playerId }
            ?: error("Hráč není účastníkem tohoto turnaje.")
        require(!participant.isQueued && !participant.isWithdrawn) { "Tento hráč už čeká nebo z turnaje odstoupil." }
        val hasInProgressMatch = dao.getMatches(tournamentId).any {
            it.status == "IN_PROGRESS" && (it.player1Id == playerId || it.player2Id == playerId)
        }
        require(!hasInProgressMatch) { "Nejprve dokonči nebo zruš právě rozehraný zápas." }
        dao.updateParticipant(participant.copy(isWithdrawn = true))
        rebuildBracket(tournamentId)
        rebuildPoints(tournamentId)
        rebuildEloRatings()
    }

    suspend fun recalculateEloRatings() = database.withTransaction {
        rebuildEloRatings()
    }

    suspend fun setMatchInProgress(tournamentId: Long, matchNumber: Int) = database.withTransaction {
        val tournament = dao.getTournament(tournamentId) ?: error("Turnaj neexistuje.")
        require(tournament.status == TournamentEntity.STATUS_ACTIVE) { "Tento turnaj už není aktivní." }
        val match = dao.getMatches(tournamentId).firstOrNull { it.matchNumber == matchNumber }
            ?: error("Zápas neexistuje.")
        require(match.player1Id != null && match.player2Id != null) { "Zápas ještě nemá oba hráče." }
        require(match.winnerId == null) { "Zápas už je dokončený. Výsledek lze opravit." }
        dao.updateMatch(match.copy(status = "IN_PROGRESS"))
    }

    suspend fun recordWinner(tournamentId: Long, matchNumber: Int, winnerId: Long) = database.withTransaction {
        val tournament = dao.getTournament(tournamentId) ?: error("Turnaj neexistuje.")
        require(tournament.status == TournamentEntity.STATUS_ACTIVE || tournament.status == TournamentEntity.STATUS_FINISHED) {
            "Výsledky nelze měnit, dokud turnaj není zahájený."
        }
        val match = dao.getMatches(tournamentId).firstOrNull { it.matchNumber == matchNumber }
            ?: error("Zápas neexistuje.")
        require(match.player1Id != null && match.player2Id != null) { "Zápas nemá oba hráče. Použij výslovný postup bez zápasu, pokud ho chceš potvrdit." }
        require(winnerId == match.player1Id || winnerId == match.player2Id) { "Vítěz musí být jedním z hráčů tohoto zápasu." }
        dao.updateMatch(match.copy(selectedWinnerId = winnerId, byeConfirmed = false, completedAt = System.currentTimeMillis()))
        rebuildBracket(tournamentId)
        rebuildPoints(tournamentId)
        rebuildEloRatings()
    }

    suspend fun advanceWithoutMatch(tournamentId: Long, matchNumber: Int) = database.withTransaction {
        val tournament = dao.getTournament(tournamentId) ?: error("Turnaj neexistuje.")
        require(tournament.status == TournamentEntity.STATUS_ACTIVE || tournament.status == TournamentEntity.STATUS_FINISHED) {
            "Postup bez zápasu nelze zapsat do nezahájeného turnaje."
        }
        val match = dao.getMatches(tournamentId).firstOrNull { it.matchNumber == matchNumber }
            ?: error("Zápas neexistuje.")
        val solo = listOfNotNull(match.player1Id, match.player2Id).singleOrNull()
            ?: error("Tento zápas nemá přesně jednoho hráče bez soupeře.")
        dao.updateMatch(match.copy(selectedWinnerId = solo, byeConfirmed = true, completedAt = System.currentTimeMillis()))
        rebuildBracket(tournamentId)
        rebuildPoints(tournamentId)
        rebuildEloRatings()
    }

    suspend fun finishTournament(tournamentId: Long) = database.withTransaction {
        val tournament = dao.getTournament(tournamentId) ?: error("Turnaj neexistuje.")
        require(tournament.status != TournamentEntity.STATUS_FINISHED) { "Turnaj je už ukončený." }
        val matches = dao.getMatches(tournamentId)
        val finalMatch = matches.firstOrNull { it.isTournamentFinal }
        dao.updateTournament(
            tournament.copy(
                status = TournamentEntity.STATUS_FINISHED,
                winnerId = finalMatch?.winnerId,
                finishedAt = System.currentTimeMillis()
            )
        )
    }

    private suspend fun rebuildBracket(tournamentId: Long) {
        val storedMatches = dao.getMatches(tournamentId)
        // Keep the last persisted matchup for each node. If an upstream result changes the
        // players entering a downstream node, that node's old result must be invalidated even
        // when its previously selected winner happens to still be one of the new players.
        val previousByNumber = storedMatches.associateBy { it.matchNumber }
        val participants = dao.getParticipants(tournamentId).filterNot { it.isQueued }.sortedBy { it.seed }
        val participantById = participants.associateBy { it.playerId }
        // Seeds are fixed seat numbers. A withdrawal leaves a hole instead of shifting all later
        // entrants to new opponents. A player who already completed their opening match remains
        // in that historical match, but is not sent to a future round.
        val participantBySeed = participants.filterNot { it.isWithdrawn }.associateBy { it.seed }
        val working = storedMatches.associate { it.matchNumber to it }.toMutableMap()

        // Clear all derived positions and results; keep the organizer's chosen winners.
        storedMatches.forEach { match ->
            val openingIndex = match.matchKey.removePrefix("W1_").toIntOrNull()
            val directTwoPlayerFinal = match.matchKey == "G" && storedMatches.size == 1
            var p1: TournamentParticipantEntity? = null
            var p2: TournamentParticipantEntity? = null
            if (openingIndex != null && match.branch == "WINNERS" && match.roundNumber == 1) {
                val previous = previousByNumber[match.matchNumber] ?: match
                val firstSeed = openingIndex * 2 + 1
                val secondSeed = openingIndex * 2 + 2
                p1 = participantBySeed[firstSeed]
                    ?: previous.player1Id?.let(participantById::get)?.takeIf { it.isWithdrawn && previous.winnerId != null }
                p2 = participantBySeed[secondSeed]
                    ?: previous.player2Id?.let(participantById::get)?.takeIf { it.isWithdrawn && previous.winnerId != null }
            } else if (directTwoPlayerFinal) {
                p1 = participants.getOrNull(0)
                p2 = participants.getOrNull(1)
            }
            working[match.matchNumber] = match.copy(
                player1Id = p1?.playerId,
                player1NameSnapshot = p1?.playerNameSnapshot,
                player2Id = p2?.playerId,
                player2NameSnapshot = p2?.playerNameSnapshot,
                winnerId = null,
                loserId = null,
                status = if (match.status == "IN_PROGRESS" && match.selectedWinnerId == null) "IN_PROGRESS" else "WAITING_FOR_PLAYERS",
                completedAt = match.completedAt
            )
        }

        val order = BracketEngine.topologicalOrder(storedMatches)
        for (number in order) {
            var current = working.getValue(number)
            val p1 = current.player1Id
            val p2 = current.player2Id
            var winner: Long? = null
            var loser: Long? = null
            val previous = previousByNumber[number]
            val matchupChanged = previous != null &&
                !BracketEngine.hasSameEntrants(previous, p1, p2)
            var selected = if (matchupChanged) null else current.selectedWinnerId
            var bye = if (matchupChanged) false else current.byeConfirmed
            var completedAt = if (matchupChanged) null else current.completedAt
            if (matchupChanged && current.status == "IN_PROGRESS") {
                current = current.copy(status = "READY")
            }

            if (p1 != null && p2 != null && p1 != p2) {
                if (selected == p1) winner = p1 else if (selected == p2) winner = p2
                if (winner != null) {
                    loser = if (winner == p1) p2 else p1
                    current = current.copy(status = "COMPLETED")
                    completedAt = completedAt ?: System.currentTimeMillis()
                } else {
                    selected = null
                    bye = false
                    completedAt = null
                    current = current.copy(status = if (current.status == "IN_PROGRESS") "IN_PROGRESS" else "READY")
                }
            } else if ((p1 != null || p2 != null) && bye) {
                val solo = p1 ?: p2
                if (selected == solo) {
                    winner = solo
                    current = current.copy(status = "COMPLETED")
                    completedAt = completedAt ?: System.currentTimeMillis()
                } else {
                    selected = null
                    bye = false
                    completedAt = null
                    current = current.copy(status = "WAITING_FOR_OPPONENT")
                }
            } else {
                selected = null
                bye = false
                completedAt = null
                current = current.copy(status = if (p1 != null || p2 != null) "WAITING_FOR_OPPONENT" else "WAITING_FOR_PLAYERS")
            }

            current = current.copy(
                selectedWinnerId = selected,
                byeConfirmed = bye,
                winnerId = winner,
                loserId = loser,
                completedAt = completedAt
            )
            working[number] = current

            if (winner != null && current.nextWinnerMatchNumber != null && participantById[winner]?.isWithdrawn != true) {
                val sourceName = participantById[winner]?.playerNameSnapshot
                assignSlot(working, current.nextWinnerMatchNumber, current.winnerTargetSlot, winner, sourceName)
            }
            if (loser != null && current.nextLoserMatchNumber != null && participantById[loser]?.isWithdrawn != true) {
                val sourceName = participantById[loser]?.playerNameSnapshot
                assignSlot(working, current.nextLoserMatchNumber, current.loserTargetSlot, loser, sourceName)
            }
        }

        // Persist derived bracket state. Any downstream result whose player changed is cleared
        // when its match is evaluated in topological order above.
        working.values.forEach { dao.updateMatch(it) }
        val finalWinner = working.values.firstOrNull { it.isTournamentFinal }?.winnerId
        val tournament = dao.getTournament(tournamentId) ?: return
        if (tournament.winnerId != finalWinner) dao.updateTournament(tournament.copy(winnerId = finalWinner))
    }

    private fun assignSlot(
        map: MutableMap<Int, MatchEntity>,
        targetNumber: Int,
        slot: Int?,
        playerId: Long,
        playerName: String?
    ) {
        require(slot == 0 || slot == 1) { "Chybně definovaný cíl v turnajovém bracketu." }
        val target = map[targetNumber] ?: error("Navazující zápas $targetNumber neexistuje.")
        val existing = if (slot == 0) target.player1Id else target.player2Id
        require(existing == null || existing == playerId) {
            "Konflikt v bracketu: hráč by byl přiřazen do obsazené pozice zápasu $targetNumber."
        }
        map[targetNumber] = if (slot == 0) {
            target.copy(player1Id = playerId, player1NameSnapshot = playerName)
        } else {
            target.copy(player2Id = playerId, player2NameSnapshot = playerName)
        }
    }

    private suspend fun rebuildEloRatings() {
        val players = dao.getAllPlayersForBackup()
        if (players.isEmpty()) return
        val ratings = EloRating.recalculate(players.map { it.id }, dao.getAllMatchesForBackup())
        players.forEach { player ->
            val rating = ratings[player.id] ?: EloRating.INITIAL_RATING
            if (player.eloRating != rating) dao.updatePlayer(player.copy(eloRating = rating))
        }
    }

    private suspend fun rebuildPoints(tournamentId: Long) {
        val matches = dao.getMatches(tournamentId)
        val participants = dao.getParticipants(tournamentId).associateBy { it.playerId }
        dao.deleteTournamentPoints(tournamentId)
        val ledger = mutableListOf<PointTransactionEntity>()
        for (match in matches) {
            val winner = match.winnerId ?: continue
            val p1 = match.player1Id
            val p2 = match.player2Id
            // A confirmed no-opponent advance is progression, not a played win.
            if (p1 != null && p2 != null && p1 != p2) {
                val snap = participants[winner]
                ScoringRules.pointsForWinner(wasPlayed = true, isTournamentFinal = match.isTournamentFinal).forEach { (amount, reason) ->
                    ledger += PointTransactionEntity(
                        playerId = winner,
                        tournamentId = tournamentId,
                        matchId = match.id.takeIf { it != 0L },
                        matchNumber = match.matchNumber,
                        points = amount,
                        reason = reason,
                        clanIdSnapshot = snap?.clanIdSnapshot,
                        clanNameSnapshot = snap?.clanNameSnapshot,
                        createdAt = match.completedAt ?: System.currentTimeMillis()
                    )
                }
            }
        }
        if (ledger.isNotEmpty()) dao.insertPointTransactions(ledger)
    }
}
