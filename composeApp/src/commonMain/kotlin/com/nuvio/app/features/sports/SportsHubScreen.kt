package com.nuvio.app.features.sports

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import coil3.compose.SubcomposeAsyncImage
import com.nuvio.app.isIos
import com.nuvio.app.core.ui.CinemaGold
import com.nuvio.app.core.ui.CinemaGoldButton
import com.nuvio.app.core.ui.CinemaLivePill
import com.nuvio.app.core.ui.CinemaPulseDot
import com.nuvio.app.core.ui.PlatformBackHandler
import com.nuvio.app.core.ui.cinemaPressScale
import com.nuvio.app.core.ui.cinemaSpinRing
import com.nuvio.app.core.ui.rememberCinemaBreath
import com.nuvio.app.core.time.EpisodeReleaseDatePlatform
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.home.MetaPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

private val HubAccent = CinemaGold.Base
private val HubBackground = Color(0xFF08080A)
private val HubMuted = Color(0xFF9E998F)
private val HubCard = Color.White.copy(alpha = 0.045f)
private val HubCardBorder = Color.White.copy(alpha = 0.07f)
private val HubLiveText = Color(0xFFFF7B86)

private enum class SportsSection(val label: String) {
    AllSports("All Sports"), Live("Live Now"), MyTeams("My Teams"), Upcoming("Upcoming"), Final("Final"),
    Standings("Standings"), Replays("Replays"), Highlights("Highlights"), Events("Events"),
    Channels("NFL Channels"),
}

private fun nowMillis(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

private fun delayLabel(seconds: Int): String = when {
    seconds <= 0 -> "Live"
    seconds < 60 -> "${seconds}s delay"
    else -> "${seconds / 60} min delay"
}

@Composable
internal fun SportsHubScreen(
    onBack: () -> Unit,
    onOpenStream: (MetaPreview) -> Unit,
    repository: SportsEventRepository = SportsEventRepository.shared,
    showBack: Boolean = true,
) {
    val leagues = remember { SportsLeague.entries.filter { it.supportsPlatform(android = !isIos) } }
    var league by rememberSaveable { mutableStateOf(SportsLeague.NFL) }
    var section by rememberSaveable { mutableStateOf(SportsSection.AllSports) }
    var showIntro by rememberSaveable { mutableStateOf(true) }
    var multiviewOpen by rememberSaveable { mutableStateOf(false) }
    var multiviewLayout by rememberSaveable { mutableStateOf(2) }
    var multiviewState by remember { mutableStateOf(SportsMultiviewState()) }
    val multiviewSources = remember { mutableStateListOf<SportsCatalogMatch>() }
    var selectedEvent by remember { mutableStateOf<SportsEvent?>(null) }
    var board by remember(league) { mutableStateOf<SportsScoreboard?>(null) }
    var error by remember(league) { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(0) }
    val owner = LocalLifecycleOwner.current
    val addons by AddonRepository.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { AddonRepository.initialize() }
    LaunchedEffect(league, refresh, owner) {
        if (league.scoreboardPath == null) return@LaunchedEffect
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
    var combinedBoards by remember { mutableStateOf<List<SportsScoreboard>>(emptyList()) }
    var combinedLoading by remember { mutableStateOf(true) }
    var combinedError by remember { mutableStateOf(false) }
    LaunchedEffect(section, refresh, owner) {
        if (section != SportsSection.AllSports) return@LaunchedEffect
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var force = refresh > 0
            while (true) {
                val fetched = mutableListOf<SportsScoreboard>()
                var failures = 0
                for (sport in leagues.filter { it.scoreboardPath != null }) {
                    try {
                        fetched += repository.scoreboard(sport, forceRefresh = force)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        failures++
                        repository.cached(sport)?.let { fetched += it }
                    }
                }
                combinedBoards = fetched
                combinedError = failures > 0
                combinedLoading = false
                force = true
                delay(60_000L)
            }
        }
    }
    val prefs by SportsPreferences.state.collectAsStateWithLifecycle()
    // Score delay: keep recent scoreboards and show the one from [delay] ago, so scores on
    // screen don't run ahead of a stream that lags behind live TV.
    val history = remember(league) { mutableStateListOf<SportsScoreboard>() }
    LaunchedEffect(board) {
        val b = board ?: return@LaunchedEffect
        if (history.lastOrNull()?.fetchedAtEpochMillis != b.fetchedAtEpochMillis) {
            history.add(b)
            while (history.size > 40) history.removeAt(0)
        }
    }
    var clockNow by remember { mutableStateOf(nowMillis()) }
    LaunchedEffect(prefs.scoreDelaySeconds, section) {
        while (prefs.scoreDelaySeconds > 0 || section == SportsSection.AllSports) {
            clockNow = nowMillis()
            delay(5_000L)
        }
    }
    val shownBoard = if (prefs.scoreDelaySeconds <= 0) board else {
        val cutoff = clockNow - prefs.scoreDelaySeconds * 1_000L
        history.lastOrNull { it.fetchedAtEpochMillis <= cutoff } ?: history.firstOrNull() ?: board
    }
    PlatformBackHandler(enabled = selectedEvent != null) { selectedEvent = null }
    val currentEvent = selectedEvent?.let { selected ->
        (shownBoard?.events.orEmpty() + combinedBoards.flatMap { it.events }).firstOrNull { it.id == selected.id && it.league == selected.league } ?: selected
    }
    val activeAddons = addons.addons.filter { it.isActive }.map { it.copy(isRefreshing = false, errorMessage = null) }
    val events = shownBoard?.events.orEmpty()
    // My Teams: games across every league that has a favorite team.
    var myTeamGames by remember { mutableStateOf<List<SportsEvent>?>(null) }
    LaunchedEffect(section, prefs.favoriteTeams, refresh) {
        if (section != SportsSection.MyTeams) return@LaunchedEffect
        myTeamGames = null
        val found = SportsPreferences.favoriteLeagues(prefs).flatMap { lg ->
            runCatching { repository.scoreboard(lg).events }.getOrDefault(emptyList())
        }.filter { SportsPreferences.involvesFavorite(it, prefs) }
        myTeamGames = SportsEventSections.live(found) + SportsEventSections.upcoming(found) + SportsEventSections.final(found)
    }
    // Standings for the selected league.
    var standings by remember(league) { mutableStateOf<SportsStandings?>(null) }
    var standingsLoaded by remember(league) { mutableStateOf(false) }
    LaunchedEffect(section, league) {
        if (section != SportsSection.Standings || standingsLoaded) return@LaunchedEffect
        standings = runCatching { repository.standings(league) }.getOrNull()
        standingsLoaded = true
    }
    var nflChannels by remember { mutableStateOf<List<SportsCatalogMatch>?>(null) }
    LaunchedEffect(section, league, activeAddons, refresh) {
        if (league == SportsLeague.NFL && section == SportsSection.Channels) {
            nflChannels = null
            nflChannels = SportsAddonDiscovery.findNflChannels(activeAddons)
        }
    }
    val vod = section == SportsSection.Replays || section == SportsSection.Highlights
    val catalogOnly = league.scoreboardPath == null
    val catalogSection = vod || section == SportsSection.Events
    var recordings by remember(league, catalogSection, vod, activeAddons) { mutableStateOf<List<SportsCatalogMatch>?>(null) }
    LaunchedEffect(league, catalogSection, vod, activeAddons, events) {
        if (catalogSection) {
            val replaySchedule = if (vod && !isIos && activeAddons.isNotEmpty() && SportsRecordingClassifier.appliesTo(league)) {
                (events + repository.recentResults(league)).distinctBy { it.id }
            } else events
            recordings = SportsAddonDiscovery.find(league, replaySchedule, activeAddons, replays = vod)
        }
    }

    if (currentEvent != null) {
        SportsGameDetail(
            currentEvent, repository, activeAddons, { selectedEvent = null }, onOpenStream,
            multiviewOpen = multiviewOpen,
            onAddToMultiview = { source ->
                val id = source.addonName + ":" + source.item.id
                if (multiviewSources.none { it.addonName + ":" + it.item.id == id } &&
                    multiviewSources.size < multiviewLayout) {
                    multiviewSources.add(source)
                    multiviewState = multiviewState.add(id)
                }
            },
        )
    } else {
        val liveGames = SportsEventSections.live(events)
        val combinedFeed = SportsHubCombinedEvents.combine(combinedBoards, clockNow)
        Box(Modifier.fillMaxSize().background(HubBackground)) {
            HubGlows()
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                HubHeader(title = "Sports Hub", liveCount = liveGames.size, onBack = onBack, showBack = showBack) {
                    ScoreDelayChip(prefs.scoreDelaySeconds)
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { multiviewOpen = !multiviewOpen }) {
                        Text(if (multiviewOpen) "Close Multi-View" else "Open Multi-View", color = HubAccent)
                    }
                    if (multiviewOpen) {
                        TextButton(onClick = { multiviewLayout = 2; multiviewState = multiviewState.withLayout(2); while (multiviewSources.size > 2) multiviewSources.removeAt(multiviewSources.lastIndex) }) {
                            Text("2 Screens", color = if (multiviewLayout == 2) HubAccent else HubMuted)
                        }
                        TextButton(onClick = { multiviewLayout = 4; multiviewState = multiviewState.withLayout(4) }) {
                            Text("4 Screens", color = if (multiviewLayout == 4) HubAccent else HubMuted)
                        }
                    }
                }
                if (multiviewOpen) {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                        Text("Multi-View · ${multiviewSources.size}/$multiviewLayout selected", color = HubAccent)
                        multiviewSources.toList().forEach { source ->
                            val id = source.addonName + ":" + source.item.id
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    (if (multiviewState.shouldPlayAudio(id)) "Selected for audio · " else "Not selected for audio · ") + source.item.name,
                                    color = if (multiviewState.shouldPlayAudio(id)) HubAccent else HubMuted,
                                    modifier = Modifier.weight(1f).clickable { multiviewState = multiviewState.focus(id) },
                                    maxLines = 1,
                                )
                                TextButton(onClick = {
                                    multiviewSources.remove(source)
                                    multiviewState = multiviewState.remove(id)
                                }) { Text("Remove", color = HubAccent) }
                            }
                        }
                        Text("Use Add to Multi-View on a channel below. Playback integration is pending.", color = HubMuted)
                    }
                }
                ScoreTicker(events)
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    itemsIndexed(leagues, key = { _, sport -> sport.name }) { index, sport ->
                        LeagueBadge(sport, selected = league == sport, index = index) {
                            if (league != sport) {
                                section = when {
                                    sport.scoreboardPath == null -> SportsSection.Events
                                    sport != SportsLeague.NFL && section == SportsSection.Channels -> SportsSection.Live
                                    section == SportsSection.Events || section == SportsSection.Standings &&
                                        !sport.usesTeamStats && sport != SportsLeague.UFC ->
                                        SportsSection.Live
                                    else -> section
                                }
                                league = sport
                            }
                        }
                    }
                }
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val sections = if (catalogOnly) {
                        listOf(SportsSection.AllSports, SportsSection.Events, SportsSection.Replays, SportsSection.Highlights)
                    } else {
                        val visible = SportsSection.entries.filter { tab ->
                            tab != SportsSection.Events && (tab != SportsSection.Channels || league == SportsLeague.NFL) &&
                                (tab != SportsSection.Standings || isIos ||
                                league == SportsLeague.UFC || league.usesTeamStats)
                        }
                        if (league == SportsLeague.NFL) {
                            listOf(SportsSection.Channels) + visible.filterNot { it == SportsSection.Channels }
                        } else visible
                    }
                    items(sections) { tab ->
                        SectionPill(tab.label, selected = section == tab) { section = tab }
                    }
                }
                if (error) {
                    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (board == null) "Scoreboard unavailable." else "Showing the last available scores.", color = HubMuted)
                        TextButton(onClick = { refresh++ }) { Text("Retry", color = HubAccent) }
                    }
                }
                val games = when (section) {
                    SportsSection.AllSports -> combinedFeed.live + combinedFeed.upcoming
                    SportsSection.Live -> liveGames
                    SportsSection.MyTeams -> myTeamGames.orEmpty()
                    SportsSection.Upcoming -> SportsEventSections.upcoming(events)
                    SportsSection.Final -> SportsEventSections.final(events)
                    else -> emptyList()
                }
                val clips = recordings.orEmpty().filter {
                    it.kind == when (section) {
                        SportsSection.Events -> SportsContentKind.EVENT
                        SportsSection.Highlights -> SportsContentKind.HIGHLIGHT
                        else -> SportsContentKind.REPLAY
                    }
                }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    when {
                        section == SportsSection.AllSports -> {
                            if (combinedLoading && combinedBoards.isEmpty()) {
                                item { CircularProgressIndicator(color = HubAccent) }
                            } else {
                                if (combinedError) item {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("Some leagues are unavailable. Showing available games.", color = HubMuted, modifier = Modifier.weight(1f))
                                        TextButton(onClick = { refresh++ }) { Text("Retry", color = HubAccent) }
                                    }
                                }
                                item { HubSectionTitle("LIVE NOW") }
                                if (combinedFeed.live.isEmpty()) item { HubMessage("No live games right now.") }
                                itemsIndexed(combinedFeed.live, key = { _, event -> "live_${event.league.name}_${event.id}" }) { index, event ->
                                    EnterAnimated(index) { GameRow(event) { selectedEvent = event } }
                                }
                                item { HubSectionTitle("UPCOMING · NEXT 60 MIN") }
                                if (combinedFeed.upcoming.isEmpty()) item { HubMessage("No games starting in the next hour.") }
                                itemsIndexed(combinedFeed.upcoming, key = { _, event -> "soon_${event.league.name}_${event.id}" }) { index, event ->
                                    EnterAnimated(index) { GameRow(event) { selectedEvent = event } }
                                }
                            }
                        }
                        section == SportsSection.Channels && league == SportsLeague.NFL -> {
                            item {
                                HubMessage("NFL RedZone and NFL Network · Available channels from your enabled add-ons")
                            }
                            when {
                                nflChannels == null -> item { CircularProgressIndicator(color = HubAccent) }
                                nflChannels!!.isEmpty() -> item {
                                    HubMessage("No NFL RedZone or NFL Network channel was found in the enabled add-ons.")
                                }
                                else -> itemsIndexed(nflChannels!!) { index, channel ->
                                    EnterAnimated(index) {
                                        Column {
                                            SourceCard(channel, onOpenStream)
                                            if (multiviewOpen) {
                                                TextButton(onClick = {
                                                    val id = channel.addonName + ":" + channel.item.id
                                                    if (multiviewSources.none { it.addonName + ":" + it.item.id == id } &&
                                                        multiviewSources.size < multiviewLayout) {
                                                        multiviewSources.add(channel)
                                                        multiviewState = multiviewState.add(id)
                                                    }
                                                }) { Text("Add to Multi-View", color = HubAccent) }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        section == SportsSection.Standings -> when {
                            !standingsLoaded -> item { CircularProgressIndicator(color = HubAccent) }
                            standings == null -> item { HubMessage("Standings aren't available for ${league.label}.") }
                            else -> standings!!.groups.forEachIndexed { gi, group ->
                                item(key = "standings_$gi") {
                                    EnterAnimated(gi) { StandingsTable(league, group, prefs) }
                                }
                            }
                        }
                        section == SportsSection.MyTeams && prefs.favoriteTeams.isEmpty() ->
                            item { HubMessage("Tap the star next to a team in any game or in Standings to follow it. Its games will show up here, across every league.") }
                        section == SportsSection.MyTeams && myTeamGames == null ->
                            item { CircularProgressIndicator(color = HubAccent) }
                        section == SportsSection.MyTeams && games.isEmpty() ->
                            item { HubMessage("No games for your teams on the current schedule.") }
                        catalogSection && recordings == null || !catalogSection && board == null && !error ->
                            item { CircularProgressIndicator(color = HubAccent) }
                        catalogSection && clips.isEmpty() ->
                            item { HubMessage("No ${section.label.lowercase()} found in enabled sports add-ons.") }
                        catalogSection -> itemsIndexed(clips) { index, clip ->
                            EnterAnimated(index) { SourceCard(clip, onOpenStream) }
                        }
                        games.isNotEmpty() -> {
                            val featured = games.first().takeIf { section == SportsSection.Live }
                            if (featured != null) {
                                item(key = "featured_${featured.id}") {
                                    EnterAnimated(0) { FeaturedGameCard(featured) { selectedEvent = featured } }
                                }
                                item(key = "today_heading") {
                                    if (games.size > 1) HubSectionTitle("More games")
                                }
                            }
                            val rest = if (featured != null) games.drop(1) else games
                            itemsIndexed(rest, key = { _, event -> event.id }) { index, event ->
                                EnterAnimated(index + 1) { GameRow(event) { selectedEvent = event } }
                            }
                        }
                        !error || board != null -> item { HubMessage("No ${section.label.lowercase()} games for ${league.label}.") }
                    }
                }
            }
        }
    }
    if (showIntro) SportsHubIntro { showIntro = false }
}

/** Two slow-breathing glows (gold and ember) behind the hub. */
@Composable
private fun HubGlows() {
    val left = rememberCinemaBreath(4_000)
    val right = rememberCinemaBreath(4_000, delayMillis = 2_000)
    Box(
        Modifier.fillMaxSize().drawBehind {
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color(0xFFFFB612).copy(alpha = 0.22f * left), Color.Transparent),
                    center = androidx.compose.ui.geometry.Offset(0f, size.height * 0.3f),
                    radius = size.width * 0.75f,
                ),
                radius = size.width * 0.75f,
                center = androidx.compose.ui.geometry.Offset(0f, size.height * 0.3f),
            )
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color(0xFFC83803).copy(alpha = 0.22f * right), Color.Transparent),
                    center = androidx.compose.ui.geometry.Offset(size.width, size.height * 0.34f),
                    radius = size.width * 0.75f,
                ),
                radius = size.width * 0.75f,
                center = androidx.compose.ui.geometry.Offset(size.width, size.height * 0.34f),
            )
        },
    )
}

@Composable
private fun HubHeader(
    title: String,
    liveCount: Int,
    onBack: () -> Unit,
    showBack: Boolean = true,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().padding(start = if (showBack) 4.dp else 16.dp, end = 16.dp, top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showBack) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
        }
        Text(
            title,
            color = Color.White,
            fontSize = 28.sp,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        trailing()
        if (liveCount > 0) {
            Spacer(Modifier.width(8.dp))
            CinemaLivePill("$liveCount LIVE")
        }
    }
}

/** A broadcast-style strip of scores that scrolls on its own. */
@Composable
private fun ScoreTicker(events: List<SportsEvent>) {
    val scored = events.filter { it.hasScores }
        .sortedBy { if (it.status == SportsEventStatus.LIVE) 0 else 1 }
        .take(14)
    if (scored.isEmpty()) return
    Box(
        Modifier
            .padding(top = 12.dp)
            .fillMaxWidth()
            .height(34.dp)
            .background(Color.White.copy(alpha = 0.03f))
            .border(width = 0.5.dp, color = HubCardBorder),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 600, velocity = 38.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width(4.dp))
            scored.forEach { event ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(event.league.shortLabel(), color = HubMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(event.scoreLine().orEmpty(), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(
                        event.displayStatus(),
                        color = if (event.status == SportsEventStatus.LIVE) HubLiveText else HubMuted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun LeagueBadge(league: SportsLeague, selected: Boolean, index: Int, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val label by animateColorAsState(if (selected) CinemaGold.Soft else HubMuted, label = "league_label")
    EnterAnimated(index) {
        Column(
            modifier = Modifier
                .cinemaPressScale(interaction)
                .clickable(interactionSource = interaction, indication = null, role = Role.Tab, onClick = onClick),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Box(
                Modifier
                    .size(58.dp)
                    .cinemaSpinRing(selected)
                    .padding(4.dp)
                    .clip(CircleShape)
                    .background(if (selected) Color(0xFF1F1A10) else Color(0xFF141317)),
                contentAlignment = Alignment.Center,
            ) {
                LeagueLogo(league, Modifier.size(30.dp))
            }
            Text(league.shortLabel(), color = label, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SectionPill(text: String, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val textColor by animateColorAsState(if (selected) CinemaGold.Ink else Color.White, label = "pill_text")
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .cinemaPressScale(interaction)
            .clip(shape)
            .then(
                if (selected) Modifier.background(CinemaGold.brush)
                else Modifier.background(Color.Black.copy(alpha = 0.25f)).border(1.dp, Color.White.copy(alpha = 0.16f), shape),
            )
            .clickable(interactionSource = interaction, indication = null, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Text(text, color = textColor, fontSize = 13.sp, fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold)
    }
}

/** The big live game card: glowing gold-to-ember border, team logos, popping scores. */
@Composable
private fun FeaturedGameCard(event: SportsEvent, showActions: Boolean = true, onOpen: () -> Unit) {
    val away = event.away
    val home = event.home
    val outer = RoundedCornerShape(24.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(outer)
            .background(
                Brush.linearGradient(
                    listOf(Color(0xB3FFB612), Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.06f), Color(0xB3C83803)),
                ),
            )
            .padding(1.dp)
            .clip(RoundedCornerShape(23.dp))
            .background(Color(0xF0100E0C))
            .clickable(enabled = showActions, onClick = onOpen)
            .padding(18.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.clip(CircleShape).background(Color(0xFFD93A2F)).padding(horizontal = 10.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val blink = rememberCinemaBreath(1_000)
                    Box(Modifier.size(6.dp).alpha(blink).clip(CircleShape).background(Color.White))
                    Text("LIVE", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "${event.league.shortLabel()} · ${event.displayStatus()}",
                    color = Color(0xFFCFC9BE),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
            if (away != null && home != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TeamColumn(away, event.league, glow = Color(0xFFFFB612), modifier = Modifier.weight(1f))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PopScore(away.score ?: "–", Color.White)
                        Text("–", color = Color(0xFF5E5A53), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        PopScore(home.score ?: "–", Color(0xFFA39E94))
                    }
                    TeamColumn(home, event.league, glow = Color(0xFFC83803), modifier = Modifier.weight(1f))
                }
            } else {
                Text(event.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            }
            if (showActions) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CinemaGoldButton(
                    text = "Watch live",
                    onClick = onOpen,
                    modifier = Modifier.weight(1f),
                    leading = { Icon(Icons.Rounded.PlayArrow, null, tint = CinemaGold.Ink, modifier = Modifier.size(18.dp)) },
                )
                val interaction = remember { MutableInteractionSource() }
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .cinemaPressScale(interaction)
                        .clip(RoundedCornerShape(50))
                        .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(50))
                        .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onOpen),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (event.league.usesTeamStats || isIos || event.league == SportsLeague.UFC) "Box score" else "Event details", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun TeamColumn(team: SportsCompetitor, league: SportsLeague, glow: Color, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier
                .size(58.dp)
                .shadow(18.dp, CircleShape, ambientColor = glow, spotColor = glow)
                .clip(CircleShape)
                .background(Color(0xFF17161A))
                .border(2.dp, glow, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            val logo = team.logoUrl?.takeIf { league.isCollegeBasketball || league == SportsLeague.WNBA }
                ?: teamLogoUrl(league, team.abbreviation)
            val fallback: @Composable () -> Unit = {
                Text(team.shortLabel.take(4), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
            }
            if (logo == null) fallback() else SubcomposeAsyncImage(
                model = logo,
                contentDescription = team.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(38.dp),
                loading = { fallback() },
                error = { fallback() },
            )
        }
        Text(team.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        team.record?.let { Text(it, color = HubMuted, fontSize = 11.sp) }
    }
}

/** A score that springs up and glows gold whenever it changes. */
@Composable
private fun PopScore(score: String, color: Color) {
    AnimatedContent(
        targetState = score,
        transitionSpec = {
            (slideInVertically(tween(450, easing = FastOutSlowInEasing)) { it } + fadeIn() + scaleIn(initialScale = 1.5f)) togetherWith
                (slideOutVertically(tween(350)) { -it } + fadeOut())
        },
        label = "pop_score",
    ) { value ->
        Text(value, color = color, fontSize = 46.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun GameRow(event: SportsEvent, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(16.dp)
    val statusColor = when (event.status) {
        SportsEventStatus.LIVE -> HubLiveText
        SportsEventStatus.FINAL -> HubMuted
        else -> CinemaGold.Soft
    }
    Row(
        Modifier
            .fillMaxWidth()
            .cinemaPressScale(interaction, pressedScale = 0.97f)
            .clip(shape)
            .background(HubCard)
            .border(1.dp, HubCardBorder, shape)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val away = event.away
        val home = event.home
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (away != null && home != null) {
                TeamLine(away, event.status == SportsEventStatus.FINAL && away.isWinner, SportsPreferences.isFavorite(event.league, away))
                TeamLine(home, event.status == SportsEventStatus.FINAL && home.isWinner, SportsPreferences.isFavorite(event.league, home))
            } else {
                Text(event.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 2)
            }
        }
        Box(Modifier.width(1.dp).height(36.dp).background(Color.White.copy(alpha = 0.08f)))
        Row(Modifier.width(84.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            if (event.status == SportsEventStatus.LIVE) {
                CinemaPulseDot(size = 6.dp)
                Spacer(Modifier.width(6.dp))
            }
            Text(event.displayStatus(), color = statusColor, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2)
        }
    }
}

@Composable
private fun TeamLine(team: SportsCompetitor, winner: Boolean, favorite: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (favorite) {
            Text("★", color = CinemaGold.Base, fontSize = 13.sp, modifier = Modifier.padding(end = 6.dp))
        }
        Text(
            team.name,
            color = if (favorite) CinemaGold.Soft else Color.White,
            fontSize = 14.sp,
            fontWeight = if (winner) FontWeight.ExtraBold else FontWeight.Bold,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        AnimatedContent(
            targetState = team.score.orEmpty(),
            transitionSpec = { (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut()) },
            label = "row_score",
        ) { value ->
            Text(value, color = if (winner) CinemaGold.Soft else Color.White, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
        }
    }
}

@Composable
private fun HubSectionTitle(text: String) {
    Text(text, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 14.dp, bottom = 2.dp))
}

/** Fades and lifts content in, one item after another. */
@Composable
private fun EnterAnimated(index: Int, content: @Composable () -> Unit) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(550, delayMillis = (index.coerceAtMost(8)) * 60, easing = FastOutSlowInEasing))
    }
    Box(
        Modifier.graphicsLayer {
            alpha = progress.value
            translationY = (1f - progress.value) * 22.dp.toPx()
        },
    ) { content() }
}

private fun SportsLeague.shortLabel(): String = shortLabel

private fun teamLogoUrl(league: SportsLeague, abbreviation: String?): String? {
    val abbr = abbreviation?.takeIf { it.isNotBlank() }?.lowercase() ?: return null
    val slug = when (league) {
        SportsLeague.NFL -> "nfl"
        SportsLeague.NBA -> "nba"
        SportsLeague.MLB -> "mlb"
        SportsLeague.NHL -> "nhl"
        else -> return null
    }
    return "https://a.espncdn.com/i/teamlogos/$slug/500/$abbr.png"
}

@Composable
private fun SportsGameDetail(
    event: SportsEvent,
    repository: SportsEventRepository,
    addons: List<ManagedAddon>,
    onBack: () -> Unit,
    onOpenStream: (MetaPreview) -> Unit,
    multiviewOpen: Boolean,
    onAddToMultiview: (SportsCatalogMatch) -> Unit,
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
            // Finished games: full replays before highlights.
            .sortedBy { if (event.status == SportsEventStatus.FINAL && it.kind != SportsContentKind.REPLAY) 1 else 0 }
    }
    LaunchedEffect(event.id, event.status, owner) {
        if (!event.league.usesTeamStats) return@LaunchedEffect
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
        HubHeader(title = event.league.shortLabel(), liveCount = if (event.status == SportsEventStatus.LIVE) 1 else 0, onBack = onBack)
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                if (event.status == SportsEventStatus.LIVE && event.away != null && event.home != null) {
                    FeaturedGameCard(event, showActions = false) {}
                    Spacer(Modifier.height(12.dp))
                }
                Text(event.title, style = MaterialTheme.typography.headlineSmall, color = Color.White, fontWeight = FontWeight.ExtraBold)
                Text(event.displayStatus(), color = HubAccent)
                listOfNotNull(event.away, event.home).ifEmpty { event.competitors }.forEach { side ->
                    HubMessage(listOfNotNull(side.name, side.score, side.record).joinToString("  •  "))
                }
                event.venue?.let { HubMessage(it) }
                if (event.broadcasts.isNotEmpty()) HubMessage("Watch on ${event.broadcasts.joinToString()}")
                Spacer(Modifier.height(10.dp))
                GameDetailActions(event)
            }
            item {
                HubHeading(when {
                    event.status == SportsEventStatus.FINAL -> "Replays and highlights"
                    event.league.androidOnly && !event.league.usesTeamStats -> "Watch event"
                    else -> "Watch game"
                })
            }
            when {
                sources == null -> item { HubMessage("Checking enabled sports add-ons…") }
                sources.orEmpty().isEmpty() -> item { HubMessage("No matching stream found in enabled sports add-ons.") }
                else -> items(sources.orEmpty()) { source ->
                    Column {
                        SourceCard(source, onOpenStream)
                        if (multiviewOpen) {
                            TextButton(onClick = { onAddToMultiview(source) }) {
                                Text("Add to Multi-View", color = HubAccent)
                            }
                        }
                    }
                }
            }
            if (event.league.usesTeamStats || event.league == SportsLeague.UFC) {
                item { HubHeading("Player stats") }
                val teams = boxScore?.teams.orEmpty()
                when {
                    teams.isNotEmpty() -> {
                        item {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(teams.size) { index ->
                                    SectionPill(teams[index].shortLabel, selected = index == teamIndex) { teamIndex = index }
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
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .cinemaPressScale(interaction, pressedScale = 0.97f)
            .clip(shape)
            .background(HubCard)
            .border(1.dp, HubCardBorder, shape)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button) { onOpenStream(source.item) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(CinemaGold.brush), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = CinemaGold.Ink, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(source.item.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(source.addonName, color = HubMuted, fontSize = 12.sp, maxLines = 1)
        }
    }
}

@Composable
private fun HubHeading(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = HubAccent, fontWeight = FontWeight.ExtraBold)
}

@Composable
private fun HubMessage(text: String) {
    Text(text, color = Color.White, style = MaterialTheme.typography.bodyMedium)
}

@Composable
internal fun SportsHubHomeButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .cinemaPressScale(interaction)
            .clip(shape)
            .background(CinemaGold.Base.copy(alpha = 0.14f))
            .border(1.dp, CinemaGold.Base.copy(alpha = 0.6f), shape)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CinemaPulseDot()
        Text("Live sports", color = CinemaGold.Soft, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
        Text("· Sports Hub", color = Color(0xFFCFC9BE), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun LeagueLogo(league: SportsLeague, modifier: Modifier) {
    val isTennisTour = league == SportsLeague.ATP || league == SportsLeague.WTA
    val logoUrl = when (league) {
        SportsLeague.ATP -> "https://thumb.wikimedia.org/wikipedia/commons/thumb/3/3f/ATP_Tour_logo.svg/330px-ATP_Tour_logo.svg.png"
        SportsLeague.WTA -> "https://www.wtatennis.com/resources/v7.55.15/i/elements/wta-publisher-logo.png"
        else -> league.logoPath?.let { "https://a.espncdn.com/$it" }
    }
    if (logoUrl == null) {
        SportIcon(league.glyph(), modifier = modifier, color = HubAccent, size = 22.dp)
        return
    }
    SubcomposeAsyncImage(
        model = logoUrl,
        contentDescription = "${league.label} logo",
        contentScale = ContentScale.Fit,
        modifier = if (isTennisTour) {
            modifier.background(Color(0xFFF5EFE9), CircleShape).padding(2.dp)
        } else modifier,
        loading = { SportIcon(league.glyph(), color = HubAccent, size = 22.dp) },
        error = { SportIcon(league.glyph(), color = HubAccent, size = 22.dp) },
    )
}

private fun SportsEvent.displayStatus(): String =
    statusDetail?.takeIf { it.isNotBlank() }
        ?: startTimeEpochMillis?.takeIf { status == SportsEventStatus.SCHEDULED }
            ?.let(EpisodeReleaseDatePlatform::localIsoDateAtEpochMs)
        ?: status.name.lowercase().replaceFirstChar(Char::uppercaseChar)

@Composable
internal expect fun SportsHubIntro(onFinished: () -> Unit)

/** Tap to step the score delay: Live → 30s → 1 min → 2 min → 5 min. */
@Composable
private fun ScoreDelayChip(delaySeconds: Int) {
    val interaction = remember { MutableInteractionSource() }
    val on = delaySeconds > 0
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .cinemaPressScale(interaction)
            .clip(shape)
            .background(if (on) CinemaGold.Base.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.06f))
            .border(1.dp, if (on) CinemaGold.Base.copy(alpha = 0.6f) else HubCardBorder, shape)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button) {
                val options = SportsPreferences.delayOptions
                val next = options[(options.indexOf(delaySeconds).coerceAtLeast(0) + 1) % options.size]
                SportsPreferences.setScoreDelay(next)
            }
            .padding(horizontal = 12.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("⏱", fontSize = 12.sp)
        Text(delayLabel(delaySeconds), color = if (on) CinemaGold.Soft else Color(0xFFCFC9BE), fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

/** Follow either team, and get a reminder before an upcoming game. */
@Composable
private fun GameDetailActions(event: SportsEvent) {
    val prefs by SportsPreferences.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var reminderNote by remember(event.id) { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOfNotNull(event.away, event.home).forEach { team ->
                val fav = SportsPreferences.teamKey(event.league, team) in prefs.favoriteTeams
                ActionPill(
                    text = (if (fav) "★ " else "☆ ") + team.shortLabel,
                    selected = fav,
                ) { SportsPreferences.toggleFavorite(event.league, team) }
            }
        }
        val start = event.startTimeEpochMillis
        if (event.status == SportsEventStatus.SCHEDULED && start != null && start > nowMillis()) {
            val on = event.id in prefs.reminders
            ActionPill(text = if (on) "🔔 Reminder set · tap to cancel" else "🔔 Remind me 10 min before", selected = on) {
                scope.launch {
                    val scheduled = SportsReminders.toggle(event, nowMillis())
                    reminderNote = when {
                        scheduled -> "We'll remind you about 10 minutes before the start."
                        event.id in SportsPreferences.state.value.reminders -> null
                        on -> "Reminder cancelled."
                        else -> "Allow notifications for Kable Killers to get game reminders."
                    }
                }
            }
            reminderNote?.let { Text(it, color = HubMuted, fontSize = 12.sp) }
        }
    }
}

@Composable
private fun ActionPill(text: String, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .cinemaPressScale(interaction)
            .clip(shape)
            .then(
                if (selected) Modifier.background(CinemaGold.Base.copy(alpha = 0.16f)).border(1.dp, CinemaGold.Base.copy(alpha = 0.7f), shape)
                else Modifier.background(Color.White.copy(alpha = 0.05f)).border(1.dp, HubCardBorder, shape),
            )
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text(text, color = if (selected) CinemaGold.Soft else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

/** One conference or division table. Tap a team's star to follow it. */
@Composable
private fun StandingsTable(league: SportsLeague, group: SportsStandingsGroup, prefs: SportsPrefs) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(HubCard).border(1.dp, HubCardBorder, shape).padding(vertical = 10.dp),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(group.name.uppercase(), color = CinemaGold.Base, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.2.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            group.columns.forEach { col ->
                Text(col, color = HubMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(42.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
        }
        group.entries.forEachIndexed { index, entry ->
            val key = "${league.name}:${(entry.abbreviation ?: entry.teamName).uppercase()}"
            val fav = key in prefs.favoriteTeams
            Row(
                Modifier.fillMaxWidth()
                    .background(if (fav) CinemaGold.Base.copy(alpha = 0.08f) else Color.Transparent)
                    .clickable { SportsPreferences.toggleFavoriteKey(key) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${index + 1}", color = HubMuted, fontSize = 12.sp, modifier = Modifier.width(22.dp))
                Text(if (fav) "★" else "☆", color = if (fav) CinemaGold.Base else HubMuted, fontSize = 13.sp, modifier = Modifier.width(20.dp))
                Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                    entry.logoUrl?.let { url ->
                        SubcomposeAsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(20.dp))
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(entry.abbreviation ?: entry.teamName, color = if (fav) CinemaGold.Soft else Color.White, fontSize = 14.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                entry.values.forEach { value ->
                    Text(value, color = Color.White, fontSize = 13.sp, modifier = Modifier.width(42.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.End, maxLines = 1)
                }
            }
        }
    }
}
