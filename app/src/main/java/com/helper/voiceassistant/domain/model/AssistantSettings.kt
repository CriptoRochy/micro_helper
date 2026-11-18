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

data class AssistantSettings(
    val uiLanguage: UiLanguage = UiLanguage.SYSTEM,
    val beep: BeepConfig = BeepConfig(),
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
