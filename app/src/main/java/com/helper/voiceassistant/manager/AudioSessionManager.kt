package com.helper.voiceassistant.manager

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Audio mode (MODE_IN_COMMUNICATION for HFP/SCO headsets) and audio focus for TTS playback.
 * Focus is requested only while the assistant speaks, so other apps (music, navigation) are only
 * ducked/paused for the duration of an answer.
 */
@Singleton
class AudioSessionManager @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var previousMode: Int? = null
    private var focusRequest: AudioFocusRequest? = null

    private val _focusLost = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    /** Emits when another app takes audio focus permanently / transiently while we speak. */
    val focusLost: SharedFlow<Unit> = _focusLost.asSharedFlow()

    @Synchronized
    fun enterCommunicationMode() {
        if (previousMode == null) previousMode = audioManager.mode
        if (audioManager.mode != AudioManager.MODE_IN_COMMUNICATION) {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            Timber.i("Audio mode → IN_COMMUNICATION")
        }
    }

    /**
     * Leaves communication mode. The recorded mode is never a call mode: with the headset "call"
     * (HeadsetCallBridge) Telecom switches to IN_COMMUNICATION before we record the previous mode,
     * and restoring that would keep the phone in call mode (media and speech on the earpiece).
     */
    @Synchronized
    fun restoreMode() {
        val prev = previousMode ?: return
        previousMode = null
        val target = if (prev == AudioManager.MODE_IN_COMMUNICATION || prev == AudioManager.MODE_IN_CALL) AudioManager.MODE_NORMAL else prev
        runCatching { audioManager.mode = target }
        Timber.i("Audio mode restored → %d", target)
    }

    @Synchronized
    fun requestPlaybackFocus(attributes: AudioAttributes): Boolean {
        abandonPlaybackFocus()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    Timber.w("Audio focus lost (%d)", change)
                    _focusLost.tryEmit(Unit)
                }
            }
            .build()
        focusRequest = request
        val granted = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!granted) Timber.w("Audio focus not granted; speaking anyway")
        return granted
    }

    @Synchronized
    fun abandonPlaybackFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }
}
