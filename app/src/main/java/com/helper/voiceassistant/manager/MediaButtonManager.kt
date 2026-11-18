package com.helper.voiceassistant.manager

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import com.helper.voiceassistant.assistant.HeadsetButtons
import com.helper.voiceassistant.detector.ButtonGesture
import com.helper.voiceassistant.detector.ButtonGestureDetector
import com.helper.voiceassistant.domain.model.HeadsetButtonConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** A raw key seen from the headset, shown by the "detect button" function in Settings. */
data class DetectedKey(val keyCode: Int, val name: String, val atMs: Long)

/**
 * Receives headset / media button presses through a MediaSession and turns them into gestures.
 *
 * Android delivers media buttons to the session of the app that played audio most recently, so on
 * activation (and after every spoken answer, see [reclaim]) a few milliseconds of silence are played.
 * Wired headsets send KEYCODE_HEADSETHOOK; Bluetooth headsets usually send play/pause over AVRCP.
 */
@Singleton
class MediaButtonManager @Inject constructor(
    @ApplicationContext private val context: Context,
) : HeadsetButtons {

    private val main = Handler(Looper.getMainLooper())
    private val detector = ButtonGestureDetector()
    private var session: MediaSession? = null
    private val owners = mutableSetOf<String>()
    @Volatile private var config = HeadsetButtonConfig()

    private val _gestures = MutableSharedFlow<ButtonGesture>(extraBufferCapacity = 16)
    override val gestures: SharedFlow<ButtonGesture> = _gestures.asSharedFlow()

    private val _lastKey = MutableStateFlow<DetectedKey?>(null)
    /** Every key press seen, also ones not assigned to an action (for the "detect button" UI). */
    val lastKey: StateFlow<DetectedKey?> = _lastKey.asStateFlow()

    /** While true, presses are only reported in [lastKey] and never trigger actions. */
    @Volatile var learning = false

    private val tick = Runnable { onTick() }

    override fun acquire(owner: String) = main.post {
        owners += owner
        if (session == null) open()
        reclaimNow()
    }.let { }

    override fun release(owner: String) = main.post {
        owners -= owner
        if (owners.isEmpty()) close()
    }.let { }

    override fun updateConfig(config: HeadsetButtonConfig) {
        this.config = config
        main.post { detector.config = config }
    }

    override fun reclaim() {
        main.post { reclaimNow() }
        // Again once the call / voice link has fully closed (Telecom may still hold the buttons briefly).
        main.postDelayed({ session?.isActive = true; reclaimNow() }, 1_200)
    }

    private fun open() {
        val s = MediaSession(context, "VoiceAssistant")
        s.setCallback(object : MediaSession.Callback() {
            override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                val event = keyEvent(mediaButtonIntent) ?: return super.onMediaButtonEvent(mediaButtonIntent)
                return handleKey(event)
            }
        }, main)
        s.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP,
                )
                .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                .build(),
        )
        s.isActive = true
        session = s
        Timber.i("Media button session active")
    }

    private fun close() {
        main.removeCallbacks(tick)
        detector.reset()
        session?.let { it.isActive = false; it.release() }
        session = null
        Timber.i("Media button session released")
    }

    /**
     * Keys that reach the app window: a Bluetooth / USB keyboard, a presenter or a "selfie" remote.
     * They only arrive while the app is on screen (Android delivers non-media keys to the focused
     * window only). Media keys are left to the media session, so they are not handled twice.
     */
    fun onWindowKey(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (isMediaKey(code) || code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_HOME) return false
        if (session == null) return false // the assistant is not running and nothing is being detected
        if (!learning && (!config.enabled || code !in config.keyCodes)) return false
        return handleKey(event)
    }

    private fun handleKey(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            _lastKey.value = DetectedKey(code, KeyEvent.keyCodeToString(code), SystemClock.uptimeMillis())
            Timber.d("Key down: %s (%d)", KeyEvent.keyCodeToString(code), code)
        }
        if (learning) return true
        val cfg = config
        if (!cfg.enabled || code !in cfg.keyCodes) return false
        val gesture = when (event.action) {
            KeyEvent.ACTION_DOWN -> detector.onDown(code, event.eventTime)
            KeyEvent.ACTION_UP -> detector.onUp(code, event.eventTime)
            else -> null
        }
        gesture?.let(::emit)
        scheduleTick()
        return true
    }

    private fun onTick() {
        detector.onTick(SystemClock.uptimeMillis())?.let(::emit)
        scheduleTick()
    }

    private fun scheduleTick() {
        main.removeCallbacks(tick)
        detector.nextDeadline()?.let { main.postAtTime(tick, it) }
    }

    private fun emit(g: ButtonGesture) {
        Timber.i("Headset button: %s (%s)", g.type, KeyEvent.keyCodeToString(g.keyCode))
        _gestures.tryEmit(g)
    }

    /** Plays ~100 ms of silence so that this app becomes the media-button receiver. */
    private fun reclaimNow() {
        if (session == null) return
        Thread({
            runCatching {
                val rate = 16_000
                val samples = ShortArray(rate / 10)
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                    )
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(samples.size * 2)
                    .build()
                track.write(samples, 0, samples.size)
                track.play()
                Thread.sleep(150)
                track.stop()
                track.release()
            }.onFailure { Timber.w(it, "Silent playback for media-button focus failed") }
        }, "media-button-claim").start()
    }

    companion object {
        /** Keys delivered through the media session, also in the background and with the screen off. */
        fun isMediaKey(code: Int): Boolean =
            code == KeyEvent.KEYCODE_HEADSETHOOK || KeyEvent.keyCodeToString(code).startsWith("KEYCODE_MEDIA_")
    }

    @Suppress("DEPRECATION")
    private fun keyEvent(intent: Intent): KeyEvent? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
}
