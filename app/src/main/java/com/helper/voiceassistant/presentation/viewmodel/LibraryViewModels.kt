package com.helper.voiceassistant.presentation.viewmodel

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.helper.voiceassistant.assistant.AssistantController
import com.helper.voiceassistant.assistant.ClipState
import com.helper.voiceassistant.data.library.AudioFormatPolicy
import com.helper.voiceassistant.data.library.AudioLibraryRepository
import com.helper.voiceassistant.data.library.LibraryDraft
import com.helper.voiceassistant.data.library.PhraseConflict
import com.helper.voiceassistant.data.library.SaveResult
import com.helper.voiceassistant.data.library.StageResult
import com.helper.voiceassistant.data.library.StagedFile
import com.helper.voiceassistant.data.stt.SttModelManager
import com.helper.voiceassistant.data.stt.SttModelStatus
import com.helper.voiceassistant.domain.model.AssistantMode
import com.helper.voiceassistant.domain.model.AssistantSettings
import com.helper.voiceassistant.domain.model.LibraryItem
import com.helper.voiceassistant.domain.repository.SettingsRepository
import com.helper.voiceassistant.util.tr
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import javax.inject.Inject

/**
 * «Голосовой поиск». While this screen is on the back stack the assistant searches the audio library
 * instead of asking DeepSeek; leaving it (back) returns to the normal dialog without a restart.
 */
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val controller: AssistantController,
    library: AudioLibraryRepository,
    private val sttModel: SttModelManager,
    settingsRepository: SettingsRepository,
) : ViewModel() {
    val status = controller.status
    val route = controller.route
    val micLevel = controller.micLevelDb
    val clip: StateFlow<ClipState> = controller.clip
    val sttModelStatus: StateFlow<SttModelStatus> = sttModel.status
    val settings: StateFlow<AssistantSettings> =
        settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AssistantSettings())
    /** null until loaded. */
    val libraryCount: StateFlow<Int?> =
        library.observeItems().map { it.size }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        controller.setMode(AssistantMode.SEARCH)
    }

    override fun onCleared() {
        controller.setMode(AssistantMode.DIALOG)
    }

    fun talk() = controller.talk()
    fun cancelRecording() = controller.cancelRecording()
    fun pauseClip() = controller.pauseClip()
    fun resumeClip() = controller.resumeClip()
    fun seekClip(deltaMs: Long) = controller.seekClip(deltaMs)
    fun stopClip() = controller.stopClip()
    fun downloadSttModel() = sttModel.startDownload()
}

/** The entry being added or edited. [id] null = new entry. */
data class LibraryEditor(
    val id: String? = null,
    val title: String = "",
    val phrasesText: String = "",
    /** A newly picked file (new entry, or replacement), not saved yet. */
    val staged: StagedFile? = null,
    /** The file the entry plays now (editing only). */
    val currentFileName: String? = null,
    val currentDurationMs: Long = 0,
    val error: String? = null,
    /** Non-null: the phrases clash with other entries; the user picks "change" or "keep both". */
    val conflicts: List<PhraseConflict>? = null,
) {
    val phrases: List<String> get() = phrasesText.lines()
}

/** «Настройки аудиотеки»: list, add, edit, replace the file, delete. */
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val library: AudioLibraryRepository,
) : ViewModel() {
    val items: StateFlow<List<LibraryItem>?> =
        library.observeItems().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _editor = MutableStateFlow<LibraryEditor?>(null)
    val editor: StateFlow<LibraryEditor?> = _editor.asStateFlow()

    private val _busy = MutableStateFlow<String?>(null)
    /** What is in progress ("Копирую файл…"), or null. */
    val busy: StateFlow<String?> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Key of what the preview plays: an entry id, or [STAGED]. */
    private val _previewing = MutableStateFlow<String?>(null)
    val previewing: StateFlow<String?> = _previewing.asStateFlow()
    private var preview: MediaPlayer? = null

    /** Leftovers of interrupted imports are removed before anything new is staged. */
    private val cleaned = viewModelScope.async { runCatching { library.cleanUp() } }

    fun consumeMessage() { _message.value = null }

    /** A file picked with «Добавить запись»: its name becomes the editable default title. */
    fun onPickedForNew(uri: Uri) = viewModelScope.launch {
        val staged = stage(uri) ?: return@launch
        _editor.value = LibraryEditor(title = AudioFormatPolicy.titleFromFileName(staged.displayName), staged = staged)
    }

    fun edit(item: LibraryItem) {
        stopPreview()
        _editor.value = LibraryEditor(
            id = item.id, title = item.title, phrasesText = item.phrases.joinToString("\n"),
            currentFileName = item.originalName, currentDurationMs = item.durationMs,
        )
    }

    /** «Заменить файл»: the new file is only staged; the entry keeps its file until saving succeeds. */
    fun onPickedReplacement(uri: Uri) = viewModelScope.launch {
        if (_previewing.value == STAGED) stopPreview()
        val staged = stage(uri, inEditor = true) ?: return@launch
        val old = _editor.value?.staged
        _editor.update { it?.copy(staged = staged, error = null) }
        if (old != null && old != staged) library.discard(old)
    }

    fun setTitle(title: String) = _editor.update { it?.copy(title = title, error = null) }
    fun setPhrases(text: String) = _editor.update { it?.copy(phrasesText = text, error = null) }

    /** Checks phrase clashes first; [keepBoth] saves anyway. */
    fun save(keepBoth: Boolean = false) = viewModelScope.launch {
        val e = _editor.value ?: return@launch
        val draft = LibraryDraft(e.id, e.title, e.phrases, e.staged)
        if (!keepBoth) {
            val clashes = runCatching { library.conflicts(draft) }.getOrDefault(emptyList())
            if (clashes.isNotEmpty()) {
                _editor.value = e.copy(conflicts = clashes)
                return@launch
            }
        }
        stopPreview()
        _busy.value = tr("Saving…", "Сохраняю…")
        val result = library.save(draft)
        _busy.value = null
        when (result) {
            is SaveResult.Saved -> {
                _editor.value = null
                _message.value = tr("Saved «${result.item.title}».", "Запись «${result.item.title}» сохранена.")
            }
            // A failed save removes the staged copy; validation errors keep it.
            is SaveResult.Failed -> _editor.value = e.copy(error = result.message, conflicts = null, staged = e.staged?.takeIf { it.file.isFile })
        }
    }

    /** «Изменить фразу»: back to the editor. */
    fun dismissConflicts() = _editor.update { it?.copy(conflicts = null) }

    fun cancelEdit() {
        stopPreview()
        library.discard(_editor.value?.staged)
        _editor.value = null
    }

    /** Removes the entry and its private copy; the user's original file stays where it was. */
    fun delete(item: LibraryItem) = viewModelScope.launch {
        if (_previewing.value == item.id) stopPreview()
        runCatching { library.delete(item.id) }
            .onSuccess { _message.value = tr("«${item.title}» deleted.", "Запись «${item.title}» удалена.") }
            .onFailure { Timber.e(it, "Delete failed"); _message.value = tr("Could not delete the entry.", "Не удалось удалить запись.") }
    }

    fun togglePreview(item: LibraryItem) = togglePreview(item.id, library.fileFor(item))

    fun togglePreviewStaged() {
        val staged = _editor.value?.staged
        if (staged != null) togglePreview(STAGED, staged.file)
        else _editor.value?.id?.let { id -> items.value?.firstOrNull { it.id == id }?.let { togglePreview(it) } }
    }

    private fun togglePreview(key: String, file: File) {
        if (_previewing.value == key) { stopPreview(); return }
        stopPreview()
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            mp.setDataSource(file.path)
            mp.setOnCompletionListener { if (preview === it) stopPreview() }
            mp.setOnErrorListener { p, _, _ -> if (preview === p) stopPreview(); true }
            mp.prepare()
            mp.start()
            preview = mp
            _previewing.value = key
        } catch (e: Exception) {
            Timber.w(e, "Preview failed")
            mp.release()
            _message.value = tr("The file cannot be played.", "Файл не удаётся воспроизвести.")
        }
    }

    fun stopPreview() {
        preview?.let { runCatching { it.stop() }; it.release() }
        preview = null
        _previewing.value = null
    }

    private suspend fun stage(uri: Uri, inEditor: Boolean = false): StagedFile? {
        cleaned.await()
        _busy.value = tr("Copying the file…", "Копирую файл…")
        val result = runCatching { library.stage(uri) }.getOrElse {
            Timber.e(it, "Staging failed")
            StageResult.Failed(com.helper.voiceassistant.data.library.ImportError.READ_FAILED)
        }
        _busy.value = null
        return when (result) {
            is StageResult.Ok -> result.staged
            is StageResult.Failed -> {
                val text = tr("Not added: ", "Файл не добавлен: ") + result.error.message()
                if (inEditor) _editor.update { it?.copy(error = text) } else _message.value = text
                null
            }
        }
    }

    override fun onCleared() {
        stopPreview()
        library.discard(_editor.value?.staged)
    }

    companion object {
        const val STAGED = "staged"
    }
}
