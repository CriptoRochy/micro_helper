package com.helper.voiceassistant.assistant

import com.helper.voiceassistant.domain.model.AssistantState.CONNECTING_AUDIO
import com.helper.voiceassistant.domain.model.AssistantState.ERROR
import com.helper.voiceassistant.domain.model.AssistantState.IDLE
import com.helper.voiceassistant.domain.model.AssistantState.LISTENING
import com.helper.voiceassistant.domain.model.AssistantState.PROCESSING
import com.helper.voiceassistant.domain.model.AssistantState.RECORDING
import com.helper.voiceassistant.domain.model.AssistantState.REPLAYING
import com.helper.voiceassistant.domain.model.AssistantState.SPEAKING
import com.helper.voiceassistant.domain.model.AssistantState.TRANSCRIBING
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantStateMachineTest {

    private fun machineAt(vararg path: com.helper.voiceassistant.domain.model.AssistantState) =
        AssistantStateMachine().also { sm -> path.forEach { assertTrue("→ $it", sm.transition(it)) } }

    @Test
    fun `main interaction flow is allowed`() {
        val sm = machineAt(CONNECTING_AUDIO, LISTENING, RECORDING, TRANSCRIBING, PROCESSING, SPEAKING, LISTENING)
        assertEquals(LISTENING, sm.current)
    }

    @Test
    fun `replay flow is allowed`() {
        val sm = machineAt(CONNECTING_AUDIO, LISTENING, REPLAYING, LISTENING)
        assertEquals(LISTENING, sm.current)
    }

    @Test
    fun `error flow recovers to listening or idle`() {
        machineAt(CONNECTING_AUDIO, LISTENING, RECORDING, ERROR, LISTENING)
        machineAt(CONNECTING_AUDIO, LISTENING, RECORDING, TRANSCRIBING, PROCESSING, ERROR, IDLE)
        machineAt(CONNECTING_AUDIO, ERROR, CONNECTING_AUDIO, LISTENING)
    }

    @Test
    fun `conflicting transitions are rejected`() {
        val sm = machineAt(CONNECTING_AUDIO, LISTENING, RECORDING)
        assertFalse("second recording", sm.transition(RECORDING))
        assertFalse(sm.transition(SPEAKING))
        assertFalse(sm.transition(REPLAYING))

        val processing = machineAt(CONNECTING_AUDIO, LISTENING, RECORDING, TRANSCRIBING, PROCESSING)
        assertFalse("recording while processing", processing.transition(RECORDING))
        assertFalse("second request", processing.transition(PROCESSING))
        assertFalse("replay while processing", processing.transition(REPLAYING))

        val speaking = machineAt(CONNECTING_AUDIO, LISTENING, RECORDING, TRANSCRIBING, PROCESSING, SPEAKING)
        assertFalse("recording while speaking", speaking.transition(RECORDING))
        assertFalse("parallel playback", speaking.transition(REPLAYING))

        val idle = AssistantStateMachine()
        assertFalse(idle.transition(LISTENING))
        assertFalse(idle.transition(RECORDING))
    }

    @Test
    fun `every active state can fail and stop`() {
        AssistantStateMachine.allowed.keys.filter { it != IDLE && it != ERROR }.forEach { s ->
            assertTrue("$s → ERROR", ERROR in AssistantStateMachine.allowed.getValue(s))
            assertTrue("$s → IDLE", IDLE in AssistantStateMachine.allowed.getValue(s))
        }
    }

    @Test
    fun `compare-and-set only applies from the expected state`() {
        val sm = machineAt(CONNECTING_AUDIO, LISTENING)
        assertFalse(sm.transition(RECORDING, TRANSCRIBING))
        assertTrue(sm.transition(LISTENING, RECORDING))
    }
}
