package com.helper.voiceassistant.data.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import com.helper.voiceassistant.R
import com.helper.voiceassistant.assistant.CuePlayer
import com.helper.voiceassistant.assistant.RecordingCue
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plays the recording start / end sounds (res/raw, 16-bit PCM WAV) through the output the assistant
 * uses at that moment: the headset call link while the headset microphone is open, media otherwise.
 * [play] returns once the whole sound has left the player, so a recording started afterwards does not
 * contain it.
 */
@Singleton
class RecordingCuePlayer @Inject constructor(
    @ApplicationContext private val context: Context,
) : CuePlayer {

    private class Pcm(val data: ByteArray, val sampleRate: Int, val channels: Int) {
        val frames: Int get() = data.size / (2 * channels)
        val durationMs: Long get() = frames * 1000L / sampleRate
    }

    private val cache = HashMap<RecordingCue, Pcm>()
    @Volatile private var track: AudioTrack? = null

    override suspend fun play(cue: RecordingCue, communicationRoute: Boolean) {
        val pcm = withContext(Dispatchers.IO) { load(cue) } ?: return
        val usage = if (communicationRoute) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA
        val t = try {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(pcm.sampleRate)
                        .setChannelMask(if (pcm.channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.data.size)
                .build()
        } catch (e: Exception) {
            Timber.w(e, "Cue %s: no audio track", cue)
            return
        }
        try {
            if (t.write(pcm.data, 0, pcm.data.size) < 0) return
            t.setVolume(VOLUME)
            stop()
            track = t
            t.play()
            Timber.d("Cue %s (%d ms, %s)", cue, pcm.durationMs, if (communicationRoute) "call link" else "media")
            val deadline = SystemClock.uptimeMillis() + pcm.durationMs + 1500
            while (t.playState == AudioTrack.PLAYSTATE_PLAYING && t.playbackHeadPosition < pcm.frames && SystemClock.uptimeMillis() < deadline) {
                delay(20)
            }
            delay(OUTPUT_TAIL_MS) // what is still in the output buffers
        } catch (e: IllegalStateException) {
            Timber.w(e, "Cue %s failed", cue)
        } finally {
            if (track === t) track = null
            runCatching { t.stop() }
            t.release()
        }
    }

    override fun stop() {
        track?.let { runCatching { it.pause(); it.flush() } }
    }

    private fun load(cue: RecordingCue): Pcm? = synchronized(cache) {
        cache[cue] ?: runCatching { parseWav(context.resources.openRawResource(rawId(cue)).use { it.readBytes() }) }
            .onFailure { Timber.e(it, "Cue %s could not be loaded", cue) }
            .getOrNull()
            ?.also { cache[cue] = it }
    }

    private fun rawId(cue: RecordingCue) = when (cue) {
        RecordingCue.START -> R.raw.cue_record_start
        RecordingCue.END -> R.raw.cue_record_end
    }

    companion object {
        private const val VOLUME = 0.7f
        private const val OUTPUT_TAIL_MS = 150L

        /** 16-bit PCM WAV → raw samples; other formats are rejected. */
        private fun parseWav(b: ByteArray): Pcm {
            require(b.size > 44 && String(b, 0, 4) == "RIFF" && String(b, 8, 4) == "WAVE") { "not a WAV file" }
            var i = 12
            var rate = 0
            var channels = 0
            while (i + 8 <= b.size) {
                val id = String(b, i, 4)
                val size = ByteBuffer.wrap(b, i + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                val body = i + 8
                when (id) {
                    "fmt " -> {
                        val f = ByteBuffer.wrap(b, body, 16).order(ByteOrder.LITTLE_ENDIAN)
                        require(f.short.toInt() == 1) { "not PCM" }
                        channels = f.short.toInt()
                        rate = f.int
                        f.int; f.short
                        require(f.short.toInt() == 16) { "not 16-bit" }
                    }
                    "data" -> {
                        require(rate > 0 && channels in 1..2) { "no format chunk" }
                        val len = minOf(size, b.size - body)
                        return Pcm(b.copyOfRange(body, body + len - len % (2 * channels)), rate, channels)
                    }
                }
                i = body + size + (size and 1)
            }
            error("no data chunk")
        }
    }
}
