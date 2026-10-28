import { SUPABASE_URL, SUPABASE_ANON_KEY } from "./config.js";

const $ = (selector) => document.querySelector(selector);
const state = { snapshot: null, view: "tournaments", search: "", currentTournamentId: null, currentPlayerId: null, currentClanId: null, leaderboardMode: "players" };

function esc(value) {
  return String(value ?? "—").replace(/[&<>"']/g, (char) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"
  })[char]);
}
function idNumber(value) {
  const id = Number(value);
  return Number.isSafeInteger(id) && id >= 0 ? id : null;
}
function dateLabel(value) {
  if (!value) return "Datum není uvedeno";
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? esc(value) : new Intl.DateTimeFormat("cs-CZ", { day: "numeric", month: "short", year: "numeric" }).format(parsed);
}
function numberLabel(value) { return new Intl.NumberFormat("cs-CZ").format(Number(value) || 0); }
function statusLabel(status) { return ({ PLANNED: "Plánovaný", ACTIVE: "Probíhá", FINISHED: "Dokončený" })[status] || "Turnaj"; }
function pillClass(status) { return status === "FINISHED" ? "finished" : status === "PLANNED" ? "planned" : ""; }
function data() { return state.snapshot || {}; }
function byId(list, id) { return (list || []).find((item) => Number(item.id) === Number(id)); }
function playerName(id, fallback) {
  if (id === null || id === undefined) return fallback || "—";
  return byId(data().players, id)?.name || fallback || "Hráč";
}
function playerLink(id, fallback) {
  const safeId = idNumber(id);
  if (safeId === null) return esc(fallback || "—");
  return '<button class="text-link" data-action="player" data-id="' + safeId + '">' + esc(playerName(safeId, fallback)) + "</button>";
}
function clanLink(clan) {
  return '<button class="text-link" data-action="clan" data-id="' + idNumber(clan.id) + '">' + esc(clan.name) + "</button>";
}
function currentMatches() { return data().matches || []; }
function currentTournaments() { return data().tournaments || []; }
function currentPlayers() { return data().players || []; }
function currentClans() { return data().clans || []; }
function statsFor(player) {
  const games = currentMatches().filter((match) => Number(match.player1Id) === Number(player.id) || Number(match.player2Id) === Number(player.id))
    .filter((match) => match.winnerId !== null && match.winnerId !== undefined && match.player1Id !== null && match.player1Id !== undefined && match.player2Id !== null && match.player2Id !== undefined);
  const wins = games.filter((match) => Number(match.winnerId) === Number(player.id)).length;
  const losses = games.filter((match) => Number(match.loserId) === Number(player.id)).length;
  return { wins, losses, games: games.length, winRate: games.length ? Math.round(100 * wins / games.length) : 0 };
}
function setTitle(eyebrow, title, searchPlaceholder) {
  $("#section-eyebrow").textContent = eyebrow;
  $("#section-title").textContent = title;
  $("#search-box").hidden = !searchPlaceholder;
  if (searchPlaceholder) $("#search").placeholder = searchPlaceholder;
}
function empty(message) { return '<div class="empty">' + esc(message) + "</div>"; }
function matchCard(match) {
  const winner = match.winnerId === null || match.winnerId === undefined ? null : Number(match.winnerId);
  const p1 = Number(match.player1Id) === winner && winner !== null ? "<strong>" + playerLink(match.player1Id, match.player1Name) + "</strong>" : playerLink(match.player1Id, match.player1Name);
  const p2 = Number(match.player2Id) === winner && winner !== null ? "<strong>" + playerLink(match.player2Id, match.player2Name) + "</strong>" : playerLink(match.player2Id, match.player2Name);
  const outcome = winner === null ? "Výsledek zatím není zveřejněn." : "Vítěz: <strong>" + playerLink(match.winnerId, match.winnerName) + "</strong>" +
    (match.loserId !== null && match.loserId !== undefined ? " · Poražený: " + playerLink(match.loserId, match.loserName) : "");
  return '<details class="match"><summary><span class="match-teams">' + p1 + '<span class="versus">vs.</span>' + p2 +
    '</span><span class="match-number">#' + esc(match.matchNumber) + "</span></summary><div class=\"match-body\">" + outcome +
    (match.completedAt ? "<br>Odehráno: " + dateLabel(Number(match.completedAt)) : "") + "</div></details>";
}
function renderMetrics() {
  $("#metrics").innerHTML = [
    ["Turnaje", currentTournaments().length],
    ["Výsledky", currentMatches().filter((m) => m.winnerId !== null && m.winnerId !== undefined).length],
    ["Aktivní hráči", currentPlayers().filter((p) => !p.archived).length],
    ["Klany", currentClans().length]
  ].map((metric) => '<div class="metric"><span class="num">' + numberLabel(metric[1]) + '</span><span class="label">' + esc(metric[0]) + "</span></div>").join("");
}
function renderTournamentList() {
  setTitle("ARCHIV ZÁPASŮ", "Turnaje", "Hledat turnaj…");
  const query = state.search.trim().toLocaleLowerCase("cs-CZ");
  const tournaments = currentTournaments().slice().sort((a, b) => String(b.dateIso).localeCompare(String(a.dateIso)) || Number(b.id) - Number(a.id))
    .filter((t) => !query || (t.name + " " + t.dateIso + " " + statusLabel(t.status)).toLocaleLowerCase("cs-CZ").includes(query));
  if (!tournaments.length) {
    $("#content").innerHTML = empty(currentTournaments().length ? "Tomuto hledání neodpovídá žádný turnaj." : "Zatím nejsou zveřejněná žádná data. V aplikaci Magic Heroes použij tlačítko Web → Publikovat.");
    return;
  }
  $("#content").innerHTML = '<div class="grid">' + tournaments.map((tournament) => {
    const participants = (data().participants || []).filter((p) => Number(p.tournamentId) === Number(tournament.id));
    const matches = currentMatches().filter((m) => Number(m.tournamentId) === Number(tournament.id));
    const played = matches.filter((m) => m.winnerId !== null && m.winnerId !== undefined).length;
    return '<button class="card clickable" data-action="tournament" data-id="' + idNumber(tournament.id) + '">' +
      '<div class="card-top"><span class="pill ' + pillClass(tournament.status) + '">' + esc(statusLabel(tournament.status)) + '</span><span class="numbered">' + dateLabel(tournament.dateIso) + '</span></div>' +
      '<h3>' + esc(tournament.name) + '</h3><p>' + (tournament.winnerName && tournament.winnerId !== null && tournament.winnerId !== undefined ? "Vítěz: " + esc(tournament.winnerName) : "Soutěžní pavouk a historie výsledků") +
      '</p><div class="card-stats"><div><strong>' + participants.length + '</strong> <span>hráčů</span></div><div><strong>' + played + "/" + matches.length + '</strong> <span>výsledků</span></div></div></button>';
  }).join("") + "</div>";
}
function renderTournamentDetail() {
  const tournament = byId(currentTournaments(), state.currentTournamentId);
  if (!tournament) { state.view = "tournaments"; render(); return; }
  setTitle("DETAIL TURNAJE", tournament.name, null);
  $("#content").innerHTML = '<button class="back-button" data-action="back">← Zpět na turnaje</button>' +
    '<section class="detail-hero"><span class="pill ' + pillClass(tournament.status) + '">' + esc(statusLabel(tournament.status)) + '</span><h2>' +
    esc(tournament.name) + '</h2><p>' + dateLabel(tournament.dateIso) + ' · ' + esc(statusLabel(tournament.status)) + '</p>' +
    (tournament.winnerId !== null && tournament.winnerId !== undefined ? '<p>🏆 Vítěz: <strong>' + playerLink(tournament.winnerId, tournament.winnerName) + "</strong></p>" : "") +
    '</section><div class="section-label">ÚČASTNÍCI</div>' + renderParticipants(tournament.id) + '<div class="section-label">PAVOUK A VÝSLEDKY</div>' + renderBracket(tournament.id);
}
function renderParticipants(tournamentId) {
  const participants = (data().participants || []).filter((p) => Number(p.tournamentId) === Number(tournamentId)).slice().sort((a, b) => Number(a.seed) - Number(b.seed));
  if (!participants.length) return empty("Účastníci nejsou k dispozici.");
  return '<div class="participant-list">' + participants.map((p) => '<span class="participant">#' + esc(p.seed) + " · " + playerLink(p.playerId, p.playerName) +
    (p.withdrawn ? " · odstoupil" : "") + (p.queued ? " · čeká" : "") + "</span>").join("") + "</div>";
}
function renderBracket(tournamentId) {
  const matches = currentMatches().filter((m) => Number(m.tournamentId) === Number(tournamentId)).slice().sort((a, b) => Number(a.matchNumber) - Number(b.matchNumber));
  if (!matches.length) return empty("Zápasy nejsou k dispozici.");
  const branches = [["WINNERS", "Vítězná větev"], ["LOSERS", "Poraženecká větev"], ["FINAL", "Finále"]];
  return branches.map(([branch, label]) => {
    const list = matches.filter((match) => match.branch === branch);
    if (!list.length) return "";
    const roundNumbers = [...new Set(list.map((m) => Number(m.roundNumber)))].sort((a, b) => a - b);
    return '<section><div class="section-label">' + esc(label) + '</div><div class="rounds">' + roundNumbers.map((round) => {
      const roundMatches = list.filter((m) => Number(m.roundNumber) === round);
      return '<div class="round-column"><h3>Kolo ' + round + '</h3><div class="match-list">' + roundMatches.map(matchCard).join("") + "</div></div>";
    }).join("") + "</div></section>";
  }).join("");
}
function renderPlayerList() {
  setTitle("KNIHOVNA PROFILŮ", "Hráči", "Hledat hráče…");
  const query = state.search.trim().toLocaleLowerCase("cs-CZ");
  const players = currentPlayers().filter((p) => !p.archived && (!query || (p.name + " " + p.clanName).toLocaleLowerCase("cs-CZ").includes(query)))
    .sort((a, b) => Number(b.eloRating) - Number(a.eloRating) || Number(b.points) - Number(a.points));
  if (!players.length) { $("#content").innerHTML = empty("Žádní hráči neodpovídají hledání."); return; }
  $("#content").innerHTML = '<div class="grid">' + players.map((p) => {
    const stats = statsFor(p);
    return '<button class="card clickable" data-action="player" data-id="' + idNumber(p.id) + '"><div class="player-row"><div class="avatar">' +
      esc((p.name || "?").trim().slice(0, 1).toUpperCase()) + '</div><div class="player-main"><h3>' + esc(p.name) + '</h3><p>' + esc(p.clanName || "Bez klanu") +
      '</p></div><div class="score">' + numberLabel(p.eloRating) + '<br><small>ELO</small></div></div>' +
      '<div class="card-stats"><div><strong>' + stats.wins + '</strong> <span>výher</span></div><div><strong>' + stats.losses + '</strong> <span>proher</span></div><div><strong>' +
      numberLabel(p.points) + '</strong> <span>bodů</span></div></div></button>';
  }).join("") + "</div>";
}
function renderPlayerProfile() {
  const player = byId(currentPlayers(), state.currentPlayerId);
  if (!player) { state.view = "players"; render(); return; }
  setTitle("PROFIL HRÁČE", player.name, null);
  const stats = statsFor(player);
  const history = currentMatches().filter((m) => Number(m.player1Id) === Number(player.id) || Number(m.player2Id) === Number(player.id))
    .filter((m) => m.winnerId !== null && m.winnerId !== undefined).sort((a, b) => Number(b.completedAt || 0) - Number(a.completedAt || 0)).slice(0, 12);
  const tournaments = (data().participants || []).filter((p) => Number(p.playerId) === Number(player.id))
    .map((p) => ({ participant: p, tournament: byId(currentTournaments(), p.tournamentId) }))
    .filter((item) => item.tournament).sort((a, b) => String(b.tournament.dateIso).localeCompare(String(a.tournament.dateIso)));
  const clan = player.clanId !== null && player.clanId !== undefined ? byId(currentClans(), player.clanId) : null;
  $("#content").innerHTML = '<button class="back-button" data-action="back">← Zpět na hráče</button><section class="detail-hero">' +
    '<div class="player-row"><div class="avatar">' + esc((player.name || "?").trim().slice(0, 1).toUpperCase()) + '</div><div><div class="eyebrow">HRÁČSKÝ PROFIL</div><h2>' +
    esc(player.name) + '</h2><p>' + (clan ? clanLink(clan) : esc(player.clanName || "Bez klanu")) + (player.archived ? " · archivovaný profil" : "") + '</p></div></div></section>' +
    '<div class="profile-grid">' + statTile(numberLabel(player.eloRating), "ELO") + statTile(numberLabel(player.points), "Body") +
    statTile(stats.wins, "Výhry") + statTile(stats.losses, "Prohry") + '</div><div class="section-label">ZÁPASY</div>' +
    (history.length ? '<div class="match-list">' + history.map((m) => {
      const won = Number(m.winnerId) === Number(player.id);
      const opponentId = Number(m.player1Id) === Number(player.id) ? m.player2Id : m.player1Id;
      const opponentName = Number(m.player1Id) === Number(player.id) ? m.player2Name : m.player1Name;
      const tournament = byId(currentTournaments(), m.tournamentId);
      return '<details class="match"><summary><span class="match-teams">' + (won ? '<strong>VÝHRA</strong>' : "PROHRA") + ' · vs. ' +
        playerLink(opponentId, opponentName) + '<br><small>' + esc(tournament?.name || "Turnaj") + '</small></span><span class="match-number">#' +
        esc(m.matchNumber) + "</span></summary><div class=\"match-body\">Vítěz: " + playerLink(m.winnerId, m.winnerName) +
        (m.completedAt ? "<br>" + dateLabel(Number(m.completedAt)) : "") + "</div></details>";
    }).join("") + "</div>" : empty("Tento profil zatím nemá zveřejněné odehrané zápasy.")) +
    '<div class="section-label">TURNAJOVÁ HISTORIE</div>' +
    (tournaments.length ? '<div class="participant-list">' + tournaments.map((item) => '<button class="participant" data-action="tournament" data-id="' +
      idNumber(item.tournament.id) + '">' + esc(item.tournament.name) + " · " + dateLabel(item.tournament.dateIso) +
      (Number(item.tournament.winnerId) === Number(player.id) ? " · VÍTĚZ" : "") + "</button>").join("") + "</div>" : empty("Žádná zveřejněná účast v turnaji."));
}
function statTile(number, label) { return '<div class="profile-stat"><span>' + esc(number) + '</span><small>' + esc(label) + "</small></div>"; }
function renderClanList() {
  setTitle("TÝMOVÁ RIVALITA", "Klany", "Hledat klan…");
  const query = state.search.trim().toLocaleLowerCase("cs-CZ");
  const clans = currentClans().filter((c) => !query || c.name.toLocaleLowerCase("cs-CZ").includes(query))
    .sort((a, b) => Number(b.points) - Number(a.points) || a.name.localeCompare(b.name, "cs"));
  if (!clans.length) { $("#content").innerHTML = empty("Žádné klany neodpovídají hledání."); return; }
  $("#content").innerHTML = '<div class="grid">' + clans.map((clan, index) => {
    const members = currentPlayers().filter((p) => !p.archived && Number(p.clanId) === Number(clan.id));
    return '<button class="card clickable" data-action="clan" data-id="' + idNumber(clan.id) + '"><div class="card-top"><span class="numbered">#' +
      (index + 1) + ' · KLAN</span><span class="score">' + numberLabel(clan.points) + ' bodů</span></div><h3>' + esc(clan.name) +
      '</h3><p>' + members.length + ' aktivních členů</p><div class="participant-list">' + members.slice(0, 5).map((p) => '<span class="participant">' + esc(p.name) + "</span>").join("") + '</div></button>';
  }).join("") + "</div>";
}
function renderClanProfile() {
  const clan = byId(currentClans(), state.currentClanId);
  if (!clan) { state.view = "clans"; render(); return; }
  setTitle("KLANOVÝ PROFIL", clan.name, null);
  const members = currentPlayers().filter((p) => !p.archived && Number(p.clanId) === Number(clan.id)).sort((a, b) => Number(b.eloRating) - Number(a.eloRating));
  const sumElo = members.reduce((sum, p) => sum + Number(p.eloRating || 0), 0);
  $("#content").innerHTML = '<button class="back-button" data-action="back">← Zpět na klany</button><section class="detail-hero"><div class="eyebrow">KLANOVÝ PROFIL</div><h2>' +
    esc(clan.name) + '</h2><p>' + members.length + ' aktivních členů v poslední zveřejněné verzi.</p></section><div class="profile-grid">' +
    statTile(numberLabel(clan.points), "Body klanu") + statTile(members.length, "Aktivní členové") + statTile(numberLabel(sumElo), "Součet ELO") +
    statTile(members.length ? numberLabel(Math.round(sumElo / members.length)) : 0, "Průměrné ELO") + '</div><div class="section-label">ČLENOVÉ KLANU</div>' +
    (members.length ? '<div class="grid">' + members.map((p) => '<button class="card clickable" data-action="player" data-id="' + idNumber(p.id) +
      '"><div class="player-row"><div class="avatar">' + esc((p.name || "?").slice(0, 1).toUpperCase()) + '</div><div class="player-main"><h3>' +
      esc(p.name) + '</h3><p>' + numberLabel(p.eloRating) + ' ELO</p></div></div></button>').join("") + "</div>" : empty("Tento klan nemá aktuálně žádné aktivní členy."));
}
function renderLeaderboard() {
  setTitle("SOUTĚŽNÍ POŘADÍ", "Žebříčky", null);
  const playersMode = state.leaderboardMode === "players";
  const list = playersMode ? currentPlayers().filter((p) => !p.archived).slice().sort((a, b) => Number(b.eloRating) - Number(a.eloRating) || Number(b.points) - Number(a.points))
    : currentClans().slice().sort((a, b) => Number(b.points) - Number(a.points) || a.name.localeCompare(b.name, "cs"));
  $("#subnav").innerHTML = '<div class="subnav"><button class="subtab ' + (playersMode ? "active" : "") + '" data-action="leaderboard-players">Hráči · ELO</button><button class="subtab ' +
    (!playersMode ? "active" : "") + '" data-action="leaderboard-clans">Klany · body</button></div>';
  if (!list.length) { $("#content").innerHTML = empty("Žebříček zatím nemá žádné údaje."); return; }
  $("#content").innerHTML = '<div class="table-list"><div class="table-row table-header"><span>#</span><span>Jméno</span><span class="align-right">' +
    (playersMode ? "ELO" : "Body") + '</span><span class="align-right">' + (playersMode ? "Body" : "Členové") + '</span></div>' +
    list.map((item, index) => '<div class="table-row"><span class="rank">' + String(index + 1).padStart(2, "0") + '</span><span>' +
      (playersMode ? playerLink(item.id, item.name) : clanLink(item)) + '</span><strong class="align-right">' +
      numberLabel(playersMode ? item.eloRating : item.points) + '</strong><span class="align-right">' +
      numberLabel(playersMode ? item.points : item.memberCount) + "</span></div>").join("") + "</div>";
}
function render() {
  const titles = { tournaments: ["ARCHIV ZÁPASŮ", "Turnaje"], players: ["KNIHOVNA PROFILŮ", "Hráči"], clans: ["TÝMOVÁ RIVALITA", "Klany"], leaderboard: ["SOUTĚŽNÍ POŘADÍ", "Žebříčky"] };
  document.querySelectorAll("[data-view]").forEach((button) => button.classList.toggle("active", button.dataset.view === state.view));
  $("#subnav").innerHTML = "";
  if (state.view === "tournament") return renderTournamentDetail();
  if (state.view === "player") return renderPlayerProfile();
  if (state.view === "clan") return renderClanProfile();
  if (titles[state.view]) { $("#section-eyebrow").textContent = titles[state.view][0]; $("#section-title").textContent = titles[state.view][1]; }
  if (state.view === "tournaments") return renderTournamentList();
  if (state.view === "players") return renderPlayerList();
  if (state.view === "clans") return renderClanList();
  renderLeaderboard();
}
async function loadSnapshot() {
  let url = String(SUPABASE_URL || "").trim();
  while (url.endsWith("/")) url = url.slice(0, -1);
  if (!url.startsWith("https://") || url.includes("YOUR_PROJECT_REF") || !SUPABASE_ANON_KEY || SUPABASE_ANON_KEY.includes("YOUR_SUPABASE")) {
    $("#published-status").textContent = "Čeká na nastavení veřejného databázového připojení";
    $("#content").innerHTML = '<div class="error-note">Webový vzhled je připravený. Před zveřejněním doplň hodnoty <strong>SUPABASE_URL</strong> a <strong>SUPABASE_ANON_KEY</strong> do souboru <code>website/config.js</code> a vytvoř tabulku podle dokumentace v repozitáři.</div>';
    $("#metrics").innerHTML = "";
    return;
  }
  try {
    const response = await fetch(url + "/rest/v1/public_site_snapshot?select=snapshot,published_at&id=eq.1", {
      headers: { apikey: SUPABASE_ANON_KEY }
    });
    if (!response.ok) throw new Error("Supabase vrátil HTTP " + response.status + ". Zkontroluj URL, klíč a veřejnou SELECT politiku.");
    const rows = await response.json();
    if (!Array.isArray(rows) || !rows.length || !rows[0].snapshot) {
      $("#published-status").textContent = "Zatím nebyla zveřejněna žádná data";
      $("#content").innerHTML = empty("Web je připravený. První veřejný snapshot nahraješ z aplikace Magic Heroes pomocí tlačítka Web → Publikovat.");
      $("#metrics").innerHTML = "";
      return;
    }
    state.snapshot = rows[0].snapshot;
    const publishedAt = rows[0].published_at || data().publishedAt;
    $("#published-status").textContent = "Poslední zveřejnění: " + dateLabel(publishedAt);
    $("#footer-updated").textContent = "Aktualizováno " + dateLabel(publishedAt);
    renderMetrics();
    render();
  } catch (error) {
    $("#published-status").textContent = "Data se nepodařilo načíst";
    $("#content").innerHTML = '<div class="error-note">' + esc(error?.message || "Neznámá chyba při načítání veřejných dat.") + "</div>";
    $("#metrics").innerHTML = "";
  }
}
function handleAction(event) {
  const button = event.target.closest("[data-action]");
  if (!button) return;
  const action = button.dataset.action;
  const id = idNumber(button.dataset.id);
  if (action === "tournament") { state.currentTournamentId = id; state.view = "tournament"; }
  else if (action === "player") { state.currentPlayerId = id; state.view = "player"; }
  else if (action === "clan") { state.currentClanId = id; state.view = "clan"; }
  else if (action === "back") {
    if (state.view === "tournament") state.view = "tournaments";
    else if (state.view === "player") state.view = "players";
    else if (state.view === "clan") state.view = "clans";
  } else if (action === "leaderboard-players" || action === "leaderboard-clans") {
    state.leaderboardMode = action === "leaderboard-players" ? "players" : "clans";
  } else return;
  render();
}
document.querySelectorAll("[data-view]").forEach((button) => button.addEventListener("click", () => {
  state.view = button.dataset.view; state.currentTournamentId = null; state.currentPlayerId = null; state.currentClanId = null; state.search = ""; $("#search").value = ""; render();
}));
$("#search").addEventListener("input", (event) => { state.search = event.target.value; render(); });
$("#content").addEventListener("click", handleAction);
$("#subnav").addEventListener("click", handleAction);
loadSnapshot();
