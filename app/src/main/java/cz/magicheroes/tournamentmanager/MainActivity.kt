@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package cz.magicheroes.tournamentmanager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import cz.magicheroes.tournamentmanager.data.ClanEntity
import cz.magicheroes.tournamentmanager.data.ClanPointAdjustmentEntity
import cz.magicheroes.tournamentmanager.data.MatchEntity
import cz.magicheroes.tournamentmanager.data.PlayerEntity
import cz.magicheroes.tournamentmanager.data.PointTransactionEntity
import cz.magicheroes.tournamentmanager.data.TournamentEntity
import cz.magicheroes.tournamentmanager.data.TournamentParticipantEntity
import cz.magicheroes.tournamentmanager.export.PdfExporter
import cz.magicheroes.tournamentmanager.ui.MainViewModel
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.Locale

private val NightColors = darkColorScheme(
    primary = Color(0xFFC6A6FF),
    onPrimary = Color(0xFF241438),
    secondary = Color(0xFFE2C17C),
    background = Color(0xFF100E15),
    surface = Color(0xFF1A1722),
    surfaceVariant = Color(0xFF292432),
    onBackground = Color(0xFFF2EDF8),
    onSurface = Color(0xFFF2EDF8),
    onSurfaceVariant = Color(0xFFCEC5D8),
    error = Color(0xFFFF8A80)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = NightColors) {
                val vm: MainViewModel = viewModel(factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                        MainViewModel(application) as T
                })
                MagicHeroesApp(vm)
            }
        }
    }
}

private enum class Page(val label: String) {
    HOME("Domů"), PLAYERS("Hráči"), CLANS("Klany"), TOURNAMENTS("Turnaje"), LEADERBOARD("Žebříčky"), HISTORY("Historie")
}

private data class PlayerStats(
    val player: PlayerEntity,
    val wins: Int,
    val losses: Int,
    val games: Int,
    val winRate: Double,
    val currentWinStreak: Int,
    val currentLossStreak: Int,
    val longestWinStreak: Int,
    val points: Int,
    val tournaments: Int,
    val tournamentWins: Int
)

private data class PdfRequest(val fileName: String, val title: String, val lines: List<String>)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MagicHeroesApp(vm: MainViewModel) {
    val context = LocalContext.current
    val players by vm.players.collectAsState()
    val allPlayers by vm.allPlayers.collectAsState()
    val clans by vm.clans.collectAsState()
    val tournaments by vm.tournaments.collectAsState()
    val allMatches by vm.allMatches.collectAsState()
    val allParticipants by vm.allParticipants.collectAsState()
    val points by vm.points.collectAsState()
    val clanPointAdjustments by vm.clanPointAdjustments.collectAsState()
    val selectedMatches by vm.selectedMatches.collectAsState()
    val selectedParticipants by vm.selectedParticipants.collectAsState()
    val message by vm.message.collectAsState()
    val busy by vm.busy.collectAsState()

    var page by remember { mutableStateOf(Page.HOME) }
    var editPlayer by remember { mutableStateOf<PlayerEntity?>(null) }
    var showAddPlayer by remember { mutableStateOf(false) }
    var showAddClan by remember { mutableStateOf(false) }
    var editClan by remember { mutableStateOf<ClanEntity?>(null) }
    var adjustClanPointsFor by remember { mutableStateOf<ClanEntity?>(null) }
    var deletePlayerConfirm by remember { mutableStateOf<PlayerEntity?>(null) }
    var deleteClanConfirm by remember { mutableStateOf<ClanEntity?>(null) }
    var showCreateTournament by remember { mutableStateOf(false) }
    var selectedTournamentId by remember { mutableStateOf<Long?>(null) }
    var selectedPlayerProfile by remember { mutableStateOf<Long?>(null) }
    var selectedClanProfile by remember { mutableStateOf<Long?>(null) }
    var exportRequest by remember { mutableStateOf<PdfRequest?>(null) }
    var showRestoreConfirm by remember { mutableStateOf(false) }

    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.exportBackup(uri)
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.restoreBackup(uri)
    }

    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val request = exportRequest
        if (uri != null && request != null) {
            runCatching { PdfExporter.write(context.contentResolver, uri, request.title, request.lines) }
                .onSuccess { /* File is now saved using Android's document picker. */ }
                .onFailure { vm.showMessageForExport(it.message ?: "PDF se nepodařilo uložit.") }
        }
        exportRequest = null
    }
    fun startPdf(request: PdfRequest) {
        exportRequest = request
        pdfLauncher.launch(request.fileName)
    }

    val stats = remember(players, allPlayers, allMatches, points, allParticipants, tournaments) {
        buildStats(allPlayers.filterNot { it.isArchived }, allMatches, points, allParticipants, tournaments)
    }
    val clanMap = remember(clans) { clans.associateBy { it.id } }
    val activeTournament = tournaments.firstOrNull { it.id == selectedTournamentId }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("MAGIC HEROES", fontWeight = FontWeight.Black)
                        Text("TOURNAMENT MANAGER", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                    }
                },
                actions = {
                    if (busy) Text("Ukládám…", modifier = Modifier.padding(end = 12.dp), style = MaterialTheme.typography.labelSmall)
                }
            )
        },
        bottomBar = {
            NavigationBar {
                val destinations = listOf(Page.HOME, Page.PLAYERS, Page.TOURNAMENTS, Page.LEADERBOARD)
                destinations.forEach { destination ->
                    NavigationBarItem(
                        selected = page == destination && selectedTournamentId == null,
                        onClick = { selectedTournamentId = null; vm.closeTournament(); page = destination },
                        icon = { Text(when (destination) { Page.HOME -> "⌂"; Page.PLAYERS -> "♟"; Page.TOURNAMENTS -> "⚔"; else -> "♜" }) },
                        label = { Text(destination.label, maxLines = 1) }
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                selectedTournamentId != null && activeTournament != null -> TournamentDetailScreen(
                    tournament = activeTournament,
                    matches = selectedMatches,
                    participants = selectedParticipants,
                    availablePlayers = players,
                    playerName = { id -> allPlayers.firstOrNull { it.id == id }?.name ?: allParticipants.firstOrNull { it.playerId == id }?.playerNameSnapshot ?: "Neznámý hráč" },
                    playerElo = { id -> allPlayers.firstOrNull { it.id == id }?.eloRating ?: 1000 },
                    points = points.filter { it.tournamentId == activeTournament.id },
                    onBack = { selectedTournamentId = null; vm.closeTournament() },
                    onAddExistingPlayer = { vm.addPlayerToWaitlist(activeTournament.id, it) },
                    onAddNewPlayer = { vm.createPlayerInWaitlist(activeTournament.id, it) },
                    onPromoteNext = { vm.promoteNextWaitlistedPlayer(activeTournament.id) },
                    onStartMatch = { vm.startMatch(activeTournament.id, it) },
                    onWinner = { matchNumber, winnerId -> vm.recordWinner(activeTournament.id, matchNumber, winnerId) },
                    onAdvance = { vm.advanceWithoutMatch(activeTournament.id, it) },
                    onSwapEntrants = { first, second, confirmed -> vm.swapOpeningEntrants(activeTournament.id, first, second, confirmed) },
                    onSetManualSlot = { matchNumber, slot, playerId, locked ->
                        vm.setManualMatchSlot(activeTournament.id, matchNumber, slot, playerId, locked)
                    },
                    onWithdrawPlayer = { playerId -> vm.withdrawPlayer(activeTournament.id, playerId) },
                    onFinish = { vm.finishTournament(activeTournament.id) },
                    onExport = {
                        val tournamentLines = buildTournamentPdf(activeTournament, selectedParticipants, selectedMatches, points.filter { it.tournamentId == activeTournament.id })
                        startPdf(PdfRequest("${safeFileName(activeTournament.name)}.pdf", "Magic Heroes — ${activeTournament.name}", tournamentLines))
                    }
                )
                else -> when (page) {
                    Page.HOME -> HomeScreen(
                        playerCount = players.size,
                        clanCount = clans.size,
                        activeCount = tournaments.count { it.status == TournamentEntity.STATUS_ACTIVE },
                        tournamentCount = tournaments.size,
                        onPlayers = { page = Page.PLAYERS },
                        onTournaments = { page = Page.TOURNAMENTS },
                        onStats = { page = Page.LEADERBOARD },
                        onHistory = { page = Page.HISTORY },
                        onBackup = { backupLauncher.launch("magic-heroes-zaloha.json") },
                        onRestore = { showRestoreConfirm = true }
                    )
                    Page.PLAYERS -> PlayersScreen(
                        players = players,
                        clans = clans,
                        stats = stats,
                        onAdd = { editPlayer = null; showAddPlayer = true },
                        onClans = { page = Page.CLANS },
                        onEdit = { editPlayer = it },
                        onDelete = { deletePlayerConfirm = it },
                        onProfile = { selectedPlayerProfile = it.id }
                    )
                    Page.CLANS -> ClansScreen(
                        clans = clans,
                        players = players,
                        stats = stats,
                        points = points,
                        adjustments = clanPointAdjustments,
                        onAdd = { showAddClan = true },
                        onOpen = { selectedClanProfile = it.id },
                        onEdit = { editClan = it },
                        onAdjustPoints = { adjustClanPointsFor = it },
                        onDelete = { deleteClanConfirm = it }
                    )
                    Page.TOURNAMENTS -> TournamentsScreen(
                        tournaments = tournaments,
                        participants = allParticipants,
                        matches = allMatches,
                        allPlayers = allPlayers,
                        onCreate = { showCreateTournament = true },
                        onOpen = { selectedTournamentId = it.id; vm.openTournament(it.id) },
                        onExportLeaderboard = { startPdf(PdfRequest("magic-heroes-zebricek.pdf", "Magic Heroes — žebříček hráčů", buildLeaderboardPdf(stats, clanMap))) }
                    )
                    Page.LEADERBOARD -> LeaderboardScreen(
                        stats = stats,
                        clans = clans,
                        points = points,
                        clanAdjustments = clanPointAdjustments,
                        matches = allMatches,
                        onProfile = { selectedPlayerProfile = it.id },
                        onExportPlayers = { startPdf(PdfRequest("magic-heroes-zebricek-hracu.pdf", "Magic Heroes — žebříček hráčů", buildLeaderboardPdf(stats, clanMap))) },
                        onExportClans = { startPdf(PdfRequest("magic-heroes-zebricek-klanu.pdf", "Magic Heroes — žebříček klanů", buildClanLeaderboardPdf(clans, players, points, clanPointAdjustments, stats))) }
                    )
                    Page.HISTORY -> HistoryScreen(
                        tournaments = tournaments.filter { it.status == TournamentEntity.STATUS_FINISHED },
                        participants = allParticipants,
                        matches = allMatches,
                        players = allPlayers,
                        onOpen = { selectedTournamentId = it.id; vm.openTournament(it.id) }
                    )
                }
            }
        }
    }

    if (showRestoreConfirm) ConfirmDialog(
        title = "Obnovit zálohu?",
        text = "Obnovení nahradí všechna aktuální data v telefonu daty ze zálohovacího souboru. Před pokračováním si ověř, že máš správný soubor.",
        confirm = "Vybrat soubor",
        onDismiss = { showRestoreConfirm = false },
        onConfirm = {
            showRestoreConfirm = false
            restoreLauncher.launch(arrayOf("application/json", "text/json", "text/plain", "*/*"))
        }
    )

    if (showAddPlayer) PlayerEditorDialog(
        title = "Přidat hráče", initial = null, clans = clans,
        onDismiss = { showAddPlayer = false },
        onSave = { name, clanId -> vm.addPlayer(name, clanId); showAddPlayer = false }
    )
    editPlayer?.let { player -> PlayerEditorDialog(
        title = "Upravit hráče", initial = player, clans = clans,
        onDismiss = { editPlayer = null },
        onSave = { name, clanId -> vm.updatePlayer(player.id, name, clanId); editPlayer = null }
    ) }
    if (showAddClan) NameDialog("Nový klan", "Název klanu", onDismiss = { showAddClan = false }, onSave = { vm.addClan(it); showAddClan = false })
    editClan?.let { clan -> NameDialog("Přejmenovat klan", "Název klanu", clan.name, { editClan = null }, { vm.updateClan(clan.id, it); editClan = null }) }
    deletePlayerConfirm?.let { player -> ConfirmDialog(
        title = "Archivovat hráče?",
        text = "Profil „${player.name}“ se skryje z aktivní knihovny. Historické zápasy a body zůstanou zachované.",
        confirm = "Archivovat",
        onDismiss = { deletePlayerConfirm = null },
        onConfirm = { vm.archivePlayer(player.id); deletePlayerConfirm = null }
    ) }
    deleteClanConfirm?.let { clan -> ConfirmDialog(
        title = "Odstranit klan?",
        text = "Aktuální členové budou převedeni bez klanu. Historické body zůstanou přiřazené původnímu klanu.",
        confirm = "Odstranit",
        onDismiss = { deleteClanConfirm = null },
        onConfirm = { vm.deleteClan(clan.id); deleteClanConfirm = null }
    ) }
    if (showCreateTournament) CreateTournamentDialog(
        players = players,
        clans = clans,
        onDismiss = { showCreateTournament = false },
        onCreate = { name, date, note, selected ->
            val parsedDate = try { LocalDate.parse(date) } catch (_: DateTimeParseException) { null }
            if (parsedDate == null) vm.showMessageForExport("Datum zadej ve formátu RRRR-MM-DD, například 2026-10-15.")
            else {
                vm.createTournament(name, date, note, selected) { id -> selectedTournamentId = id }
                showCreateTournament = false
            }
        }
    )

    selectedPlayerProfile?.let { id ->
        val player = allPlayers.firstOrNull { it.id == id }
        val playerStats = stats.firstOrNull { it.player.id == id }
        if (player != null) PlayerProfileDialog(
            player = player,
            clanName = player.clanId?.let { clanMap[it]?.name } ?: "Bez klanu",
            stats = playerStats,
            allMatches = allMatches,
            participants = allParticipants,
            tournaments = tournaments,
            nameFor = { pid -> allPlayers.firstOrNull { it.id == pid }?.name ?: allParticipants.firstOrNull { it.playerId == pid }?.playerNameSnapshot ?: "Neznámý hráč" },
            onDismiss = { selectedPlayerProfile = null },
            onExport = {
                val lines = buildPlayerPdf(player, playerStats, allMatches, allParticipants, tournaments, points, allPlayers)
                startPdf(PdfRequest("${safeFileName(player.name)}-profil.pdf", "Magic Heroes — profil ${player.name}", lines))
            }
        )
    }
    selectedClanProfile?.let { id ->
        clans.firstOrNull { it.id == id }?.let { clan -> ClanProfileDialog(
            clan = clan, players = players.filter { it.clanId == id }, stats = stats,
            points = points, adjustments = clanPointAdjustments, onDismiss = { selectedClanProfile = null }
        ) }
    }

    adjustClanPointsFor?.let { clan ->
        ClanPointsDialog(
            clan = clan,
            onDismiss = { adjustClanPointsFor = null },
            onSubmit = { delta, reason ->
                vm.adjustClanPoints(clan.id, delta, reason)
                adjustClanPointsFor = null
            }
        )
    }

    message?.let { text ->
        AlertDialog(
            onDismissRequest = { vm.dismissMessage() },
            title = { Text(if (text.contains("nepodařilo", true) || text.contains("nelze", true) || text.contains("musí", true) || text.contains("už", true) && text.contains("neexistuje", true)) "Upozornění" else "Magic Heroes" ) },
            text = { Text(text) },
            confirmButton = { Button(onClick = { vm.dismissMessage() }) { Text("Rozumím") } }
        )
    }
}

@Composable
private fun HomeScreen(
    playerCount: Int, clanCount: Int, activeCount: Int, tournamentCount: Int,
    onPlayers: () -> Unit, onTournaments: () -> Unit, onStats: () -> Unit, onHistory: () -> Unit,
    onBackup: () -> Unit, onRestore: () -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF252034)), border = BorderStroke(1.dp, Color(0xFF69528D))) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("SPRÁVA TURNAJŮ", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
                Text("Vítej v Noxveilu", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("Všechna data se ukládají do telefonu. Aplikace funguje offline.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("Hráči", playerCount.toString(), Modifier.weight(1f))
            StatTile("Klany", clanCount.toString(), Modifier.weight(1f))
            StatTile("Aktivní", activeCount.toString(), Modifier.weight(1f))
        }
        HomeAction("♟  Hráči a klany", "Spravuj hráče, přezdívky a členství", onPlayers)
        HomeAction("⚔  Turnaje", "Vytvoř turnaj a zapisuj výsledky", onTournaments)
        HomeAction("♜  Statistiky a žebříčky", "Body, výhry, série a pořadí", onStats)
        HomeAction("▤  Historie turnajů", "$tournamentCount uložených turnajů", onHistory)
        Divider(color = Color(0xFF393142))
        Text("ZÁLOHA DAT", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
        Text("Záloha obsahuje hráče, klany, turnaje, pavouk i ruční úpravy bodů.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onBackup, modifier = Modifier.weight(1f)) { Text("Exportovat zálohu") }
            OutlinedButton(onClick = onRestore, modifier = Modifier.weight(1f)) { Text("Obnovit zálohu") }
        }
        Text("Verze 0.1 · lokální databáze Room", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp)) {
            Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun HomeAction(title: String, subtitle: String, onClick: () -> Unit) {
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, Color(0xFF393142))) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("›", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.secondary)
        }
    }
}

@Composable
private fun PlayersScreen(
    players: List<PlayerEntity>, clans: List<ClanEntity>, stats: List<PlayerStats>,
    onAdd: () -> Unit, onClans: () -> Unit, onEdit: (PlayerEntity) -> Unit,
    onDelete: (PlayerEntity) -> Unit, onProfile: (PlayerEntity) -> Unit
) {
    var search by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Hráči", style = MaterialTheme.typography.headlineMedium); Text("${players.size} aktivních profilů", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            OutlinedButton(onClick = onClans) { Text("Klany") }
            Button(onClick = onAdd) { Text("+ Přidat") }
        }
        OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), label = { Text("Hledat hráče") }, singleLine = true)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
            items(players.filter { it.name.contains(search, true) }, key = { it.id }) { player ->
                val playerStats = stats.firstOrNull { it.player.id == player.id }
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, Color(0xFF393142))) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(player.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(clans.firstOrNull { it.id == player.clanId }?.name ?: "Bez klanu", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("ELO ${player.eloRating}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                                Text("${playerStats?.points ?: 0} b.", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("${playerStats?.wins ?: 0} V · ${playerStats?.losses ?: 0} P · úspěšnost ${formatPercent(playerStats?.winRate ?: 0.0)} · série W${playerStats?.currentWinStreak ?: 0}/L${playerStats?.currentLossStreak ?: 0}", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(onClick = { onProfile(player) }) { Text("Profil") }
                            TextButton(onClick = { onEdit(player) }) { Text("Upravit") }
                            TextButton(onClick = { onDelete(player) }) { Text("Archivovat", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ClansScreen(
    clans: List<ClanEntity>, players: List<PlayerEntity>, stats: List<PlayerStats>, points: List<PointTransactionEntity>,
    adjustments: List<ClanPointAdjustmentEntity>,
    onAdd: () -> Unit, onOpen: (ClanEntity) -> Unit, onEdit: (ClanEntity) -> Unit,
    onAdjustPoints: (ClanEntity) -> Unit, onDelete: (ClanEntity) -> Unit
) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Klany", style = MaterialTheme.typography.headlineMedium); Text("${clans.size} registrovaných klanů", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Button(onClick = onAdd) { Text("+ Klan") }
        }
        if (clans.isEmpty()) EmptyState("Zatím tu nejsou žádné klany.", "Přidej první klan tlačítkem nahoře.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
            items(clans, key = { it.id }) { clan ->
                val members = players.filter { it.clanId == clan.id }
                val clanPoints = points.filter { it.clanIdSnapshot == clan.id }.sumOf { it.points } +
                    adjustments.filter { it.clanIdSnapshot == clan.id }.sumOf { it.points }
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, Color(0xFF393142))) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(clan.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text("${members.size} členů · $clanPoints bodů", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { onOpen(clan) }) { Text("Detail") }
                            TextButton(onClick = { onEdit(clan) }) { Text("Přejmenovat") }
                            TextButton(onClick = { onAdjustPoints(clan) }) { Text("Body ±") }
                        }
                        Row {
                            TextButton(onClick = { onDelete(clan) }) { Text("Odstranit", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TournamentsScreen(
    tournaments: List<TournamentEntity>, participants: List<TournamentParticipantEntity>, matches: List<MatchEntity>, allPlayers: List<PlayerEntity>,
    onCreate: () -> Unit, onOpen: (TournamentEntity) -> Unit, onExportLeaderboard: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Turnaje", style = MaterialTheme.typography.headlineMedium); Text("Vytvoření, průběh a výsledky", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Button(onClick = onCreate) { Text("+ Nový") }
        }
        if (tournaments.isEmpty()) EmptyState("Zatím žádný turnaj", "Vytvoř turnaj a vyber hráče z knihovny.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(tournaments, key = { it.id }) { tournament ->
                val count = participants.count { it.tournamentId == tournament.id && !it.isQueued && !it.isWithdrawn }
                val all = matches.filter { it.tournamentId == tournament.id }
                val played = all.count { it.winnerId != null }
                val winnerName = tournament.winnerId?.let { id -> allPlayers.firstOrNull { it.id == id }?.name ?: participants.firstOrNull { it.tournamentId == tournament.id && it.playerId == id }?.playerNameSnapshot }
                Card(onClick = { onOpen(tournament) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, Color(0xFF393142))) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(tournament.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            StatusPill(tournament.status)
                        }
                        Text(tournament.dateIso, color = MaterialTheme.colorScheme.secondary)
                        Text("$count hráčů · $played/${all.size} dokončených zápasů", style = MaterialTheme.typography.bodySmall)
                        if (winnerName != null) Text("Vítěz: $winnerName", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        TextButton(onClick = onExportLeaderboard) { Text("Exportovat žebříček hráčů do PDF") }
    }
}

@Composable
private fun HistoryScreen(
    tournaments: List<TournamentEntity>, participants: List<TournamentParticipantEntity>, matches: List<MatchEntity>, players: List<PlayerEntity>, onOpen: (TournamentEntity) -> Unit
) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Historie turnajů", style = MaterialTheme.typography.headlineMedium)
        Text("Ukončené turnaje zůstávají uložené v telefonu.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (tournaments.isEmpty()) EmptyState("Historie je zatím prázdná", "Po ukončení turnaje se objeví zde.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(tournaments, key = { it.id }) { tournament ->
                val count = participants.count { it.tournamentId == tournament.id }
                val played = matches.count { it.tournamentId == tournament.id && it.winnerId != null }
                val winner = tournament.winnerId?.let { id -> players.firstOrNull { it.id == id }?.name ?: participants.firstOrNull { it.tournamentId == tournament.id && it.playerId == id }?.playerNameSnapshot }
                HomeAction("${tournament.name} · ${tournament.dateIso}", "$count účastníků · $played dokončených zápasů${winner?.let { " · vítěz: $it" } ?: ""}", { onOpen(tournament) })
            }
        }
    }
}

@Composable
private fun LeaderboardScreen(
    stats: List<PlayerStats>, clans: List<ClanEntity>, points: List<PointTransactionEntity>,
    clanAdjustments: List<ClanPointAdjustmentEntity>, matches: List<MatchEntity>,
    onProfile: (PlayerEntity) -> Unit, onExportPlayers: () -> Unit, onExportClans: () -> Unit
) {
    var mode by remember { mutableStateOf(false) }
    var eloRanking by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Žebříčky", style = MaterialTheme.typography.headlineMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!mode) Button(onClick = { mode = false }) { Text("Hráči") } else OutlinedButton(onClick = { mode = false }) { Text("Hráči") }
            if (mode) Button(onClick = { mode = true }) { Text("Klany") } else OutlinedButton(onClick = { mode = true }) { Text("Klany") }
        }
        if (!mode) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!eloRanking) Button(onClick = { eloRanking = false }) { Text("Žebříček bodů") }
                else OutlinedButton(onClick = { eloRanking = false }) { Text("Žebříček bodů") }
                if (eloRanking) Button(onClick = { eloRanking = true }) { Text("Žebříček ELO") }
                else OutlinedButton(onClick = { eloRanking = true }) { Text("Žebříček ELO") }
            }
            val rankedStats = if (eloRanking) {
                stats.sortedWith(compareByDescending<PlayerStats> { it.player.eloRating }.thenByDescending { it.wins }.thenBy { it.player.name.lowercase(Locale.ROOT) })
            } else {
                stats.sortedWith(compareByDescending<PlayerStats> { it.points }.thenByDescending { it.winRate }.thenByDescending { it.wins })
            }
            Text(
                if (eloRanking) "Řazení: ELO → výhry" else "Řazení: body → úspěšnost → výhry",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                items(rankedStats, key = { it.player.id }) { stat ->
                    val rank = rankedStats.indexOfFirst { it.player.id == stat.player.id } + 1
                    Card(onClick = { onProfile(stat.player) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("#$rank", Modifier.width(44.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
                            Column(Modifier.weight(1f)) {
                                Text(stat.player.name, fontWeight = FontWeight.SemiBold)
                                Text("${stat.player.clanId?.let { id -> clans.firstOrNull { it.id == id }?.name } ?: "Bez klanu"} · ${stat.wins} V · ${stat.losses} P · ${formatPercent(stat.winRate)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("ELO ${stat.player.eloRating} · W${stat.currentWinStreak} / L${stat.currentLossStreak} · max W${stat.longestWinStreak}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                            }
                            Text(if (eloRanking) "${stat.player.eloRating} ELO" else "${stat.points} b.", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                        }
                    }
                }
            }
            OutlinedButton(onClick = onExportPlayers, modifier = Modifier.fillMaxWidth()) { Text("Export žebříčku hráčů do PDF") }
        } else {
            val clanRanking = clans.map { clan ->
                val score = points.filter { it.clanIdSnapshot == clan.id }.sumOf { it.points } +
                    clanAdjustments.filter { it.clanIdSnapshot == clan.id }.sumOf { it.points }
                val members = stats.filter { it.player.clanId == clan.id }
                Triple(clan, score, members)
            }.sortedByDescending { it.second }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                items(clanRanking, key = { it.first.id }) { row ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(row.first.name, fontWeight = FontWeight.SemiBold)
                                Text("${row.third.size} aktivních členů", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text("${row.second} b.", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                        }
                    }
                }
            }
            OutlinedButton(onClick = onExportClans, modifier = Modifier.fillMaxWidth()) { Text("Export žebříčku klanů do PDF") }
        }
    }
}

@Composable
private fun TournamentDetailScreen(
    tournament: TournamentEntity, matches: List<MatchEntity>, participants: List<TournamentParticipantEntity>, availablePlayers: List<PlayerEntity>,
    playerName: (Long) -> String, playerElo: (Long) -> Int, points: List<PointTransactionEntity>,
    onBack: () -> Unit, onAddExistingPlayer: (Long) -> Unit, onAddNewPlayer: (String) -> Unit, onPromoteNext: () -> Unit,
    onStartMatch: (Int) -> Unit, onWinner: (Int, Long) -> Unit, onAdvance: (Int) -> Unit,
    onSwapEntrants: (Long, Long, Boolean) -> Unit,
    onSetManualSlot: (Int, Int, Long?, Boolean) -> Unit,
    onWithdrawPlayer: (Long) -> Unit, onFinish: () -> Unit, onExport: () -> Unit
) {
    var matchToEdit by remember { mutableStateOf<MatchEntity?>(null) }
    var matchToSwap by remember { mutableStateOf<MatchEntity?>(null) }
    var matchToManualSlots by remember { mutableStateOf<MatchEntity?>(null) }
    var participantToWithdraw by remember { mutableStateOf<Long?>(null) }
    var showFinishConfirm by remember { mutableStateOf(false) }
    var showWaitlistDialog by remember { mutableStateOf(false) }
    val completed = matches.count { it.winnerId != null }
    val finalMatch = matches.firstOrNull { it.isTournamentFinal }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Zpět") }
            Spacer(Modifier.weight(1f))
            StatusPill(tournament.status)
        }
        Text(tournament.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        val queued = participants.filter { it.isQueued }.sortedBy { it.seed }
        val activeParticipants = participants.filterNot { it.isQueued || it.isWithdrawn }.sortedBy { it.seed }
        val withdrawnParticipants = participants.filter { it.isWithdrawn }.sortedBy { it.seed }
        Text("${tournament.dateIso} · ${activeParticipants.size} hráčů" + if (queued.isNotEmpty()) " · ${queued.size} čeká" else "", color = MaterialTheme.colorScheme.secondary)
        if (tournament.note.isNotBlank()) Text(tournament.note, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (tournament.status == TournamentEntity.STATUS_ACTIVE) {
            OutlinedButton(onClick = { showWaitlistDialog = true }, modifier = Modifier.fillMaxWidth()) {
                Text("+ Přidat hráče do čekací fronty")
            }
        }
        if (queued.isNotEmpty()) {
            val openingMatches = matches.filter { it.branch == "WINNERS" && it.roundNumber == 1 }
            val occupiedSeeds = activeParticipants.map { it.seed }.toSet()
            val promotionSeed = (1..(openingMatches.size * 2)).firstOrNull { seed ->
                if (seed in occupiedSeeds) return@firstOrNull false
                val target = openingMatches.firstOrNull { it.matchKey == "W1_${(seed - 1) / 2}" } ?: return@firstOrNull false
                val slotPlayer = if ((seed - 1) % 2 == 0) target.player1Id else target.player2Id
                slotPlayer == null && target.winnerId == null && target.selectedWinnerId == null &&
                    !target.byeConfirmed && target.status != "IN_PROGRESS"
            }
            val canPromote = tournament.status == TournamentEntity.STATUS_ACTIVE && promotionSeed != null
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("ČEKACÍ FRONTA", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
                    queued.forEachIndexed { index, participant ->
                        Text("${index + 1}. ${participant.playerNameSnapshot}", style = MaterialTheme.typography.bodyMedium)
                    }
                    Button(onClick = onPromoteNext, enabled = canPromote, modifier = Modifier.fillMaxWidth()) {
                        Text("Zařadit dalšího do volného pole")
                    }
                    Text(
                        if (canPromote) "Další čekající bude zařazen bez změny dosavadních výsledků."
                        else "Čekající hráče lze zařadit, až bude v bracketu bezpečně volné místo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("Zápasy", "$completed/${matches.size}", Modifier.weight(1f))
            StatTile("Čeká", matches.count { it.status.startsWith("WAITING") }.toString(), Modifier.weight(1f))
            StatTile("Body", points.sumOf { it.points }.toString(), Modifier.weight(1f))
        }
        Text("ÚČASTNÍCI", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            activeParticipants.forEach { participant ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${participant.seed}. ${participant.playerNameSnapshot}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    if (tournament.status == TournamentEntity.STATUS_ACTIVE) {
                        TextButton(onClick = { participantToWithdraw = participant.playerId }) {
                            Text("Odstoupit", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            if (withdrawnParticipants.isNotEmpty()) {
                Text(
                    "Odstoupili: " + withdrawnParticipants.joinToString(" · ") { it.playerNameSnapshot },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        Divider(color = Color(0xFF393142))
        Text("BRACKET", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        val bracketCapacity = matches.count { it.branch == "WINNERS" && it.roundNumber == 1 } * 2
        val bracketSize = when (bracketCapacity) { 8 -> "MALÝ"; 16 -> "STŘEDNÍ"; else -> "VELKÝ" }
        Text("PAVOUK $bracketSize · KAPACITA $bracketCapacity HRÁČŮ", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
        Text("Posouvej do stran. Kliknutím na zápas zadáš nebo opravíš výsledek.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val columns = matches.groupBy { "${it.branch}|${it.roundNumber}" }.values.sortedWith(
            compareBy<List<MatchEntity>> { it.firstOrNull()?.let { m -> branchOrder(m.branch) } ?: 9 }
                .thenBy { it.firstOrNull()?.roundNumber ?: 0 }
        )
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            columns.forEach { group ->
                val first = group.first()
                Column(Modifier.width(228.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(columnTitle(first.branch, first.roundNumber), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
                    group.sortedBy { it.matchNumber }.forEach { match ->
                        val nameFromThisMatch: (Long) -> String = { id ->
                            when (id) {
                                match.player1Id -> match.player1NameSnapshot ?: playerName(id)
                                match.player2Id -> match.player2NameSnapshot ?: playerName(id)
                                else -> playerName(id)
                            }
                        }
                        MatchCard(match, nameFromThisMatch, playerElo) { matchToEdit = match }
                    }
                }
            }
        }
        Divider(color = Color(0xFF393142))
        Text("BODY Z TURNAJE", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (points.isEmpty()) Text("Zatím nebyly přiděleny žádné body.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        points.sortedByDescending { it.createdAt }.forEach { entry ->
            Text("${playerName(entry.playerId)}  ${if (entry.points > 0) "+" else ""}${entry.points} b. — ${entry.reason}", style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onExport, modifier = Modifier.weight(1f)) { Text("Export PDF") }
            Button(onClick = { showFinishConfirm = true }, enabled = tournament.status != TournamentEntity.STATUS_FINISHED, modifier = Modifier.weight(1f)) { Text("Ukončit turnaj") }
        }
        if (tournament.status == TournamentEntity.STATUS_FINISHED) {
            val winner = tournament.winnerId?.let(playerName)
            Text(winner?.let { "Vítěz turnaje: $it" } ?: "Turnaj byl ukončen bez určeného vítěze.", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
        } else if (finalMatch?.winnerId != null) {
            Text("Finále je rozhodnuté. Po potvrzení můžeš turnaj ukončit.", color = MaterialTheme.colorScheme.secondary)
        }
    }

    if (showWaitlistDialog) WaitlistDialog(
        availablePlayers = availablePlayers.filter { player -> participants.none { it.playerId == player.id } },
        onDismiss = { showWaitlistDialog = false },
        onAddExisting = { id -> onAddExistingPlayer(id); showWaitlistDialog = false },
        onAddNew = { name -> onAddNewPlayer(name); showWaitlistDialog = false }
    )

    matchToEdit?.let { match ->
        val nameFromThisMatch: (Long) -> String = { id ->
            when (id) {
                match.player1Id -> match.player1NameSnapshot ?: playerName(id)
                match.player2Id -> match.player2NameSnapshot ?: playerName(id)
                else -> playerName(id)
            }
        }
        MatchResultDialog(
        match = match,
        playerName = nameFromThisMatch,
        isFinished = tournament.status == TournamentEntity.STATUS_FINISHED,
        onDismiss = { matchToEdit = null },
        onStart = { onStartMatch(match.matchNumber); matchToEdit = null },
        onConfirmWinner = { winnerId -> onWinner(match.matchNumber, winnerId); matchToEdit = null },
        onAdvance = { onAdvance(match.matchNumber); matchToEdit = null },
        canEditPairing = match.branch == "WINNERS" && match.roundNumber == 1 && match.player1Id != null && match.player2Id != null,
        onEditPairing = { matchToEdit = null; matchToSwap = match },
        canEditSlots = tournament.status == TournamentEntity.STATUS_ACTIVE && match.winnerId == null && match.status != "IN_PROGRESS",
        onEditSlots = { matchToEdit = null; matchToManualSlots = match }
    )
    }
    matchToManualSlots?.let { match ->
        ManualSlotDialog(
            match = match,
            participants = participants.filterNot { it.isWithdrawn }.sortedBy { it.seed },
            onDismiss = { matchToManualSlots = null },
            onSave = { slot, playerId, locked ->
                onSetManualSlot(match.matchNumber, slot, playerId, locked)
                matchToManualSlots = null
            }
        )
    }
    matchToSwap?.let { match ->
        ManualSwapDialog(
            match = match,
            participants = participants.filterNot { it.isQueued || it.isWithdrawn }.sortedBy { it.seed },
            playerName = playerName,
            onDismiss = { matchToSwap = null },
            onConfirmSwap = { firstId, secondId ->
                onSwapEntrants(firstId, secondId, true)
                matchToSwap = null
            }
        )
    }
    participantToWithdraw?.let { playerId ->
        val participant = participants.firstOrNull { it.playerId == playerId }
        ConfirmDialog(
            title = "Odstoupit z turnaje?",
            text = "Hráč ${participant?.playerNameSnapshot ?: playerName(playerId)} přestane postupovat do dalších zápasů. Již odehrané výsledky zůstanou v historii; neodehraná místa zůstanou volná.",
            confirm = "Potvrdit odstoupení",
            onDismiss = { participantToWithdraw = null },
            onConfirm = { onWithdrawPlayer(playerId); participantToWithdraw = null }
        )
    }
    if (showFinishConfirm) {
        val incomplete = matches.count { it.winnerId == null }
        val finalDone = finalMatch?.winnerId != null
        ConfirmDialog(
            title = "Ukončit turnaj?",
            text = buildString {
                append("Turnaj bude uzavřen a zachován v historii. ")
                if (incomplete > 0) append("Zbývá $incomplete nedohraných zápasů. ")
                if (!finalDone) append("Není určen vítěz finále. ")
                append("Opravdu chceš pokračovat?")
            },
            confirm = "Ukončit i tak",
            onDismiss = { showFinishConfirm = false },
            onConfirm = { onFinish(); showFinishConfirm = false }
        )
    }
}

@Composable
private fun MatchCard(match: MatchEntity, playerName: (Long) -> String, playerElo: (Long) -> Int, onClick: () -> Unit) {
    val statusText = when (match.status) {
        "COMPLETED" -> "DOKONČENO"
        "READY" -> "PŘIPRAVEN"
        "IN_PROGRESS" -> "ROZEHRÁNO"
        "WAITING_FOR_OPPONENT" -> "ČEKÁ NA SOUPEŘE"
        else -> "ČEKÁ NA HRÁČE"
    }
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = if (match.status == "IN_PROGRESS") Color(0xFF302440) else MaterialTheme.colorScheme.surfaceVariant), border = BorderStroke(1.dp, if (match.status == "COMPLETED") Color(0xFF5C8A6A) else Color(0xFF44394F))) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Zápas ${match.matchNumber}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text(statusText, style = MaterialTheme.typography.labelSmall, color = if (match.status == "COMPLETED") Color(0xFF9CD5A8) else MaterialTheme.colorScheme.secondary)
            }
            Text(match.player1Id?.let { "${playerName(it)} · ELO ${playerElo(it)}" } ?: "Volná pozice", maxLines = 1, overflow = TextOverflow.Ellipsis)
            Divider(color = Color(0xFF494151))
            Text(match.player2Id?.let { "${playerName(it)} · ELO ${playerElo(it)}" } ?: "Čeká na soupeře", maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (match.winnerId != null) Text("Vítěz: ${playerName(match.winnerId)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            val winnerDestination = if (match.winnerId != null) match.routedWinnerMatchNumber else match.nextWinnerMatchNumber
            val loserDestination = if (match.winnerId != null) match.routedLoserMatchNumber else match.nextLoserMatchNumber
            if (winnerDestination != null) {
                Text("↗ Vítěz → zápas " + winnerDestination, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (loserDestination != null) {
                Text("↘ Poražený → zápas " + loserDestination, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (match.winnerId != null && match.branch != "FINAL" && match.loserId != null) {
                Text("Poražený: není volné místo", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun ManualSlotDialog(
    match: MatchEntity,
    participants: List<TournamentParticipantEntity>,
    onDismiss: () -> Unit,
    onSave: (Int, Long?, Boolean) -> Unit
) {
    var selectedSlot by remember(match.matchNumber) { mutableStateOf(0) }
    var slot1Player by remember(match.matchNumber) { mutableStateOf(match.manualPlayer1Id ?: match.player1Id) }
    var slot2Player by remember(match.matchNumber) { mutableStateOf(match.manualPlayer2Id ?: match.player2Id) }
    var slot1Locked by remember(match.matchNumber) { mutableStateOf(match.manualPlayer1Locked) }
    var slot2Locked by remember(match.matchNumber) { mutableStateOf(match.manualPlayer2Locked) }
    var playerMenu by remember { mutableStateOf(false) }

    val selectedPlayer = if (selectedSlot == 0) slot1Player else slot2Player
    val selectedLocked = if (selectedSlot == 0) slot1Locked else slot2Locked
    val currentPlayer = if (selectedSlot == 0) match.player1Id else match.player2Id
    val nameFor: (Long?) -> String = { id ->
        id?.let { value -> participants.firstOrNull { it.playerId == value }?.playerNameSnapshot }
            ?: "Volná pozice"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ruční obsazení · zápas " + match.matchNumber) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(columnTitle(match.branch, match.roundNumber), color = MaterialTheme.colorScheme.secondary)
                Text("Vyber pozici a hráče. Ruční umístění má přednost před automatickým přesunem.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (selectedSlot == 0) Button(onClick = { selectedSlot = 0 }) { Text("Pozice 1") }
                    else OutlinedButton(onClick = { selectedSlot = 0 }) { Text("Pozice 1") }
                    if (selectedSlot == 1) Button(onClick = { selectedSlot = 1 }) { Text("Pozice 2") }
                    else OutlinedButton(onClick = { selectedSlot = 1 }) { Text("Pozice 2") }
                }
                Text("Aktuální obsazení: " + nameFor(currentPlayer))
                OutlinedButton(onClick = { playerMenu = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(nameFor(selectedPlayer))
                }
                DropdownMenu(expanded = playerMenu, onDismissRequest = { playerMenu = false }) {
                    participants.forEach { participant ->
                        DropdownMenuItem(
                            text = {
                                Text(participant.playerNameSnapshot + if (participant.isQueued) " · čeká" else "")
                            },
                            onClick = {
                                if (selectedSlot == 0) {
                                    slot1Player = participant.playerId
                                    slot1Locked = true
                                } else {
                                    slot2Player = participant.playerId
                                    slot2Locked = true
                                }
                                playerMenu = false
                            }
                        )
                    }
                }
                OutlinedButton(
                    onClick = {
                        if (selectedSlot == 0) {
                            slot1Player = null
                            slot1Locked = true
                        } else {
                            slot2Player = null
                            slot2Locked = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Ponechat pozici prázdnou") }
                TextButton(onClick = {
                    if (selectedSlot == 0) {
                        slot1Player = match.player1Id
                        slot1Locked = false
                    } else {
                        slot2Player = match.player2Id
                        slot2Locked = false
                    }
                }) { Text("Obnovit automatické umístění") }
                if (selectedLocked) {
                    Text("Pozice je ručně uzamčená.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(selectedSlot, selectedPlayer, selectedLocked)
            }) { Text("Uložit pozici") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Zrušit") } }
    )
}

@Composable
private fun MatchResultDialog(
    match: MatchEntity, playerName: (Long) -> String, isFinished: Boolean, onDismiss: () -> Unit, onStart: () -> Unit,
    onConfirmWinner: (Long) -> Unit, onAdvance: () -> Unit,
    canEditPairing: Boolean, onEditPairing: () -> Unit,
    canEditSlots: Boolean, onEditSlots: () -> Unit
) {
    var chosenWinner by remember(match.id, match.winnerId) { mutableStateOf(match.winnerId) }
    var confirmAdvance by remember { mutableStateOf(false) }
    val one = listOfNotNull(match.player1Id, match.player2Id).singleOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Zápas ${match.matchNumber}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(columnTitle(match.branch, match.roundNumber), color = MaterialTheme.colorScheme.secondary)
                if (isFinished) Text("Historický turnaj: oprava změní uložené statistiky, body a ELO.", color = MaterialTheme.colorScheme.error)
                if (match.player1Id == null && match.player2Id == null) {
                    Text("Tato pozice zatím nemá přiřazené hráče.")
                } else if (one != null) {
                    Text("${playerName(one)} čeká na soupeře. Bez zápasu se nikdo neposune automaticky.")
                    if (confirmAdvance) Text("Potvrzením hráč postoupí do navazujícího zápasu bez odehrané hry a nezíská 2 body za výhru.", color = MaterialTheme.colorScheme.error)
                } else {
                    Text("Vyber vítěze. Výsledek lze později opravit; neplatné navazující výsledky se vymažou.")
                    listOfNotNull(match.player1Id, match.player2Id).forEach { id ->
                        if (chosenWinner == id) Button(onClick = { chosenWinner = id }, modifier = Modifier.fillMaxWidth()) { Text("✓ ${playerName(id)}") }
                        else OutlinedButton(onClick = { chosenWinner = id }, modifier = Modifier.fillMaxWidth()) { Text(playerName(id)) }
                    }
                    if (match.winnerId != null) Text("Aktuální vítěz: ${playerName(match.winnerId)}", color = MaterialTheme.colorScheme.secondary)
                }
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                if (one != null && !confirmAdvance) Button(onClick = { confirmAdvance = true }) { Text("Postoupit bez zápasu…") }
                else if (one != null && confirmAdvance) Button(onClick = onAdvance) { Text("Ano, potvrdit postup") }
                if (canEditPairing) {
                    TextButton(onClick = onEditPairing) { Text("Ruční úprava soupeře") }
                }
                if (canEditSlots) {
                    TextButton(onClick = onEditSlots) { Text("Upravit libovolnou pozici") }
                }
                if (!isFinished && match.player1Id != null && match.player2Id != null && match.winnerId == null && match.status != "IN_PROGRESS") {
                    TextButton(onClick = onStart) { Text("Označit jako rozehraný") }
                }
                if (chosenWinner != null && match.player1Id != null && match.player2Id != null) {
                    Button(onClick = { onConfirmWinner(chosenWinner!!) }) { Text(if (match.winnerId != null) "Potvrdit opravu" else "Potvrdit výsledek") }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Zavřít") } }
    )
}

@Composable
private fun ManualSwapDialog(
    match: MatchEntity,
    participants: List<TournamentParticipantEntity>,
    playerName: (Long) -> String,
    onDismiss: () -> Unit,
    onConfirmSwap: (Long, Long) -> Unit
) {
    val thisOpeningIndex = match.matchKey.removePrefix("W1_").toIntOrNull() ?: -1
    val thisMatchIds = listOfNotNull(match.player1Id, match.player2Id).toSet()
    val replacementOptions = participants.filter { candidate ->
        candidate.playerId !in thisMatchIds && (candidate.seed - 1) / 2 != thisOpeningIndex
    }
    var movingPlayerId by remember(match.matchNumber) {
        mutableStateOf(match.player1Id ?: match.player2Id ?: 0L)
    }
    var replacementPlayerId by remember(match.matchNumber) {
        mutableStateOf(replacementOptions.firstOrNull()?.playerId)
    }
    var movingMenu by remember { mutableStateOf(false) }
    var replacementMenu by remember { mutableStateOf(false) }
    var confirmStep by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ruční úprava soupeře") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Úvodní dvojice · zápas ${match.matchNumber}", color = MaterialTheme.colorScheme.secondary)
                Text("Kterého hráče chceš přesunout?")
                Box {
                    OutlinedButton(onClick = { movingMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(playerName(movingPlayerId), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    DropdownMenu(expanded = movingMenu, onDismissRequest = { movingMenu = false }) {
                        listOfNotNull(match.player1Id, match.player2Id).forEach { id ->
                            DropdownMenuItem(text = { Text(playerName(id)) }, onClick = {
                                movingPlayerId = id
                                movingMenu = false
                            })
                        }
                    }
                }
                Text("S kým si má vyměnit místo?")
                Box {
                    OutlinedButton(
                        onClick = { replacementMenu = true },
                        enabled = replacementOptions.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(replacementPlayerId?.let(playerName) ?: "Není dostupný další hráč",
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    DropdownMenu(expanded = replacementMenu, onDismissRequest = { replacementMenu = false }) {
                        replacementOptions.forEach { participant ->
                            DropdownMenuItem(
                                text = { Text(participant.playerNameSnapshot) },
                                onClick = { replacementPlayerId = participant.playerId; replacementMenu = false }
                            )
                        }
                    }
                }
                if (confirmStep) {
                    Text(
                        "Potvrzením se přepočítají navazující zápasy. Výsledky zápasů, ve kterých se změní hráči, se zruší; body a ELO se vypočítají znovu.",
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    Text(
                        "Už odehrané výsledky zůstanou zachovány, pokud se nezmění účastníci daného zápasu.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            if (!confirmStep) {
                Button(onClick = { confirmStep = true }, enabled = replacementPlayerId != null) {
                    Text("Zkontrolovat změnu")
                }
            } else {
                Button(
                    onClick = { replacementPlayerId?.let { onConfirmSwap(movingPlayerId, it) } },
                    enabled = replacementPlayerId != null && movingPlayerId != 0L
                ) { Text("Potvrdit výměnu") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (confirmStep) "Zrušit" else "Zavřít") } }
    )
}

@Composable
private fun CreateTournamentDialog(players: List<PlayerEntity>, clans: List<ClanEntity>, onDismiss: () -> Unit, onCreate: (String, String, String, List<Long>) -> Unit) {
    var name by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    var note by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    var clanFilter by remember { mutableStateOf<Long?>(null) }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var showClanMenu by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nový turnaj") },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Název turnaje *") }, singleLine = true)
                OutlinedTextField(date, { date = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Datum (RRRR-MM-DD)") }, singleLine = true)
                OutlinedTextField(note, { note = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Poznámka (volitelné)") }, minLines = 2)
                Divider()
                Text("Hráči (${selected.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                OutlinedTextField(search, { search = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Hledat hráče") }, singleLine = true)
                Box {
                    OutlinedButton(onClick = { showClanMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(clanFilter?.let { id -> clans.firstOrNull { it.id == id }?.name ?: "Všechny klany" } ?: "Všechny klany")
                    }
                    DropdownMenu(expanded = showClanMenu, onDismissRequest = { showClanMenu = false }) {
                        DropdownMenuItem(text = { Text("Všechny klany") }, onClick = { clanFilter = null; showClanMenu = false })
                        DropdownMenuItem(text = { Text("Bez klanu") }, onClick = { clanFilter = -1L; showClanMenu = false })
                        clans.forEach { clan -> DropdownMenuItem(text = { Text(clan.name) }, onClick = { clanFilter = clan.id; showClanMenu = false }) }
                    }
                }
                players.filter { p ->
                    p.name.contains(search, true) && when (clanFilter) { null -> true; -1L -> p.clanId == null; else -> p.clanId == clanFilter }
                }.forEach { player ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = player.id in selected, onCheckedChange = { checked -> selected = if (checked) selected + player.id else selected - player.id })
                        Column(Modifier.weight(1f)) {
                            Text(player.name)
                            Text(clans.firstOrNull { it.id == player.clanId }?.name ?: "Bez klanu", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (players.isEmpty()) Text("Nejprve vytvoř alespoň dva hráče v knihovně.", color = MaterialTheme.colorScheme.error)
                Text("Turnaj podporuje 2–32 hráčů. Volné pozice v bracketu nepostupují automaticky.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { Button(onClick = { onCreate(name, date, note, selected.toList()) }, enabled = name.isNotBlank() && selected.size in 2..32) { Text("Zahájit turnaj") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Zrušit") } }
    )
}

@Composable
private fun WaitlistDialog(
    availablePlayers: List<PlayerEntity>,
    onDismiss: () -> Unit,
    onAddExisting: (Long) -> Unit,
    onAddNew: (String) -> Unit
) {
    var selectedId by remember { mutableStateOf<Long?>(null) }
    var newName by remember { mutableStateOf("") }
    var showPlayers by remember { mutableStateOf(false) }
    val selected = availablePlayers.firstOrNull { it.id == selectedId }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Přidat do čekací fronty") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Vyber existujícího hráče, nebo zadej nového.")
                Box {
                    OutlinedButton(onClick = { showPlayers = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(selected?.name ?: "Vybrat existujícího hráče")
                    }
                    DropdownMenu(expanded = showPlayers, onDismissRequest = { showPlayers = false }) {
                        availablePlayers.forEach { player ->
                            DropdownMenuItem(text = { Text(player.name) }, onClick = {
                                selectedId = player.id
                                newName = ""
                                showPlayers = false
                            })
                        }
                    }
                }
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it; if (it.isNotBlank()) selectedId = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Nebo vytvořit nového hráče") },
                    singleLine = true
                )
                if (availablePlayers.isEmpty()) Text("Žádní dostupní existující hráči.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Button(
                onClick = { if (newName.isNotBlank()) onAddNew(newName.trim()) else selectedId?.let(onAddExisting) },
                enabled = newName.isNotBlank() || selectedId != null
            ) { Text("Přidat do fronty") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Zrušit") } }
    )
}

@Composable
private fun PlayerEditorDialog(title: String, initial: PlayerEntity?, clans: List<ClanEntity>, onDismiss: () -> Unit, onSave: (String, Long?) -> Unit) {
    var name by remember(initial?.id) { mutableStateOf(initial?.name ?: "") }
    var clanId by remember(initial?.id) { mutableStateOf(initial?.clanId) }
    var showMenu by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Jméno nebo přezdívka *") }, singleLine = true)
                Box {
                    OutlinedButton(onClick = { showMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(clanId?.let { id -> clans.firstOrNull { it.id == id }?.name } ?: "Bez klanu")
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(text = { Text("Bez klanu") }, onClick = { clanId = null; showMenu = false })
                        clans.forEach { clan -> DropdownMenuItem(text = { Text(clan.name) }, onClick = { clanId = clan.id; showMenu = false }) }
                    }
                }
                if (initial != null) Text("Interní ID hráče: ${initial.id}. Změna jména nesmaže jeho historii.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { Button(onClick = { onSave(name, clanId) }, enabled = name.isNotBlank()) { Text("Uložit") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Zrušit") } }
    )
}

@Composable
private fun NameDialog(title: String, label: String, initial: String = "", onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by remember(title, initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value, { value = it }, modifier = Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true) },
        confirmButton = { Button(onClick = { onSave(value) }, enabled = value.isNotBlank()) { Text("Uložit") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Zrušit") } }
    )
}

@Composable
private fun PlayerProfileDialog(
    player: PlayerEntity, clanName: String, stats: PlayerStats?, allMatches: List<MatchEntity>, participants: List<TournamentParticipantEntity>,
    tournaments: List<TournamentEntity>, nameFor: (Long) -> String, onDismiss: () -> Unit, onExport: () -> Unit
) {
    val games = allMatches.filter { (it.player1Id == player.id || it.player2Id == player.id) && it.winnerId != null && it.player1Id != null && it.player2Id != null }
        .sortedByDescending { it.completedAt }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(player.name, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(clanName, color = MaterialTheme.colorScheme.secondary)
                Text("ELO hodnocení: ${player.eloRating}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.secondary)
                Divider()
                Text("Zápasy: ${stats?.games ?: 0} · Výhry: ${stats?.wins ?: 0} · Prohry: ${stats?.losses ?: 0}")
                Text("Úspěšnost: ${formatPercent(stats?.winRate ?: 0.0)}")
                Text("Aktuální série: W${stats?.currentWinStreak ?: 0} / L${stats?.currentLossStreak ?: 0}")
                Text("Nejdelší winstreak: ${stats?.longestWinStreak ?: 0}")
                Text("Body: ${stats?.points ?: 0} · Turnaje: ${stats?.tournaments ?: 0} · Vítězství v turnaji: ${stats?.tournamentWins ?: 0}")
                Divider()
                Text("Poslední zápasy", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                if (games.isEmpty()) Text("Hráč zatím nemá dokončené zápasy.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                games.take(20).forEach { match ->
                    val won = match.winnerId == player.id
                    Text("${if (won) "VÝHRA" else "PROHRA"} · ${match.player1NameSnapshot ?: match.player1Id?.let(nameFor)} — ${match.player2NameSnapshot ?: match.player2Id?.let(nameFor)}", style = MaterialTheme.typography.bodySmall, color = if (won) Color(0xFF9CD5A8) else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Divider()
                Text("Turnajová umístění", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                val participated = participants.filter { it.playerId == player.id }.mapNotNull { p -> tournaments.firstOrNull { it.id == p.tournamentId } }
                participated.forEach { tournament -> Text("${tournament.dateIso} · ${tournament.name}${if (tournament.winnerId == player.id) " — VÍTĚZ" else ""}", style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { Button(onClick = onExport) { Text("Export PDF") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Zavřít") } }
    )
}

@Composable
private fun ClanProfileDialog(
    clan: ClanEntity, players: List<PlayerEntity>, stats: List<PlayerStats>, points: List<PointTransactionEntity>,
    adjustments: List<ClanPointAdjustmentEntity>, onDismiss: () -> Unit
) {
    val members = stats.filter { it.player.clanId == clan.id }
    val wins = members.sumOf { it.wins }
    val losses = members.sumOf { it.losses }
    val score = points.filter { it.clanIdSnapshot == clan.id }.sumOf { it.points } +
        adjustments.filter { it.clanIdSnapshot == clan.id }.sumOf { it.points }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(clan.name, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${players.size} aktuálních členů")
                Text("Body klanu: $score", color = MaterialTheme.colorScheme.secondary)
                Text("Výhry současných členů: $wins · prohry: $losses")
                val manualAdjustments = adjustments.filter { it.clanIdSnapshot == clan.id }.sortedByDescending { it.createdAt }
                if (manualAdjustments.isNotEmpty()) {
                    Divider()
                    Text("Ruční úpravy bodů", fontWeight = FontWeight.SemiBold)
                    manualAdjustments.take(10).forEach { entry ->
                        Text("${if (entry.points > 0) "+" else ""}${entry.points} b. · ${entry.reason}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Divider()
                players.forEach { Text("• ${it.name}") }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("Zavřít") } }
    )
}

@Composable
private fun ClanPointsDialog(
    clan: ClanEntity,
    onDismiss: () -> Unit,
    onSubmit: (Int, String) -> Unit
) {
    var amount by remember(clan.id) { mutableStateOf("1") }
    var reason by remember(clan.id) { mutableStateOf("") }
    val parsedAmount = amount.toIntOrNull()?.takeIf { it in 1..1_000_000 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Upravit body · ${clan.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Přidání nebo odebrání bodů se uloží do historie a zachová se i po restartu.")
                OutlinedTextField(
                    value = amount,
                    onValueChange = { value -> amount = value.filter { it.isDigit() }.take(7) },
                    label = { Text("Počet bodů") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it.take(120) },
                    label = { Text("Důvod (volitelné)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    enabled = parsedAmount != null,
                    onClick = { parsedAmount?.let { onSubmit(it, reason) } }
                ) { Text("+ Přidat") }
                Button(
                    enabled = parsedAmount != null,
                    onClick = { parsedAmount?.let { onSubmit(-it, reason) } }
                ) { Text("− Odebrat") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Zrušit") } }
    )
}

@Composable
private fun ConfirmDialog(title: String, text: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { Button(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Zrušit") } }
    )
}

@Composable
private fun StatusPill(status: String) {
    val label = when (status) {
        TournamentEntity.STATUS_ACTIVE -> "AKTIVNÍ"
        TournamentEntity.STATUS_FINISHED -> "UKONČENÝ"
        else -> "PLÁNOVANÝ"
    }
    Surface(color = when (status) {
        TournamentEntity.STATUS_ACTIVE -> Color(0xFF3A2C4A)
        TournamentEntity.STATUS_FINISHED -> Color(0xFF213D30)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }, shape = MaterialTheme.shapes.small) {
        Text(label, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun EmptyState(title: String, description: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun buildStats(
    players: List<PlayerEntity>, matches: List<MatchEntity>, points: List<PointTransactionEntity>,
    participants: List<TournamentParticipantEntity>, tournaments: List<TournamentEntity>
): List<PlayerStats> = players.map { player ->
    val games = matches.filter { (it.player1Id == player.id || it.player2Id == player.id) && it.winnerId != null && it.player1Id != null && it.player2Id != null }
        .sortedBy { it.completedAt ?: Long.MIN_VALUE }
    val wins = games.count { it.winnerId == player.id }
    val losses = games.count { it.loserId == player.id }
    val outcomes = games.map { it.winnerId == player.id }
    var longest = 0
    var current = 0
    outcomes.forEach { won -> current = if (won) current + 1 else 0; if (current > longest) longest = current }
    var winStreak = 0
    var lossStreak = 0
    outcomes.asReversed().takeWhile { it }.forEach { winStreak++ }
    outcomes.asReversed().takeWhile { !it }.forEach { lossStreak++ }
    PlayerStats(
        player = player,
        wins = wins,
        losses = losses,
        games = wins + losses,
        winRate = if (wins + losses == 0) 0.0 else wins.toDouble() / (wins + losses),
        currentWinStreak = winStreak,
        currentLossStreak = lossStreak,
        longestWinStreak = longest,
        points = points.filter { it.playerId == player.id }.sumOf { it.points },
        tournaments = participants.count { it.playerId == player.id },
        tournamentWins = tournaments.count { it.winnerId == player.id }
    )
}

private fun formatPercent(value: Double): String = String.format(Locale.getDefault(), "%.0f %%", value * 100)
private fun branchOrder(branch: String): Int = when (branch) { "WINNERS" -> 0; "LOSERS" -> 1; else -> 2 }
private fun columnTitle(branch: String, round: Int): String = when (branch) {
    "WINNERS" -> "Vítězná větev · kolo $round"
    "LOSERS" -> "Poraženecká větev · kolo $round"
    else -> "Finále"
}
private fun safeFileName(name: String): String = name.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9-]+"), "-").trim('-').ifBlank { "magic-heroes" }

private fun buildTournamentPdf(tournament: TournamentEntity, participants: List<TournamentParticipantEntity>, matches: List<MatchEntity>, points: List<PointTransactionEntity>): List<String> = buildList {
    add("Turnaj: ${tournament.name}")
    add("Datum: ${tournament.dateIso}")
    add("Stav: ${tournament.status}")
    if (tournament.note.isNotBlank()) { add("Poznámka: ${tournament.note}"); add("") }
    add("ÚČASTNÍCI")
    participants.sortedBy { it.seed }.forEach {
        val status = when {
            it.isWithdrawn -> "ODSTOUPIL "
            it.isQueued -> "ČEKÁ ${it.seed}. "
            else -> "${it.seed}. "
        }
        add("$status${it.playerNameSnapshot} — ${it.clanNameSnapshot ?: "Bez klanu"}")
    }
    add("")
    add("ZÁPASY")
    matches.forEach { m ->
        val p1 = m.player1NameSnapshot ?: "Volná pozice"
        val p2 = m.player2NameSnapshot ?: "Čeká na soupeře"
        val winner = m.winnerId?.let { id -> if (id == m.player1Id) p1 else p2 } ?: "bez výsledku"
        add("#${m.matchNumber} ${columnTitle(m.branch, m.roundNumber)}: $p1 vs. $p2 | ${m.status} | vítěz: $winner")
    }
    add("")
    add("BODY")
    if (points.isEmpty()) add("Zatím bez bodových záznamů.")
    points.sortedBy { it.createdAt }.forEach { add("${it.clanNameSnapshot ?: "Bez klanu"} | hráč ID ${it.playerId} | ${it.points} b. | ${it.reason}") }
    val finalWinner = matches.firstOrNull { it.isTournamentFinal }?.let { m -> if (m.winnerId == m.player1Id) m.player1NameSnapshot else if (m.winnerId == m.player2Id) m.player2NameSnapshot else null }
    add("")
    add("VÍTĚZ TURNAJE: ${finalWinner ?: "neurčen"}")
}

private fun buildLeaderboardPdf(stats: List<PlayerStats>, clans: Map<Long, ClanEntity>): List<String> = buildList {
    add("Pozice | Hráč | ELO | Klan | Body | Výhry | Prohry | Úspěšnost | W streak | L streak | Nejdelší W")
    val sorted = stats.sortedWith(compareByDescending<PlayerStats> { it.points }.thenByDescending { it.winRate }.thenByDescending { it.wins })
    sorted.forEachIndexed { index, s ->
        add("${index + 1}. | ${s.player.name} | ${s.player.eloRating} | ${s.player.clanId?.let { clans[it]?.name } ?: "Bez klanu"} | ${s.points} | ${s.wins} | ${s.losses} | ${formatPercent(s.winRate)} | ${s.currentWinStreak} | ${s.currentLossStreak} | ${s.longestWinStreak}")
    }
}

private fun buildClanLeaderboardPdf(
    clans: List<ClanEntity>, players: List<PlayerEntity>, points: List<PointTransactionEntity>,
    adjustments: List<ClanPointAdjustmentEntity>, stats: List<PlayerStats>
): List<String> = buildList {
    add("Pozice | Klan | Celkové body | Aktivní členové | Výhry členů | Prohry členů")
    clans.map { clan ->
        val memberStats = stats.filter { it.player.clanId == clan.id }
        val score = points.filter { it.clanIdSnapshot == clan.id }.sumOf { it.points } +
            adjustments.filter { it.clanIdSnapshot == clan.id }.sumOf { it.points }
        Triple(clan, score, memberStats)
    }.sortedByDescending { it.second }.forEachIndexed { index, row ->
        add("${index + 1}. | ${row.first.name} | ${row.second} | ${players.count { it.clanId == row.first.id }} | ${row.third.sumOf { it.wins }} | ${row.third.sumOf { it.losses }}")
    }
}

private fun buildPlayerPdf(
    player: PlayerEntity, stat: PlayerStats?, matches: List<MatchEntity>, participants: List<TournamentParticipantEntity>,
    tournaments: List<TournamentEntity>, points: List<PointTransactionEntity>, allPlayers: List<PlayerEntity>
): List<String> = buildList {
    add("Hráč: ${player.name}")
    add("ELO: ${player.eloRating}")
    add("Aktuální klan: ${player.clanId?.let { id -> "ID $id" } ?: "Bez klanu"}")
    add("Zápasy: ${stat?.games ?: 0} | Výhry: ${stat?.wins ?: 0} | Prohry: ${stat?.losses ?: 0}")
    add("Úspěšnost: ${formatPercent(stat?.winRate ?: 0.0)}")
    add("Aktuální winstreak: ${stat?.currentWinStreak ?: 0} | losestreak: ${stat?.currentLossStreak ?: 0} | nejdelší winstreak: ${stat?.longestWinStreak ?: 0}")
    add("Body: ${stat?.points ?: 0} | Turnaje: ${stat?.tournaments ?: 0} | Turnajová vítězství: ${stat?.tournamentWins ?: 0}")
    add("")
    add("HISTORIE ZÁPASŮ")
    matches.filter { it.winnerId != null && (it.player1Id == player.id || it.player2Id == player.id) }.sortedByDescending { it.completedAt }.forEach { match ->
        val won = match.winnerId == player.id
        add("${if (won) "Výhra" else "Prohra"} | ${match.player1NameSnapshot ?: "hráč"} vs. ${match.player2NameSnapshot ?: "hráč"} | zápas ${match.matchNumber}")
    }
    add("")
    add("HISTORIE TURNAJŮ")
    participants.filter { it.playerId == player.id }.forEach { p ->
        val tournament = tournaments.firstOrNull { it.id == p.tournamentId }
        if (tournament != null) add("${tournament.dateIso} | ${tournament.name} | ${if (tournament.winnerId == player.id) "vítěz" else tournament.status}")
    }
    add("")
    add("BODOVÉ ZÁZNAMY")
    points.filter { it.playerId == player.id }.sortedByDescending { it.createdAt }.forEach { add("${it.points} b. | ${it.reason} | turnaj ID ${it.tournamentId}") }
}
