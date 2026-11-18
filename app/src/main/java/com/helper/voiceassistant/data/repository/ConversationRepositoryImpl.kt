package com.helper.voiceassistant.data.repository

import com.helper.voiceassistant.data.local.ConversationDao
import com.helper.voiceassistant.data.local.ConversationEntity
import com.helper.voiceassistant.domain.model.ConversationEntry
import com.helper.voiceassistant.domain.model.ExchangeStatus
import com.helper.voiceassistant.domain.repository.ConversationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConversationRepositoryImpl @Inject constructor(
    private val dao: ConversationDao,
) : ConversationRepository {

    override fun observeAll(): Flow<List<ConversationEntry>> = dao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun get(id: Long): ConversationEntry? = dao.get(id)?.toDomain()

    override suspend fun add(entry: ConversationEntry, limit: Int): Long =
        dao.insertAndTrim(entry.toEntity(), limit.coerceAtLeast(1))

    override suspend fun lastSuccessful(count: Int): List<ConversationEntry> =
        if (count <= 0) emptyList() else dao.lastSuccessful(count).map { it.toDomain() }

    override suspend fun delete(id: Long) = dao.delete(id)

    override suspend fun clear() = dao.clear()
}

internal fun ConversationEntity.toDomain() = ConversationEntry(
    id = id,
    timestampMs = timestampMs,
    userText = userText,
    responseText = responseText,
    status = runCatching { ExchangeStatus.valueOf(status) }.getOrDefault(ExchangeStatus.API_ERROR),
    errorMessage = errorMessage,
)

internal fun ConversationEntry.toEntity() = ConversationEntity(
    id = id,
    timestampMs = timestampMs,
    userText = userText,
    responseText = responseText,
    status = status.name,
    errorMessage = errorMessage,
)
