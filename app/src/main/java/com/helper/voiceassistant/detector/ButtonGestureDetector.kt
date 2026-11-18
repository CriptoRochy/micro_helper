package com.helper.voiceassistant.detector

import com.helper.voiceassistant.domain.model.ButtonAction
import com.helper.voiceassistant.domain.model.HeadsetButtonConfig

enum class ButtonGestureType { SINGLE, DOUBLE, LONG }

/** A recognized headset-button gesture. [pressedAtMs] is the uptime of the (first) key-down. */
data class ButtonGesture(val type: ButtonGestureType, val keyCode: Int, val pressedAtMs: Long)

/**
 * Turns raw key down/up events into single / double / long presses. Pure logic with explicit time,
 * so it is unit-tested on the JVM; the Android side calls [onTick] at [nextDeadline].
 *
 * A single press is only reported after the double-press window has passed (like the double-beep
 * confirmation). When no double-press action is configured it is reported immediately on release.
 */
class ButtonGestureDetector(var config: HeadsetButtonConfig = HeadsetButtonConfig()) {
    private var downAt: Long? = null
    private var downKey = 0
    private var longFired = false
    private var pendingAt: Long? = null
    private var pendingKey = 0
    private var pendingDeadline = 0L

    private val longEnabled get() = config.longPress != ButtonAction.NONE
    private val doubleEnabled get() = config.doublePress != ButtonAction.NONE

    fun reset() {
        downAt = null; pendingAt = null; longFired = false
    }

    fun onDown(keyCode: Int, timeMs: Long): ButtonGesture? {
        if (downAt != null) return null // auto-repeat
        downAt = timeMs
        downKey = keyCode
        longFired = false
        return null
    }

    fun onUp(keyCode: Int, timeMs: Long): ButtonGesture? {
        val d = downAt ?: return onDownAndUp(keyCode, timeMs)
        downAt = null
        if (longFired) return null
        if (longEnabled && timeMs - d >= config.longPressMs) {
            pendingAt = null
            return ButtonGesture(ButtonGestureType.LONG, keyCode, d)
        }
        pendingAt?.let { first ->
            pendingAt = null
            return ButtonGesture(ButtonGestureType.DOUBLE, pendingKey, first)
        }
        if (!doubleEnabled) return ButtonGesture(ButtonGestureType.SINGLE, keyCode, d)
        pendingAt = d
        pendingKey = keyCode
        pendingDeadline = timeMs + config.doublePressMs
        return null
    }

    /** Some Bluetooth stacks deliver only the release; treat it as a complete short press. */
    private fun onDownAndUp(keyCode: Int, timeMs: Long): ButtonGesture? {
        onDown(keyCode, timeMs)
        return onUp(keyCode, timeMs)
    }

    fun onTick(nowMs: Long): ButtonGesture? {
        val d = downAt
        if (d != null && longEnabled && !longFired && nowMs - d >= config.longPressMs) {
            longFired = true
            pendingAt = null
            return ButtonGesture(ButtonGestureType.LONG, downKey, d)
        }
        val p = pendingAt
        if (p != null && d == null && nowMs >= pendingDeadline) {
            pendingAt = null
            return ButtonGesture(ButtonGestureType.SINGLE, pendingKey, p)
        }
        return null
    }

    /** Uptime at which [onTick] should be called next, or null when nothing is pending. */
    fun nextDeadline(): Long? {
        val d = downAt
        if (d != null && longEnabled && !longFired) return d + config.longPressMs
        return pendingAt?.let { pendingDeadline }
    }
}
