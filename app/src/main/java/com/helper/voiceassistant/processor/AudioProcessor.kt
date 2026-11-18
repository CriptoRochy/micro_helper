package com.helper.voiceassistant.processor

import com.helper.voiceassistant.recorder.WavFileWriter
import java.io.File
import java.io.RandomAccessFile
import javax.inject.Inject
import kotlin.math.log10
import kotlin.math.sqrt

sealed interface RecordingCheck {
    data class Valid(val durationMs: Long, val speechMs: Long, val rmsDbfs: Float) : RecordingCheck
    data class TooShort(val durationMs: Long) : RecordingCheck
    data class Silent(val durationMs: Long, val rmsDbfs: Float) : RecordingCheck
    data class Unreadable(val reason: String) : RecordingCheck
}

/**
 * Validates a finished recording before it is uploaded for recognition: the file must be a readable
 * WAV, at least `minDurationMs` long and contain enough frames above `minSpeechDbfs` to plausibly hold
 * speech. This avoids paying for (and waiting on) STT requests for empty or accidental recordings.
 */
class AudioProcessor @Inject constructor() {

    fun check(
        file: File,
        minDurationMs: Int,
        minSpeechDbfs: Float,
        sampleRate: Int = 16_000,
    ): RecordingCheck {
        if (!file.exists()) return RecordingCheck.Unreadable("file missing")
        val dataBytes = file.length() - WavFileWriter.HEADER_SIZE
        if (dataBytes <= 0) return RecordingCheck.TooShort(0)
        val totalSamples = dataBytes / 2
        val durationMs = totalSamples * 1000 / sampleRate
        if (durationMs < minDurationMs) return RecordingCheck.TooShort(durationMs)

        val frame = sampleRate / 50 // 20 ms
        var speechFrames = 0L
        var sumSq = 0.0
        var count = 0L
        try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(WavFileWriter.HEADER_SIZE.toLong())
                val buf = ByteArray(frame * 2)
                while (true) {
                    val read = raf.read(buf)
                    if (read <= 1) break
                    val n = read / 2
                    var frameSq = 0.0
                    for (i in 0 until n) {
                        val s = ((buf[2 * i + 1].toInt() shl 8) or (buf[2 * i].toInt() and 0xFF)).toShort() / 32768.0
                        frameSq += s * s
                    }
                    sumSq += frameSq
                    count += n
                    if (toDb(sqrt(frameSq / n)) >= minSpeechDbfs) speechFrames++
                }
            }
        } catch (e: Exception) {
            return RecordingCheck.Unreadable(e.message ?: e.javaClass.simpleName)
        }
        val rms = toDb(sqrt(sumSq / count.coerceAtLeast(1)))
        val speechMs = speechFrames * 20
        // Require at least ~200 ms of frames above the speech floor.
        return if (speechMs < MIN_SPEECH_MS) RecordingCheck.Silent(durationMs, rms)
        else RecordingCheck.Valid(durationMs, speechMs, rms)
    }

    private fun toDb(v: Double): Float = if (v <= 1e-9) -120f else (20 * log10(v)).toFloat()

    companion object {
        const val MIN_SPEECH_MS = 200L
    }
}
