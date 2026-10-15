package cz.magicheroes.tournamentmanager.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import cz.magicheroes.tournamentmanager.data.AppDatabase
import cz.magicheroes.tournamentmanager.data.DatabaseBackup
import cz.magicheroes.tournamentmanager.data.ClanEntity
import cz.magicheroes.tournamentmanager.data.MatchEntity
import cz.magicheroes.tournamentmanager.data.PlayerEntity
import cz.magicheroes.tournamentmanager.data.PointTransactionEntity
import cz.magicheroes.tournamentmanager.data.TournamentEntity
import cz.magicheroes.tournamentmanager.data.TournamentParticipantEntity
import cz.magicheroes.tournamentmanager.data.TournamentRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val database = AppDatabase.get(application)
    private val dao = database.tournamentDao()
    private val repository = TournamentRepository(database)

    val players: StateFlow<List<PlayerEntity>> = dao.observePlayers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val allPlayers: StateFlow<List<PlayerEntity>> = dao.observeAllPlayersIncludingArchived()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val clans: StateFlow<List<ClanEntity>> = dao.observeClans()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val tournaments: StateFlow<List<TournamentEntity>> = dao.observeTournaments()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val allMatches: StateFlow<List<MatchEntity>> = dao.observeAllMatches()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val allParticipants: StateFlow<List<TournamentParticipantEntity>> = dao.observeAllParticipants()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val points: StateFlow<List<PointTransactionEntity>> = dao.observeAllPointTransactions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val selectedTournamentId = MutableStateFlow<Long?>(null)
    @OptIn(ExperimentalCoroutinesApi::class)
    val selectedMatches: StateFlow<List<MatchEntity>> = selectedTournamentId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else dao.observeMatches(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val selectedParticipants: StateFlow<List<TournamentParticipantEntity>> = selectedTournamentId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else dao.observeParticipants(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun openTournament(id: Long) { selectedTournamentId.value = id }
    fun closeTournament() { selectedTournamentId.value = null }
    fun dismissMessage() { _message.value = null }
    fun showMessageForExport(message: String) { _message.value = message }

    fun addPlayer(name: String, clanId: Long?) = perform("Hráč byl uložen.") { repository.addPlayer(name, clanId) }
    fun updatePlayer(id: Long, name: String, clanId: Long?) = perform("Profil hráče byl upraven.") { repository.updatePlayer(id, name, clanId) }
    fun archivePlayer(id: Long) = perform("Hráč byl archivován; historie zůstala zachována.") { repository.archivePlayer(id) }
    fun addClan(name: String) = perform("Klan byl vytvořen.") { repository.addClan(name) }
    fun updateClan(id: Long, name: String) = perform("Klan byl přejmenován.") { repository.updateClan(id, name) }
    fun deleteClan(id: Long) = perform("Klan byl odstraněn. Historické výsledky zůstaly zachovány.") { repository.deleteClan(id) }

    fun createTournament(name: String, dateIso: String, note: String, playerIds: List<Long>, onCreated: (Long) -> Unit = {}) =
        perform("Turnaj byl vytvořen a bracket připraven.") {
            val id = repository.createAndStartTournament(name, dateIso, note, playerIds)
            selectedTournamentId.value = id
            withContext(Dispatchers.Main.immediate) { onCreated(id) }
        }

    fun startMatch(tournamentId: Long, matchNumber: Int) = perform("Zápas byl označen jako rozehraný.") {
        repository.setMatchInProgress(tournamentId, matchNumber)
    }

    fun recordWinner(tournamentId: Long, matchNumber: Int, winnerId: Long) = perform("Výsledek byl uložen a body přepočítány.") {
        repository.recordWinner(tournamentId, matchNumber, winnerId)
    }

    fun advanceWithoutMatch(tournamentId: Long, matchNumber: Int) = perform("Postup bez zápasu byl potvrzen; body za výhru nebyly přidány.") {
        repository.advanceWithoutMatch(tournamentId, matchNumber)
    }

    fun finishTournament(tournamentId: Long) = perform("Turnaj byl ukončen.") {
        repository.finishTournament(tournamentId)
    }

    fun exportBackup(uri: Uri) = perform("Záloha byla bezpečně uložena.") {
        DatabaseBackup.export(database, getApplication<Application>().contentResolver, uri)
    }

    fun restoreBackup(uri: Uri) = perform("Záloha byla obnovena. Data se znovu načetla z databáze.") {
        DatabaseBackup.restore(database, getApplication<Application>().contentResolver, uri)
    }

    private fun perform(successMessage: String, action: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            _busy.value = true
            try {
                action()
                _message.value = successMessage
            } catch (error: Throwable) {
                _message.value = error.message?.takeIf { it.isNotBlank() }
                    ?: "Akci se nepodařilo dokončit. Zkontroluj zadané údaje."
            } finally {
                _busy.value = false
            }
        }
    }
}
