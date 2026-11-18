package com.helper.voiceassistant.data

import com.helper.voiceassistant.data.deepseek.DeepSeekApiClient
import com.helper.voiceassistant.domain.model.AssistantFailure
import com.helper.voiceassistant.domain.model.AssistantSettings
import com.helper.voiceassistant.domain.model.ChatMessage
import com.helper.voiceassistant.domain.model.ErrorKind
import com.helper.voiceassistant.domain.repository.SecretKey
import com.helper.voiceassistant.domain.repository.SecretStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class FakeSecrets(private val values: MutableMap<SecretKey, String?> = mutableMapOf()) : SecretStore {
    override fun get(key: SecretKey) = values[key]
    override fun put(key: SecretKey, value: String?) { values[key] = value }
}

val testJson = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

class DeepSeekApiClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: DeepSeekApiClient
    private val secrets = FakeSecrets(mutableMapOf(SecretKey.DEEPSEEK_API_KEY to "sk-test-0123456789"))
    private lateinit var settings: AssistantSettings
    private val messages = listOf(
        ChatMessage(ChatMessage.Role.SYSTEM, "You are a voice assistant."),
        ChatMessage(ChatMessage.Role.USER, "Привет! Как дела? \"кавычки\" & emoji 🙂"),
    )

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        client = DeepSeekApiClient(OkHttpClient(), secrets, testJson).apply { baseRetryDelayMs = 1 }
        settings = AssistantSettings(deepSeekBaseUrl = server.url("/").toString(), maxRetries = 2, requestTimeoutSec = 10)
    }

    @After
    fun tearDown() = server.shutdown()

    private fun ok(content: String) = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(
        """{"id":"x","model":"deepseek-flash","choices":[{"index":0,"message":{"role":"assistant","content":${testJson.encodeToString(kotlinx.serialization.serializer<String>(), content)},"reasoning_content":null},"finish_reason":"stop"}],"usage":{"total_tokens":3}}""",
    )

    @Test
    fun `successful request is serialized correctly and parsed`() = runTest {
        server.enqueue(ok("Всё хорошо, спасибо!"))
        val reply = client.complete(messages, settings)
        assertEquals("Всё хорошо, спасибо!", reply)

        val request = server.takeRequest()
        assertEquals("/chat/completions", request.path)
        assertEquals("POST", request.method)
        assertEquals("Bearer sk-test-0123456789", request.getHeader("Authorization"))
        val body = testJson.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("deepseek-flash", body["model"]!!.jsonPrimitive.content)
        assertFalse(body["stream"]!!.jsonPrimitive.boolean)
        assertEquals("disabled", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val sent = body["messages"]!!.jsonArray
        assertEquals("system", sent[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals(messages[1].content, sent[1].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `401 fails as invalid key without retry`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"Authentication Fails"}}"""))
        val e = expectFailure { client.complete(messages, settings) }
        assertEquals(ErrorKind.INVALID_API_KEY, e.kind)
        assertEquals(1, server.requestCount)
        assertFalse("key must not leak into messages", e.userMessage.contains("sk-test"))
    }

    @Test
    fun `429 is retried and then succeeds`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"message":"rate"}}"""))
        server.enqueue(ok("ok"))
        assertEquals("ok", client.complete(messages, settings))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `5xx exhausts limited retries`() = runTest {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(503)) }
        val e = expectFailure { client.complete(messages, settings) }
        assertEquals(ErrorKind.API_ERROR, e.kind)
        assertEquals(3, server.requestCount) // 1 + maxRetries(2)
    }

    @Test
    fun `persistent 429 is reported as rate limited`() = runTest {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(429)) }
        assertEquals(ErrorKind.RATE_LIMITED, expectFailure { client.complete(messages, settings) }.kind)
    }

    @Test
    fun `empty content is an error`() = runTest {
        server.enqueue(ok("   "))
        assertEquals(ErrorKind.EMPTY_RESPONSE, expectFailure { client.complete(messages, settings) }.kind)
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"choices":[]}"""))
        assertEquals(ErrorKind.EMPTY_RESPONSE, expectFailure { client.complete(messages, settings) }.kind)
    }

    @Test
    fun `missing key fails before any request`() = runTest {
        secrets.put(SecretKey.DEEPSEEK_API_KEY, null)
        assertEquals(ErrorKind.INVALID_API_KEY, expectFailure { client.complete(messages, settings) }.kind)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `thinking flag is sent when enabled`() = runTest {
        server.enqueue(ok("x"))
        client.complete(messages, settings.copy(deepSeekThinking = true, deepSeekModel = "deepseek-v4-pro"))
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"enabled\""))
        assertTrue(body.contains("deepseek-v4-pro"))
    }
}

suspend fun expectFailure(block: suspend () -> Unit): AssistantFailure {
    try {
        block()
    } catch (e: AssistantFailure) {
        return e
    }
    fail("Expected AssistantFailure")
    throw IllegalStateException()
}
