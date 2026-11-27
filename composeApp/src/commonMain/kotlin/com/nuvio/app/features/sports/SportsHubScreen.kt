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
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
import com.nuvio.app.features.streams.LinkHealth
import com.nuvio.app.features.streams.LiveLinkChecker
import com.nuvio.app.features.player.FullscreenPlayerDialog
import com.nuvio.app.features.player.LockPlayerToLandscape
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

private val HubAccent = CinemaGold.Base
private val HubBackground = Color(0xFF08080A)
private val HubMuted = Color(0xFF9E998F)
private val HubCard = Color.White.copy(alpha = 0.045f)
private val HubCardBorder = Color.White.copy(alpha = 0.07f)
private val HubLiveText = Color(0xFFFF7B86)

private enum class SportsSection(val label: String) {
    AllSports("All Sports"), LiveEvents("Live Events"), UsChannels("US Channels"), Live("Live Now"), MyTeams("My Teams"), Upcoming("Upcoming"), Final("Final"),
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
    // US sports only; "All" holds All Sports, Live Events, US Channels and My Teams.
    val leagues = HubSports.scoreboardLeagues
    var hubKey by rememberSaveable { mutableStateOf(HubSports.All.key) }
    val hub = HubSports.byKey(hubKey)
    val league = hub.primary
    var section by rememberSaveable { mutableStateOf(SportsSection.AllSports) }
    var showIntro by rememberSaveable { mutableStateOf(true) }
    // Multi-View: "Add to Multi-View" on a stream opens a link picker; picking a
    // link adds a screen and shows the full-screen grid.
    var multiviewHelp by remember { mutableStateOf(false) }
    var multiviewPending by remember { mutableStateOf<SportsCatalogMatch?>(null) }
    var multiviewChoices by remember { mutableStateOf<List<com.nuvio.app.features.streams.StreamItem>>(emptyList()) }
    var multiviewSearching by remember { mutableStateOf(false) }
    // Saveable, so Back from a stream re-opens the same game with the streams
    // that were already found (no add-on re-search).
    var selectedEventKey by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedEvent by remember { mutableStateOf(SportsHubLastGame.restore(selectedEventKey)) }
    LaunchedEffect(selectedEvent) {
        selectedEventKey = SportsHubLastGame.save(selectedEvent)
    }
    var board by remember(hubKey) { mutableStateOf<SportsScoreboard?>(null) }
    var error by remember(hubKey) { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(0) }
    val owner = LocalLifecycleOwner.current
    val addons by AddonRepository.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { AddonRepository.initialize() }
    LaunchedEffect(hubKey, refresh, owner) {
        val members = hub.scoreboardMembers
        if (members.isEmpty()) return@LaunchedEffect
        // One board for the whole choice: College BB = men's + women's, Golf = PGA + LPGA…
        fun merged(boards: List<SportsScoreboard>) = if (boards.isEmpty()) null else SportsScoreboard(
            league = hub.primary,
            events = boards.flatMap { it.events },
            fetchedAtEpochMillis = boards.maxOf { it.fetchedAtEpochMillis },
        )
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            board = merged(members.mapNotNull { repository.cached(it) })
            var force = refresh > 0
            while (true) {
                // All leagues of the choice at once instead of one after another.
                val results = coroutineScope {
                    members.map { member ->
                        async {
                            try {
                                repository.scoreboard(member, forceRefresh = force) to false
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                repository.cached(member) to true
                            }
                        }
                    }.awaitAll()
                }
                val boards = results.mapNotNull { it.first }
                val failed = results.any { it.second }
                merged(boards)?.let { board = it }
                error = failed && boards.isEmpty()
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
                // Every league in parallel: the All Sports feed used to wait for ~20
                // scoreboards one after another.
                val results = coroutineScope {
                    leagues.filter { it.scoreboardPath != null }.map { sport ->
                        async {
                            try {
                                repository.scoreboard(sport, forceRefresh = force) to false
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                repository.cached(sport) to true
                            }
                        }
                    }.awaitAll()
                }
                val fetched = results.mapNotNull { it.first }
                val failures = results.count { it.second }
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
    val history = remember(hubKey) { mutableStateListOf<SportsScoreboard>() }
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
    // A game alert was tapped: open that game.
    val openRequest by SportsHubOpenRequest.pending.collectAsStateWithLifecycle()
    LaunchedEffect(openRequest) {
        val request = openRequest ?: return@LaunchedEffect
        SportsHubOpenRequest.consume(request)
        val target = SportsLeague.entries.firstOrNull { it.name == request.league } ?: return@LaunchedEffect
        HubSports.list.firstOrNull { target in it.members }?.let { hubKey = it.key }
        section = SportsSection.AllSports
        val game = runCatching { repository.scoreboard(target).events }.getOrDefault(emptyList())
            .firstOrNull { it.id == request.eventId }
        if (game != null) selectedEvent = game
    }
    val currentEvent = selectedEvent?.let { selected ->
        (shownBoard?.events.orEmpty() + combinedBoards.flatMap { it.events }).firstOrNull { it.id == selected.id && it.league == selected.league } ?: selected
    }
    val activeAddons = remember(addons.addons) {
        addons.addons.filter { it.isActive && !SportsAddonPriority.excludedFromHub(it) }.map { it.copy(isRefreshing = false, errorMessage = null) }
    }
    LaunchedEffect(multiviewPending) {
        val source = multiviewPending ?: return@LaunchedEffect
        multiviewChoices = emptyList()
        multiviewSearching = true
        try {
            multiviewChoices = runCatching {
                SportsMultiviewStreamResolver.directStreams(source, activeAddons)
            }.getOrElse { if (it is CancellationException) throw it; emptyList() }
                .sortedBy { SportsAddonPriority.rank(it.addonName, it.addonId) }
            LiveLinkChecker.check(multiviewChoices)
        } finally {
            multiviewSearching = false
        }
    }
    val addToMultiview: (SportsCatalogMatch) -> Unit = { source -> multiviewPending = source }
    val events = shownBoard?.events.orEmpty()
    // Start looking for links to the games on now (and about to start) in the background, so
    // opening one usually finds its links already there. Two at a time, after the board settles.
    LaunchedEffect(events.map { it.id to it.status }, activeAddons) {
        if (activeAddons.isEmpty() || events.isEmpty()) return@LaunchedEffect
        delay(1_500)
        val soon = SportsEventSections.live(events) + SportsEventSections.upcoming(events).take(2)
        soon.distinctBy { it.id }.take(6).chunked(2).forEach { pair ->
            coroutineScope {
                pair.forEach { game ->
                    launch {
                        runCatching { SportsAddonDiscovery.find(game.league, listOf(game), activeAddons, replays = false) }
                            .onFailure { if (it is CancellationException) throw it }
                    }
                }
            }
        }
    }
    // My Teams: games across every league that has a favorite team.
    var myTeamGames by remember { mutableStateOf<List<SportsEvent>?>(null) }
    var myTeamsKey by remember { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(section, prefs.favoriteTeams, refresh) {
        if (section != SportsSection.MyTeams) return@LaunchedEffect
        // Spinner only when the teams changed; a score refresh keeps the games on screen.
        if (myTeamsKey != prefs.favoriteTeams) myTeamGames = null
        myTeamsKey = prefs.favoriteTeams
        // All leagues at once, and a slow one can't hold the page (12 seconds each at most).
        val found = coroutineScope {
            SportsPreferences.favoriteLeagues(prefs).filter { it.scoreboardPath != null }.map { lg ->
                async {
                    // Any failure (including a network call that reports itself as "cancelled")
                    // just means no games from that league; only a real page close stops this.
                    try {
                        withTimeoutOrNull(12_000) { repository.scoreboard(lg).events }.orEmpty()
                    } catch (e: Exception) {
                        currentCoroutineContext().ensureActive()
                        repository.cached(lg)?.events.orEmpty()
                    }
                }
            }.awaitAll().flatten()
        }.filter { SportsPreferences.involvesFavorite(it, prefs) }
        myTeamGames = SportsEventSections.live(found) + SportsEventSections.upcoming(found) + SportsEventSections.final(found)
    }
    // My Teams straight from the board on screen (All has every league) while its own load runs.
    val myTeamsShown: List<SportsEvent>? = myTeamGames ?: shownBoard?.events
        ?.filter { SportsPreferences.involvesFavorite(it, prefs) }
        ?.takeIf { it.isNotEmpty() }
        ?.let { SportsEventSections.live(it) + SportsEventSections.upcoming(it) + SportsEventSections.final(it) }
    // Standings for the selected league.
    var standings by remember(hubKey) { mutableStateOf<SportsStandings?>(null) }
    var standingsLoaded by remember(hubKey) { mutableStateOf(false) }
    LaunchedEffect(section, league) {
        if (section != SportsSection.Standings || standingsLoaded) return@LaunchedEffect
        standings = runCatching { repository.standings(league) }.getOrNull()
        standingsLoaded = true
    }
    var nflChannels by remember { mutableStateOf<List<SportsCatalogMatch>?>(null) }
    LaunchedEffect(section, league, activeAddons, refresh) {
        if (league == SportsLeague.NFL && section == SportsSection.Channels) {
            nflChannels = SportsAddonDiscovery.peekNflChannels(activeAddons)?.let(::rememberedFirst)
            nflChannels = rememberedFirst(SportsAddonDiscovery.findNflChannels(activeAddons))
        }
    }
    // Search: teams ("packers", "brewers"), channels ("espn2"), events ("ufc").
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val searchActive = searchQuery.trim().length >= 2
    var searchBoards by remember { mutableStateOf<List<SportsScoreboard>>(emptyList()) }
    var searchAddonResults by remember { mutableStateOf<List<SportsCatalogMatch>?>(null) }
    var searchChannels by remember { mutableStateOf<List<SportsTvChannel>?>(null) }
    var searchEvents by remember { mutableStateOf<List<SportsLiveEvent>?>(null) }
    LaunchedEffect(searchActive, activeAddons) {
        if (!searchActive) return@LaunchedEffect
        // Today's games for every league (cached ones first, then fresh).
        val boards = mutableListOf<SportsScoreboard>()
        for (sport in leagues.filter { it.scoreboardPath != null }) {
            repository.cached(sport)?.let { boards += it }
        }
        searchBoards = boards.toList()
        val missing = leagues.filter { it.scoreboardPath != null && boards.none { b -> b.league == it } }
        val fresh = coroutineScope {
            missing.map { sport -> async { runCatching { repository.scoreboard(sport) }.getOrNull() } }.awaitAll()
        }
        searchBoards = boards + fresh.filterNotNull()
    }
    LaunchedEffect(searchActive, activeAddons) {
        if (!searchActive) return@LaunchedEffect
        searchChannels = SportsLiveTv.peekChannels(activeAddons) ?: SportsLiveTv.usChannels(activeAddons)
    }
    LaunchedEffect(searchActive, activeAddons) {
        if (!searchActive) return@LaunchedEffect
        searchEvents = SportsLiveTv.peekEvents(activeAddons) ?: SportsLiveTv.liveEvents(activeAddons)
    }
    LaunchedEffect(searchQuery, activeAddons) {
        searchAddonResults = null
        val query = searchQuery.trim()
        if (query.length < 2) return@LaunchedEffect
        delay(450) // wait for typing to pause
        searchAddonResults = SportsHubSearch.addons(query, activeAddons)
    }

    // Every add-on's live events and US sports channels (same for any league tab).
    var liveEvents by remember { mutableStateOf<List<SportsLiveEvent>?>(null) }
    var usChannels by remember { mutableStateOf<List<SportsTvChannel>?>(null) }
    LaunchedEffect(section, activeAddons, refresh) {
        when (section) {
            SportsSection.LiveEvents -> {
                liveEvents = SportsLiveTv.peekEvents(activeAddons)
                liveEvents = SportsLiveTv.liveEvents(activeAddons)
            }
            SportsSection.UsChannels -> {
                usChannels = SportsLiveTv.peekChannels(activeAddons)
                usChannels = SportsLiveTv.usChannels(activeAddons)
            }
            else -> Unit
        }
    }
    val vod = section == SportsSection.Replays || section == SportsSection.Highlights
    val catalogOnly = league.scoreboardPath == null
    val catalogSection = vod || section == SportsSection.Events
    var recordings by remember(league, catalogSection, vod, activeAddons) { mutableStateOf<List<SportsCatalogMatch>?>(null) }
    LaunchedEffect(hubKey, catalogSection, vod, activeAddons, events) {
        if (catalogSection && !hub.isAll) {
            // Replays/Highlights: every league in the choice. Events: the ones with no
            // ESPN scoreboard (boxing, wrestling, world soccer) from add-on event lists.
            val targets = if (vod) hub.members else hub.catalogMembers
            val found = coroutineScope {
                targets.map { member ->
                    async {
                        val memberEvents = events.filter { it.league == member }
                        val replaySchedule = if (vod && !isIos && activeAddons.isNotEmpty() && SportsRecordingClassifier.appliesTo(member)) {
                            (memberEvents + repository.recentResults(member)).distinctBy { it.id }
                        } else memberEvents
                        SportsAddonDiscovery.find(member, replaySchedule, activeAddons, replays = vod)
                    }
                }.awaitAll().flatten()
            }
            recordings = SportsAddonPriority.sorted(found.distinctBy { Triple(it.addonName, it.item.type, it.item.id) }) { it.addonName }
        }
    }

    if (currentEvent != null) {
        SportsGameDetail(
            currentEvent, repository, activeAddons, { selectedEvent = null }, onOpenStream,
            onAddToMultiview = addToMultiview,
        )
    } else {
        val liveGames = SportsEventSections.live(events)
        val combinedFeed = SportsHubCombinedEvents.combine(combinedBoards, clockNow)
        Box(Modifier.fillMaxSize().background(HubBackground)) {
            // Nothing behind the Multi-View grid should animate; it takes GPU time from the video.
            if (!SportsMultiviewSession.open) HubGlows()
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                HubHeader(title = "Sports Hub", liveCount = liveGames.size, onBack = onBack, showBack = showBack) {
                    ScoreDelayChip(prefs.scoreDelaySeconds)
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val screens = SportsMultiviewSession.tiles.size
                    TextButton(onClick = {
                        if (screens > 0 && !isIos) SportsMultiviewSession.open = true else multiviewHelp = true
                    }) {
                        Text("▦  Multi-View ($screens/${SportsMultiviewSession.MAX_TILES})", color = HubAccent)
                    }
                }
                SportsSearchField(searchQuery, onChange = { searchQuery = it })
                if (searchActive) {
                    SportsSearchResults(
                        query = searchQuery.trim(),
                        games = SportsHubSearch.games(searchQuery.trim(), searchBoards.flatMap { it.events } + combinedBoards.flatMap { it.events } + events),
                        channels = searchChannels?.let { SportsHubSearch.channels(searchQuery.trim(), it) },
                        liveEvents = searchEvents?.let { SportsHubSearch.events(searchQuery.trim(), it) },
                        addonResults = searchAddonResults,
                        onOpenGame = { selectedEvent = it },
                        onOpenStream = onOpenStream,
                        onAddToMultiview = addToMultiview,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                ScoreTicker(if (hub.isAll) combinedBoards.flatMap { it.events } else events, animate = !SportsMultiviewSession.open)
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    itemsIndexed(HubSports.list, key = { _, sport -> sport.key }) { index, sport ->
                        LeagueBadge(sport.logo, sport.label, selected = hub.key == sport.key, index = index) {
                            if (hub.key != sport.key) {
                                section = defaultSection(sport)
                                hubKey = sport.key
                            }
                        }
                    }
                }
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val sections = hubSections(hub)
                    items(sections) { tab ->
                        SectionPill(tab.label, selected = section == tab) { section = tab }
                    }
                    // A saved tab that this sport doesn't have falls back to its first one.
                    if (section !in sections) item {
                        LaunchedEffect(hub.key) { section = defaultSection(hub) }
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
                    SportsSection.MyTeams -> myTeamsShown.orEmpty()
                    SportsSection.Upcoming -> SportsEventSections.upcoming(events)
                    SportsSection.Final -> SportsEventSections.final(events)
                    else -> emptyList()
                }
                val clips = rememberedFirst(recordings.orEmpty()).filter {
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
                    if (section == SportsSection.MyTeams && prefs.favoriteTeams.isNotEmpty()) {
                        item(key = "game_alerts") { GameAlertsToggle(prefs.gameAlerts) }
                    }
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
                                    EnterAnimated(index) { GameRow(event, showLeague = hub.isAll || hub.mixesLeagues) { selectedEvent = event } }
                                }
                                item { HubSectionTitle("UPCOMING · NEXT 60 MIN") }
                                if (combinedFeed.upcoming.isEmpty()) item { HubMessage("No games starting in the next hour.") }
                                itemsIndexed(combinedFeed.upcoming, key = { _, event -> "soon_${event.league.name}_${event.id}" }) { index, event ->
                                    EnterAnimated(index) { GameRow(event, showLeague = hub.isAll || hub.mixesLeagues) { selectedEvent = event } }
                                }
                            }
                        }
                        section == SportsSection.LiveEvents -> {
                            item { HubMessage("Live events from all your add-ons · the same event from several add-ons is grouped") }
                            val list = liveEvents
                            when {
                                list == null -> item { CircularProgressIndicator(color = HubAccent) }
                                list.isEmpty() -> item { HubMessage("No live events found right now in your enabled add-ons.") }
                                else -> list.groupBy { it.sport }.forEach { (sport, inSport) ->
                                    item(key = "le_h_$sport") { HubHeading("$sport · ${inSport.size}") }
                                    itemsIndexed(inSport, key = { i, _ -> "le_${sport}_$i" }) { i, event ->
                                        EnterAnimated(i) { GroupedSources(event.title, event.sources, onOpenStream, addToMultiview) }
                                    }
                                }
                            }
                        }
                        section == SportsSection.UsChannels -> {
                            item { HubMessage("US sports channels (ESPN, ESPN2, FS1, NFL Network…) from all your add-ons · foreign feeds left out") }
                            val list = usChannels
                            when {
                                list == null -> item { CircularProgressIndicator(color = HubAccent) }
                                list.isEmpty() -> item { HubMessage("No US sports channels found in your enabled add-ons.") }
                                else -> itemsIndexed(list, key = { _, channel -> "us_${channel.name}" }) { i, channel ->
                                    EnterAnimated(i) { GroupedSources(channel.name, channel.sources, onOpenStream, addToMultiview) }
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
                                            AddToMultiviewButton { addToMultiview(channel) }
                                        }
                                    }
                                }
                            }
                        }
                        section == SportsSection.Standings -> when {
                            !standingsLoaded -> item { CircularProgressIndicator(color = HubAccent) }
                            standings == null -> item { HubMessage("Standings aren't available for ${hub.label}.") }
                            else -> standings!!.groups.forEachIndexed { gi, group ->
                                item(key = "standings_$gi") {
                                    EnterAnimated(gi) { StandingsTable(league, group, prefs) }
                                }
                            }
                        }
                        section == SportsSection.MyTeams && prefs.favoriteTeams.isEmpty() ->
                            item { HubMessage("Tap the star next to a team in any game or in Standings to follow it. Its games will show up here, across every league.") }
                        section == SportsSection.MyTeams && myTeamsShown == null ->
                            item { CircularProgressIndicator(color = HubAccent) }
                        section == SportsSection.MyTeams && games.isEmpty() ->
                            item { HubMessage("No games for your teams on the current schedule.") }
                        catalogSection && recordings == null || !catalogSection && board == null && !error ->
                            item { CircularProgressIndicator(color = HubAccent) }
                        catalogSection && clips.isEmpty() ->
                            item { HubMessage("No ${section.label.lowercase()} found in enabled sports add-ons.") }
                        catalogSection -> itemsIndexed(clips) { index, clip ->
                            EnterAnimated(index) {
                                Column {
                                    SourceCard(clip, onOpenStream)
                                    // Live events (fights, races…) can go in Multi-View too.
                                    if (section == SportsSection.Events) AddToMultiviewButton { addToMultiview(clip) }
                                }
                            }
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
                                EnterAnimated(index + 1) { GameRow(event, showLeague = hub.isAll || hub.mixesLeagues) { selectedEvent = event } }
                            }
                        }
                        !error || board != null -> item { HubMessage("No ${section.label.lowercase()} games for ${hub.label}.") }
                    }
                }
            }
                }
        }
    }
    if (multiviewHelp) {
        Dialog(onDismissRequest = { multiviewHelp = false }) {
            Column(
                Modifier.clip(RoundedCornerShape(18.dp)).background(Color(0xFF15151A)).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Multi-View", color = HubAccent, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                Text(
                    if (isIos) "Multi-View is available on Android."
                    else "Watch up to four streams at once.\n\n1. Open a game (or NFL Channels).\n" +
                        "2. Tap \"Add to Multi-View\" under a stream and pick a link.\n" +
                        "3. Repeat for up to four screens. Tap a screen to hear it.",
                    color = Color.White,
                )
                TextButton(onClick = { multiviewHelp = false }, modifier = Modifier.align(Alignment.End)) {
                    Text("Got it", color = HubAccent)
                }
            }
        }
    }
    multiviewPending?.let { source ->
        Dialog(onDismissRequest = { multiviewPending = null }) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 520.dp)
                    .clip(RoundedCornerShape(18.dp)).background(Color(0xFF15151A)).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Add to Multi-View", color = HubAccent, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                Text(source.item.name, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                when {
                    isIos -> Text("Multi-View is available on Android.", color = HubMuted)
                    multiviewSearching -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = HubAccent, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Finding playable links in your add-ons…", color = HubMuted)
                    }
                    multiviewChoices.isEmpty() -> Text(
                        "No link here can play inside Multi-View (it needs the normal player). Try another stream.",
                        color = HubMuted,
                    )
                    else -> {
                        // Links tested in the background; clearly dead ones drop out (unless all are).
                        val dead = multiviewChoices.count { LiveLinkChecker.health(it) == LinkHealth.Dead }
                        val working = if (dead in 1 until multiviewChoices.size) {
                            multiviewChoices.filter { LiveLinkChecker.health(it) != LinkHealth.Dead }
                        } else multiviewChoices
                        val hidden = multiviewChoices.size - working.size
                        Text(
                            "Pick a link · ${working.size} found" + if (hidden > 0) " · $hidden not working (hidden)" else "",
                            color = HubMuted,
                        )
                        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                            working.forEach { stream ->
                                val tile = SportsMultiviewSession.tileFor(source.item.name, stream) ?: return@forEach
                                val added = SportsMultiviewSession.tiles.any { it.id == tile.id }
                                Column(
                                    Modifier.fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable {
                                            SportsMultiviewSession.add(tile)
                                            multiviewPending = null
                                        }
                                        .padding(vertical = 8.dp, horizontal = 6.dp),
                                ) {
                                    Text(
                                        (if (added) "✓ " else "") + stream.streamLabel,
                                        color = if (added) HubAccent else Color.White,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(stream.addonName, color = HubMuted, fontSize = 12.sp, maxLines = 1)
                                }
                            }
                        }
                        if (SportsMultiviewSession.tiles.size >= SportsMultiviewSession.MAX_TILES) {
                            Text("All four screens are in use; the oldest one will be replaced.", color = HubMuted, fontSize = 12.sp)
                        }
                    }
                }
                TextButton(onClick = { multiviewPending = null }, modifier = Modifier.align(Alignment.End)) {
                    Text("Cancel", color = HubAccent)
                }
            }
        }
    }
    if (SportsMultiviewSession.open && SportsMultiviewSession.tiles.isNotEmpty() && !isIos) {
        LockPlayerToLandscape()
        FullscreenPlayerDialog(onDismiss = { SportsMultiviewSession.open = false }) {
            // Multi-View shrinks into a floating window too (home button, or the PiP button).
            com.nuvio.app.features.player.ManagePlayerPictureInPicture(
                isPlaying = true,
                videoSize = androidx.compose.ui.unit.IntSize(16, 9),
            )
            val inPip = com.nuvio.app.features.player.rememberIsInPictureInPicture()
            val enterPip = com.nuvio.app.features.player.rememberEnterPictureInPicture()
            Box(Modifier.fillMaxSize().background(Color.Black).then(if (inPip) Modifier else Modifier.safeDrawingPadding())) {
                SportsMultiviewSurface(
                    tiles = SportsMultiviewSession.tiles.toList(),
                    focusedId = SportsMultiviewSession.focusedId,
                    onFocus = { SportsMultiviewSession.focusedId = it },
                    modifier = Modifier.fillMaxSize().padding(top = if (inPip) 0.dp else 44.dp),
                )
                if (!inPip) Row(
                    Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { SportsMultiviewSession.open = false }) {
                        Text("‹ Sports Hub", color = HubAccent)
                    }
                    Text(
                        "Multi-View · ${SportsMultiviewSession.tiles.size}/${SportsMultiviewSession.MAX_TILES} · tap a screen to hear it",
                        color = HubMuted,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (SportsMultiviewSession.tiles.size < SportsMultiviewSession.MAX_TILES) {
                        TextButton(onClick = { SportsMultiviewSession.open = false }) {
                            Text("+ Add", color = HubAccent)
                        }
                    }
                    TextButton(onClick = { SportsMultiviewSession.remove(SportsMultiviewSession.focusedId) }) {
                        Text("Remove", color = HubAccent)
                    }
                    if (enterPip != null) {
                        TextButton(onClick = enterPip) { Text("PiP", color = HubAccent) }
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
private fun ScoreTicker(events: List<SportsEvent>, animate: Boolean = true) {
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
            if (animate) Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 600, velocity = 38.dp) else Modifier,
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

/** Tabs for a choice in the top row. All: the cross-sport views; a sport: its own games. */
private fun hubSections(hub: HubSport): List<SportsSection> = if (hub.isAll) {
    listOf(SportsSection.AllSports, SportsSection.LiveEvents, SportsSection.UsChannels, SportsSection.MyTeams)
} else buildList {
    if (hub.primary == SportsLeague.NFL) add(SportsSection.Channels)
    if (hub.scoreboardMembers.isNotEmpty()) {
        addAll(listOf(SportsSection.Live, SportsSection.Upcoming, SportsSection.Final))
    }
    if (hub.catalogMembers.isNotEmpty()) add(SportsSection.Events)
    if (hub.scoreboardMembers.isNotEmpty() && (isIos || hub.primary.usesTeamStats || hub.primary == SportsLeague.UFC)) {
        add(SportsSection.Standings)
    }
    addAll(listOf(SportsSection.Replays, SportsSection.Highlights))
}

private fun defaultSection(hub: HubSport): SportsSection = hubSections(hub).let { tabs ->
    if (SportsSection.Live in tabs) SportsSection.Live else tabs.first()
}

@Composable
private fun LeagueBadge(league: SportsLeague?, text: String, selected: Boolean, index: Int, onClick: () -> Unit) {
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
                if (league != null) LeagueLogo(league, Modifier.size(30.dp))
                else Text("ALL", color = HubAccent, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
            }
            Text(text, color = label, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
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
private fun GameRow(event: SportsEvent, showLeague: Boolean = false, onClick: () -> Unit) {
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
            // Mixed lists (All, College BB men's + women's…): say which league.
            if (showLeague) Text(event.league.shortLabel, color = HubAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
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

private fun gameSources(event: SportsEvent, found: List<SportsCatalogMatch>): List<SportsCatalogMatch> =
    rememberedFirst(found.filter { it.score > 0 })
        // Finished games: full replays before highlights.
        .sortedBy { if (event.status == SportsEventStatus.FINAL && it.kind != SportsContentKind.REPLAY) 1 else 0 }

/** Links that played before go first; ones that just failed go last (order kept otherwise). */
internal fun rememberedFirst(list: List<SportsCatalogMatch>): List<SportsCatalogMatch> =
    list.sortedByDescending { com.nuvio.app.features.streams.LinkMemory.score(it.item.id, it.item.name, it.addonName) }

@Composable
private fun AddToMultiviewButton(onClick: () -> Unit) {
    if (isIos) return
    TextButton(onClick = onClick) { Text("＋ Add to Multi-View", color = HubAccent) }
}

/** The last game opened in the hub, so it can be re-opened after Back from a stream. */
internal object SportsHubLastGame {
    private var last: SportsEvent? = null

    private fun key(event: SportsEvent) = event.league.name + "|" + event.id

    fun save(event: SportsEvent?): String? {
        if (event != null) last = event
        return event?.let(::key)
    }

    fun restore(key: String?): SportsEvent? = last?.takeIf { key != null && key(it) == key }
}

@Composable
private fun SportsGameDetail(
    event: SportsEvent,
    repository: SportsEventRepository,
    addons: List<ManagedAddon>,
    onBack: () -> Unit,
    onOpenStream: (MetaPreview) -> Unit,
    onAddToMultiview: (SportsCatalogMatch) -> Unit,
) {
    val replays = event.status == SportsEventStatus.FINAL
    var sources by remember(event.id, event.status, addons) {
        mutableStateOf(SportsAddonDiscovery.peek(event.league, listOf(event), addons, replays)?.let { gameSources(event, it) })
    }
    var boxScore by remember(event.id) { mutableStateOf<SportsBoxScore?>(null) }
    var statsLoading by remember(event.id) { mutableStateOf(true) }
    var statsError by remember(event.id) { mutableStateOf(false) }
    var teamIndex by remember(event.id) { mutableStateOf(0) }
    val owner = LocalLifecycleOwner.current
    var searching by remember(event.id, event.status, addons) { mutableStateOf(sources == null) }
    LaunchedEffect(event.id, event.status, addons) {
        if (sources == null) searching = true
        // Links appear as each add-on answers; the slowest one no longer holds up the rest.
        val found = SportsAddonDiscovery.find(event.league, listOf(event), addons, replays) { partial ->
            val shown = gameSources(event, partial)
            if (shown.isNotEmpty()) sources = shown
        }
        sources = gameSources(event, found)
        searching = false
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
                sources.orEmpty().isEmpty() && searching -> item { HubMessage("Checking enabled sports add-ons…") }
                sources.orEmpty().isEmpty() -> item { HubMessage("No matching stream found in enabled sports add-ons.") }
                event.status == SportsEventStatus.FINAL -> {
                    // Full games first, then the short highlight clips, each under its own heading.
                    val full = sources.orEmpty().filter { it.kind != SportsContentKind.HIGHLIGHT }
                    val clips = sources.orEmpty().filter { it.kind == SportsContentKind.HIGHLIGHT }
                    if (full.isNotEmpty()) {
                        item { HubMessage("Full game replays") }
                        items(full) { source -> SourceCard(source, onOpenStream) }
                    }
                    if (clips.isNotEmpty()) {
                        item { HubMessage("Highlights") }
                        items(clips) { source -> SourceCard(source, onOpenStream) }
                    }
                }
                else -> items(sources.orEmpty()) { source ->
                    Column {
                        SourceCard(source, onOpenStream)
                        AddToMultiviewButton { onAddToMultiview(source) }
                    }
                }
            }
            if (searching && !sources.isNullOrEmpty()) {
                item { HubMessage("Still checking more add-ons…") }
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
            if (com.nuvio.app.features.streams.LinkMemory.workedBefore(source.item.id, source.item.name, source.addonName)) {
                Text("✓ Worked last time", color = HubAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun SportsSearchField(query: String, onChange: (String) -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(shape).background(HubCard).border(1.dp, HubCardBorder, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Rounded.Search, contentDescription = null, tint = HubMuted, modifier = Modifier.size(20.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text("Search teams, channels, events…", color = HubMuted, fontSize = 15.sp)
            BasicTextField(
                value = query,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                cursorBrush = SolidColor(HubAccent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Icon(
                Icons.Rounded.Close, contentDescription = "Clear search", tint = HubMuted,
                modifier = Modifier.size(20.dp).clickable { onChange("") },
            )
        }
    }
}

@Composable
private fun SportsSearchResults(
    query: String,
    games: List<SportsEvent>,
    channels: List<SportsTvChannel>?,
    liveEvents: List<SportsLiveEvent>?,
    addonResults: List<SportsCatalogMatch>?,
    onOpenGame: (SportsEvent) -> Unit,
    onOpenStream: (MetaPreview) -> Unit,
    onAddToMultiview: (SportsCatalogMatch) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (games.isNotEmpty()) {
            item(key = "s_games") { HubSectionTitle("GAMES · ${games.size}") }
            itemsIndexed(games.take(30), key = { _, e -> "s_g_${e.league.name}_${e.id}" }) { _, event ->
                GameRow(event, showLeague = true) { onOpenGame(event) }
            }
        }
        if (!channels.isNullOrEmpty()) {
            item(key = "s_channels") { HubSectionTitle("CHANNELS · ${channels.size}") }
            itemsIndexed(channels, key = { _, c -> "s_c_${c.name}" }) { _, channel ->
                GroupedSources(channel.name, channel.sources, onOpenStream, onAddToMultiview)
            }
        }
        if (!liveEvents.isNullOrEmpty()) {
            item(key = "s_events") { HubSectionTitle("LIVE EVENTS · ${liveEvents.size}") }
            itemsIndexed(liveEvents, key = { i, _ -> "s_e_$i" }) { _, event ->
                GroupedSources(event.title, event.sources, onOpenStream, onAddToMultiview)
            }
        }
        item(key = "s_addons") { HubSectionTitle("FROM YOUR ADD-ONS" + (addonResults?.let { " · ${it.size}" } ?: "")) }
        when {
            addonResults == null -> item(key = "s_addons_loading") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = HubAccent, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Searching your add-ons for \"$query\"…", color = HubMuted)
                }
            }
            addonResults.isEmpty() -> item(key = "s_addons_none") { HubMessage("No add-on results for \"$query\".") }
            else -> itemsIndexed(addonResults, key = { i, _ -> "s_a_$i" }) { _, source ->
                Column {
                    SourceCard(source, onOpenStream)
                    AddToMultiviewButton { onAddToMultiview(source) }
                }
            }
        }
        if (games.isEmpty() && channels?.isEmpty() == true && liveEvents?.isEmpty() == true && addonResults?.isEmpty() == true) {
            item(key = "s_none") { HubMessage("Nothing found for \"$query\". Try a team name (Packers), a channel (ESPN2) or an event (UFC).") }
        }
    }
}

/** A channel or event with one or more add-on links; more than one folds open on tap. */
@Composable
private fun GroupedSources(
    title: String,
    sources: List<SportsCatalogMatch>,
    onOpenStream: (MetaPreview) -> Unit,
    onAddToMultiview: (SportsCatalogMatch) -> Unit,
) {
    @Suppress("NAME_SHADOWING")
    val sources = remember(sources) { rememberedFirst(sources) }
    if (sources.size == 1) {
        Column {
            SourceCard(sources.first(), onOpenStream)
            AddToMultiviewButton { onAddToMultiview(sources.first()) }
        }
        return
    }
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(HubCard).border(1.dp, HubCardBorder, shape),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "${sources.size} links · " + sources.map { it.addonName }.distinct().joinToString(),
                    color = HubMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Text(if (expanded) "Hide" else "Show", color = HubAccent, fontSize = 13.sp)
        }
        if (expanded) {
            Column(Modifier.padding(horizontal = 8.dp).padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                sources.forEach { source ->
                    SourceCard(source, onOpenStream)
                    AddToMultiviewButton { onAddToMultiview(source) }
                }
            }
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

/** Set when a game alert is tapped; the hub opens that game. */
internal object SportsHubOpenRequest {
    data class Request(val league: String?, val eventId: String?)

    private val _pending = kotlinx.coroutines.flow.MutableStateFlow<Request?>(null)
    val pending: kotlinx.coroutines.flow.StateFlow<Request?> = _pending

    fun request(league: String?, eventId: String?) { _pending.value = Request(league, eventId) }

    fun consume(request: Request) { if (_pending.value == request) _pending.value = null }
}

/** My Teams: game alerts on or off (asks for notification permission when turned on). */
@Composable
private fun GameAlertsToggle(on: Boolean) {
    val scope = rememberCoroutineScope()
    var allowed by remember { mutableStateOf(true) }
    LaunchedEffect(on) {
        allowed = runCatching { com.nuvio.app.features.notifications.EpisodeReleaseNotificationPlatform.notificationsAuthorized() }.getOrDefault(true)
    }
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("🔔 Game alerts", color = Color.White, fontWeight = FontWeight.Bold)
            Text("Starting soon, close late, and final scores for your teams", color = HubMuted, fontSize = 12.sp)
            if (on && !allowed) {
                TextButton(onClick = {
                    scope.launch {
                        allowed = runCatching { com.nuvio.app.features.notifications.EpisodeReleaseNotificationPlatform.requestAuthorization() }.getOrDefault(false)
                    }
                }) { Text("Allow notifications", color = HubAccent) }
            }
        }
        androidx.compose.material3.Switch(
            checked = on,
            onCheckedChange = { value ->
                SportsPreferences.setGameAlerts(value)
                if (value) scope.launch { runCatching { com.nuvio.app.features.notifications.EpisodeReleaseNotificationPlatform.requestAuthorization() } }
            },
            colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = HubAccent, checkedThumbColor = Color.Black),
        )
    }
}
