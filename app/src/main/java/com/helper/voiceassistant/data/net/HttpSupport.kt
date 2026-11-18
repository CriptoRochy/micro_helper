package com.helper.voiceassistant.data.net

import com.helper.voiceassistant.domain.model.AssistantFailure
import com.helper.voiceassistant.domain.model.ErrorKind
import com.helper.voiceassistant.util.Redactor
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import timber.log.Timber
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import com.helper.voiceassistant.util.tr

/** Suspends on an OkHttp call; coroutine cancellation cancels the HTTP call. */
suspend fun OkHttpClient.await(request: Request): Response = suspendCancellableCoroutine { cont ->
    val call = newCall(request)
    cont.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            cont.resume(response)
        }
    })
}

data class RetryPolicy(
    val maxRetries: Int,
    val baseDelayMs: Long = 600,
    val maxDelayMs: Long = 8_000,
)

/**
 * Executes a request with limited retries and exponential backoff.
 *  - IOException, HTTP 429 and 5xx are retried (429 honours Retry-After).
 *  - 401/403 fail immediately as INVALID_API_KEY; other 4xx fail immediately as API_ERROR.
 * The request is rebuilt for each attempt (bodies are one-shot). Logged messages are redacted.
 */
suspend fun <T> executeWithRetry(
    client: OkHttpClient,
    service: String,
    policy: RetryPolicy,
    buildRequest: () -> Request,
    parse: (String) -> T,
): T {
    var attempt = 0
    while (true) {
        val response = try {
            client.await(buildRequest())
        } catch (e: IOException) {
            Timber.w("%s network error (attempt %d): %s", service, attempt + 1, Redactor.redact(e.message ?: e.javaClass.simpleName))
            if (attempt < policy.maxRetries) {
                delay(backoff(policy, attempt)); attempt++; continue
            }
            throw AssistantFailure(ErrorKind.NETWORK, tr("$service is unreachable. Check the internet connection.", "$service недоступен. Проверьте подключение к интернету."), e)
        }

        val code = response.code
        val retryAfter = response.header("Retry-After")?.toLongOrNull()
        val body = try {
            response.use { it.body?.string().orEmpty() }
        } catch (e: IOException) {
            if (attempt < policy.maxRetries) { delay(backoff(policy, attempt)); attempt++; continue }
            throw AssistantFailure(ErrorKind.NETWORK, tr("Connection to $service was interrupted.", "Соединение с $service прервано."), e)
        }

        if (code in 200..299) return parse(body)

        val serverMessage = extractErrorMessage(body)
        Timber.w("%s HTTP %d (attempt %d): %s", service, code, attempt + 1, Redactor.redact(serverMessage ?: ""))
        when {
            code == 401 || code == 403 ->
                throw AssistantFailure(ErrorKind.INVALID_API_KEY, tr("$service rejected the API key (HTTP $code).", "$service отклонил API-ключ (HTTP $code)."), httpCode = code)
            code == 429 || code >= 500 -> {
                if (attempt < policy.maxRetries) {
                    val wait = retryAfter?.let { (it * 1000).coerceAtMost(policy.maxDelayMs) } ?: backoff(policy, attempt)
                    delay(wait); attempt++; continue
                }
                if (code == 429) throw AssistantFailure(ErrorKind.RATE_LIMITED, tr("$service rate limit reached. Try again later.", "Превышен лимит запросов $service. Попробуйте позже."), httpCode = code)
                throw AssistantFailure(ErrorKind.API_ERROR, tr("$service is temporarily unavailable (HTTP $code).", "$service временно недоступен (HTTP $code)."), httpCode = code)
            }
            code == 402 ->
                throw AssistantFailure(ErrorKind.API_ERROR, tr("$service: insufficient account balance (HTTP 402).", "$service: недостаточно средств на балансе (HTTP 402)."), httpCode = code)
            else -> {
                val detail = serverMessage?.let { ": " + Redactor.redact(it).take(160) } ?: ""
                throw AssistantFailure(ErrorKind.API_ERROR, tr("$service request failed (HTTP $code)$detail", "Ошибка запроса к $service (HTTP $code)$detail"), httpCode = code)
            }
        }
    }
}

private fun backoff(policy: RetryPolicy, attempt: Int): Long =
    (policy.baseDelayMs shl attempt.coerceAtMost(10)).coerceAtMost(policy.maxDelayMs)

private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

/** Pulls `error.message` (OpenAI/DeepSeek style) out of an error body, if present. */
fun extractErrorMessage(body: String): String? = runCatching {
    val root = lenientJson.parseToJsonElement(body).jsonObject
    val error = root["error"]
    when {
        error == null -> root["message"]?.jsonPrimitive?.content
        error is kotlinx.serialization.json.JsonPrimitive -> error.content
        else -> error.jsonObject["message"]?.jsonPrimitive?.content
    }
}.getOrNull() ?: body.takeIf { it.isNotBlank() && it.length < 300 }
