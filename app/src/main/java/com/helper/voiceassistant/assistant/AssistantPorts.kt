package com.helper.voiceassistant.assistant

import com.helper.voiceassistant.detector.BeepEvent
import com.helper.voiceassistant.detector.ButtonGesture
import com.helper.voiceassistant.detector.BeepEventProcessor
import com.helper.voiceassistant.domain.model.AssistantSettings
import com.helper.voiceassistant.domain.model.AudioRouteInfo
import com.helper.voiceassistant.domain.model.BeepConfig
import com.helper.voiceassistant.domain.model.ChatMessage
import com.helper.voiceassistant.domain.model.HeadsetButtonConfig
import com.helper.voiceassistant.domain.model.MicSource
import com.helper.voiceassistant.processor.AutoStopConfig
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
    /** Silence after speech: the recording should end at [endSample]. */
    data class SilenceDetected(val endSample: Long) : PipelineEvent
}

data class PipelineConfig(
    val micSource: MicSource,
    val preferredInputId: Int?,
    val beep: BeepConfig,
    val maxRecordingMs: Long,
    val autoStop: AutoStopConfig? = null,
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
    fun updateConfig(beep: BeepConfig, maxRecordingMs: Long, autoStop: AutoStopConfig?)

    /** Absolute stream sample index that was being captured at [uptimeMs] (SystemClock.uptimeMillis). */
    fun sampleAtUptime(uptimeMs: Long): Long

    /** Index of the latest captured sample (where a recording started "now" begins). */
    fun currentSample(): Long

    fun startRecording(file: File, fromSample: Long)
    fun stopRecording(endSample: Long? = null): RecordingResult?
    fun cancelRecording()
}

/**
 * [headsetMicAvailable]: a headset microphone that needs the communication route (Bluetooth HFP/SCO,
 * LE Audio) is connected, even if this route did not use it (connect with useHeadsetMic = false).
 */
data class RouteResult(val info: AudioRouteInfo, val inputDeviceId: Int?, val headsetMicAvailable: Boolean = false)

/** Headset detection / routing (communication device, SCO, mode). */
interface AudioRouting {
    val route: StateFlow<AudioRouteInfo>
    /** Emits when the input device in use was disconnected. */
    val inputLost: SharedFlow<Unit>
    /** Emits when a headset appears while running (allows re-routing to it). */
    val headsetAdded: SharedFlow<Unit>
    /**
     * Emits when the headset itself closed a voice-recognition link opened with
     * connect(voiceRecognition = true) — typically the user tapped the headset button.
     */
    val headsetVoiceEnded: SharedFlow<Unit>
    fun startMonitoring()
    fun stopMonitoring()
    /**
     * Brings up the input route. With [useHeadsetMic] = false a Bluetooth headset microphone is not
     * opened (no call mode): the phone microphone is used and the headset stays in music mode.
     */
    suspend fun connect(preferredInputId: Int?, useHeadsetMic: Boolean = true, voiceRecognition: Boolean = false): RouteResult
    fun disconnect()
}

/** Headset / media buttons (play-pause, call/hook button). */
interface HeadsetButtons {
    val gestures: SharedFlow<ButtonGesture>
    /** Starts receiving button presses while at least one owner holds it. */
    fun acquire(owner: String)
    fun release(owner: String)
    fun updateConfig(config: HeadsetButtonConfig)
    /** Re-claims media-button focus, e.g. after another app or TTS played audio. */
    fun reclaim()
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

/** Read access to the audio library for voice search. */
interface AudioLibrary {
    suspend fun items(): List<com.helper.voiceassistant.domain.model.LibraryItem>
    /** The private copy of the entry's audio file. */
    fun fileFor(item: com.helper.voiceassistant.domain.model.LibraryItem): File
}

sealed interface ClipEvent {
    data object Completed : ClipEvent
    data class Failed(val message: String) : ClipEvent
    /** Paused by the system: headset unplugged ("becoming noisy") or audio focus taken by another app. */
    data object PausedBySystem : ClipEvent
}

/** What the clip player is doing; [title] stays set while a clip is loaded (also when paused). */
data class ClipState(
    val title: String? = null,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
)

/** Short sounds around a recording: [START] before it (speak after it), [END] once it is over. */
enum class RecordingCue { START, END }

interface CuePlayer {
    /** Plays [cue] and returns once it has been played (or could not be). Cancelling stops it. */
    suspend fun play(cue: RecordingCue, communicationRoute: Boolean)
    fun stop()
}

/** Plays library recordings through the current output device. */
interface ClipPlayer {
    val events: SharedFlow<ClipEvent>
    val state: StateFlow<ClipState>
    /** Starts [file] from the beginning, replacing any loaded clip. Throws if it cannot be played. */
    fun play(file: File, title: String)
    fun pause()
    fun resume()
    fun seekBy(deltaMs: Long)
    fun stop()
}
