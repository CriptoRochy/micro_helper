package com.helper.voiceassistant

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.helper.voiceassistant.data.library.AudioLibraryRepository
import com.helper.voiceassistant.data.library.ImportError
import com.helper.voiceassistant.data.library.LibraryDraft
import com.helper.voiceassistant.data.library.PhraseConflict
import com.helper.voiceassistant.data.library.SaveResult
import com.helper.voiceassistant.data.library.StageResult
import com.helper.voiceassistant.data.library.StagedFile
import com.helper.voiceassistant.data.local.AppDatabase
import com.helper.voiceassistant.domain.model.LibraryItem
import com.helper.voiceassistant.search.LibrarySearch
import com.helper.voiceassistant.search.SearchOutcome
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.PI
import kotlin.math.sin

/** Instrumented: real Room (in-memory), real MediaExtractor, files in a temporary directory. */
@RunWith(AndroidJUnit4::class)
class AudioLibraryRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: AppDatabase
    private lateinit var dir: File
    private lateinit var sources: File
    private lateinit var repo: AudioLibraryRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val root = File(context.cacheDir, "libtest-${UUID.randomUUID()}")
        dir = File(root, "library").apply { mkdirs() }
        sources = File(root, "sources").apply { mkdirs() }
        repo = AudioLibraryRepository(context, db.libraryDao(), dir)
    }

    @After
    fun tearDown() {
        db.close()
        dir.parentFile?.deleteRecursively()
    }

    // ───────────── fixtures ─────────────

    private fun wav(name: String, ms: Int, sampleRate: Int = 16_000): File {
        val n = sampleRate * ms / 1000
        val data = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) data.putShort((sin(2 * PI * 440 * i / sampleRate) * 8000).toInt().toShort())
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + n * 2).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(n * 2)
        return File(sources, name).apply { outputStream().use { it.write(h.array()); it.write(data.array()) } }
    }

    /** AAC in an MP4 container, encoded on the device (there is no MP3 encoder on Android). */
    private fun m4a(name: String, ms: Int): File {
        val file = File(sources, name)
        val rate = 44_100
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val total = rate * ms / 1000
        var fed = 0
        var track = -1
        var inputDone = false
        var outputDone = false
        val info = MediaCodec.BufferInfo()
        while (!outputDone) {
            if (!inputDone) {
                val i = codec.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    val buf = codec.getInputBuffer(i)!!.order(ByteOrder.nativeOrder())
                    buf.clear()
                    val count = minOf(buf.capacity() / 2, total - fed, 1024)
                    if (count <= 0) {
                        codec.queueInputBuffer(i, 0, 0, fed * 1_000_000L / rate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        for (k in 0 until count) buf.putShort((sin(2 * PI * 440 * (fed + k) / rate) * 8000).toInt().toShort())
                        codec.queueInputBuffer(i, 0, count * 2, fed * 1_000_000L / rate, 0)
                        fed += count
                    }
                }
            }
            val o = codec.dequeueOutputBuffer(info, 10_000)
            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                track = muxer.addTrack(codec.outputFormat)
                muxer.start()
            } else if (o >= 0) {
                val out = codec.getOutputBuffer(o)!!
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                if (info.size > 0 && track >= 0) {
                    out.position(info.offset)
                    out.limit(info.offset + info.size)
                    muxer.writeSampleData(track, out, info)
                }
                codec.releaseOutputBuffer(o, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
            }
        }
        codec.stop(); codec.release()
        muxer.stop(); muxer.release()
        return file
    }

    private suspend fun staged(file: File): StagedFile = when (val r = repo.stageFile(file)) {
        is StageResult.Ok -> r.staged
        is StageResult.Failed -> { fail("staging ${file.name} failed: ${r.error}"); throw IllegalStateException() }
    }

    private suspend fun saved(draft: LibraryDraft): LibraryItem = when (val r = repo.save(draft)) {
        is SaveResult.Saved -> r.item
        is SaveResult.Failed -> { fail("save failed: ${r.message}"); throw IllegalStateException() }
    }

    private fun storedFiles() = dir.listFiles()!!.filter { it.isFile }.map { it.name }.toSet()

    // ───────────── tests ─────────────

    @Test
    fun addedEntryGetsPermanentIdAndIsFoundByPhrase() = runTest {
        val source = wav("Расписание.wav", 1000)
        val s = staged(source)
        assertEquals("audio/raw", s.codecMime)
        assertTrue("duration read: ${s.durationMs}", s.durationMs in 900..1100)

        val item = saved(LibraryDraft(null, "Расписание", listOf("расписание", "включи расписание", "  ", "Расписание!"), s))
        UUID.fromString(item.id) // a real UUID
        assertEquals("blank and duplicate phrases dropped", listOf("расписание", "включи расписание"), item.phrases)
        assertTrue(repo.fileFor(item).isFile)
        assertTrue("the copy ends in .wav", item.fileName.endsWith(".wav"))
        assertFalse("staging file moved into place", s.file.exists())
        assertTrue("the user's original is untouched", source.isFile)

        val found = LibrarySearch.search("Включи, расписание!", repo.items())
        assertEquals(item.id, (found as SearchOutcome.Single).item.id)
    }

    @Test
    fun m4aIsAccepted() = runTest {
        val s = staged(m4a("lecture.m4a", 1500))
        assertEquals("audio/mp4a-latm", s.codecMime)
        val item = saved(LibraryDraft(null, "Лекция", emptyList(), s))
        assertTrue(item.fileName.endsWith(".m4a"))
        assertTrue(item.durationMs in 1300..1700)
    }

    @Test
    fun renameAndPhrasesKeepIdAndFile() = runTest {
        val item = saved(LibraryDraft(null, "Зарядка", listOf("зарядка"), staged(wav("a.wav", 500))))
        val edited = saved(LibraryDraft(item.id, "Утренняя зарядка", listOf("утро", "разминка"), null))
        assertEquals(item.id, edited.id)
        assertEquals(item.fileName, edited.fileName)
        assertEquals(listOf("утро", "разминка"), edited.phrases)
        assertEquals(SearchOutcome.NotFound, LibrarySearch.search("зарядка", repo.items()))
        assertEquals(item.id, (LibrarySearch.search("разминка", repo.items()) as SearchOutcome.Single).item.id)
    }

    @Test
    fun replacedFileIsBoundToTheSameId() = runTest {
        val item = saved(LibraryDraft(null, "Сказка", listOf("сказка"), staged(wav("v1.wav", 500))))
        val oldFile = repo.fileFor(item)
        val replaced = saved(LibraryDraft(item.id, item.title, item.phrases, staged(wav("v2.wav", 2000))))
        assertEquals(item.id, replaced.id)
        assertNotEquals(item.fileName, replaced.fileName)
        assertTrue(replaced.durationMs in 1800..2200)
        assertTrue(repo.fileFor(replaced).isFile)
        assertFalse("the previous copy is removed after the switch", oldFile.exists())
        assertEquals(setOf(replaced.fileName), storedFiles())
    }

    @Test
    fun failedReplacementKeepsThePreviousFile() = runTest {
        val item = saved(LibraryDraft(null, "Сказка", listOf("сказка"), staged(wav("v1.wav", 500))))
        val oldFile = repo.fileFor(item)
        val oldLength = oldFile.length()
        val newStaged = staged(wav("v2.wav", 2000))

        repo.failNextCommit = true
        val result = repo.save(LibraryDraft(item.id, "Другое название", listOf("другое"), newStaged))
        assertTrue(result is SaveResult.Failed)

        val stored = repo.get(item.id)!!
        assertEquals("entry still points at its old file", item.fileName, stored.fileName)
        assertEquals("nothing else changed", "Сказка", stored.title)
        assertEquals(listOf("сказка"), stored.phrases)
        assertTrue(oldFile.isFile)
        assertEquals(oldLength, oldFile.length())
        assertEquals("no half-written copy left", setOf(item.fileName), storedFiles())
        assertFalse(newStaged.file.exists())
    }

    @Test
    fun corruptOversizedAndNonAudioFilesAreRejectedWithAReason() = runTest {
        val text = File(sources, "notes.mp3").apply { writeText("это не аудио") }
        assertEquals(StageResult.Failed(ImportError.CORRUPT), repo.stageFile(text))

        val huge = File(sources, "huge.wav")
        RandomAccessFile(huge, "rw").use { it.setLength(51L * 1024 * 1024) }
        assertEquals(StageResult.Failed(ImportError.TOO_LARGE), repo.stageFile(huge))

        assertTrue(repo.items().isEmpty())
        assertTrue("rejected files leave nothing behind", File(dir, "staging").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun deleteRemovesEntryAndCopyButNotTheOriginal() = runTest {
        val source = wav("keep-me.wav", 500)
        val item = saved(LibraryDraft(null, "Временная", listOf("временная"), staged(source)))
        repo.delete(item.id)
        assertTrue(repo.items().isEmpty())
        assertFalse(repo.fileFor(item).exists())
        assertTrue(source.isFile)
        assertEquals(SearchOutcome.LibraryEmpty, LibrarySearch.search("временная", repo.items()))
    }

    @Test
    fun duplicatePhrasesAreReportedAndBothCanBeKept() = runTest {
        saved(LibraryDraft(null, "Зарядка утро", listOf("зарядка"), staged(wav("a.wav", 300))))
        val draft = LibraryDraft(null, "Зарядка вечер", listOf("Зарядка"), staged(wav("b.wav", 300)))
        assertEquals(listOf(PhraseConflict("Зарядка", "Зарядка утро")), repo.conflicts(draft))
        saved(draft) // «Оставить обе»
        val outcome = LibrarySearch.search("зарядка", repo.items()) as SearchOutcome.Multiple
        assertEquals(listOf("Зарядка утро", "Зарядка вечер"), outcome.items.map { it.title })
    }

    @Test
    fun cleanUpRemovesInterruptedImportsOnly() = runTest {
        val item = saved(LibraryDraft(null, "Нужная", emptyList(), staged(wav("a.wav", 300))))
        File(dir, "orphan.wav").writeText("x")
        File(File(dir, "staging").apply { mkdirs() }, "left.part").writeText("x")
        repo.cleanUp()
        assertEquals(setOf(item.fileName), storedFiles())
        assertTrue(File(dir, "staging").listFiles().orEmpty().isEmpty())
    }
}
