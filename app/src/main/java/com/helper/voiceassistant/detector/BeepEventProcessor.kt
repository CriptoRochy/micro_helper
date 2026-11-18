package com.helper.voiceassistant.detector

import com.helper.voiceassistant.domain.model.BeepConfig

/**
 * Turns raw [TonePulse]s into commands, depending on what the assistant is doing.
 *
 * Time is measured in stream samples (not wall-clock), which makes the logic deterministic and
 * testable without a real clock: the audio thread calls [onTick] after each captured chunk.
 *
 * LISTENING: the first pulse is held as "pending". If a second pulse starts within
 *            doubleBeepInterval after the first one ended → DOUBLE_BEEP. If the interval elapses
 *            first → SINGLE_BEEP. Double-beep recognition therefore always has priority: a double
 *            beep can never produce a SINGLE_BEEP (= start recording).
 * RECORDING: any pulse is reported immediately as SINGLE_BEEP (= stop recording); it can never
 *            become a replay command.
 * IGNORE:    pulses are swallowed (processing / speaking / replaying).
 *
 * After every emitted command, pulses that start within debounceMs are ignored, which absorbs echoes,
 * a third beep of a triple, and repeated triggers. Every accepted pulse also yields BEEP_END.
 */
class BeepEventProcessor(
    config: BeepConfig,
    private val sampleRate: Int = 16_000,
) {
    enum class Mode { LISTENING, RECORDING, IGNORE }

    @Volatile
    var config: BeepConfig = config

    var mode: Mode = Mode.IGNORE
        set(value) {
            if (field != value) pending = null
            field = value
        }

    private var pending: TonePulse? = null
    private var cooldownUntil = Long.MIN_VALUE

    val hasPending: Boolean get() = pending != null

    fun onPulse(pulse: TonePulse): List<BeepEvent> {
        if (pulse.startSample < cooldownUntil) return emptyList()
        val events = mutableListOf(BeepEvent(BeepEventType.BEEP_END, listOf(pulse), pulse.endSample))
        when (mode) {
            Mode.IGNORE -> return events
            Mode.RECORDING -> {
                events += emit(BeepEventType.SINGLE_BEEP, listOf(pulse), pulse.endSample)
            }
            Mode.LISTENING -> {
                val first = pending
                if (first == null) {
                    pending = pulse
                } else if (pulse.startSample - first.endSample <= ms(config.doubleBeepIntervalMs)) {
                    pending = null
                    events += emit(BeepEventType.DOUBLE_BEEP, listOf(first, pulse), pulse.endSample)
                } else {
                    // Too far apart: the first was a single beep that onTick has not confirmed yet.
                    // The new pulse is held as the start of a possible next command, unless it falls
                    // inside the debounce window opened by that single.
                    events += emit(BeepEventType.SINGLE_BEEP, listOf(first), pulse.startSample)
                    pending = if (pulse.startSample < cooldownUntil) null else pulse
                }
            }
        }
        return events
    }

    /** Confirms a pending single beep once the double-beep window has elapsed. */
    fun onTick(currentSample: Long): BeepEvent? {
        val first = pending ?: return null
        if (mode != Mode.LISTENING) {
            pending = null
            return null
        }
        if (currentSample - first.endSample > ms(config.doubleBeepIntervalMs)) {
            pending = null
            return emit(BeepEventType.SINGLE_BEEP, listOf(first), currentSample)
        }
        return null
    }

    fun reset() {
        pending = null
        cooldownUntil = Long.MIN_VALUE
    }

    private fun emit(type: BeepEventType, pulses: List<TonePulse>, at: Long): BeepEvent {
        cooldownUntil = pulses.last().endSample + ms(config.debounceMs)
        return BeepEvent(type, pulses, at)
    }

    private fun ms(value: Int): Long = value.toLong() * sampleRate / 1000
}
