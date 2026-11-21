package com.nuvio.app.desktop.player

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

/**
 * One mpv player for the Windows app.
 *
 * mpv decodes and renders each frame in software (mpv's "sw" render API) into
 * memory at the size of the player area; the frame is published as a Skia
 * [Image] that Compose draws under the regular player controls. mpv also draws
 * subtitles into the frame. Audio goes straight to Windows (WASAPI).
 */
internal class DesktopMpvPlayer private constructor(
    private val lib: LibMpv,
    private val handle: Pointer,
) {
    data class TrackInfo(
        val index: Int,
        val id: Int,
        val title: String,
        val lang: String,
        val selected: Boolean,
    )

    // ---- playback state (read by the UI poller) ----
    @Volatile var isFileLoaded = false; private set
    @Volatile var isLoading = true; private set
    @Volatile var isPlaying = false; private set
    @Volatile var isEnded = false; private set
    @Volatile var durationMs = 0L; private set
    @Volatile var positionMs = 0L; private set
    @Volatile var bufferedMs = 0L; private set
    @Volatile var speed = 1f; private set
    @Volatile var audioTracks: List<TrackInfo> = emptyList(); private set
    @Volatile var subtitleTracks: List<TrackInfo> = emptyList(); private set

    private val errorLock = Any()
    private val recentLogs = ArrayDeque<String>()
    private var errorMessage: String? = null

    // ---- video frames ----
    private val _frame = MutableStateFlow<Image?>(null)
    val frame: StateFlow<Image?> = _frame.asStateFlow()
    private val retiredFrames = ArrayDeque<Image>()

    @Volatile private var targetWidth = 0
    @Volatile private var targetHeight = 0
    @Volatile private var running = true
    private val renderSignal = Semaphore(0)
    private var renderContext: Pointer? = null
    // Held so the JVM never collects the callback mpv calls into.
    private val updateCallback = LibMpv.RenderUpdateCallback { renderSignal.release() }

    private var activeHeaders: Map<String, String> = emptyMap()
    private var startupSubtitles: List<StartupSubtitle> = emptyList()
    private var pendingSubtitleUrl: String? = null
    private var preferredAudioLanguages: List<String> = emptyList()

    data class StartupSubtitle(val url: String, val title: String?, val language: String?, val headers: Map<String, String>)

    // ---------- setup ----------

    private fun start() {
        setOption("vo", "libmpv")
        setOption("hwdec", "auto-copy")
        setOption("keep-open", "yes")
        setOption("idle", "yes")
        setOption("osc", "no")
        setOption("input-default-bindings", "no")
        setOption("input-vo-keyboard", "no")
        setOption("terminal", "no")
        setOption("config", "no")
        setOption("ytdl", "no")
        setOption("audio-fallback-to-null", "yes")
        setOption("subs-match-os-language", "yes")
        setOption("subs-fallback", "yes")
        setOption("cache", "yes")
        setOption("demuxer-max-bytes", "150MiB")
        setOption("demuxer-max-back-bytes", "50MiB")
        setOption("network-timeout", "30")
        setOption("sub-font", "Segoe UI")
        check(lib.mpv_initialize(handle) >= 0) { "mpv could not start" }
        lib.mpv_request_log_messages(handle, "warn")

        for (name in listOf("pause", "paused-for-cache", "core-idle", "eof-reached", "seeking", "track-list", "aid", "sid")) {
            lib.mpv_observe_property(handle, 0, name, 0)
        }

        createRenderContext()
        thread(name = "kk-mpv-events", isDaemon = true) { eventLoop() }
        thread(name = "kk-mpv-render", isDaemon = true, priority = Thread.MAX_PRIORITY - 1) { renderLoop() }
    }

    private fun createRenderContext() {
        val apiType = Memory(8).apply { setString(0, "sw") }
        val params = Memory(16L * 2).apply {
            clear()
            setInt(0, LibMpv.RENDER_PARAM_API_TYPE)
            setPointer(8, apiType)
            setInt(16, LibMpv.RENDER_PARAM_INVALID)
        }
        val out = PointerByReference()
        val status = lib.mpv_render_context_create(out, handle, params)
        check(status >= 0) { "mpv video output failed: ${lib.mpv_error_string(status)}" }
        val context = out.value
        renderContext = context
        lib.mpv_render_context_set_update_callback(context, updateCallback, null)
    }

    // ---------- rendering ----------

    /** The player area's size in pixels; frames are rendered at this size. */
    fun setViewportSize(width: Int, height: Int) {
        val w = width.coerceIn(0, MAX_WIDTH)
        val h = height.coerceIn(0, MAX_HEIGHT)
        if (w != targetWidth || h != targetHeight) {
            targetWidth = w
            targetHeight = h
            renderSignal.release()
        }
    }

    private fun renderLoop() {
        var buffer: Memory? = null
        var bytes = ByteArray(0)
        var renderedWidth = 0
        var renderedHeight = 0
        val size = Memory(8)
        val format = Memory(8).apply { setString(0, "bgr0") }
        val stride = Memory(Native.SIZE_T_SIZE.toLong())
        val params = Memory(16L * 5)

        while (running) {
            renderSignal.tryAcquire(100, TimeUnit.MILLISECONDS)
            renderSignal.drainPermits()
            val context = renderContext ?: continue
            if (!running) break
            val flags = lib.mpv_render_context_update(context)
            val w = targetWidth
            val h = targetHeight
            if (w < 2 || h < 2) continue
            val sizeChanged = w != renderedWidth || h != renderedHeight
            if (flags and LibMpv.RENDER_UPDATE_FRAME == 0L && !sizeChanged) continue

            val rowBytes = ((w * 4 + 63) / 64) * 64
            val length = rowBytes.toLong() * h
            if (buffer == null || buffer.size() < length + 64) {
                buffer = Memory(length + 64)
            }
            val aligned = buffer.align(64)
            size.setInt(0, w)
            size.setInt(4, h)
            if (Native.SIZE_T_SIZE == 8) stride.setLong(0, rowBytes.toLong()) else stride.setInt(0, rowBytes)
            params.clear()
            params.setInt(0, LibMpv.RENDER_PARAM_SW_SIZE); params.setPointer(8, size)
            params.setInt(16, LibMpv.RENDER_PARAM_SW_FORMAT); params.setPointer(24, format)
            params.setInt(32, LibMpv.RENDER_PARAM_SW_STRIDE); params.setPointer(40, stride)
            params.setInt(48, LibMpv.RENDER_PARAM_SW_POINTER); params.setPointer(56, aligned)
            params.setInt(64, LibMpv.RENDER_PARAM_INVALID)
            if (lib.mpv_render_context_render(context, params) < 0) continue
            renderedWidth = w
            renderedHeight = h

            if (bytes.size != length.toInt()) bytes = ByteArray(length.toInt())
            aligned.read(0, bytes, 0, bytes.size)
            val image = runCatching {
                Image.makeRaster(ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.OPAQUE), bytes, rowBytes)
            }.getOrNull() ?: continue
            publish(image)
        }
    }

    private fun publish(image: Image) {
        val previous = _frame.value
        _frame.value = image
        if (previous != null) {
            synchronized(retiredFrames) {
                retiredFrames.addLast(previous)
                // Keep a few old frames alive: the UI may still be drawing them.
                while (retiredFrames.size > 3) retiredFrames.removeFirst().close()
            }
        }
    }

    // ---------- events ----------

    private fun eventLoop() {
        while (running) {
            val event = runCatching { lib.mpv_wait_event(handle, 0.5) }.getOrNull() ?: continue
            val id = event.getInt(0)
            val data = event.getPointer(16)
            when (id) {
                LibMpv.EVENT_NONE -> Unit
                LibMpv.EVENT_SHUTDOWN -> return
                LibMpv.EVENT_PROPERTY_CHANGE -> updateState()
                LibMpv.EVENT_FILE_LOADED -> onFileLoaded()
                LibMpv.EVENT_PLAYBACK_RESTART -> updateState()
                LibMpv.EVENT_END_FILE -> if (data != null) {
                    val reason = data.getInt(0)
                    val error = data.getInt(4)
                    if (reason == LibMpv.END_FILE_REASON_ERROR) {
                        setPlaybackError("[mpv] ${lib.mpv_error_string(error)}")
                    }
                }
                LibMpv.EVENT_LOG_MESSAGE -> if (data != null) {
                    val prefix = data.getPointer(0)?.getString(0).orEmpty()
                    val level = data.getPointer(8)?.getString(0).orEmpty()
                    val text = data.getPointer(16)?.getString(0).orEmpty()
                    appendLog(prefix, level, text)
                }
            }
        }
    }

    private fun onFileLoaded() {
        clearPlaybackError()
        isLoading = false
        isFileLoaded = true
        val subtitles = startupSubtitles
        startupSubtitles = emptyList()
        subtitles.forEach { addSubtitle(it, mode = "auto") }
        pendingSubtitleUrl?.let {
            pendingSubtitleUrl = null
            addSubtitleUrl(it)
        }
        updateState()
    }

    // ---------- playback API ----------

    fun load(videoUrl: String, audioUrl: String?, headers: Map<String, String>, subtitles: List<StartupSubtitle>) {
        clearPlaybackError()
        activeHeaders = headers
        applyRequestHeaders(headers)
        isLoading = true
        isEnded = false
        isFileLoaded = false
        applyAudioLanguagePreferences(preferredAudioLanguages)
        startupSubtitles = subtitles
        command("loadfile", videoUrl, "replace")
        if (!audioUrl.isNullOrBlank()) {
            thread(isDaemon = true) {
                Thread.sleep(200)
                command("audio-add", audioUrl, "select")
            }
        }
    }

    fun play() {
        setProperty("pause", "no")
        isPlaying = true
    }

    fun pause() {
        setProperty("pause", "yes")
        isPlaying = false
    }

    fun seekTo(positionMs: Long) = command("seek", seconds(positionMs), "absolute")

    fun seekBy(offsetMs: Long) = command("seek", seconds(offsetMs), "relative")

    fun retry() {
        val path = getString("path") ?: return
        clearPlaybackError()
        applyRequestHeaders(activeHeaders)
        val position = getDouble("time-pos")
        isFileLoaded = false
        command("loadfile", path, "replace")
        thread(isDaemon = true) {
            Thread.sleep(500)
            command("seek", String.format(Locale.US, "%.3f", position), "absolute")
        }
    }

    fun setSpeed(value: Float) = setProperty("speed", String.format(Locale.US, "%.3f", value.toDouble()))

    fun setMuted(muted: Boolean) = setProperty("mute", if (muted) "yes" else "no")

    /** 0 = fit, 1 = fill, 2 = zoom. */
    fun setResizeMode(mode: Int) = setProperty("panscan", if (mode == 0) "0.0" else "1.0")

    fun setVideoTuning(hardwareDecoding: Boolean, deband: Boolean, brightness: Int, contrast: Int, saturation: Int, gamma: Int) {
        setProperty("hwdec", if (hardwareDecoding) "auto-copy" else "no")
        setProperty("deband", if (deband) "yes" else "no")
        setProperty("brightness", brightness.coerceIn(-100, 100).toString())
        setProperty("contrast", contrast.coerceIn(-100, 100).toString())
        setProperty("saturation", saturation.coerceIn(-100, 100).toString())
        setProperty("gamma", gamma.coerceIn(-100, 100).toString())
    }

    fun selectAudio(trackId: Int) = setProperty("aid", trackId.toString())

    fun applyAudioLanguagePreferences(languages: List<String>) {
        preferredAudioLanguages = languages
        setProperty("alang", languages.joinToString(","))
        setProperty("aid", "auto")
    }

    fun selectSubtitle(trackId: Int) = setProperty("sid", if (trackId < 0) "no" else trackId.toString())

    fun addSubtitleUrl(url: String) {
        if (!isFileLoaded) {
            pendingSubtitleUrl = url
            return
        }
        command("sub-add", url, "select")
    }

    private fun addSubtitle(subtitle: StartupSubtitle, mode: String) {
        val extra = subtitle.headers
        if (extra.isNotEmpty()) applyRequestHeaders(activeHeaders + extra)
        command("sub-add", subtitle.url, mode, subtitle.title ?: subtitle.language ?: "", subtitle.language ?: "")
        if (extra.isNotEmpty()) applyRequestHeaders(activeHeaders)
    }

    fun removeExternalSubtitles(selectAfter: Int? = null) {
        pendingSubtitleUrl = null
        val count = getInt("track-list/count")
        for (i in count - 1 downTo 0) {
            if (getString("track-list/$i/type") == "sub" && getFlag("track-list/$i/external")) {
                command("sub-remove", getInt("track-list/$i/id").toString())
            }
        }
        if (selectAfter != null && selectAfter >= 0) selectSubtitle(selectAfter) else setProperty("sid", "no")
    }

    fun setSubtitleDelayMs(delayMs: Int) =
        setProperty("sub-delay", String.format(Locale.US, "%.3f", delayMs.coerceIn(-60_000, 60_000) / 1000.0))

    fun applySubtitleStyle(
        textColor: String,
        backgroundColor: String,
        outlineColor: String,
        outlineSize: Float,
        bold: Boolean,
        fontSize: Float,
        subPos: Int,
        stripSdh: Boolean,
    ) {
        setProperty("sub-ass-override", "no")
        setProperty("sub-color", textColor)
        setProperty("sub-back-color", backgroundColor)
        setProperty("sub-outline-color", outlineColor)
        setProperty("sub-border-style", if (backgroundColor.startsWith("#00")) "outline-and-shadow" else "opaque-box")
        setProperty("sub-bold", if (bold) "yes" else "no")
        setProperty("sub-outline-size", String.format(Locale.US, "%.2f", outlineSize.toDouble()))
        setProperty("sub-font-size", String.format(Locale.US, "%.1f", fontSize.toDouble()))
        setProperty("sub-pos", subPos.toString())
        setProperty("sub-filter-sdh", if (stripSdh) "yes" else "no")
        setProperty("sub-filter-sdh-harder", if (stripSdh) "yes" else "no")
    }

    fun errorMessage(): String? = synchronized(errorLock) { errorMessage }

    fun getString(name: String): String? {
        val result = runCatching { lib.mpv_get_property_string(handle, name) }.getOrNull() ?: return null
        return try {
            result.getString(0)
        } finally {
            lib.mpv_free(result)
        }
    }

    fun destroy() {
        if (!running) return
        running = false
        renderSignal.release()
        renderContext?.let { context ->
            lib.mpv_render_context_set_update_callback(context, null, null)
            lib.mpv_render_context_free(context)
        }
        renderContext = null
        lib.mpv_terminate_destroy(handle)
        val last = _frame.value
        _frame.value = null
        synchronized(retiredFrames) {
            retiredFrames.forEach { it.close() }
            retiredFrames.clear()
        }
        last?.close()
    }

    // ---------- state ----------

    /** Cheap scalar refresh, called by the UI poller every 250 ms. */
    fun refreshPlaybackState() {
        if (!running) return
        val duration = getDouble("duration")
        val position = getDouble("time-pos")
        val cached = getDouble("demuxer-cache-time")
        val currentSpeed = getDouble("speed")
        val paused = getFlag("pause")
        val eof = getFlag("eof-reached")
        val idle = getFlag("core-idle")
        val seeking = getFlag("seeking")
        val buffering = getFlag("paused-for-cache")

        isLoading = (idle && !paused && !eof) || seeking || buffering || !isFileLoaded
        isPlaying = !paused && !idle && !eof
        isEnded = eof
        durationMs = (duration * 1000).toLong()
        positionMs = (max(position, 0.0) * 1000).toLong()
        bufferedMs = (max(position + cached, 0.0) * 1000).toLong()
        speed = if (currentSpeed > 0) currentSpeed.toFloat() else 1f
    }

    private fun updateState() {
        refreshPlaybackState()
        refreshTracks()
    }

    private fun refreshTracks() {
        if (!running) return
        val audio = mutableListOf<TrackInfo>()
        val subs = mutableListOf<TrackInfo>()
        val count = getInt("track-list/count")
        for (i in 0 until count) {
            val type = getString("track-list/$i/type").orEmpty()
            if (type != "audio" && type != "sub") continue
            val index = if (type == "audio") audio.size else subs.size
            val lang = trackString(i, "lang")
            val info = TrackInfo(
                index = index,
                id = getInt("track-list/$i/id"),
                title = formatTrackTitle(
                    type = type,
                    index = index,
                    title = trackString(i, "title"),
                    lang = lang,
                    codec = trackString(i, "codec").ifBlank { trackString(i, "decoder-desc") },
                    channels = trackString(i, "demux-channels"),
                    channelCount = getInt("track-list/$i/demux-channel-count"),
                ),
                lang = lang,
                selected = getFlag("track-list/$i/selected"),
            )
            if (type == "audio") audio += info else subs += info
        }
        audioTracks = audio
        subtitleTracks = subs
    }

    private fun trackString(index: Int, field: String) = getString("track-list/$index/$field").orEmpty().trim()

    private fun formatTrackTitle(
        type: String,
        index: Int,
        title: String,
        lang: String,
        codec: String,
        channels: String,
        channelCount: Int,
    ): String {
        val language = lang.takeIf { it.isNotBlank() }?.let { code ->
            Locale.forLanguageTag(code).getDisplayLanguage(Locale.getDefault()).takeIf { it.isNotBlank() && it != code } ?: code
        }
        val base = title.takeIf { it.isNotBlank() } ?: language ?: if (type == "sub") "Subtitle ${index + 1}" else "Track ${index + 1}"
        val channelName = if (type != "audio") null else when {
            channels.isNotBlank() && channels != "unknown" -> when (channels.lowercase()) {
                "mono" -> "Mono"
                "stereo" -> "Stereo"
                else -> channels
            }
            channelCount == 1 -> "Mono"
            channelCount == 2 -> "Stereo"
            channelCount == 6 -> "5.1"
            channelCount == 8 -> "7.1"
            channelCount > 0 -> "${channelCount}ch"
            else -> null
        }
        val details = listOfNotNull(channelName, codecName(codec)).filterNot { base.contains(it, ignoreCase = true) }
        return if (details.isEmpty()) base else "$base (${details.joinToString(", ")})"
    }

    private fun codecName(raw: String): String? {
        if (raw.isBlank()) return null
        val codec = raw.lowercase()
        return when {
            "eac3" in codec || "e-ac-3" in codec -> if ("joc" in codec || "atmos" in codec) "E-AC-3-JOC" else "E-AC-3"
            "truehd" in codec -> "TrueHD"
            "ac3" in codec || "ac-3" in codec -> "AC-3"
            "dts-hd" in codec || "dtshd" in codec -> "DTS-HD"
            "dts" in codec || codec == "dca" -> "DTS"
            "aac" in codec -> "AAC"
            "mp3" in codec -> "MP3"
            "opus" in codec -> "Opus"
            "vorbis" in codec -> "Vorbis"
            "flac" in codec -> "FLAC"
            "pcm" in codec -> "WAV"
            "pgs" in codec || "hdmv" in codec -> "PGS"
            "subrip" in codec || codec == "srt" -> "SRT"
            "ass" in codec || "ssa" in codec -> "SSA"
            "webvtt" in codec || codec == "vtt" -> "VTT"
            "mov_text" in codec -> "TX3G"
            "dvb" in codec -> "DVB"
            else -> raw
        }
    }

    // ---------- errors ----------

    private fun clearPlaybackError() = synchronized(errorLock) {
        recentLogs.clear()
        errorMessage = null
    }

    private fun appendLog(prefix: String, level: String, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || level !in setOf("warn", "error", "fatal")) return
        synchronized(errorLock) {
            recentLogs.addLast("[$prefix] $trimmed")
            while (recentLogs.size > 4) recentLogs.removeFirst()
        }
    }

    private fun setPlaybackError(fallback: String) = synchronized(errorLock) {
        val parts = recentLogs.toList().takeLast(3).toMutableList()
        if (fallback.isNotBlank() && fallback !in parts) parts += fallback
        errorMessage = if (parts.isEmpty()) "Unable to play this stream." else parts.joinToString("\n")
    }

    // ---------- mpv helpers ----------

    private fun applyRequestHeaders(headers: Map<String, String>) {
        val userAgent = headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
        setProperty("user-agent", userAgent ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) KableKillers")
        val serialized = headers
            .filterKeys { !it.equals("User-Agent", true) }
            .entries.sortedBy { it.key.lowercase() }
            .joinToString(",") { (key, value) -> "$key: ${value.replace("\\", "\\\\").replace(",", "\\,")}" }
        setProperty("http-header-fields", serialized)
    }

    private fun command(vararg args: String) {
        if (!running) return
        runCatching { lib.mpv_command(handle, arrayOf(*args, null)) }
    }

    private fun setOption(name: String, value: String) {
        lib.mpv_set_option_string(handle, name, value)
    }

    private fun setProperty(name: String, value: String) {
        if (!running) return
        runCatching { lib.mpv_set_property_string(handle, name, value) }
    }

    private val scratch = ThreadLocal.withInitial { Memory(8) }

    private fun getDouble(name: String): Double {
        if (!running) return 0.0
        val mem = scratch.get().apply { setDouble(0, 0.0) }
        return if (lib.mpv_get_property(handle, name, LibMpv.FORMAT_DOUBLE, mem) >= 0) mem.getDouble(0) else 0.0
    }

    private fun getFlag(name: String): Boolean {
        if (!running) return false
        val mem = scratch.get().apply { setLong(0, 0) }
        return lib.mpv_get_property(handle, name, LibMpv.FORMAT_FLAG, mem) >= 0 && mem.getInt(0) != 0
    }

    private fun getInt(name: String): Int {
        if (!running) return 0
        val mem = scratch.get().apply { setLong(0, 0) }
        return if (lib.mpv_get_property(handle, name, LibMpv.FORMAT_INT64, mem) >= 0) mem.getLong(0).toInt() else 0
    }

    private fun seconds(ms: Long) = String.format(Locale.US, "%.3f", ms / 1000.0)

    companion object {
        private const val MAX_WIDTH = 3840
        private const val MAX_HEIGHT = 2160

        /** Creates a player, or returns the reason libmpv couldn't start. */
        fun create(): Result<DesktopMpvPlayer> = runCatching {
            val lib = LibMpv.load() ?: error("The video player library is missing (${LibMpv.loadError})")
            val handle = lib.mpv_create() ?: error("mpv could not be created")
            DesktopMpvPlayer(lib, handle).also { it.start() }
        }

        @Suppress("unused")
        private fun clampSize(value: Int, limit: Int) = min(max(value, 0), limit)
    }
}
