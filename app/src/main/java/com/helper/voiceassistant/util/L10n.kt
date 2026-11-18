package com.helper.voiceassistant.util

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.helper.voiceassistant.domain.model.UiLanguage
import java.util.Locale

/**
 * App interface language. Backed by Compose snapshot state, so every composable that calls [tr]
 * recomposes as soon as the language is changed in Settings. Non-UI code (errors, notification)
 * reads the same value at the moment the text is built.
 */
object AppLanguage {
    var setting: UiLanguage by mutableStateOf(UiLanguage.SYSTEM)

    val isRussian: Boolean
        get() = when (setting) {
            UiLanguage.RUSSIAN -> true
            UiLanguage.ENGLISH -> false
            UiLanguage.SYSTEM -> Locale.getDefault().language == "ru"
        }
}

/** Returns the English or Russian text depending on the selected interface language. */
fun tr(en: String, ru: String): String = if (AppLanguage.isRussian) ru else en
