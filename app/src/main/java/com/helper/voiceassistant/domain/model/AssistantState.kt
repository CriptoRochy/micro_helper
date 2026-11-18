package com.helper.voiceassistant.domain.model

/** Every state the assistant can be in. Transitions are validated by AssistantStateMachine. */
enum class AssistantState {
    IDLE,
    CONNECTING_AUDIO,
    LISTENING,
    RECORDING,
    TRANSCRIBING,
    PROCESSING,
    SPEAKING,
    REPLAYING,
    /** Voice search: a library recording is playing. */
    PLAYING_CLIP,
    ERROR;

    val isActive: Boolean get() = this != IDLE
}

/** Classifies failures so the UI can show a clear message and the controller can decide on recovery. */
enum class ErrorKind(val recoverable: Boolean) {
    NO_PERMISSION(false),
    AUDIO_INIT(false),
    AUDIO_DEVICE_LOST(true),
    EMPTY_RECORDING(true),
    STT_FAILED(true),
    STT_MODEL_MISSING(true),
    NETWORK(true),
    INVALID_API_KEY(true),
    RATE_LIMITED(true),
    API_ERROR(true),
    EMPTY_RESPONSE(true),
    TTS_FAILED(true),
    NOTHING_TO_REPLAY(true),
    LIBRARY_NOT_FOUND(true),
    CLIP_PLAYBACK(true),
    /** Dialog mode needs the user's consent to send text to DeepSeek (search mode does not). */
    CONSENT_REQUIRED(true),
    UNKNOWN(true),
}

data class AssistantError(
    val kind: ErrorKind,
    val message: String,
    val recoveryHint: String? = null,
)

/** Snapshot exposed to the UI and notification. */
data class AssistantStatus(
    val state: AssistantState = AssistantState.IDLE,
    val error: AssistantError? = null,
    val info: String? = null,
    val recordingStartedAtMs: Long? = null,
    val lastRecognizedText: String? = null,
    val lastResponse: String? = null,
    /** What the next recognized phrase is used for. */
    val mode: AssistantMode = AssistantMode.DIALOG,
    /** Voice search is waiting for "первый / второй…": titles in announced order. */
    val choiceOptions: List<String>? = null,
)
