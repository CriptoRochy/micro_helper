package com.helper.voiceassistant.presentation.screen

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.helper.voiceassistant.data.library.AudioFormatPolicy
import com.helper.voiceassistant.domain.model.LibraryItem
import com.helper.voiceassistant.presentation.viewmodel.LibraryEditor
import com.helper.voiceassistant.presentation.viewmodel.LibraryViewModel
import com.helper.voiceassistant.util.tr

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(viewModel: LibraryViewModel, onBack: () -> Unit) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val editor by viewModel.editor.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val previewing by viewModel.previewing.collectAsStateWithLifecycle()
    var toDelete by remember { mutableStateOf<LibraryItem?>(null) }

    val pickNew = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::onPickedForNew) }
    val pickReplacement = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::onPickedReplacement) }

    val e = editor
    BackHandler(enabled = e != null) { viewModel.cancelEdit() }

    toDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text(tr("Delete «${item.title}»?", "Удалить «${item.title}»?")) },
            text = {
                Text(tr("The entry and its copy inside the app are deleted. The original file on the phone stays.",
                    "Запись и её копия в приложении будут удалены. Исходный файл на телефоне останется."))
            },
            confirmButton = { TextButton(onClick = { viewModel.delete(item); toDelete = null }) { Text(tr("Delete", "Удалить")) } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text(tr("Cancel", "Отмена")) } },
        )
    }

    e?.conflicts?.let { conflicts ->
        AlertDialog(
            onDismissRequest = viewModel::dismissConflicts,
            title = { Text(tr("The phrase is already used", "Фраза уже используется")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    conflicts.forEach { Text(tr("«${it.phrase}» — in «${it.otherTitle}»", "«${it.phrase}» — у записи «${it.otherTitle}»")) }
                    Spacer(Modifier.height(4.dp))
                    Text(tr("If you keep both, saying this phrase will offer a choice.", "Если оставить обе, на эту фразу ассистент предложит выбрать запись."))
                }
            },
            confirmButton = { TextButton(onClick = { viewModel.save(keepBoth = true) }) { Text(tr("Keep both", "Оставить обе")) } },
            dismissButton = { TextButton(onClick = viewModel::dismissConflicts) { Text(tr("Change the phrase", "Изменить фразу")) } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            e == null -> tr("Audio library settings", "Настройки аудиотеки")
                            e.id == null -> tr("New entry", "Новая запись")
                            else -> tr("Edit entry", "Изменить запись")
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (e != null) viewModel.cancelEdit() else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Back", "Назад"))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            busy?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            message?.let { Hint(it, isError = false) { TextButton(onClick = viewModel::consumeMessage) { Text(tr("OK", "OK")) } } }

            if (e == null) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(tr("Formats: ", "Форматы: ") + AudioFormatPolicy.describe(), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            tr("The file is copied into the app, so the search works even if the original is moved.",
                                "Файл копируется в приложение, поэтому поиск работает, даже если оригинал перенесут."),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(onClick = { viewModel.consumeMessage(); pickNew.launch(AudioFormatPolicy.PICKER_MIME_TYPES) }, enabled = busy == null) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Text(tr("Add a recording", "Добавить запись"))
                        }
                    }
                }
                val list = items
                when {
                    list == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    list.isEmpty() -> Text(tr("The audio library has no recordings yet.", "В аудиотеке пока нет записей."), style = MaterialTheme.typography.bodyLarge)
                    else -> list.forEach { item ->
                        EntryCard(
                            item, playing = previewing == item.id,
                            onPreview = { viewModel.togglePreview(item) },
                            onEdit = { viewModel.edit(item) },
                            onDelete = { toDelete = item },
                        )
                    }
                }
            } else {
                Editor(
                    e, previewing = previewing == LibraryViewModel.STAGED || (e.staged == null && previewing == e.id), busy = busy != null,
                    onTitle = viewModel::setTitle, onPhrases = viewModel::setPhrases,
                    onPreview = viewModel::togglePreviewStaged,
                    onReplace = { pickReplacement.launch(AudioFormatPolicy.PICKER_MIME_TYPES) },
                    onSave = { viewModel.save() }, onCancel = viewModel::cancelEdit,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun EntryCard(item: LibraryItem, playing: Boolean, onPreview: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 4.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (item.phrases.isEmpty()) tr("Found by its title", "Ищется по названию")
                        else tr("Phrases: ", "Фразы: ") + item.phrases.joinToString(", ") { "«$it»" },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text("${formatDuration(item.durationMs)} · ${item.originalName}", style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = onPreview) {
                    Icon(if (playing) Icons.Filled.Close else Icons.Filled.PlayArrow, contentDescription = if (playing) tr("Stop listening", "Остановить прослушивание") else tr("Listen", "Прослушать"))
                }
                IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = tr("Edit", "Изменить")) }
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = tr("Delete", "Удалить")) }
            }
        }
    }
}

@Composable
private fun Editor(
    e: LibraryEditor,
    previewing: Boolean,
    busy: Boolean,
    onTitle: (String) -> Unit,
    onPhrases: (String) -> Unit,
    onPreview: () -> Unit,
    onReplace: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    OutlinedTextField(
        value = e.title, onValueChange = onTitle, singleLine = true, modifier = Modifier.fillMaxWidth(),
        label = { Text(tr("Title", "Название")) },
        supportingText = { Text(tr("The title also works as a search phrase.", "Название тоже работает как фраза для поиска.")) },
    )
    OutlinedTextField(
        value = e.phrasesText, onValueChange = onPhrases, minLines = 3, modifier = Modifier.fillMaxWidth(),
        label = { Text(tr("Search phrases", "Фразы для поиска")) },
        supportingText = { Text(tr("One per line, e.g. «расписание» or «включи расписание».", "Каждая с новой строки, например «расписание» или «включи расписание».")) },
    )
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val staged = e.staged
            Text(tr("Audio file", "Аудиофайл"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            when {
                staged != null -> {
                    Text("${staged.displayName} · ${formatDuration(staged.durationMs)} · ${"%.1f".format(staged.sizeBytes / 1_048_576.0)} МБ")
                    if (e.id != null) Text(tr("New file — replaces the current one when saved.", "Новый файл — заменит текущий после сохранения."), style = MaterialTheme.typography.bodySmall)
                }
                e.currentFileName != null -> Text("${e.currentFileName} · ${formatDuration(e.currentDurationMs)}")
                else -> Text("—")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPreview, enabled = staged != null || e.id != null) {
                    Text(if (previewing) tr("Stop", "Стоп") else tr("Listen", "Прослушать"))
                }
                OutlinedButton(onClick = onReplace, enabled = !busy) {
                    Text(if (e.id == null) tr("Choose another file", "Выбрать другой файл") else tr("Replace the file", "Заменить файл"))
                }
            }
        }
    }
    e.error?.let { Hint(it, isError = true) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text(tr("Cancel", "Отмена")) }
        Button(onClick = onSave, enabled = !busy && e.title.isNotBlank(), modifier = Modifier.weight(1f)) { Text(tr("Save", "Сохранить")) }
    }
}
