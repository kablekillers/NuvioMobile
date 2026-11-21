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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
 *
 * Once opened it fills in thumbnails across the whole video in the background (a coarse pass,
 * then a finer one), so scrubbing can show the nearest ready picture instantly. On-demand frames
 * take priority: the background work gives way after each frame.
 */
private class RetrieverScrubThumbnailLoader(
    private val source: ScrubThumbnailSource,
    private val scope: CoroutineScope,
) : ScrubThumbnailLoader {
    private val mutex = Mutex()
    private var retriever: MediaMetadataRetriever? = null
    private var failed = false
    private var durationMs = 0L
    private val cache = java.util.concurrent.ConcurrentSkipListMap<Long, ImageBitmap>()
    private var prefetchJob: Job? = null
    @Volatile private var pendingOnDemand = 0

    override fun nearest(positionMs: Long): ImageBitmap? {
        if (cache.isEmpty()) return null
        val below = cache.floorEntry(positionMs)
        val above = cache.ceilingEntry(positionMs)
        val best = listOfNotNull(below, above).minByOrNull { kotlin.math.abs(it.key - positionMs) } ?: return null
        // Only use it if it's reasonably close (within 3% of the video, at least 20 s).
        val tolerance = maxOf(20_000L, durationMs * 3 / 100)
        return best.value.takeIf { kotlin.math.abs(best.key - positionMs) <= tolerance }
    }

    override suspend fun frameAt(positionMs: Long): ImageBitmap? {
        val key = (positionMs / EXACT_BUCKET_MS) * EXACT_BUCKET_MS
        cache[key]?.let { return it }
        pendingOnDemand++
        try {
            return withContext(Dispatchers.IO) {
                mutex.withLock { decode(key) }
            }
        } finally {
            pendingOnDemand--
            startPrefetch()
        }
    }

    /** Must hold [mutex]. */
    private fun decode(key: Long): ImageBitmap? {
        if (failed) return null
        cache[key]?.let { return it }
        val mmr = retriever ?: open() ?: return null
        val bitmap = runCatching {
            val timeUs = key * 1_000L
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                mmr.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, WIDTH, HEIGHT)
            } else {
                mmr.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?.let { Bitmap.createScaledBitmap(it, WIDTH, HEIGHT, true) }
            }
        }.getOrNull() ?: return null
        // RGB_565 halves the memory of each thumbnail.
        val compact = runCatching { bitmap.copy(Bitmap.Config.RGB_565, false) }.getOrNull() ?: bitmap
        if (compact !== bitmap) bitmap.recycle()
        val image = compact.asImageBitmap()
        cache[key] = image
        while (cache.size > MAX_CACHED) cache.pollFirstEntry()
        return image
    }

    private fun startPrefetch() {
        if (prefetchJob != null || failed) return
        prefetchJob = scope.launch(Dispatchers.IO) {
            if (durationMs <= 0L) return@launch
            val coarse = (1..COARSE_STEPS).map { durationMs * it / (COARSE_STEPS + 1) }
            val fine = (1..FINE_STEPS).map { durationMs * it / (FINE_STEPS + 1) }.filter { p -> coarse.none { kotlin.math.abs(it - p) < durationMs / (FINE_STEPS * 2) } }
            for (position in coarse + fine) {
                if (!isActive || failed) break
                while (pendingOnDemand > 0 && isActive) delay(50)
                val key = (position / EXACT_BUCKET_MS) * EXACT_BUCKET_MS
                if (cache.containsKey(key)) continue
                mutex.withLock { decode(key) }
                delay(PREFETCH_PAUSE_MS)
            }
        }
    }

    /** Must hold [mutex]. */
    private fun open(): MediaMetadataRetriever? {
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
        }.onFailure { failed = true }.getOrNull()?.also { mmr ->
            retriever = mmr
            durationMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        }
    }

    fun release() {
        prefetchJob?.cancel()
        // The screen's scope is already gone here, so release on a scope of its own.
        CoroutineScope(Dispatchers.IO).launch {
            mutex.withLock {
                runCatching { retriever?.release() }
                retriever = null
                cache.clear()
            }
        }
    }

    private companion object {
        const val EXACT_BUCKET_MS = 2_000L
        const val WIDTH = 256
        const val HEIGHT = 144
        const val MAX_CACHED = 220
        const val COARSE_STEPS = 40
        const val FINE_STEPS = 160
        const val PREFETCH_PAUSE_MS = 150L
    }
}

@Composable
internal actual fun rememberScrubThumbnailLoader(source: ScrubThumbnailSource?): ScrubThumbnailLoader? {
    if (source == null) return null
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val loader = remember(source.url) { RetrieverScrubThumbnailLoader(source, scope) }
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
