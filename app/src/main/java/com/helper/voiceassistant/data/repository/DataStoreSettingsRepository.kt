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
import com.helper.voiceassistant.domain.model.HeadsetButtonConfig
import com.helper.voiceassistant.domain.model.RecordingControl
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
        val btnEnabled = booleanPreferencesKey("btn_enabled")
        val btnKeys = stringPreferencesKey("btn_keys")
        val btnSingle = stringPreferencesKey("btn_single")
        val btnDouble = stringPreferencesKey("btn_double")
        val btnLong = stringPreferencesKey("btn_long")
        val btnDoubleMs = intPreferencesKey("btn_double_ms")
        val btnLongMs = intPreferencesKey("btn_long_ms")
        val recStartBeep = booleanPreferencesKey("rec_start_beep")
        val recStartButton = booleanPreferencesKey("rec_start_button")
        val recStopBeep = booleanPreferencesKey("rec_stop_beep")
        val recStopButton = booleanPreferencesKey("rec_stop_button")
        val recStopSilence = booleanPreferencesKey("rec_stop_silence")
        val recCueSounds = booleanPreferencesKey("rec_cue_sounds")
        val recSilenceMs = intPreferencesKey("rec_silence_ms")
        val recSilenceDb = floatPreferencesKey("rec_silence_db")
        val recMinSpeechMs = intPreferencesKey("rec_min_speech_ms")
        val recBtnStartMs = intPreferencesKey("rec_btn_start_ms")
        val recBtnEndMs = intPreferencesKey("rec_btn_end_ms")
        val recBluetoothMic = stringPreferencesKey("rec_bluetooth_mic")
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
            button = buttonConfig(),
            recording = recordingControl(),
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
        val bt = s.button
        this[K.btnEnabled] = bt.enabled
        this[K.btnKeys] = bt.keyCodes.sorted().joinToString(",")
        this[K.btnSingle] = bt.singlePress.name
        this[K.btnDouble] = bt.doublePress.name
        this[K.btnLong] = bt.longPress.name
        this[K.btnDoubleMs] = bt.doublePressMs
        this[K.btnLongMs] = bt.longPressMs
        val r = s.recording
        this[K.recStartBeep] = r.startByBeep
        this[K.recStartButton] = r.startByButton
        this[K.recStopBeep] = r.stopByBeep
        this[K.recStopButton] = r.stopByButton
        this[K.recStopSilence] = r.stopOnSilence
        this[K.recCueSounds] = r.cueSounds
        this[K.recBluetoothMic] = r.bluetoothMic.name
        this[K.recSilenceMs] = r.silenceMs
        this[K.recSilenceDb] = r.silenceThresholdDbfs
        this[K.recMinSpeechMs] = r.minSpeechBeforeAutoStopMs
        this[K.recBtnStartMs] = r.buttonStartOffsetMs
        this[K.recBtnEndMs] = r.buttonEndTrimMs
    }

    private fun Preferences.buttonConfig(): HeadsetButtonConfig {
        val d = HeadsetButtonConfig()
        return HeadsetButtonConfig(
            enabled = this[K.btnEnabled] ?: d.enabled,
            keyCodes = this[K.btnKeys]?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.toSet() ?: d.keyCodes,
            singlePress = enumOr(this[K.btnSingle], d.singlePress),
            doublePress = enumOr(this[K.btnDouble], d.doublePress),
            longPress = enumOr(this[K.btnLong], d.longPress),
            doublePressMs = this[K.btnDoubleMs] ?: d.doublePressMs,
            longPressMs = this[K.btnLongMs] ?: d.longPressMs,
        )
    }

    private fun Preferences.recordingControl(): RecordingControl {
        val d = RecordingControl()
        return RecordingControl(
            startByBeep = this[K.recStartBeep] ?: d.startByBeep,
            startByButton = this[K.recStartButton] ?: d.startByButton,
            stopByBeep = this[K.recStopBeep] ?: d.stopByBeep,
            stopByButton = this[K.recStopButton] ?: d.stopByButton,
            stopOnSilence = this[K.recStopSilence] ?: d.stopOnSilence,
            silenceMs = this[K.recSilenceMs] ?: d.silenceMs,
            silenceThresholdDbfs = this[K.recSilenceDb] ?: d.silenceThresholdDbfs,
            minSpeechBeforeAutoStopMs = this[K.recMinSpeechMs] ?: d.minSpeechBeforeAutoStopMs,
            buttonStartOffsetMs = this[K.recBtnStartMs] ?: d.buttonStartOffsetMs,
            buttonEndTrimMs = this[K.recBtnEndMs] ?: d.buttonEndTrimMs,
            bluetoothMic = enumOr(this[K.recBluetoothMic], d.bluetoothMic),
            cueSounds = this[K.recCueSounds] ?: d.cueSounds,
        )
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default
}
