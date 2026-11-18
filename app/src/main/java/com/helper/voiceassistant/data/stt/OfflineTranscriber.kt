package com.helper.voiceassistant.data.stt

import com.helper.voiceassistant.assistant.SttEngine
import com.helper.voiceassistant.domain.model.AssistantFailure
import com.helper.voiceassistant.domain.model.AssistantSettings
import com.helper.voiceassistant.domain.model.ErrorKind
import com.helper.voiceassistant.recorder.WavFileWriter
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import com.helper.voiceassistant.util.tr

/**
 * Fully offline speech recognition: GigaAM v3 (RNNT, INT8) running in sherpa-onnx on the CPU.
 * Takes the 16 kHz / 16-bit / mono WAV written by the capture pipeline and returns the text that is
 * then sent to DeepSeek. No network access. The recognizer is created lazily on first use (~1–3 s)
 * and kept in memory for later recordings.
 */
@Singleton
class OfflineTranscriber @Inject constructor(
    private val models: SttModelManager,
) : SttEngine {

    private val lock = Any()
    private var recognizer: OfflineRecognizer? = null

    init {
        models.onModelRemoved = { release() }
    }

    override suspend fun transcribe(file: File, settings: AssistantSettings): String = withContext(Dispatchers.Default) {
        if (!models.isInstalled()) {
            throw AssistantFailure(ErrorKind.STT_MODEL_MISSING, tr("The offline speech model is not downloaded.", "Офлайн-модель распознавания не скачана."))
        }
        if (!file.exists() || file.length() <= WavFileWriter.HEADER_SIZE) {
            throw AssistantFailure(ErrorKind.EMPTY_RECORDING, tr("The recording is empty.", "Запись пустая."))
        }
        val samples = readPcm16(file)
        val started = System.currentTimeMillis()
        val text = try {
            transcribe(samples)
        } catch (e: AssistantFailure) {
            throw e
        } catch (e: Throwable) {
            Timber.e(e, "Offline recognition failed")
            throw AssistantFailure(ErrorKind.STT_FAILED, tr("Offline speech recognition failed.", "Ошибка офлайн-распознавания речи."), e)
        }
        Timber.i("STT (offline): %d chars, %d ms audio in %d ms",
            text.length, samples.size * 1000L / SAMPLE_RATE, System.currentTimeMillis() - started)
        if (text.isBlank() || NON_SPEECH.matches(text)) {
            throw AssistantFailure(ErrorKind.STT_FAILED, tr("Speech was not recognized. Please speak louder or closer to the microphone.", "Речь не распознана. Говорите громче или ближе к микрофону."))
        }
        text
    }

    /** [samples] are mono floats in -1..1 at 16 kHz. */
    fun transcribe(samples: FloatArray): String = synchronized(lock) {
        val r = recognizer ?: createRecognizer().also { recognizer = it }
        val stream = r.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            r.decode(stream)
            r.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    /** Frees the native model (~400 MB RAM). It is reloaded on the next transcription. */
    fun release() = synchronized(lock) {
        recognizer?.release()
        recognizer = null
    }

    private fun createRecognizer(): OfflineRecognizer {
        val started = System.currentTimeMillis()
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = models.file(GigaAmV3.ENCODER).absolutePath,
                    decoder = models.file(GigaAmV3.DECODER).absolutePath,
                    joiner = models.file(GigaAmV3.JOINER).absolutePath,
                ),
                tokens = models.file(GigaAmV3.TOKENS).absolutePath,
                modelType = "nemo_transducer",
                numThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
                provider = "cpu",
            ),
        )
        // assetManager = null → model paths are regular files in filesDir.
        return OfflineRecognizer(null, config).also {
            Timber.i("GigaAM v3 loaded in %d ms", System.currentTimeMillis() - started)
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private val NON_SPEECH = Regex("""^[\s.,!?…\-—]*$""")

        /** Reads the PCM payload of a 16-bit mono WAV produced by [WavFileWriter] as normalized floats. */
        fun readPcm16(file: File): FloatArray {
            val bytes = file.readBytes()
            val dataOffset = findDataChunk(bytes) ?: WavFileWriter.HEADER_SIZE
            val shorts = ByteBuffer.wrap(bytes, dataOffset, bytes.size - dataOffset).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            return FloatArray(shorts.remaining()) { shorts.get() / 32768f }
        }

        private fun findDataChunk(b: ByteArray): Int? {
            var i = 12
            while (i + 8 <= b.size) {
                val size = ByteBuffer.wrap(b, i + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                if (b[i] == 'd'.code.toByte() && b[i + 1] == 'a'.code.toByte() && b[i + 2] == 't'.code.toByte() && b[i + 3] == 'a'.code.toByte()) return i + 8
                if (size < 0) return null
                i += 8 + size + (size and 1)
            }
            return null
        }
    }
}
