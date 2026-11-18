package com.helper.voiceassistant.data.stt

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import com.helper.voiceassistant.util.tr

/** Status of the on-device speech model, shown in Settings and on the main screen. */
sealed interface SttModelStatus {
    data object NotDownloaded : SttModelStatus
    /** [progress] is 0..1. */
    data class Downloading(val progress: Float, val downloadedBytes: Long, val totalBytes: Long) : SttModelStatus
    data object Ready : SttModelStatus
    data class Failed(val message: String) : SttModelStatus
}

/**
 * GigaAM v3 E2E RNNT (Sber, MIT license) exported for sherpa-onnx as a NeMo transducer, INT8 encoder.
 * Russian speech, output already has punctuation and capitalization.
 *
 * The files (~326 MB) are too large for the APK, so they are downloaded once into
 * `filesDir/models/gigaam-v3/` and verified by SHA-256. Hashes and sizes are pinned to the
 * `model-gigaam-v3` release of the open-source govorun-lite project.
 */
object GigaAmV3 {
    const val DISPLAY_NAME = "GigaAM v3"
    const val BASE_URL = "https://github.com/amidexe/govorun-lite/releases/download/model-gigaam-v3"
    const val DIR = "models/gigaam-v3"

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

/** Downloads, verifies and deletes the local STT model. Survives app restarts: files live in filesDir. */
@Singleton
class SttModelManager @Inject constructor(
    @ApplicationContext context: Context,
    httpClient: OkHttpClient,
) {
    val modelDir: File = File(context.filesDir, GigaAmV3.DIR)

    private val client = httpClient.newBuilder()
        .callTimeout(0, TimeUnit.MILLISECONDS) // large file: no overall limit, only per-read timeouts
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _status = MutableStateFlow<SttModelStatus>(if (isInstalled()) SttModelStatus.Ready else SttModelStatus.NotDownloaded)
    val status: StateFlow<SttModelStatus> = _status.asStateFlow()

    /** Called after the model is deleted so the recognizer can release native memory. */
    var onModelRemoved: (() -> Unit)? = null

    fun file(name: String) = File(modelDir, name)

    /** Cheap check (sizes only). Hashes are verified right after download. */
    fun isInstalled(): Boolean = GigaAmV3.FILES.all { file(it.name).let { f -> f.isFile && f.length() == it.sizeBytes } }

    @Synchronized
    fun startDownload() {
        if (job?.isActive == true) return
        if (isInstalled()) { _status.value = SttModelStatus.Ready; return }
        job = scope.launch {
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
                    when (e) {
                        is IOException -> tr("Download failed: check the internet connection and try again.", "Ошибка скачивания: проверьте интернет и попробуйте ещё раз.")
                        else -> e.message ?: tr("Download failed.", "Ошибка скачивания.")
                    },
                )
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
        var done = GigaAmV3.FILES.filter { isComplete(it) }.sumOf { it.sizeBytes }
        publish(done)
        for (mf in GigaAmV3.FILES) {
            if (isComplete(mf)) continue
            val target = file(mf.name)
            val part = File(modelDir, "${mf.name}.part")
            part.delete()
            val digest = MessageDigest.getInstance("SHA-256")
            val request = Request.Builder().url("${GigaAmV3.BASE_URL}/${mf.name}").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code} for ${mf.name}")
                val body = response.body ?: throw IOException("Empty response for ${mf.name}")
                body.byteStream().use { input ->
                    part.outputStream().use { output ->
                        val buf = ByteArray(256 * 1024)
                        var lastPublish = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            done += n
                            if (done - lastPublish > 1_000_000) { publish(done); lastPublish = done }
                        }
                    }
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            if (part.length() != mf.sizeBytes || hash != mf.sha256) {
                part.delete()
                throw IllegalStateException(tr("Downloaded ${mf.name} is corrupted (checksum mismatch). Please try again.", "Скачанный файл ${mf.name} повреждён (не совпала контрольная сумма). Попробуйте ещё раз."))
            }
            target.delete()
            if (!part.renameTo(target)) throw IOException("Could not save ${mf.name}")
            publish(done)
        }
    }

    private fun isComplete(mf: GigaAmV3.ModelFile) = file(mf.name).let { it.isFile && it.length() == mf.sizeBytes }

    private fun publish(done: Long) {
        val total = GigaAmV3.TOTAL_BYTES
        _status.value = SttModelStatus.Downloading((done.toFloat() / total).coerceIn(0f, 1f), done, total)
    }
}
