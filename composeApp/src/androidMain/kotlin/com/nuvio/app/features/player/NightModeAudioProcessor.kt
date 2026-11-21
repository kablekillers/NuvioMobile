@file:androidx.annotation.OptIn(UnstableApi::class)

package com.nuvio.app.features.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/**
 * Night mode: a gentle feed-forward compressor with make-up gain. Loud peaks are pulled down and
 * quiet dialogue is lifted, so the volume can stay low. Like [GainAudioProcessor] it stays in the
 * chain for PCM and only copies while night mode is off, so toggling it never reconfigures the
 * audio sink. Encoded passthrough audio is left alone.
 */
internal class NightModeAudioProcessor(
    private val enabledProvider: () -> Boolean,
) : BaseAudioProcessor() {

    private var envelopeDb = -90f
    private var attackCoeff = 0f
    private var releaseCoeff = 0f
    private var channels = 2

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT,
            C.ENCODING_PCM_FLOAT -> {
                val rate = inputAudioFormat.sampleRate.coerceAtLeast(8_000).toFloat()
                channels = inputAudioFormat.channelCount.coerceAtLeast(1)
                attackCoeff = exp(-1f / (rate * ATTACK_SECONDS))
                releaseCoeff = exp(-1f / (rate * RELEASE_SECONDS))
                envelopeDb = -90f
                inputAudioFormat
            }
            else -> AudioProcessor.AudioFormat.NOT_SET
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val outputBuffer = replaceOutputBuffer(inputBuffer.remaining())
        if (!enabledProvider()) {
            outputBuffer.put(inputBuffer)
            outputBuffer.flip()
            return
        }
        inputBuffer.order(ByteOrder.nativeOrder())
        outputBuffer.order(ByteOrder.nativeOrder())
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) {
            val frameBytes = 2 * channels
            val frame = FloatArray(channels)
            while (inputBuffer.remaining() >= frameBytes) {
                for (c in 0 until channels) frame[c] = inputBuffer.short / 32768f
                val gain = frameGain(frame)
                for (c in 0 until channels) {
                    val v = (frame[c] * gain).coerceIn(-1f, 1f)
                    outputBuffer.putShort((v * 32767f).toInt().toShort())
                }
            }
        } else {
            val frameBytes = 4 * channels
            val frame = FloatArray(channels)
            while (inputBuffer.remaining() >= frameBytes) {
                for (c in 0 until channels) frame[c] = inputBuffer.float
                val gain = frameGain(frame)
                for (c in 0 until channels) outputBuffer.putFloat((frame[c] * gain).coerceIn(-1f, 1f))
            }
        }
        if (inputBuffer.hasRemaining()) outputBuffer.put(inputBuffer)
        outputBuffer.flip()
    }

    /** Linear gain for one frame, from the loudest channel's level. */
    private fun frameGain(frame: FloatArray): Float {
        var peak = 0f
        for (v in frame) peak = max(peak, abs(v))
        val levelDb = if (peak > 1e-6f) 20f * log10(peak) else -120f
        val coeff = if (levelDb > envelopeDb) attackCoeff else releaseCoeff
        envelopeDb = coeff * envelopeDb + (1f - coeff) * levelDb
        val over = envelopeDb - THRESHOLD_DB
        val reductionDb = if (over > 0f) over * (1f - 1f / RATIO) else 0f
        return 10f.pow((MAKEUP_DB - reductionDb) / 20f)
    }

    override fun onReset() {
        envelopeDb = -90f
    }

    private companion object {
        const val THRESHOLD_DB = -26f
        const val RATIO = 4f
        const val MAKEUP_DB = 8f
        const val ATTACK_SECONDS = 0.005f
        const val RELEASE_SECONDS = 0.25f
    }
}
