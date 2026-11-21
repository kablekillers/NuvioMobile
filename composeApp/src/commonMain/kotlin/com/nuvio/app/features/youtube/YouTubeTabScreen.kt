package com.nuvio.app.features.youtube

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.PlatformBackHandler
import com.nuvio.app.features.music.MusicPlayer
import com.nuvio.app.features.music.YouTubeMusicPlayerDialog
import com.nuvio.app.features.music.YouTubeMusicVideo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val YtBackground = Color(0xFF07090F)
private val YtSurface = Color(0xFF151A24)
private val YtRed = Color(0xFFFF0033)
private val KkBlue = Color(0xFF19A8FF)

private enum class YtFeed(val label: String, val category: String?) {
    Trending("Trending", "all"),
    Following("Following", null),
    Music("Music", "music"),
    Gaming("Gaming", "gaming"),
    Sports("Sports", "sports"),
    News("News", "news"),
    Movies("Movies", "movies"),
    Comedy("Comedy", "comedy"),
    Science("Science", "science"),
    History("History", null),
}

private data class ChannelPage(val id: String, val name: String)

/**
 * The YouTube tab: trending feeds by category, search, channels you follow and your watch
 * history. Data comes from the official YouTube Data API (through the Kable Killers Music addon)
 * and videos play in YouTube's own embedded player, ads included.
 */
@Composable
fun YouTubeTabScreen() {
    val local by remember { YouTubeRepository.ensureLoaded(); YouTubeRepository.local }.collectAsState()
    var feed by rememberSaveable { mutableStateOf(YtFeed.Trending) }
    var query by rememberSaveable { mutableStateOf("") }
    var channel by remember { mutableStateOf<ChannelPage?>(null) }
    var videos by remember { mutableStateOf<List<YouTubeVideo>>(emptyList()) }
    var nextToken by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf<YouTubeVideo?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val now = remember(videos) { kotlin.time.Clock.System.now().toEpochMilliseconds() }

    suspend fun loadPage(token: String?): YouTubePage {
        val q = query.trim()
        val ch = channel
        return when {
            ch != null -> YouTubeRepository.channel(ch.id, token)
            q.isNotEmpty() -> YouTubeRepository.search(q, token)
            feed.category != null -> YouTubeRepository.trending(feed.category!!, token)
            feed == YtFeed.Following -> {
                // Newest uploads from every followed channel, merged by date.
                val pages = local.following.take(12).map { ref ->
                    runCatching { YouTubeRepository.channel(ref.id) }.getOrNull()?.results.orEmpty().take(6)
                }
                YouTubePage(pages.flatten().sortedByDescending { it.publishedAt })
            }
            else -> YouTubePage(local.history)
        }
    }

    LaunchedEffect(feed, query, channel, if (feed == YtFeed.History) local.history else null) {
        val q = query.trim()
        if (q.isNotEmpty()) delay(450)
        loading = true
        message = null
        try {
            val page = loadPage(null)
            videos = page.results
            nextToken = page.nextPageToken
            if (videos.isEmpty()) message = when {
                feed == YtFeed.Following && q.isEmpty() && channel == null ->
                    "Follow channels from any video's channel page and their newest videos show up here."
                feed == YtFeed.History && q.isEmpty() && channel == null -> "Videos you watch show up here."
                else -> "No videos found."
            }
            listState.scrollToItem(0)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (e: YouTubeUnavailable) {
            videos = emptyList()
            message = e.message
        } catch (_: Exception) {
            videos = emptyList()
            message = "YouTube isn't available right now. Try again in a moment."
        } finally {
            loading = false
        }
    }

    // Load the next page when the list nears its end.
    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 4
        }
    }
    LaunchedEffect(nearEnd, nextToken) {
        val token = nextToken ?: return@LaunchedEffect
        if (!nearEnd || loading || loadingMore) return@LaunchedEffect
        loadingMore = true
        try {
            val page = loadPage(token)
            videos = (videos + page.results).distinctBy { it.videoId }
            nextToken = page.nextPageToken
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            nextToken = null
        } finally {
            loadingMore = false
        }
    }

    PlatformBackHandler(enabled = channel != null) { channel = null }

    Box(Modifier.fillMaxSize().background(YtBackground)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(bottom = 110.dp),
        ) {
            item(key = "header") {
                val ch = channel
                if (ch == null) {
                    Row(Modifier.padding(start = 18.dp, end = 18.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.SmartDisplay, contentDescription = null, tint = YtRed, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("YouTube", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
                    }
                } else {
                    Row(Modifier.padding(start = 6.dp, end = 18.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White,
                            modifier = Modifier.clip(CircleShape).clickable { channel = null }.padding(12.dp),
                        )
                        Text(ch.name, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        val followed = local.following.any { it.id == ch.id }
                        Text(
                            if (followed) "Following" else "Follow",
                            color = if (followed) Color.White else Color.Black,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(if (followed) YtSurface else Color.White)
                                .clickable { YouTubeRepository.toggleFollow(ch.id, ch.name) }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
            }
            if (channel == null) {
                item(key = "search") {
                    Row(
                        Modifier
                            .padding(horizontal = 18.dp, vertical = 12.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(28.dp))
                            .background(YtSurface)
                            .padding(horizontal = 16.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Search, contentDescription = null, tint = Color.White.copy(alpha = 0.6f))
                        Spacer(Modifier.width(10.dp))
                        Box(Modifier.weight(1f)) {
                            if (query.isEmpty()) Text("Search YouTube", color = Color.White.copy(alpha = 0.45f), fontSize = 15.sp)
                            BasicTextField(
                                value = query,
                                onValueChange = { query = it },
                                singleLine = true,
                                textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                                cursorBrush = SolidColor(KkBlue),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        if (query.isNotEmpty()) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = "Clear",
                                tint = Color.White.copy(alpha = 0.7f),
                                modifier = Modifier.clip(CircleShape).clickable { query = "" }.padding(2.dp),
                            )
                        }
                    }
                }
                if (query.isBlank()) {
                    item(key = "feeds") {
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 18.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            YtFeed.entries.forEach { option ->
                                val selected = feed == option
                                Text(
                                    option.label,
                                    color = if (selected) Color.Black else Color.White,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (selected) Color.White else YtSurface)
                                        .clickable { feed = option }
                                        .padding(horizontal = 14.dp, vertical = 8.dp),
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
            if (feed == YtFeed.History && query.isBlank() && channel == null && local.history.isNotEmpty()) {
                item(key = "clear_history") {
                    Text(
                        "Clear history",
                        color = KkBlue,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp).clickable { YouTubeRepository.clearHistory() },
                    )
                }
            }
            if (loading) item(key = "loading") { YtMessage("Loading…") }
            message?.let { item(key = "message") { YtMessage(it) } }
            items(videos, key = { it.videoId }) { video ->
                YtVideoCard(
                    video = video,
                    now = now,
                    onPlay = {
                        YouTubeRepository.addToHistory(video)
                        playing = video
                    },
                    onChannel = if (video.channelId.isNotBlank()) {
                        { channel = ChannelPage(video.channelId, video.channel); query = "" }
                    } else null,
                )
            }
            if (loadingMore) item(key = "more") { YtMessage("Loading more…") }
        }
    }

    LaunchedEffect(playing) {
        if (playing != null) MusicPlayer.pause()
    }
    playing?.let { video ->
        YouTubeMusicPlayerDialog(
            video = YouTubeMusicVideo(video.videoId, video.title, video.channel, video.thumbnail),
            onClose = { playing = null },
        )
    }
}

@Composable
private fun YtVideoCard(video: YouTubeVideo, now: Long, onPlay: () -> Unit, onChannel: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth().padding(bottom = 18.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(YtSurface)
                .clickable(onClick = onPlay),
        ) {
            AsyncImage(
                model = video.thumbnail.ifBlank { "https://i.ytimg.com/vi/${video.videoId}/hqdefault.jpg" },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            val badge = if (video.isLive) "LIVE" else formatIsoDuration(video.duration)
            if (badge != null) {
                Text(
                    badge,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (video.isLive) YtRed else Color.Black.copy(alpha = 0.8f))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
        }
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(YtSurface)
                    .then(if (onChannel != null) Modifier.clickable(onClick = onChannel) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Text(video.channel.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).clickable(onClick = onPlay)) {
                Text(video.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val age = relativeAge(video.publishedAt, now)
                Text(
                    listOfNotNull(video.channel.takeIf { it.isNotBlank() }, age).joinToString(" · "),
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (onChannel != null) Modifier.clickable(onClick = onChannel) else Modifier,
                )
            }
        }
    }
}

@Composable
private fun YtMessage(text: String) {
    Text(text, color = Color.White.copy(alpha = 0.65f), fontSize = 14.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp))
}
