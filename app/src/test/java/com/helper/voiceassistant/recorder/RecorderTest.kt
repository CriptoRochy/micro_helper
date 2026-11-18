package com.helper.voiceassistant.recorder

import com.helper.voiceassistant.TestAudio
import com.helper.voiceassistant.detector.BeepDetector
import com.helper.voiceassistant.detector.TonePulse
import com.helper.voiceassistant.domain.model.BeepConfig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RecorderTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `ring buffer keeps the newest samples by absolute index`() {
        val buf = AudioBuffer(10)
        buf.write(ShortArray(8) { it.toShort() })
        buf.write(ShortArray(8) { (it + 8).toShort() })
        assertEquals(16, buf.endIndex)
        assertEquals(6, buf.startIndex)
        assertArrayEquals(shortArrayOf(6, 7, 8), buf.read(0, 9)) // clipped to what is still available
        assertArrayEquals(shortArrayOf(14, 15), buf.read(14))
        assertEquals(0, buf.read(20).size)
    }

    @Test
    fun `wav header is valid and truncation fixes sizes`() {
        val file = tmp.newFile("a.wav")
        val w = WavFileWriter(file, 16_000)
        w.write(ShortArray(1000) { 1 })
        assertEquals(600, w.finish(600))
        assertEquals(44L + 1200, file.length())
        RandomAccessFile(file, "r").use { raf ->
            val h = ByteArray(44).also { raf.readFully(it) }
            val bb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals("RIFF", String(h, 0, 4))
            assertEquals(36 + 1200, bb.getInt(4))
            assertEquals("WAVE", String(h, 8, 4))
            assertEquals(1, bb.getShort(22).toInt())      // mono
            assertEquals(16_000, bb.getInt(24))
            assertEquals(16, bb.getShort(34).toInt())     // bits
            assertEquals(1200, bb.getInt(40))
        }
    }

    @Test
    fun `stop with nothing left deletes the file`() {
        val buf = AudioBuffer(16_000)
        val rec = VoiceRecorder()
        val file = tmp.root.resolve("empty.wav")
        rec.start(file, 0, buf)
        assertNull(rec.stop(0))
        assertFalse(file.exists())
    }

    /**
     * End-to-end over the real detector: beep → speech → beep. The recording is started late (after the
     * double-beep confirmation delay) from the ring buffer and cut before the second beep. It must contain
     * the whole speech and none of the beep samples.
     */
    @Test
    fun `control beeps are excluded and speech start is not lost`() {
        val sr = TestAudio.SR
        val speechMarker: Short = 1234
        val speech = ShortArray(TestAudio.samples(1500)) { speechMarker }
        val signal = TestAudio.concat(
            TestAudio.silence(300), TestAudio.tone(150, amplitude = 0.5), TestAudio.silence(300),
            speech,
            TestAudio.silence(300), TestAudio.tone(150, amplitude = 0.5), TestAudio.silence(600),
        )
        val beep1End = TestAudio.samples(450).toLong()
        val speechStart = beep1End + TestAudio.samples(300)
        val speechEnd = speechStart + speech.size
        val beep2Start = speechEnd + TestAudio.samples(300)

        val guard = BeepConfig().guardMs * sr / 1000L
        val pulses = mutableListOf<TonePulse>()
        val detector = BeepDetector(BeepConfig(), sr) { pulses += it }
        val buffer = AudioBuffer(sr * 10)
        val recorder = VoiceRecorder(sr)
        val file = tmp.root.resolve("speech.wav")
        var index = 0L
        var started = false
        var result: RecordingResult? = null
        val confirmDelay = TestAudio.samples(450) // double-beep window

        for (chunk in signal.toList().chunked(512)) {
            val arr = chunk.toShortArray()
            buffer.write(arr, arr.size)
            recorder.append(arr, arr.size, index)
            detector.process(arr, arr.size, index)
            index += arr.size
            // Start only after the confirmation delay, like the real controller.
            if (!started && pulses.size == 1 && index > pulses[0].endSample + confirmDelay) {
                recorder.start(file, pulses[0].endSample + guard, buffer)
                started = true
            }
            if (started && result == null && pulses.size == 2) {
                result = recorder.stop(pulses[1].startSample - guard)
            }
        }

        assertEquals(2, pulses.size)
        assertTrue(pulses[0].endSample >= beep1End)
        assertTrue(pulses[1].startSample <= beep2Start)
        val r = result!!
        val recorded = TestAudio.readWavSamples(r.file)
        // Speech fully present...
        assertEquals(speech.size, recorded.count { it == speechMarker })
        // ...and every other sample is the silence padding (no tone samples at all).
        assertTrue("beep samples leaked", recorded.all { it == speechMarker || it.toInt() == 0 })
        assertEquals(recorded.size.toLong(), r.sampleCount)
    }
}
