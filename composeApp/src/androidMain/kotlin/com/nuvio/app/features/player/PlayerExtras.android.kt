package com.nuvio.app.features.player

import android.content.Context
import android.content.SharedPreferences
import android.database.ContentObserver
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.nuvio.app.core.storage.ProfileScopedKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal actual object PlayerExtrasStorage {
    private const val preferencesName = "kk_player_extras"
    private const val payloadKey = "player_extras_payload"
    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    actual fun loadPayload(): String? =
        preferences?.getString(ProfileScopedKey.of(payloadKey), null)

    actual fun savePayload(payload: String) {
        preferences?.edit()?.putString(ProfileScopedKey.of(payloadKey), payload)?.apply()
    }
}

/**
 * Reads frames with Android's MediaMetadataRetriever. It works for progressive files (MP4, MKV,
 * WebM) over http(s) and local files; HLS/DASH and torrents return nothing and the seek bar just
 * shows no preview.
 */
private class RetrieverScrubThumbnailLoader(
    private val source: ScrubThumbnailSource,
) : ScrubThumbnailLoader {
    private val mutex = Mutex()
    private var retriever: MediaMetadataRetriever? = null
    private var failed = false
    private val cache = LinkedHashMap<Long, ImageBitmap>(32, 0.75f, true)

    override suspend fun frameAt(positionMs: Long): ImageBitmap? = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (failed) return@withLock null
            val bucket = positionMs / BUCKET_MS
            cache[bucket]?.let { return@withLock it }
            val mmr = retriever ?: openRetriever() ?: return@withLock null
            val timeUs = bucket * BUCKET_MS * 1_000L
            val bitmap = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    mmr.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, WIDTH, HEIGHT)
                } else {
                    mmr.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?.let { Bitmap.createScaledBitmap(it, WIDTH, HEIGHT, true) }
                }
            }.getOrNull() ?: return@withLock null
            val image = bitmap.asImageBitmap()
            cache[bucket] = image
            if (cache.size > MAX_CACHED) cache.remove(cache.keys.first())
            image
        }
    }

    private fun openRetriever(): MediaMetadataRetriever? {
        val url = source.url
        val supported = url.startsWith("http://") || url.startsWith("https://") ||
            url.startsWith("file:") || url.startsWith("/")
        val streaming = url.contains(".m3u8", ignoreCase = true) || url.contains(".mpd", ignoreCase = true)
        if (!supported || streaming) {
            failed = true
            return null
        }
        return runCatching {
            MediaMetadataRetriever().apply {
                when {
                    url.startsWith("file:") -> setDataSource(url.removePrefix("file://"))
                    url.startsWith("/") -> setDataSource(url)
                    else -> setDataSource(url, source.headers)
                }
            }
        }.onFailure { failed = true }.getOrNull()?.also { retriever = it }
    }

    fun release() {
        runCatching { retriever?.release() }
        retriever = null
        cache.clear()
    }

    private companion object {
        const val BUCKET_MS = 2_000L
        const val WIDTH = 320
        const val HEIGHT = 180
        const val MAX_CACHED = 60
    }
}

@Composable
internal actual fun rememberScrubThumbnailLoader(source: ScrubThumbnailSource?): ScrubThumbnailLoader? {
    if (source == null) return null
    val loader = remember(source.url) { RetrieverScrubThumbnailLoader(source) }
    DisposableEffect(loader) {
        onDispose { loader.release() }
    }
    return loader
}

@Composable
internal actual fun rememberMediaVolumeMuted(): State<Boolean> {
    val context = LocalContext.current
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }
    val state = remember { mutableStateOf(audioManager.isMediaMuted()) }
    DisposableEffect(audioManager) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                state.value = audioManager.isMediaMuted()
            }
        }
        context.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    return state
}

private fun AudioManager?.isMediaMuted(): Boolean {
    if (this == null) return false
    val volume = getStreamVolume(AudioManager.STREAM_MUSIC)
    val muted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && isStreamMute(AudioManager.STREAM_MUSIC)
    return muted || volume == 0
}
