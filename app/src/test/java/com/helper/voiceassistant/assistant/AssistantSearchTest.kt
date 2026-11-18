package com.helper.voiceassistant.assistant

import com.helper.voiceassistant.TestAudio
import com.helper.voiceassistant.detector.BeepEvent
import com.helper.voiceassistant.detector.BeepEventProcessor
import com.helper.voiceassistant.detector.BeepEventType
import com.helper.voiceassistant.detector.ButtonGesture
import com.helper.voiceassistant.detector.ButtonGestureType
import com.helper.voiceassistant.detector.TonePulse
import com.helper.voiceassistant.domain.model.AssistantFailure
import com.helper.voiceassistant.domain.model.AssistantMode
import com.helper.voiceassistant.domain.model.AssistantSettings
import com.helper.voiceassistant.domain.model.AssistantState
import com.helper.voiceassistant.domain.model.ErrorKind
import com.helper.voiceassistant.domain.model.HeadsetButtonConfig
import com.helper.voiceassistant.domain.model.LibraryItem
import com.helper.voiceassistant.processor.AudioProcessor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import com.helper.voiceassistant.domain.model.UiLanguage
import com.helper.voiceassistant.util.AppLanguage
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Voice search over the audio library: no DeepSeek, no history, choice prompts, clip playback. */
@OptIn(ExperimentalCoroutinesApi::class)
class AssistantSearchTest {
    @get:Rule val tmp = TemporaryFolder()

    private val pipeline = FakePipeline()
    private val routing = FakeRouting()
    private val stt = FakeStt()
    private val chat = FakeChat()
    private val speaker = FakeSpeaker()
    private val buttons = FakeButtons()
    private val library = FakeLibrary()
    private val player = FakeClipPlayer()
    private val history = FakeConversations()
    private var settings = AssistantSettings(spokenFeedback = false, sttConsentGiven = true)

    private val schedule = item("a", "Расписание", listOf("расписание"), 1)
    private val warmupA = item("b", "Зарядка утро", listOf("зарядка"), 2)
    private val warmupB = item("c", "Зарядка вечер", listOf("зарядка"), 3)

    @Before fun russianUi() { AppLanguage.setting = UiLanguage.RUSSIAN }
    @After fun systemUi() { AppLanguage.setting = UiLanguage.SYSTEM }

    private fun item(id: String, title: String, phrases: List<String>, created: Long) =
        LibraryItem(id, title, phrases, "$id.mp3", "$id.mp3", "audio/mpeg", 1000, 1000, created, created)

    private fun TestScope.controller(): AssistantController = AssistantController(
        pipeline, routing, stt, chat, speaker, buttons, library, player, FakeSettings(settings), history, AudioProcessor(),
        tmp.newFolder(), StandardTestDispatcher(testScheduler),
    )

    private fun pulse(startMs: Int) = TonePulse(startMs * 16L, (startMs + 150) * 16L, 150, -10f, 2000f)
    private fun single(p: TonePulse) = BeepEvent(BeepEventType.SINGLE_BEEP, listOf(p), p.endSample)
    private fun press(atMs: Long) =
        buttons.gestures.tryEmit(ButtonGesture(ButtonGestureType.SINGLE, HeadsetButtonConfig.KEYCODE_MEDIA_PLAY_PAUSE, atMs))

    private fun TestScope.searching(): AssistantController {
        val c = controller()
        c.start()
        c.setMode(AssistantMode.SEARCH)
        advanceUntilIdle()
        assertEquals(AssistantState.LISTENING, c.state.value)
        assertEquals(AssistantMode.SEARCH, c.status.value.mode)
        return c
    }

    /** Beep — phrase — beep, recognized as [phrase]. */
    private fun TestScope.ask(c: AssistantController, phrase: String) {
        stt.result = { phrase }
        pipeline.beepEvents.tryEmit(single(pulse(1000)))
        advanceUntilIdle()
        assertEquals(AssistantState.RECORDING, c.state.value)
        pipeline.beepEvents.tryEmit(single(pulse(5000)))
        advanceUntilIdle()
    }

    @Test
    fun `a phrase that matches one entry plays it without DeepSeek or history`() = runTest {
        library.list = listOf(schedule, warmupA)
        val c = searching()
        ask(c, "Расписание!")
        assertEquals(AssistantState.PLAYING_CLIP, c.state.value)
        assertEquals(listOf("Расписание"), player.played)
        assertEquals(0, chat.calls)
        assertTrue("search is not saved to the conversation history", history.items.value.isEmpty())
        assertEquals("Расписание!", c.status.value.lastRecognizedText)
        c.stop()
    }

    @Test
    fun `no match says the recording was not found`() = runTest {
        library.list = listOf(schedule)
        val c = searching()
        ask(c, "погода")
        assertEquals(listOf("Запись не найдена."), speaker.spoken)
        assertEquals(ErrorKind.LIBRARY_NOT_FOUND, c.status.value.error?.kind)
        assertEquals(AssistantState.LISTENING, c.state.value)
        assertTrue(player.played.isEmpty())
        c.stop()
    }

    @Test
    fun `empty library says there are no recordings yet`() = runTest {
        val c = searching()
        ask(c, "расписание")
        assertEquals(listOf("В аудиотеке пока нет записей."), speaker.spoken)
        assertEquals(AssistantState.LISTENING, c.state.value)
        c.stop()
    }

    @Test
    fun `several matches are announced, then the answer is recorded after the prompt and picks one`() = runTest {
        library.list = listOf(schedule, warmupA, warmupB)
        val c = searching()
        pipeline.now = 200_000
        ask(c, "зарядка")

        assertEquals("Нашлось 2 записи. Первый — Зарядка утро. Второй — Зарядка вечер. Скажите номер.", speaker.spoken.single())
        assertTrue("the prompt itself selects nothing", player.played.isEmpty())
        assertEquals(AssistantState.RECORDING, c.state.value)
        assertEquals(listOf("Зарядка утро", "Зарядка вечер"), c.status.value.choiceOptions)
        assertEquals("answer recording starts after the prompt", 200_000 + 300 * 16L, pipeline.recordingStarts.last().second)
        assertNotNull("the answer ends on silence", pipeline.autoStop)
        assertEquals(10_000L, pipeline.maxRecordingMs)

        stt.result = { "второй" }
        pipeline.events.tryEmit(PipelineEvent.SilenceDetected(300_000))
        advanceUntilIdle()

        assertEquals(listOf("Зарядка вечер"), player.played)
        assertEquals(AssistantState.PLAYING_CLIP, c.state.value)
        assertNull(c.status.value.choiceOptions)
        assertNull("silence stop is off again after the answer", pipeline.autoStop)
        assertEquals(0, chat.calls)
        c.stop()
    }

    @Test
    fun `an unclear answer repeats the options`() = runTest {
        library.list = listOf(warmupA, warmupB)
        val c = searching()
        ask(c, "зарядка")
        stt.result = { "зарядка" } // matches both titles
        pipeline.events.tryEmit(PipelineEvent.SilenceDetected(300_000))
        advanceUntilIdle()

        assertEquals(2, speaker.spoken.size)
        assertTrue(speaker.spoken[1].startsWith("Не понял выбор."))
        assertEquals(AssistantState.RECORDING, c.state.value)
        assertTrue(player.played.isEmpty())

        stt.result = { "утро" }
        pipeline.events.tryEmit(PipelineEvent.SilenceDetected(400_000))
        advanceUntilIdle()
        assertEquals(listOf("Зарядка утро"), player.played)
        c.stop()
    }

    @Test
    fun `a new key phrase instead of a number starts a new search`() = runTest {
        library.list = listOf(schedule, warmupA, warmupB)
        val c = searching()
        ask(c, "зарядка")
        stt.result = { "расписание" }
        pipeline.events.tryEmit(PipelineEvent.SilenceDetected(300_000))
        advanceUntilIdle()
        assertEquals(listOf("Расписание"), player.played)
        assertNull(c.status.value.choiceOptions)
        c.stop()
    }

    @Test
    fun `silence instead of an answer drops the choice`() = runTest {
        library.list = listOf(warmupA, warmupB)
        val c = searching()
        ask(c, "зарядка")
        pipeline.recordingContent = null // nothing recorded
        pipeline.events.tryEmit(PipelineEvent.RecordingLimitReached)
        advanceUntilIdle()
        assertEquals(AssistantState.LISTENING, c.state.value)
        assertNull(c.status.value.choiceOptions)
        assertEquals("Не удалось распознать фразу.", speaker.spoken.last())

        pipeline.recordingContent = TestAudio.voiceLike(1500)
        ask(c, "второй") // no longer an answer: searched as a phrase
        assertTrue(player.played.isEmpty())
        assertEquals("Запись не найдена.", speaker.spoken.last())
        c.stop()
    }

    @Test
    fun `endless unclear answers cancel the choice`() = runTest {
        library.list = listOf(warmupA, warmupB)
        val c = searching()
        ask(c, "зарядка")
        stt.result = { "что-то" }
        repeat(3) {
            pipeline.events.tryEmit(PipelineEvent.SilenceDetected(300_000L + it))
            advanceUntilIdle()
        }
        assertEquals("Выбор отменён.", speaker.spoken.last())
        assertEquals(AssistantState.LISTENING, c.state.value)
        assertNull(c.status.value.choiceOptions)
        c.stop()
    }

    @Test
    fun `a new request during playback pauses the clip and records, a new choice replaces it`() = runTest {
        library.list = listOf(schedule, warmupA)
        val c = searching()
        ask(c, "расписание")
        assertEquals(AssistantState.PLAYING_CLIP, c.state.value)

        press(20_000)
        advanceUntilIdle()
        assertEquals(1, player.pauses)
        assertEquals(AssistantState.RECORDING, c.state.value)

        stt.result = { "зарядка утро" }
        press(25_000)
        advanceUntilIdle()
        assertEquals(listOf("Расписание", "Зарядка утро"), player.played)
        assertTrue("previous clip stopped before the new one", player.stops >= 1)
        assertEquals(AssistantState.PLAYING_CLIP, c.state.value)
        c.stop()
    }

    @Test
    fun `a beep during playback pauses the clip and takes a new request`() = runTest {
        library.list = listOf(schedule, warmupA)
        val c = searching()
        ask(c, "расписание")
        assertEquals(AssistantState.PLAYING_CLIP, c.state.value)
        assertEquals("beeps are still detected during playback", BeepEventProcessor.Mode.LISTENING, pipeline.modes.last())

        pipeline.beepEvents.tryEmit(single(pulse(20_000)))
        advanceUntilIdle()
        assertEquals(1, player.pauses)
        assertEquals(AssistantState.RECORDING, c.state.value)

        stt.result = { "зарядка утро" }
        pipeline.beepEvents.tryEmit(single(pulse(25_000)))
        advanceUntilIdle()
        assertEquals(listOf("Расписание", "Зарядка утро"), player.played)
        c.stop()
    }

    @Test
    fun `clip end returns to listening, headset disconnect keeps the clip paused`() = runTest {
        library.list = listOf(schedule)
        val c = searching()
        ask(c, "расписание")
        val stopsBefore = player.stops

        player.events.tryEmit(ClipEvent.PausedBySystem)
        advanceUntilIdle()
        assertEquals(AssistantState.LISTENING, c.state.value)
        assertEquals("clip kept loaded", stopsBefore, player.stops)

        c.resumeClip()
        advanceUntilIdle()
        assertEquals(AssistantState.PLAYING_CLIP, c.state.value)
        assertEquals(1, player.resumes)

        player.events.tryEmit(ClipEvent.Completed)
        advanceUntilIdle()
        assertEquals(AssistantState.LISTENING, c.state.value)
        c.stop()
    }

    @Test
    fun `recognition failure in search is always spoken and not saved`() = runTest {
        library.list = listOf(schedule)
        val c = searching()
        stt.result = { throw AssistantFailure(ErrorKind.STT_FAILED, "boom") }
        pipeline.beepEvents.tryEmit(single(pulse(1000)))
        advanceUntilIdle()
        pipeline.beepEvents.tryEmit(single(pulse(5000)))
        advanceUntilIdle()
        assertEquals(listOf("Не удалось распознать фразу."), speaker.spoken)
        assertEquals("Не удалось распознать фразу.", c.status.value.error?.message)
        assertTrue(history.items.value.isEmpty())
        assertEquals(AssistantState.LISTENING, c.state.value)
        c.stop()
    }

    @Test
    fun `an unplayable file reports the error and keeps listening`() = runTest {
        library.list = listOf(schedule)
        player.failWith = AssistantFailure(ErrorKind.CLIP_PLAYBACK, "bad")
        val c = searching()
        ask(c, "расписание")
        assertEquals(ErrorKind.CLIP_PLAYBACK, c.status.value.error?.kind)
        assertEquals(AssistantState.LISTENING, c.state.value)
        assertEquals(listOf("Не удалось воспроизвести запись."), speaker.spoken)
        c.stop()
    }

    @Test
    fun `switching back to dialog stops the clip and sends the next phrase to DeepSeek`() = runTest {
        library.list = listOf(schedule)
        val c = searching()
        ask(c, "расписание")
        assertEquals(AssistantState.PLAYING_CLIP, c.state.value)

        c.setMode(AssistantMode.DIALOG)
        advanceUntilIdle()
        assertEquals(AssistantState.LISTENING, c.state.value)
        assertEquals(ClipState(), player.state.value)

        ask(c, "Привет")
        assertEquals(1, chat.calls)
        assertEquals(listOf("Ответ"), speaker.spoken)
        assertEquals(1, history.items.value.size)
        c.stop()
    }

    @Test
    fun `search needs no DeepSeek consent, dialog without consent sends nothing`() = runTest {
        settings = settings.copy(sttConsentGiven = false)
        library.list = listOf(schedule)
        val c = searching()
        ask(c, "расписание")
        assertEquals(listOf("Расписание"), player.played)

        c.setMode(AssistantMode.DIALOG)
        advanceUntilIdle()
        ask(c, "Привет")
        assertEquals(0, chat.calls)
        assertEquals(ErrorKind.CONSENT_REQUIRED, c.status.value.error?.kind)
        assertEquals(AssistantState.LISTENING, c.state.value)
        c.stop()
    }

    @Test
    fun `talk button before start records once the assistant listens`() = runTest {
        library.list = listOf(schedule)
        val c = controller()
        c.setMode(AssistantMode.SEARCH)
        c.talk()
        advanceUntilIdle()
        c.start()
        advanceUntilIdle()
        assertEquals(AssistantState.RECORDING, c.state.value)
        stt.result = { "расписание" }
        c.talk()
        advanceUntilIdle()
        assertEquals(listOf("Расписание"), player.played)
        c.stop()
    }
}
