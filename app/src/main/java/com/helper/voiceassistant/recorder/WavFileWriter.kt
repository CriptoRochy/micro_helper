package com.helper.voiceassistant.recorder

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Streams 16-bit mono PCM into a WAV file. The RIFF header is written as a placeholder and patched on
 * [finish], which can also truncate the data — used to cut the terminating beep off the end.
 */
class WavFileWriter(
    val file: File,
    private val sampleRate: Int = 16_000,
) : Closeable {
    private val raf = RandomAccessFile(file, "rw")
    private var scratch = ByteArray(4096)
    var samplesWritten = 0L
        private set

    init {
        raf.setLength(0)
        raf.write(header(0))
    }

    fun write(samples: ShortArray, offset: Int = 0, count: Int = samples.size - offset) {
        if (count <= 0) return
        val bytes = count * 2
        if (scratch.size < bytes) scratch = ByteArray(bytes)
        var j = 0
        for (i in offset until offset + count) {
            val s = samples[i].toInt()
            scratch[j++] = (s and 0xFF).toByte()
            scratch[j++] = ((s shr 8) and 0xFF).toByte()
        }
        raf.write(scratch, 0, bytes)
        samplesWritten += count
    }

    /** Keeps only the first [keepSamples] samples (or all, if null), fixes the header and closes the file. */
    fun finish(keepSamples: Long? = null): Long {
        val kept = (keepSamples ?: samplesWritten).coerceIn(0, samplesWritten)
        raf.setLength(HEADER_SIZE + kept * 2)
        raf.seek(0)
        raf.write(header(kept * 2))
        raf.close()
        samplesWritten = kept
        return kept
    }

    override fun close() {
        runCatching { raf.close() }
    }

    private fun header(dataBytes: Long): ByteArray {
        val byteRate = sampleRate * 2
        return ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt((36 + dataBytes).toInt())
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)              // PCM fmt chunk size
            putShort(1)             // PCM
            putShort(1)             // mono
            putInt(sampleRate)
            putInt(byteRate)
            putShort(2)             // block align
            putShort(16)            // bits per sample
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataBytes.toInt())
        }.array()
    }

    companion object {
        const val HEADER_SIZE = 44
    }
}
