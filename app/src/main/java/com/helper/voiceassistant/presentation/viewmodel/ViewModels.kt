package com.helper.voiceassistant.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.helper.voiceassistant.assistant.AssistantController
import com.helper.voiceassistant.data.local.KeystoreSecretStore
import com.helper.voiceassistant.data.stt.SttModelManager
import com.helper.voiceassistant.data.stt.SttModelStatus
import com.helper.voiceassistant.data.tts.TtsSpeaker
import com.helper.voiceassistant.data.tts.VoiceOption
import com.helper.voiceassistant.domain.model.AssistantSettings
import com.helper.voiceassistant.domain.model.ConversationEntry
import com.helper.voiceassistant.domain.repository.ConversationRepository
import com.helper.voiceassistant.domain.repository.SecretKey
import com.helper.voiceassistant.domain.repository.SettingsRepository
import com.helper.voiceassistant.manager.AudioDeviceManager
import com.helper.voiceassistant.manager.DetectedKey
import com.helper.voiceassistant.manager.MediaButtonManager
import com.helper.voiceassistant.util.LogBuffer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Named
import com.helper.voiceassistant.util.tr

@HiltViewModel
class MainViewModel @Inject constructor(
    private val controller: AssistantController,
    private val settingsRepository: SettingsRepository,
    private val secrets: KeystoreSecretStore,
    private val devices: AudioDeviceManager,
    private val sttModel: SttModelManager,
) : ViewModel() {
    val status = controller.status
    val sttModelStatus: StateFlow<SttModelStatus> = sttModel.status
    val route = controller.route
    val micLevel = controller.micLevelDb
    val settings: StateFlow<AssistantSettings> =
        settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AssistantSettings())

    private val _keysMissing = MutableStateFlow(false)
    val keysMissing: StateFlow<Boolean> = _keysMissing.asStateFlow()

    init {
        viewModelScope.launch { controller.restoreLastResponse() }
        devices.refresh()
        refreshKeys()
    }

    fun refreshKeys() {
        viewModelScope.launch {
            _keysMissing.value = withContext(Dispatchers.IO) {
                !secrets.has(SecretKey.DEEPSEEK_API_KEY)
            }
        }
    }

    fun refreshDevices() = devices.refresh()
    fun replay() = controller.replayLast()
    fun stopPlayback() = controller.stopPlayback()
    fun cancelRecording() = controller.cancelRecording()
    fun downloadSttModel() = sttModel.startDownload()
    fun giveSttConsent() = viewModelScope.launch { settingsRepository.update { it.copy(sttConsentGiven = true) } }
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val secrets: KeystoreSecretStore,
    private val conversations: ConversationRepository,
    private val tts: TtsSpeaker,
    devices: AudioDeviceManager,
    @Named("recordingsDir") private val recordingsDir: File,
    private val sttModel: SttModelManager,
    private val buttons: MediaButtonManager,
) : ViewModel() {
    val sttModelStatus: StateFlow<SttModelStatus> = sttModel.status

    private val _learningButton = MutableStateFlow(false)
    val learningButton: StateFlow<Boolean> = _learningButton.asStateFlow()
    private val _lastKey = MutableStateFlow<DetectedKey?>(null)
    val lastKey: StateFlow<DetectedKey?> = _lastKey.asStateFlow()
    private var learnStartedAt = 0L

    init {
        // Only show presses made after «Detect» was tapped.
        viewModelScope.launch {
            buttons.lastKey.collect { k -> if (_learningButton.value && k != null && k.atMs >= learnStartedAt) _lastKey.value = k }
        }
    }

    fun startButtonLearning() {
        if (_learningButton.value) return
        learnStartedAt = android.os.SystemClock.uptimeMillis()
        _lastKey.value = null
        _learningButton.value = true
        buttons.learning = true
        buttons.acquire(LEARN_OWNER)
    }

    fun stopButtonLearning() {
        if (!_learningButton.value) return
        _learningButton.value = false
        buttons.learning = false
        buttons.release(LEARN_OWNER)
    }

    override fun onCleared() {
        stopButtonLearning()
    }

    fun downloadSttModel() = sttModel.startDownload()
    fun cancelSttDownload() = sttModel.cancelDownload()
    fun deleteSttModel() {
        viewModelScope.launch(Dispatchers.IO) { sttModel.delete(); _message.value = tr("Speech model deleted", "Модель распознавания удалена") }
    }

    val settings: StateFlow<AssistantSettings> =
        settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AssistantSettings())
    val route = devices.route
    val ttsWarning = tts.warning

    private val _voices = MutableStateFlow<List<VoiceOption>>(emptyList())
    val voices: StateFlow<List<VoiceOption>> = _voices.asStateFlow()

    private val _keyState = MutableStateFlow(KeyState())
    val keyState: StateFlow<KeyState> = _keyState.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    data class KeyState(val deepSeekSet: Boolean = false)

    init {
        devices.refresh()
        refreshKeyState()
        loadVoices(null)
    }

    fun update(transform: (AssistantSettings) -> AssistantSettings) {
        viewModelScope.launch { settingsRepository.update(transform) }
    }

    fun loadVoices(languageTag: String?) {
        viewModelScope.launch { _voices.value = tts.voices(languageTag ?: settings.value.ttsLanguage) }
    }

    fun saveKey(key: SecretKey, value: String) {
        viewModelScope.launch(Dispatchers.IO) {
            secrets.put(key, value)
            refreshKeyState()
            _message.value = if (value.isBlank()) tr("Key removed", "Ключ удалён") else tr("Key saved (encrypted)", "Ключ сохранён (зашифрован)")
        }
    }

    private fun refreshKeyState() {
        viewModelScope.launch(Dispatchers.IO) {
            _keyState.value = KeyState(secrets.has(SecretKey.DEEPSEEK_API_KEY))
        }
    }

    fun clearHistory() {
        viewModelScope.launch { conversations.clear(); _message.value = tr("History cleared", "История очищена") }
    }

    /** Deletes history, stored recordings, API keys and logs. */
    fun deleteAllLocalData() {
        viewModelScope.launch(Dispatchers.IO) {
            conversations.clear()
            recordingsDir.listFiles()?.forEach { it.delete() }
            secrets.put(SecretKey.DEEPSEEK_API_KEY, null)
            LogBuffer.clear()
            refreshKeyState()
            _message.value = tr("All local data deleted", "Все локальные данные удалены")
        }
    }

    fun resetDefaults() = update { AssistantSettings(sttConsentGiven = it.sttConsentGiven, uiLanguage = it.uiLanguage) }

    fun consumeMessage() { _message.value = null }

    private companion object { const val LEARN_OWNER = "settings-learn" }
}

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val conversations: ConversationRepository,
    private val controller: AssistantController,
) : ViewModel() {
    val entries: StateFlow<List<ConversationEntry>> =
        conversations.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun replay(entry: ConversationEntry) {
        entry.responseText?.let { controller.replayText(it) }
    }

    fun stopPlayback() = controller.stopPlayback()
    fun delete(id: Long) = viewModelScope.launch { conversations.delete(id) }
    fun clear() = viewModelScope.launch { conversations.clear() }
}
