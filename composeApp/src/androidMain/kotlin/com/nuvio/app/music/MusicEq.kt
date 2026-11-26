package com.nuvio.app.music

import android.content.Context
import android.content.SharedPreferences
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** A genre / listening curve: ten band gains in dB (31 Hz … 16 kHz) plus extra bass. */
data class MusicEqPreset(
    val id: String,
    val name: String,
    val bands: List<Float>,
    val bass: Float = 0f,
)

/** Everything the equalizer does to the sound. */
data class MusicEqState(
    val enabled: Boolean = false,
    val presetId: String = "flat",
    val bands: List<Float> = List(MusicEq.BAND_COUNT) { 0f },
    /** Extra bass shelf, 0 … 12 dB. */
    val bass: Float = 0f,
    /** Volume boost, 0 … 15 dB (about 5.6× louder at the top). */
    val boost: Float = 0f,
    /** The user's own curve, kept even while a preset is chosen. */
    val customBands: List<Float> = List(MusicEq.BAND_COUNT) { 0f },
    val customBass: Float = 0f,
)

/**
 * Kable Killers' music equalizer: ten bands, extra bass and a volume boost, done inside the
 * music player (so it works the same on every phone, unlike the phone's own equalizer, which
 * often has only five bands or none). A limiter keeps the boosted sound from crackling.
 */
object MusicEq {
    const val BAND_COUNT = 10
    const val BAND_MIN = -12f
    const val BAND_MAX = 12f
    const val BASS_MAX = 12f
    const val BOOST_MAX = 15f
    val frequencies = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1_000f, 2_000f, 4_000f, 8_000f, 16_000f)
    val frequencyLabels = listOf("31", "62", "125", "250", "500", "1K", "2K", "4K", "8K", "16K")

    private fun p(id: String, name: String, vararg b: Float, bass: Float = 0f) = MusicEqPreset(id, name, b.toList(), bass)

    val presets: List<MusicEqPreset> = listOf(
        p("flat", "Flat", 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
        p("bass_boost", "Bass Boost", 7f, 6f, 5f, 3f, 1f, 0f, 0f, 0f, 0f, 0f, bass = 3f),
        p("super_bass", "Super Bass", 10f, 9f, 7f, 4f, 1f, 0f, -1f, -1f, 0f, 0f, bass = 6f),
        p("bass_reducer", "Bass Reducer", -6f, -5f, -4f, -2f, -1f, 0f, 0f, 0f, 0f, 0f),
        p("treble_boost", "Treble Boost", 0f, 0f, 0f, 0f, 0f, 1f, 3f, 5f, 6f, 7f),
        p("loudness", "Loudness", 6f, 5f, 2f, 0f, -1f, -1f, 0f, 2f, 4f, 5f),
        p("vocal", "Vocal", -3f, -2f, -1f, 1f, 3f, 4f, 4f, 3f, 1f, 0f),
        p("pop", "Pop", -1f, 1f, 3f, 4f, 3f, 1f, -1f, -1f, 1f, 2f),
        p("rock", "Rock", 5f, 4f, 3f, 1f, -1f, -1f, 1f, 3f, 4f, 5f),
        p("metal", "Metal", 5f, 4f, 2f, 0f, -2f, 0f, 2f, 4f, 5f, 4f),
        p("hiphop", "Hip-Hop", 6f, 5f, 3f, 2f, -1f, -1f, 1f, 0f, 2f, 3f, bass = 2f),
        p("rnb", "R&B", 4f, 6f, 4f, 1f, -2f, -1f, 2f, 2f, 3f, 3f),
        p("rap", "Rap", 6f, 6f, 4f, 1f, -1f, 0f, 2f, 1f, 2f, 3f, bass = 3f),
        p("edm", "EDM", 6f, 5f, 2f, 0f, -1f, 1f, 0f, 2f, 5f, 6f, bass = 2f),
        p("dance", "Dance", 5f, 6f, 3f, 0f, 1f, 2f, 3f, 3f, 2f, 1f),
        p("electronic", "Electronic", 4f, 4f, 1f, 0f, -2f, 2f, 1f, 2f, 4f, 5f),
        p("country", "Country", 2f, 2f, 1f, 1f, 0f, 2f, 3f, 4f, 3f, 2f),
        p("jazz", "Jazz", 3f, 2f, 1f, 2f, -2f, -2f, 0f, 1f, 3f, 4f),
        p("blues", "Blues", 3f, 3f, 2f, 1f, -1f, 0f, 1f, 2f, 1f, 1f),
        p("classical", "Classical", 4f, 3f, 2f, 1f, -1f, -1f, 0f, 2f, 3f, 4f),
        p("acoustic", "Acoustic", 3f, 3f, 2f, 1f, 1f, 1f, 2f, 3f, 2f, 1f),
        p("gospel", "Gospel", 2f, 2f, 1f, 2f, 3f, 3f, 2f, 2f, 2f, 1f),
        p("latin", "Latin", 3f, 2f, 0f, 0f, -1f, -1f, -1f, 2f, 4f, 5f),
        p("reggae", "Reggae", 5f, 4f, 2f, 0f, -2f, 0f, 2f, 2f, 1f, 0f),
        p("piano", "Piano", 1f, 1f, 0f, 2f, 3f, 1f, 3f, 4f, 2f, 3f),
        p("lounge", "Lounge / Chill", 2f, 2f, 1f, 0f, -1f, 0f, 1f, 1f, 0f, -1f),
        p("podcast", "Podcast / Talk", -6f, -4f, -2f, 1f, 3f, 4f, 4f, 3f, 0f, -2f),
        p("late_night", "Late Night", 2f, 2f, 1f, 0f, 0f, 1f, 1f, 0f, -2f, -4f),
        p("small_speakers", "Phone Speaker", -4f, -2f, 2f, 4f, 3f, 2f, 2f, 3f, 2f, 1f),
        p("headphones", "Headphones", 4f, 3f, 1f, 0f, -1f, 0f, 1f, 2f, 3f, 2f),
        p("car", "Car", 6f, 5f, 3f, 1f, 0f, 0f, 1f, 2f, 3f, 3f, bass = 2f),
    )

    const val CUSTOM_ID = "custom"

    private val _state = MutableStateFlow(MusicEqState())
    val state: StateFlow<MusicEqState> = _state.asStateFlow()

    /** Read by the audio thread; replaced (never changed in place) on every update. */
    @Volatile
    internal var current: MusicEqState = MusicEqState()
        private set

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        val store = context.applicationContext.getSharedPreferences("kk_music_eq", Context.MODE_PRIVATE)
        prefs = store
        fun floats(key: String): List<Float> =
            store.getString(key, null)?.split(',')?.mapNotNull { it.toFloatOrNull() }
                ?.takeIf { it.size == BAND_COUNT } ?: List(BAND_COUNT) { 0f }
        set(
            MusicEqState(
                enabled = store.getBoolean("enabled", false),
                presetId = store.getString("preset", "flat") ?: "flat",
                bands = floats("bands"),
                bass = store.getFloat("bass", 0f).coerceIn(0f, BASS_MAX),
                boost = store.getFloat("boost", 0f).coerceIn(0f, BOOST_MAX),
                customBands = floats("custom_bands"),
                customBass = store.getFloat("custom_bass", 0f).coerceIn(0f, BASS_MAX),
            ),
            save = false,
        )
    }

    private fun set(value: MusicEqState, save: Boolean = true) {
        current = value
        _state.value = value
        if (!save) return
        prefs?.edit()?.apply {
            putBoolean("enabled", value.enabled)
            putString("preset", value.presetId)
            putString("bands", value.bands.joinToString(","))
            putFloat("bass", value.bass)
            putFloat("boost", value.boost)
            putString("custom_bands", value.customBands.joinToString(","))
            putFloat("custom_bass", value.customBass)
            apply()
        }
    }

    fun setEnabled(on: Boolean) = set(_state.value.copy(enabled = on))

    fun choosePreset(id: String) {
        val s = _state.value
        if (id == CUSTOM_ID) {
            set(s.copy(enabled = true, presetId = CUSTOM_ID, bands = s.customBands, bass = s.customBass))
            return
        }
        val preset = presets.firstOrNull { it.id == id } ?: return
        set(s.copy(enabled = true, presetId = id, bands = preset.bands, bass = preset.bass))
    }

    /** Moving a band (or the bass) turns the curve into the custom one. */
    fun setBand(index: Int, db: Float) {
        val s = _state.value
        val bands = s.bands.toMutableList().also { it[index] = db.coerceIn(BAND_MIN, BAND_MAX) }
        set(s.copy(enabled = true, presetId = CUSTOM_ID, bands = bands, customBands = bands, customBass = s.bass))
    }

    fun setBass(db: Float) {
        val s = _state.value
        val bass = db.coerceIn(0f, BASS_MAX)
        set(s.copy(enabled = true, presetId = CUSTOM_ID, bass = bass, customBands = s.bands, customBass = bass))
    }

    fun setBoost(db: Float) = set(_state.value.copy(enabled = true, boost = db.coerceIn(0f, BOOST_MAX)))

    fun resetCustom() {
        val s = _state.value
        val zero = List(BAND_COUNT) { 0f }
        set(s.copy(presetId = CUSTOM_ID, bands = zero, bass = 0f, customBands = zero, customBass = 0f))
    }

    fun presetName(id: String): String =
        if (id == CUSTOM_ID) "Custom" else presets.firstOrNull { it.id == id }?.name ?: "Flat"

    // ---- Filters (RBJ audio-EQ cookbook biquads) ----

    internal class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double)

    private fun peaking(fs: Double, f0: Double, db: Double, q: Double): Biquad {
        val a = 10.0.pow(db / 40.0)
        val w = 2 * PI * f0 / fs
        val alpha = sin(w) / (2 * q)
        val c = cos(w)
        val a0 = 1 + alpha / a
        return Biquad((1 + alpha * a) / a0, (-2 * c) / a0, (1 - alpha * a) / a0, (-2 * c) / a0, (1 - alpha / a) / a0)
    }

    private fun shelf(fs: Double, f0: Double, db: Double, high: Boolean): Biquad {
        val a = 10.0.pow(db / 40.0)
        val w = 2 * PI * f0 / fs
        val c = cos(w)
        val alpha = sin(w) / 2 * sqrt(2.0) // shelf slope 1
        val sa = 2 * sqrt(a) * alpha
        return if (!high) {
            val a0 = (a + 1) + (a - 1) * c + sa
            Biquad(
                a * ((a + 1) - (a - 1) * c + sa) / a0,
                2 * a * ((a - 1) - (a + 1) * c) / a0,
                a * ((a + 1) - (a - 1) * c - sa) / a0,
                -2 * ((a - 1) + (a + 1) * c) / a0,
                ((a + 1) + (a - 1) * c - sa) / a0,
            )
        } else {
            val a0 = (a + 1) - (a - 1) * c + sa
            Biquad(
                a * ((a + 1) + (a - 1) * c + sa) / a0,
                -2 * a * ((a - 1) + (a + 1) * c) / a0,
                a * ((a + 1) + (a - 1) * c - sa) / a0,
                2 * ((a - 1) - (a + 1) * c) / a0,
                ((a + 1) - (a - 1) * c - sa) / a0,
            )
        }
    }

    /** The filters for a state at a sample rate (bands at 0 dB are skipped). */
    internal fun filters(s: MusicEqState, sampleRate: Int): List<Biquad> {
        val fs = sampleRate.toDouble()
        val nyquistSafe = fs * 0.45
        val list = ArrayList<Biquad>(BAND_COUNT + 1)
        s.bands.forEachIndexed { i, db ->
            if (abs(db) < 0.05f) return@forEachIndexed
            val f = frequencies[i].toDouble().coerceAtMost(nyquistSafe)
            list += when (i) {
                0 -> shelf(fs, 45.0, db.toDouble(), high = false)
                BAND_COUNT - 1 -> shelf(fs, 11_000.0.coerceAtMost(nyquistSafe), db.toDouble(), high = true)
                else -> peaking(fs, f, db.toDouble(), 1.41)
            }
        }
        if (s.bass > 0.05f) list += shelf(fs, 90.0, s.bass.toDouble(), high = false)
        return list
    }
}

/**
 * Runs [MusicEq] on the music player's decoded audio (16-bit or float PCM). When the equalizer is
 * off it passes the sound straight through.
 */
@UnstableApi
internal class MusicEqProcessor : BaseAudioProcessor() {
    private var channels = 2
    private var sampleRate = 44_100
    private var float = false

    private var applied: MusicEqState? = null
    private var filters: Array<MusicEq.Biquad> = emptyArray()
    private var gain = 1.0
    // Per filter, per channel: x1, x2, y1, y2.
    private var z = DoubleArray(0)
    private var limiterGain = 1.0
    private var limiterRelease = 0.0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        channels = inputAudioFormat.channelCount
        sampleRate = inputAudioFormat.sampleRate
        float = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        applied = null
        // About 150 ms for the limiter to let go after a loud peak.
        limiterRelease = 1.0 - exp(-1.0 / (0.15 * sampleRate))
        return inputAudioFormat
    }

    private fun refresh(s: MusicEqState) {
        if (s === applied) return
        val old = applied
        applied = s
        val newFilters = if (s.enabled) MusicEq.filters(s, sampleRate).toTypedArray() else emptyArray()
        // Keep the filter memory when only gains moved, so turning a slider doesn't click.
        if (old == null || newFilters.size != filters.size) z = DoubleArray(newFilters.size * channels * 4)
        filters = newFilters
        gain = if (s.enabled) 10.0.pow(s.boost / 20.0) else 1.0
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val s = MusicEq.current
        refresh(s)
        val out = replaceOutputBuffer(remaining)
        val active = s.enabled && (filters.isNotEmpty() || gain != 1.0)
        if (!active) {
            out.put(inputBuffer)
            out.flip()
            limiterGain = 1.0
            return
        }
        inputBuffer.order(ByteOrder.nativeOrder())
        val bytesPerSample = if (float) 4 else 2
        val frames = remaining / (bytesPerSample * channels)
        val frame = DoubleArray(channels)
        val nf = filters.size
        for (n in 0 until frames) {
            var peak = 0.0
            for (ch in 0 until channels) {
                var x = if (float) inputBuffer.getFloat().toDouble() else inputBuffer.getShort() / 32768.0
                for (f in 0 until nf) {
                    val q = filters[f]
                    val k = (f * channels + ch) * 4
                    val y = q.b0 * x + q.b1 * z[k] + q.b2 * z[k + 1] - q.a1 * z[k + 2] - q.a2 * z[k + 3]
                    z[k + 1] = z[k]; z[k] = x
                    z[k + 3] = z[k + 2]; z[k + 2] = y
                    x = y
                }
                x *= gain
                frame[ch] = x
                val ax = abs(x)
                if (ax > peak) peak = ax
            }
            // Limiter: pull down instantly on a peak, recover smoothly, so loud never crackles.
            val ceiling = 0.97
            val needed = if (peak > ceiling) ceiling / peak else 1.0
            limiterGain = if (needed < limiterGain) needed else limiterGain + (1.0 - limiterGain) * limiterRelease
            for (ch in 0 until channels) {
                val y = (frame[ch] * limiterGain).coerceIn(-1.0, 1.0)
                if (float) out.putFloat(y.toFloat()) else out.putShort((y * 32767.0).toInt().toShort())
            }
        }
        // Any bytes that didn't make a whole frame are passed on unchanged.
        while (inputBuffer.hasRemaining()) out.put(inputBuffer.get())
        out.flip()
    }

    override fun onFlush() {
        z.fill(0.0)
        limiterGain = 1.0
    }

    override fun onReset() {
        applied = null
        filters = emptyArray()
        z = DoubleArray(0)
        limiterGain = 1.0
    }
}
