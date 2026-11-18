package com.helper.voiceassistant.detector

import com.helper.voiceassistant.TestAudio
import com.helper.voiceassistant.TestAudio.concat
import com.helper.voiceassistant.TestAudio.noise
import com.helper.voiceassistant.TestAudio.samples
import com.helper.voiceassistant.TestAudio.silence
import com.helper.voiceassistant.TestAudio.tone
import com.helper.voiceassistant.domain.model.BeepConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class BeepDetectorTest {

    private fun detect(signal: ShortArray, config: BeepConfig = BeepConfig()): List<TonePulse> {
        val pulses = mutableListOf<TonePulse>()
        val detector = BeepDetector(config, TestAudio.SR) { pulses += it }
        var index = 0L
        // Feed in realistic 512-sample chunks, like the capture thread does.
        signal.toList().chunked(512).forEach { chunk ->
            val arr = chunk.toShortArray()
            detector.process(arr, arr.size, index)
            index += arr.size
        }
        return pulses
    }

    @Test
    fun `single 2 kHz beep is detected once with conservative bounds`() {
        val signal = concat(silence(500), tone(150), silence(800))
        val pulses = detect(signal)
        assertEquals(1, pulses.size)
        val p = pulses.single()
        val toneStart = samples(500).toLong()
        val toneEnd = toneStart + samples(150)
        assertTrue("start must not be after the real onset", p.startSample <= toneStart)
        assertTrue("end must not be before the real end", p.endSample >= toneEnd)
        assertTrue("duration estimate ${p.durationMs}", abs(p.durationMs - 150) <= 70)
        assertTrue("frequency ${p.frequencyHz}", abs(p.frequencyHz - 2000f) <= 20f)
    }

    @Test
    fun `beep in background noise is detected`() {
        val beep = concat(silence(400), tone(200, amplitude = 0.25), silence(600))
        val signal = TestAudio.add(beep, noise(1200, amplitude = 0.02))
        assertEquals(1, detect(signal).size)
    }

    @Test
    fun `two separated beeps give two pulses`() {
        val signal = concat(silence(300), tone(120), silence(250), tone(120), silence(700))
        val pulses = detect(signal)
        assertEquals(2, pulses.size)
        assertTrue(pulses[0].endSample <= pulses[1].startSample + samples(64))
    }

    @Test
    fun `other frequencies are ignored`() {
        assertEquals(0, detect(concat(silence(300), tone(200, freq = 1000.0), silence(600))).size)
        assertEquals(0, detect(concat(silence(300), tone(200, freq = 2150.0), silence(600))).size)
    }

    @Test
    fun `frequency inside tolerance is accepted`() {
        assertEquals(1, detect(concat(silence(300), tone(200, freq = 2035.0), silence(600))).size)
    }

    @Test
    fun `tone below threshold is ignored`() {
        // amplitude 0.01 ≈ −40 dBFS, threshold −30 dBFS
        assertEquals(0, detect(concat(silence(300), tone(200, amplitude = 0.01), silence(600))).size)
    }

    @Test
    fun `loud broadband noise and speech-like sound are not beeps`() {
        assertEquals(0, detect(noise(2000, amplitude = 0.5)).size)
        assertEquals(0, detect(TestAudio.voiceLike(2000)).size)
    }

    @Test
    fun `long continuous tone is rejected`() {
        assertEquals(0, detect(concat(silence(300), tone(2500), silence(600))).size)
    }

    @Test
    fun `too short click is rejected`() {
        val cfg = BeepConfig(minBeepMs = 60)
        assertEquals(0, detect(concat(silence(300), tone(15), silence(600)), cfg).size)
    }

    @Test
    fun `configured frequency is honoured`() {
        val cfg = BeepConfig(frequencyHz = 1000f)
        assertEquals(1, detect(concat(silence(300), tone(200, freq = 1000.0), silence(600)), cfg).size)
        assertEquals(0, detect(concat(silence(300), tone(200, freq = 2000.0), silence(600)), cfg).size)
    }
}
