package com.helper.voiceassistant.detector

import com.helper.voiceassistant.detector.BeepEventProcessor.Mode
import com.helper.voiceassistant.domain.model.BeepConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BeepEventProcessorTest {

    // 16 samples per ms
    private fun ms(v: Int) = v * 16L
    private fun pulse(startMs: Int, durMs: Int = 150) = TonePulse(ms(startMs), ms(startMs + durMs), durMs, -10f, 2000f)
    private fun List<BeepEvent>.commands() = filter { it.type != BeepEventType.BEEP_END }

    private lateinit var p: BeepEventProcessor

    @Before
    fun setUp() {
        p = BeepEventProcessor(BeepConfig(doubleBeepIntervalMs = 450, debounceMs = 350), 16_000)
        p.mode = Mode.LISTENING
    }

    @Test
    fun `single beep is confirmed only after the double-beep window`() {
        val events = p.onPulse(pulse(1000))
        assertEquals(listOf(BeepEventType.BEEP_END), events.map { it.type })
        assertNull(p.onTick(ms(1150 + 400)))
        val single = p.onTick(ms(1150 + 460))
        assertEquals(BeepEventType.SINGLE_BEEP, single?.type)
        assertEquals(ms(1150), single!!.first.endSample)
    }

    @Test
    fun `two close beeps are a double beep and never a single`() {
        p.onPulse(pulse(1000))
        assertNull(p.onTick(ms(1300)))
        val events = p.onPulse(pulse(1350))
        assertEquals(listOf(BeepEventType.DOUBLE_BEEP), events.commands().map { it.type })
        assertEquals(2, events.commands().single().pulses.size)
        // Nothing left pending: no late SINGLE_BEEP may start a recording.
        assertNull(p.onTick(ms(5000)))
    }

    @Test
    fun `beeps further apart than the interval are two singles`() {
        p.onPulse(pulse(1000))
        val events = p.onPulse(pulse(2000)) // 850 ms gap > 450 ms, no tick in between
        assertEquals(listOf(BeepEventType.SINGLE_BEEP), events.commands().map { it.type })
        assertTrue(p.hasPending)
    }

    @Test
    fun `while recording any beep stops immediately`() {
        p.mode = Mode.RECORDING
        val events = p.onPulse(pulse(1000))
        assertEquals(listOf(BeepEventType.BEEP_END, BeepEventType.SINGLE_BEEP), events.map { it.type })
        assertTrue(!p.hasPending)
    }

    @Test
    fun `beeps are ignored while processing or speaking`() {
        p.mode = Mode.IGNORE
        assertTrue(p.onPulse(pulse(1000)).commands().isEmpty())
        assertTrue(p.onPulse(pulse(1300)).commands().isEmpty())
        assertNull(p.onTick(ms(9000)))
    }

    @Test
    fun `debounce swallows a third beep after a double`() {
        p.onPulse(pulse(1000))
        p.onPulse(pulse(1300)) // DOUBLE, ends at 1450
        assertTrue(p.onPulse(pulse(1600)).isEmpty()) // starts 150 ms after → debounced
        assertNull(p.onTick(ms(9000)))
    }

    @Test
    fun `debounce prevents re-triggering right after a single`() {
        p.onPulse(pulse(1000))
        p.onTick(ms(1700))           // SINGLE confirmed (pulse ended 1150)
        p.mode = Mode.RECORDING
        assertTrue(p.onPulse(pulse(1300, 100)).isEmpty()) // echo inside debounce
        assertEquals(1, p.onPulse(pulse(4000)).commands().size)
    }

    @Test
    fun `changing mode drops a pending beep`() {
        p.onPulse(pulse(1000))
        p.mode = Mode.IGNORE
        p.mode = Mode.LISTENING
        assertNull(p.onTick(ms(5000)))
    }
}
