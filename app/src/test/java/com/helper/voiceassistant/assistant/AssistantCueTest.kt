package com.helper.voiceassistant.assistant

import com.helper.voiceassistant.detector.BeepEvent
import com.helper.voiceassistant.detector.BeepEventProcessor
import com.helper.voiceassistant.detector.BeepEventType
import com.helper.voiceassistant.detector.ButtonGesture
import com.helper.voiceassistant.detector.ButtonGestureType
import com.helper.voiceassistant.detector.TonePulse
import com.helper.voiceassistant.domain.model.AssistantMode
import com.helper.voiceassistant.domain.model.AssistantSettings
import com.helper.voiceassistant.domain.model.AssistantState
import com.helper.voiceassistant.domain.model.HeadsetButtonConfig
import com.helper.voiceassistant.domain.model.LibraryItem
import com.helper.voiceassistant.domain.model.RecordingControl
import com.helper.voiceassistant.domain.model.UiLanguage
import com.helper.voiceassistant.util.AppLanguage
import com.helper.voiceassistant.processor.AudioProcessor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Recording start / end sounds: the recording begins after the start sound, the end sound follows it. */
@OptIn(ExperimentalCoroutinesApi::class)
class AssistantCueTest {
    @get:Rule val tmp = TemporaryFolder()

    private val pipeline = FakePipeline()
    private val routing = FakeRouting()
    private val stt = FakeStt()
    private val chat = FakeChat()
    private val speaker = FakeSpeaker()
    private val buttons = FakeButtons()
    private val library = FakeLibrary()
    private val player = FakeClipPlayer()
    private val cues = FakeCues(speaker)
    private val history = FakeConversations()
    private var settings = AssistantSettings(spokenFeedback = false, sttConsentGiven = true)

    @Before fun russianUi() { AppLanguage.setting = UiLanguage.RUSSIAN }
    @After fun systemUi() { AppLanguage.setting = UiLanguage.SYSTEM }

    private fun TestScope.controller(): AssistantController = AssistantController(
        pipeline, routing, stt, chat, speaker, buttons, library, player, cues, FakeSettings(settings), history, AudioProcessor(),
        tmp.newFolder(), StandardTestDispatcher(testScheduler),
    )

    private fun pulse(startMs: Int) = TonePulse(startMs * 16L, (startMs + 150) * 16L, 150, -10f, 2000f)
    private fun single(p: TonePulse) = BeepEvent(BeepEventType.SINGLE_BEEP, listOf(p), p.endSample)
    private fun press(atMs: Long) =
        buttons.gestures.tryEmit(ButtonGesture(ButtonGestureType.SINGLE, HeadsetButtonConfig.KEYCODE_MEDIA_PLAY_PAUSE, atMs))

    private fun TestScope.listening(): AssistantController {
        val c = controller()
        c.start()
        advanceUntilIdle()
        assertEquals(AssistantState.LISTENING, c.state.value)
        return c
    }

    @Test
    fun `the recording starts once the start sound has ended, beeps are ignored meanwhile`() = runTest {
        val c = listening()
        pipeline.now = 80_000 // where the stream is when the sound has ended
        pipeline.beepEvents.tryEmit(single(pulse(1000)))
        runCurrent()
        assertEquals(AssistantState.RECORDING, c.state.value)
        assertEquals(listOf(RecordingCue.START), cues.played)
        assertTrue("no recording while the sound plays", pipeline.recordingStarts.isEmpty())
        assertEquals("the sound is not taken for a beep", BeepEventProcessor.Mode.IGNORE, pipeline.modes.last())

        advanceTimeBy(cues.durationMs + 50)
        runCurrent()
        assertEquals("records from the end of the sound", 80_000L, pipeline.recordingStarts.single().second)
        assertEquals("listens for the stop beep again", BeepEventProcessor.Mode.RECORDING, pipeline.modes.last())
        assertEquals("Говорите.", c.status.value.info)
        c.stop()
    }

    @Test
    fun `the end sound plays when the recording ends, the answer is spoken after it`() = runTest {
        val c = listening()
        pipeline.beepEvents.tryEmit(single(pulse(1000)))
        advanceUntilIdle()
        pipeline.beepEvents.tryEmit(single(pulse(5000)))
        advanceUntilIdle()
        assertEquals(listOf(RecordingCue.START, RecordingCue.END), cues.played)
        assertEquals("nothing spoken before the end sound", 0, cues.spokenBefore.last())
        assertEquals(1, stt.calls)
        assertEquals(listOf("Ответ"), speaker.spoken)
        assertEquals(AssistantState.LISTENING, c.state.value)
        c.stop()
    }

    @Test
    fun `ending the request during the start sound cancels it quietly`() = runTest {
        val c = listening()
        c.talk()
        runCurrent()
        assertEquals(AssistantState.RECORDING, c.state.value)
        advanceTimeBy(100)
        c.talk() // «Готово» while the sound still plays
        advanceUntilIdle()
        assertEquals(AssistantState.LISTENING, c.state.value)
        assertTrue("no recording was started", pipeline.recordingStarts.isEmpty())
        assertEquals(0, stt.calls)
        assertEquals(listOf(RecordingCue.START, RecordingCue.END), cues.played)
        assertEquals("Запись отменена.", c.status.value.info)
        c.stop()
    }

    @Test
    fun `headset microphone - start sound through the call link, end sound after switching back`() = runTest {
        routing.bluetoothHeadset = true
        val c = listening()
        press(1000)
        advanceUntilIdle()
        assertEquals(AssistantState.RECORDING, c.state.value)
        assertEquals(listOf(false, true), routing.connects)
        routing.headsetVoiceEnded.tryEmit(Unit) // tap on the headset: it hangs up the call
        advanceUntilIdle()
        assertEquals(listOf(false, true, false), routing.connects)
        assertEquals(listOf(RecordingCue.START, RecordingCue.END), cues.played)
        assertEquals("start: call link; end: music link", listOf(true, false), cues.routes)
        assertEquals(1, stt.calls)
        c.stop()
    }

    @Test
    fun `voice search uses the same sounds before the request and the choice answer`() = runTest {
        library.list = listOf(
            LibraryItem("b", "Зарядка утро", listOf("зарядка"), "b.mp3", "b.mp3", "audio/mpeg", 1000, 1000, 1, 1),
            LibraryItem("c", "Зарядка вечер", listOf("зарядка"), "c.mp3", "c.mp3", "audio/mpeg", 1000, 1000, 2, 2),
        )
        val c = listening()
        c.setMode(AssistantMode.SEARCH)
        advanceUntilIdle()
        stt.result = { "зарядка" }
        press(1000)
        advanceUntilIdle()
        press(4000)
        advanceUntilIdle()
        // The options are read out, then the answer recording opens with the start sound.
        assertEquals(AssistantState.RECORDING, c.state.value)
        assertEquals(listOf(RecordingCue.START, RecordingCue.END, RecordingCue.START), cues.played)
        stt.result = { "второй" }
        pipeline.events.tryEmit(PipelineEvent.SilenceDetected(200_000))
        advanceUntilIdle()
        assertEquals(listOf("Зарядка вечер"), player.played)
        assertEquals(RecordingCue.END, cues.played.last())
        c.stop()
    }

    @Test
    fun `sounds off - the recording starts at the beep and nothing is played`() = runTest {
        settings = settings.copy(recording = RecordingControl(cueSounds = false))
        val c = listening()
        pipeline.beepEvents.tryEmit(single(pulse(1000)))
        advanceUntilIdle()
        assertEquals(1150L * 16 + settings.beep.guardMs * 16L, pipeline.recordingStarts.single().second)
        pipeline.beepEvents.tryEmit(single(pulse(5000)))
        advanceUntilIdle()
        assertTrue(cues.played.isEmpty())
        assertEquals(1, stt.calls)
        c.stop()
    }
}
