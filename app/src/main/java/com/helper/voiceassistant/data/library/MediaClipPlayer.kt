package com.helper.voiceassistant.data.library

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.helper.voiceassistant.assistant.ClipEvent
import com.helper.voiceassistant.assistant.ClipPlayer
import com.helper.voiceassistant.assistant.ClipState
import com.helper.voiceassistant.domain.model.AssistantFailure
import com.helper.voiceassistant.domain.model.ErrorKind
import com.helper.voiceassistant.util.tr
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MediaPlayer for library recordings, played as media (A2DP on Bluetooth headsets).
 *
 * Pauses itself when the headset is unplugged / disconnected (ACTION_AUDIO_BECOMING_NOISY, sent
 * before Android moves audio to the speaker) or when another app takes audio focus; both are reported
 * as [ClipEvent.PausedBySystem] and the clip stays loaded so it can be resumed.
 */
@Singleton
class MediaClipPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
) : ClipPlayer {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    private var noisyRegistered = false

    private val _events = MutableSharedFlow<ClipEvent>(extraBufferCapacity = 8)
    override val events: SharedFlow<ClipEvent> = _events.asSharedFlow()
    private val _state = MutableStateFlow(ClipState())
    override val state: StateFlow<ClipState> = _state.asStateFlow()

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private val ticker = object : Runnable {
        override fun run() {
            publish()
            if (player?.isPlaying == true) main.postDelayed(this, 500)
        }
    }

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && player?.isPlaying == true) {
                Timber.i("Headset disconnected — pausing clip")
                pauseBySystem()
            }
        }
    }

    @Synchronized
    override fun play(file: File, title: String) {
        releasePlayer()
        if (!file.isFile) throw AssistantFailure(ErrorKind.CLIP_PLAYBACK, tr("The audio file is missing.", "Аудиофайл не найден."))
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(attributes)
            mp.setDataSource(file.path)
            mp.prepare()
        } catch (e: Exception) {
            mp.release()
            Timber.e(e, "Clip cannot be played: %s", file.name)
            throw AssistantFailure(ErrorKind.CLIP_PLAYBACK, tr("The recording cannot be played.", "Запись не удаётся воспроизвести."), e)
        }
        mp.setOnCompletionListener { onCompleted(it) }
        mp.setOnErrorListener { p, what, extra ->
            Timber.e("Clip playback error %d/%d", what, extra)
            if (p === player) {
                releasePlayer()
                _events.tryEmit(ClipEvent.Failed(tr("Playback error.", "Ошибка воспроизведения.")))
            }
            true
        }
        player = mp
        _state.value = ClipState(title = title, playing = false, positionMs = 0, durationMs = mp.duration.toLong().coerceAtLeast(0))
        startPlayback()
    }

    @Synchronized
    override fun pause() {
        val mp = player ?: return
        if (mp.isPlaying) mp.pause()
        abandonFocus()
        publish()
    }

    @Synchronized
    override fun resume() {
        val mp = player ?: return
        if (!mp.isPlaying) startPlayback()
    }

    @Synchronized
    override fun seekBy(deltaMs: Long) {
        val mp = player ?: return
        val target = (mp.currentPosition + deltaMs).coerceIn(0, mp.duration.toLong().coerceAtLeast(0))
        mp.seekTo(target.toInt())
        publish()
    }

    @Synchronized
    override fun stop() {
        releasePlayer()
    }

    private fun startPlayback() {
        val mp = player ?: return
        requestFocus()
        registerNoisy()
        mp.start()
        publish()
        main.removeCallbacks(ticker)
        main.post(ticker)
    }

    @Synchronized
    private fun onCompleted(mp: MediaPlayer) {
        if (mp !== player) return
        releasePlayer()
        _events.tryEmit(ClipEvent.Completed)
    }

    @Synchronized
    private fun pauseBySystem() {
        val mp = player ?: return
        if (mp.isPlaying) mp.pause()
        abandonFocus()
        publish()
        _events.tryEmit(ClipEvent.PausedBySystem)
    }

    private fun publish() {
        val mp = player
        _state.value = if (mp == null) ClipState() else runCatching {
            _state.value.copy(playing = mp.isPlaying, positionMs = mp.currentPosition.toLong(), durationMs = mp.duration.toLong().coerceAtLeast(0))
        }.getOrDefault(_state.value)
    }

    private fun releasePlayer() {
        main.removeCallbacks(ticker)
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        abandonFocus()
        unregisterNoisy()
        _state.value = ClipState()
    }

    private fun requestFocus() {
        if (focus != null) return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener({ change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    Timber.w("Clip lost audio focus (%d)", change)
                    pauseBySystem()
                }
            }, main)
            .build()
        focus = request
        if (audioManager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) Timber.w("Clip audio focus not granted")
    }

    private fun abandonFocus() {
        focus?.let { audioManager.abandonAudioFocusRequest(it) }
        focus = null
    }

    private fun registerNoisy() {
        if (noisyRegistered) return
        ContextCompat.registerReceiver(context, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_EXPORTED)
        noisyRegistered = true
    }

    private fun unregisterNoisy() {
        if (!noisyRegistered) return
        runCatching { context.unregisterReceiver(noisy) }
        noisyRegistered = false
    }
}
