package com.helper.voiceassistant.detector

/**
 * One detected tone burst. Sample positions are absolute indices in the capture stream and are
 * deliberately conservative: [startSample] is at or before the real tone onset and [endSample] is at
 * or after the real tone end, so cutting the recording at these points never leaks the beep.
 */
data class TonePulse(
    val startSample: Long,
    val endSample: Long,
    val durationMs: Int,
    val peakDbfs: Float,
    val frequencyHz: Float,
)

enum class BeepEventType { SINGLE_BEEP, DOUBLE_BEEP, BEEP_END }

data class BeepEvent(
    val type: BeepEventType,
    /** The pulse(s) that make up this event: 1 for SINGLE/BEEP_END, 2 for DOUBLE. */
    val pulses: List<TonePulse>,
    /** Stream position at which the event was decided. */
    val decidedAtSample: Long,
) {
    val first: TonePulse get() = pulses.first()
    val last: TonePulse get() = pulses.last()
}
