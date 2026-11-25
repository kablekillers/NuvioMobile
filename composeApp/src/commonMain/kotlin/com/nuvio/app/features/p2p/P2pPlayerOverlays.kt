package com.nuvio.app.features.p2p

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.player_torrent_stats
import org.jetbrains.compose.resources.stringResource

@Composable
fun P2pLoadingStatus(
    message: String?,
    progress: Float?,
    modifier: Modifier = Modifier,
    visible: Boolean = message != null || progress != null,
) {
    if (!visible) return

    Column(
        modifier = modifier
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!message.isNullOrBlank()) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.78f),
            )
        }
        if (progress != null) {
            Box(
                modifier = Modifier
                    .width(200.dp)
                    .height(3.dp)
                    .background(
                        color = Color.White.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(2.dp),
                    ),
            ) {
                Box(
                    modifier = Modifier
                        .width(200.dp * progress.coerceIn(0f, 1f))
                        .height(3.dp)
                        .background(
                            color = Color.White.copy(alpha = 0.85f),
                            shape = RoundedCornerShape(2.dp),
                        ),
                )
            }
        }
    }
}
