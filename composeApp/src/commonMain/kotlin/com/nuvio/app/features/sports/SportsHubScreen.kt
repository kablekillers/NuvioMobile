package com.nuvio.app.features.sports

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import coil3.compose.SubcomposeAsyncImage
import com.nuvio.app.core.ui.PlatformBackHandler
import com.nuvio.app.core.time.EpisodeReleaseDatePlatform
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.home.MetaPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

private val ElectricBlue = Color(0xFF42C7FF)
private val HubBackground = Color(0xFF071224)

private enum class SportsSection(val label: String) {
    Live("Live Now"), Upcoming("Upcoming"), Final("Final"),
    Replays("Replays"), Highlights("Highlights"),
}

@Composable
internal fun SportsHubScreen(
    onBack: () -> Unit,
    onOpenStream: (MetaPreview) -> Unit,
    repository: SportsEventRepository = SportsEventRepository.shared,
) {
    var league by rememberSaveable { mutableStateOf(SportsLeague.NFL) }
    var section by rememberSaveable { mutableStateOf(SportsSection.Live) }
    var showIntro by rememberSaveable { mutableStateOf(true) }
    var selectedEvent by remember { mutableStateOf<SportsEvent?>(null) }
    var board by remember(league) { mutableStateOf<SportsScoreboard?>(null) }
    var error by remember(league) { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(0) }
    val owner = LocalLifecycleOwner.current
    val addons by AddonRepository.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { AddonRepository.initialize() }
    LaunchedEffect(league, refresh, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            board = repository.cached(league)
            var force = refresh > 0
            while (true) {
                try {
                    board = repository.scoreboard(league, forceRefresh = force)
                    error = false
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    error = true
                }
                force = true
                delay(SportsEventRepository.refreshIntervalMillis(board))
            }
        }
    }
    PlatformBackHandler(enabled = selectedEvent != null) { selectedEvent = null }
    val currentEvent = selectedEvent?.let { selected ->
        board?.events?.firstOrNull { it.id == selected.id } ?: selected
    }
    val activeAddons = addons.addons.filter { it.isActive }.map { it.copy(isRefreshing = false, errorMessage = null) }
    val events = board?.events.orEmpty()
    val vod = section == SportsSection.Replays || section == SportsSection.Highlights
    var recordings by remember(league, vod, activeAddons) { mutableStateOf<List<SportsCatalogMatch>?>(null) }
    LaunchedEffect(league, vod, activeAddons, events) {
        if (vod) recordings = SportsAddonDiscovery.find(league, events, activeAddons, replays = true)
    }

    if (currentEvent != null) {
        SportsGameDetail(currentEvent, repository, activeAddons, { selectedEvent = null }, onOpenStream)
    } else {
        Column(Modifier.fillMaxSize().background(HubBackground).safeDrawingPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = onBack) { Text("Back", color = ElectricBlue) }
                Text("Kable Killers Sports Hub", style = MaterialTheme.typography.titleLarge, color = Color.White)
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                SportsLeague.entries.forEach { LeagueLogo(it, Modifier.size(32.dp)) }
            }
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(SportsLeague.entries) { sport ->
                    FilterChip(
                        selected = league == sport,
                        onClick = { league = sport },
                        label = { Text(sport.label) },
                        leadingIcon = { LeagueLogo(sport, Modifier.size(20.dp)) },
                    )
                }
            }
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(SportsSection.entries) { tab ->
                    FilterChip(selected = section == tab, onClick = { section = tab }, label = { Text(tab.label) })
                }
            }
            if (error) {
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (board == null) "Scoreboard unavailable." else "Showing the last available scores.", color = Color.White)
                    TextButton(onClick = { refresh++ }) { Text("Retry", color = ElectricBlue) }
                }
            }
            val games = when (section) {
                SportsSection.Live -> SportsEventSections.live(events)
                SportsSection.Upcoming -> SportsEventSections.upcoming(events)
                SportsSection.Final -> SportsEventSections.final(events)
                else -> emptyList()
            }
            val clips = recordings.orEmpty().filter {
                it.kind == if (section == SportsSection.Highlights) SportsContentKind.HIGHLIGHT else SportsContentKind.REPLAY
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when {
                    vod && recordings == null || !vod && board == null && !error ->
                        item { CircularProgressIndicator(color = ElectricBlue) }
                    vod && clips.isEmpty() ->
                        item { HubMessage("No ${section.label.lowercase()} found in enabled sports add-ons.") }
                    vod -> items(clips) { clip -> SourceCard(clip, onOpenStream) }
                    games.isNotEmpty() -> items(games, key = { it.id }) { event ->
                        Card(onClick = { selectedEvent = event }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(event.title, style = MaterialTheme.typography.titleMedium)
                                event.scoreLine()?.let { Text(it, fontWeight = FontWeight.Bold) }
                                Text(event.displayStatus())
                            }
                        }
                    }
                    !error || board != null -> item { HubMessage("No ${section.label.lowercase()} games for ${league.label}.") }
                }
            }
        }
    }
    if (showIntro) SportsHubIntro { showIntro = false }
}

@Composable
private fun SportsGameDetail(
    event: SportsEvent,
    repository: SportsEventRepository,
    addons: List<ManagedAddon>,
    onBack: () -> Unit,
    onOpenStream: (MetaPreview) -> Unit,
) {
    var sources by remember(event.id, event.status, addons) { mutableStateOf<List<SportsCatalogMatch>?>(null) }
    var boxScore by remember(event.id) { mutableStateOf<SportsBoxScore?>(null) }
    var statsLoading by remember(event.id) { mutableStateOf(true) }
    var statsError by remember(event.id) { mutableStateOf(false) }
    var teamIndex by remember(event.id) { mutableStateOf(0) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(event.id, event.status, addons) {
        sources = SportsAddonDiscovery.find(event.league, listOf(event), addons, event.status == SportsEventStatus.FINAL)
            .filter { it.score > 0 }
    }
    LaunchedEffect(event.id, event.status, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            do {
                try {
                    boxScore = repository.boxScore(event)
                    statsError = false
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    statsError = true
                }
                statsLoading = false
                if (event.status == SportsEventStatus.LIVE) delay(SportsEventRepository.LIVE_BOX_SCORE_MAX_AGE_MILLIS)
            } while (event.status == SportsEventStatus.LIVE)
        }
    }
    Column(Modifier.fillMaxSize().background(HubBackground).safeDrawingPadding()) {
        TextButton(onClick = onBack, modifier = Modifier.padding(horizontal = 12.dp)) {
            Text("Back to Sports Hub", color = ElectricBlue)
        }
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(event.title, style = MaterialTheme.typography.headlineSmall, color = Color.White)
                Text(event.displayStatus(), color = ElectricBlue)
                listOfNotNull(event.away, event.home).ifEmpty { event.competitors }.forEach { side ->
                    HubMessage(listOfNotNull(side.name, side.score, side.record).joinToString("  •  "))
                }
                event.venue?.let { HubMessage(it) }
                if (event.broadcasts.isNotEmpty()) HubMessage("Watch on ${event.broadcasts.joinToString()}")
            }
            item { HubHeading(if (event.status == SportsEventStatus.FINAL) "Replays and highlights" else "Watch game") }
            when {
                sources == null -> item { HubMessage("Checking enabled sports add-ons…") }
                sources.orEmpty().isEmpty() -> item { HubMessage("No matching stream found in enabled sports add-ons.") }
                else -> items(sources.orEmpty()) { source -> SourceCard(source, onOpenStream) }
            }
            item { HubHeading("Player stats") }
            val teams = boxScore?.teams.orEmpty()
            when {
                teams.isNotEmpty() -> {
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(teams.size) { index ->
                                FilterChip(index == teamIndex, { teamIndex = index }, { Text(teams[index].shortLabel) })
                            }
                        }
                    }
                    items(teams.getOrElse(teamIndex) { teams.first() }.groups) { StatTable(it) }
                }
                event.league == SportsLeague.UFC -> item { HubMessage("Fight stats aren’t available from the scoreboard.") }
                event.status == SportsEventStatus.SCHEDULED -> item { HubMessage("Player stats appear once the game starts.") }
                statsLoading -> item { HubMessage("Loading player stats…") }
                statsError -> item { HubMessage("Player stats are temporarily unavailable.") }
                else -> item { HubMessage("No player stats reported for this game yet.") }
            }
        }
    }
}

@Composable
private fun StatTable(group: SportsStatGroup) {
    val entries = group.players
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        group.title?.let { HubHeading(it) }
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            StatLine("Player", group.labels, group.labels.size, emphasized = true)
            entries.forEach { player -> StatLine(player.name, player.stats, group.labels.size, emphasized = false) }
            if (group.totals.isNotEmpty()) StatLine("Totals", group.totals, group.labels.size, emphasized = true)
        }
    }
}

@Composable
private fun StatLine(name: String, values: List<String>, columns: Int, emphasized: Boolean) {
    val weight = if (emphasized) FontWeight.Bold else FontWeight.Normal
    Row(Modifier.heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, modifier = Modifier.size(width = 150.dp, height = 32.dp).padding(end = 8.dp), color = Color.White, fontWeight = weight, maxLines = 2)
        repeat(columns) { i ->
            Box(Modifier.size(width = 60.dp, height = 32.dp), contentAlignment = Alignment.Center) {
                Text(values.getOrNull(i) ?: "–", color = Color.White, fontWeight = weight)
            }
        }
    }
}

@Composable
private fun SourceCard(source: SportsCatalogMatch, onOpenStream: (MetaPreview) -> Unit) {
    OutlinedButton(onClick = { onOpenStream(source.item) }, modifier = Modifier.fillMaxWidth()) {
        Text("${source.item.name} · ${source.addonName}", color = Color.White)
    }
}

@Composable
private fun HubHeading(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = ElectricBlue)
}

@Composable
private fun HubMessage(text: String) {
    Text(text, color = Color.White, style = MaterialTheme.typography.bodyMedium)
}

@Composable
internal fun SportsHubHomeButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(onClick = onClick, modifier = modifier) { Text("Sports Hub") }
}

@Composable
private fun LeagueLogo(league: SportsLeague, modifier: Modifier) {
    val slug = when (league) {
        SportsLeague.NFL -> "nfl"
        SportsLeague.COLLEGE_FOOTBALL -> null
        SportsLeague.NBA -> "nba"
        SportsLeague.MLB -> "mlb"
        SportsLeague.NHL -> "nhl"
        SportsLeague.UFC -> "ufc"
    }
    SubcomposeAsyncImage(
        model = if (slug == null) "https://a.espncdn.com/redesign/assets/img/icons/NCAASports_Football.png"
            else "https://a.espncdn.com/i/teamlogos/leagues/500/$slug.png",
        contentDescription = "${league.label} logo",
        contentScale = ContentScale.Fit,
        modifier = modifier,
        loading = { SportIcon(league.glyph(), color = ElectricBlue) },
        error = { SportIcon(league.glyph(), color = ElectricBlue) },
    )
}

private fun SportsEvent.displayStatus(): String =
    statusDetail?.takeIf { it.isNotBlank() }
        ?: startTimeEpochMillis?.takeIf { status == SportsEventStatus.SCHEDULED }
            ?.let(EpisodeReleaseDatePlatform::localIsoDateAtEpochMs)
        ?: status.name.lowercase().replaceFirstChar(Char::uppercaseChar)

@Composable
internal expect fun SportsHubIntro(onFinished: () -> Unit)
