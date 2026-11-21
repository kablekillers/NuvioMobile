package com.nuvio.app.features.music

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.PlatformBackHandler
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.catalog.fetchCatalogPage
import com.nuvio.app.features.home.MetaPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

/** Kable Killers electric blue, matching the brand. */
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.History
import com.nuvio.app.features.addons.fetchAddonResponseText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json


/** Kable Killers electric blue, matching the brand. */
internal val MusicAccent = Color(0xFF19A8FF)
internal val MusicBackground = Color.Black
internal val MusicSurface = Color(0xFF16181D)

private enum class MusicTab(val label: String) {
    Home("Home"), Explore("Explore"), Library("Library"), Radio("Radio"),
    Albums("Albums"), Search("Search"), Videos("Videos"),
}

@Serializable
private data class HomeMeta(val id: String = "", val name: String = "", val poster: String? = null, val description: String? = null)

@Serializable
private data class HomeSection(val title: String = "", val kind: String = "tracks", val items: List<HomeMeta> = emptyList())

@Serializable
private data class HomeArtist(val name: String = "", val handle: String = "", val image: String = "")

@Serializable
private data class HomePayload(val sections: List<HomeSection> = emptyList(), val artists: List<HomeArtist> = emptyList())

private val homeJson = Json { ignoreUnknownKeys = true; coerceInputValues = true }

private fun HomeMeta.toPreview(): MetaPreview = MetaPreview(id = id, type = "music", name = name, poster = poster)

/** A titled row of artwork, opened full-screen from Explore or "See all". */
private data class MusicCollection(val title: String, val kind: String, val items: List<MetaPreview>)

/** The Music tab, laid out like Nuvio One: black, pill tabs and rows of artwork. */
@Composable
fun MusicTabScreen() {
    var tab by rememberSaveable { mutableStateOf(MusicTab.Home) }
    var query by rememberSaveable { mutableStateOf("") }
    var addonMissing by remember { mutableStateOf(false) }
    var sections by remember { mutableStateOf<List<MusicCollection>>(emptyList()) }
    var artists by remember { mutableStateOf<List<HomeArtist>>(emptyList()) }
    var homeLoading by remember { mutableStateOf(true) }
    var results by remember { mutableStateOf<List<MetaPreview>>(emptyList()) }
    var videos by remember { mutableStateOf<List<YouTubeMusicVideo>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var openAlbum by remember { mutableStateOf<MetaPreview?>(null) }
    var openCollection by remember { mutableStateOf<MusicCollection?>(null) }
    var selectedVideo by remember { mutableStateOf<YouTubeMusicVideo?>(null) }
    val recent by MusicPlayer.recent.collectAsState()
    val player by MusicPlayer.state.collectAsState()

    LaunchedEffect(Unit) {
        homeLoading = true
        try {
            val manifest = musicAddonManifestUrl()
            if (manifest == null) {
                addonMissing = true
            } else {
                val base = manifest.substringBeforeLast("/").trimEnd('/')
                val payload = runCatching {
                    homeJson.decodeFromString<HomePayload>(fetchAddonResponseText("$base/api/music/home"))
                }.getOrNull()
                if (payload != null && payload.sections.isNotEmpty()) {
                    sections = payload.sections.map { s -> MusicCollection(s.title, s.kind, s.items.map { it.toPreview() }) }
                    artists = payload.artists
                } else {
                    // Older addon without the home endpoint: fall back to its catalogs.
                    coroutineScope {
                        val trending = async { loadCatalog(manifest, "kk_audius", null, 40) }
                        val radio = async { loadCatalog(manifest, "kk_radio", null, 30) }
                        sections = listOf(
                            MusicCollection("Today's biggest hits", "tracks", trending.await()),
                            MusicCollection("Live radio", "radio", radio.await()),
                        ).filter { it.items.isNotEmpty() }
                    }
                }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            status = "Music is unavailable right now. Try again in a moment."
        } finally {
            homeLoading = false
        }
    }

    LaunchedEffect(query, tab) {
        val q = query.trim()
        results = emptyList()
        videos = emptyList()
        if (tab !in listOf(MusicTab.Search, MusicTab.Radio, MusicTab.Albums, MusicTab.Videos) || q.isEmpty()) return@LaunchedEffect
        delay(400)
        searching = true
        status = null
        try {
            val manifest = musicAddonManifestUrl() ?: run { addonMissing = true; return@LaunchedEffect }
            when (tab) {
                MusicTab.Search -> results = coroutineScope {
                    listOf("kk_audius", "kablekillers_music_search", "kk_audius_albums", "kk_radio", "kk_commons")
                        .map { id -> async { loadCatalog(manifest, id, q, 20) } }
                        .awaitAll().flatten().distinctBy { it.id }
                }
                MusicTab.Radio -> results = loadCatalog(manifest, "kk_radio", q, 60)
                MusicTab.Albums -> results = coroutineScope {
                    listOf("kk_audius_albums", "kk_archive")
                        .map { id -> async { loadCatalog(manifest, id, q, 30) } }
                        .awaitAll().flatten().filter(::isPlayableAlbum).distinctBy { it.id }
                }
                MusicTab.Videos -> videos = findYouTubeMusic(q, manifest)
                else -> Unit
            }
            if (results.isEmpty() && videos.isEmpty()) status = "No results for \"$q\"."
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            status = "Search isn't available right now. Try again in a moment."
        } finally {
            searching = false
        }
    }

    PlatformBackHandler(enabled = openAlbum != null || openCollection != null) {
        if (openAlbum != null) openAlbum = null else openCollection = null
    }

    fun openItem(item: MetaPreview, kind: String, siblings: List<MetaPreview>) {
        when {
            isPlayableAlbum(item) || kind == "albums" -> openAlbum = item
            else -> {
                val playable = siblings.filterNot(::isPlayableAlbum)
                MusicPlayer.playQueue(playable.map { it.toMusicTrack() }, playable.indexOf(item).coerceAtLeast(0))
            }
        }
    }

    val bottomPad = if (player.current != null) 100.dp else 28.dp

    Box(Modifier.fillMaxSize().background(MusicBackground)) {
        val album = openAlbum
        val collection = openCollection
        when {
            album != null -> MusicAlbumPage(album = album, onBack = { openAlbum = null })
            collection != null -> MusicCollectionPage(
                collection = collection,
                bottomPadding = bottomPad,
                onBack = { openCollection = null },
                onItem = { item -> openItem(item, collection.kind, collection.items) },
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().statusBarsPadding(),
                contentPadding = PaddingValues(bottom = bottomPad),
            ) {
                item(key = "header") { MusicHeader() }
                item(key = "tabs") {
                    MusicPillTabs(selected = tab, onSelect = { tab = it; query = "" })
                }
                if (tab in listOf(MusicTab.Search, MusicTab.Radio, MusicTab.Albums, MusicTab.Videos)) {
                    item(key = "search") {
                        MusicSearchPill(
                            value = query,
                            placeholder = when (tab) {
                                MusicTab.Radio -> "Search 30,000+ radio stations"
                                MusicTab.Albums -> "Search albums and concerts"
                                MusicTab.Videos -> "Search music videos"
                                else -> "Songs, artists, albums or stations"
                            },
                            onValueChange = { query = it },
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                        )
                    }
                }
                if (addonMissing) item(key = "missing") { MusicMessage("Add Kable Killers Music in Settings → Add-ons to start listening.") }
                status?.let { item(key = "status") { MusicMessage(it) } }
                if (searching) item(key = "searching") { MusicMessage("Searching…") }

                when (tab) {
                    MusicTab.Home -> {
                        if (artists.isNotEmpty()) {
                            item(key = "artists") {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                ) {
                                    items(artists, key = { "artist:" + it.name }) { artist ->
                                        MusicArtistChip(artist) { query = artist.name; tab = MusicTab.Search }
                                    }
                                }
                            }
                        }
                        if (recent.isNotEmpty()) {
                            item(key = "recent") {
                                MusicRowSection(
                                    title = "Jump back in",
                                    cards = recent.map { MusicCardData(it.title, it.artist.ifBlank { "Music" }, it.artwork, it.isLive) },
                                    onCard = { index -> MusicPlayer.playQueue(recent, index) },
                                )
                            }
                        }
                        if (homeLoading && sections.isEmpty()) item(key = "loading") { MusicMessage("Loading music…") }
                        sections.forEach { section ->
                            item(key = "sec:" + section.title) {
                                MusicRowSection(
                                    title = section.title,
                                    cards = section.items.take(20).map { it.toCard(section.kind) },
                                    onCard = { index -> openItem(section.items[index], section.kind, section.items) },
                                    onSeeAll = if (section.items.size > 3) ({ openCollection = section }) else null,
                                )
                            }
                        }
                    }
                    MusicTab.Explore -> {
                        item(key = "explore") {
                            Text("Browse", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier.padding(start = 18.dp, top = 16.dp, bottom = 12.dp))
                        }
                        items(sections.chunked(2), key = { row -> "ex:" + row.joinToString { it.title } }) { row ->
                            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                row.forEach { section ->
                                    MusicGenreTile(section, Modifier.weight(1f)) { openCollection = section }
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                    MusicTab.Library -> {
                        if (recent.isEmpty()) {
                            item(key = "lib_empty") { MusicMessage("Songs and stations you play show up here.") }
                        } else {
                            item(key = "lib_title") { MusicSectionHeader("Recently played", null) }
                            itemsIndexed(recent, key = { _, t -> "lib:" + t.id }) { index, track ->
                                MusicTrackRow(track = track, isCurrent = player.current?.id == track.id,
                                    onClick = { MusicPlayer.playQueue(recent, index) })
                            }
                        }
                    }
                    MusicTab.Radio -> {
                        val stations = if (query.isBlank()) sections.firstOrNull { it.kind == "radio" }?.items.orEmpty() else results
                        musicGridItems(stations, "radio", onItem = { openItem(it, "radio", stations) })
                    }
                    MusicTab.Albums -> {
                        if (query.isBlank()) {
                            val trendingAlbums = sections.firstOrNull { it.kind == "albums" }
                            if (trendingAlbums != null) {
                                item(key = "alb_title") { MusicSectionHeader(trendingAlbums.title, null) }
                                musicGridItems(trendingAlbums.items, "albums", onItem = { openAlbum = it })
                            } else {
                                item(key = "alb_hint") { MusicMessage("Search for an artist or album to play it start to finish.") }
                            }
                        } else {
                            musicGridItems(results, "albums", onItem = { openAlbum = it })
                        }
                    }
                    MusicTab.Search -> {
                        if (query.isBlank()) item(key = "search_hint") { MusicMessage("Search across Audius, SoundCloud, live radio and open recordings.") }
                        musicGridItems(results, "tracks", onItem = { openItem(it, "tracks", results) })
                    }
                    MusicTab.Videos -> {
                        if (query.isBlank()) item(key = "videos_hint") {
                            MusicMessage("Official music videos play in YouTube's own player, so YouTube may show ads.")
                        }
                        items(videos, key = { "yt:" + it.videoId }) { video -> MusicVideoRow(video) { selectedVideo = video } }
                    }
                }
            }
        }
    }

    LaunchedEffect(selectedVideo) {
        if (selectedVideo != null) MusicPlayer.pause()
    }
    selectedVideo?.let { video ->
        YouTubeMusicPlayerDialog(video = video, onClose = { selectedVideo = null })
    }
}

private data class MusicCardData(val title: String, val subtitle: String, val artwork: String?, val live: Boolean = false)

private fun MetaPreview.toCard(kind: String): MusicCardData {
    val track = toMusicTrack()
    val subtitle = when {
        kind == "radio" || track.isLive -> "Live radio"
        kind == "albums" || isPlayableAlbum(this) -> track.artist.ifBlank { "Album" }
        else -> track.artist.ifBlank { "Music" }
    }
    return MusicCardData(track.title, subtitle, track.artwork, track.isLive)
}

/** Three cards per row, like Nuvio One's grids. */
private fun androidx.compose.foundation.lazy.LazyListScope.musicGridItems(
    items: List<MetaPreview>,
    kind: String,
    onItem: (MetaPreview) -> Unit,
) {
    items(items.chunked(3), key = { row -> kind + ":" + row.joinToString { it.id } }) { row ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            row.forEach { item ->
                MusicCard(item.toCard(kind), Modifier.weight(1f)) { onItem(item) }
            }
            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun MusicHeader() {
    Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(40.dp).clip(CircleShape)
                .background(Brush.linearGradient(listOf(Color(0xFF0A4DFF), MusicAccent, Color(0xFF7DE3FF)))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = Color.Black, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text("Kable Killers Music", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MusicPillTabs(selected: MusicTab, onSelect: (MusicTab) -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(
            Modifier
                .clip(RoundedCornerShape(30.dp))
                .background(Color(0xFF111216))
                .horizontalScroll(rememberScrollState())
                .padding(5.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            MusicTab.entries.forEach { option ->
                val isSelected = option == selected
                Text(
                    option.label,
                    color = if (isSelected) Color.Black else Color.White.copy(alpha = 0.75f),
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 15.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(24.dp))
                        .background(if (isSelected) Color.White else Color.Transparent)
                        .clickable { onSelect(option) }
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                )
            }
        }
    }
}

@Composable
private fun MusicArtistChip(artist: HomeArtist, onClick: () -> Unit) {
    Column(Modifier.width(76.dp).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(MusicSurface), contentAlignment = Alignment.Center) {
            Text(artist.name.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold)
            if (artist.image.isNotBlank()) {
                AsyncImage(model = artist.image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(artist.name, color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MusicSectionHeader(title: String, onSeeAll: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 10.dp, top = 22.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (onSeeAll != null) {
            Text("See all", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onSeeAll).padding(horizontal = 8.dp, vertical = 4.dp))
        }
    }
}

@Composable
private fun MusicRowSection(title: String, cards: List<MusicCardData>, onCard: (Int) -> Unit, onSeeAll: (() -> Unit)? = null) {
    Column {
        MusicSectionHeader(title, onSeeAll)
        LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            itemsIndexed(cards, key = { i, c -> "$i:${c.title}" }) { index, card ->
                MusicCard(card, Modifier.width(150.dp)) { onCard(index) }
            }
        }
    }
}

@Composable
private fun MusicCard(card: MusicCardData, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick)) {
        Box {
            MusicArtwork(card.artwork, Modifier.fillMaxWidth().aspectRatio(1f), corner = 14.dp)
            if (card.live) {
                Text("LIVE", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(8.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFFE53935)).padding(horizontal = 6.dp, vertical = 2.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(card.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(2.dp))
        Text(card.subtitle, color = Color.White.copy(alpha = 0.55f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
    }
}

private val GenreTileColors = listOf(
    Color(0xFF1E5BFF), Color(0xFFE5318C), Color(0xFF12A36B), Color(0xFFFF7A1A),
    Color(0xFF8B3DFF), Color(0xFF0FA3B1), Color(0xFFD72638), Color(0xFF3D5A80),
)

@Composable
private fun MusicGenreTile(section: MusicCollection, modifier: Modifier, onClick: () -> Unit) {
    val color = GenreTileColors[(section.title.hashCode() and 0x7fffffff) % GenreTileColors.size]
    val art = section.items.firstOrNull()?.poster
    Box(
        modifier.height(96.dp).clip(RoundedCornerShape(14.dp))
            .background(Brush.linearGradient(listOf(color, color.copy(alpha = 0.55f))))
            .clickable(onClick = onClick),
    ) {
        if (!art.isNullOrBlank()) {
            AsyncImage(
                model = art, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.size(70.dp).align(Alignment.BottomEnd).padding(bottom = 0.dp)
                    .rotate(18f).clip(RoundedCornerShape(8.dp)),
            )
        }
        Text(section.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(12.dp).fillMaxWidth(0.7f), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MusicCollectionPage(collection: MusicCollection, bottomPadding: androidx.compose.ui.unit.Dp, onBack: () -> Unit, onItem: (MetaPreview) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = bottomPadding)) {
        item(key = "back") {
            Row(Modifier.padding(start = 6.dp, top = 4.dp, end = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = Color.White,
                    modifier = Modifier.clip(CircleShape).clickable(onClick = onBack).padding(12.dp))
                Text(collection.title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        musicGridItems(collection.items, collection.kind, onItem)
    }
}

@Composable
private fun MusicAlbumPage(album: MetaPreview, onBack: () -> Unit) {
    var tracks by remember(album.id) { mutableStateOf<List<MusicAlbumTrack>>(emptyList()) }
    var loading by remember(album.id) { mutableStateOf(true) }
    var error by remember(album.id) { mutableStateOf<String?>(null) }
    val header = album.toMusicTrack()
    val player by MusicPlayer.state.collectAsState()

    LaunchedEffect(album.id) {
        loading = true
        try {
            val manifest = musicAddonManifestUrl()
            tracks = if (manifest != null) fetchMusicAlbumTracks(album, manifest) else emptyList()
            if (tracks.isEmpty()) error = "This album doesn't have a playable track list yet."
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            error = "Couldn't load that album."
        } finally {
            loading = false
        }
    }

    val queue = tracks.mapIndexed { index, t ->
        MusicTrack(id = "${album.id}#$index", title = t.name, artist = header.artist.ifBlank { header.title }, artwork = header.artwork, url = t.url)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(bottom = if (player.current != null) 96.dp else 24.dp),
    ) {
        item(key = "back") {
            Row(Modifier.padding(start = 8.dp, top = 4.dp)) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White,
                    modifier = Modifier.clip(CircleShape).clickable(onClick = onBack).padding(12.dp),
                )
            }
        }
        item(key = "art") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                MusicArtwork(header.artwork, Modifier.fillMaxWidth().aspectRatio(1f), corner = 18.dp)
                Spacer(Modifier.height(18.dp))
                Text(header.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (header.artist.isNotBlank()) Text(header.artist, color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp)
                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier
                        .clip(RoundedCornerShape(28.dp))
                        .background(if (queue.isEmpty()) Color.White.copy(alpha = 0.1f) else MusicAccent)
                        .clickable(enabled = queue.isNotEmpty()) { MusicPlayer.playQueue(queue, 0) }
                        .padding(horizontal = 28.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = Color.Black)
                    Spacer(Modifier.width(6.dp))
                    Text("Play album", color = Color.Black, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(12.dp))
            }
        }
        if (loading) item(key = "loading") { MusicMessage("Loading tracks…") }
        error?.let { item(key = "error") { MusicMessage(it) } }
        itemsIndexed(queue, key = { _, t -> t.id }) { index, track ->
            MusicTrackRow(
                track = track,
                position = index + 1,
                showArtwork = false,
                isCurrent = player.current?.id == track.id,
                onClick = { MusicPlayer.playQueue(queue, index) },
            )
        }
    }
}

@Composable
internal fun MusicArtwork(url: String?, modifier: Modifier, corner: androidx.compose.ui.unit.Dp = 10.dp) {
    Box(
        modifier.clip(RoundedCornerShape(corner)).background(MusicSurface),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = Color.White.copy(alpha = 0.25f), modifier = Modifier.size(28.dp))
        if (!url.isNullOrBlank()) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun MusicSearchPill(value: String, placeholder: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(MusicSurface)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, contentDescription = null, tint = Color.White.copy(alpha = 0.6f))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, color = Color.White.copy(alpha = 0.45f), fontSize = 15.sp)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                cursorBrush = SolidColor(MusicAccent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = "Clear",
                tint = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.clip(CircleShape).clickable { onValueChange("") }.padding(2.dp),
            )
        }
    }
}

@Composable
private fun MusicChip(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) MusicAccent else MusicSurface)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (selected) Color.Black else Color.White, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = if (selected) Color.Black else Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

@Composable
private fun MusicSectionTitle(title: String) {
    Text(
        title,
        color = Color.White,
        fontSize = 19.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 10.dp),
    )
}

@Composable
private fun MusicMessage(text: String) {
    Text(text, color = Color.White.copy(alpha = 0.65f), fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
}

@Composable
internal fun MusicTrackRow(
    track: MusicTrack,
    isCurrent: Boolean,
    onClick: () -> Unit,
    position: Int? = null,
    showArtwork: Boolean = true,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (position != null) {
            Text(
                position.toString(),
                color = if (isCurrent) MusicAccent else Color.White.copy(alpha = 0.5f),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.width(28.dp),
            )
        }
        if (showArtwork) {
            MusicArtwork(track.artwork, Modifier.size(52.dp), corner = 8.dp)
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                color = if (isCurrent) MusicAccent else Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = if (track.isLive) listOf("LIVE", track.artist).filter { it.isNotBlank() }.joinToString(" · ") else track.artist
            if (subtitle.isNotBlank()) {
                Text(subtitle, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (isCurrent) {
            Icon(Icons.Rounded.GraphicEq, contentDescription = "Now playing", tint = MusicAccent, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun MusicSquareCard(track: MusicTrack, live: Boolean = false, onClick: () -> Unit) {
    Column(Modifier.width(132.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)) {
        Box {
            MusicArtwork(track.artwork, Modifier.size(132.dp), corner = 12.dp)
            if (live || track.isLive) {
                Text(
                    "LIVE",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .padding(8.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFFE53935))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(track.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (track.artist.isNotBlank()) {
            Text(track.artist, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun MusicAlbumCard(item: MetaPreview, modifier: Modifier, onClick: () -> Unit) {
    val track = item.toMusicTrack()
    Column(modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)) {
        MusicArtwork(track.artwork, Modifier.fillMaxWidth().aspectRatio(1f), corner = 12.dp)
        Spacer(Modifier.height(8.dp))
        Text(track.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (track.artist.isNotBlank()) {
            Text(track.artist, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun MusicVideoRow(video: YouTubeMusicVideo, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(140.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(MusicSurface)) {
            AsyncImage(
                model = video.thumbnail.ifBlank { "https://i.ytimg.com/vi/${video.videoId}/hqdefault.jpg" },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(video.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(video.channel, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, maxLines = 1)
        }
    }
}
