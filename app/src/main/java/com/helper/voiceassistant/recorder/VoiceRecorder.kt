package com.helper.voiceassistant.recorder

import java.io.File

data class RecordingResult(
    val file: File,
    val sampleCount: Long,
    val durationMs: Long,
)

/**
 * Writes the user's speech into a WAV file, addressed by absolute stream sample indices.
 *
 *  - [start] begins at `fromSample` (just after the starting beep) and back-fills everything captured
 *    since then from the [AudioBuffer], covering the single-beep confirmation delay.
 *  - [append] is called by the capture thread with every new chunk.
 *  - [stop] truncates the file at `endSample` (just before the terminating beep), so neither control
 *    beep is ever part of the recording.
 *
 * All methods are synchronized: start/stop come from the controller, append from the audio thread.
 */
class VoiceRecorder(private val sampleRate: Int = 16_000) {
    private var writer: WavFileWriter? = null
    private var startSample = 0L
    private var nextSample = 0L

    val isRecording: Boolean @Synchronized get() = writer != null

    /** Duration of what has been captured so far, in ms. */
    val currentDurationMs: Long
        @Synchronized get() = if (writer == null) 0 else (nextSample - startSample) * 1000 / sampleRate

    @Synchronized
    fun start(file: File, fromSample: Long, buffer: AudioBuffer) {
        check(writer == null) { "Recording already in progress" }
        file.parentFile?.mkdirs()
        val w = WavFileWriter(file, sampleRate)
        writer = w
        val available = maxOf(fromSample, buffer.startIndex)
        startSample = available
        val backlog = buffer.read(available, buffer.endIndex)
        w.write(backlog)
        nextSample = available + backlog.size
    }

    @Synchronized
    fun append(samples: ShortArray, count: Int, firstSampleIndex: Long) {
        val w = writer ?: return
        val chunkEnd = firstSampleIndex + count
        if (chunkEnd <= nextSample) return // already back-filled
        val skip = (nextSample - firstSampleIndex).coerceAtLeast(0).toInt()
        w.write(samples, skip, count - skip)
        nextSample = chunkEnd
    }

    /**
     * Ends the recording so that the last sample is strictly before [endSample].
     * Returns null when nothing remains (the file is then deleted).
     */
    @Synchronized
    fun stop(endSample: Long = nextSample): RecordingResult? {
        val w = writer ?: return null
        writer = null
        val keep = (endSample - startSample).coerceAtLeast(0)
        val kept = runCatching { w.finish(keep) }.getOrElse {
            w.close(); w.file.delete(); throw it
        }
        if (kept <= 0) {
            w.file.delete()
            return null
        }
        return RecordingResult(w.file, kept, kept * 1000 / sampleRate)
    }

    @Synchronized
    fun cancel() {
        val w = writer ?: return
        writer = null
        w.close()
        w.file.delete()
    }
}
