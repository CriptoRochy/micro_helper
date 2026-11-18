package com.helper.voiceassistant

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.helper.voiceassistant.data.local.AppDatabase
import com.helper.voiceassistant.data.local.MIGRATION_1_2
import com.helper.voiceassistant.data.repository.ConversationRepositoryImpl
import com.helper.voiceassistant.domain.model.ExchangeStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** v1 (history only) → v2 (audio library): the conversation history must survive the upgrade. */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val dbName = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test
    fun migrate1To2KeepsConversationHistory() {
        helper.createDatabase(dbName, 1).apply {
            execSQL("INSERT INTO conversations (timestampMs, userText, responseText, status, errorMessage) VALUES (1000, 'Какая погода?', 'Солнечно.', 'SUCCESS', NULL)")
            execSQL("INSERT INTO conversations (timestampMs, userText, responseText, status, errorMessage) VALUES (2000, '', NULL, 'STT_ERROR', 'Не удалось распознать речь.')")
            close()
        }

        // Validates the migrated schema against app/schemas/.../2.json.
        val db = helper.runMigrationsAndValidate(dbName, 2, true, MIGRATION_1_2)
        db.query("SELECT userText, responseText, status FROM conversations ORDER BY timestampMs").use { c ->
            assertEquals(2, c.count)
            c.moveToFirst()
            assertEquals("Какая погода?", c.getString(0))
            assertEquals("Солнечно.", c.getString(1))
            c.moveToNext()
            assertEquals("STT_ERROR", c.getString(2))
        }
        db.query("SELECT COUNT(*) FROM library_items").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
        db.close()

        // The app's own builder opens the migrated file and reads the history through the repository.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val room = Room.databaseBuilder(context, AppDatabase::class.java, dbName).addMigrations(MIGRATION_1_2).build()
        try {
            runBlocking {
                val repo = ConversationRepositoryImpl(room.conversationDao())
                val all = repo.observeAll().first()
                assertEquals(2, all.size)
                assertEquals("Солнечно.", repo.lastSuccessful(1).single().responseText)
                assertEquals(ExchangeStatus.STT_ERROR, all.first().status)
                assertEquals(0, room.libraryDao().all().size)
            }
        } finally {
            room.close()
            context.deleteDatabase(dbName)
        }
    }
}
