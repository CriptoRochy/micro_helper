package com.helper.voiceassistant

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.helper.voiceassistant.data.local.AppDatabase
import com.helper.voiceassistant.data.repository.ConversationRepositoryImpl
import com.helper.voiceassistant.domain.model.ConversationEntry
import com.helper.voiceassistant.domain.model.ExchangeStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Instrumented: real Room (in-memory) on a device/emulator. Run with ./gradlew connectedDebugAndroidTest */
@RunWith(AndroidJUnit4::class)
class ConversationDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: ConversationRepositoryImpl

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        repo = ConversationRepositoryImpl(db.conversationDao())
    }

    @After
    fun tearDown() = db.close()

    private fun entry(t: Long, status: ExchangeStatus = ExchangeStatus.SUCCESS) =
        ConversationEntry(timestampMs = t, userText = "вопрос $t", responseText = if (status == ExchangeStatus.SUCCESS) "ответ $t" else null, status = status, errorMessage = null)

    @Test
    fun insertTrimAndQuery() = runTest {
        (1L..25L).forEach { repo.add(entry(it), limit = 20) }
        val all = repo.observeAll().first()
        assertEquals(20, all.size)
        assertEquals(25L, all.first().timestampMs)
        assertEquals("ответ 25", repo.lastSuccessful(1).single().responseText)
    }

    @Test
    fun failedExchangesAreStoredButNotUsedAsContext() = runTest {
        repo.add(entry(1), 20)
        repo.add(entry(2, ExchangeStatus.API_ERROR), 20)
        assertEquals(2, repo.observeAll().first().size)
        assertEquals(1L, repo.lastSuccessful(5).single().timestampMs)
    }

    @Test
    fun deleteAndClear() = runTest {
        val id = repo.add(entry(1), 20)
        repo.add(entry(2), 20)
        repo.delete(id)
        assertNull(repo.get(id))
        repo.clear()
        assertEquals(0, repo.observeAll().first().size)
    }
}
