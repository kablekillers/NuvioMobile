package com.nuvio.app.features.calendar

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.CinemaGold
import com.nuvio.app.core.ui.cinemaPressScale
import com.nuvio.app.core.ui.cinemaShine
import com.nuvio.app.features.notifications.EpisodeReleaseNotificationsRepository

private val Muted = Color(0xFF9E998F)
private val CardBg = Color.White.copy(alpha = 0.045f)
private val CardBorder = Color.White.copy(alpha = 0.07f)

/** Upcoming and recently aired episodes of the shows in the library, grouped by day. */
@Composable
fun CalendarScreen(
    onBack: () -> Unit,
    onOpenShow: (CalendarEpisode) -> Unit,
) {
    val state by UpcomingEpisodesRepository.state.collectAsStateWithLifecycle()
    val alerts by EpisodeReleaseNotificationsRepository.uiState.collectAsStateWithLifecycle()
    var recentTab by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        UpcomingEpisodesRepository.ensureLoaded()
        EpisodeReleaseNotificationsRepository.ensureLoaded()
    }
    val list = if (recentTab) state.recent else state.upcoming
    Column(Modifier.fillMaxSize().background(Color(0xFF08080A)).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Text("Calendar", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TabPill("Coming up", selected = !recentTab) { recentTab = false }
            TabPill("Recently aired", selected = recentTab) { recentTab = true }
        }
        AlertsRow(enabled = alerts.isEnabled) { EpisodeReleaseNotificationsRepository.setEnabled(!alerts.isEnabled) }
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                !state.hasLoaded -> item { CircularProgressIndicator(color = CinemaGold.Base) }
                list.isEmpty() -> item {
                    Text(
                        if (recentTab) "No episodes of your shows aired in the last two weeks."
                        else "Nothing scheduled yet. Add shows to your Library and their new episodes will show up here.",
                        color = Muted,
                        fontSize = 14.sp,
                    )
                }
                else -> {
                    list.groupBy { it.dateIso }.forEach { (_, day) ->
                        item(key = "day_${day.first().dateIso}") {
                            Text(
                                UpcomingEpisodesRepository.dayLabel(day.first()),
                                color = if (day.first().dayOffset == 0) CinemaGold.Base else Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                            )
                        }
                        items(day, key = { "${it.showId}_${it.season}_${it.episode}_${it.dateIso}" }) { ep ->
                            EpisodeRow(ep) { onOpenShow(ep) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AlertsRow(enabled: Boolean, onToggle: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .cinemaPressScale(interaction, pressedScale = 0.98f)
            .clip(shape)
            .background(if (enabled) CinemaGold.Base.copy(alpha = 0.12f) else CardBg)
            .border(1.dp, if (enabled) CinemaGold.Base.copy(alpha = 0.6f) else CardBorder, shape)
            .clickable(interactionSource = interaction, indication = null, role = Role.Switch, onClick = onToggle)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("🔔", fontSize = 16.sp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("New-episode alerts", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(
                if (enabled) "On · we'll notify you when a new episode of a show in your Library comes out"
                else "Off · tap to get notified when new episodes come out",
                color = Muted,
                fontSize = 12.sp,
            )
        }
        Text(if (enabled) "ON" else "OFF", color = if (enabled) CinemaGold.Soft else Muted, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun TabPill(text: String, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
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
        Text(text, color = if (selected) CinemaGold.Ink else Color.White, fontSize = 13.sp, fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold)
    }
}

@Composable
private fun EpisodeRow(ep: CalendarEpisode, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .cinemaPressScale(interaction, pressedScale = 0.97f)
            .clip(shape)
            .background(CardBg)
            .border(1.dp, CardBorder, shape)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AsyncImage(
            model = ep.poster,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.width(52.dp).height(76.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF17161A)),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(ep.showName, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(ep.code.takeIf { it.isNotBlank() }, ep.title).joinToString(" · "),
                color = Color(0xFFCFC9BE),
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Home row: the next episodes of library shows, with a gold "See all" into the Calendar. */
@Composable
fun ComingUpRow(onOpenShow: (CalendarEpisode) -> Unit, onSeeAll: () -> Unit, modifier: Modifier = Modifier) {
    val state by UpcomingEpisodesRepository.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { UpcomingEpisodesRepository.ensureLoaded() }
    val next = state.upcoming.filter { it.dayOffset <= 14 }.distinctBy { it.showId }.take(12)
    if (next.isEmpty()) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Coming up", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            Text("Calendar", color = CinemaGold.Base, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable(onClick = onSeeAll).padding(6.dp))
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(next, key = { "${it.showId}_${it.dateIso}" }) { ep -> ComingUpCard(ep) { onOpenShow(ep) } }
        }
    }
}

@Composable
private fun ComingUpCard(ep: CalendarEpisode, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(500, easing = FastOutSlowInEasing)) }
    Column(
        Modifier
            .width(220.dp)
            .graphicsLayer { alpha = enter.value; translationY = (1f - enter.value) * 16.dp.toPx() }
            .cinemaPressScale(interaction)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp)).background(Color(0xFF17161A))) {
            AsyncImage(model = ep.thumbnail ?: ep.background ?: ep.poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)))))
            Box(
                Modifier
                    .padding(8.dp)
                    .clip(RoundedCornerShape(50))
                    .then(if (ep.dayOffset == 0) Modifier.background(CinemaGold.brush).cinemaShine(periodMillis = 4_000, strength = 0.5f) else Modifier.background(Color.Black.copy(alpha = 0.6f)))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    UpcomingEpisodesRepository.dayLabel(ep),
                    color = if (ep.dayOffset == 0) CinemaGold.Ink else Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
            }
            Text(
                ep.code,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
            )
        }
        Text(ep.showName, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        ep.title?.let { Text(it, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}
