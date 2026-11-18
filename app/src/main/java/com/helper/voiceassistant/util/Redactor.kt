package com.helper.voiceassistant.util

/** Removes anything that looks like a secret before text reaches logs or the UI. */
object Redactor {
    private val patterns = listOf(
        Regex("""sk-[A-Za-z0-9_\-]{8,}"""),
        Regex("""(?i)(bearer\s+)[A-Za-z0-9._\-]+"""),
        Regex("""(?i)(api[_-]?key["'=:\s]+)[A-Za-z0-9._\-]{8,}"""),
    )

    fun redact(text: String): String {
        var out = text
        patterns.forEachIndexed { i, p ->
            out = if (i == 0) p.replace(out, "sk-***") else p.replace(out) { it.groupValues[1] + "***" }
        }
        return out
    }
}

/** Prepares LLM output for speech: drops markdown syntax that TTS would read aloud. */
object SpeechText {
    fun clean(text: String): String = text
        .replace(Regex("""```[\s\S]*?```"""), " ")
        .replace(Regex("""`([^`]*)`"""), "$1")
        .replace(Regex("""(?m)^\s{0,3}#{1,6}\s*"""), "")
        .replace(Regex("""\*\*|__|\*|~~"""), "")
        .replace(Regex("""(?m)^\s*[-•]\s+"""), "")
        .replace(Regex("""\[([^\]]+)]\([^)]+\)"""), "$1")
        .replace(Regex("""[ \t]+"""), " ")
        .trim()

    /** Splits at sentence boundaries into chunks no longer than [max] characters. */
    fun chunk(text: String, max: Int): List<String> {
        if (text.length <= max) return listOf(text)
        val out = mutableListOf<String>()
        val sentences = text.split(Regex("""(?<=[.!?…])\s+"""))
        val sb = StringBuilder()
        for (s in sentences) {
            if (sb.length + s.length + 1 > max && sb.isNotEmpty()) { out += sb.toString(); sb.clear() }
            if (s.length > max) {
                s.chunked(max).forEach { out += it }
            } else {
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(s)
            }
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }
}
