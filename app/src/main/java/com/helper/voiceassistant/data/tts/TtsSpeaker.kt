package com.helper.voiceassistant.data.tts

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.helper.voiceassistant.assistant.Speaker
import com.helper.voiceassistant.domain.model.AssistantFailure
import com.helper.voiceassistant.domain.model.AssistantSettings
import com.helper.voiceassistant.domain.model.ErrorKind
import com.helper.voiceassistant.domain.model.TtsAudioUsage
import com.helper.voiceassistant.manager.AudioSessionManager
import com.helper.voiceassistant.util.SpeechText
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import com.helper.voiceassistant.util.tr

data class VoiceOption(val name: String, val localeTag: String, val networkRequired: Boolean, val quality: Int)

/**
 * Android TextToSpeech wrapper. speak() suspends until the whole answer has been spoken (or stopped),
 * which is what lets the controller return to LISTENING exactly when playback ends.
 */
@Singleton
class TtsSpeaker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val session: AudioSessionManager,
) : Speaker {

    private var tts: TextToSpeech? = null
    private val initMutex = Mutex()
    private val speakMutex = Mutex()
    private val utteranceCounter = AtomicInteger()

    override val interruptions: SharedFlow<Unit> = session.focusLost

    private val _warning = MutableStateFlow<String?>(null)
    /** Non-fatal notice, e.g. "Russian voice not installed, using English". */
    val warning: StateFlow<String?> = _warning.asStateFlow()

    private suspend fun engine(): TextToSpeech = initMutex.withLock {
        tts?.let { return it }
        val ready = CompletableDeferred<Int>()
        val created = withContext(Dispatchers.Main) {
            TextToSpeech(context) { status -> ready.complete(status) }
        }
        val status = try {
            withTimeout(10_000) { ready.await() }
        } catch (e: Exception) {
            created.shutdown()
            throw AssistantFailure(ErrorKind.TTS_FAILED, tr("The text-to-speech engine did not start.", "Синтезатор речи не запустился."), e)
        }
        if (status != TextToSpeech.SUCCESS) {
            created.shutdown()
            throw AssistantFailure(ErrorKind.TTS_FAILED, tr("No text-to-speech engine is available on this device.", "На устройстве нет синтезатора речи."))
        }
        tts = created
        created
    }

    override suspend fun speak(text: String, settings: AssistantSettings, communicationRoute: Boolean) = speakMutex.withLock {
        val engine = engine()
        val spoken = SpeechText.clean(text)
        if (spoken.isBlank()) return@withLock

        applyLanguageAndVoice(engine, settings)
        engine.setSpeechRate(settings.ttsRate.coerceIn(0.3f, 3f))
        val attributes = attributesFor(settings.ttsAudioUsage, communicationRoute)
        engine.setAudioAttributes(attributes)
        session.requestPlaybackFocus(attributes)

        val chunks = SpeechText.chunk(spoken, TextToSpeech.getMaxSpeechInputLength() - 100)
        val batch = utteranceCounter.incrementAndGet()
        val lastId = "u$batch-${chunks.lastIndex}"
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, settings.ttsVolume.coerceIn(0f, 1f)) }
        try {
            suspendCancellableCoroutine { cont ->
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String) {
                        Timber.d("TTS start %s", utteranceId)
                    }

                    override fun onDone(utteranceId: String) {
                        if (utteranceId == lastId && cont.isActive) cont.resume(Unit)
                    }

                    override fun onStop(utteranceId: String, interrupted: Boolean) {
                        if (utteranceId.startsWith("u$batch-") && cont.isActive) cont.resume(Unit)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String) = onError(utteranceId, TextToSpeech.ERROR)

                    override fun onError(utteranceId: String, errorCode: Int) {
                        Timber.e("TTS error %d on %s", errorCode, utteranceId)
                        if (cont.isActive) cont.resumeWithException(
                            AssistantFailure(ErrorKind.TTS_FAILED, tr("Speech playback failed (code $errorCode).", "Не удалось воспроизвести речь (код $errorCode).")),
                        )
                    }
                })
                cont.invokeOnCancellation { engine.stop() }
                chunks.forEachIndexed { i, chunk ->
                    val mode = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                    if (engine.speak(chunk, mode, params, "u$batch-$i") == TextToSpeech.ERROR && cont.isActive) {
                        cont.resumeWithException(AssistantFailure(ErrorKind.TTS_FAILED, tr("Speech playback could not start.", "Не удалось начать воспроизведение речи.")))
                        return@suspendCancellableCoroutine
                    }
                }
            }
        } finally {
            session.abandonPlaybackFocus()
        }
    }

    private fun applyLanguageAndVoice(engine: TextToSpeech, settings: AssistantSettings) {
        val wanted = Locale.forLanguageTag(settings.ttsLanguage.ifBlank { Locale.getDefault().toLanguageTag() })
        val candidates = listOf(wanted, Locale.getDefault(), Locale.US).distinct()
        val chosen = candidates.firstOrNull { loc ->
            val r = engine.setLanguage(loc)
            r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
        }
        _warning.value = when {
            chosen == null -> tr("No usable TTS language installed.", "Не установлен подходящий язык синтеза речи.")
            chosen != wanted -> tr("Voice for ${wanted.displayName} is not installed — using ${chosen.displayName}.", "Голос для ${wanted.displayName} не установлен — используется ${chosen.displayName}.")
            else -> null
        }
        _warning.value?.let { Timber.w(it) }
        if (settings.ttsVoiceName.isNotBlank()) {
            val voice = runCatching { engine.voices }.getOrNull()?.firstOrNull { it.name == settings.ttsVoiceName }
            if (voice != null) engine.voice = voice else Timber.w("Selected TTS voice not found: %s", settings.ttsVoiceName)
        }
    }

    private fun attributesFor(usage: TtsAudioUsage, communicationRoute: Boolean): AudioAttributes {
        val u = when (usage) {
            TtsAudioUsage.AUTO -> if (communicationRoute) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA
            TtsAudioUsage.MEDIA -> AudioAttributes.USAGE_MEDIA
            TtsAudioUsage.VOICE_COMMUNICATION -> AudioAttributes.USAGE_VOICE_COMMUNICATION
            TtsAudioUsage.ASSISTANCE_ACCESSIBILITY -> AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY
        }
        return AudioAttributes.Builder().setUsage(u).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    }

    override fun stop() {
        tts?.stop()
    }

    /** Installed voices, optionally filtered by language (e.g. "ru"). */
    suspend fun voices(languageTag: String?): List<VoiceOption> {
        val engine = runCatching { engine() }.getOrNull() ?: return emptyList()
        val lang = languageTag?.let { Locale.forLanguageTag(it).language }
        return runCatching { engine.voices }.getOrNull().orEmpty()
            .filter { lang.isNullOrBlank() || it.locale.language == lang }
            .map { VoiceOption(it.name, it.locale.toLanguageTag(), it.isNetworkConnectionRequired, it.quality) }
            .sortedBy { it.name }
    }

    override fun shutdown() {
        tts?.shutdown()
        tts = null
    }
}
