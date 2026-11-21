package com.nuvio.app.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.storage.DesktopDefaults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Update checks for Kable Killers on Windows. Windows releases live on the
 * NuvioMobile repo as tags "windows-vX.Y.Z" (never marked latest, so the phone
 * updater ignores them). A newer version is offered at startup; "Update" downloads
 * the MSI and lets Windows install it over the current version.
 */
object DesktopUpdater {
    private const val RELEASES_URL = "https://api.github.com/repos/kablekillers/NuvioMobile/releases?per_page=40"
    private const val TAG_PREFIX = "windows-v"
    private const val SKIPPED_KEY = "kk_windows_skipped_update"

    data class Update(val version: String, val notes: String, val msiUrl: String, val msiName: String)

    sealed interface State {
        data object Idle : State
        data class Available(val update: Update) : State
        data class Downloading(val update: Update, val progress: Float?) : State
        data class Failed(val update: Update, val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state = _state.asStateFlow()

    private val client = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(20))
        .build()

    /** Installed version (set by the Windows installer); null when run from a dev build. */
    val currentVersion: String? get() = System.getProperty("jpackage.app-version")?.takeIf { it.isNotBlank() }

    suspend fun check() = withContext(Dispatchers.IO) {
        val installed = currentVersion ?: return@withContext
        runCatching {
            val request = HttpRequest.newBuilder(URI(RELEASES_URL))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "KableKillers-Windows/$installed")
                .timeout(Duration.ofSeconds(20))
                .build()
            val body = client.send(request, HttpResponse.BodyHandlers.ofString()).body()
            val newest = Json.parseToJsonElement(body).jsonArray
                .mapNotNull { it as? JsonObject }
                .filterNot { it["draft"]?.jsonPrimitive?.boolean == true || it["prerelease"]?.jsonPrimitive?.boolean == true }
                .mapNotNull { release ->
                    val tag = release["tag_name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    if (!tag.startsWith(TAG_PREFIX)) return@mapNotNull null
                    val msi = (release["assets"] as? JsonArray).orEmpty()
                        .map { it.jsonObject }
                        .firstOrNull { it["name"]?.jsonPrimitive?.content?.endsWith(".msi", true) == true }
                        ?: return@mapNotNull null
                    Update(
                        version = tag.removePrefix(TAG_PREFIX),
                        notes = release["body"]?.jsonPrimitive?.contentOrNull.orEmpty().take(600),
                        msiUrl = msi["browser_download_url"]!!.jsonPrimitive.content,
                        msiName = msi["name"]!!.jsonPrimitive.content,
                    )
                }
                .maxWithOrNull { a, b -> compareVersions(a.version, b.version) }
                ?: return@runCatching
            if (compareVersions(newest.version, installed) > 0 &&
                DesktopDefaults.stringForKey(SKIPPED_KEY) != newest.version
            ) {
                _state.value = State.Available(newest)
            }
        }
    }

    fun later() {
        _state.value = State.Idle
    }

    fun skip(update: Update) {
        DesktopDefaults.setObject(update.version, forKey = SKIPPED_KEY)
        _state.value = State.Idle
    }

    suspend fun install(update: Update, exitApp: () -> Unit) = withContext(Dispatchers.IO) {
        _state.value = State.Downloading(update, null)
        val result = runCatching {
            val target = File(System.getProperty("java.io.tmpdir"), update.msiName)
            val request = HttpRequest.newBuilder(URI(update.msiUrl))
                .header("User-Agent", "KableKillers-Windows")
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            check(response.statusCode() in 200..299) { "Download failed (HTTP ${response.statusCode()})" }
            val total = response.headers().firstValueAsLong("Content-Length").orElse(-1L)
            var done = 0L
            response.body().use { input ->
                target.outputStream().use { out ->
                    val buffer = ByteArray(256 * 1024)
                    var lastUpdate = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        done += read
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 200) {
                            lastUpdate = now
                            _state.value = State.Downloading(update, if (total > 0) done.toFloat() / total else null)
                        }
                    }
                }
            }
            target
        }
        result.onSuccess { msi ->
            // Windows Installer replaces this version (same upgrade code), then the app is reopened from the Start menu.
            runCatching {
                ProcessBuilder("msiexec", "/i", msi.absolutePath, "/passive", "/norestart").start()
            }.onSuccess {
                withContext(Dispatchers.Main) { exitApp() }
            }.onFailure {
                _state.value = State.Failed(update, "Couldn't start the installer: ${it.message}")
            }
        }.onFailure {
            _state.value = State.Failed(update, it.message ?: "Download failed")
        }
    }

    /** Compares "1.2.10" style versions numerically. */
    fun compareVersions(a: String, b: String): Int {
        val left = a.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val right = b.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(left.size, right.size)) {
            val diff = left.getOrElse(i) { 0 } - right.getOrElse(i) { 0 }
            if (diff != 0) return diff
        }
        return 0
    }
}

/** The update prompt drawn over the app when a new Windows version is available. */
@Composable
fun DesktopUpdatePrompt(exitApp: () -> Unit) {
    val state by DesktopUpdater.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { DesktopUpdater.check() }

    val update = when (val current = state) {
        DesktopUpdater.State.Idle -> return
        is DesktopUpdater.State.Available -> current.update
        is DesktopUpdater.State.Downloading -> current.update
        is DesktopUpdater.State.Failed -> current.update
    }
    val gold = Color(0xFFE8B84A)

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 460.dp)
                .background(Color(0xFF15130F), RoundedCornerShape(18.dp))
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Update available", color = gold, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Kable Killers ${update.version} is ready to install.",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
            if (update.notes.isNotBlank()) {
                Text(update.notes, color = Color.White.copy(alpha = 0.75f), fontSize = 14.sp)
            }
            when (val current = state) {
                is DesktopUpdater.State.Downloading -> {
                    Text("Downloading…", color = Color.White.copy(alpha = 0.75f), fontSize = 14.sp)
                    val progress = current.progress
                    if (progress != null) {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), color = gold)
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = gold)
                    }
                }
                else -> {
                    if (current is DesktopUpdater.State.Failed) {
                        Text(current.message, color = Color(0xFFFF8A80), fontSize = 13.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = { scope.launch { DesktopUpdater.install(update, exitApp) } },
                            colors = ButtonDefaults.buttonColors(containerColor = gold, contentColor = Color.Black),
                        ) { Text("Update now") }
                        TextButton(onClick = { DesktopUpdater.later() }) { Text("Later", color = Color.White) }
                        TextButton(onClick = { DesktopUpdater.skip(update) }) {
                            Text("Skip this version", color = Color.White.copy(alpha = 0.6f))
                        }
                    }
                }
            }
        }
    }
}
