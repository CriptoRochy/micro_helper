package com.helper.voiceassistant.data.audio

import com.helper.voiceassistant.processor.AutoStopConfig
import com.helper.voiceassistant.processor.SilenceDetector
import android.os.SystemClock

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import com.helper.voiceassistant.assistant.AudioPipeline
import com.helper.voiceassistant.assistant.PipelineConfig
import com.helper.voiceassistant.assistant.PipelineEvent
import com.helper.voiceassistant.detector.BeepDetector
import com.helper.voiceassistant.detector.BeepEvent
import com.helper.voiceassistant.detector.BeepEventProcessor
import com.helper.voiceassistant.domain.model.AssistantFailure
import com.helper.voiceassistant.domain.model.BeepConfig
import com.helper.voiceassistant.domain.model.ErrorKind
import com.helper.voiceassistant.domain.model.MicSource
import com.helper.voiceassistant.recorder.AudioBuffer
import com.helper.voiceassistant.recorder.RecordingResult
import com.helper.voiceassistant.recorder.VoiceRecorder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.log10
import kotlin.math.sqrt
import com.helper.voiceassistant.util.tr

/**
 * Owns the one and only AudioRecord. A dedicated high-priority thread reads 16 kHz mono PCM and fans
 * every chunk out to: the ring buffer → the voice recorder (if recording) → the beep detector →
 * the beep event processor. Using one AudioRecord avoids microphone conflicts between detection and
 * recording, and lets recordings be cut at exact sample positions around the control beeps.
 */
@Singleton
class AudioStreamProcessor @Inject constructor(
    @ApplicationContext private val context: Context,
) : AudioPipeline {

    override val sampleRate = SAMPLE_RATE

    private val _beepEvents = MutableSharedFlow<BeepEvent>(extraBufferCapacity = 32)
    override val beepEvents: SharedFlow<BeepEvent> = _beepEvents.asSharedFlow()
    private val _events = MutableSharedFlow<PipelineEvent>(extraBufferCapacity = 8)
    override val events: SharedFlow<PipelineEvent> = _events.asSharedFlow()
    private val _micLevel = MutableStateFlow(-120f)
    override val micLevelDb: StateFlow<Float> = _micLevel.asStateFlow()

    private val lock = Any()
    private val buffer = AudioBuffer(SAMPLE_RATE * BUFFER_SECONDS)
    private val recorder = VoiceRecorder(SAMPLE_RATE)
    private val eventProcessor = BeepEventProcessor(BeepConfig(), SAMPLE_RATE)
    private val detector = BeepDetector(BeepConfig(), SAMPLE_RATE) { pulse ->
        // Called on the capture thread while `lock` is held.
        if (!beepEnabled) return@BeepDetector
        eventProcessor.onPulse(pulse).forEach { event ->
            Timber.d("Beep %s (%d ms, %.1f dBFS, %.0f Hz)", event.type, pulse.durationMs, pulse.peakDbfs, pulse.frequencyHz)
            _beepEvents.tryEmit(event)
        }
    }

    @Volatile private var record: AudioRecord? = null
    @Volatile private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var beepEnabled = true
    @Volatile private var maxRecordingMs = 120_000L
    @Volatile private var autoStop: AutoStopConfig? = null
    private val silence = SilenceDetector(SAMPLE_RATE)
    private var silenceReported = false
    /** Last captured sample index and the uptime at which it was read, for button → sample mapping. */
    @Volatile private var lastIndex = 0L
    @Volatile private var lastIndexUptime = 0L
    private var limitReported = false

    override val isRunning: Boolean get() = running

    @SuppressLint("MissingPermission") // RECORD_AUDIO is checked by the UI before the service starts.
    override fun start(config: PipelineConfig) {
        if (running) return
        updateConfig(config.beep, config.maxRecordingMs, config.autoStop)
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) throw AssistantFailure(ErrorKind.AUDIO_INIT, tr("The microphone does not support 16 kHz mono capture.", "Микрофон не поддерживает запись 16 кГц моно."))
        val bufferBytes = maxOf(minBuf * 2, SAMPLE_RATE / 2 * 2) // ≥ 500 ms
        val source = when (config.micSource) {
            MicSource.VOICE_RECOGNITION -> MediaRecorder.AudioSource.VOICE_RECOGNITION
            MicSource.MIC -> MediaRecorder.AudioSource.MIC
            MicSource.VOICE_COMMUNICATION -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
        }
        val ar = try {
            AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferBytes)
        } catch (e: SecurityException) {
            throw AssistantFailure(ErrorKind.NO_PERMISSION, tr("Microphone permission is not granted.", "Нет разрешения на микрофон."), e)
        } catch (e: IllegalArgumentException) {
            throw AssistantFailure(ErrorKind.AUDIO_INIT, tr("Could not open the microphone.", "Не удалось открыть микрофон."), e)
        }
        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            ar.release()
            throw AssistantFailure(ErrorKind.AUDIO_INIT, tr("The microphone is busy or unavailable.", "Микрофон занят или недоступен."))
        }
        config.preferredInputId?.let { id ->
            val device = findInput(id)
            if (device != null && !ar.setPreferredDevice(device)) Timber.w("setPreferredDevice(%s) refused", device.productName)
        }
        try {
            ar.startRecording()
        } catch (e: IllegalStateException) {
            ar.release()
            throw AssistantFailure(ErrorKind.AUDIO_INIT, tr("Could not start microphone capture.", "Не удалось начать запись с микрофона."), e)
        }
        if (ar.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            ar.release()
            throw AssistantFailure(ErrorKind.AUDIO_INIT, tr("Microphone capture was blocked by the system.", "Система заблокировала запись с микрофона."))
        }
        Timber.i("AudioRecord started: source=%s routed=%s", config.micSource, ar.routedDevice?.productName)
        synchronized(lock) {
            buffer.clear()
            detector.reset()
            eventProcessor.reset()
        }
        record = ar
        running = true
        thread = Thread({ captureLoop(ar) }, "audio-capture").also { it.start() }
    }

    private fun captureLoop(ar: AudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val chunk = ShortArray(CHUNK)
        var index = 0L
        var levelAcc = 0.0
        var levelCount = 0
        while (running) {
            val n = ar.read(chunk, 0, CHUNK)
            if (n < 0) {
                if (running) {
                    Timber.e("AudioRecord.read error %d", n)
                    _events.tryEmit(PipelineEvent.CaptureFailed(tr("Microphone read error ($n)", "Ошибка чтения микрофона ($n)")))
                }
                break
            }
            if (n == 0) continue
            synchronized(lock) {
                buffer.write(chunk, n)
                recorder.append(chunk, n, index)
                detector.process(chunk, n, index)
                index += n
                eventProcessor.onTick(index)?.let { _beepEvents.tryEmit(it) }
                if (recorder.isRecording && !limitReported && recorder.currentDurationMs >= maxRecordingMs) {
                    limitReported = true
                    _events.tryEmit(PipelineEvent.RecordingLimitReached)
                }
                val auto = autoStop
                if (auto != null && recorder.isRecording && !silenceReported) {
                    silence.process(chunk, n, index - n, auto)?.let {
                        silenceReported = true
                        _events.tryEmit(PipelineEvent.SilenceDetected(it))
                    }
                }
                lastIndex = index
                lastIndexUptime = SystemClock.uptimeMillis()
            }
            for (i in 0 until n) { val s = chunk[i] / 32768.0; levelAcc += s * s }
            levelCount += n
            if (levelCount >= SAMPLE_RATE / 8) {
                val rms = sqrt(levelAcc / levelCount)
                _micLevel.value = if (rms > 1e-9) (20 * log10(rms)).toFloat() else -120f
                levelAcc = 0.0; levelCount = 0
            }
        }
    }

    override fun stop() {
        running = false
        val ar = record
        record = null
        runCatching { ar?.stop() }
        thread?.join(1000)
        thread = null
        runCatching { ar?.release() }
        synchronized(lock) { recorder.cancel() }
        _micLevel.value = -120f
        Timber.i("AudioRecord released")
    }

    override fun setMode(mode: BeepEventProcessor.Mode) {
        synchronized(lock) { eventProcessor.mode = mode }
    }

    override fun sampleAtUptime(uptimeMs: Long): Long {
        val behindMs = (lastIndexUptime - uptimeMs).coerceAtLeast(0)
        return (lastIndex - behindMs * SAMPLE_RATE / 1000).coerceAtLeast(0)
    }

    override fun currentSample(): Long = lastIndex

    override fun updateConfig(beep: BeepConfig, maxRecordingMs: Long, autoStop: AutoStopConfig?) {
        this.autoStop = autoStop
        synchronized(lock) {
            detector.config = beep
            eventProcessor.config = beep
        }
        beepEnabled = beep.enabled
        this.maxRecordingMs = maxRecordingMs
    }

    override fun startRecording(file: File, fromSample: Long) {
        synchronized(lock) {
            limitReported = false
            silenceReported = false
            silence.reset()
            recorder.start(file, fromSample, buffer)
        }
    }

    override fun stopRecording(endSample: Long?): RecordingResult? =
        synchronized(lock) { if (endSample == null) recorder.stop() else recorder.stop(endSample) }

    override fun cancelRecording() {
        synchronized(lock) { recorder.cancel() }
    }

    private fun findInput(id: Int): AudioDeviceInfo? {
        val am = context.getSystemService(AudioManager::class.java)
        return am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == id }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHUNK = 512          // 32 ms
        const val BUFFER_SECONDS = 10  // ring buffer covers the confirmation delay with lots of margin
    }
}
