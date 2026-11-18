package com.helper.voiceassistant.data.deepseek

import com.helper.voiceassistant.assistant.ChatClient
import com.helper.voiceassistant.data.net.RetryPolicy
import com.helper.voiceassistant.data.net.executeWithRetry
import com.helper.voiceassistant.domain.model.AssistantFailure
import com.helper.voiceassistant.domain.model.AssistantSettings
import com.helper.voiceassistant.domain.model.ChatMessage
import com.helper.voiceassistant.domain.model.ErrorKind
import com.helper.voiceassistant.domain.repository.SecretKey
import com.helper.voiceassistant.domain.repository.SecretStore
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import com.helper.voiceassistant.util.tr

@Serializable
data class WireMessage(val role: String, val content: String? = null)

@Serializable
data class ThinkingOption(val type: String)

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<WireMessage>,
    val stream: Boolean = false,
    /** DeepSeek: {"thinking":{"type":"enabled"|"disabled"}}. Thinking is on by default server-side. */
    val thinking: ThinkingOption? = null,
)

@Serializable
data class ChatCompletionResponse(
    val id: String? = null,
    val model: String? = null,
    val choices: List<Choice> = emptyList(),
) {
    @Serializable
    data class Choice(
        val index: Int = 0,
        val message: WireMessage? = null,
        @SerialName("finish_reason") val finishReason: String? = null,
    )
}

/**
 * Official DeepSeek Chat Completions API (OpenAI format): POST {baseUrl}/chat/completions.
 * Base URL and model are settings, so a backend proxy can be used for public builds.
 */
@Singleton
class DeepSeekApiClient @Inject constructor(
    private val httpClient: OkHttpClient,
    private val secrets: SecretStore,
    private val json: Json,
) : ChatClient {

    /** Exposed for tests (to keep retries fast). */
    var baseRetryDelayMs: Long = 600

    override suspend fun complete(messages: List<ChatMessage>, settings: AssistantSettings): String {
        val apiKey = secrets.get(SecretKey.DEEPSEEK_API_KEY)
        if (apiKey.isNullOrBlank()) {
            throw AssistantFailure(ErrorKind.INVALID_API_KEY, tr("DeepSeek API key is not set.", "API-ключ DeepSeek не задан."))
        }
        val payload = json.encodeToString(
            ChatCompletionRequest.serializer(),
            ChatCompletionRequest(
                model = settings.deepSeekModel.trim(),
                messages = messages.map { WireMessage(it.role.wire, it.content) },
                stream = false,
                thinking = ThinkingOption(if (settings.deepSeekThinking) "enabled" else "disabled"),
            ),
        )
        val url = settings.deepSeekBaseUrl.trim().trimEnd('/') + "/chat/completions"
        val client = httpClient.newBuilder()
            .callTimeout(settings.requestTimeoutSec.toLong().coerceAtLeast(5), TimeUnit.SECONDS)
            .readTimeout(settings.requestTimeoutSec.toLong().coerceAtLeast(5), TimeUnit.SECONDS)
            .build()
        val started = System.currentTimeMillis()
        val text = executeWithRetry(
            client = client,
            service = "DeepSeek",
            policy = RetryPolicy(settings.maxRetries.coerceIn(0, 5), baseRetryDelayMs),
            buildRequest = {
                Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $apiKey")
                    .header("Accept", "application/json")
                    .post(payload.toRequestBody(JSON))
                    .build()
            },
            parse = { body -> parseContent(body) },
        )
        Timber.i("DeepSeek reply: %d chars in %d ms", text.length, System.currentTimeMillis() - started)
        return text
    }

    private fun parseContent(body: String): String {
        val response = try {
            json.decodeFromString(ChatCompletionResponse.serializer(), body)
        } catch (e: Exception) {
            throw AssistantFailure(ErrorKind.API_ERROR, tr("DeepSeek returned an unreadable response.", "DeepSeek вернул непонятный ответ."), e)
        }
        val content = response.choices.firstOrNull()?.message?.content?.trim()
        if (content.isNullOrEmpty()) throw AssistantFailure(ErrorKind.EMPTY_RESPONSE, tr("DeepSeek returned an empty answer.", "DeepSeek вернул пустой ответ."))
        return content
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
