package com.helper.voiceassistant.domain.repository

import com.helper.voiceassistant.domain.model.AssistantSettings
import com.helper.voiceassistant.domain.model.ConversationEntry
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    val settings: Flow<AssistantSettings>
    suspend fun current(): AssistantSettings
    suspend fun update(transform: (AssistantSettings) -> AssistantSettings)
}

interface ConversationRepository {
    fun observeAll(): Flow<List<ConversationEntry>>
    suspend fun get(id: Long): ConversationEntry?
    suspend fun add(entry: ConversationEntry, limit: Int): Long
    suspend fun lastSuccessful(count: Int): List<ConversationEntry>
    suspend fun delete(id: Long)
    suspend fun clear()
}

enum class SecretKey { DEEPSEEK_API_KEY }

/** Sensitive values (API keys) encrypted with an Android Keystore key. */
interface SecretStore {
    fun get(key: SecretKey): String?
    fun put(key: SecretKey, value: String?)
    fun has(key: SecretKey): Boolean = !get(key).isNullOrBlank()
}
