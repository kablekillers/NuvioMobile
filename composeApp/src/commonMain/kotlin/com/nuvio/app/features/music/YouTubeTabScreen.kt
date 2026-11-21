package com.nuvio.app.features.music

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.addons.AddonRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * YouTube tab. Searches through the Kable Killers Music addon's official YouTube Data API
 * endpoint and plays videos in YouTube's official embedded player.
 */
@Composable
fun YouTubeTabScreen() {
    val colors = MaterialTheme.nuvio.colors
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<YouTubeMusicVideo>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<YouTubeMusicVideo?>(null) }

    LaunchedEffect(query) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            results = emptyList()
            status = null
            loading = false
            return@LaunchedEffect
        }
        delay(450)
        loading = true
        status = null
        try {
            AddonRepository.initialize()
            AddonRepository.awaitManifestsLoaded()
            val manifest = AddonRepository.uiState.value.addons.firstOrNull {
                it.isActive && it.manifest?.id == "org.kablekillers.music"
            }?.manifest
            if (manifest == null) {
                status = "Install Kable Killers Music from Add-ons to search YouTube."
                results = emptyList()
            } else {
                results = findYouTubeMusic(trimmed, manifest.transportUrl)
                if (results.isEmpty()) status = "No videos found."
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            status = "YouTube search isn't available right now. Try again in a moment."
        } finally {
            loading = false
        }
    }

    Box(Modifier.fillMaxSize().background(colors.background)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "header") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.SmartDisplay,
                        contentDescription = null,
                        tint = Color(0xFFFF0033),
                        modifier = Modifier.size(30.dp),
                    )
                    Spacer(Modifier.size(10.dp))
                    Text(
                        "YouTube",
                        color = colors.textPrimary,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.ExtraBold,
                    )
                }
            }
            item(key = "search") {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp),
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    placeholder = { Text("Search YouTube") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {}),
                )
            }
            if (loading) {
                item(key = "loading") { Text("Searching…", color = colors.textSecondary) }
            }
            status?.let { message ->
                item(key = "status") { Text(message, color = colors.textSecondary) }
            }
            if (query.isBlank() && !loading) {
                item(key = "hint") {
                    Text(
                        "Search for videos, music, channels or highlights. Videos play in YouTube's own player.",
                        color = colors.textSecondary,
                    )
                }
            }
            items(results, key = { it.videoId }) { video ->
                YouTubeVideoCard(video = video, onClick = { selected = video })
            }
        }
    }

    selected?.let { video ->
        YouTubeMusicPlayerDialog(video = video, onClose = { selected = null })
    }
}

@Composable
private fun YouTubeVideoCard(video: YouTubeMusicVideo, onClick: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(14.dp))
                .background(colors.backgroundCard),
        ) {
            val thumbnail = video.thumbnail.ifBlank { "https://i.ytimg.com/vi/${video.videoId}/hqdefault.jpg" }
            AsyncImage(
                model = thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            video.title,
            color = colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (video.channel.isNotBlank()) {
            Text(video.channel, color = colors.textSecondary, fontSize = 13.sp, maxLines = 1)
        }
        Spacer(Modifier.padding(bottom = 2.dp))
    }
}
