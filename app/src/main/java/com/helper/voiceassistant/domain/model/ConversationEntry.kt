package com.helper.voiceassistant.domain.model

import com.helper.voiceassistant.util.tr

enum class ExchangeStatus { SUCCESS, STT_ERROR, API_ERROR, TTS_ERROR }

data class ConversationEntry(
    val id: Long = 0,
    val timestampMs: Long,
    val userText: String,
    val responseText: String?,
    val status: ExchangeStatus,
    val errorMessage: String? = null,
)

/** One message of the chat context sent to the LLM. */
data class ChatMessage(val role: Role, val content: String) {
    enum class Role(val wire: String) { SYSTEM("system"), USER("user"), ASSISTANT("assistant") }
}

data class AudioDeviceSummary(
    val id: Int,
    val name: String,
    val type: AudioDeviceKind,
    val isSource: Boolean,
)

enum class AudioDeviceKind(private val en: String, private val ru: String) {
    BLUETOOTH_SCO("Bluetooth headset (HFP)", "Bluetooth-гарнитура (HFP)"),
    BLUETOOTH_A2DP("Bluetooth (A2DP, no mic)", "Bluetooth (A2DP, без микрофона)"),
    BLE_HEADSET("Bluetooth LE headset", "Bluetooth LE-гарнитура"),
    WIRED_HEADSET("Wired headset", "Проводная гарнитура"),
    USB_HEADSET("USB headset", "USB-гарнитура"),
    BUILTIN_MIC("Phone microphone", "Микрофон телефона"),
    BUILTIN_SPEAKER("Phone speaker", "Динамик телефона"),
    OTHER("Other", "Другое"),
    ;

    val label: String get() = tr(en, ru)
}

/** Current routing summary for the UI. */
data class AudioRouteInfo(
    val headsetConnected: Boolean = false,
    val inputName: String? = null,
    val inputKind: AudioDeviceKind? = null,
    val outputName: String? = null,
    val usesCommunicationRoute: Boolean = false,
    val availableInputs: List<AudioDeviceSummary> = emptyList(),
)
