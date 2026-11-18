package com.helper.voiceassistant.domain.model

import com.helper.voiceassistant.util.tr

/**
 * Failure raised by STT, LLM, TTS or audio layers. [userMessage] is safe to show and never contains
 * secrets; technical detail goes to logs only.
 */
class AssistantFailure(
    val kind: ErrorKind,
    val userMessage: String,
    cause: Throwable? = null,
    val httpCode: Int? = null,
) : Exception(userMessage, cause) {
    fun toError(hint: String? = null) = AssistantError(kind, userMessage, hint ?: defaultHint())

    private fun defaultHint(): String? = when (kind) {
        ErrorKind.INVALID_API_KEY -> tr("Check the API key in Settings.", "Проверьте API-ключ в настройках.")
        ErrorKind.STT_MODEL_MISSING -> tr("Download the offline speech model in Settings › Local STT model.", "Скачайте офлайн-модель: Настройки › Локальная модель распознавания.")
        ErrorKind.NETWORK -> tr("Check the internet connection and try again.", "Проверьте подключение к интернету и попробуйте ещё раз.")
        ErrorKind.RATE_LIMITED -> tr("Too many requests — wait a little and try again.", "Слишком много запросов — подождите и попробуйте снова.")
        ErrorKind.NO_PERMISSION -> tr("Grant the microphone permission and start again.", "Разрешите доступ к микрофону и запустите снова.")
        ErrorKind.AUDIO_DEVICE_LOST -> tr("Reconnect the headset; the assistant will continue.", "Подключите гарнитуру снова — ассистент продолжит работу.")
        ErrorKind.TTS_FAILED -> tr("Install a TTS voice (Settings › Text-to-speech) or pick another language.", "Установите голос синтеза речи (Настройки › Синтез речи) или выберите другой язык.")
        else -> null
    }
}
