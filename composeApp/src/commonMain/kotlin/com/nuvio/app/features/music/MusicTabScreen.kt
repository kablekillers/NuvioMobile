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
internal val MusicAccent = Color(0xFF19A8FF)
internal val MusicBackground = Color(0xFF060B16)
internal val MusicSurface = Color(0xFF111A2B)

private enum class MusicSection(val label: String, val icon: ImageVector) {
    ForYou("For you", Icons.Rounded.MusicNote),
    Radio("Radio", Icons.Rounded.Radio),
    Albums("Albums", Icons.Rounded.Album),
    Videos("Videos", Icons.Rounded.SmartDisplay),
}

private data class MusicHomeData(
    val trending: List<MetaPreview> = emptyList(),
    val radio: List<MetaPreview> = emptyList(),
)

/** The Music tab: a clean, artwork-first music section in the style of Nuvio One. */
@Composable
fun MusicTabScreen() {
    var section by rememberSaveable { mutableStateOf(MusicSection.ForYou) }
    var query by rememberSaveable { mutableStateOf("") }
    var addonMissing by remember { mutableStateOf(false) }
    var home by remember { mutableStateOf(MusicHomeData()) }
    var homeLoading by remember { mutableStateOf(true) }
    var results by remember { mutableStateOf<List<MetaPreview>>(emptyList()) }
    var videos by remember { mutableStateOf<List<YouTubeMusicVideo>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var openAlbum by remember { mutableStateOf<MetaPreview?>(null) }
    var selectedVideo by remember { mutableStateOf<YouTubeMusicVideo?>(null) }
    val recent by MusicPlayer.recent.collectAsState()
    val player by MusicPlayer.state.collectAsState()

    // Home rows: trending Audius tracks and top radio stations, which need no search.
    LaunchedEffect(Unit) {
        homeLoading = true
        try {
            val manifest = musicAddonManifestUrl()
            if (manifest == null) {
                addonMissing = true
            } else {
                coroutineScope {
                    val trending = async { loadCatalog(manifest, "kk_audius", null, 40) }
                    val radio = async { loadCatalog(manifest, "kk_radio", null, 30) }
                    home = MusicHomeData(trending.await(), radio.await())
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

    LaunchedEffect(query, section) {
        val q = query.trim()
        results = emptyList()
        videos = emptyList()
        status = null
        val needsSearch = section == MusicSection.Albums || section == MusicSection.Videos || q.isNotEmpty()
        if (!needsSearch) return@LaunchedEffect
        if (q.isEmpty()) return@LaunchedEffect
        delay(400)
        searching = true
        try {
            val manifest = musicAddonManifestUrl() ?: run { addonMissing = true; return@LaunchedEffect }
            when (section) {
                MusicSection.ForYou -> {
                    results = coroutineScope {
                        listOf("kk_audius", "kablekillers_music_search", "kk_commons")
                            .map { id -> async { loadCatalog(manifest, id, q, 30) } }
                            .awaitAll()
                            .flatten()
                            .distinctBy { it.id }
                    }
                }
                MusicSection.Radio -> results = loadCatalog(manifest, "kk_radio", q, 60)
                MusicSection.Albums -> results = coroutineScope {
                    listOf("kk_audius_albums", "kk_archive")
                        .map { id -> async { loadCatalog(manifest, id, q, 30) } }
                        .awaitAll()
                        .flatten()
                        .filter(::isPlayableAlbum)
                        .distinctBy { it.id }
                }
                MusicSection.Videos -> videos = findYouTubeMusic(q, manifest)
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

    PlatformBackHandler(enabled = openAlbum != null) { openAlbum = null }

    Box(
        Modifier
            .fillMaxSize()
            .background(MusicBackground)
            .background(
                Brush.radialGradient(
                    colors = listOf(MusicAccent.copy(alpha = 0.22f), Color.Transparent),
                    radius = 900f,
                ),
            ),
    ) {
        val album = openAlbum
        if (album != null) {
            MusicAlbumPage(album = album, onBack = { openAlbum = null })
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().statusBarsPadding(),
                contentPadding = PaddingValues(bottom = if (player.current != null) 96.dp else 24.dp),
            ) {
                item(key = "header") {
                    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp)) {
                        Text("Music", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold)
                        Text("KABLE KILLERS MUSIC", color = MusicAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    }
                }
                item(key = "search") {
                    MusicSearchPill(
                        value = query,
                        placeholder = when (section) {
                            MusicSection.ForYou -> "Songs, artists or albums"
                            MusicSection.Radio -> "Search 30,000+ stations"
                            MusicSection.Albums -> "Search albums and concerts"
                            MusicSection.Videos -> "Search music videos"
                        },
                        onValueChange = { query = it },
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                    )
                }
                item(key = "sections") {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MusicSection.entries.forEach { option ->
                            MusicChip(option.label, option.icon, selected = section == option) { section = option }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }

                if (addonMissing) {
                    item(key = "missing") {
                        MusicMessage("Add Kable Killers Music in Settings → Add-ons to start listening.")
                    }
                }
                status?.let { message -> item(key = "status") { MusicMessage(message) } }
                if (searching) item(key = "searching") { MusicMessage("Searching…") }

                val q = query.trim()
                when {
                    section == MusicSection.Videos -> {
                        if (q.isEmpty()) item(key = "videos_hint") {
                            MusicMessage("Official music videos play in YouTube's own player, so YouTube may show ads.")
                        }
                        items(videos, key = { "yt:" + it.videoId }) { video ->
                            MusicVideoRow(video) { selectedVideo = video }
                        }
                    }
                    section == MusicSection.Albums -> {
                        if (q.isEmpty()) item(key = "albums_hint") {
                            MusicMessage("Search for an artist or album to play it from start to finish.")
                        }
                        items(results.chunked(2), key = { row -> row.joinToString { it.id } }) { row ->
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                row.forEach { item ->
                                    MusicAlbumCard(item, Modifier.weight(1f)) { openAlbum = item }
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                    section == MusicSection.Radio -> {
                        val stations = if (q.isEmpty()) home.radio else results
                        if (q.isEmpty()) item(key = "radio_title") { MusicSectionTitle("Top stations") }
                        itemsIndexed(stations, key = { _, it -> "radio:" + it.id }) { index, station ->
                            MusicTrackRow(
                                track = station.toMusicTrack(),
                                isCurrent = player.current?.id == station.id,
                                onClick = { MusicPlayer.playQueue(stations.map { it.toMusicTrack() }, index) },
                            )
                        }
                    }
                    q.isNotEmpty() -> {
                        item(key = "songs_title") { MusicSectionTitle("Songs") }
                        itemsIndexed(results, key = { _, it -> "res:" + it.id }) { index, item ->
                            MusicTrackRow(
                                track = item.toMusicTrack(),
                                isCurrent = player.current?.id == item.id,
                                onClick = {
                                    if (isPlayableAlbum(item)) openAlbum = item
                                    else MusicPlayer.playQueue(results.filterNot(::isPlayableAlbum).map { it.toMusicTrack() },
                                        results.filterNot(::isPlayableAlbum).indexOf(item).coerceAtLeast(0))
                                },
                            )
                        }
                    }
                    else -> {
                        if (recent.isNotEmpty()) {
                            item(key = "recent") {
                                MusicSectionTitle("Jump back in")
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 20.dp),
                                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                                ) {
                                    items(recent, key = { "recent:" + it.id }) { track ->
                                        MusicSquareCard(track) { MusicPlayer.playQueue(recent, recent.indexOf(track)) }
                                    }
                                }
                            }
                        }
                        if (home.radio.isNotEmpty()) {
                            item(key = "home_radio") {
                                MusicSectionTitle("Live radio")
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 20.dp),
                                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                                ) {
                                    val stations = home.radio.take(15).map { it.toMusicTrack() }
                                    itemsIndexed(stations, key = { _, it -> "hr:" + it.id }) { index, station ->
                                        MusicSquareCard(station, live = true) { MusicPlayer.playQueue(stations, index) }
                                    }
                                }
                            }
                        }
                        if (homeLoading) item(key = "home_loading") { MusicMessage("Loading music…") }
                        if (home.trending.isNotEmpty()) {
                            item(key = "trending_title") { MusicSectionTitle("Trending this week") }
                            val tracks = home.trending.map { it.toMusicTrack() }
                            itemsIndexed(tracks, key = { _, it -> "tr:" + it.id }) { index, track ->
                                MusicTrackRow(
                                    track = track,
                                    position = index + 1,
                                    isCurrent = player.current?.id == track.id,
                                    onClick = { MusicPlayer.playQueue(tracks, index) },
                                )
                            }
                        }
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

private suspend fun loadCatalog(manifestUrl: String, catalogId: String, search: String?, max: Int): List<MetaPreview> =
    try {
        fetchCatalogPage(
            manifestUrl = manifestUrl,
            type = "music",
            catalogId = catalogId,
            search = search,
            maxItems = max,
        ).items
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        emptyList()
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
