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

    suspend fun adjustClanPoints(clanId: Long, delta: Int, reason: String) = database.withTransaction {
        require(delta != 0) { "Změna bodů nesmí být nula." }
        require(kotlin.math.abs(delta.toLong()) <= 1_000_000L) { "Jedna úprava může být nejvýše 1 000 000 bodů." }
        val clan = dao.getClan(clanId) ?: error("Klan už neexistuje.")
        val cleanReason = reason.trim().ifBlank { "Ruční úprava bodů" }
        require(cleanReason.length <= 120) { "Důvod může mít nejvýše 120 znaků." }
        dao.insertClanPointAdjustment(
            ClanPointAdjustmentEntity(
                clanIdSnapshot = clan.id,
                clanNameSnapshot = clan.name,
                points = delta,
                reason = cleanReason
            )
        )
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

        // A player may participate in multiple active tournaments; membership is scoped by tournamentId.

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
        // Cross-tournament participation is allowed; duplicate membership within this tournament is checked above.
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
            require(dao.getMatches(tournamentId).none { it.status == "IN_PROGRESS" }) {
                "Nejprve dokonči nebo zruš rozehraný zápas, potom uprav úvodní dvojice."
            }
            val first = active.firstOrNull { it.playerId == firstPlayerId } ?: error("První hráč není aktivním účastníkem turnaje.")
            val second = active.firstOrNull { it.playerId == secondPlayerId } ?: error("Druhý hráč není aktivním účastníkem turnaje.")
            require((first.seed - 1) / 2 != (second.seed - 1) / 2) {
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

    suspend fun setManualMatchSlot(tournamentId: Long, matchNumber: Int, slot: Int, playerId: Long?, locked: Boolean) = database.withTransaction {
        require(slot in 0..1) { "Vyber první nebo druhou pozici." }
        val tournament = dao.getTournament(tournamentId) ?: error("Turnaj neexistuje.")
        require(tournament.status == TournamentEntity.STATUS_ACTIVE) { "Ruční pozice lze měnit jen v aktivním turnaji." }
        val matches = dao.getMatches(tournamentId)
        val target = matches.firstOrNull { it.matchNumber == matchNumber } ?: error("Zápas neexistuje.")
        val previousSlotPlayerId = if (slot == 0) target.player1Id else target.player2Id
        require(target.winnerId == null && target.selectedWinnerId == null && target.status != "IN_PROGRESS") {
            "Obsazení lze měnit jen v neodehraném a nerozehraném zápase."
        }
        if (playerId != null) {
            val participant = dao.getParticipants(tournamentId).firstOrNull { it.playerId == playerId }
                ?: error("Vybraný hráč není účastníkem turnaje.")
            require(!participant.isWithdrawn) { "Odstoupivšího hráče nelze znovu umístit." }
            require(matches.none { it.status == "IN_PROGRESS" && (it.player1Id == playerId || it.player2Id == playerId) }) {
                "Hráč právě hraje rozehraný zápas. Nejprve ho dokonči."
            }
            require((if (slot == 0) target.player2Id else target.player1Id) != playerId) {
                "Stejný hráč nemůže být v jednom zápase v obou pozicích."
            }
            val openingCapacity = matches.count { it.branch == "WINNERS" && it.roundNumber == 1 } * 2
            when {
                locked && participant.isQueued ->
                    dao.updateParticipant(participant.copy(isQueued = false, isWithdrawn = false))
                !locked && participant.seed > openingCapacity && !participant.isQueued ->
                    dao.updateParticipant(participant.copy(isQueued = true))
            }
        }

        // Keep manual pins on finished matches for history; clear a pending pin if the same
        // player is being moved to a different currently unplayed match.
        if (playerId != null) {
            matches.filter { it.matchNumber != matchNumber && it.selectedWinnerId == null && it.winnerId == null }.forEach { old ->
                var updatedMatch = old
                if (old.manualPlayer1Locked && old.manualPlayer1Id == playerId) {
                    updatedMatch = updatedMatch.copy(manualPlayer1Locked = false, manualPlayer1Id = null, manualPlayer1AssignedAt = null)
                }
                if (old.manualPlayer2Locked && old.manualPlayer2Id == playerId) {
                    updatedMatch = updatedMatch.copy(manualPlayer2Locked = false, manualPlayer2Id = null, manualPlayer2AssignedAt = null)
                }
                if (updatedMatch != old) dao.updateMatch(updatedMatch)
            }
        }

        // If a late entrant with no played history is removed from their only manual seat,
        // return them to the waitlist instead of silently leaving them outside the bracket.
        if (previousSlotPlayerId != null && previousSlotPlayerId != playerId) {
            val previousParticipant = dao.getParticipants(tournamentId).firstOrNull { it.playerId == previousSlotPlayerId }
            val openingCapacity = matches.count { it.branch == "WINNERS" && it.roundNumber == 1 } * 2
            val hasPlayedHistory = matches.any { match ->
                match.winnerId != null && (
                    match.player1Id == previousSlotPlayerId ||
                        match.player2Id == previousSlotPlayerId ||
                        match.winnerId == previousSlotPlayerId ||
                        match.loserId == previousSlotPlayerId
                    )
            }
            val hasAnotherManualPin = matches.any { match ->
                match.matchNumber != matchNumber &&
                    ((match.manualPlayer1Locked && match.manualPlayer1Id == previousSlotPlayerId) ||
                        (match.manualPlayer2Locked && match.manualPlayer2Id == previousSlotPlayerId))
            }
            if (previousParticipant != null && previousParticipant.seed > openingCapacity &&
                !previousParticipant.isQueued && !previousParticipant.isWithdrawn &&
                !hasPlayedHistory && !hasAnotherManualPin
            ) {
                dao.updateParticipant(previousParticipant.copy(isQueued = true))
            }
        }

        val now = System.currentTimeMillis()
        val updatedTarget = if (slot == 0) {
            target.copy(
                manualPlayer1Locked = locked,
                manualPlayer1Id = playerId.takeIf { locked },
                manualPlayer1AssignedAt = now.takeIf { locked }
            )
        } else {
            target.copy(
                manualPlayer2Locked = locked,
                manualPlayer2Id = playerId.takeIf { locked },
                manualPlayer2AssignedAt = now.takeIf { locked }
            )
        }
        dao.updateMatch(updatedTarget)
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
        dao.getMatches(tournamentId)
            .filter { it.winnerId == null && it.selectedWinnerId == null }
            .forEach { match ->
                var cleared = match
                if (match.manualPlayer1Id == playerId) {
                    cleared = cleared.copy(manualPlayer1Locked = false, manualPlayer1Id = null, manualPlayer1AssignedAt = null)
                }
                if (match.manualPlayer2Id == playerId) {
                    cleared = cleared.copy(manualPlayer2Locked = false, manualPlayer2Id = null, manualPlayer2AssignedAt = null)
                }
                if (cleared != match) dao.updateMatch(cleared)
            }
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
        dao.updateMatch(match.copy(
            selectedWinnerId = winnerId,
            byeConfirmed = false,
            completedAt = match.completedAt ?: System.currentTimeMillis()
        ))
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

    /** Permanently removes one non-active tournament and all data owned by it. */
    suspend fun deleteTournament(tournamentId: Long) = database.withTransaction {
        val tournament = dao.getTournament(tournamentId) ?: error("Turnaj neexistuje.")
        require(tournament.status != TournamentEntity.STATUS_ACTIVE) {
            "Aktivní turnaj nelze smazat. Nejprve ho ukonči."
        }
        dao.deleteTournamentPoints(tournamentId)
        dao.deleteTournamentMatches(tournamentId)
        dao.deleteTournamentParticipants(tournamentId)
        dao.deleteTournament(tournamentId)
        // ELO is derived from match history, so recalculate it after removing these matches.
        rebuildEloRatings()
    }

    private data class ManualPlacement(
        val matchNumber: Int,
        val slot: Int,
        val playerId: Long,
        val assignedAt: Long
    )

    private suspend fun rebuildBracket(tournamentId: Long) {
        val storedMatches = dao.getMatches(tournamentId)
        val previousByNumber = storedMatches.associateBy { it.matchNumber }
        val participants = dao.getParticipants(tournamentId).filterNot { it.isQueued }.sortedBy { it.seed }
        val participantById = participants.associateBy { it.playerId }
        val manualPlacements = storedMatches.flatMap { match ->
            val placements = mutableListOf<ManualPlacement>()
            if (match.manualPlayer1Locked && match.manualPlayer1Id != null) {
                placements += ManualPlacement(match.matchNumber, 0, match.manualPlayer1Id, match.manualPlayer1AssignedAt ?: 0L)
            }
            if (match.manualPlayer2Locked && match.manualPlayer2Id != null) {
                placements += ManualPlacement(match.matchNumber, 1, match.manualPlayer2Id, match.manualPlayer2AssignedAt ?: 0L)
            }
            placements
        }
        val latestManualByPlayer = manualPlacements.groupBy { it.playerId }
            .mapValues { (_, rows) -> rows.maxByOrNull { it.assignedAt }!! }

        // A manual placement in another match removes the player from their seed when the
        // seed match has not been played, while the original played matchup stays in history.
        val participantBySeed = participants.filterNot { it.isWithdrawn }.filterNot { participant ->
            val manual = latestManualByPlayer[participant.playerId] ?: return@filterNot false
            val openingIndex = (participant.seed - 1) / 2
            val opening = storedMatches.firstOrNull { it.matchKey == "W1_" + openingIndex } ?: return@filterNot false
            manual.matchNumber != opening.matchNumber &&
                opening.winnerId == null
        }.associateBy { it.seed }

        val working = storedMatches.associate { match ->
            val previous = previousByNumber[match.matchNumber] ?: match
            val openingIndex = match.matchKey.removePrefix("W1_").toIntOrNull()
            val directTwoPlayerFinal = match.matchKey == "G" && storedMatches.size == 1
            var p1: TournamentParticipantEntity? = null
            var p2: TournamentParticipantEntity? = null
            if (
                match.winnerId != null &&
                listOfNotNull(match.player1Id, match.player2Id).any { participantById[it]?.isWithdrawn == true }
            ) {
                // Preserve played history when a participant later withdraws.
                // Otherwise, completed matches must be rebuilt from their feeder results so
                // correcting an earlier result can invalidate a now-impossible matchup.
                p1 = match.player1Id?.let(participantById::get)
                p2 = match.player2Id?.let(participantById::get)
            } else if (openingIndex != null && match.branch == "WINNERS" && match.roundNumber == 1) {
                // Rebuild opening matchups from current seeds, even when a result exists.
                // Result replay below keeps the outcome only when both entrants still match.
                p1 = participantBySeed[openingIndex * 2 + 1]
                p2 = participantBySeed[openingIndex * 2 + 2]
            } else if (directTwoPlayerFinal) {
                p1 = participants.getOrNull(0)
                p2 = participants.getOrNull(1)
            }

            // An in-progress match can already contain players routed into dynamically
            // chosen slots. Preserve that exact pairing during unrelated recalculations.
            if (match.status == "IN_PROGRESS" && match.selectedWinnerId == null) {
                p1 = match.player1Id?.let(participantById::get)
                p2 = match.player2Id?.let(participantById::get)
            }
            if (match.manualPlayer1Locked) p1 = match.manualPlayer1Id?.let(participantById::get)
            if (match.manualPlayer2Locked) p2 = match.manualPlayer2Id?.let(participantById::get)
            val sameCurrentEntrants = BracketEngine.hasSameEntrants(match, p1?.playerId, p2?.playerId)
            match.matchNumber to match.copy(
                player1Id = p1?.playerId,
                player1NameSnapshot = p1?.playerNameSnapshot,
                player2Id = p2?.playerId,
                player2NameSnapshot = p2?.playerNameSnapshot,
                selectedWinnerId = null,
                winnerId = null,
                loserId = null,
                byeConfirmed = false,
                completedAt = null,
                routedWinnerMatchNumber = null,
                routedLoserMatchNumber = null,
                status = when {
                    match.status == "IN_PROGRESS" && sameCurrentEntrants -> "IN_PROGRESS"
                    p1 != null && p2 != null -> "READY"
                    p1 != null || p2 != null -> "WAITING_FOR_OPPONENT"
                    else -> "WAITING_FOR_PLAYERS"
                }
            )
        }.toMutableMap()

        // Replaying chronologically allows outcomes to use a nearby open slot without
        // contradicting a follow-up match which has already been played.
        val results = storedMatches.filter { it.selectedWinnerId != null }
            .sortedWith(compareBy<MatchEntity> { it.completedAt ?: Long.MAX_VALUE }.thenBy { it.matchNumber })

        for (saved in results) {
            var current = working.getValue(saved.matchNumber)
            val p1 = current.player1Id
            val p2 = current.player2Id
            val sameEntrants = BracketEngine.hasSameEntrants(saved, p1, p2)
            val selected = saved.selectedWinnerId
            val validPlayedResult = sameEntrants && p1 != null && p2 != null && p1 != p2 &&
                (selected == p1 || selected == p2)
            val validBye = sameEntrants && saved.byeConfirmed && selected != null &&
                (selected == p1 || selected == p2) && (p1 == null || p2 == null)

            if (!validPlayedResult && !validBye) {
                working[saved.matchNumber] = current.copy(
                    selectedWinnerId = null,
                    byeConfirmed = false,
                    winnerId = null,
                    loserId = null,
                    completedAt = null,
                    routedWinnerMatchNumber = null,
                    routedLoserMatchNumber = null,
                    status = when {
                        current.status == "IN_PROGRESS" -> "IN_PROGRESS"
                        p1 != null && p2 != null -> "READY"
                        p1 != null || p2 != null -> "WAITING_FOR_OPPONENT"
                        else -> "WAITING_FOR_PLAYERS"
                    }
                )
                continue
            }

            val winner = selected!!
            val loser = if (p1 != null && p2 != null && p1 != p2) {
                if (winner == p1) p2 else p1
            } else null
            current = current.copy(
                selectedWinnerId = winner,
                byeConfirmed = saved.byeConfirmed,
                winnerId = winner,
                loserId = loser,
                completedAt = saved.completedAt ?: System.currentTimeMillis(),
                status = "COMPLETED",
                routedWinnerMatchNumber = null,
                routedLoserMatchNumber = null
            )
            working[saved.matchNumber] = current

            val winnerTarget = if (
                current.isTournamentFinal || participantById[winner]?.isWithdrawn == true
            ) null else routeOutcome(
                working, previousByNumber, latestManualByPlayer, current, winner, isLoser = false
            )
            val loserTarget = if (
                loser == null || current.isTournamentFinal || participantById[loser]?.isWithdrawn == true
            ) null else routeOutcome(
                working, previousByNumber, latestManualByPlayer, current, loser, isLoser = true
            )
            working[saved.matchNumber] = working.getValue(saved.matchNumber).copy(
                routedWinnerMatchNumber = winnerTarget,
                routedLoserMatchNumber = loserTarget
            )
        }

        working.values.toList().forEach { match ->
            val finalStatus = when {
                match.winnerId != null -> "COMPLETED"
                match.status == "IN_PROGRESS" && match.selectedWinnerId == null -> "IN_PROGRESS"
                match.player1Id != null && match.player2Id != null -> "READY"
                match.player1Id != null || match.player2Id != null -> "WAITING_FOR_OPPONENT"
                else -> "WAITING_FOR_PLAYERS"
            }
            dao.updateMatch(match.copy(status = finalStatus))
        }
        val finalWinner = working.values.firstOrNull { it.isTournamentFinal }?.winnerId
        val tournament = dao.getTournament(tournamentId) ?: return
        if (tournament.winnerId != finalWinner) dao.updateTournament(tournament.copy(winnerId = finalWinner))
    }

    /**
     * Route a result to a manually pinned target first. Otherwise preserve a played
     * follow-up, use the standard winner edge when possible, then try the nearest free
     * slot. A winner-branch loser prefers any open winners-branch slot; a losers-branch
     * loser prefers a losers-branch slot. Elimination is the fallback only if all slots
     * are occupied, locked, completed, or currently in progress.
     */
    private fun routeOutcome(
        map: MutableMap<Int, MatchEntity>,
        previousByNumber: Map<Int, MatchEntity>,
        latestManualByPlayer: Map<Long, ManualPlacement>,
        source: MatchEntity,
        playerId: Long,
        isLoser: Boolean
    ): Int? {
        if (source.isTournamentFinal) return null

        // A manual placement is authoritative for either winner or loser. Route the
        // player to that explicitly chosen future slot instead of also auto-placing them
        // in a feeder match, which could duplicate the player and corrupt the bracket.
        val manual = latestManualByPlayer[playerId]
        if (manual != null && manual.matchNumber != source.matchNumber) {
            val target = map[manual.matchNumber]
            if (target != null && target.selectedWinnerId == null && target.winnerId == null &&
                assignPlayerToSlot(
                    map, manual.matchNumber, manual.slot, playerId,
                    participantName(map, previousByNumber, playerId), allowRecordedResult = false
                )
            ) return manual.matchNumber
        }

        // Winners and lower-branch winners advance one round at a time. In that next
        // round, first join a match where an opponent is already waiting. Only if there
        // is no such match do we use the pre-planned feeder match, then another empty seat.
        // This prevents a waiting player from being skipped just because their original
        // feeder finished later.
        if (!isLoser) {
            val targetNumber = source.nextWinnerMatchNumber
            val plannedTarget = targetNumber?.let(map::get)
            if (plannedTarget?.branch == "FINAL") {
                val targetSlot = source.winnerTargetSlot
                if (targetNumber != null && targetSlot != null &&
                    assignPlayerToSlot(
                        map, targetNumber, targetSlot, playerId,
                        participantName(map, previousByNumber, playerId), false
                    )
                ) return targetNumber
                return null
            }

            return findNearestOpenSlot(
                map = map,
                source = source,
                playerId = playerId,
                branch = source.branch,
                previousByNumber = previousByNumber,
                targetRound = source.roundNumber + 1,
                preferredMatchNumber = targetNumber
            )
        }

        // Do not reuse an old dynamic destination merely because this player appeared there
        // before. Replaying the current outcome decides the new route; a changed entrant then
        // invalidates downstream results rather than preserving a now-impossible progression.

        // Extra chances are intentional: a loser may re-enter an opening-round slot.
        // Prefer the branch where the loss happened, then fall back to the other branch.
        // A WINNERS-branch loser therefore tries WINNERS round 1 first, then LOSERS round 1.
        val preferredBranch = if (source.branch == "LOSERS") "LOSERS" else "WINNERS"
        val fallbackBranch = if (preferredBranch == "WINNERS") "LOSERS" else "WINNERS"
        val preferredSeat = findNearestOpenSlot(
            map, source, playerId, preferredBranch, previousByNumber, openingRoundOnly = true
        )
        if (preferredSeat != null) return preferredSeat
        return findNearestOpenSlot(
            map, source, playerId, fallbackBranch, previousByNumber, openingRoundOnly = true
        )
    }

    private fun findNearestOpenSlot(
        map: MutableMap<Int, MatchEntity>,
        source: MatchEntity,
        playerId: Long,
        branch: String,
        previousByNumber: Map<Int, MatchEntity>,
        openingRoundOnly: Boolean = false,
        targetRound: Int? = null,
        preferredMatchNumber: Int? = null
    ): Int? {
        val sourceOpponent = source.winnerId
        val candidates = map.values.asSequence()
            .filter { target ->
                target.matchNumber != source.matchNumber &&
                    target.branch == branch &&
                    (!openingRoundOnly || target.roundNumber == 1) &&
                    (targetRound == null || target.roundNumber == targetRound) &&
                    !target.isTournamentFinal &&
                    target.selectedWinnerId == null &&
                    target.winnerId == null &&
                    target.status != "IN_PROGRESS" &&
                    (sourceOpponent == null || (target.player1Id != sourceOpponent && target.player2Id != sourceOpponent))
            }
            .flatMap { target -> sequenceOf(0, 1).map { slot -> target to slot } }
            .filter { (target, slot) ->
                val occupied = if (slot == 0) target.player1Id else target.player2Id
                val locked = if (slot == 0) target.manualPlayer1Locked else target.manualPlayer2Locked
                val manualId = if (slot == 0) target.manualPlayer1Id else target.manualPlayer2Id
                occupied == null && (!locked || manualId == playerId) &&
                    target.player1Id != playerId && target.player2Id != playerId
            }
            .sortedWith(
                // A match with one player waiting is always more urgent than an empty
                // match. If several candidates are waiting, keep the planned feeder as
                // the first tie-breaker; otherwise preserve the planned path where possible.
                compareBy<Pair<MatchEntity, Int>> {
                    if (it.first.player1Id != null || it.first.player2Id != null) 0 else 1
                }
                    .thenBy { if (it.first.matchNumber == preferredMatchNumber) 0 else 1 }
                    .thenBy { kotlin.math.abs(it.first.roundNumber - (targetRound ?: source.roundNumber)) }
                    .thenBy { it.first.matchNumber }
                    .thenBy { it.second }
            )
            .toList()
        for ((target, slot) in candidates) {
            if (assignPlayerToSlot(
                    map, target.matchNumber, slot, playerId,
                    participantName(map, previousByNumber, playerId), allowRecordedResult = false
                )
            ) return target.matchNumber
        }
        return null
    }

    private fun participantName(
        map: MutableMap<Int, MatchEntity>,
        previousByNumber: Map<Int, MatchEntity>,
        playerId: Long
    ): String? = map.values.asSequence()
        .firstOrNull { it.player1Id == playerId || it.player2Id == playerId }
        ?.let { if (it.player1Id == playerId) it.player1NameSnapshot else it.player2NameSnapshot }
        ?: previousByNumber.values.asSequence()
            .firstOrNull { it.player1Id == playerId || it.player2Id == playerId }
            ?.let { if (it.player1Id == playerId) it.player1NameSnapshot else it.player2NameSnapshot }

    private fun assignPlayerToSlot(
        map: MutableMap<Int, MatchEntity>,
        targetNumber: Int,
        slot: Int,
        playerId: Long,
        playerName: String?,
        allowRecordedResult: Boolean
    ): Boolean {
        if (slot !in 0..1) return false
        val target = map[targetNumber] ?: return false
        if (target.status == "IN_PROGRESS") return false
        if (!allowRecordedResult && (target.selectedWinnerId != null || target.winnerId != null)) return false
        val existing = if (slot == 0) target.player1Id else target.player2Id
        val locked = if (slot == 0) target.manualPlayer1Locked else target.manualPlayer2Locked
        val manualId = if (slot == 0) target.manualPlayer1Id else target.manualPlayer2Id
        if (locked && manualId != playerId) return false
        if (existing == playerId) return true
        if (existing != null || target.player1Id == playerId || target.player2Id == playerId) return false
        map[targetNumber] = if (slot == 0) {
            target.copy(player1Id = playerId, player1NameSnapshot = playerName)
        } else {
            target.copy(player2Id = playerId, player2NameSnapshot = playerName)
        }
        return true
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
