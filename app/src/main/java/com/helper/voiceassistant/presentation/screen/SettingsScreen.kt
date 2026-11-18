package com.helper.voiceassistant.presentation.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.helper.voiceassistant.domain.model.AssistantSettings
import com.helper.voiceassistant.domain.model.MicSource
import androidx.compose.material3.LinearProgressIndicator
import com.helper.voiceassistant.data.stt.GigaAmV3
import com.helper.voiceassistant.data.stt.SttModelStatus
import com.helper.voiceassistant.domain.model.TtsAudioUsage
import com.helper.voiceassistant.domain.model.UiLanguage
import com.helper.voiceassistant.domain.repository.SecretKey
import com.helper.voiceassistant.presentation.components.DropdownRow
import com.helper.voiceassistant.presentation.components.SectionHeader
import com.helper.voiceassistant.presentation.components.SliderRow
import com.helper.voiceassistant.presentation.components.SwitchRow
import com.helper.voiceassistant.presentation.components.TextRow
import com.helper.voiceassistant.presentation.viewmodel.SettingsViewModel
import kotlin.math.roundToInt
import com.helper.voiceassistant.util.tr

private val TTS_LANGUAGES: List<Pair<String, String>> get() = listOf("ru-RU" to tr("Russian", "Русский"), "en-US" to tr("English (US)", "Английский (США)"), "en-GB" to tr("English (UK)", "Английский (Великобритания)"))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit, onOpenLogs: () -> Unit) {
    val s by viewModel.settings.collectAsStateWithLifecycle()
    val keys by viewModel.keyState.collectAsStateWithLifecycle()
    val route by viewModel.route.collectAsStateWithLifecycle()
    val voices by viewModel.voices.collectAsStateWithLifecycle()
    val ttsWarning by viewModel.ttsWarning.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val modelStatus by viewModel.sttModelStatus.collectAsStateWithLifecycle()
    val learning by viewModel.learningButton.collectAsStateWithLifecycle()
    val lastKey by viewModel.lastKey.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var formVersion by remember { mutableIntStateOf(0) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); viewModel.consumeMessage() }
    }

    fun set(transform: (AssistantSettings) -> AssistantSettings) = viewModel.update(transform)

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(tr("Delete all local data?", "Удалить все локальные данные?")) },
            text = { Text(tr("Conversation history, kept recordings, stored API keys and logs will be permanently deleted.", "История диалогов, сохранённые записи, API-ключи и логи будут удалены без возможности восстановления.")) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; viewModel.deleteAllLocalData() }) { Text(tr("Delete", "Удалить")) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(tr("Cancel", "Отмена")) } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tr("Settings", "Настройки")) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Back", "Назад")) } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        key(formVersion) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                // ───────── Interface
                SectionHeader(tr("Interface", "Интерфейс"))
                DropdownRow(tr("Interface language", "Язык интерфейса"), UiLanguage.entries, s.uiLanguage, {
                    when (it) {
                        UiLanguage.SYSTEM -> tr("System default", "Как в системе")
                        UiLanguage.RUSSIAN -> "Русский"
                        UiLanguage.ENGLISH -> "English"
                    }
                }) { v -> set { it.copy(uiLanguage = v) } }

                // ───────── Beep control
                SectionHeader(tr("Beep control", "Управление сигналами"))
                SwitchRow(tr("Audio-signal control", "Управление звуковым сигналом"), s.beep.enabled, tr("1 beep = speak / send, 2 beeps = repeat", "1 сигнал — говорить / отправить, 2 сигнала — повторить")) { v -> set { it.copy(beep = it.beep.copy(enabled = v)) } }
                SliderRow(tr("Beep frequency", "Частота сигнала"), s.beep.frequencyHz, 500f..4000f, 0, { tr("${it.roundToInt()} Hz", "${it.roundToInt()} Гц") }) { v -> set { it.copy(beep = it.beep.copy(frequencyHz = (v / 10).roundToInt() * 10f)) } }
                SliderRow(tr("Frequency tolerance", "Допуск по частоте"), s.beep.toleranceHz, 10f..200f, 0, { tr("±${it.roundToInt()} Hz", "±${it.roundToInt()} Гц") }) { v -> set { it.copy(beep = it.beep.copy(toleranceHz = v.roundToInt().toFloat())) } }
                SliderRow(tr("Detection threshold (sensitivity)", "Порог обнаружения (чувствительность)"), s.beep.thresholdDbfs, -60f..-10f, 0, { "${it.roundToInt()} dBFS" }) { v -> set { it.copy(beep = it.beep.copy(thresholdDbfs = v.roundToInt().toFloat())) } }
                SliderRow(tr("Tone purity required", "Требуемая чистота тона"), s.beep.tonalityRatio, 0.15f..0.9f, 0, { "${(it * 100).roundToInt()} %" }) { v -> set { it.copy(beep = it.beep.copy(tonalityRatio = v)) } }
                SliderRow(tr("Min beep duration", "Мин. длительность сигнала"), s.beep.minBeepMs.toFloat(), 20f..300f, 0, { tr("${it.roundToInt()} ms", "${it.roundToInt()} мс") }) { v -> set { it.copy(beep = it.beep.copy(minBeepMs = v.roundToInt())) } }
                SliderRow(tr("Max beep duration", "Макс. длительность сигнала"), s.beep.maxBeepMs.toFloat(), 150f..2000f, 0, { tr("${it.roundToInt()} ms", "${it.roundToInt()} мс") }) { v -> set { it.copy(beep = it.beep.copy(maxBeepMs = v.roundToInt())) } }
                SliderRow(tr("Double-beep interval", "Интервал двойного сигнала"), s.beep.doubleBeepIntervalMs.toFloat(), 150f..1200f, 0, { tr("${it.roundToInt()} ms", "${it.roundToInt()} мс") }) { v -> set { it.copy(beep = it.beep.copy(doubleBeepIntervalMs = v.roundToInt())) } }
                SliderRow(tr("Debounce after command", "Пауза после команды"), s.beep.debounceMs.toFloat(), 0f..1500f, 0, { tr("${it.roundToInt()} ms", "${it.roundToInt()} мс") }) { v -> set { it.copy(beep = it.beep.copy(debounceMs = v.roundToInt())) } }

                // ───────── Recording start / end, headset button
                RecordingTriggersSection(s, ::set)
                HeadsetButtonSection(s, ::set, learning, lastKey, viewModel::startButtonLearning, viewModel::stopButtonLearning)

                // ───────── Recording
                SectionHeader(tr("Recording", "Запись"))
                SliderRow(tr("Maximum recording length", "Максимальная длина записи"), s.maxRecordingSec.toFloat(), 10f..600f, 0, { tr("${it.roundToInt()} s", "${it.roundToInt()} с") }) { v -> set { it.copy(maxRecordingSec = (v / 10).roundToInt() * 10) } }
                SliderRow(tr("Minimum recording length", "Минимальная длина записи"), s.minRecordingMs.toFloat(), 200f..3000f, 0, { tr("${it.roundToInt()} ms", "${it.roundToInt()} мс") }) { v -> set { it.copy(minRecordingMs = v.roundToInt()) } }
                SliderRow(tr("Speech level floor", "Минимальный уровень речи"), s.minSpeechRmsDbfs, -70f..-25f, 0, { "${it.roundToInt()} dBFS" }) { v -> set { it.copy(minSpeechRmsDbfs = v.roundToInt().toFloat()) } }
                SwitchRow(tr("Keep recordings after processing", "Сохранять записи после обработки"), s.keepRecordings, tr("Off: WAV files are deleted right after recognition", "Выкл.: WAV-файлы удаляются сразу после распознавания")) { v -> set { it.copy(keepRecordings = v) } }

                // ───────── Audio device
                SectionHeader(tr("Audio device", "Аудиоустройство"))
                val inputs = listOf(-1 to tr("Automatic (headset first)", "Автоматически (сначала гарнитура)")) + route.availableInputs.map { it.id to it.name }
                DropdownRow(tr("Microphone", "Микрофон"), inputs, inputs.firstOrNull { it.first == s.preferredInputDeviceId } ?: inputs.first(), { it.second }) { v -> set { it.copy(preferredInputDeviceId = v.first) } }
                DropdownRow(tr("Microphone processing", "Обработка микрофона"), MicSource.entries, s.micSource, {
                    when (it) {
                        MicSource.VOICE_RECOGNITION -> tr("Voice recognition (recommended)", "Распознавание голоса (рекомендуется)")
                        MicSource.MIC -> tr("Raw microphone", "Микрофон без обработки")
                        MicSource.VOICE_COMMUNICATION -> tr("Voice call (echo/noise suppression)", "Голосовой вызов (подавление эха/шума)")
                    }
                }) { v -> set { it.copy(micSource = v) } }

                // ───────── Speech recognition
                SectionHeader(tr("Local STT model", "Локальная модель распознавания"))
                LocalSttModelSection(
                    status = modelStatus,
                    onDownload = viewModel::downloadSttModel,
                    onCancel = viewModel::cancelSttDownload,
                    onDelete = viewModel::deleteSttModel,
                )

                // ───────── DeepSeek
                SectionHeader("DeepSeek")
                TextRow(tr("API base URL (or your proxy)", "Базовый URL API (или ваш прокси)"), s.deepSeekBaseUrl, keyboardType = KeyboardType.Uri) { v -> set { it.copy(deepSeekBaseUrl = v.trim()) } }
                TextRow(tr("Model", "Модель"), s.deepSeekModel) { v -> set { it.copy(deepSeekModel = v.trim()) } }
                Text(tr("Current official models: deepseek-flash, deepseek-v4-pro", "Актуальные официальные модели: deepseek-flash, deepseek-v4-pro"), style = MaterialTheme.typography.bodySmall)
                SwitchRow(tr("Thinking mode", "Режим размышления"), s.deepSeekThinking, tr("More accurate, but slower answers", "Точнее, но ответы медленнее")) { v -> set { it.copy(deepSeekThinking = v) } }
                ApiKeyField(tr("DeepSeek API key", "API-ключ DeepSeek"), keys.deepSeekSet) { viewModel.saveKey(SecretKey.DEEPSEEK_API_KEY, it) }
                TextRow(tr("System prompt", "Системный промпт"), s.systemPrompt, singleLine = false) { v -> set { it.copy(systemPrompt = v) } }
                SliderRow(tr("Context: previous exchanges", "Контекст: предыдущих обменов"), s.contextExchanges.toFloat(), 0f..20f, 19, { "${it.roundToInt()}" }) { v -> set { it.copy(contextExchanges = v.roundToInt()) } }
                SliderRow(tr("Request timeout", "Тайм-аут запроса"), s.requestTimeoutSec.toFloat(), 10f..180f, 0, { tr("${it.roundToInt()} s", "${it.roundToInt()} с") }) { v -> set { it.copy(requestTimeoutSec = v.roundToInt()) } }
                SliderRow(tr("Retries", "Повторы"), s.maxRetries.toFloat(), 0f..5f, 4, { "${it.roundToInt()}" }) { v -> set { it.copy(maxRetries = v.roundToInt()) } }

                // ───────── Speech output
                SectionHeader(tr("Speech output (TTS)", "Озвучивание (TTS)"))
                DropdownRow(tr("Language", "Язык"), TTS_LANGUAGES, TTS_LANGUAGES.firstOrNull { it.first == s.ttsLanguage } ?: TTS_LANGUAGES.first(), { it.second }) { v ->
                    set { it.copy(ttsLanguage = v.first, ttsVoiceName = "") }
                    viewModel.loadVoices(v.first)
                }
                val voiceOptions = listOf("" to tr("Default voice", "Голос по умолчанию")) + voices.map { it.name to "${it.name}${if (it.networkRequired) tr(" (online)", " (онлайн)") else ""}" }
                DropdownRow(tr("Voice", "Голос"), voiceOptions, voiceOptions.firstOrNull { it.first == s.ttsVoiceName } ?: voiceOptions.first(), { it.second }) { v -> set { it.copy(ttsVoiceName = v.first) } }
                ttsWarning?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                SliderRow(tr("Speech rate", "Скорость речи"), s.ttsRate, 0.5f..2.0f, 0, { "%.2f×".format(it) }) { v -> set { it.copy(ttsRate = v) } }
                SliderRow(tr("Speech volume", "Громкость речи"), s.ttsVolume, 0.1f..1.0f, 0, { "${(it * 100).roundToInt()} %" }) { v -> set { it.copy(ttsVolume = v) } }
                DropdownRow(tr("Playback stream", "Аудиопоток воспроизведения"), TtsAudioUsage.entries, s.ttsAudioUsage, {
                    when (it) {
                        TtsAudioUsage.AUTO -> tr("Automatic (call stream on Bluetooth HFP)", "Автоматически (поток вызова при Bluetooth HFP)")
                        TtsAudioUsage.MEDIA -> tr("Media", "Медиа")
                        TtsAudioUsage.VOICE_COMMUNICATION -> tr("Voice call", "Голосовой вызов")
                        TtsAudioUsage.ASSISTANCE_ACCESSIBILITY -> tr("Accessibility", "Спец. возможности")
                    }
                }) { v -> set { it.copy(ttsAudioUsage = v) } }
                SwitchRow(tr("Spoken notices", "Голосовые подсказки"), s.spokenFeedback, tr("Say short messages like \"nothing to repeat\" in the headset", "Произносить в гарнитуре короткие сообщения, например «нечего повторить»")) { v -> set { it.copy(spokenFeedback = v) } }

                // ───────── History & data
                SectionHeader(tr("History & data", "История и данные"))
                SwitchRow(tr("Save conversation history", "Сохранять историю диалогов"), s.historyEnabled) { v -> set { it.copy(historyEnabled = v) } }
                SliderRow(tr("Stored exchanges", "Хранить обменов"), s.historyLimit.toFloat(), 5f..200f, 0, { "${it.roundToInt()}" }) { v -> set { it.copy(historyLimit = v.roundToInt()) } }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    OutlinedButton(onClick = viewModel::clearHistory, modifier = Modifier.weight(1f)) { Text(tr("Clear history", "Очистить историю")) }
                    OutlinedButton(onClick = onOpenLogs, modifier = Modifier.weight(1f)) { Text(tr("Technical logs", "Технические логи")) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    OutlinedButton(onClick = { viewModel.resetDefaults(); formVersion++ }, modifier = Modifier.weight(1f)) { Text(tr("Reset to defaults", "Сбросить настройки")) }
                    OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.weight(1f)) { Text(tr("Delete local data", "Удалить локальные данные")) }
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun ApiKeyField(title: String, isSet: Boolean, onSave: (String) -> Unit) {
    var value by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            label = { Text(if (isSet) tr("$title (stored — enter to replace)", "$title (сохранён — введите новый для замены)") else title) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onSave(value); value = "" }, enabled = value.isNotBlank()) { Text(tr("Save key", "Сохранить ключ")) }
            if (isSet) OutlinedButton(onClick = { onSave("") }) { Text(tr("Remove", "Удалить")) }
        }
        Text(
            tr("Stored encrypted with the Android Keystore. Never logged.", "Хранится в зашифрованном виде (Android Keystore). Никогда не пишется в логи."),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun LocalSttModelSection(
    status: SttModelStatus,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val sizeMb = GigaAmV3.TOTAL_BYTES / 1_000_000
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(tr("${GigaAmV3.DISPLAY_NAME}: Russian, runs on the phone, works offline", "${GigaAmV3.DISPLAY_NAME}: русский язык, работает на телефоне без интернета"), style = MaterialTheme.typography.bodyMedium)
        val statusText = when (status) {
            SttModelStatus.NotDownloaded -> tr("Status: Not downloaded (~$sizeMb MB)", "Статус: не скачана (~$sizeMb МБ)")
            is SttModelStatus.Downloading -> tr("Status: Downloading…", "Статус: скачивание…") + " ${(status.progress * 100).roundToInt()} % " +
                "(${status.downloadedBytes / 1_000_000} / ${status.totalBytes / 1_000_000} ${tr("MB", "МБ")})" +
                (if (status.attempt > 0) tr(" · connection dropped, reconnecting (${status.attempt})…", " · связь оборвалась, переподключение (${status.attempt})…") else "")
            SttModelStatus.Ready -> tr("Status: Ready", "Статус: готова")
            is SttModelStatus.Failed -> tr("Status: Error. ${status.message}", "Статус: ошибка. ${status.message}")
        }
        Text(
            statusText,
            style = MaterialTheme.typography.bodySmall,
            color = if (status is SttModelStatus.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        if (status is SttModelStatus.Downloading) {
            LinearProgressIndicator(progress = { status.progress }, modifier = Modifier.fillMaxWidth())
        }
        when (status) {
            SttModelStatus.NotDownloaded, is SttModelStatus.Failed ->
                Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) { Text(tr("Download model", "Скачать модель")) }
            is SttModelStatus.Downloading ->
                OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text(tr("Cancel download", "Отменить скачивание")) }
            SttModelStatus.Ready ->
                OutlinedButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) { Text(tr("Delete model", "Удалить модель")) }
        }
    }
}
