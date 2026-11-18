package com.helper.voiceassistant.data.library

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import com.helper.voiceassistant.assistant.AudioLibrary
import com.helper.voiceassistant.data.local.LibraryDao
import com.helper.voiceassistant.data.local.LibraryItemEntity
import com.helper.voiceassistant.data.local.LibraryItemWithPhrases
import com.helper.voiceassistant.data.local.LibraryPhraseEntity
import com.helper.voiceassistant.domain.model.LibraryItem
import com.helper.voiceassistant.search.LibrarySearch
import com.helper.voiceassistant.search.PhraseNormalizer
import com.helper.voiceassistant.util.tr
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/** A file copied into the app's staging area and checked, waiting to be saved (or discarded). */
data class StagedFile(
    val file: File,
    val displayName: String,
    val codecMime: String,
    val sizeBytes: Long,
    val durationMs: Long,
)

sealed interface StageResult {
    data class Ok(val staged: StagedFile) : StageResult
    data class Failed(val error: ImportError) : StageResult
}

/** [phrase] (title or key phrase) is already used by the other entry [otherTitle]. */
data class PhraseConflict(val phrase: String, val otherTitle: String)

/** What the editor saves. [id] is null for a new entry; [staged] is null when the file is kept. */
data class LibraryDraft(
    val id: String?,
    val title: String,
    val phrases: List<String>,
    val staged: StagedFile?,
)

sealed interface SaveResult {
    data class Saved(val item: LibraryItem) : SaveResult
    data class Failed(val message: String) : SaveResult
}

/**
 * The audio library: entries in Room, audio files as private copies in `filesDir/library/`.
 *
 * Import is two-step so nothing half-written ever reaches the search: the picked file is first copied
 * into `library/staging/` and checked (format, size, free space), then [save] moves it into place and
 * writes the entry and its phrases in one transaction. Each saved file gets a fresh name, so replacing
 * the file of an entry never touches the old copy until the database points at the new one; if
 * anything fails, the new copy is deleted and the entry keeps playing its previous file.
 */
@Singleton
class AudioLibraryRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: LibraryDao,
    @Named("libraryDir") private val dir: File,
) : AudioLibrary {

    private val stagingDir get() = File(dir, "staging")

    /** Test hook: simulates a failure while committing a save (after the file was moved into place). */
    @Volatile internal var failNextCommit = false

    fun observeItems(): Flow<List<LibraryItem>> = dao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun items(): List<LibraryItem> = withContext(Dispatchers.IO) { dao.all().map { it.toDomain() } }

    suspend fun get(id: String): LibraryItem? = withContext(Dispatchers.IO) { dao.get(id)?.toDomain() }

    override fun fileFor(item: LibraryItem): File = File(dir, item.fileName)

    // ───────────────────────────── import ─────────────────────────────

    /** Copies the picked document into staging and checks it. */
    suspend fun stage(uri: Uri): StageResult = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var name = "audio"
        var size = -1L
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        if (size >= 0 && AudioFormatPolicy.isTooLarge(size)) return@withContext StageResult.Failed(ImportError.TOO_LARGE)
        stagingDir.mkdirs()
        if (size >= 0 && !AudioFormatPolicy.hasRoomFor(size, freeBytes())) return@withContext StageResult.Failed(ImportError.NO_SPACE)
        val part = File(stagingDir, "${UUID.randomUUID()}.part")
        val copied = try {
            val input = resolver.openInputStream(uri) ?: return@withContext StageResult.Failed(ImportError.READ_FAILED)
            input.use { copyLimited(it, part) }
        } catch (e: IOException) {
            part.delete()
            Timber.w(e, "Import copy failed")
            return@withContext StageResult.Failed(if (isNoSpace(e)) ImportError.NO_SPACE else ImportError.READ_FAILED)
        } catch (e: SecurityException) {
            part.delete()
            return@withContext StageResult.Failed(ImportError.READ_FAILED)
        }
        if (copied == null) { part.delete(); return@withContext StageResult.Failed(ImportError.TOO_LARGE) }
        inspect(part, name)
    }

    /** Stages a local file (used by tests and internal copies). */
    suspend fun stageFile(source: File, displayName: String = source.name): StageResult = withContext(Dispatchers.IO) {
        if (AudioFormatPolicy.isTooLarge(source.length())) return@withContext StageResult.Failed(ImportError.TOO_LARGE)
        stagingDir.mkdirs()
        val part = File(stagingDir, "${UUID.randomUUID()}.part")
        try {
            source.inputStream().use { copyLimited(it, part) } ?: run { part.delete(); return@withContext StageResult.Failed(ImportError.TOO_LARGE) }
        } catch (e: IOException) {
            part.delete()
            return@withContext StageResult.Failed(if (isNoSpace(e)) ImportError.NO_SPACE else ImportError.READ_FAILED)
        }
        inspect(part, displayName)
    }

    /** Space the system can give us, counting cache it would clear (falls back to plain free space). */
    private fun freeBytes(): Long = try {
        val storage = context.getSystemService(StorageManager::class.java)
        storage.getAllocatableBytes(storage.getUuidForPath(stagingDir))
    } catch (e: Exception) {
        stagingDir.usableSpace
    }

    fun discard(staged: StagedFile?) {
        staged?.file?.delete()
    }

    /** Returns bytes copied, or null if the limit was exceeded. */
    private fun copyLimited(input: java.io.InputStream, target: File): Long? {
        var total = 0L
        target.outputStream().use { out ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (AudioFormatPolicy.isTooLarge(total)) return null
                out.write(buf, 0, n)
            }
            out.fd.sync()
        }
        return total
    }

    private fun inspect(part: File, displayName: String): StageResult {
        val codec = try {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(part.path)
                (0 until extractor.trackCount)
                    .map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) }
                    .firstOrNull { it?.startsWith("audio/") == true }
            } finally {
                extractor.release()
            }
        } catch (e: Exception) {
            Timber.w(e, "Not a readable audio file: %s", displayName)
            part.delete()
            return StageResult.Failed(ImportError.CORRUPT)
        }
        if (codec == null) { part.delete(); return StageResult.Failed(ImportError.CORRUPT) }
        if (!AudioFormatPolicy.isSupportedCodec(codec)) {
            Timber.w("Unsupported codec %s in %s", codec, displayName)
            part.delete()
            return StageResult.Failed(ImportError.UNSUPPORTED)
        }
        val duration = runCatching {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(part.path)
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } finally {
                r.release()
            }
        }.getOrDefault(0L)
        return StageResult.Ok(StagedFile(part, displayName, codec, part.length(), duration))
    }

    private fun isNoSpace(e: IOException) = e.message?.contains("ENOSPC") == true || e.message?.contains("No space", ignoreCase = true) == true

    // ───────────────────────────── editing ─────────────────────────────

    /** Phrases of [draft] (title included) that another entry already uses. */
    suspend fun conflicts(draft: LibraryDraft): List<PhraseConflict> = withContext(Dispatchers.IO) {
        val mine = (listOf(draft.title) + draft.phrases).filter { PhraseNormalizer.normalize(it).isNotEmpty() }
        val others = dao.all().map { it.toDomain() }.filter { it.id != draft.id }
        mine.flatMap { phrase ->
            val key = PhraseNormalizer.normalize(phrase)
            others.filter { key in LibrarySearch.keysOf(it) }.map { PhraseConflict(phrase.trim(), it.title) }
        }.distinct()
    }

    suspend fun save(draft: LibraryDraft): SaveResult = withContext(Dispatchers.IO) {
        val title = draft.title.trim()
        if (title.isEmpty()) return@withContext SaveResult.Failed(tr("Enter a title.", "Введите название."))
        val phrases = cleanPhrases(draft.phrases)
        val existing = draft.id?.let { dao.get(it)?.item }
        if (draft.id != null && existing == null) return@withContext SaveResult.Failed(tr("The entry no longer exists.", "Запись больше не существует."))
        if (existing == null && draft.staged == null) return@withContext SaveResult.Failed(tr("Choose an audio file.", "Выберите аудиофайл."))

        val id = existing?.id ?: UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        var newFile: File? = null
        try {
            var entity = existing?.copy(title = title, updatedAtMs = now) ?: LibraryItemEntity(
                id = id, title = title, fileName = "", originalName = "", mimeType = "", sizeBytes = 0, durationMs = 0,
                createdAtMs = now, updatedAtMs = now,
            )
            draft.staged?.let { staged ->
                if (!staged.file.isFile) throw IOException("Staged file is gone")
                dir.mkdirs()
                val target = File(dir, "${id}_${UUID.randomUUID().toString().take(8)}.${AudioFormatPolicy.extensionFor(staged.codecMime)}")
                if (!staged.file.renameTo(target)) {
                    staged.file.copyTo(target, overwrite = false)
                    staged.file.delete()
                }
                newFile = target
                entity = entity.copy(
                    fileName = target.name, originalName = staged.displayName, mimeType = staged.codecMime,
                    sizeBytes = target.length(), durationMs = staged.durationMs,
                )
            }
            if (failNextCommit) { failNextCommit = false; throw IOException("Simulated commit failure") }
            val rows = phrases.mapIndexed { i, p -> LibraryPhraseEntity(itemId = id, phrase = p, normalized = PhraseNormalizer.normalize(p), position = i) }
            dao.save(entity, rows, isNew = existing == null)
            // Only now is the previous copy unused.
            if (existing != null && newFile != null && existing.fileName != entity.fileName) File(dir, existing.fileName).delete()
            SaveResult.Saved(dao.get(id)!!.toDomain())
        } catch (e: Exception) {
            Timber.e(e, "Saving library entry failed")
            newFile?.delete()
            draft.staged?.file?.delete()
            SaveResult.Failed(
                if (e is IOException && isNoSpace(e)) ImportError.NO_SPACE.message()
                else tr("Saving failed; the previous version is kept.", "Не удалось сохранить; прежняя версия сохранена."),
            )
        }
    }

    /** Deletes the entry, its phrases and the private copy. The user's original file is not touched. */
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val item = dao.get(id)?.item ?: return@withContext
        dao.delete(id)
        File(dir, item.fileName).delete()
    }

    /** Removes leftovers of interrupted imports: staging files and copies no entry points to. */
    suspend fun cleanUp() = withContext(Dispatchers.IO) {
        stagingDir.listFiles()?.forEach { it.delete() }
        val used = dao.fileNames().toSet()
        dir.listFiles()?.filter { it.isFile && it.name !in used }?.forEach {
            Timber.i("Removing orphaned library file %s", it.name)
            it.delete()
        }
    }

    private fun cleanPhrases(phrases: List<String>): List<String> =
        phrases.map { it.trim() }.filter { PhraseNormalizer.normalize(it).isNotEmpty() }.distinctBy { PhraseNormalizer.normalize(it) }

    private fun LibraryItemWithPhrases.toDomain() = LibraryItem(
        id = item.id, title = item.title, phrases = phrases.sortedBy { it.position }.map { it.phrase },
        fileName = item.fileName, originalName = item.originalName, mimeType = item.mimeType,
        sizeBytes = item.sizeBytes, durationMs = item.durationMs, createdAtMs = item.createdAtMs, updatedAtMs = item.updatedAtMs,
    )
}
