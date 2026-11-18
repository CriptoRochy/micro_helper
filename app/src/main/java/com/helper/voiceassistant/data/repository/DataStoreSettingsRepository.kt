package com.helper.voiceassistant.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.helper.voiceassistant.domain.model.AssistantSettings
import com.helper.voiceassistant.domain.model.BeepConfig
import com.helper.voiceassistant.domain.model.MicSource
import com.helper.voiceassistant.domain.model.TtsAudioUsage
import com.helper.voiceassistant.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Non-secret settings in Preferences DataStore. API keys live in KeystoreSecretStore instead. */
@Singleton
class DataStoreSettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {

    override val settings: Flow<AssistantSettings> = dataStore.data.map { it.toSettings() }.distinctUntilChanged()

    override suspend fun current(): AssistantSettings = settings.first()

    override suspend fun update(transform: (AssistantSettings) -> AssistantSettings) {
        dataStore.edit { prefs ->
            val updated = transform(prefs.toSettings())
            prefs.write(updated)
        }
    }

    private object K {
        val beepEnabled = booleanPreferencesKey("beep_enabled")
        val beepFreq = floatPreferencesKey("beep_freq")
        val beepTol = floatPreferencesKey("beep_tol")
        val beepThreshold = floatPreferencesKey("beep_threshold")
        val beepTonality = floatPreferencesKey("beep_tonality")
        val beepMin = intPreferencesKey("beep_min_ms")
        val beepMax = intPreferencesKey("beep_max_ms")
        val beepDouble = intPreferencesKey("beep_double_ms")
        val beepDebounce = intPreferencesKey("beep_debounce_ms")
        val beepHysteresis = floatPreferencesKey("beep_hysteresis")
        val beepGuard = intPreferencesKey("beep_guard_ms")
        val maxRec = intPreferencesKey("max_recording_sec")
        val minRec = intPreferencesKey("min_recording_ms")
        val minSpeechDb = floatPreferencesKey("min_speech_db")
        val micSource = stringPreferencesKey("mic_source")
        val inputDevice = intPreferencesKey("input_device")
        val sttConsent = booleanPreferencesKey("stt_consent")
        val dsUrl = stringPreferencesKey("ds_url")
        val dsModel = stringPreferencesKey("ds_model")
        val dsThinking = booleanPreferencesKey("ds_thinking")
        val systemPrompt = stringPreferencesKey("system_prompt")
        val contextExchanges = intPreferencesKey("context_exchanges")
        val timeout = intPreferencesKey("timeout_sec")
        val retries = intPreferencesKey("max_retries")
        val ttsRate = floatPreferencesKey("tts_rate")
        val ttsVolume = floatPreferencesKey("tts_volume")
        val ttsVoice = stringPreferencesKey("tts_voice")
        val ttsLang = stringPreferencesKey("tts_lang")
        val ttsUsage = stringPreferencesKey("tts_usage")
        val spokenFeedback = booleanPreferencesKey("spoken_feedback")
        val historyEnabled = booleanPreferencesKey("history_enabled")
        val historyLimit = intPreferencesKey("history_limit")
        val keepRecordings = booleanPreferencesKey("keep_recordings")
        val uiLanguage = stringPreferencesKey("ui_language")
    }

    private fun Preferences.toSettings(): AssistantSettings {
        val d = AssistantSettings()
        val b = d.beep
        return AssistantSettings(
            beep = BeepConfig(
                enabled = this[K.beepEnabled] ?: b.enabled,
                frequencyHz = this[K.beepFreq] ?: b.frequencyHz,
                toleranceHz = this[K.beepTol] ?: b.toleranceHz,
                thresholdDbfs = this[K.beepThreshold] ?: b.thresholdDbfs,
                tonalityRatio = this[K.beepTonality] ?: b.tonalityRatio,
                minBeepMs = this[K.beepMin] ?: b.minBeepMs,
                maxBeepMs = this[K.beepMax] ?: b.maxBeepMs,
                doubleBeepIntervalMs = this[K.beepDouble] ?: b.doubleBeepIntervalMs,
                debounceMs = this[K.beepDebounce] ?: b.debounceMs,
                hysteresisDb = this[K.beepHysteresis] ?: b.hysteresisDb,
                guardMs = this[K.beepGuard] ?: b.guardMs,
            ),
            maxRecordingSec = this[K.maxRec] ?: d.maxRecordingSec,
            minRecordingMs = this[K.minRec] ?: d.minRecordingMs,
            minSpeechRmsDbfs = this[K.minSpeechDb] ?: d.minSpeechRmsDbfs,
            micSource = enumOr(this[K.micSource], d.micSource),
            preferredInputDeviceId = this[K.inputDevice] ?: d.preferredInputDeviceId,
            sttConsentGiven = this[K.sttConsent] ?: d.sttConsentGiven,
            deepSeekBaseUrl = this[K.dsUrl] ?: d.deepSeekBaseUrl,
            deepSeekModel = this[K.dsModel] ?: d.deepSeekModel,
            deepSeekThinking = this[K.dsThinking] ?: d.deepSeekThinking,
            systemPrompt = this[K.systemPrompt] ?: d.systemPrompt,
            contextExchanges = this[K.contextExchanges] ?: d.contextExchanges,
            requestTimeoutSec = this[K.timeout] ?: d.requestTimeoutSec,
            maxRetries = this[K.retries] ?: d.maxRetries,
            ttsRate = this[K.ttsRate] ?: d.ttsRate,
            ttsVolume = this[K.ttsVolume] ?: d.ttsVolume,
            ttsVoiceName = this[K.ttsVoice] ?: d.ttsVoiceName,
            ttsLanguage = this[K.ttsLang] ?: d.ttsLanguage,
            ttsAudioUsage = enumOr(this[K.ttsUsage], d.ttsAudioUsage),
            spokenFeedback = this[K.spokenFeedback] ?: d.spokenFeedback,
            historyEnabled = this[K.historyEnabled] ?: d.historyEnabled,
            historyLimit = this[K.historyLimit] ?: d.historyLimit,
            keepRecordings = this[K.keepRecordings] ?: d.keepRecordings,
            uiLanguage = enumOr(this[K.uiLanguage], d.uiLanguage),
        )
    }

    private fun MutablePreferences.write(s: AssistantSettings) {
        val b = s.beep
        this[K.beepEnabled] = b.enabled
        this[K.beepFreq] = b.frequencyHz
        this[K.beepTol] = b.toleranceHz
        this[K.beepThreshold] = b.thresholdDbfs
        this[K.beepTonality] = b.tonalityRatio
        this[K.beepMin] = b.minBeepMs
        this[K.beepMax] = b.maxBeepMs
        this[K.beepDouble] = b.doubleBeepIntervalMs
        this[K.beepDebounce] = b.debounceMs
        this[K.beepHysteresis] = b.hysteresisDb
        this[K.beepGuard] = b.guardMs
        this[K.maxRec] = s.maxRecordingSec
        this[K.minRec] = s.minRecordingMs
        this[K.minSpeechDb] = s.minSpeechRmsDbfs
        this[K.micSource] = s.micSource.name
        this[K.inputDevice] = s.preferredInputDeviceId
        this[K.sttConsent] = s.sttConsentGiven
        this[K.dsUrl] = s.deepSeekBaseUrl
        this[K.dsModel] = s.deepSeekModel
        this[K.dsThinking] = s.deepSeekThinking
        this[K.systemPrompt] = s.systemPrompt
        this[K.contextExchanges] = s.contextExchanges
        this[K.timeout] = s.requestTimeoutSec
        this[K.retries] = s.maxRetries
        this[K.ttsRate] = s.ttsRate
        this[K.ttsVolume] = s.ttsVolume
        this[K.ttsVoice] = s.ttsVoiceName
        this[K.ttsLang] = s.ttsLanguage
        this[K.ttsUsage] = s.ttsAudioUsage.name
        this[K.spokenFeedback] = s.spokenFeedback
        this[K.historyEnabled] = s.historyEnabled
        this[K.historyLimit] = s.historyLimit
        this[K.keepRecordings] = s.keepRecordings
        this[K.uiLanguage] = s.uiLanguage.name
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default
}
