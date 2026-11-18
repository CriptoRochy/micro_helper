package com.helper.voiceassistant.processor

import com.helper.voiceassistant.TestAudio
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AudioProcessorTest {
    @get:Rule val tmp = TemporaryFolder()
    private val processor = AudioProcessor()

    @Test
    fun `too short recording is rejected`() {
        val f = TestAudio.writeWav(tmp.root.resolve("s.wav"), TestAudio.noise(300, 0.2))
        assertTrue(processor.check(f, 600, -50f) is RecordingCheck.TooShort)
    }

    @Test
    fun `silent recording is rejected`() {
        val f = TestAudio.writeWav(tmp.root.resolve("q.wav"), TestAudio.noise(2000, 0.0005))
        assertTrue(processor.check(f, 600, -50f) is RecordingCheck.Silent)
    }

    @Test
    fun `recording with speech-level audio is valid`() {
        val f = TestAudio.writeWav(tmp.root.resolve("v.wav"), TestAudio.concat(TestAudio.silence(300), TestAudio.voiceLike(1200), TestAudio.silence(300)))
        val r = processor.check(f, 600, -50f)
        assertTrue(r.toString(), r is RecordingCheck.Valid)
    }

    @Test
    fun `missing or header-only file is handled`() {
        assertTrue(processor.check(tmp.root.resolve("none.wav"), 600, -50f) is RecordingCheck.Unreadable)
        val empty = TestAudio.writeWav(tmp.root.resolve("e.wav"), ShortArray(0))
        assertTrue(processor.check(empty, 600, -50f) is RecordingCheck.TooShort)
    }
}
