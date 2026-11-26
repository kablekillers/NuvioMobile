package com.nuvio.app.music

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

private val EqGold = Color(0xFFF5B83D)
private val EqHot = Color(0xFFFF5A5F)

/** A row for Music settings that opens the equalizer. */
@Composable
internal fun MusicEqSettingsRow(onClick: () -> Unit) {
    val eq by MusicEq.state.collectAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MusicPalette.surface.copy(alpha = 0.55f))
            .border(1.dp, MusicPalette.border.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.GraphicEq, null, tint = EqGold)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Equalizer & volume boost", color = MusicPalette.text, fontFamily = MusicSans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(
                if (!eq.enabled) "Off" else buildString {
                    append(MusicEq.presetName(eq.presetId))
                    if (eq.boost > 0.05f) append(" · +${eq.boost.roundToInt()} dB boost")
                },
                color = MusicPalette.textSecondary, fontFamily = MusicSans, fontSize = 13.sp,
            )
        }
        Icon(Icons.Default.ChevronRight, null, tint = MusicPalette.textMuted)
    }
}

/** The full equalizer: presets, ten bands, extra bass and the volume boost. Changes are heard right away. */
@Composable
internal fun MusicEqualizerScreen(onClose: () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val eq by MusicEq.state.collectAsState()
        Column(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(MusicPalette.backgroundTop, MusicPalette.background)))
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            // Header
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = MusicPalette.text)
                }
                Text("Equalizer", color = MusicPalette.text, fontFamily = MusicSans, fontWeight = FontWeight.Bold, fontSize = 22.sp, modifier = Modifier.weight(1f))
                Text(if (eq.enabled) "ON" else "OFF", color = if (eq.enabled) EqGold else MusicPalette.textMuted, fontFamily = MusicSans, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = eq.enabled,
                    onCheckedChange = { MusicEq.setEnabled(it) },
                    colors = SwitchDefaults.colors(checkedTrackColor = EqGold, checkedThumbColor = Color.Black),
                    modifier = Modifier.padding(end = 8.dp),
                )
            }

            // Response curve
            EqCurve(eq, Modifier.fillMaxWidth().height(130.dp).padding(horizontal = 16.dp))

            // Presets
            Spacer(Modifier.height(14.dp))
            SectionLabel("Presets")
            val chips = remember { listOf(MusicEq.CUSTOM_ID to "Custom") + MusicEq.presets.map { it.id to it.name } }
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
            ) {
                items(chips, key = { it.first }) { (id, name) ->
                    val selected = eq.enabled && eq.presetId == id
                    Text(
                        name,
                        color = if (selected) Color.Black else MusicPalette.text,
                        fontFamily = MusicSans,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (selected) EqGold else MusicPalette.surfaceRaised)
                            .border(1.dp, if (selected) EqGold else MusicPalette.border, RoundedCornerShape(50))
                            .clickable { MusicEq.choosePreset(id) }
                            .padding(horizontal = 16.dp, vertical = 9.dp),
                    )
                }
            }

            // Bands
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth().padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("Bands", Modifier.weight(1f))
                Text(
                    "Reset",
                    color = EqGold, fontFamily = MusicSans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { MusicEq.resetCustom() }.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            Text(
                "Drag a band up or down. Double-tap a band to put it back to 0. Moving any band saves it as Custom.",
                color = MusicPalette.textMuted, fontFamily = MusicSans, fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().height(250.dp).padding(horizontal = 8.dp)) {
                eq.bands.forEachIndexed { i, db ->
                    EqBand(
                        value = db,
                        label = MusicEq.frequencyLabels[i],
                        active = eq.enabled,
                        onChange = { MusicEq.setBand(i, it) },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }

            // Extra bass
            Spacer(Modifier.height(18.dp))
            SliderBlock(
                title = "Extra bass",
                value = eq.bass,
                max = MusicEq.BASS_MAX,
                valueText = if (eq.bass < 0.05f) "Off" else "+${"%.1f".format(eq.bass)} dB",
                color = EqGold,
                onChange = { MusicEq.setBass(it) },
            )

            // Volume boost
            Spacer(Modifier.height(10.dp))
            val loud = eq.boost >= 9f
            val times = 10.0.pow(eq.boost / 20.0)
            SliderBlock(
                title = "Volume boost",
                value = eq.boost,
                max = MusicEq.BOOST_MAX,
                valueText = if (eq.boost < 0.05f) "Off" else "+${eq.boost.roundToInt()} dB · ${"%.1f".format(times)}× louder",
                color = if (loud) EqHot else EqGold,
                onChange = { MusicEq.setBoost(it) },
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background((if (loud) EqHot else MusicPalette.surface).copy(alpha = if (loud) 0.14f else 0.6f))
                    .padding(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(Icons.Default.Warning, null, tint = if (loud) EqHot else MusicPalette.textMuted, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    "Goes well past normal phone volume. A limiter stops crackling, but very loud sound can hurt your hearing and blow small speakers. Turn it down with headphones.",
                    color = if (loud) MusicPalette.text else MusicPalette.textSecondary,
                    fontFamily = MusicSans, fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        color = MusicPalette.textSecondary, fontFamily = MusicSans, fontWeight = FontWeight.Bold,
        fontSize = 12.sp, letterSpacing = 1.6.sp,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
private fun SliderBlock(title: String, value: Float, max: Float, valueText: String, color: Color, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title.uppercase(), color = MusicPalette.textSecondary, fontFamily = MusicSans, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.6.sp, modifier = Modifier.weight(1f))
            Text(valueText, color = color, fontFamily = MusicSans, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = 0f..max,
            colors = SliderDefaults.colors(thumbColor = color, activeTrackColor = color, inactiveTrackColor = MusicPalette.surfaceRaised),
        )
    }
}

/** One vertical band slider: drag to set, tap to jump, double-tap for 0 dB. */
@Composable
private fun EqBand(value: Float, label: String, active: Boolean, onChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    val range = MusicEq.BAND_MAX - MusicEq.BAND_MIN
    val color = if (active) EqGold else MusicPalette.textMuted
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            (if (value > 0.05f) "+" else "") + value.roundToInt().toString(),
            color = if (active) MusicPalette.text else MusicPalette.textMuted,
            fontFamily = MusicSans, fontWeight = FontWeight.SemiBold, fontSize = 11.sp,
        )
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { onChange(0f) },
                        onTap = { pos -> onChange(MusicEq.BAND_MAX - (pos.y / size.height) * range) },
                    )
                }
                .pointerInput(Unit) {
                    detectVerticalDragGestures { change, _ ->
                        change.consume()
                        val v = MusicEq.BAND_MAX - (change.position.y / size.height).coerceIn(0f, 1f) * range
                        onChange((v * 2).roundToInt() / 2f)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxHeight().width(28.dp)) {
                val trackW = 6.dp.toPx()
                val x = size.width / 2
                val zeroY = size.height * (MusicEq.BAND_MAX / range)
                val y = size.height * ((MusicEq.BAND_MAX - value) / range)
                drawRoundRect(
                    color = MusicPalette.surfaceRaised,
                    topLeft = Offset(x - trackW / 2, 0f),
                    size = Size(trackW, size.height),
                    cornerRadius = CornerRadius(trackW, trackW),
                )
                drawLine(MusicPalette.border, Offset(x - 9.dp.toPx(), zeroY), Offset(x + 9.dp.toPx(), zeroY), strokeWidth = 1.dp.toPx())
                drawLine(color, Offset(x, zeroY), Offset(x, y), strokeWidth = trackW, cap = StrokeCap.Round)
                drawCircle(Color.Black, radius = 10.dp.toPx(), center = Offset(x, y))
                drawCircle(color, radius = 8.dp.toPx(), center = Offset(x, y))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(label, color = MusicPalette.textSecondary, fontFamily = MusicSans, fontSize = 11.sp)
    }
}

/** The overall sound curve (what all the bands add up to), drawn across 20 Hz – 20 kHz. */
@Composable
private fun EqCurve(eq: MusicEqState, modifier: Modifier = Modifier) {
    val fs = 48_000.0
    val filters = remember(eq) { if (eq.enabled) MusicEq.filters(eq, fs.toInt()) else emptyList() }
    Canvas(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MusicPalette.surface.copy(alpha = 0.7f)),
    ) {
        val maxDb = 18.0
        val midY = size.height / 2
        // Grid
        listOf(-12.0, -6.0, 0.0, 6.0, 12.0).forEach { db ->
            val y = (midY - db / maxDb * midY).toFloat()
            drawLine(MusicPalette.border.copy(alpha = if (db == 0.0) 0.9f else 0.4f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }
        val path = Path()
        val fill = Path()
        val steps = 120
        val lo = log10(20.0)
        val hi = log10(20_000.0)
        for (s in 0..steps) {
            val f = 10.0.pow(lo + (hi - lo) * s / steps)
            val w = 2 * PI * f / fs
            var db = 0.0
            filters.forEach { q ->
                // |H(e^jw)| for a biquad.
                val c1 = cos(w); val s1 = sin(w); val c2 = cos(2 * w); val s2 = sin(2 * w)
                val nr = q.b0 + q.b1 * c1 + q.b2 * c2
                val ni = -(q.b1 * s1 + q.b2 * s2)
                val dr = 1 + q.a1 * c1 + q.a2 * c2
                val di = -(q.a1 * s1 + q.a2 * s2)
                db += 10 * log10((nr * nr + ni * ni) / (dr * dr + di * di))
            }
            val x = (size.width * s / steps)
            val y = (midY - db.coerceIn(-maxDb, maxDb) / maxDb * midY).toFloat()
            if (s == 0) { path.moveTo(x, y); fill.moveTo(x, midY); fill.lineTo(x, y) } else { path.lineTo(x, y); fill.lineTo(x, y) }
        }
        fill.lineTo(size.width, midY)
        fill.close()
        val color = if (eq.enabled) EqGold else MusicPalette.textMuted
        drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.35f), Color.Transparent)))
        drawPath(path, color, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
        if (eq.enabled && eq.boost > 0.05f) {
            // Boost meter on the right edge.
            val h = (size.height * (eq.boost / MusicEq.BOOST_MAX)).toFloat()
            drawRoundRect(
                color = if (eq.boost >= 9f) EqHot else EqGold,
                topLeft = Offset(size.width - 6.dp.toPx(), size.height - h),
                size = Size(4.dp.toPx(), h),
                cornerRadius = CornerRadius(4f, 4f),
            )
        }
    }
}
