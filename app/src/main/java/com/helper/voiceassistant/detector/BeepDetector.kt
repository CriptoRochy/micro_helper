package com.helper.voiceassistant.detector

import com.helper.voiceassistant.domain.model.BeepConfig
import com.helper.voiceassistant.util.Fft
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Detects short tonal control beeps in a 16-bit mono PCM stream.
 *
 * Algorithm (per hop of [hopSize] samples, analysing the last [fftSize] samples):
 *  1. Apply a Hann window and FFT.
 *  2. Find the strongest bin within targetFrequency ± (tolerance + 1 bin) and refine its frequency with
 *     parabolic interpolation on log-magnitudes (bin width at 16 kHz/1024 is 15.6 Hz, so this keeps the
 *     ±50 Hz tolerance meaningful).
 *  3. Estimate the tone amplitude from the energy of the Hann main lobe (k-2..k+2) and convert to dBFS
 *     (a full-scale sine = 0 dBFS).
 *  4. Compute tonality = main-lobe energy / total frame energy. Speech, music and broadband noise
 *     spread energy across many bins, so they fail this test even when loud.
 *  5. A frame is "tone" when level ≥ threshold, tonality ≥ ratio and the frequency is in tolerance.
 *     Two consecutive tone frames switch the detector ON; while ON a looser "sustain" test is used
 *     (threshold − hysteresis), and two consecutive non-sustain frames switch it OFF. This hysteresis
 *     prevents one beep from being reported several times.
 *  6. On OFF, the estimated duration must be within [BeepConfig.minBeepMs, BeepConfig.maxBeepMs];
 *     long tones (alarms, ringtones, held notes) are rejected.
 *
 * The detector is not thread-safe; it is driven from the single audio capture thread.
 * Grouping pulses into single/double beeps is done separately by [BeepEventProcessor].
 */
class BeepDetector(
    config: BeepConfig,
    val sampleRate: Int = 16_000,
    val fftSize: Int = 1024,
    val hopSize: Int = 256,
    private val onPulse: (TonePulse) -> Unit,
) {
    @Volatile
    var config: BeepConfig = config

    private val fft = Fft(fftSize)
    private val window = Fft.hann(fftSize)
    private val windowEnergy = window.sumOf { it * it }
    private val binWidth = sampleRate.toDouble() / fftSize

    private val ring = DoubleArray(fftSize)
    private var ringPos = 0
    private var filled = 0
    private var samplesSinceHop = 0
    private var totalSamples = 0L

    private val re = DoubleArray(fftSize)
    private val im = DoubleArray(fftSize)
    private val power = DoubleArray(fftSize / 2)

    // Tone tracking state
    private var isOn = false
    private var onCount = 0
    private var offCount = 0
    private var candidateStart = 0L
    private var toneStart = 0L
    private var lastOnEnd = 0L
    private var peakDb = -120f
    private var freqSum = 0.0
    private var freqCount = 0

    /** Last analysed frame, exposed for diagnostics / tests. */
    var lastLevelDbfs: Float = -120f
        private set
    var lastTonality: Float = 0f
        private set
    var lastFrequencyHz: Float = 0f
        private set

    /** Feeds [count] samples whose first sample has absolute stream index [firstSampleIndex]. */
    fun process(samples: ShortArray, count: Int = samples.size, firstSampleIndex: Long = totalSamples) {
        totalSamples = firstSampleIndex
        for (i in 0 until count) {
            ring[ringPos] = samples[i] / 32768.0
            ringPos = (ringPos + 1) % fftSize
            if (filled < fftSize) filled++
            totalSamples++
            samplesSinceHop++
            if (filled == fftSize && samplesSinceHop >= hopSize) {
                samplesSinceHop = 0
                analyzeFrame(windowEnd = totalSamples)
            }
        }
    }

    fun reset() {
        ring.fill(0.0); ringPos = 0; filled = 0; samplesSinceHop = 0
        isOn = false; onCount = 0; offCount = 0
    }

    private fun analyzeFrame(windowEnd: Long) {
        val cfg = config
        // ringPos points at the oldest sample.
        for (i in 0 until fftSize) {
            re[i] = ring[(ringPos + i) % fftSize] * window[i]
            im[i] = 0.0
        }
        fft.transform(re, im)

        var total = 0.0
        for (k in 1 until fftSize / 2) {
            val p = re[k] * re[k] + im[k] * im[k]
            power[k] = p
            total += p
        }
        val lo = ((cfg.frequencyHz - cfg.toleranceHz) / binWidth - 1).toInt().coerceIn(3, fftSize / 2 - 4)
        val hi = ((cfg.frequencyHz + cfg.toleranceHz) / binWidth + 1).toInt().coerceIn(lo, fftSize / 2 - 4)
        var peakBin = lo
        for (k in lo..hi) if (power[k] > power[peakBin]) peakBin = k

        val frequency = interpolatedFrequency(peakBin)
        var lobe = 0.0
        for (k in peakBin - 2..peakBin + 2) lobe += power[k]
        // One-sided main-lobe energy of A·cos(ωn) ≈ N·(A/2)²·Σw²  ⇒  A = 2·sqrt(lobe / (N·Σw²))
        val amplitude = 2.0 * sqrt(lobe / (fftSize * windowEnergy))
        val levelDb = if (amplitude > 1e-9) (20 * log10(amplitude)).toFloat() else -120f
        val tonality = if (total > 0) (lobe / total).toFloat() else 0f

        lastLevelDbfs = levelDb
        lastTonality = tonality
        lastFrequencyHz = frequency.toFloat()

        val freqOk = abs(frequency - cfg.frequencyHz) <= cfg.toleranceHz
        val toneFrame = levelDb >= cfg.thresholdDbfs && tonality >= cfg.tonalityRatio && freqOk
        val sustainFrame = levelDb >= cfg.thresholdDbfs - cfg.hysteresisDb &&
            tonality >= cfg.tonalityRatio * 0.7f &&
            abs(frequency - cfg.frequencyHz) <= cfg.toleranceHz * 1.5f
        val windowStart = windowEnd - fftSize

        if (!isOn) {
            if (toneFrame) {
                if (onCount == 0) candidateStart = windowStart
                onCount++
                if (onCount >= ON_FRAMES) {
                    isOn = true
                    offCount = 0
                    toneStart = candidateStart
                    lastOnEnd = windowEnd
                    peakDb = levelDb
                    freqSum = frequency; freqCount = 1
                }
            } else {
                onCount = 0
            }
            return
        }

        if (sustainFrame) {
            offCount = 0
            lastOnEnd = windowEnd
            if (levelDb > peakDb) peakDb = levelDb
            freqSum += frequency; freqCount++
        } else {
            offCount++
            if (offCount >= OFF_FRAMES) {
                isOn = false
                onCount = 0
                finishTone()
            }
        }
    }

    private fun finishTone() {
        val cfg = config
        // Every analysis window that "sees" the tone extends ~fftSize/2 beyond each side of it,
        // so the span [first window start, last window end] ≈ duration + fftSize.
        val spanSamples = lastOnEnd - toneStart
        val estimatedSamples = (spanSamples - fftSize).coerceAtLeast(hopSize.toLong())
        val durationMs = (estimatedSamples * 1000 / sampleRate).toInt()
        if (durationMs < cfg.minBeepMs || durationMs > cfg.maxBeepMs) return
        onPulse(
            TonePulse(
                startSample = toneStart,
                endSample = lastOnEnd,
                durationMs = durationMs,
                peakDbfs = peakDb,
                frequencyHz = (freqSum / freqCount.coerceAtLeast(1)).toFloat(),
            ),
        )
    }

    private fun interpolatedFrequency(k: Int): Double {
        val a = ln(power[k - 1])
        val b = ln(power[k])
        val c = ln(power[k + 1])
        val denom = a - 2 * b + c
        val delta = if (abs(denom) < 1e-12) 0.0 else (0.5 * (a - c) / denom).coerceIn(-0.5, 0.5)
        return (k + delta) * binWidth
    }

    private fun ln(p: Double) = kotlin.math.ln(p + 1e-20)

    companion object {
        const val ON_FRAMES = 2
        const val OFF_FRAMES = 2
    }
}
