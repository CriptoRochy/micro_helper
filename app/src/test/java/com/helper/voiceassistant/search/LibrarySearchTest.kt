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
    fun `one word of a longer phrase is not a match`() {
        assertEquals(SearchOutcome.NotFound, LibrarySearch.search("пары", listOf(schedule)))
    }

    // ── similarity (Levenshtein) ──

    private val lessons = item("l", "Расписание занятий", "расписание", "включи расписание", created = 4)
    private val warmupMorning = item("m", "Зарядка утро", "зарядка", created = 5)
    private val warmupEvening = item("e", "Зарядка вечер", "зарядка", created = 6)
    private val library = listOf(lessons, warmupMorning, warmupEvening)

    @Test
    fun `levenshtein distance`() {
        assertEquals(0, Fuzzy.levenshtein("утро", "утро"))
        assertEquals(1, Fuzzy.levenshtein("утро", "утра"))
        assertEquals(3, Fuzzy.levenshtein("", "абв"))
        assertEquals(3, Fuzzy.levenshtein("kitten", "sitting"))
        assertEquals(11.0 / 12, Fuzzy.similarity("зарядка утра", "зарядка утро"), 1e-9)
    }

    @Test
    fun `a slightly misheard phrase finds the most similar entry`() {
        assertEquals("the case from the phone", SearchOutcome.Single(warmupMorning), LibrarySearch.search("Зарядка утра.", library))
        assertEquals(SearchOutcome.Single(warmupEvening), LibrarySearch.search("зарядка вечером", library))
        assertEquals(SearchOutcome.Single(lessons), LibrarySearch.search("распесание", library))
    }

    @Test
    fun `other word forms, word order and command words`() {
        assertEquals(SearchOutcome.Single(warmupMorning), LibrarySearch.search("Включи зарядку утро", library))
        assertEquals(SearchOutcome.Single(warmupMorning), LibrarySearch.search("утро зарядка", library))
    }

    @Test
    fun `an exact match still wins over similar entries`() {
        assertEquals(SearchOutcome.Multiple(listOf(warmupMorning, warmupEvening)), LibrarySearch.search("зарядка", library))
    }

    @Test
    fun `equally similar entries are offered as a choice`() {
        val one = item("o", "Песня один", created = 1)
        val two = item("t", "Песня одна", created = 2)
        assertEquals(SearchOutcome.Multiple(listOf(one, two)), LibrarySearch.search("песня одн", listOf(two, one)))
    }

    @Test
    fun `unrelated phrases are still not found`() {
        for (q in listOf("какая сегодня погода", "поставь музыку", "да", "сессия завтра")) {
            assertEquals(q, SearchOutcome.NotFound, LibrarySearch.search(q, library))
        }
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
    fun `a misheard name picks the most similar option`() {
        assertEquals(ChoiceOutcome.Chosen(warmupMorning), LibrarySearch.resolveChoice("зарядка утра", listOf(warmupMorning, warmupEvening)))
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
