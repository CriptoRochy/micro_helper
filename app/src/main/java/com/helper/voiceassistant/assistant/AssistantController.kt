package com.helper.voiceassistant.assistant

import com.helper.voiceassistant.detector.BeepEvent
import com.helper.voiceassistant.detector.BeepEventProcessor
import com.helper.voiceassistant.detector.BeepEventType
import com.helper.voiceassistant.detector.ButtonGesture
import com.helper.voiceassistant.detector.ButtonGestureType
import com.helper.voiceassistant.domain.model.ButtonAction
import com.helper.voiceassistant.domain.model.BluetoothMicMode
import com.helper.voiceassistant.processor.AutoStopConfig
import com.helper.voiceassistant.domain.model.AssistantError
import com.helper.voiceassistant.domain.model.AssistantFailure
import com.helper.voiceassistant.domain.model.AssistantSettings
import com.helper.voiceassistant.domain.model.AssistantState
import com.helper.voiceassistant.domain.model.AssistantStatus
import com.helper.voiceassistant.domain.model.AudioDeviceKind
import com.helper.voiceassistant.domain.model.AudioRouteInfo
import com.helper.voiceassistant.domain.model.ChatMessage
import com.helper.voiceassistant.domain.model.ConversationEntry
import com.helper.voiceassistant.domain.model.ErrorKind
import com.helper.voiceassistant.domain.model.ExchangeStatus
import com.helper.voiceassistant.domain.repository.ConversationRepository
import com.helper.voiceassistant.domain.repository.SettingsRepository
import com.helper.voiceassistant.processor.AudioProcessor
import com.helper.voiceassistant.processor.RecordingCheck
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import com.helper.voiceassistant.util.tr

/**
 * Orchestrates the full hands-free loop:
 *
 *   LISTENING ─(1 beep)→ RECORDING ─(beep)→ TRANSCRIBING → PROCESSING → SPEAKING → LISTENING
 *   LISTENING ─(2 beeps)→ REPLAYING → LISTENING           (no STT, no DeepSeek request)
 *
 * Headset-button gestures drive the same transitions (single press = single beep, double = double beep),
 * and a recording can also end on silence. Which events may start / end a recording is configurable.
 *
 * All work runs on a single-parallelism dispatcher, so handlers never race each other; the state
 * machine additionally rejects any transition that would overlap recordings, requests or playback.
 */
@Singleton
class AssistantController @Inject constructor(
    private val pipeline: AudioPipeline,
    private val routing: AudioRouting,
    private val stt: SttEngine,
    private val chat: ChatClient,
    private val speaker: Speaker,
    private val buttons: HeadsetButtons,
    private val settingsRepository: SettingsRepository,
    private val conversations: ConversationRepository,
    private val audioProcessor: AudioProcessor,
    @Named("recordingsDir") private val recordingsDir: File,
    @Named("controller") dispatcher: CoroutineDispatcher,
) {
    private val stateMachine = AssistantStateMachine()
    private val appScope = CoroutineScope(SupervisorJob() + dispatcher)
    private var session: CoroutineScope? = null
    private var processingJob: Job? = null
    @Volatile private var settings = AssistantSettings()

    /**
     * "Headset mic only while recording" is in effect: a Bluetooth headset is connected, the assistant
     * waits on the phone microphone (headset in music mode, so its buttons work) and opens the headset
     * microphone only for the recording.
     */
    private var micOnDemand = false
    /** The headset microphone (call mode) is currently open for a recording. */
    private var headsetMicOpen = false
    /** A Bluetooth headset mic exists but the phone mic is used on purpose (WHILE_RECORDING or NEVER). */
    private var phoneMicByChoice = false

    /** In-memory context, used when persistent history is disabled. */
    private val sessionContext = ArrayDeque<Pair<String, String>>()

    private val _status = MutableStateFlow(AssistantStatus())
    val status: StateFlow<AssistantStatus> = _status.asStateFlow()
    val state: StateFlow<AssistantState> = stateMachine.state
    val route: StateFlow<AudioRouteInfo> get() = routing.route
    val micLevelDb: StateFlow<Float> get() = pipeline.micLevelDb

    private val _lastResponse = MutableStateFlow<String?>(null)
    /** The latest DeepSeek answer: kept in memory and restored from history on start. */
    val lastResponse: StateFlow<String?> = _lastResponse.asStateFlow()

    // ───────────────────────────── lifecycle ─────────────────────────────

    fun start() {
        if (!stateMachine.transition(AssistantState.IDLE, AssistantState.CONNECTING_AUDIO)) return
        updateStatus { it.copy(state = AssistantState.CONNECTING_AUDIO, error = null, info = tr("Connecting audio…", "Подключение аудио…")) }
        val scope = CoroutineScope(appScope.coroutineContext + SupervisorJob(appScope.coroutineContext[Job]))
        session = scope
        scope.launch {
            settings = settingsRepository.current()
            restoreLastResponse()
            scope.launch { settingsRepository.settings.collect { onSettingsChanged(it) } }
            scope.launch { pipeline.beepEvents.collect { onBeep(it) } }
            scope.launch { buttons.gestures.collect { onButton(it) } }
            buttons.updateConfig(settings.button)
            buttons.acquire(BUTTON_OWNER)
            scope.launch { pipeline.events.collect { onPipelineEvent(it) } }
            scope.launch { routing.inputLost.collect { onInputLost() } }
            scope.launch { routing.headsetAdded.collect { onHeadsetAdded() } }
            scope.launch { routing.headsetVoiceEnded.collect { onHeadsetVoiceEnded() } }
            scope.launch { speaker.interruptions.collect { speaker.stop() } }
            routing.startMonitoring()
            connectAndListen()
        }
    }

    fun stop() {
        micOnDemand = false
        phoneMicByChoice = false
        headsetMicOpen = false
        processingJob?.cancel()
        processingJob = null
        speaker.stop()
        runCatching { buttons.release(BUTTON_OWNER) }
        runCatching { pipeline.stop() }
        runCatching { routing.disconnect() }
        runCatching { routing.stopMonitoring() }
        session?.cancel()
        session = null
        stateMachine.transition(AssistantState.IDLE)
        updateStatus { it.copy(state = AssistantState.IDLE, info = null, recordingStartedAtMs = null) }
    }

    /** Called by the service when the system refuses to start it (e.g. background start restrictions). */
    fun reportStartFailure(message: String) {
        stop()
        updateStatus { it.copy(error = AssistantError(ErrorKind.AUDIO_INIT, message, tr("Open the app and press Start.", "Откройте приложение и нажмите «Запустить»."))) }
    }

    suspend fun restoreLastResponse() {
        if (_lastResponse.value == null) {
            _lastResponse.value = runCatching { conversations.lastSuccessful(1).firstOrNull()?.responseText }.getOrNull()
            updateStatus { it.copy(lastResponse = _lastResponse.value) }
        }
    }

    private suspend fun connectAndListen() {
        try {
            val mode = settings.recording.bluetoothMic
            val result = routing.connect(settings.preferredInputDeviceId.takeIf { it >= 0 }, useHeadsetMic = mode == BluetoothMicMode.ALWAYS)
            micOnDemand = mode == BluetoothMicMode.WHILE_RECORDING && result.headsetMicAvailable
            phoneMicByChoice = mode != BluetoothMicMode.ALWAYS && result.headsetMicAvailable
            headsetMicOpen = false
            pipeline.start(pipelineConfig(result.inputDeviceId))
            val info = when {
                micOnDemand -> tr(
                    "Waiting on the phone microphone, headset buttons active. The headset microphone turns on for the recording.",
                    "Ожидание — микрофон телефона, кнопки гарнитуры работают. Микрофон гарнитуры включается на время записи.",
                )
                phoneMicByChoice -> tr(
                    "Phone microphone; the headset stays in music mode, its buttons work.",
                    "Микрофон телефона; гарнитура в музыкальном режиме, её кнопки работают.",
                )
                !result.info.headsetConnected || result.info.inputKind == AudioDeviceKind.BUILTIN_MIC ->
                    tr("No headset microphone — using the phone microphone.", "Микрофон гарнитуры не найден — используется микрофон телефона.")
                result.info.inputKind == AudioDeviceKind.BLUETOOTH_SCO && !result.info.usesCommunicationRoute ->
                    tr("Bluetooth headset found but its microphone link could not be opened.", "Bluetooth-гарнитура найдена, но не удалось открыть её микрофон.")
                else -> null
            }
            moveTo(AssistantState.LISTENING) { it.copy(info = info) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AssistantFailure) {
            fatal(e)
        } catch (e: Exception) {
            Timber.e(e, "Audio start failed")
            fatal(AssistantFailure(ErrorKind.AUDIO_INIT, tr("Could not start audio capture.", "Не удалось запустить захват звука."), e))
        }
    }

    private fun fatal(failure: AssistantFailure) {
        Timber.e("Fatal: %s", failure.userMessage)
        stateMachine.transition(AssistantState.ERROR)
        updateStatus { it.copy(state = AssistantState.ERROR, error = failure.toError()) }
        val error = failure.toError()
        stop()
        updateStatus { it.copy(error = error) }
    }

    // ───────────────────────────── beep commands ─────────────────────────────

    private fun onBeep(event: BeepEvent) {
        if (!settings.beep.enabled) return
        val state = stateMachine.current
        when (event.type) {
            BeepEventType.BEEP_END -> Unit
            BeepEventType.SINGLE_BEEP -> when (state) {
                AssistantState.LISTENING ->
                    if (settings.recording.startByBeep) startRecording(event.last.endSample + guardSamples())
                    else Timber.d("Beep start disabled")
                AssistantState.RECORDING ->
                    if (settings.recording.stopByBeep) finishRecording(event.first.startSample - guardSamples(), null)
                    else Timber.d("Beep stop disabled")
                else -> Timber.d("Single beep ignored in %s", state)
            }
            BeepEventType.DOUBLE_BEEP -> when (state) {
                AssistantState.LISTENING -> replayLast()
                else -> Timber.d("Double beep ignored in %s", state)
            }
        }
    }

    // ───────────────────────────── headset button ─────────────────────────────

    private fun onButton(g: ButtonGesture) {
        val action = when (g.type) {
            ButtonGestureType.SINGLE -> settings.button.singlePress
            ButtonGestureType.DOUBLE -> settings.button.doublePress
            ButtonGestureType.LONG -> settings.button.longPress
        }
        val state = stateMachine.current
        Timber.d("Button %s → %s in %s", g.type, action, state)
        val pressSample = pipeline.sampleAtUptime(g.pressedAtMs)
        val rec = settings.recording
        when (action) {
            ButtonAction.NONE -> Unit
            ButtonAction.START_STOP_RECORDING -> when (state) {
                AssistantState.LISTENING ->
                    if (rec.startByButton) startRecording(pressSample + msToSamples(rec.buttonStartOffsetMs))
                AssistantState.RECORDING ->
                    if (rec.stopByButton) finishRecording(pressSample - msToSamples(rec.buttonEndTrimMs), null)
                AssistantState.SPEAKING, AssistantState.REPLAYING -> speaker.stop()
                else -> Unit
            }
            ButtonAction.REPLAY -> if (state == AssistantState.LISTENING) replayLastInternal()
            ButtonAction.CANCEL_RECORDING -> when (state) {
                AssistantState.RECORDING -> cancelRecordingInternal()
                AssistantState.SPEAKING, AssistantState.REPLAYING -> speaker.stop()
                else -> Unit
            }
            ButtonAction.STOP_SPEECH -> speaker.stop()
        }
    }

    private fun msToSamples(ms: Int): Long = ms.toLong() * pipeline.sampleRate / 1000

    private fun autoStopActive(s: AssistantSettings) = s.recording.stopOnSilence

    private fun autoStopConfig(s: AssistantSettings): AutoStopConfig? = s.recording.takeIf { autoStopActive(s) }?.let {
        AutoStopConfig(it.silenceMs, it.silenceThresholdDbfs, it.minSpeechBeforeAutoStopMs)
    }

    private fun pipelineConfig(inputId: Int?) =
        PipelineConfig(settings.micSource, inputId, settings.beep, settings.maxRecordingSec * 1000L, autoStopConfig(settings))

    /**
     * On-demand mode: switches capture between the phone microphone (headset in music mode) and the
     * headset microphone (call mode). Takes ~0.5-1 s while the Bluetooth voice link opens or closes.
     */
    private suspend fun switchMic(headset: Boolean) {
        if (headsetMicOpen == headset) return
        Timber.i("Switching to %s microphone", if (headset) "headset" else "phone")
        pipeline.stop()
        routing.disconnect()
        val result = routing.connect(settings.preferredInputDeviceId.takeIf { it >= 0 }, useHeadsetMic = headset, voiceRecognition = headset)
        headsetMicOpen = headset
        pipeline.start(pipelineConfig(result.inputDeviceId))
        pipeline.setMode(modeFor(stateMachine.current))
        // The call / voice link took over the media buttons: take them back for the music-mode headset.
        if (!headset) buttons.reclaim()
    }

    /** Back to the phone microphone once the recording is over (keeps the buttons working). */
    private fun closeHeadsetMicIfDone() {
        if (!micOnDemand || !headsetMicOpen || stateMachine.current == AssistantState.RECORDING) return
        session?.launch {
            try {
                if (stateMachine.current != AssistantState.RECORDING) switchMic(false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Switching back to the phone microphone failed")
                recoverAudio(AssistantFailure(ErrorKind.AUDIO_INIT, tr("Could not switch the microphone.", "Не удалось переключить микрофон."), e))
            }
        }
    }

    private fun guardSamples(): Long = settings.beep.guardMs.toLong() * pipeline.sampleRate / 1000

    private fun startRecording(fromSample: Long) {
        if (micOnDemand && !headsetMicOpen) {
            startRecordingOnHeadsetMic()
            return
        }
        if (!moveTo(AssistantState.RECORDING) { it.copy(error = null, info = null, recordingStartedAtMs = System.currentTimeMillis()) }) return
        beginRecordingFile(fromSample)
    }

    /** On-demand mode: open the headset microphone first, then record from the start of that stream. */
    private fun startRecordingOnHeadsetMic() {
        if (!moveTo(AssistantState.RECORDING) {
                it.copy(error = null, info = tr("Turning on the headset microphone…", "Включаю микрофон гарнитуры…"), recordingStartedAtMs = null)
            }) return
        session?.launch {
            try {
                switchMic(true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Opening the headset microphone failed")
                recoverAudio(AssistantFailure(ErrorKind.AUDIO_INIT, tr("Could not turn on the headset microphone.", "Не удалось включить микрофон гарнитуры."), e))
                return@launch
            }
            if (stateMachine.current != AssistantState.RECORDING) { closeHeadsetMicIfDone(); return@launch }
            updateStatus { it.copy(info = tr("Speak now.", "Говорите."), recordingStartedAtMs = System.currentTimeMillis()) }
            beginRecordingFile(0)
        }
    }

    private fun beginRecordingFile(fromSample: Long) {
        try {
            recordingsDir.mkdirs()
            val file = File(recordingsDir, "rec_${FILE_TS.format(Date())}.wav")
            pipeline.startRecording(file, fromSample.coerceAtLeast(0))
        } catch (e: Exception) {
            Timber.e(e, "Could not start recording")
            recoverable(AssistantFailure(ErrorKind.AUDIO_INIT, tr("Could not start recording.", "Не удалось начать запись."), e))
        }
    }

    /** Stops the recording (cut before [endSample]) and launches recognition → DeepSeek → speech. */
    private fun finishRecording(endSample: Long?, info: String?) {
        if (stateMachine.current != AssistantState.RECORDING) return
        val result = try {
            pipeline.stopRecording(endSample)
        } catch (e: Exception) {
            Timber.e(e, "Finalizing recording failed")
            recoverable(AssistantFailure(ErrorKind.AUDIO_INIT, tr("Saving the recording failed.", "Не удалось сохранить запись."), e))
            return
        }
        updateStatus { it.copy(recordingStartedAtMs = null) }
        if (result == null) {
            emptyRecording(tr("Nothing was recorded.", "Ничего не записано."))
            return
        }
        when (val check = audioProcessor.check(result.file, settings.minRecordingMs, settings.minSpeechRmsDbfs, pipeline.sampleRate)) {
            is RecordingCheck.Valid -> {
                Timber.i("Recording ok: %d ms (speech %d ms, %.1f dBFS)", check.durationMs, check.speechMs, check.rmsDbfs)
                moveTo(AssistantState.TRANSCRIBING) { it.copy(info = info) }
                processingJob = session?.launch { runExchange(result.file) }
            }
            is RecordingCheck.TooShort -> { deleteRecording(result.file); emptyRecording(tr("The recording is too short.", "Запись слишком короткая.")) }
            is RecordingCheck.Silent -> { deleteRecording(result.file); emptyRecording(tr("No speech was detected in the recording.", "В записи не обнаружена речь.")) }
            is RecordingCheck.Unreadable -> { deleteRecording(result.file); emptyRecording(tr("The recording could not be read.", "Не удалось прочитать запись.")) }
        }
    }

    private fun emptyRecording(message: String) {
        moveTo(AssistantState.LISTENING) { it.copy(error = AssistantError(ErrorKind.EMPTY_RECORDING, message, tr("Beep once, speak, then beep again.", "Подайте сигнал, скажите вопрос и снова подайте сигнал."))) }
        feedback(Feedback.EMPTY)
    }

    private suspend fun runExchange(file: File) {
        val s = settings
        // 1. Speech-to-text
        val text = try {
            stt.transcribe(file, s)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AssistantFailure) {
            saveHistory(ConversationEntry(timestampMs = now(), userText = "", responseText = null, status = ExchangeStatus.STT_ERROR, errorMessage = e.userMessage))
            recoverable(e, Feedback.NOT_RECOGNIZED)
            return
        } finally {
            if (!s.keepRecordings) deleteRecording(file)
        }
        if (!moveTo(AssistantState.PROCESSING) { it.copy(lastRecognizedText = text) }) return

        // 2. DeepSeek
        val reply = try {
            chat.complete(buildContext(s, text), s)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AssistantFailure) {
            saveHistory(ConversationEntry(timestampMs = now(), userText = text, responseText = null, status = ExchangeStatus.API_ERROR, errorMessage = e.userMessage))
            recoverable(e, Feedback.REQUEST_FAILED)
            return
        }
        _lastResponse.value = reply
        sessionContext.addLast(text to reply)
        while (sessionContext.size > 50) sessionContext.removeFirst()
        saveHistory(ConversationEntry(timestampMs = now(), userText = text, responseText = reply, status = ExchangeStatus.SUCCESS))

        // 3. Speech
        if (!moveTo(AssistantState.SPEAKING) { it.copy(lastResponse = reply) }) return
        speakOrReport(reply)
        moveTo(AssistantState.LISTENING)
    }

    private suspend fun buildContext(s: AssistantSettings, userText: String): List<ChatMessage> {
        val previous: List<Pair<String, String>> = if (s.historyEnabled) {
            runCatching { conversations.lastSuccessful(s.contextExchanges) }.getOrDefault(emptyList())
                .reversed()
                .mapNotNull { e -> e.responseText?.let { e.userText to it } }
        } else {
            sessionContext.toList().takeLast(s.contextExchanges)
        }
        return buildList {
            if (s.systemPrompt.isNotBlank()) add(ChatMessage(ChatMessage.Role.SYSTEM, s.systemPrompt))
            previous.forEach { (u, a) ->
                add(ChatMessage(ChatMessage.Role.USER, u))
                add(ChatMessage(ChatMessage.Role.ASSISTANT, a))
            }
            add(ChatMessage(ChatMessage.Role.USER, userText))
        }
    }

    // ───────────────────────────── replay / manual controls ─────────────────────────────

    /** Double beep or the Replay button: speaks the stored answer again — no STT, no DeepSeek request. */
    fun replayLast() = appScope.launch { replayLastInternal() }

    private fun replayLastInternal() {
        val text = _lastResponse.value
        if (stateMachine.current == AssistantState.IDLE) {
            if (text != null) appScope.launch { speakOrReport(text) }
            else updateStatus { it.copy(error = AssistantError(ErrorKind.NOTHING_TO_REPLAY, tr("There is no answer to repeat yet.", "Пока нет ответа для повтора."))) }
            return
        }
        if (stateMachine.current != AssistantState.LISTENING) return
        if (text.isNullOrBlank()) {
            updateStatus { it.copy(error = AssistantError(ErrorKind.NOTHING_TO_REPLAY, tr("There is no answer to repeat yet.", "Пока нет ответа для повтора."))) }
            feedback(Feedback.NOTHING_TO_REPLAY)
            return
        }
        if (!moveTo(AssistantState.REPLAYING) { it.copy(error = null) }) return
        processingJob = session?.launch {
            speakOrReport(text)
            moveTo(AssistantState.LISTENING)
        }
    }

    /** Replays an answer chosen from history. */
    fun replayText(text: String) = appScope.launch {
        when (stateMachine.current) {
            AssistantState.IDLE -> speakOrReport(text)
            AssistantState.LISTENING -> if (moveTo(AssistantState.REPLAYING)) {
                processingJob = session?.launch { speakOrReport(text); moveTo(AssistantState.LISTENING) }
            }
            else -> Unit
        }
    }

    fun stopPlayback() {
        speaker.stop()
    }

    fun cancelRecording() = appScope.launch { cancelRecordingInternal() }

    private fun cancelRecordingInternal() {
        if (stateMachine.current != AssistantState.RECORDING) return
        pipeline.cancelRecording()
        moveTo(AssistantState.LISTENING) { it.copy(recordingStartedAtMs = null, info = tr("Recording cancelled.", "Запись отменена.")) }
    }

    // ───────────────────────────── events ─────────────────────────────

    private fun onPipelineEvent(event: PipelineEvent) {
        when (event) {
            PipelineEvent.RecordingLimitReached -> {
                Timber.i("Max recording duration reached")
                finishRecording(null, tr("Maximum recording length reached — sent automatically.", "Достигнута максимальная длина записи — отправлено автоматически."))
            }
            is PipelineEvent.SilenceDetected -> if (autoStopActive(settings)) {
                Timber.i("Silence detected — ending recording")
                finishRecording(event.endSample, tr("Silence detected — sent automatically.", "Тишина — запись отправлена автоматически."))
            }
            is PipelineEvent.CaptureFailed -> session?.launch {
                recoverAudio(AssistantFailure(ErrorKind.AUDIO_INIT, event.message))
            }
        }
    }

    /** The headset closed its voice link during a recording: usually a tap on its button → send. */
    private fun onHeadsetVoiceEnded() {
        if (!micOnDemand || !headsetMicOpen) return
        if (stateMachine.current == AssistantState.RECORDING && settings.recording.stopByButton) {
            Timber.i("Headset ended the voice link — ending recording")
            finishRecording(null, tr("Ended by the headset button.", "Завершено кнопкой гарнитуры."))
        } else if (stateMachine.current == AssistantState.RECORDING) {
            cancelRecordingInternal()
        }
    }

    private fun onInputLost() {
        if (micOnDemand && headsetMicOpen && stateMachine.current == AssistantState.RECORDING) {
            onHeadsetVoiceEnded() // the headset dropped its microphone link, not a disconnect
            return
        }
        session?.launch {
            recoverAudio(AssistantFailure(ErrorKind.AUDIO_DEVICE_LOST, tr("The headset was disconnected.", "Гарнитура отключена.")))
        }
    }

    private fun onHeadsetAdded() {
        val usingPhoneMic = routing.route.value.inputKind == AudioDeviceKind.BUILTIN_MIC
        if (stateMachine.current == AssistantState.LISTENING && usingPhoneMic && !phoneMicByChoice) {
            Timber.i("Headset connected — re-routing")
            session?.launch { restartAudio() }
        }
    }

    /** Stops any recording/playback, reports the problem and restarts capture on the best device left. */
    private suspend fun recoverAudio(failure: AssistantFailure) {
        processingJob?.cancel()
        processingJob = null
        speaker.stop()
        pipeline.cancelRecording()
        stateMachine.transition(AssistantState.ERROR)
        updateStatus { it.copy(state = AssistantState.ERROR, error = failure.toError(), recordingStartedAtMs = null) }
        val error = failure.toError()
        restartAudio()
        updateStatus { it.copy(error = error) }
    }

    private suspend fun restartAudio() {
        headsetMicOpen = false
        pipeline.stop()
        routing.disconnect()
        if (!stateMachine.transition(AssistantState.CONNECTING_AUDIO)) return
        updateStatus { it.copy(state = AssistantState.CONNECTING_AUDIO) }
        connectAndListen()
    }

    private fun onSettingsChanged(new: AssistantSettings) {
        val old = settings
        settings = new
        pipeline.updateConfig(new.beep, new.maxRecordingSec * 1000L, autoStopConfig(new))
        buttons.updateConfig(new.button)
        val routingChanged = old.preferredInputDeviceId != new.preferredInputDeviceId || old.micSource != new.micSource ||
            old.recording.bluetoothMic != new.recording.bluetoothMic
        if (routingChanged && stateMachine.current == AssistantState.LISTENING) {
            session?.launch { restartAudio() }
        }
    }

    // ───────────────────────────── helpers ─────────────────────────────

    /** Recoverable failure: show it, optionally say a short notice, return to LISTENING. */
    private fun recoverable(failure: AssistantFailure, notice: Feedback? = null) {
        Timber.w("Recoverable: %s", failure.userMessage)
        pipeline.cancelRecording()
        if (stateMachine.transition(AssistantState.ERROR)) {
            updateStatus { it.copy(state = AssistantState.ERROR, error = failure.toError(), recordingStartedAtMs = null) }
        }
        moveTo(AssistantState.LISTENING)
        notice?.let { feedback(it) }
    }

    private suspend fun speakOrReport(text: String) {
        try {
            speaker.speak(text, settings, routing.route.value.usesCommunicationRoute)
            buttons.reclaim()
        } catch (e: CancellationException) {
            throw e
        } catch (e: AssistantFailure) {
            Timber.e("TTS failed: %s", e.userMessage)
            updateStatus { it.copy(error = e.toError()) }
        } catch (e: Exception) {
            Timber.e(e, "TTS failed")
            updateStatus { it.copy(error = AssistantError(ErrorKind.TTS_FAILED, tr("Speech playback failed.", "Не удалось воспроизвести речь."))) }
        }
    }

    private enum class Feedback(val ru: String, val en: String) {
        EMPTY("Запись пустая. Повторите.", "Nothing recorded. Please try again."),
        NOT_RECOGNIZED("Не удалось распознать речь.", "Sorry, I could not recognize that."),
        REQUEST_FAILED("Не удалось получить ответ.", "I could not get an answer."),
        NOTHING_TO_REPLAY("Нет ответа для повтора.", "There is nothing to repeat yet."),
    }

    /** Short spoken notice through the headset. Beeps are ignored while it plays. */
    private fun feedback(f: Feedback) {
        if (!settings.spokenFeedback) return
        val text = if (settings.ttsLanguage.startsWith("ru", ignoreCase = true)) f.ru else f.en
        session?.launch {
            pipeline.setMode(BeepEventProcessor.Mode.IGNORE)
            try {
                speakOrReport(text)
            } finally {
                pipeline.setMode(modeFor(stateMachine.current))
            }
        }
    }

    private fun moveTo(to: AssistantState, extra: (AssistantStatus) -> AssistantStatus = { it }): Boolean {
        if (!stateMachine.transition(to)) return false
        pipeline.setMode(modeFor(to))
        updateStatus { extra(it.copy(state = to)) }
        if (to != AssistantState.RECORDING) closeHeadsetMicIfDone()
        return true
    }

    private fun modeFor(state: AssistantState) = when (state) {
        AssistantState.LISTENING -> BeepEventProcessor.Mode.LISTENING
        AssistantState.RECORDING -> BeepEventProcessor.Mode.RECORDING
        else -> BeepEventProcessor.Mode.IGNORE
    }

    private fun updateStatus(transform: (AssistantStatus) -> AssistantStatus) {
        _status.update { transform(it).copy(lastResponse = _lastResponse.value ?: it.lastResponse) }
    }

    private suspend fun saveHistory(entry: ConversationEntry) {
        if (!settings.historyEnabled) return
        runCatching { conversations.add(entry, settings.historyLimit) }
            .onFailure { Timber.e(it, "Saving history failed") }
    }

    private fun deleteRecording(file: File) {
        if (file.exists() && !file.delete()) Timber.w("Could not delete %s", file.name)
    }

    private fun now() = System.currentTimeMillis()

    companion object {
        private const val BUTTON_OWNER = "assistant"
        private val FILE_TS = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
    }
}
