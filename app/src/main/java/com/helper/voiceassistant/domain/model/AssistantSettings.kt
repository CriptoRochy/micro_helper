package com.helper.voiceassistant.domain.model

/** Interface language. SYSTEM follows the phone language (Russian if the phone is in Russian, otherwise English). */
enum class UiLanguage { SYSTEM, RUSSIAN, ENGLISH }

/** Which AudioAttributes usage TTS uses. AUTO = voice communication while a SCO/communication route is active. */
enum class TtsAudioUsage { AUTO, MEDIA, VOICE_COMMUNICATION, ASSISTANCE_ACCESSIBILITY }

/** AudioRecord source. VOICE_RECOGNITION avoids aggressive noise suppression that can erase the control tone. */
enum class MicSource { VOICE_RECOGNITION, MIC, VOICE_COMMUNICATION }

/** Beep detector tuning. All values configurable in Settings. */
data class BeepConfig(
    val enabled: Boolean = true,
    val frequencyHz: Float = 2000f,
    val toleranceHz: Float = 50f,
    val thresholdDbfs: Float = -30f,
    /** Fraction (0..1) of frame energy that must sit in the target band. Higher = stricter, fewer false positives. */
    val tonalityRatio: Float = 0.45f,
    val minBeepMs: Int = 60,
    val maxBeepMs: Int = 600,
    /** Max silence between the end of beep 1 and the start of beep 2 to count as a double beep. */
    val doubleBeepIntervalMs: Int = 450,
    /** Pulses starting within this time after a handled command are ignored (debounce). */
    val debounceMs: Int = 350,
    /** Hysteresis: the tone must fall this many dB below threshold to count as ended. */
    val hysteresisDb: Float = 6f,
    /** Audio cut away next to each control beep so that no part of it ends up in the recording. */
    val guardMs: Int = 60,
)

/** What a headset-button gesture does. START_STOP behaves exactly like a single control beep. */
enum class ButtonAction { NONE, START_STOP_RECORDING, REPLAY, CANCEL_RECORDING, STOP_SPEECH }

/**
 * Headset / media button control. Key codes are android.view.KeyEvent codes so that any headset can be
 * taught with the "detect button" function in Settings.
 */
data class HeadsetButtonConfig(
    val enabled: Boolean = true,
    val keyCodes: Set<Int> = DEFAULT_KEY_CODES,
    val singlePress: ButtonAction = ButtonAction.START_STOP_RECORDING,
    val doublePress: ButtonAction = ButtonAction.REPLAY,
    val longPress: ButtonAction = ButtonAction.CANCEL_RECORDING,
    /** A second press within this time after the first one is a double press. */
    val doublePressMs: Int = 400,
    /** Holding the button at least this long is a long press. */
    val longPressMs: Int = 800,
) {
    companion object {
        const val KEYCODE_HEADSETHOOK = 79
        const val KEYCODE_MEDIA_PLAY_PAUSE = 85
        const val KEYCODE_MEDIA_NEXT = 87
        const val KEYCODE_MEDIA_PREVIOUS = 88
        const val KEYCODE_MEDIA_PLAY = 126
        const val KEYCODE_MEDIA_PAUSE = 127
        val DEFAULT_KEY_CODES = setOf(KEYCODE_HEADSETHOOK, KEYCODE_MEDIA_PLAY_PAUSE, KEYCODE_MEDIA_PLAY, KEYCODE_MEDIA_PAUSE)
    }
}

/** Which events start and end a recording, and how much audio is cut next to them. */
data class RecordingControl(
    val startByBeep: Boolean = true,
    val startByButton: Boolean = true,
    val stopByBeep: Boolean = true,
    val stopByButton: Boolean = true,
    /** Ends the recording automatically after [silenceMs] of silence that follows speech. */
    val stopOnSilence: Boolean = false,
    val silenceMs: Int = 1500,
    /** Audio quieter than this counts as silence. */
    val silenceThresholdDbfs: Float = -45f,
    /** Automatic stop is armed only after this much speech, so a pause before speaking doesn't end it. */
    val minSpeechBeforeAutoStopMs: Int = 400,
    /** Audio skipped after the button press that starts a recording (button click, headset prompt tone). */
    val buttonStartOffsetMs: Int = 200,
    /** Audio cut before the button press that ends a recording. */
    val buttonEndTrimMs: Int = 150,
    /** Which microphone a Bluetooth headset setup uses, see [BluetoothMicMode]. */
    val bluetoothMic: BluetoothMicMode = BluetoothMicMode.WHILE_RECORDING,
    /** A sound before the recording starts (speak after it) and one after it ends. */
    val cueSounds: Boolean = true,
)

/**
 * A Bluetooth headset microphone only works in HFP/SCO "call mode", and in call mode most headsets
 * send no media buttons (play/pause, next track). This chooses the trade-off.
 */
enum class BluetoothMicMode {
    /** Wait on the phone mic (buttons work), record with the headset mic (buttons may not arrive). */
    WHILE_RECORDING,
    /** Always the headset mic (call mode all the time): best sound, buttons usually don't work. */
    ALWAYS,
    /** Always the phone mic: the headset stays in music mode, buttons work all the time. */
    NEVER,
}

data class AssistantSettings(
    val uiLanguage: UiLanguage = UiLanguage.SYSTEM,
    val beep: BeepConfig = BeepConfig(),
    val button: HeadsetButtonConfig = HeadsetButtonConfig(),
    val recording: RecordingControl = RecordingControl(),
    val maxRecordingSec: Int = 120,
    val minRecordingMs: Int = 600,
    val minSpeechRmsDbfs: Float = -50f,
    val micSource: MicSource = MicSource.VOICE_RECOGNITION,
    /** AudioDeviceInfo.id of the preferred input, or -1 for automatic headset selection. */
    val preferredInputDeviceId: Int = -1,

    /** Speech recognition runs on-device (GigaAM v3); only the recognized text is sent to DeepSeek. */
    val sttConsentGiven: Boolean = false,

    val deepSeekBaseUrl: String = "https://api.deepseek.com",
    val deepSeekModel: String = "deepseek-flash",
    val deepSeekThinking: Boolean = false,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    /** Number of previous exchanges sent as context. */
    val contextExchanges: Int = 6,
    val requestTimeoutSec: Int = 60,
    val maxRetries: Int = 3,

    val ttsRate: Float = 1.0f,
    val ttsVolume: Float = 1.0f,
    val ttsVoiceName: String = "",
    /** BCP-47 tag, e.g. "ru-RU", "en-US". */
    val ttsLanguage: String = "ru-RU",
    val ttsAudioUsage: TtsAudioUsage = TtsAudioUsage.AUTO,
    /** Short spoken notifications in the headset ("recording too short", "nothing to repeat"...). */
    val spokenFeedback: Boolean = true,

    val historyEnabled: Boolean = true,
    val historyLimit: Int = 20,
    val keepRecordings: Boolean = false,
) {
    companion object {
        const val DEFAULT_SYSTEM_PROMPT =
            "You are a voice AI assistant. Respond naturally, clearly, and concisely. " +
                "Your answers are read aloud, so avoid markdown, lists, tables, code blocks and emoji. " +
                "Answer in the language the user speaks."
    }
}
