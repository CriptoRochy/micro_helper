package com.helper.voiceassistant.assistant

import com.helper.voiceassistant.TestAudio
import com.helper.voiceassistant.detector.BeepEvent
import com.helper.voiceassistant.detector.BeepEventProcessor
import com.helper.voiceassistant.detector.BeepEventType
import com.helper.voiceassistant.detector.ButtonGesture
import com.helper.voiceassistant.detector.ButtonGestureType
import com.helper.voiceassistant.domain.model.HeadsetButtonConfig
import com.helper.voiceassistant.domain.model.LibraryItem
import com.helper.voiceassistant.domain.model.BluetoothMicMode
import com.helper.voiceassistant.domain.model.RecordingControl
import com.helper.voiceassistant.processor.AutoStopConfig
import com.helper.voiceassistant.detector.TonePulse
import com.helper.voiceassistant.domain.model.AssistantFailure
import com.helper.voiceassistant.domain.model.AssistantSettings
import com.helper.voiceassistant.domain.model.AssistantState
import com.helper.voiceassistant.domain.model.AudioDeviceKind
import com.helper.voiceassistant.domain.model.AudioRouteInfo
import com.helper.voiceassistant.domain.model.BeepConfig
import com.helper.voiceassistant.domain.model.ChatMessage
import com.helper.voiceassistant.domain.model.ConversationEntry
import com.helper.voiceassistant.domain.model.ErrorKind
import com.helper.voiceassistant.domain.model.ExchangeStatus
import com.helper.voiceassistant.domain.repository.ConversationRepository
import com.helper.voiceassistant.domain.repository.SettingsRepository
import com.helper.voiceassistant.processor.AudioProcessor
import com.helper.voiceassistant.recorder.RecordingResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

// ───────────── fakes ─────────────

class FakePipeline : AudioPipeline {
    override val beepEvents = MutableSharedFlow<BeepEvent>(extraBufferCapacity = 16)
    override val events = MutableSharedFlow<PipelineEvent>(extraBufferCapacity = 16)
    override val micLevelDb = MutableStateFlow(-120f)
    override var isRunning = false
    override val sampleRate = 16_000
    val modes = mutableListOf<BeepEventProcessor.Mode>()
    val recordingStarts = mutableListOf<Pair<File, Long>>()
    var stopEndSample: Long? = null
    var recordingContent: ShortArray? = TestAudio.voiceLike(1500)
    private var current: File? = null

    override fun start(config: PipelineConfig) { isRunning = true; autoStop = config.autoStop }
    override fun stop() { isRunning = false }
    override fun setMode(mode: BeepEventProcessor.Mode) { modes += mode }
    var autoStop: AutoStopConfig? = null
    /** Fake clock: sample index = uptime ms × 16. */
    override fun sampleAtUptime(uptimeMs: Long): Long = uptimeMs * 16
    var now = 0L
    override fun currentSample(): Long = now
    var maxRecordingMs = 0L
    override fun updateConfig(beep: BeepConfig, maxRecordingMs: Long, autoStop: AutoStopConfig?) { this.autoStop = autoStop; this.maxRecordingMs = maxRecordingMs }
    override fun startRecording(file: File, fromSample: Long) { recordingStarts += file to fromSample; current = file }
    override fun stopRecording(endSample: Long?): RecordingResult? {
        stopEndSample = endSample
        val file = current ?: return null
        current = null
        val content = recordingContent ?: return null
        TestAudio.writeWav(file, content)
        return RecordingResult(file, content.size.toLong(), content.size * 1000L / 16_000)
    }
    override fun cancelRecording() { current = null }
}

class FakeButtons : HeadsetButtons {
    override val gestures = MutableSharedFlow<ButtonGesture>(extraBufferCapacity = 16)
    val owners = mutableSetOf<String>()
    var reclaims = 0
    override fun acquire(owner: String) { owners += owner }
    override fun release(owner: String) { owners -= owner }
    override fun updateConfig(config: HeadsetButtonConfig) = Unit
    override fun reclaim() { reclaims++ }
}

class FakeRouting : AudioRouting {
    override val route = MutableStateFlow(AudioRouteInfo())
    override val inputLost = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    override val headsetAdded = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    override val headsetVoiceEnded = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    override fun startMonitoring() = Unit
    override fun stopMonitoring() = Unit
    /** true = a Bluetooth headset mic exists, so on-demand mode applies. Default false (behaves like a wired headset). */
    var bluetoothHeadset = false
    /** useHeadsetMic of every connect call. */
    val connects = mutableListOf<Boolean>()
    override suspend fun connect(preferredInputId: Int?, useHeadsetMic: Boolean, voiceRecognition: Boolean): RouteResult {
        connects += useHeadsetMic
        route.value = if (bluetoothHeadset && !useHeadsetMic) {
            AudioRouteInfo(true, "Phone", AudioDeviceKind.BUILTIN_MIC, "Headset", false)
        } else {
            AudioRouteInfo(true, "Headset", AudioDeviceKind.BLUETOOTH_SCO, "Headset", true)
        }
        return RouteResult(route.value, if (route.value.inputKind == AudioDeviceKind.BUILTIN_MIC) 1 else 7, bluetoothHeadset)
    }
    override fun disconnect() = Unit
}

class FakeStt : SttEngine {
    var calls = 0
    var result: () -> String = { "Привет" }
    override suspend fun transcribe(file: File, settings: AssistantSettings): String { calls++; return result() }
}

class FakeChat : ChatClient {
    var calls = 0
    val requests = mutableListOf<List<ChatMessage>>()
    var gate: CompletableDeferred<Unit>? = null
    var result: () -> String = { "Ответ" }
    override suspend fun complete(messages: List<ChatMessage>, settings: AssistantSettings): String {
        calls++; requests += messages
        gate?.await()
        return result()
    }
}

class FakeSpeaker : Speaker {
    override val interruptions = MutableSharedFlow<Unit>()
    val spoken = mutableListOf<String>()
    var failWith: AssistantFailure? = null
    override suspend fun speak(text: String, settings: AssistantSettings, communicationRoute: Boolean) {
        spoken += text
        failWith?.let { throw it }
    }
    override fun stop() = Unit
    override fun shutdown() = Unit
}

class FakeLibrary(var list: List<LibraryItem> = emptyList()) : AudioLibrary {
    override suspend fun items() = list
    override fun fileFor(item: LibraryItem) = File(item.fileName)
}

class FakeClipPlayer : ClipPlayer {
    override val events = MutableSharedFlow<ClipEvent>(extraBufferCapacity = 8)
    override val state = MutableStateFlow(ClipState())
    val played = mutableListOf<String>()
    var pauses = 0
    var resumes = 0
    var stops = 0
    var failWith: Exception? = null
    override fun play(file: File, title: String) {
        failWith?.let { throw it }
        played += title
        state.value = ClipState(title, playing = true, positionMs = 0, durationMs = 1000)
    }
    override fun pause() { pauses++; state.value = state.value.copy(playing = false) }
    override fun resume() { resumes++; state.value = state.value.copy(playing = true) }
    override fun seekBy(deltaMs: Long) = Unit
    override fun stop() { stops++; state.value = ClipState() }
}

class FakeCues(private val speaker: FakeSpeaker? = null) : CuePlayer {
    val played = mutableListOf<RecordingCue>()
    /** communicationRoute of every sound. */
    val routes = mutableListOf<Boolean>()
    /** How much speech had been said when each sound started. */
    val spokenBefore = mutableListOf<Int>()
    var durationMs = 500L
    var stops = 0
    override suspend fun play(cue: RecordingCue, communicationRoute: Boolean) {
        played += cue; routes += communicationRoute; spokenBefore += speaker?.spoken?.size ?: 0
        kotlinx.coroutines.delay(durationMs)
    }
    override fun stop() { stops++ }
}

class FakeSettings(initial: AssistantSettings) : SettingsRepository {
    private val state = MutableStateFlow(initial)
    override val settings: Flow<AssistantSettings> = state
    override suspend fun current() = state.value
    override suspend fun update(transform: (AssistantSettings) -> AssistantSettings) = state.update(transform)
}

class FakeConversations : ConversationRepository {
    val items = MutableStateFlow<List<ConversationEntry>>(emptyList())
    private var nextId = 1L
    override fun observeAll() = items.map { it.sortedByDescending { e -> e.timestampMs } }
    override suspend fun get(id: Long) = items.value.firstOrNull { it.id == id }
    override suspend fun add(entry: ConversationEntry, limit: Int): Long {
        val id = nextId++
        items.update { (it + entry.copy(id = id)).takeLast(limit) }
        return id
    }
    override suspend fun lastSuccessful(count: Int) =
        items.value.filter { it.status == ExchangeStatus.SUCCESS }.sortedByDescending { it.id }.take(count)
    override suspend fun delete(id: Long) = items.update { list -> list.filterNot { it.id == id } }
    override suspend fun clear() { items.value = emptyList() }
}

// ───────────── tests ─────────────

@OptIn(ExperimentalCoroutinesApi::class)
class AssistantControllerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val pipeline = FakePipeline()
    private val routing = FakeRouting()
    private val stt = FakeStt()
    private val chat = FakeChat()
    private val speaker = FakeSpeaker()
    private val buttons = FakeButtons()
    private val library = FakeLibrary()
    private val player = FakeClipPlayer()
    private val cues = FakeCues()
    private val history = FakeConversations()
    private var settings = AssistantSettings(spokenFeedback = false, sttConsentGiven = true, recording = RecordingControl(cueSounds = false))

    private fun TestScope.controller(): AssistantController = AssistantController(
        pipeline, routing, stt, chat, speaker, buttons, library, player, cues, FakeSettings(settings), history, AudioProcessor(),
        tmp.newFolder(), StandardTestDispatcher(testScheduler),
    )

    private fun pulse(startMs: Int, durMs: Int = 150) = TonePulse(startMs * 16L, (startMs + durMs) * 16L, durMs, -10f, 2000f)
    private fun single(p: TonePulse) = BeepEvent(BeepEventType.SINGLE_BEEP, listOf(p), p.endSample)
    private fun double(a: TonePulse, b: TonePulse) = BeepEvent(BeepEventType.DOUBLE_BEEP, listOf(a, b), b.endSample)

    private fun TestScope.startListening(c: AssistantController) {
        c.start()
        advanceUntilIdle()
        assertEquals(AssistantState.LISTENING, c.state.value)
    }

    private fun TestScope.fullExchange(c: AssistantController) {
        pipeline.beepEvents.tryEmit(single(pulse(1000)))
        advanceUntilIdle()
        assertEquals(AssistantState.RECORDING, c.state.value)
        pipeline.beepEvents.tryEmit(single(pulse(5000)))
        advanceUntilIdle()
    }

    @Test
    fun `beep, speech, beep runs STT, DeepSeek and TTS, then listens again`() = runTest {
        val c = controller()
        startListening(c)
        fullExchange(c)

        assertEquals(AssistantState.LISTENING, c.state.value)
        val guard = settings.beep.guardMs * 16L
        assertEquals("recording starts after beep 1", pulse(1000).endSample + guard, pipeline.recordingStarts.single().second)
        assertEquals("recording ends before beep 2", pulse(5000).startSample - guard, pipeline.stopEndSample)
        assertEquals(1, stt.calls)
        assertEquals(1, chat.calls)
        assertEquals(listOf("Ответ"), speaker.spoken)
        assertEquals("Ответ", c.lastResponse.value)
        assertEquals("Привет", c.status.value.lastRecognizedText)
        assertEquals(ExchangeStatus.SUCCESS, history.items.value.single().status)
        assertFalse("temporary recording deleted", pipeline.recordingStarts.single().first.exists())
        // system prompt + user message
        assertEquals(ChatMessage.Role.SYSTEM, chat.requests.single().first().role)
        assertEquals("Привет", chat.requests.single().last().content)
        assertEquals(BeepEventProcessor.Mode.LISTENING, pipeline.modes.last())
        c.stop()
    }

    @Test
    fun `double beep replays the last answer without STT or a new DeepSeek request`() = runTest {
        val c = controller()
        startListening(c)
        fullExchange(c)

        pipeline.beepEvents.tryEmit(double(pulse(9000), pulse(9300)))
        advanceUntilIdle()

        assertEquals(listOf("Ответ", "Ответ"), speaker.spoken)
        assertEquals(1, chat.calls)
        assertEquals(1, stt.calls)
        assertEquals("double beep must not start a recording", 1, pipeline.recordingStarts.size)
        assertEquals(AssistantState.LISTENING, c.state.value)
        c.stop()
    }

    @Test
    fun `double beep with no stored answer only notifies`() = runTest {
        val c = controller()
        startListening(c)
        pipeline.beepEvents.tryEmit(double(pulse(1000), pulse(1300)))
        advanceUntilIdle()
        assertTrue(speaker.spoken.isEmpty())
        assertTrue(pipeline.recordingStarts.isEmpty())
        assertEquals(ErrorKind.NOTHING_TO_REPLAY, c.status.value.error?.kind)
        assertEquals(AssistantState.LISTENING, c.state.value)
        c.stop()
    }

    @Test
    fun `last answer is restored from history and replayable after restart`() = runTest {
        history.add(ConversationEntry(timestampMs = 1, userText = "q", responseText = "Сохранённый ответ", status = ExchangeStatus.SUCCESS), 20)
        val c = controller()
        startListening(c)
        pipeline.beepEvents.tryEmit(double(pulse(1000), pulse(1300)))
        advanceUntilIdle()
        assertEquals(listOf("Сохранённый ответ"), speaker.spoken)
        assertEquals(0, chat.calls)
        c.stop()
    }

    @Test
    fun `beeps are ignored while the request is processing`() = runTest {
        chat.gate = CompletableDeferred()
        val c = controller()
        startListening(c)
        fullExchange(c)
        assertEquals(AssistantState.PROCESSING, c.state.value)

        pipeline.beepEvents.tryEmit(single(pulse(7000)))
        pipeline.beepEvents.tryEmit(double(pulse(8000), pulse(8300)))
        advanceUntilIdle()
        assertEquals(1, pipeline.recordingStarts.size)
        assertEquals(1, chat.calls)
        assertTrue(speaker.spoken.isEmpty())

        chat.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("Ответ"), speaker.spoken)
        assertEquals(AssistantState.LISTENING, c.state.value)
        c.stop()
    }

    @Test
    fun `empty recording returns to listening without STT`() = runTest {
        pipeline.recordingContent = null
        val c = controller()
        startListening(c)
        fullExchange(c)
        assertEquals(AssistantState.LISTENING, c.state.value)
        assertEquals(ErrorKind.EMPTY_RECORDING, c.status.value.error?.kind)
        assertEquals(0, stt.calls)
        c.stop()
    }

    @Test
    fun `silent recording is rejected before STT`() = runTest {
        pipeline.recordingContent = TestAudio.silence(1500)
        val c = controller()
        startListening(c)
        fullExchange(c)
        assertEquals(ErrorKind.EMPTY_RECORDING, c.status.value.error?.kind)
        assertEquals(0, stt.calls)
        c.stop()
    }

    @Test
    fun `STT failure is reported and the assistant keeps listening`() = runTest {
        stt.result = { throw AssistantFailure(ErrorKind.STT_FAILED, "not recognized") }
        val c = controller()
        startListening(c)
        fullExchange(c)
        assertEquals(AssistantState.LISTENING, c.state.value)
        assertEquals(ErrorKind.STT_FAILED, c.status.value.error?.kind)
        assertEquals(0, chat.calls)
        assertEquals(ExchangeStatus.STT_ERROR, history.items.value.single().status)
        c.stop()
    }

    @Test
    fun `DeepSeek failure is reported and nothing is spoken`() = runTest {
        chat.result = { throw AssistantFailure(ErrorKind.INVALID_API_KEY, "bad key") }
        val c = controller()
        startListening(c)
        fullExchange(c)
        assertEquals(AssistantState.LISTENING, c.state.value)
        assertEquals(ErrorKind.INVALID_API_KEY, c.status.value.error?.kind)
        assertTrue(speaker.spoken.isEmpty())
        assertNull(c.lastResponse.value)
        assertEquals(ExchangeStatus.API_ERROR, history.items.value.single().status)
        c.stop()
    }

    @Test
    fun `TTS failure keeps the answer and returns to listening`() = runTest {
        speaker.failWith = AssistantFailure(ErrorKind.TTS_FAILED, "tts broken")
        val c = controller()
        startListening(c)
        fullExchange(c)
        assertEquals(AssistantState.LISTENING, c.state.value)
        assertEquals(ErrorKind.TTS_FAILED, c.status.value.error?.kind)
        assertEquals("Ответ", c.lastResponse.value)
        c.stop()
    }

    @Test
    fun `previous exchanges are sent as context`() = runTest {
        val c = controller()
        startListening(c)
        fullExchange(c)
        stt.result = { "И ещё вопрос" }
        fullExchange(c)
        val second = chat.requests.last()
        assertEquals(listOf("system", "user", "assistant", "user"), second.map { it.role.wire })
        assertEquals("Ответ", second[2].content)
        c.stop()
    }

    @Test
    fun `stop returns to idle and releases audio`() = runTest {
        val c = controller()
        startListening(c)
        assertTrue(pipeline.isRunning)
        c.stop()
        advanceUntilIdle()
        assertEquals(AssistantState.IDLE, c.state.value)
        assertFalse(pipeline.isRunning)
        assertEquals(AssistantState.IDLE, c.status.first().state)
    }

    // ───────────── headset button & recording triggers ─────────────

    private fun press(type: ButtonGestureType, atMs: Long) =
        buttons.gestures.tryEmit(ButtonGesture(type, HeadsetButtonConfig.KEYCODE_MEDIA_PLAY_PAUSE, atMs))

    @Test
    fun `headset button starts and ends a recording like a beep`() = runTest {
        val c = controller()
        startListening(c)
        assertTrue("button session acquired", buttons.owners.isNotEmpty())
        press(ButtonGestureType.SINGLE, 1000)
        advanceUntilIdle()
        assertEquals(AssistantState.RECORDING, c.state.value)
        val r = settings.recording
        assertEquals((1000 + r.buttonStartOffsetMs) * 16L, pipeline.recordingStarts.single().second)
        press(ButtonGestureType.SINGLE, 5000)
        advanceUntilIdle()
        assertEquals((5000 - r.buttonEndTrimMs) * 16L, pipeline.stopEndSample)
        assertEquals(1, stt.calls)
        assertEquals(listOf("Ответ"), speaker.spoken)
        assertEquals(AssistantState.LISTENING, c.state.value)
        assertTrue("media-button focus reclaimed after speaking", buttons.reclaims > 0)
        c.stop()
        assertTrue("button session released", buttons.owners.isEmpty())
    }

    @Test
    fun `double press replays and long press cancels a recording`() = runTest {
        history.add(ConversationEntry(timestampMs = 1, userText = "q", responseText = "Старый ответ", status = ExchangeStatus.SUCCESS), 20)
        val c = controller()
        startListening(c)
        press(ButtonGestureType.DOUBLE, 1000)
        advanceUntilIdle()
        assertEquals(listOf("Старый ответ"), speaker.spoken)
        assertEquals(0, stt.calls)

        press(ButtonGestureType.SINGLE, 3000)
        advanceUntilIdle()
        assertEquals(AssistantState.RECORDING, c.state.value)
        press(ButtonGestureType.LONG, 4000)
        advanceUntilIdle()
        assertEquals(AssistantState.LISTENING, c.state.value)
        assertEquals(0, stt.calls)
        c.stop()
    }

    @Test
    fun `disabled triggers are ignored`() = runTest {
        settings = settings.copy(recording = RecordingControl(startByBeep = false, stopByButton = false, cueSounds = false))
        val c = controller()
        startListening(c)
        pipeline.beepEvents.tryEmit(single(pulse(1000)))
        advanceUntilIdle()
        assertEquals("beep start disabled", AssistantState.LISTENING, c.state.value)
        press(ButtonGestureType.SINGLE, 2000)
        advanceUntilIdle()
        assertEquals(AssistantState.RECORDING, c.state.value)
        press(ButtonGestureType.SINGLE, 4000)
        advanceUntilIdle()
        assertEquals("button stop disabled", AssistantState.RECORDING, c.state.value)
        pipeline.beepEvents.tryEmit(single(pulse(6000)))
        advanceUntilIdle()
        assertEquals(1, stt.calls)
        c.stop()
    }

    @Test
    fun `silence after speech ends the recording when enabled`() = runTest {
        settings = settings.copy(recording = RecordingControl(stopOnSilence = true, silenceMs = 1200, cueSounds = false))
        val c = controller()
        startListening(c)
        assertEquals(1200, pipeline.autoStop?.silenceMs)
        pipeline.beepEvents.tryEmit(single(pulse(1000)))
        advanceUntilIdle()
        pipeline.events.tryEmit(PipelineEvent.SilenceDetected(70_000))
        advanceUntilIdle()
        assertEquals(70_000L, pipeline.stopEndSample)
        assertEquals(1, stt.calls)
        c.stop()
    }

    @Test
    fun `bluetooth headset waits on the phone mic and opens the headset mic only for the recording`() = runTest {
        routing.bluetoothHeadset = true
        val c = controller()
        startListening(c)
        assertEquals("waits without the headset mic", listOf(false), routing.connects)
        assertEquals(AudioDeviceKind.BUILTIN_MIC, routing.route.value.inputKind)
        assertEquals("no silence stop unless enabled", null, pipeline.autoStop)

        press(ButtonGestureType.SINGLE, 1000)
        advanceUntilIdle()
        assertEquals(AssistantState.RECORDING, c.state.value)
        assertEquals("headset mic opened for the recording", listOf(false, true), routing.connects)
        assertEquals("records from the start of the headset stream", 0L, pipeline.recordingStarts.single().second)

        pipeline.events.tryEmit(PipelineEvent.SilenceDetected(40_000))
        advanceUntilIdle()
        assertEquals("silence ignored when not enabled", AssistantState.RECORDING, c.state.value)
        pipeline.beepEvents.tryEmit(single(pulse(5000)))
        advanceUntilIdle()
        assertEquals("back to the phone mic after recording", listOf(false, true, false), routing.connects)
        assertEquals(1, stt.calls)
        assertEquals(listOf("Ответ"), speaker.spoken)
        assertEquals(AssistantState.LISTENING, c.state.value)
        assertTrue(pipeline.isRunning)
        c.stop()
    }

    @Test
    fun `headset closing its voice link ends the recording like a button press`() = runTest {
        routing.bluetoothHeadset = true
        val c = controller()
        startListening(c)
        press(ButtonGestureType.SINGLE, 1000)
        advanceUntilIdle()
        assertEquals(AssistantState.RECORDING, c.state.value)
        routing.headsetVoiceEnded.tryEmit(Unit)
        advanceUntilIdle()
        assertEquals(1, stt.calls)
        assertEquals(AssistantState.LISTENING, c.state.value)
        assertEquals("back on the phone mic", listOf(false, true, false), routing.connects)
        c.stop()
    }

    @Test
    fun `headset mic stays open when on-demand mode is off`() = runTest {
        routing.bluetoothHeadset = true
        settings = settings.copy(recording = RecordingControl(bluetoothMic = BluetoothMicMode.ALWAYS, cueSounds = false))
        val c = controller()
        startListening(c)
        fullExchange(c)
        assertEquals(listOf(true), routing.connects)
        assertEquals(1, stt.calls)
        c.stop()
    }

    @Test
    fun `phone-mic mode never opens the headset mic, buttons start and end the recording`() = runTest {
        routing.bluetoothHeadset = true
        settings = settings.copy(recording = RecordingControl(bluetoothMic = BluetoothMicMode.NEVER, cueSounds = false))
        val c = controller()
        startListening(c)
        press(ButtonGestureType.SINGLE, 1000)
        advanceUntilIdle()
        assertEquals(AssistantState.RECORDING, c.state.value)
        assertEquals((1000 + settings.recording.buttonStartOffsetMs) * 16L, pipeline.recordingStarts.single().second)
        press(ButtonGestureType.SINGLE, 5000)
        advanceUntilIdle()
        assertEquals(1, stt.calls)
        assertEquals("headset mic never opened", listOf(false), routing.connects)
        c.stop()
    }
}
