package com.helper.voiceassistant.search

import com.helper.voiceassistant.domain.model.LibraryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySearchTest {

    private fun item(id: String, title: String, vararg phrases: String, created: Long = id.hashCode().toLong()) = LibraryItem(
        id = id, title = title, phrases = phrases.toList(), fileName = "$id.mp3", originalName = "$title.mp3",
        mimeType = "audio/mpeg", sizeBytes = 1, durationMs = 1, createdAtMs = created, updatedAtMs = created,
    )

    private val schedule = item("a", "Расписание занятий", "что у меня сегодня", "пары на сегодня", created = 1)
    private val exams = item("b", "Расписание экзаменов", "сессия", created = 2)

    // ── normalization ──

    @Test
    fun `normalization ignores case, yo, punctuation and extra spaces`() {
        assertEquals("пары на сегодня", PhraseNormalizer.normalize("  Пары   на сегодня!  "))
        assertEquals("пары на сегодня", PhraseNormalizer.normalize("ПАРЫ-НА-СЕГОДНЯ?"))
        assertEquals("еще ежик", PhraseNormalizer.normalize("Ещё, ёжик…"))
        assertEquals("", PhraseNormalizer.normalize(" ... "))
    }

    // ── search ──

    @Test
    fun `key phrase finds its entry`() {
        assertEquals(SearchOutcome.Single(schedule), LibrarySearch.search("Пары на сегодня.", listOf(schedule, exams)))
        assertEquals(SearchOutcome.Single(exams), LibrarySearch.search("Сессия", listOf(schedule, exams)))
    }

    @Test
    fun `title alone finds the entry`() {
        val noPhrases = item("c", "Гимн школы", created = 3)
        assertEquals(SearchOutcome.Single(noPhrases), LibrarySearch.search("гимн школы", listOf(schedule, noPhrases)))
    }

    @Test
    fun `entry matching through several phrases is one result`() {
        val twice = item("d", "Сегодня", "сегодня", created = 4)
        assertEquals(SearchOutcome.Single(twice), LibrarySearch.search("Сегодня", listOf(twice)))
    }

    @Test
    fun `phrase shared by two entries gives both in stable order`() {
        val a = item("x", "Расписание занятий", "расписание", created = 10)
        val b = item("y", "Расписание экзаменов", "расписание", created = 20)
        assertEquals(SearchOutcome.Multiple(listOf(a, b)), LibrarySearch.search("Расписание", listOf(b, a)))
    }

    @Test
    fun `unknown phrase, empty query and empty library`() {
        assertEquals(SearchOutcome.NotFound, LibrarySearch.search("погода", listOf(schedule)))
        assertEquals(SearchOutcome.NotFound, LibrarySearch.search("...", listOf(schedule)))
        assertEquals(SearchOutcome.LibraryEmpty, LibrarySearch.search("пары на сегодня", emptyList()))
    }

    @Test
    fun `partial phrase is not a match in the first version`() {
        assertEquals(SearchOutcome.NotFound, LibrarySearch.search("пары", listOf(schedule)))
    }

    // ── choice ──

    private val options = listOf(schedule, exams)

    @Test
    fun `numbers pick the option in announced order`() {
        for (answer in listOf("Второй", "второй.", "два", "2", "номер два", "Вторую")) {
            assertEquals(answer, ChoiceOutcome.Chosen(exams), LibrarySearch.resolveChoice(answer, options))
        }
        assertEquals(ChoiceOutcome.Chosen(schedule), LibrarySearch.resolveChoice("Первый", options))
    }

    @Test
    fun `unambiguous name picks the option`() {
        assertEquals(ChoiceOutcome.Chosen(exams), LibrarySearch.resolveChoice("Расписание экзаменов", options))
        assertEquals("a distinctive word is enough", ChoiceOutcome.Chosen(exams), LibrarySearch.resolveChoice("экзаменов", options))
        assertEquals("a shared word is not", ChoiceOutcome.Ambiguous, LibrarySearch.resolveChoice("расписание", options))
    }

    @Test
    fun `out of range or unclear answer asks again`() {
        assertEquals(ChoiceOutcome.Ambiguous, LibrarySearch.resolveChoice("третий", options))
        assertEquals(ChoiceOutcome.Ambiguous, LibrarySearch.resolveChoice("какой-нибудь", options))
        assertEquals(ChoiceOutcome.Ambiguous, LibrarySearch.resolveChoice("первый или второй", options))
        assertEquals(ChoiceOutcome.Ambiguous, LibrarySearch.resolveChoice("", options))
    }

    @Test
    fun `equal titles are chosen by number only`() {
        val a = item("p", "Лекция", created = 1)
        val b = item("q", "Лекция", created = 2)
        assertEquals(ChoiceOutcome.Ambiguous, LibrarySearch.resolveChoice("Лекция", listOf(a, b)))
        assertEquals(ChoiceOutcome.Chosen(b), LibrarySearch.resolveChoice("второй", listOf(a, b)))
    }

    @Test
    fun `cancel words leave the choice`() {
        assertEquals(ChoiceOutcome.Cancelled, LibrarySearch.resolveChoice("Отмена!", options))
    }

    @Test
    fun `prompt lists numbered options`() {
        val prompt = LibrarySearch.choicePrompt(options, repeat = false)
        assertEquals("Нашлось 2 записи. Первый — Расписание занятий. Второй — Расписание экзаменов. Скажите номер.", prompt)
        assertTrue(LibrarySearch.choicePrompt(options, repeat = true).startsWith("Не понял выбор."))
    }
}
