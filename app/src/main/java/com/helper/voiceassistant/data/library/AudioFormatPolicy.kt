package com.helper.voiceassistant.data.library

import com.helper.voiceassistant.util.tr

/** Why a file could not be added to the library. */
enum class ImportError {
    TOO_LARGE, UNSUPPORTED, CORRUPT, NO_SPACE, READ_FAILED;

    fun message(): String = when (this) {
        TOO_LARGE -> tr("The file is larger than 50 MB.", "Файл больше 50 МБ.")
        UNSUPPORTED -> tr("Unsupported format. Use MP3, M4A (AAC) or WAV (PCM).", "Формат не поддерживается. Подходят MP3, M4A (AAC) и WAV (PCM).")
        CORRUPT -> tr("The file is damaged or is not an audio file.", "Файл повреждён или не является аудиофайлом.")
        NO_SPACE -> tr("Not enough free space on the phone.", "На телефоне недостаточно свободного места.")
        READ_FAILED -> tr("The file could not be read.", "Не удалось прочитать файл.")
    }
}

/** Formats accepted in the first version, checked by the codec inside the file (not the extension). */
object AudioFormatPolicy {
    const val MAX_BYTES: Long = 50L * 1024 * 1024

    /** MediaExtractor track MIME → extension of the stored copy. */
    private val CODECS = mapOf(
        "audio/mpeg" to "mp3",      // MP3
        "audio/mp4a-latm" to "m4a", // AAC (M4A / MP4 container)
        "audio/raw" to "wav",       // PCM WAV
    )

    // System picker filter. The generic audio type is included because many file managers report it.
    val PICKER_MIME_TYPES = arrayOf("audio/mpeg", "audio/mp4", "audio/x-m4a", "audio/aac", "audio/wav", "audio/x-wav", "audio/*")

    fun describe(): String = tr("MP3, M4A (AAC) or WAV (PCM), up to 50 MB", "MP3, M4A (AAC) или WAV (PCM), до 50 МБ")

    fun isTooLarge(sizeBytes: Long): Boolean = sizeBytes > MAX_BYTES

    fun isSupportedCodec(codecMime: String?): Boolean = codecMime?.lowercase() in CODECS

    fun extensionFor(codecMime: String): String = CODECS[codecMime.lowercase()] ?: "bin"

    /** Free space needed to copy a file of [sizeBytes], keeping a small reserve for the system. */
    fun hasRoomFor(sizeBytes: Long, usableBytes: Long): Boolean = usableBytes - sizeBytes > RESERVE_BYTES

    /** "raspisanie.mp3" → "raspisanie" (the editable default title). */
    fun titleFromFileName(name: String): String = name.substringBeforeLast('.', name).trim().ifEmpty { name }

    private const val RESERVE_BYTES = 20L * 1024 * 1024
}
