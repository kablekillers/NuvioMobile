package com.nuvio.app.features.updater

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nuvio.app.features.addons.httpRequestRaw
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One note from Kable Killers to every customer (mobile-messages.json in the store repo). */
@Serializable
internal data class BoardMessage(
    val id: String = "",
    val title: String = "",
    val body: String = "",
    /** "info" (blue) or "alert" (red). */
    val level: String = "info",
    /** ISO date/time after which the note is no longer shown, e.g. "2026-12-01" or "2026-12-01T06:00:00Z". */
    val expires: String? = null,
)

@Serializable
private data class BoardFile(val messages: List<BoardMessage> = emptyList())

/**
 * The message board: notes posted in the Kable Killers store repo show up for every customer
 * the next time the app is opened (checked again every 30 minutes while it's open). Each note
 * shows once; "Got it" hides it for good.
 */
internal object MessageBoard {
    const val URL = "https://raw.githubusercontent.com/kablekillers/Kable-Killers-Store/main/mobile-messages.json"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    suspend fun fetch(): List<BoardMessage> {
        // A changing query skips GitHub's 5-minute cache, so a new note shows on the next open.
        val minute = kotlin.time.Clock.System.now().toEpochMilliseconds() / 60_000L
        val response = httpRequestRaw(method = "GET", url = "$URL?t=$minute", headers = mapOf("Cache-Control" to "no-cache"), body = "")
        if (response.status !in 200..299) return emptyList()
        val now = kotlin.time.Clock.System.now()
        return json.decodeFromString(BoardFile.serializer(), response.body).messages
            .filter { it.id.isNotBlank() && (it.title.isNotBlank() || it.body.isNotBlank()) }
            .filter { message ->
                val until = message.expires?.trim()?.takeIf { it.isNotEmpty() } ?: return@filter true
                val instant = runCatching { kotlin.time.Instant.parse(if ('T' in until) until else "${until}T23:59:59Z") }.getOrNull()
                instant == null || instant > now
            }
    }
}

@Composable
fun MessageBoardHost() {
    var showing by remember { mutableStateOf<List<BoardMessage>>(emptyList()) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        delay(2_500)
        var lastCheck = 0L
        // Every time the app comes back to the screen (at most once a minute), and every 30 minutes.
        kotlinx.coroutines.flow.merge(
            lifecycle.currentStateFlow,
            kotlinx.coroutines.flow.flow { while (true) { delay(30 * 60_000L); emit(androidx.lifecycle.Lifecycle.State.RESUMED) } },
        ).collect { state ->
            if (state != androidx.lifecycle.Lifecycle.State.RESUMED) return@collect
            val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
            if (now - lastCheck < 60_000L) return@collect
            lastCheck = now
            val dismissed = runCatching { WhatsNewStorage.dismissedMessages() }.getOrDefault(emptySet())
            val fresh = runCatching { MessageBoard.fetch() }.getOrDefault(emptyList()).filter { it.id !in dismissed }
            if (fresh.isNotEmpty() && showing.isEmpty()) showing = fresh
        }
    }
    if (showing.isEmpty()) return
    MessageBoardDialog(showing) {
        val ids = showing.map { it.id }.toSet()
        runCatching {
            // Keep the list small: only ids still on the board plus the ones just closed.
            WhatsNewStorage.setDismissedMessages((WhatsNewStorage.dismissedMessages() + ids).toList().takeLast(60).toSet())
        }
        showing = emptyList()
    }
}

@Composable
private fun MessageBoardDialog(messages: List<BoardMessage>, onDismiss: () -> Unit) {
    val alert = messages.any { it.level.equals("alert", ignoreCase = true) }
    val accent = if (alert) Color(0xFFFF5A5F) else Color(0xFF19A8FF)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
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
                    Modifier.clip(RoundedCornerShape(10.dp)).background(accent).padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        if (alert) "IMPORTANT · KABLE KILLERS" else "MESSAGE FROM KABLE KILLERS",
                        color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp,
                    )
                }
                Spacer(Modifier.size(14.dp))
                Column(
                    Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    messages.forEach { message ->
                        Column {
                            if (message.title.isNotBlank()) {
                                Text(message.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                                Spacer(Modifier.size(6.dp))
                            }
                            if (message.body.isNotBlank()) {
                                Text(message.body, color = Color.White.copy(alpha = 0.88f), fontSize = 15.sp, lineHeight = 21.sp)
                            }
                        }
                    }
                }
                Spacer(Modifier.size(20.dp))
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(accent)
                        .clickable(onClick = onDismiss).padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Got it", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
        }
    }
}
