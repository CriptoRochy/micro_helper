package com.helper.voiceassistant.assistant

import com.helper.voiceassistant.domain.model.AssistantState
import com.helper.voiceassistant.domain.model.AssistantState.CONNECTING_AUDIO
import com.helper.voiceassistant.domain.model.AssistantState.ERROR
import com.helper.voiceassistant.domain.model.AssistantState.IDLE
import com.helper.voiceassistant.domain.model.AssistantState.LISTENING
import com.helper.voiceassistant.domain.model.AssistantState.PROCESSING
import com.helper.voiceassistant.domain.model.AssistantState.RECORDING
import com.helper.voiceassistant.domain.model.AssistantState.REPLAYING
import com.helper.voiceassistant.domain.model.AssistantState.SPEAKING
import com.helper.voiceassistant.domain.model.AssistantState.TRANSCRIBING
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * Single source of truth for the assistant state. Every transition goes through [transition], which
 * rejects anything not in [allowed]. Because RECORDING, PROCESSING, SPEAKING and REPLAYING are only
 * reachable from LISTENING / the previous pipeline step, it is impossible to run two recordings, two
 * DeepSeek requests or two playbacks at once, or to start recording while processing or speaking.
 */
class AssistantStateMachine {

    private val _state = MutableStateFlow(IDLE)
    val state: StateFlow<AssistantState> = _state.asStateFlow()
    val current: AssistantState get() = _state.value

    @Synchronized
    fun canTransition(to: AssistantState): Boolean = to in allowed.getValue(_state.value)

    /** Returns true if the transition was applied. Same-state "transitions" are rejected. */
    @Synchronized
    fun transition(to: AssistantState): Boolean {
        val from = _state.value
        if (to !in allowed.getValue(from)) {
            Timber.d("Rejected transition %s → %s", from, to)
            return false
        }
        _state.value = to
        Timber.i("State %s → %s", from, to)
        return true
    }

    /** Applies the transition only when currently in [expected] (compare-and-set). */
    @Synchronized
    fun transition(expected: AssistantState, to: AssistantState): Boolean =
        _state.value == expected && transition(to)

    companion object {
        val allowed: Map<AssistantState, Set<AssistantState>> = mapOf(
            IDLE to setOf(CONNECTING_AUDIO),
            CONNECTING_AUDIO to setOf(LISTENING, ERROR, IDLE),
            LISTENING to setOf(RECORDING, REPLAYING, CONNECTING_AUDIO, ERROR, IDLE),
            RECORDING to setOf(TRANSCRIBING, LISTENING, ERROR, IDLE),
            TRANSCRIBING to setOf(PROCESSING, LISTENING, ERROR, IDLE),
            PROCESSING to setOf(SPEAKING, LISTENING, ERROR, IDLE),
            SPEAKING to setOf(LISTENING, ERROR, IDLE),
            REPLAYING to setOf(LISTENING, ERROR, IDLE),
            ERROR to setOf(LISTENING, CONNECTING_AUDIO, IDLE),
        )
    }
}
