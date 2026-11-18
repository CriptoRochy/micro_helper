package com.helper.voiceassistant.assistant

import com.helper.voiceassistant.detector.BeepEvent
import com.helper.voiceassistant.detector.BeepEventProcessor
import com.helper.voiceassistant.domain.model.AssistantSettings
import com.helper.voiceassistant.domain.model.AudioRouteInfo
import com.helper.voiceassistant.domain.model.BeepConfig
import com.helper.voiceassistant.domain.model.ChatMessage
import com.helper.voiceassistant.domain.model.MicSource
import com.helper.voiceassistant.recorder.RecordingResult
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/*
 * Interfaces the AssistantController depends on. Android implementations live in data/, manager/ and
 * service/; unit tests substitute fakes, which keeps the interaction logic testable on the JVM.
 */

sealed interface PipelineEvent {
    data object RecordingLimitReached : PipelineEvent
    data class CaptureFailed(val message: String) : PipelineEvent
}

data class PipelineConfig(
    val micSource: MicSource,
    val preferredInputId: Int?,
    val beep: BeepConfig,
    val maxRecordingMs: Long,
)

/** Single shared microphone stream feeding the beep detector, the ring buffer and the recorder. */
interface AudioPipeline {
    val beepEvents: SharedFlow<BeepEvent>
    val events: SharedFlow<PipelineEvent>
    /** Input level in dBFS, updated a few times per second. */
    val micLevelDb: StateFlow<Float>
    val isRunning: Boolean
    val sampleRate: Int

    /** Opens AudioRecord and starts capturing. Throws AssistantFailure on failure. */
    fun start(config: PipelineConfig)
    fun stop()
    fun setMode(mode: BeepEventProcessor.Mode)
    fun updateConfig(beep: BeepConfig, maxRecordingMs: Long)

    fun startRecording(file: File, fromSample: Long)
    fun stopRecording(endSample: Long? = null): RecordingResult?
    fun cancelRecording()
}

data class RouteResult(val info: AudioRouteInfo, val inputDeviceId: Int?)

/** Headset detection / routing (communication device, SCO, mode). */
interface AudioRouting {
    val route: StateFlow<AudioRouteInfo>
    /** Emits when the input device in use was disconnected. */
    val inputLost: SharedFlow<Unit>
    /** Emits when a headset appears while running (allows re-routing to it). */
    val headsetAdded: SharedFlow<Unit>
    fun startMonitoring()
    fun stopMonitoring()
    suspend fun connect(preferredInputId: Int?): RouteResult
    fun disconnect()
}

interface SttEngine {
    /** Returns non-empty recognized text or throws AssistantFailure. */
    suspend fun transcribe(file: File, settings: AssistantSettings): String
}

interface ChatClient {
    /** Returns the assistant reply text or throws AssistantFailure. */
    suspend fun complete(messages: List<ChatMessage>, settings: AssistantSettings): String
}

interface Speaker {
    /** Emits when playback must stop because another app took audio focus. */
    val interruptions: SharedFlow<Unit>

    /** Suspends until playback finished. Throws AssistantFailure on TTS errors. */
    suspend fun speak(text: String, settings: AssistantSettings, communicationRoute: Boolean)
    fun stop()
    fun shutdown()
}
