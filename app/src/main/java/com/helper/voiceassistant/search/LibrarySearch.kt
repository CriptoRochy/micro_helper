package com.helper.voiceassistant.search

import com.helper.voiceassistant.domain.model.LibraryItem
import java.util.Locale

/**
 * The single normalization used everywhere a phrase is compared: the spoken query, titles, key
 * phrases and the duplicate check in the library settings.
 *
 * Lower case, "ё" = "е", every run of punctuation / symbols / whitespace becomes one space.
 * "Пары на сегодня!" , "пары  на сегодня" and "ПАРЫ-НА-СЕГОДНЯ" all become "пары на сегодня".
 */
object PhraseNormalizer {
    private val RU = Locale.forLanguageTag("ru")
    private val separators = Regex("[^\\p{L}\\p{N}]+")

    fun normalize(text: String): String =
        text.lowercase(RU)
            .replace('ё', 'е')
            .replace(separators, " ")
            .trim()
}

sealed interface SearchOutcome {
    /** The library has no entries at all. */
    data object LibraryEmpty : SearchOutcome
    data object NotFound : SearchOutcome
    data class Single(val item: LibraryItem) : SearchOutcome
    /** Several different entries match; [items] keeps a stable order for the numbered choice. */
    data class Multiple(val items: List<LibraryItem>) : SearchOutcome
}

sealed interface ChoiceOutcome {
    data class Chosen(val item: LibraryItem) : ChoiceOutcome
    /** The answer does not pick exactly one option: ask again. */
    data object Ambiguous : ChoiceOutcome
    /** "Отмена", "стоп"… : leave the choice without playing anything. */
    data object Cancelled : ChoiceOutcome
}

/** Exact match after normalization (first version: no fuzzy matching). */
object LibrarySearch {

    /** Every normalized phrase that calls [item]: its title and its key phrases. */
    fun keysOf(item: LibraryItem): Set<String> =
        (listOf(item.title) + item.phrases).map(PhraseNormalizer::normalize).filter { it.isNotEmpty() }.toSet()

    fun search(query: String, items: List<LibraryItem>): SearchOutcome {
        if (items.isEmpty()) return SearchOutcome.LibraryEmpty
        val q = PhraseNormalizer.normalize(query)
        if (q.isEmpty()) return SearchOutcome.NotFound
        // An entry matching through several of its phrases is still one result.
        val hits = items.filter { q in keysOf(it) }.distinctBy { it.id }.sortedWith(STABLE_ORDER)
        return when (hits.size) {
            0 -> SearchOutcome.NotFound
            1 -> SearchOutcome.Single(hits.single())
            else -> SearchOutcome.Multiple(hits)
        }
    }

    /**
     * Interprets the answer to "Первый — …, второй — …": a number ("второй", "два", "2", "номер два")
     * or a name that matches exactly one of the offered [options]. Equal titles can still be picked
     * by number.
     */
    fun resolveChoice(answer: String, options: List<LibraryItem>): ChoiceOutcome {
        val normalized = PhraseNormalizer.normalize(answer)
        if (normalized.isEmpty()) return ChoiceOutcome.Ambiguous
        if (normalized in CANCEL_WORDS) return ChoiceOutcome.Cancelled

        val words = normalized.split(' ').filter { it !in FILLER_WORDS }
        val numbers = words.mapNotNull { ORDINALS[it] }.distinct()
        if (words.isNotEmpty() && numbers.size == 1 && words.all { it in ORDINALS }) {
            val index = numbers.single() - 1
            return options.getOrNull(index)?.let { ChoiceOutcome.Chosen(it) } ?: ChoiceOutcome.Ambiguous
        }

        val byName = options.filter { normalized in keysOf(it) || words.joinToString(" ") in keysOf(it) }
        if (byName.size == 1) return ChoiceOutcome.Chosen(byName.single())
        if (byName.size > 1 || words.isEmpty()) return ChoiceOutcome.Ambiguous
        // Among the announced options a distinctive part of a name is enough ("утро" for "Зарядка утро").
        val byPart = options.filter { o -> keysOf(o).any { key -> key.split(' ').containsAll(words) } }
        return if (byPart.size == 1) ChoiceOutcome.Chosen(byPart.single()) else ChoiceOutcome.Ambiguous
    }

    /** "Нашлось 2 записи. Первый — расписание занятий. Второй — расписание экзаменов. Скажите номер." */
    fun choicePrompt(options: List<LibraryItem>, repeat: Boolean): String = buildString {
        if (repeat) append("Не понял выбор. ")
        append("Нашлось ").append(options.size).append(' ').append(recordsWord(options.size)).append(". ")
        options.forEachIndexed { i, item -> append(ordinalName(i + 1)).append(" — ").append(item.title.trim()).append(". ") }
        append("Скажите номер.")
    }

    fun ordinalName(n: Int): String = SPOKEN_ORDINALS.getOrNull(n - 1) ?: "Номер $n"

    private fun recordsWord(n: Int): String {
        val mod100 = n % 100
        val mod10 = n % 10
        return when {
            mod100 in 11..14 -> "записей"
            mod10 == 1 -> "запись"
            mod10 in 2..4 -> "записи"
            else -> "записей"
        }
    }

    /** Oldest first, so the numbering stays the same between searches. */
    private val STABLE_ORDER = compareBy<LibraryItem>({ it.createdAtMs }, { it.id })

    private val SPOKEN_ORDINALS = listOf("Первый", "Второй", "Третий", "Четвёртый", "Пятый", "Шестой", "Седьмой", "Восьмой", "Девятый", "Десятый")

    private val ORDINALS: Map<String, Int> = buildMap {
        fun put(n: Int, vararg forms: String) = forms.forEach { put(it, n) }
        put(1, "1", "первый", "первая", "первое", "первую", "первого", "первой", "один", "одна", "одну", "раз")
        put(2, "2", "второй", "вторая", "второе", "вторую", "второго", "два", "две")
        put(3, "3", "третий", "третья", "третье", "третью", "третьего", "три")
        put(4, "4", "четвертый", "четвертая", "четвертое", "четвертую", "четвертого", "четыре")
        put(5, "5", "пятый", "пятая", "пятое", "пятую", "пятого", "пять")
        put(6, "6", "шестой", "шестая", "шестое", "шестую", "шестого", "шесть")
        put(7, "7", "седьмой", "седьмая", "седьмое", "седьмую", "седьмого", "семь")
        put(8, "8", "восьмой", "восьмая", "восьмое", "восьмую", "восьмого", "восемь")
        put(9, "9", "девятый", "девятая", "девятое", "девятую", "девятого", "девять")
        put(10, "10", "десятый", "десятая", "десятое", "десятую", "десятого", "десять")
    }

    private val FILLER_WORDS = setOf(
        "номер", "вариант", "запись", "давай", "пожалуйста", "мне", "включи", "включить", "выбираю", "выбери",
        "хочу", "вот", "это", "ну", "так", "да",
    )

    private val CANCEL_WORDS = setOf("отмена", "отмени", "отменить", "стоп", "не надо", "ничего", "выход", "хватит")
}
