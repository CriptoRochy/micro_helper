package com.helper.voiceassistant.processor

import com.helper.voiceassistant.TestAudio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SilenceDetectorTest {
    private val rate = 16_000
    private val chunk = 512
    private val config = AutoStopConfig(silenceMs = 1000, thresholdDbfs = -45f, minSpeechMs = 300)

    /** Feeds [audio] in capture-sized chunks; returns the cut sample or null. */
    private fun run(d: SilenceDetector, audio: ShortArray, start: Long = 0): Long? {
        var i = 0
        while (i < audio.size) {
            val n = minOf(chunk, audio.size - i)
            val part = audio.copyOfRange(i, i + n)
            d.process(part, n, start + i, config)?.let { return it }
            i += n
        }
        return null
    }

    @Test
    fun `speech followed by enough silence ends the recording just after the speech`() {
        val speech = TestAudio.voiceLike(1000)
        val audio = speech + ShortArray(rate * 2)
        val cut = run(SilenceDetector(rate), audio)
        assertNotNull(cut)
        val speechEnd = speech.size.toLong()
        assert(cut!! >= speechEnd && cut <= speechEnd + rate / 2) { "cut $cut not right after speech end $speechEnd" }
    }

    @Test
    fun `silence before any speech does not end the recording`() {
        assertNull(run(SilenceDetector(rate), ShortArray(rate * 3)))
    }

    @Test
    fun `a short pause inside speech does not end the recording`() {
        val audio = TestAudio.voiceLike(800) + ShortArray(rate / 2) + TestAudio.voiceLike(800)
        assertNull(run(SilenceDetector(rate), audio))
    }

    @Test
    fun `fires only once until reset`() {
        val d = SilenceDetector(rate)
        val audio = TestAudio.voiceLike(800) + ShortArray(rate * 2)
        assertNotNull(run(d, audio))
        assertNull(run(d, audio, start = audio.size.toLong()))
        d.reset()
        assertEquals(true, run(d, audio) != null)
    }
}
