package com.helper.voiceassistant.presentation.screen

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.helper.voiceassistant.assistant.ClipState
import com.helper.voiceassistant.data.stt.GigaAmV3
import com.helper.voiceassistant.data.stt.SttModelStatus
import com.helper.voiceassistant.domain.model.AssistantSettings
import com.helper.voiceassistant.domain.model.AssistantState
import com.helper.voiceassistant.domain.model.AssistantStatus
import com.helper.voiceassistant.domain.model.ErrorKind
import com.helper.voiceassistant.presentation.components.MicLevelBar
import com.helper.voiceassistant.presentation.viewmodel.SearchViewModel
import com.helper.voiceassistant.service.AudioCaptureService
import com.helper.voiceassistant.util.tr

/** Stage shown on the search screen (spec: Готов к команде / Слушаю / Распознаю / Уточните выбор / Воспроизвожу). */
private fun stageOf(s: AssistantStatus): String = when {
    s.state == AssistantState.PLAYING_CLIP -> tr("Playing", "Воспроизвожу")
    s.choiceOptions != null && s.state in setOf(AssistantState.SPEAKING, AssistantState.LISTENING, AssistantState.RECORDING) ->
        tr("Choose an option", "Уточните выбор")
    s.state == AssistantState.RECORDING -> tr("Listening", "Слушаю")
    s.state == AssistantState.TRANSCRIBING || s.state == AssistantState.PROCESSING -> tr("Recognizing", "Распознаю")
    s.state == AssistantState.SPEAKING || s.state == AssistantState.REPLAYING -> tr("Answering", "Отвечаю")
    s.state == AssistantState.LISTENING -> tr("Ready for a command", "Готов к команде")
    s.state == AssistantState.CONNECTING_AUDIO -> tr("Connecting audio…", "Подключаю звук…")
    s.state == AssistantState.ERROR -> tr("Error", "Ошибка")
    else -> tr("Not started", "Не запущен")
}

/** How a request starts, from the recording settings (the same controls as the voice assistant). */
private fun requestHint(s: AssistantSettings): String {
    val button = s.button.enabled && s.recording.startByButton
    val beep = s.beep.enabled && s.recording.startByBeep
    val how = when {
        button && beep -> tr("Press the headset button or give the beep", "Нажмите кнопку гарнитуры или подайте сигнал")
        button -> tr("Press the headset button", "Нажмите кнопку гарнитуры")
        beep -> tr("Give the beep", "Подайте сигнал")
        else -> tr("Press «Speak from the screen»", "Нажмите «Сказать с экрана»")
    }
    return if (s.recording.cueSounds) how + tr(" and say the phrase after the sound.", " и скажите фразу после звука.")
    else how + tr(" and say the phrase.", " и скажите фразу.")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    onBack: () -> Unit,
    onOpenLibrary: () -> Unit,
) {
    val context = LocalContext.current
    val status by viewModel.status.collectAsStateWithLifecycle()
    val route by viewModel.route.collectAsStateWithLifecycle()
    val micLevel by viewModel.micLevel.collectAsStateWithLifecycle()
    val clip by viewModel.clip.collectAsStateWithLifecycle()
    val modelStatus by viewModel.sttModelStatus.collectAsStateWithLifecycle()
    val libraryCount by viewModel.libraryCount.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var micDenied by remember { mutableStateOf(false) }

    val running = status.state.isActive

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true || granted(context, Manifest.permission.RECORD_AUDIO)) {
            micDenied = false
            AudioCaptureService.start(context)
        } else {
            micDenied = true
        }
    }

    // Same as the voice assistant: the big button starts / stops listening, requests start with the
    // headset button or the beep (or «Speak from the screen»).
    fun onStartStop() {
        if (running) { AudioCaptureService.stop(context); return }
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
        }.filterNot { granted(context, it) }
        when {
            modelStatus != SttModelStatus.Ready -> Unit // the model card below offers the download
            needed.isNotEmpty() -> permissionLauncher.launch(needed.toTypedArray())
            else -> AudioCaptureService.start(context)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tr("Voice search", "Голосовой поиск")) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Back to dialog", "Назад к диалогу")) } },
                actions = { IconButton(onClick = onOpenLibrary) { Icon(Icons.Filled.Settings, contentDescription = tr("Audio library settings", "Настройки аудиотеки")) } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                tr("Say a key phrase or a title — the matching recording plays. Works offline, without DeepSeek.",
                    "Скажите фразу-ключ или название — включится нужная запись. Работает офлайн, без DeepSeek."),
                style = MaterialTheme.typography.bodyMedium,
            )

            // Stage
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stageOf(status), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    if (running && status.state in setOf(AssistantState.LISTENING, AssistantState.PLAYING_CLIP)) {
                        Text(requestHint(settings), style = MaterialTheme.typography.bodyMedium)
                    }
                    MicLevelBar(micLevel, active = running)
                    Text(tr("Microphone: ${route.inputName ?: "—"}", "Микрофон: ${route.inputName ?: "—"}"), style = MaterialTheme.typography.bodySmall)
                }
            }

            // Missing model
            if (modelStatus != SttModelStatus.Ready) {
                ModelCard(modelStatus, onDownload = viewModel::downloadSttModel)
            }

            // Empty library
            if (libraryCount == 0) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(tr("The audio library has no recordings yet", "В аудиотеке пока нет записей"), fontWeight = FontWeight.SemiBold)
                        Button(onClick = onOpenLibrary) { Text(tr("Add a recording", "Добавить запись")) }
                    }
                }
            }

            if (micDenied) {
                Hint(
                    tr("No access to the microphone. Allow it: Settings › Apps › micro_helper › Permissions › Microphone.",
                        "Нет доступа к микрофону. Разрешите его: Настройки › Приложения › micro_helper › Разрешения › Микрофон."),
                    isError = true,
                ) {
                    TextButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                    }) { Text(tr("Open settings", "Открыть настройки")) }
                }
            }

            // Start / stop listening, as on the voice assistant screen
            Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
                Button(
                    onClick = ::onStartStop,
                    enabled = running || modelStatus == SttModelStatus.Ready,
                    modifier = Modifier.size(168.dp),
                    contentPadding = PaddingValues(8.dp), // «Остановить» fits on one line
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = if (running) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary),
                ) {
                    Text(
                        if (running) tr("Stop\nsearch", "Остановить\nпоиск") else tr("Start\nsearch", "Запустить\nпоиск"),
                        style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center,
                    )
                }
            }
            // Without a headset button: the request from the screen
            if (running) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    if (status.state == AssistantState.RECORDING) {
                        OutlinedButton(onClick = viewModel::talk, modifier = Modifier.weight(1f)) { Text(tr("Done", "Готово")) }
                        OutlinedButton(onClick = viewModel::cancelRecording, modifier = Modifier.weight(1f)) { Text(tr("Cancel", "Отменить")) }
                    } else {
                        OutlinedButton(
                            onClick = viewModel::talk,
                            enabled = status.state in setOf(AssistantState.LISTENING, AssistantState.PLAYING_CLIP),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(tr("Speak from the screen", "Сказать с экрана")) }
                    }
                }
            }

            status.error
                ?.takeUnless { it.kind == ErrorKind.LIBRARY_NOT_FOUND && libraryCount == 0 } // the card above says it
                ?.let { err -> Hint(err.message + (err.recoveryHint?.let { "\n$it" } ?: ""), isError = true) }
            status.info?.let { Hint(it, isError = false) }

            status.choiceOptions?.let { options ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(tr("Say the number:", "Скажите номер:"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        options.forEachIndexed { i, title -> Text("${i + 1}. $title", style = MaterialTheme.typography.bodyLarge) }
                    }
                }
            }

            Labeled(tr("Heard", "Услышано"), status.lastRecognizedText)

            if (clip.title != null) {
                ClipCard(
                    clip, playingState = status.state == AssistantState.PLAYING_CLIP,
                    onPause = viewModel::pauseClip, onResume = viewModel::resumeClip,
                    onBack10 = { viewModel.seekClip(-10_000) }, onForward10 = { viewModel.seekClip(10_000) }, onStop = viewModel::stopClip,
                )
            }

            Text(
                tr("Headset button or beep: start / finish a request; during playback it pauses the recording and listens. Sounds and controls: Settings › Recording start & end.",
                    "Кнопка гарнитуры или сигнал: начать и закончить запрос; во время воспроизведения ставят запись на паузу и слушают. Звуки и управление: Настройки › Начало и конец записи."),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ModelCard(status: SttModelStatus, onDownload: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (status) {
                is SttModelStatus.Downloading -> {
                    Text(tr("Downloading the speech model… ${(status.progress * 100).toInt()} %", "Скачиваю модель распознавания… ${(status.progress * 100).toInt()} %"))
                    LinearProgressIndicator(progress = { status.progress }, modifier = Modifier.fillMaxWidth())
                }
                else -> {
                    Text(
                        tr("Search needs the offline speech model ${GigaAmV3.DISPLAY_NAME} (~${GigaAmV3.TOTAL_BYTES / 1_000_000} MB, downloaded once).",
                            "Для поиска нужна офлайн-модель распознавания ${GigaAmV3.DISPLAY_NAME} (~${GigaAmV3.TOTAL_BYTES / 1_000_000} МБ, скачивается один раз)."),
                    )
                    Button(onClick = onDownload) { Text(tr("Download the model", "Скачать модель")) }
                }
            }
        }
    }
}

@Composable
private fun ClipCard(
    clip: ClipState,
    playingState: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onBack10: () -> Unit,
    onForward10: () -> Unit,
    onStop: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (clip.playing) tr("Now playing", "Сейчас играет") else tr("Paused", "На паузе"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(clip.title ?: "", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (clip.durationMs > 0) {
                LinearProgressIndicator(progress = { (clip.positionMs.toFloat() / clip.durationMs).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text("${formatDuration(clip.positionMs)} / ${formatDuration(clip.durationMs)}", style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onBack10, modifier = Modifier.weight(1f)) { Text("−10 с") }
                if (clip.playing || playingState) {
                    Button(onClick = onPause, modifier = Modifier.weight(1.4f)) { Text(tr("Pause", "Пауза")) }
                } else {
                    Button(onClick = onResume, modifier = Modifier.weight(1.4f)) { Text(tr("Resume", "Дальше")) }
                }
                OutlinedButton(onClick = onForward10, modifier = Modifier.weight(1f)) { Text("+10 с") }
            }
            OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text(tr("Stop", "Стоп")) }
        }
    }
}

@Composable
internal fun Hint(text: String, isError: Boolean, action: (@Composable () -> Unit)? = null) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isError) Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text(text, style = MaterialTheme.typography.bodyMedium)
            }
            action?.invoke()
        }
    }
}

@Composable
private fun Labeled(title: String, text: String?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            Text(text ?: "—", style = MaterialTheme.typography.bodyLarge)
        }
    }
}

internal fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

private fun granted(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
