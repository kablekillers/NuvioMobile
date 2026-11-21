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
    SONGS("Songs & Artists"), ALBUMS("Albums"), RADIO("Live Radio")
}

/** First mobile test of the installed music addon using the existing Nuvio stream flow. */
@Composable
fun MusicHubScreen(onBack: () -> Unit, onOpenTrack: (MetaPreview) -> Unit) {
    var search by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf(MusicTab.SONGS) }
    var items by remember { mutableStateOf<List<MetaPreview>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(search, tab) {
        delay(350)
        loading = true
        items = emptyList()
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
            } else {
                val catalogs = when (tab) {
                    MusicTab.SONGS -> listOf("kablekillers_music_search", "kk_audius", "kk_commons")
                    MusicTab.ALBUMS -> listOf("kk_audius_albums", "kk_archive")
                    MusicTab.RADIO -> listOf("kk_radio")
                }
                var failures = 0
                val found = mutableListOf<MetaPreview>()
                catalogs.forEach { id ->
                    val descriptor = manifest.catalogs.firstOrNull { it.id == id } ?: return@forEach
                    if (search.isBlank() && descriptor.extra.any { it.name == "search" && it.isRequired }) return@forEach
                    try {
                        val page = fetchCatalogPage(
                            manifestUrl = manifest.transportUrl,
                            type = descriptor.type,
                            catalogId = descriptor.id,
                            search = search.trim().ifBlank { null },
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
        status?.let { Text(it, color = Color.White) }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            items(items, key = { it.type + ":" + it.id }) { result ->
                Text(
                    result.name, color = Color.White,
                    modifier = Modifier.fillMaxWidth()
                        .background(Color(0xFF13243C))
                        .clickable {
                            if (tab == MusicTab.ALBUMS) {
                                status = "Album playback queue is still being integrated for Mobile."
                            } else {
                                onOpenTrack(result)
                            }
                        }
                        .padding(15.dp)
                )
            }
        }
    }
}
