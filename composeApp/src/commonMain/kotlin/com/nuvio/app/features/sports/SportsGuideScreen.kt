package com.nuvio.app.features.sports

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage

private val GuideBlue = Color(0xFF32A9FF)
private val GuideSurface = Color(0xFF101D30)

/**
 * Sports-only guide. Data must be supplied by real channel and EPG providers.
 * A channel without a resolved stream is not presented as playable.
 */
@Composable
internal fun SportsGuideScreen(
    channels: List<SportsGuideChannel>,
    programs: List<SportsGuideProgram>,
    atEpochMillis: Long,
    onSelectChannel: (SportsGuideChannel) -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Color(0xFF080F1C))) {
        Text(
            "SPORTS TV GUIDE",
            color = GuideBlue,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(16.dp),
        )
        if (channels.isEmpty()) {
            Text(
                "No sports channels found. Add a compatible channel source to populate the guide.",
                color = Color.White,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                channels.forEach { channel ->
                    val (now, next) = SportsGuideSchedule.nowAndNext(channel.id, atEpochMillis, programs)
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .background(GuideSurface, RoundedCornerShape(10.dp))
                            .then(if (channel.playable) Modifier.clickable { onSelectChannel(channel) } else Modifier)
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            // Only verified provider artwork should reach this model.
                            if (channel.logoUrl != null) {
                                SubcomposeAsyncImage(
                                    model = channel.logoUrl,
                                    contentDescription = channel.name,
                                    modifier = Modifier.size(width = 96.dp, height = 42.dp),
                                    error = { Text(channel.name, color = Color.White) },
                                    loading = { Text(channel.name, color = Color.White) },
                                )
                            } else {
                                Text(channel.name, color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                        Column(modifier = Modifier.weight(2f)) {
                            Text("NOW  ${now?.title ?: "Schedule unavailable"}", color = Color.White)
                            Text("NEXT  ${next?.title ?: "Schedule unavailable"}", color = Color(0xFFB1C6DB))
                            if (!channel.playable) Text("Stream unavailable", color = Color(0xFFB1C6DB))
                        }
                    }
                }
            }
        }
    }
}
