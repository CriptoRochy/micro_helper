package com.helper.voiceassistant

import com.helper.voiceassistant.recorder.WavFileWriter
import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** Synthetic PCM signals for tests (16 kHz, 16-bit mono). */
object TestAudio {
    const val SR = 16_000

    fun samples(ms: Int) = SR * ms / 1000

    fun silence(ms: Int) = ShortArray(samples(ms))

    fun tone(ms: Int, freq: Double = 2000.0, amplitude: Double = 0.3, phase: Double = 0.0) =
        ShortArray(samples(ms)) { i -> (amplitude * 32767 * sin(2 * PI * freq * i / SR + phase)).toInt().toShort() }

    fun noise(ms: Int, amplitude: Double, seed: Int = 1): ShortArray {
        val r = Random(seed)
        return ShortArray(samples(ms)) { ((r.nextDouble() * 2 - 1) * amplitude * 32767).toInt().toShort() }
    }

    /** Harmonic-rich "voiced speech"-like signal: broadband, so it must not count as a beep. */
    fun voiceLike(ms: Int, f0: Double = 170.0, amplitude: Double = 0.4) = ShortArray(samples(ms)) { i ->
        var v = 0.0
        for (h in 1..24) v += sin(2 * PI * f0 * h * i / SR) / 24
        (v * amplitude * 32767).toInt().toShort()
    }

    fun add(a: ShortArray, b: ShortArray) = ShortArray(a.size) { i ->
        (a[i] + (if (i < b.size) b[i] else 0)).coerceIn(-32768, 32767).toShort()
    }

    fun concat(vararg parts: ShortArray): ShortArray {
        val out = ShortArray(parts.sumOf { it.size })
        var pos = 0
        for (p in parts) { p.copyInto(out, pos); pos += p.size }
        return out
    }

    fun writeWav(file: File, data: ShortArray): File {
        WavFileWriter(file, SR).apply { write(data); finish() }
        return file
    }

    fun readWavSamples(file: File): ShortArray {
        val bytes = file.readBytes()
        val n = (bytes.size - WavFileWriter.HEADER_SIZE) / 2
        return ShortArray(n) { i ->
            val lo = bytes[WavFileWriter.HEADER_SIZE + 2 * i].toInt() and 0xFF
            val hi = bytes[WavFileWriter.HEADER_SIZE + 2 * i + 1].toInt()
            ((hi shl 8) or lo).toShort()
        }
    }
}
