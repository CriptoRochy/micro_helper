package com.helper.voiceassistant.presentation.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.helper.voiceassistant.domain.model.AssistantSettings
import com.helper.voiceassistant.domain.model.ButtonAction
import com.helper.voiceassistant.domain.model.BluetoothMicMode
import com.helper.voiceassistant.domain.model.HeadsetButtonConfig
import com.helper.voiceassistant.manager.DetectedKey
import com.helper.voiceassistant.presentation.components.DropdownRow
import com.helper.voiceassistant.presentation.components.SectionHeader
import com.helper.voiceassistant.presentation.components.SliderRow
import com.helper.voiceassistant.presentation.components.SwitchRow
import com.helper.voiceassistant.util.tr
import kotlin.math.roundToInt

private fun ms(v: Float) = tr("${v.roundToInt()} ms", "${v.roundToInt()} мс")

/** "Recording start & end": which events start/stop a recording and how much audio is cut. */
@Composable
fun RecordingTriggersSection(s: AssistantSettings, set: ((AssistantSettings) -> AssistantSettings) -> Unit) {
    val r = s.recording
    fun upd(f: (com.helper.voiceassistant.domain.model.RecordingControl) -> com.helper.voiceassistant.domain.model.RecordingControl) =
        set { it.copy(recording = f(it.recording)) }

    SectionHeader(tr("Recording start & end", "Начало и конец записи"))
    DropdownRow(
        tr("Bluetooth headset microphone", "Микрофон Bluetooth-гарнитуры"),
        BluetoothMicMode.entries.toList(), r.bluetoothMic, ::micModeLabel,
    ) { v -> upd { it.copy(bluetoothMic = v) } }
    Text(micModeHint(r.bluetoothMic), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(tr("Start recording by", "Начинать запись по"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
    SwitchRow(tr("Control beep", "Звуковому сигналу"), r.startByBeep) { v -> upd { it.copy(startByBeep = v) } }
    SwitchRow(tr("Headset button", "Кнопке гарнитуры"), r.startByButton) { v -> upd { it.copy(startByButton = v) } }

    Text(tr("End recording by", "Заканчивать запись по"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    SwitchRow(tr("Control beep", "Звуковому сигналу"), r.stopByBeep) { v -> upd { it.copy(stopByBeep = v) } }
    SwitchRow(tr("Headset button", "Кнопке гарнитуры"), r.stopByButton) { v -> upd { it.copy(stopByButton = v) } }
    SwitchRow(
        tr("Silence after speech", "Тишине после речи"), r.stopOnSilence,
        tr("Sends automatically when you stop talking", "Отправляет автоматически, когда вы замолчали"),
    ) { v -> upd { it.copy(stopOnSilence = v) } }
    if (r.stopOnSilence) {
        SliderRow(tr("Stop after silence of", "Остановить после тишины"), r.silenceMs.toFloat(), 500f..10000f, 0, { ms(it) }) { v -> upd { it.copy(silenceMs = (v / 100).roundToInt() * 100) } }
        SliderRow(tr("Silence level", "Уровень тишины"), r.silenceThresholdDbfs, -70f..-20f, 0, { "${it.roundToInt()} dBFS" }) { v -> upd { it.copy(silenceThresholdDbfs = v.roundToInt().toFloat()) } }
        SliderRow(tr("Speech needed before auto-stop", "Речь до автоостановки, не меньше"), r.minSpeechBeforeAutoStopMs.toFloat(), 0f..3000f, 0, { ms(it) }) { v -> upd { it.copy(minSpeechBeforeAutoStopMs = (v / 50).roundToInt() * 50) } }
    }
    if (!r.stopByBeep && !r.stopByButton && !r.stopOnSilence) {
        Text(
            tr("Only the maximum length will end a recording.", "Запись завершится только по максимальной длине."),
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
        )
    }

    Text(tr("Trimming", "Обрезка"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    SliderRow(tr("Cut around the beep", "Обрезка вокруг сигнала"), s.beep.guardMs.toFloat(), 0f..500f, 0, { ms(it) }) { v -> set { it.copy(beep = it.beep.copy(guardMs = (v / 10).roundToInt() * 10)) } }
    SliderRow(tr("Skip after start button press", "Пропуск после нажатия «старт»"), r.buttonStartOffsetMs.toFloat(), 0f..1500f, 0, { ms(it) }) { v -> upd { it.copy(buttonStartOffsetMs = (v / 10).roundToInt() * 10) } }
    SliderRow(tr("Cut before stop button press", "Обрезка перед нажатием «стоп»"), r.buttonEndTrimMs.toFloat(), 0f..1500f, 0, { ms(it) }) { v -> upd { it.copy(buttonEndTrimMs = (v / 10).roundToInt() * 10) } }
}

private fun micModeLabel(m: BluetoothMicMode) = when (m) {
    BluetoothMicMode.WHILE_RECORDING -> tr("Only while recording", "Только во время записи")
    BluetoothMicMode.ALWAYS -> tr("Always (call mode)", "Всегда (режим звонка)")
    BluetoothMicMode.NEVER -> tr("Never — phone microphone", "Никогда — микрофон телефона")
}

private fun micModeHint(m: BluetoothMicMode) = when (m) {
    BluetoothMicMode.WHILE_RECORDING -> tr(
        "Waits on the phone mic, so headset buttons can start a recording. The recording itself uses the headset mic (call mode); many headsets send no buttons then, so end it with a beep, silence or the maximum length.",
        "Ожидание — микрофон телефона, поэтому кнопки гарнитуры запускают запись. Сама запись идёт с микрофона гарнитуры (режим звонка); многие гарнитуры в нём кнопки не передают — заканчивайте сигналом, тишиной или по максимальной длине.",
    )
    BluetoothMicMode.ALWAYS -> tr(
        "Best sound, but in call mode most headsets send no pause / next-track buttons. Use beeps.",
        "Лучший звук, но в режиме звонка большинство гарнитур не передают кнопки «пауза» и «следующий трек». Управление сигналами.",
    )
    BluetoothMicMode.NEVER -> tr(
        "The headset stays in music mode: pause / next-track buttons start and end recordings. The phone microphone records, so keep the phone close.",
        "Гарнитура остаётся в музыкальном режиме: кнопки «пауза» и «следующий трек» и начинают, и заканчивают запись. Записывает микрофон телефона — держите телефон рядом.",
    )
}

private fun actionLabel(a: ButtonAction) = when (a) {
    ButtonAction.NONE -> tr("Nothing", "Ничего")
    ButtonAction.START_STOP_RECORDING -> tr("Start / send recording (like a beep)", "Начать / отправить запись (как сигнал)")
    ButtonAction.REPLAY -> tr("Repeat last answer", "Повторить последний ответ")
    ButtonAction.CANCEL_RECORDING -> tr("Cancel recording / stop speech", "Отменить запись / остановить речь")
    ButtonAction.STOP_SPEECH -> tr("Stop speech", "Остановить речь")
}

private val KNOWN_KEYS: List<Pair<Int, String>>
    get() = listOf(
        HeadsetButtonConfig.KEYCODE_HEADSETHOOK to tr("Call / hook button (wired)", "Кнопка вызова (проводная)"),
        HeadsetButtonConfig.KEYCODE_MEDIA_PLAY_PAUSE to tr("Play / Pause", "Воспроизведение / пауза"),
        HeadsetButtonConfig.KEYCODE_MEDIA_PLAY to tr("Play", "Воспроизведение"),
        HeadsetButtonConfig.KEYCODE_MEDIA_PAUSE to tr("Pause", "Пауза"),
        HeadsetButtonConfig.KEYCODE_MEDIA_NEXT to tr("Next track", "Следующий трек"),
        HeadsetButtonConfig.KEYCODE_MEDIA_PREVIOUS to tr("Previous track", "Предыдущий трек"),
    )

/** "Headset button": gestures → actions, which keys count, and a detector to learn any headset's key. */
@Composable
fun HeadsetButtonSection(
    s: AssistantSettings,
    set: ((AssistantSettings) -> AssistantSettings) -> Unit,
    learning: Boolean,
    lastKey: DetectedKey?,
    onStartLearning: () -> Unit,
    onStopLearning: () -> Unit,
) {
    val b = s.button
    fun upd(f: (HeadsetButtonConfig) -> HeadsetButtonConfig) = set { it.copy(button = f(it.button)) }

    SectionHeader(tr("Headset button", "Кнопка гарнитуры"))
    SwitchRow(
        tr("Control with the headset button", "Управление кнопкой гарнитуры"), b.enabled,
        tr("Same as the beep: press = speak / send, double press = repeat", "Как сигнал: нажатие — говорить / отправить, двойное — повторить"),
    ) { v -> upd { it.copy(enabled = v) } }
    if (!b.enabled) return

    val actions = ButtonAction.entries
    DropdownRow(tr("Single press", "Одно нажатие"), actions, b.singlePress, ::actionLabel) { v -> upd { it.copy(singlePress = v) } }
    DropdownRow(tr("Double press", "Двойное нажатие"), actions, b.doublePress, ::actionLabel) { v -> upd { it.copy(doublePress = v) } }
    DropdownRow(tr("Long press", "Долгое нажатие"), actions, b.longPress, ::actionLabel) { v -> upd { it.copy(longPress = v) } }
    SliderRow(tr("Double-press interval", "Интервал двойного нажатия"), b.doublePressMs.toFloat(), 150f..1000f, 0, { ms(it) }) { v -> upd { it.copy(doublePressMs = (v / 10).roundToInt() * 10) } }
    SliderRow(tr("Long press from", "Долгое нажатие от"), b.longPressMs.toFloat(), 400f..3000f, 0, { ms(it) }) { v -> upd { it.copy(longPressMs = (v / 50).roundToInt() * 50) } }

    Text(tr("Buttons that count", "Какие кнопки учитывать"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    val known = KNOWN_KEYS
    known.forEach { (code, label) ->
        SwitchRow(label, code in b.keyCodes) { on -> upd { it.copy(keyCodes = if (on) it.keyCodes + code else it.keyCodes - code) } }
    }
    // Keys learned from the headset that are not in the list above.
    b.keyCodes.filter { c -> known.none { it.first == c } }.sorted().forEach { code ->
        SwitchRow(tr("Learned key #$code", "Определённая кнопка №$code"), true) { on -> if (!on) upd { it.copy(keyCodes = it.keyCodes - code) } }
    }

    Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(tr("Detect your headset's button", "Определить кнопку вашей гарнитуры"), fontWeight = FontWeight.SemiBold)
            if (!learning) {
                Text(
                    tr("Tap «Detect», then press the button on the headset.", "Нажмите «Определить», затем нажмите кнопку на гарнитуре."),
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = onStartLearning, modifier = Modifier.fillMaxWidth()) { Text(tr("Detect", "Определить")) }
            } else {
                Text(
                    if (lastKey == null) tr("Waiting for a button press…", "Ожидание нажатия кнопки…")
                    else tr("Pressed: ${lastKey.name} (#${lastKey.keyCode})", "Нажата: ${lastKey.name} (№${lastKey.keyCode})"),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            lastKey?.let { k -> upd { it.copy(keyCodes = it.keyCodes + k.keyCode) } }
                            onStopLearning()
                        },
                        enabled = lastKey != null,
                        modifier = Modifier.weight(1f),
                    ) { Text(tr("Use this button", "Использовать")) }
                    OutlinedButton(onClick = onStopLearning, modifier = Modifier.weight(1f)) { Text(tr("Done", "Готово")) }
                }
                Text(
                    tr(
                        "If nothing appears, the headset sends this button to the phone's call function (e.g. redial), which apps cannot intercept. Try another button or a short press.",
                        "Если ничего не появляется, гарнитура передаёт эту кнопку функции звонков телефона (например, «перезвонить»), её приложения перехватить не могут. Попробуйте другую кнопку или короткое нажатие.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
