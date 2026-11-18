package com.helper.voiceassistant.presentation.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.helper.voiceassistant.domain.model.ConversationEntry
import com.helper.voiceassistant.domain.model.ExchangeStatus
import com.helper.voiceassistant.presentation.viewmodel.HistoryViewModel
import java.text.DateFormat
import java.util.Date
import com.helper.voiceassistant.util.tr

private fun ExchangeStatus.label(): String = when (this) {
    ExchangeStatus.SUCCESS -> tr("OK", "Успешно")
    ExchangeStatus.STT_ERROR -> tr("recognition error", "ошибка распознавания")
    ExchangeStatus.API_ERROR -> tr("DeepSeek error", "ошибка DeepSeek")
    ExchangeStatus.TTS_ERROR -> tr("playback error", "ошибка озвучивания")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(viewModel: HistoryViewModel, onBack: () -> Unit) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    var opened by remember { mutableStateOf<ConversationEntry?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    opened?.let { entry ->
        AlertDialog(
            onDismissRequest = { opened = null },
            title = { Text(formatTime(entry.timestampMs)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr("You", "Вы"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(entry.userText.ifBlank { tr("(not recognized)", "(не распознано)") })
                    Text("DeepSeek", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(entry.responseText ?: "—")
                    entry.errorMessage?.let { Text(tr("Error: $it", "Ошибка: $it"), color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                if (entry.responseText != null) TextButton(onClick = { viewModel.replay(entry) }) { Text(tr("Play", "Воспроизвести")) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = viewModel::stopPlayback) { Text(tr("Stop", "Стоп")) }
                    TextButton(onClick = { opened = null }) { Text(tr("Close", "Закрыть")) }
                }
            },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(tr("Clear the whole history?", "Очистить всю историю?")) },
            text = { Text(tr("All stored conversations will be permanently deleted.", "Все сохранённые диалоги будут удалены без возможности восстановления.")) },
            confirmButton = { TextButton(onClick = { confirmClear = false; viewModel.clear() }) { Text(tr("Clear", "Очистить")) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(tr("Cancel", "Отмена")) } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tr("Conversation history", "История диалогов")) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Back", "Назад")) } },
                actions = { if (entries.isNotEmpty()) TextButton(onClick = { confirmClear = true }) { Text(tr("Clear all", "Очистить всё")) } },
            )
        },
    ) { padding ->
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(tr("No conversations yet", "Диалогов пока нет"))
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(entries, key = { it.id }) { entry ->
                    Card(Modifier.fillMaxWidth().clickable { opened = entry }) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    formatTime(entry.timestampMs) + if (entry.status != ExchangeStatus.SUCCESS) " · ${entry.status.label()}" else "",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (entry.status == ExchangeStatus.SUCCESS) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.error,
                                )
                                Text(entry.userText.ifBlank { tr("(not recognized)", "(не распознано)") }, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                                Text(entry.responseText ?: entry.errorMessage ?: "—", maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                            }
                            if (entry.responseText != null) {
                                IconButton(onClick = { viewModel.replay(entry) }) { Icon(Icons.Filled.PlayArrow, contentDescription = tr("Play answer", "Воспроизвести ответ")) }
                            }
                            IconButton(onClick = { viewModel.delete(entry.id) }) { Icon(Icons.Filled.Delete, contentDescription = tr("Delete", "Удалить")) }
                        }
                    }
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ms))
