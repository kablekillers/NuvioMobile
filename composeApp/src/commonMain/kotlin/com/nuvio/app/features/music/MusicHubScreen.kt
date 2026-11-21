package com.nuvio.app.features.music

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.catalog.fetchCatalogPage
import com.nuvio.app.features.home.MetaPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

private val blue = Color(0xFF19A8FF)
private val dark = Color(0xFF070F20)

private enum class MusicTab(val title: String) {
    SONGS("Songs"), ALBUMS("Albums"), RADIO("Radio"), YOUTUBE("YouTube")
}

/** First mobile test of the installed music addon using the existing Nuvio stream flow. */
@Composable
fun MusicHubScreen(onBack: () -> Unit, onOpenTrack: (MetaPreview) -> Unit) {
    var search by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf(MusicTab.SONGS) }
    var items by remember { mutableStateOf<List<MetaPreview>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var youtubeVideos by remember { mutableStateOf<List<YouTubeMusicVideo>>(emptyList()) }
    var selectedYouTube by remember { mutableStateOf<YouTubeMusicVideo?>(null) }
    var albumLoading by remember { mutableStateOf(false) }
    var selectedAlbum by remember { mutableStateOf<Pair<MetaPreview, List<MusicAlbumTrack>>?>(null) }

    LaunchedEffect(search, tab) {
        delay(350)
        loading = true
        items = emptyList()
        youtubeVideos = emptyList()
        status = null
        try {
            AddonRepository.initialize()
            AddonRepository.awaitManifestsLoaded()
            val addon = AddonRepository.uiState.value.addons.firstOrNull {
                it.isActive && it.manifest?.id == "org.kablekillers.music"
            }
            val manifest = addon?.manifest
            if (manifest == null) {
                status = "Install Kable Killers Music from Add-ons to use Music Hub."
            } else if (tab == MusicTab.YOUTUBE) {
                if (search.isNotBlank()) {
                    try {
                        youtubeVideos = findYouTubeMusic(search, manifest.transportUrl)
                        if (youtubeVideos.isEmpty()) status = "No embeddable YouTube music videos found."
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        status = "YouTube search isn't available yet. Check YOUTUBE_API_KEY on Render."
                    }
                } else {
                    status = "Search for an artist, song or official music video."
                }
            } else {
                val catalogs = when (tab) {
                    MusicTab.SONGS -> listOf("kablekillers_music_search", "kk_audius", "kk_commons")
                    MusicTab.ALBUMS -> listOf("kk_audius_albums", "kk_archive")
                    MusicTab.RADIO -> listOf("kk_radio")
                    MusicTab.YOUTUBE -> emptyList()
                }
                var failures = 0
                val found = mutableListOf<MetaPreview>()
                // Show recordings on first opening Albums rather than an empty tab.
                // This is a discovery query, not a claim that every Archive item is an album.
                val effectiveSearch = search.trim().ifBlank {
                    if (tab == MusicTab.ALBUMS) "live music" else ""
                }
                catalogs.forEach { id ->
                    val descriptor = manifest.catalogs.firstOrNull { it.id == id } ?: return@forEach
                    if (effectiveSearch.isBlank() && descriptor.extra.any { it.name == "search" && it.isRequired }) return@forEach
                    try {
                        val page = fetchCatalogPage(
                            manifestUrl = manifest.transportUrl,
                            type = descriptor.type,
                            catalogId = descriptor.id,
                            search = effectiveSearch.ifBlank { null },
                            maxItems = 60
                        )
                        found += page.items
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        failures++
                    }
                }
                items = found.distinctBy { it.type + ":" + it.id }
                if (items.isEmpty()) {
                    status = if (failures > 0) "Music source unavailable; try again."
                    else if (search.isBlank() && tab == MusicTab.ALBUMS) "No albums found in the installed addon. The updated album catalogs must be deployed first."
                    else if (search.isBlank()) "Enter an artist, song or album to begin."
                    else "No matching recordings."
                }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            status = "Unable to load Music Hub."
        } finally {
            loading = false
        }
    }

    LaunchedEffect(selectedAlbum?.first?.id) {
        val album = selectedAlbum
        if (album == null || album.second.isNotEmpty()) return@LaunchedEffect
        val selected = album.first.takeIf(::isPlayableAlbum)
        val addon = AddonRepository.uiState.value.addons.firstOrNull {
            it.isActive && it.manifest?.id == "org.kablekillers.music"
        }
        if (selected == null || addon?.manifest == null) {
            albumLoading = false
            selectedAlbum = null
            status = "Music addon must be installed and enabled."
            return@LaunchedEffect
        }
        try {
            val tracks = fetchMusicAlbumTracks(selected, addon.manifest.transportUrl)
            if (tracks.isEmpty()) {
                status = "This album doesn't have a playable track list yet."
                selectedAlbum = null
            } else {
                selectedAlbum = album.first to tracks
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            status = "Unable to load the album tracks."
            selectedAlbum = null
        } finally {
            albumLoading = false
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(dark).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onBack) { Text("Back") }
            Text("KABLE KILLERS MUSIC", color = blue, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 12.dp))
        }
        OutlinedTextField(
            value = search, onValueChange = { search = it },
            label = { Text("Artist, song title or album") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            MusicTab.entries.forEach { option ->
                Button(onClick = { tab = option }, enabled = tab != option) {
                    Text(option.title)
                }
            }
        }
        if (loading) Text("Searching music sources…", color = Color.White)
        if (albumLoading) Text("Loading album track list…", color = Color.White)
        status?.let { Text(it, color = Color.White) }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            items(youtubeVideos, key = { "yt:" + it.videoId }) { video ->
                Column(
                    modifier = Modifier.fillMaxWidth().background(Color(0xFF13243C))
                        .clickable { selectedYouTube = video }.padding(15.dp)
                ) {
                    Text(video.title, color = Color.White)
                    Text(video.channel + " · YouTube", color = blue)
                }
            }
            items(items, key = { it.type + ":" + it.id }) { result ->
                Text(
                    result.name, color = Color.White,
                    modifier = Modifier.fillMaxWidth()
                        .background(Color(0xFF13243C))
                        .clickable {
                            if (tab == MusicTab.ALBUMS) {
                                albumLoading = true
                                status = null
                                selectedAlbum = result to emptyList()
                            } else {
                                onOpenTrack(result)
                            }
                        }
                        .padding(15.dp)
                )
            }
        }
    }
    selectedYouTube?.let { video ->
        YouTubeMusicPlayerDialog(video = video, onClose = { selectedYouTube = null })
    }
    selectedAlbum?.let { album ->
        if (album.second.isNotEmpty()) {
            MusicAlbumPlayerDialog(
                title = album.first.name,
                tracks = album.second,
                onClose = { selectedAlbum = null }
            )
        }
    }

}
