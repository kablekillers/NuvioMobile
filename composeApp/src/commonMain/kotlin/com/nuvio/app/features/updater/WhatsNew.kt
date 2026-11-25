package com.nuvio.app.features.updater

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nuvio.app.core.build.AppVersionConfig
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * "What's new" box, shown once after the app is updated to a new version. Notes for each release
 * live here; when a version has none, the GitHub release notes for that version are used.
 */
internal object WhatsNewNotes {
    val byVersion: Map<String, List<String>> = mapOf(
        "0.9.3" to listOf(
            "New Live TV tab: US channels from all your add-ons in one guide, sorted into Local, Sports, News, Movies, Kids and more.",
            "All-new Music player: YouTube Music with your own library and playlists, lyrics, downloads, Party mode and a home screen widget.",
            "Sports Hub is now US sports only, with College Volleyball (men's and women's), MLS, World Cup, WWE, IndyCar and more.",
            "Game pages only show US channels: Spanish, Latin and other-country feeds are left out.",
            "Sports Streams links show up more reliably, and the Netflix loop channels are gone from game pages.",
            "The whole app was cleaned up to run faster.",
        ),
        "0.9.2" to listOf(
            "Sports Hub: new Live Events tab with live games from all your add-ons, grouped by sport.",
            "Sports Hub: new US Channels tab for ESPN, ESPN2, FS1, NFL Network, TNT and more, all links in one place.",
            "Sports Hub: search for a team, channel or event, like Packers, ESPN2 or UFC.",
            "Multi-View fixed: tap Add to Multi-View under any stream, pick a link, and watch up to four at once.",
            "Links that don't work are tested and hidden, and the best sports add-ons are listed first.",
            "Replays now show full games and Highlights show the short clips.",
            "Going back from a stream keeps the links that already loaded, so nothing reloads.",
        ),
        "0.9.0" to listOf(
            "Fixed: each profile has its own Continue Watching, watched marks and Library again. Profile 1 syncs to the cloud; other profiles stay on this phone.",
        ),
        "0.8.9" to listOf(
            "Faster seek previews: thumbnails are made in the background while you watch, so dragging the seek bar shows a picture right away.",
            "Seek previews now also show when you swipe left or right on the video.",
        ),
        "0.8.8" to listOf(
            "New bottom tabs: Sports, Music and YouTube are now one tap away.",
            "All-new Music: today's biggest hits, genres, trending playlists and live radio, with a mini player and a Now Playing screen.",
            "New YouTube tab: trending videos by category, search, follow your favorite channels and see your watch history.",
            "Sports Hub: NFL Channels for NFL RedZone and NFL Network, plus Live Now and Upcoming across all sports.",
            "Player: Night mode evens out loud and quiet audio. Find it in the audio panel.",
            "Player: Sync by ear fixes out-of-sync subtitles. Tap Heard and Saw in the subtitle panel.",
            "Player: preview pictures while you drag the seek bar.",
            "New in Settings → Playback → Audio: subtitles on mute and audio passthrough for soundbars.",
        ),
    )
}

internal expect object WhatsNewStorage {
    fun lastSeenVersion(): String?
    fun setLastSeenVersion(version: String)
    /** True when this install has been updated at least once (not a fresh install). */
    fun wasUpdatedSinceInstall(): Boolean
}

/** Shows the "What's new" box once per version, after an update. */
@Composable
fun WhatsNewHost() {
    val version = AppVersionConfig.VERSION_NAME
    var notes by remember { mutableStateOf<List<String>?>(null) }

    LaunchedEffect(version) {
        val lastSeen = runCatching { WhatsNewStorage.lastSeenVersion() }.getOrNull()
        if (lastSeen == version) return@LaunchedEffect
        // Test builds install fresh, so they show the box once to make it testable.
        val isUpdate = lastSeen != null ||
            runCatching { WhatsNewStorage.wasUpdatedSinceInstall() }.getOrDefault(false) ||
            AppUpdaterPlatform.isDebugBuild
        if (!isUpdate) {
            // Fresh install: nothing is "new" yet, so just remember this version.
            runCatching { WhatsNewStorage.setLastSeenVersion(version) }
            return@LaunchedEffect
        }
        val list = WhatsNewNotes.byVersion[version] ?: runCatching { releaseNotesFromGitHub(version) }.getOrNull().orEmpty()
        if (list.isEmpty()) {
            runCatching { WhatsNewStorage.setLastSeenVersion(version) }
        } else {
            notes = list
        }
    }

    val current = notes ?: return
    WhatsNewDialog(
        version = version,
        notes = current,
        onDismiss = {
            runCatching { WhatsNewStorage.setLastSeenVersion(version) }
            notes = null
        },
    )
}

private suspend fun releaseNotesFromGitHub(version: String): List<String> {
    val body = httpGetTextWithHeaders(
        url = "https://api.github.com/repos/kablekillers/NuvioMobile/releases/tags/v$version",
        headers = mapOf("Accept" to "application/vnd.github+json"),
    )
    val text = (Json.parseToJsonElement(body) as? JsonObject)?.get("body")?.jsonPrimitive?.contentOrNull.orEmpty()
    val bullets = text.lines()
        .map { it.trim() }
        .filter { it.startsWith("-") || it.startsWith("*") || it.startsWith("•") }
        .map { it.trimStart('-', '*', '•', ' ') }
        .filter { it.isNotBlank() }
    return bullets.ifEmpty { listOfNotNull(text.trim().takeIf { it.isNotEmpty() && it.length < 600 }) }
}

private val KkBlue = Color(0xFF19A8FF)

@Composable
private fun WhatsNewDialog(version: String, notes: List<String>, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .widthIn(max = 480.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xFF0B0F18))
                    .padding(22.dp),
            ) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Brush.linearGradient(listOf(Color(0xFF0A4DFF), KkBlue)))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text("UPDATED TO $version", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
                }
                Spacer(Modifier.size(12.dp))
                Text("What's new in Kable Killers", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.size(14.dp))
                Column(
                    Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    notes.forEach { note ->
                        Row(verticalAlignment = Alignment.Top) {
                            Box(Modifier.padding(top = 7.dp).size(7.dp).clip(CircleShape).background(KkBlue))
                            Spacer(Modifier.width(12.dp))
                            Text(note, color = Color.White.copy(alpha = 0.88f), fontSize = 15.sp, lineHeight = 21.sp)
                        }
                    }
                }
                Spacer(Modifier.size(20.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(KkBlue)
                        .clickable(onClick = onDismiss)
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Got it", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
        }
    }
}
