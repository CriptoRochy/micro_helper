package com.helper.voiceassistant.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMs: Long,
    val userText: String,
    val responseText: String?,
    val status: String,
    val errorMessage: String?,
)

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY timestampMs DESC, id DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun get(id: Long): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE status = 'SUCCESS' ORDER BY timestampMs DESC, id DESC LIMIT :count")
    suspend fun lastSuccessful(count: Int): List<ConversationEntity>

    @Insert
    suspend fun insert(entity: ConversationEntity): Long

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM conversations")
    suspend fun clear()

    @Query("DELETE FROM conversations WHERE id NOT IN (SELECT id FROM conversations ORDER BY timestampMs DESC, id DESC LIMIT :keep)")
    suspend fun trim(keep: Int)

    @Transaction
    suspend fun insertAndTrim(entity: ConversationEntity, keep: Int): Long {
        val id = insert(entity)
        trim(keep)
        return id
    }
}

@Database(entities = [ConversationEntity::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
}
