package com.helper.voiceassistant.domain.model

/**
 * One entry of the audio library: an imported audio file plus the voice phrases that call it.
 * [id] is a permanent UUID, so renaming the entry or importing two files with the same name never
 * mixes them up. [fileName] is the name of the private copy in the app's storage.
 */
data class LibraryItem(
    val id: String,
    val title: String,
    val phrases: List<String>,
    val fileName: String,
    val originalName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

/** What a recognized phrase is used for. */
enum class AssistantMode {
    /** Ask DeepSeek and speak the answer (needs internet and the API key). */
    DIALOG,
    /** Find a recording in the local audio library and play it (works offline). */
    SEARCH,
}
