package com.helper.voiceassistant.detector

import com.helper.voiceassistant.domain.model.ButtonAction
import com.helper.voiceassistant.domain.model.HeadsetButtonConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ButtonGestureDetectorTest {
    private val key = HeadsetButtonConfig.KEYCODE_MEDIA_PLAY_PAUSE
    private val cfg = HeadsetButtonConfig(doublePressMs = 400, longPressMs = 800)

    @Test
    fun `single press is reported after the double-press window`() {
        val d = ButtonGestureDetector(cfg)
        assertNull(d.onDown(key, 1000))
        assertNull(d.onUp(key, 1100))
        assertEquals(1500L, d.nextDeadline())
        assertNull(d.onTick(1400))
        assertEquals(ButtonGesture(ButtonGestureType.SINGLE, key, 1000), d.onTick(1500))
        assertNull(d.nextDeadline())
    }

    @Test
    fun `two quick presses are a double press`() {
        val d = ButtonGestureDetector(cfg)
        d.onDown(key, 1000); d.onUp(key, 1080)
        d.onDown(key, 1300)
        assertEquals(ButtonGesture(ButtonGestureType.DOUBLE, key, 1000), d.onUp(key, 1380))
        assertNull(d.onTick(5000))
    }

    @Test
    fun `holding the button is a long press, fired while held`() {
        val d = ButtonGestureDetector(cfg)
        d.onDown(key, 1000)
        assertEquals(1800L, d.nextDeadline())
        assertEquals(ButtonGesture(ButtonGestureType.LONG, key, 1000), d.onTick(1800))
        assertNull("release after long press is swallowed", d.onUp(key, 2500))
        assertNull(d.onTick(4000))
    }

    @Test
    fun `without a double-press action a press is reported immediately`() {
        val d = ButtonGestureDetector(cfg.copy(doublePress = ButtonAction.NONE))
        d.onDown(key, 1000)
        assertEquals(ButtonGestureType.SINGLE, d.onUp(key, 1100)?.type)
    }

    @Test
    fun `release without a down event still counts as a press`() {
        val d = ButtonGestureDetector(cfg.copy(doublePress = ButtonAction.NONE))
        assertEquals(ButtonGestureType.SINGLE, d.onUp(key, 1000)?.type)
    }

    @Test
    fun `auto-repeat downs are ignored`() {
        val d = ButtonGestureDetector(cfg.copy(longPress = ButtonAction.NONE))
        d.onDown(key, 1000); d.onDown(key, 1050); d.onDown(key, 1100)
        d.onUp(key, 1200)
        assertEquals(ButtonGestureType.SINGLE, d.onTick(1600)?.type)
    }
}
