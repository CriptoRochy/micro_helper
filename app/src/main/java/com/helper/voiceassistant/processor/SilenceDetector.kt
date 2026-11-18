package com.helper.voiceassistant.processor

import kotlin.math.log10
import kotlin.math.sqrt

/** Automatic end-of-recording on silence. */
data class AutoStopConfig(
    val silenceMs: Int,
    val thresholdDbfs: Float,
    val minSpeechMs: Int,
)

/**
 * Watches the recorded stream chunk by chunk. Once at least [AutoStopConfig.minSpeechMs] of speech has
 * been heard, a run of [AutoStopConfig.silenceMs] below the threshold ends the recording. Returns the
 * absolute sample to cut at: just after the last speech plus a short tail, so the last word survives.
 */
class SilenceDetector(private val sampleRate: Int) {
    private var speechSamples = 0L
    private var lastSpeechEnd = -1L
    private var fired = false

    fun reset() {
        speechSamples = 0; lastSpeechEnd = -1; fired = false
    }

    fun process(chunk: ShortArray, count: Int, firstSampleIndex: Long, config: AutoStopConfig): Long? {
        if (fired || count <= 0) return null
        var acc = 0.0
        for (i in 0 until count) { val s = chunk[i] / 32768.0; acc += s * s }
        val rms = sqrt(acc / count)
        val db = if (rms > 1e-9) 20 * log10(rms) else -120.0
        val chunkEnd = firstSampleIndex + count
        if (db >= config.thresholdDbfs) {
            speechSamples += count
            lastSpeechEnd = chunkEnd
            return null
        }
        if (lastSpeechEnd < 0 || speechSamples * 1000 / sampleRate < config.minSpeechMs) return null
        val silentMs = (chunkEnd - lastSpeechEnd) * 1000 / sampleRate
        if (silentMs < config.silenceMs) return null
        fired = true
        val tail = minOf(TAIL_MS.toLong(), config.silenceMs.toLong()) * sampleRate / 1000
        return lastSpeechEnd + tail
    }

    companion object {
        private const val TAIL_MS = 250
    }
}
