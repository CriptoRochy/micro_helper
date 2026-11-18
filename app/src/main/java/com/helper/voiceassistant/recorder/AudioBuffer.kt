package com.helper.voiceassistant.recorder

/**
 * Thread-safe ring buffer of PCM samples addressed by absolute stream index.
 *
 * The capture thread writes every sample here. When a single beep is confirmed (after the
 * double-beep window), the recorder copies the samples that arrived *after* the beep from this
 * buffer, so the confirmation delay never costs the beginning of the user's speech.
 */
class AudioBuffer(val capacity: Int) {
    private val data = ShortArray(capacity)
    private var total = 0L

    /** Absolute index one past the newest sample. */
    val endIndex: Long @Synchronized get() = total

    /** Absolute index of the oldest sample still available. */
    val startIndex: Long @Synchronized get() = (total - capacity).coerceAtLeast(0)

    @Synchronized
    fun write(samples: ShortArray, count: Int = samples.size) {
        for (i in 0 until count) {
            data[(total % capacity).toInt()] = samples[i]
            total++
        }
    }

    /** Copies samples in [from, to) that are still buffered; the range is clipped to what exists. */
    @Synchronized
    fun read(from: Long, to: Long = total): ShortArray {
        val start = maxOf(from, (total - capacity).coerceAtLeast(0))
        val end = minOf(to, total)
        if (end <= start) return ShortArray(0)
        val out = ShortArray((end - start).toInt())
        for (i in out.indices) out[i] = data[((start + i) % capacity).toInt()]
        return out
    }

    @Synchronized
    fun clear() {
        total = 0
    }
}
