package com.helper.voiceassistant.presentation.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.helper.voiceassistant.domain.model.AssistantState
import kotlinx.coroutines.delay
import com.helper.voiceassistant.util.tr

fun AssistantState.label(): String = when (this) {
    AssistantState.IDLE -> tr("Stopped", "Остановлен")
    AssistantState.CONNECTING_AUDIO -> tr("Connecting audio", "Подключение аудио")
    AssistantState.LISTENING -> tr("Listening for a beep", "Ожидание сигнала")
    AssistantState.RECORDING -> tr("Recording", "Запись")
    AssistantState.TRANSCRIBING -> tr("Recognizing speech", "Распознавание речи")
    AssistantState.PROCESSING -> tr("Asking DeepSeek", "Запрос к DeepSeek")
    AssistantState.SPEAKING -> tr("Speaking", "Озвучивание ответа")
    AssistantState.REPLAYING -> tr("Repeating last answer", "Повтор последнего ответа")
    AssistantState.ERROR -> tr("Error", "Ошибка")
}

fun AssistantState.hint(): String = when (this) {
    AssistantState.IDLE -> tr("Press Start, then control the assistant with beeps.", "Нажмите «Запустить», затем управляйте ассистентом сигналами.")
    AssistantState.CONNECTING_AUDIO -> tr("Selecting the headset microphone…", "Выбор микрофона гарнитуры…")
    AssistantState.LISTENING -> tr("Beep or headset button — start speaking · double — repeat last answer", "Сигнал или кнопка гарнитуры — начать говорить · двойное — повторить ответ")
    AssistantState.RECORDING -> tr("Speak now · beep or press the button to send", "Говорите · сигнал или кнопка — отправить")
    AssistantState.TRANSCRIBING -> tr("Recognizing speech on the phone (offline)", "Распознавание речи на телефоне (офлайн)")
    AssistantState.PROCESSING -> tr("Waiting for the answer", "Ожидание ответа")
    AssistantState.SPEAKING -> tr("Playing the answer in the headset", "Воспроизведение ответа в гарнитуре")
    AssistantState.REPLAYING -> tr("Playing the stored answer again", "Повтор сохранённого ответа")
    AssistantState.ERROR -> tr("Recovering…", "Восстановление…")
}

@Composable
fun AssistantState.color(): Color = when (this) {
    AssistantState.IDLE -> MaterialTheme.colorScheme.outline
    AssistantState.CONNECTING_AUDIO -> MaterialTheme.colorScheme.secondary
    AssistantState.LISTENING -> Color(0xFF2E7D32)
    AssistantState.RECORDING -> Color(0xFFD32F2F)
    AssistantState.TRANSCRIBING, AssistantState.PROCESSING -> Color(0xFFF9A825)
    AssistantState.SPEAKING, AssistantState.REPLAYING -> MaterialTheme.colorScheme.primary
    AssistantState.ERROR -> MaterialTheme.colorScheme.error
}

/** Pulsing dot whose animation reflects the current state. */
@Composable
fun StateIndicator(state: AssistantState, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val animated by transition.animateFloat(
        initialValue = 0.75f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(tween(if (state == AssistantState.RECORDING) 450 else 900), RepeatMode.Reverse),
        label = "scale",
    )
    val pulsing = state in setOf(AssistantState.LISTENING, AssistantState.RECORDING, AssistantState.SPEAKING, AssistantState.REPLAYING)
    Box(modifier.size(28.dp), contentAlignment = Alignment.Center) {
        if (state == AssistantState.TRANSCRIBING || state == AssistantState.PROCESSING || state == AssistantState.CONNECTING_AUDIO) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp, color = state.color())
        } else {
            Box(
                Modifier
                    .size(18.dp)
                    .scale(if (pulsing) animated else 1f)
                    .clip(CircleShape)
                    .background(state.color()),
            )
        }
    }
}

@Composable
fun StatusCard(state: AssistantState, recordingStartedAtMs: Long?, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = state.color().copy(alpha = 0.12f)),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            StateIndicator(state)
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(state.label(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(state.hint(), style = MaterialTheme.typography.bodySmall)
            }
            if (state == AssistantState.RECORDING && recordingStartedAtMs != null) RecordingTimer(recordingStartedAtMs)
        }
        if (state == AssistantState.TRANSCRIBING || state == AssistantState.PROCESSING) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
        }
    }
}

@Composable
fun RecordingTimer(startedAtMs: Long) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAtMs) {
        while (true) { now = System.currentTimeMillis(); delay(250) }
    }
    val sec = ((now - startedAtMs) / 1000).coerceAtLeast(0)
    Text(
        "%d:%02d".format(sec / 60, sec % 60),
        style = MaterialTheme.typography.titleLarge,
        color = Color(0xFFD32F2F),
        fontWeight = FontWeight.Bold,
    )
}

/** Microphone input level, −60 dBFS … 0 dBFS. */
@Composable
fun MicLevelBar(levelDb: Float, active: Boolean, modifier: Modifier = Modifier) {
    val fraction = if (!active) 0f else ((levelDb + 60f) / 60f).coerceIn(0f, 1f)
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr("Mic", "Мик."), style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(32.dp))
        Box(
            Modifier
                .weight(1f)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(8.dp)
                    .background(if (fraction > 0.85f) Color(0xFFD32F2F) else Color(0xFF43A047)),
            )
        }
        Text(if (active) tr("%.0f dB", "%.0f дБ").format(levelDb) else tr("off", "выкл."), style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(48.dp))
    }
}
