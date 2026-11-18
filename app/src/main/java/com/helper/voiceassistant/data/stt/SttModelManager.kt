package com.helper.voiceassistant.data.stt

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import com.helper.voiceassistant.util.tr
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/** Status of the on-device speech model, shown in Settings and on the main screen. */
sealed interface SttModelStatus {
    data object NotDownloaded : SttModelStatus
    /** [progress] is 0..1. [attempt] > 0 while retrying after a dropped connection (resumes where it stopped). */
    data class Downloading(val progress: Float, val downloadedBytes: Long, val totalBytes: Long, val attempt: Int = 0) : SttModelStatus
    data object Ready : SttModelStatus
    data class Failed(val message: String) : SttModelStatus
}

/**
 * GigaAM v3 E2E RNNT (Sber, MIT license) exported for sherpa-onnx as a NeMo transducer, INT8 encoder.
 * Russian speech, output already has punctuation and capitalization.
 *
 * The files (~326 MB) are too large for the APK, so they are downloaded once into
 * `filesDir/models/gigaam-v3/` and verified by SHA-256. Hashes and sizes are pinned to the
 * `model-gigaam-v3` release of the open-source govorun-lite project; the Hugging Face mirrors serve
 * byte-identical files (same SHA-256), so any mirror can continue a partial download of another.
 */
object GigaAmV3 {
    const val DISPLAY_NAME = "GigaAM v3"
    const val DIR = "models/gigaam-v3"

    /** Tried in turn: a dropped connection switches to the next one and resumes with an HTTP Range request. */
    val MIRRORS = listOf(
        "https://github.com/amidexe/govorun-lite/releases/download/model-gigaam-v3",
        "https://huggingface.co/BahamutRU/GigaAM-v3-sherpa-onnx/resolve/main",
        "https://huggingface.co/Smirnov75/GigaAM-v3-sherpa-onnx/resolve/main",
    )

    const val ENCODER = "gigaam_v3_e2e_rnnt_encoder_int8.onnx"
    const val DECODER = "gigaam_v3_e2e_rnnt_decoder.onnx"
    const val JOINER = "gigaam_v3_e2e_rnnt_joint.onnx"
    const val TOKENS = "gigaam_v3_e2e_rnnt_tokens.txt"

    data class ModelFile(val name: String, val sizeBytes: Long, val sha256: String)

    val FILES = listOf(
        ModelFile(TOKENS, 13_353L, "7ddf22514c42c531358182c81446a8159771e9921019f09ae743ea622d40221d"),
        ModelFile(DECODER, 4_600_058L, "781971998e6a355d6a714f6932a30eab295e7ba0d14fd7e0f78c83b87e811860"),
        ModelFile(JOINER, 2_712_896L, "602ff7017a93311aad34df1437c8d7f49911353c13d6eae7a6ee7b041339465c"),
        ModelFile(ENCODER, 318_995_997L, "2cac62d0c270bd128f898f2be1a2d34780d524a6e9483888ebac7b00f97410f1"),
    )

    val TOTAL_BYTES: Long = FILES.sumOf { it.sizeBytes }
}

/**
 * Downloads, verifies and deletes the local STT model. Survives app restarts: files live in filesDir,
 * and a partially downloaded file (`.part`) is resumed instead of starting over. Dropped connections
 * are retried automatically with back-off, alternating between [GigaAmV3.MIRRORS].
 */
@Singleton
class SttModelManager @Inject constructor(
    @ApplicationContext private val context: Context,
    httpClient: OkHttpClient,
) {
    val modelDir: File = File(context.filesDir, GigaAmV3.DIR)

    private val client = httpClient.newBuilder()
        .callTimeout(0, TimeUnit.MILLISECONDS) // large file: no overall limit, only per-read timeouts
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _status = MutableStateFlow<SttModelStatus>(if (isInstalled()) SttModelStatus.Ready else SttModelStatus.NotDownloaded)
    val status: StateFlow<SttModelStatus> = _status.asStateFlow()

    /** Called after the model is deleted so the recognizer can release native memory. */
    var onModelRemoved: (() -> Unit)? = null

    fun file(name: String) = File(modelDir, name)
    private fun part(name: String) = File(modelDir, "$name.part")

    /** Cheap check (sizes only). Hashes are verified right after download. */
    fun isInstalled(): Boolean = GigaAmV3.FILES.all { isComplete(it) }

    @Synchronized
    fun startDownload() {
        if (job?.isActive == true) return
        if (isInstalled()) { _status.value = SttModelStatus.Ready; return }
        job = scope.launch {
            val releaseLocks = acquireLocks()
            try {
                download()
                _status.value = SttModelStatus.Ready
                Timber.i("STT model ready in %s", modelDir)
            } catch (e: CancellationException) {
                _status.value = SttModelStatus.NotDownloaded
                throw e
            } catch (e: Exception) {
                Timber.e(e, "STT model download failed")
                _status.value = SttModelStatus.Failed(
                    if (e is IOException) {
                        tr(
                            "The connection keeps dropping. Tap Download again: it continues from where it stopped.",
                            "Соединение постоянно обрывается. Нажмите «Скачать» ещё раз: загрузка продолжится с того же места.",
                        )
                    } else {
                        e.message ?: tr("Download failed.", "Ошибка скачивания.")
                    },
                )
            } finally {
                releaseLocks()
            }
        }
    }

    @Synchronized
    fun cancelDownload() {
        job?.cancel()
        job = null
    }

    @Synchronized
    fun delete() {
        cancelDownload()
        onModelRemoved?.invoke()
        modelDir.deleteRecursively()
        _status.value = SttModelStatus.NotDownloaded
    }

    private suspend fun download() {
        modelDir.mkdirs()
        publish(0)
        for (mf in GigaAmV3.FILES) {
            var attempt = 0
            while (!isComplete(mf)) {
                val base = GigaAmV3.MIRRORS[attempt % GigaAmV3.MIRRORS.size]
                try {
                    downloadFile(mf, base, attempt)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    attempt++
                    Timber.w("Download of %s from %s interrupted (%s), attempt %d", mf.name, base, e.message, attempt)
                    if (attempt > MAX_ATTEMPTS) throw e
                    publish(attempt)
                    delay(minOf(30_000L, 1_000L shl minOf(attempt, 5)))
                }
            }
        }
    }

    /** Downloads (or resumes) one file from [base]; verifies size and SHA-256 once complete. */
    private suspend fun downloadFile(mf: GigaAmV3.ModelFile, base: String, attempt: Int) {
        val part = part(mf.name)
        if (part.length() > mf.sizeBytes) part.delete()
        val have = part.length()
        if (have < mf.sizeBytes) {
            val request = Request.Builder().url("$base/${mf.name}")
                .apply { if (have > 0) header("Range", "bytes=$have-") }
                .build()
            client.newCall(request).execute().use { response ->
                val append = when (response.code) {
                    206 -> true
                    200 -> false // server ignored Range: start this file over
                    else -> throw IOException("HTTP ${response.code} for ${mf.name}")
                }
                val body = response.body ?: throw IOException("Empty response for ${mf.name}")
                var done = if (append) have else 0L
                body.byteStream().use { input ->
                    FileOutputStream(part, append).use { output ->
                        val buf = ByteArray(256 * 1024)
                        var lastPublish = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            done += n
                            if (done - lastPublish > 1_000_000) { publish(attempt); lastPublish = done }
                        }
                    }
                }
            }
        }
        if (part.length() < mf.sizeBytes) throw IOException("Connection closed early for ${mf.name}")
        if (sha256(part) != mf.sha256) {
            part.delete()
            throw IOException("Checksum mismatch for ${mf.name}, downloading again")
        }
        val target = file(mf.name)
        target.delete()
        if (!part.renameTo(target)) throw IOException("Could not save ${mf.name}")
        publish(attempt)
    }

    private fun isComplete(mf: GigaAmV3.ModelFile) = file(mf.name).let { it.isFile && it.length() == mf.sizeBytes }

    /** Bytes on disk, counting finished files and partial downloads. */
    private fun downloadedBytes(): Long = GigaAmV3.FILES.sumOf { mf ->
        if (isComplete(mf)) mf.sizeBytes else part(mf.name).length().coerceAtMost(mf.sizeBytes)
    }

    private fun publish(attempt: Int) {
        val total = GigaAmV3.TOTAL_BYTES
        val done = downloadedBytes()
        _status.value = SttModelStatus.Downloading((done.toFloat() / total).coerceIn(0f, 1f), done, total, attempt)
    }

    private fun sha256(f: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Keeps CPU and Wi-Fi awake during the long download (the screen may turn off). Returns the release function. */
    private fun acquireLocks(): () -> Unit {
        val wake = runCatching {
            context.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VoiceAssistant:model-download")
                .apply { acquire(2 * 60 * 60 * 1000L) }
        }.getOrNull()
        @Suppress("DEPRECATION")
        val wifi = runCatching {
            context.applicationContext.getSystemService(WifiManager::class.java)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "VoiceAssistant:model-download")
                .apply { acquire() }
        }.getOrNull()
        return {
            runCatching { if (wake?.isHeld == true) wake.release() }
            runCatching { if (wifi?.isHeld == true) wifi.release() }
        }
    }

    companion object {
        private const val MAX_ATTEMPTS = 30
    }
}
