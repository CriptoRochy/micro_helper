package com.helper.voiceassistant.util

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** In-memory technical log shown on the Logs screen. Every line is redacted before it is stored. */
object LogBuffer {
    private const val MAX_LINES = 600
    private val format = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    fun clear() { _lines.value = emptyList() }

    @Synchronized
    private fun add(line: String) {
        val current = _lines.value
        _lines.value = (if (current.size >= MAX_LINES) current.drop(current.size - MAX_LINES + 1) else current) + line
    }

    class Tree : Timber.DebugTree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            val safe = Redactor.redact(message)
            val level = when (priority) {
                Log.ERROR -> "E"; Log.WARN -> "W"; Log.INFO -> "I"; Log.DEBUG -> "D"; else -> "V"
            }
            val error = t?.let { " | ${it.javaClass.simpleName}: ${Redactor.redact(it.message ?: "")}" } ?: ""
            add("${format.format(Date())} $level/${tag ?: "-"}: $safe$error")
            super.log(priority, tag, safe, t)
        }
    }
}
