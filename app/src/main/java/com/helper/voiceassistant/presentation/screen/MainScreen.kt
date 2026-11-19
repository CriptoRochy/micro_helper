package com.helper.voiceassistant.presentation.screen

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.helper.voiceassistant.data.stt.GigaAmV3
import com.helper.voiceassistant.data.stt.SttModelStatus
import com.helper.voiceassistant.domain.model.AssistantState
import com.helper.voiceassistant.domain.model.AudioDeviceKind
import com.helper.voiceassistant.presentation.components.MicLevelBar
import com.helper.voiceassistant.presentation.components.StatusCard
import com.helper.voiceassistant.presentation.viewmodel.MainViewModel
import com.helper.voiceassistant.service.AudioCaptureService
import java.net.URI
import com.helper.voiceassistant.util.tr

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSearch: () -> Unit,
) {
    val context = LocalContext.current
    val status by viewModel.status.collectAsStateWithLifecycle()
    val route by viewModel.route.collectAsStateWithLifecycle()
    val micLevel by viewModel.micLevel.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val keysMissing by viewModel.keysMissing.collectAsStateWithLifecycle()
    val modelStatus by viewModel.sttModelStatus.collectAsStateWithLifecycle()
    var showModelPrompt by remember { mutableStateOf(false) }

    var showConsent by remember { mutableStateOf(false) }
    var permissionMessage by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true || hasPermission(context, Manifest.permission.RECORD_AUDIO)) {
            permissionMessage = if (Build.VERSION.SDK_INT >= 33 && result[Manifest.permission.POST_NOTIFICATIONS] == false)
                tr("Notifications are off: the assistant works, but its status/stop notification will be hidden.", "Уведомления отключены: ассистент работает, но уведомление о статусе и кнопка «Стоп» будут скрыты.") else null
            if (settings.sttConsentGiven) AudioCaptureService.start(context) else showConsent = true
        } else {
            permissionMessage = tr("Microphone permission is required: the assistant listens for control beeps and records your question.", "Нужно разрешение на микрофон: ассистент слушает управляющие сигналы и записывает ваш вопрос.")
        }
    }

    val running = status.state.isActive
    // Started from voice search, which needs no DeepSeek consent: ask before the dialog sends anything.
    LaunchedEffect(running, settings.sttConsentGiven) { if (running && !settings.sttConsentGiven) showConsent = true }
    fun onStartStop() {
        if (running) {
            AudioCaptureService.stop(context)
            return
        }
        viewModel.refreshKeys()
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
        }.filterNot { hasPermission(context, it) }
        when {
            modelStatus != SttModelStatus.Ready -> showModelPrompt = true
            needed.isNotEmpty() -> permissionLauncher.launch(needed.toTypedArray())
            !settings.sttConsentGiven -> showConsent = true
            else -> AudioCaptureService.start(context)
        }
    }

    if (showModelPrompt) {
        val downloading = modelStatus is SttModelStatus.Downloading
        AlertDialog(
            onDismissRequest = { showModelPrompt = false },
            title = { Text(tr("Download the speech model", "Скачайте модель распознавания речи")) },
            text = {
                Text(
                    if (downloading) tr("The offline speech model is still downloading. The assistant can start once it is ready.", "Офлайн-модель распознавания ещё скачивается. Ассистент можно будет запустить, когда она будет готова.")
                    else tr(
                        "Speech is recognized on the phone with ${GigaAmV3.DISPLAY_NAME}. The model (~${GigaAmV3.TOTAL_BYTES / 1_000_000} MB) " +
                            "is downloaded once; after that recognition works without internet. Wi-Fi is recommended.",
                        "Речь распознаётся прямо на телефоне моделью ${GigaAmV3.DISPLAY_NAME}. Модель (~${GigaAmV3.TOTAL_BYTES / 1_000_000} МБ) " +
                            "скачивается один раз, после этого распознавание работает без интернета. Лучше подключиться к Wi-Fi.",
                    ),
                )
            },
            confirmButton = {
                if (downloading) TextButton(onClick = { showModelPrompt = false }) { Text(tr("OK", "OK")) }
                else TextButton(onClick = { showModelPrompt = false; viewModel.downloadSttModel() }) { Text(tr("Download", "Скачать")) }
            },
            dismissButton = { if (!downloading) TextButton(onClick = { showModelPrompt = false }) { Text(tr("Later", "Позже")) } },
        )
    }

    if (showConsent && !settings.sttConsentGiven) {
        AlertDialog(
            onDismissRequest = { showConsent = false },
            title = { Text(tr("Recognized text is sent to DeepSeek", "Распознанный текст отправляется в DeepSeek")) },
            text = {
                Text(
                    tr(
                        "Speech is recognized on this phone; audio never leaves the device. " +
                            "Only the recognized text is sent to DeepSeek (${runCatching { URI(settings.deepSeekBaseUrl).host }.getOrNull()}). " +
                            "Recordings are deleted from the phone after processing unless you enable keeping them.",
                        "Речь распознаётся на этом телефоне, звук никуда не отправляется. " +
                            "В DeepSeek (${runCatching { URI(settings.deepSeekBaseUrl).host }.getOrNull()}) уходит только распознанный текст. " +
                            "Записи удаляются с телефона после обработки, если вы не включили их сохранение.",
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showConsent = false
                    viewModel.giveSttConsent()
                    if (!running) AudioCaptureService.start(context)
                }) { Text(tr("Agree and start", "Согласен, запустить")) }
            },
            dismissButton = { TextButton(onClick = { showConsent = false }) { Text(tr("Cancel", "Отмена")) } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tr("Voice Assistant", "Голосовой ассистент")) },
                actions = {
                    IconButton(onClick = onOpenHistory) { Icon(Icons.AutoMirrored.Filled.List, contentDescription = tr("Conversation history", "История диалогов")) }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, contentDescription = tr("Settings", "Настройки")) }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusCard(status.state, status.recordingStartedAtMs)

            Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                Button(
                    onClick = ::onStartStop,
                    modifier = Modifier.size(168.dp),
                    contentPadding = PaddingValues(8.dp), // «Остановить» fits on one line
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (running) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    Text(
                        if (running) tr("Stop\nassistant", "Остановить\nассистента") else tr("Start\nassistant", "Запустить\nассистента"),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("Voice search", "Голосовой поиск"), style = MaterialTheme.typography.titleSmall)
                        Text(tr("Play a recording from the audio library by a phrase", "Включить запись из аудиотеки по фразе"), style = MaterialTheme.typography.bodySmall)
                    }
                    Button(onClick = onOpenSearch) {
                        Icon(Icons.Filled.Search, contentDescription = null)
                        Spacer(Modifier.size(4.dp))
                        Text(tr("Open", "Открыть"))
                    }
                }
            }

            if (keysMissing) {
                Notice(tr("The DeepSeek API key is missing. Add it in Settings.", "Не задан API-ключ DeepSeek. Добавьте его в настройках."), isError = true)
            }
            permissionMessage?.let { Notice(it, isError = true) }

            status.error?.let { err ->
                Notice(err.message + (err.recoveryHint?.let { "\n$it" } ?: ""), isError = true)
            }
            status.info?.let { Notice(it, isError = false) }

            // Headset / device status
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("Audio device", "Аудиоустройство"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        IconButton(onClick = viewModel::refreshDevices) { Icon(Icons.Filled.Refresh, contentDescription = tr("Refresh devices", "Обновить устройства")) }
                    }
                    val headsetText = when {
                        route.inputKind != null && route.inputKind != AudioDeviceKind.BUILTIN_MIC -> tr("Headset connected", "Гарнитура подключена")
                        route.headsetConnected -> tr("Headset detected", "Гарнитура обнаружена")
                        else -> tr("No headset connected", "Гарнитура не подключена")
                    }
                    Text(headsetText, fontWeight = FontWeight.SemiBold)
                    Text(tr("Microphone: ${route.inputName ?: "—"}", "Микрофон: ${route.inputName ?: "—"}"), style = MaterialTheme.typography.bodySmall)
                    Text(
                        when (val m = modelStatus) {
                            SttModelStatus.Ready -> tr("STT: Offline (${GigaAmV3.DISPLAY_NAME})", "Распознавание: офлайн (${GigaAmV3.DISPLAY_NAME})")
                            is SttModelStatus.Downloading -> tr("STT: downloading ${GigaAmV3.DISPLAY_NAME}… ${(m.progress * 100).toInt()} %", "Распознавание: скачивание ${GigaAmV3.DISPLAY_NAME}… ${(m.progress * 100).toInt()} %") +
                                (if (m.attempt > 0) tr(" (reconnecting)", " (переподключение)") else "")
                            SttModelStatus.NotDownloaded -> tr("STT: model not downloaded", "Распознавание: модель не скачана")
                            is SttModelStatus.Failed -> tr("STT: model download failed", "Распознавание: ошибка скачивания модели")
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(tr("Output: ${route.outputName ?: "—"}", "Вывод: ${route.outputName ?: "—"}"), style = MaterialTheme.typography.bodySmall)
                    if (route.usesCommunicationRoute) Text(tr("Hands-free (HFP/SCO) link active", "Активна связь «гарнитура» (HFP/SCO)"), style = MaterialTheme.typography.bodySmall)
                    MicLevelBar(micLevel, active = running)
                }
            }

            // Manual controls
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = viewModel::replay,
                    enabled = status.lastResponse != null && status.state in setOf(AssistantState.LISTENING, AssistantState.IDLE),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.size(4.dp))
                    Text(tr("Replay", "Повторить"))
                }
                OutlinedButton(onClick = viewModel::stopPlayback, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Close, contentDescription = null)
                    Spacer(Modifier.size(4.dp))
                    Text(tr("Stop speech", "Остановить речь"))
                }
            }
            if (status.state == AssistantState.RECORDING) {
                OutlinedButton(onClick = viewModel::cancelRecording, modifier = Modifier.fillMaxWidth()) { Text(tr("Cancel recording", "Отменить запись")) }
            }

            TextBlock(tr("You said", "Вы сказали"), status.lastRecognizedText)
            TextBlock(tr("DeepSeek answered", "Ответ DeepSeek"), status.lastResponse)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Notice(text: String, isError: Boolean) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (isError) Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun TextBlock(title: String, text: String?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            Text(text ?: "—", style = MaterialTheme.typography.bodyLarge)
        }
    }
}

private fun hasPermission(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
