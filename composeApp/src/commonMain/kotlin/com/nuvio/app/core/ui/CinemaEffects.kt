package com.nuvio.app.core.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Colors of the Kable Killers "Cinema Black" look. */
object CinemaGold {
    val Light = Color(0xFFFFE08A)
    val Base = Color(0xFFE8B44A)
    val Deep = Color(0xFFB57A12)
    val Soft = Color(0xFFFFD27A)
    val Ink = Color(0xFF16110A)
    val Live = Color(0xFFFF4D5E)

    val brush: Brush get() = Brush.linearGradient(listOf(Light, Base, Deep))
    val barBrush: Brush get() = Brush.horizontalGradient(listOf(Deep, Soft))
}

/** True while the Cinema Black theme is active, so its extra motion never changes other themes. */
val isCinemaBlackTheme: Boolean
    @Composable get() = MaterialTheme.appTheme == AppTheme.CINEMA_BLACK

/**
 * A soft highlight that sweeps across the content every [periodMillis], like light catching glass.
 * Reads the animation only while drawing, so it never recomposes its content.
 */
fun Modifier.cinemaShine(
    enabled: Boolean = true,
    periodMillis: Int = 5_000,
    delayMillis: Int = 0,
    strength: Float = 0.22f,
): Modifier = if (!enabled) this else composed {
    val transition = rememberInfiniteTransition(label = "cinema_shine")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = periodMillis
                0f at 0
                1f at (periodMillis * 0.45f).toInt() using FastOutSlowInEasing
                1f at periodMillis
            },
            initialStartOffset = androidx.compose.animation.core.StartOffset(delayMillis),
        ),
        label = "cinema_shine_progress",
    )
    drawWithContent {
        drawContent()
        val p = progress
        if (p <= 0f || p >= 1f) return@drawWithContent
        val band = size.width * 0.45f
        val x = -band + (size.width + band * 2f) * p
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(Color.Transparent, Color.White.copy(alpha = strength), Color.Transparent),
                start = Offset(x - band / 2f, 0f),
                end = Offset(x + band / 2f, size.height * 0.35f),
            ),
        )
    }
}

/** Springy shrink while pressed. */
fun Modifier.cinemaPressScale(interactionSource: MutableInteractionSource, pressedScale: Float = 0.94f): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = 600f),
        label = "cinema_press",
    )
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/** A live dot with an expanding ring, as on a broadcast "LIVE" badge. */
@Composable
fun CinemaPulseDot(color: Color = CinemaGold.Live, size: Dp = 7.dp, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "cinema_pulse")
    val ring by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_400, easing = LinearEasing)),
        label = "cinema_pulse_ring",
    )
    Box(
        modifier = modifier
            .size(size)
            .drawBehind {
                drawCircle(
                    color = color.copy(alpha = 0.7f * (1f - ring)),
                    radius = this.size.minDimension / 2f * (1f + 1.8f * ring),
                )
                drawCircle(color = color)
            },
    )
}

/** A gold ring whose bright edge orbits around the content, used to mark the selected item. */
fun Modifier.cinemaSpinRing(enabled: Boolean, width: Dp = 2.5.dp, periodMillis: Int = 2_400): Modifier =
    if (!enabled) this else composed {
        val transition = rememberInfiniteTransition(label = "cinema_ring")
        val angle by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(periodMillis, easing = LinearEasing)),
            label = "cinema_ring_angle",
        )
        drawBehind {
            val stroke = width.toPx()
            rotate(angle) {
                drawCircle(
                    brush = Brush.sweepGradient(
                        listOf(CinemaGold.Base, Color.Transparent, CinemaGold.Light, Color.Transparent, CinemaGold.Base),
                    ),
                    radius = size.minDimension / 2f - stroke / 2f,
                    style = Stroke(stroke),
                )
            }
        }
    }

/** Gentle repeating breathing alpha, for background glows. */
@Composable
fun rememberCinemaBreath(periodMillis: Int = 4_000, delayMillis: Int = 0): Float {
    val transition = rememberInfiniteTransition(label = "cinema_breath")
    val value by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(periodMillis / 2, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
            androidx.compose.animation.core.StartOffset(delayMillis),
        ),
        label = "cinema_breath_value",
    )
    return value
}

/** The gold call-to-action pill with a glow and a sweeping shine. */
@Composable
fun CinemaGoldButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(50)
    Box(
        modifier = modifier
            .cinemaPressScale(interaction)
            .shadow(14.dp, shape, ambientColor = CinemaGold.Base, spotColor = CinemaGold.Base)
            .clip(shape)
            .background(CinemaGold.brush)
            .cinemaShine(periodMillis = 3_500, strength = 0.55f)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .heightIn(min = 48.dp)
            .padding(horizontal = 28.dp, vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            leading?.invoke()
            Text(text = text, color = CinemaGold.Ink, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
        }
    }
}

/** Small pill with a pulsing dot, e.g. "3 LIVE". */
@Composable
fun CinemaLivePill(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(CinemaGold.Live.copy(alpha = 0.14f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CinemaPulseDot()
        Text(text = text, color = Color(0xFFFF8A94), fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
    }
}
