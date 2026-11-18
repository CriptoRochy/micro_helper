package com.helper.voiceassistant.data.local

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/** An audio library entry. [id] is a permanent UUID; [fileName] is the private copy in filesDir/library. */
@Entity(tableName = "library_items")
data class LibraryItemEntity(
    @PrimaryKey val id: String,
    val title: String,
    val fileName: String,
    val originalName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

/** A voice phrase bound to an entry. [normalized] is PhraseNormalizer.normalize(phrase). */
@Entity(
    tableName = "library_phrases",
    foreignKeys = [
        ForeignKey(entity = LibraryItemEntity::class, parentColumns = ["id"], childColumns = ["itemId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("itemId"), Index("normalized")],
)
data class LibraryPhraseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemId: String,
    val phrase: String,
    val normalized: String,
    val position: Int,
)

data class LibraryItemWithPhrases(
    @Embedded val item: LibraryItemEntity,
    @Relation(parentColumn = "id", entityColumn = "itemId") val phrases: List<LibraryPhraseEntity>,
)

@Dao
interface LibraryDao {
    @Transaction
    @Query("SELECT * FROM library_items ORDER BY createdAtMs, id")
    fun observeAll(): Flow<List<LibraryItemWithPhrases>>

    @Transaction
    @Query("SELECT * FROM library_items ORDER BY createdAtMs, id")
    suspend fun all(): List<LibraryItemWithPhrases>

    @Transaction
    @Query("SELECT * FROM library_items WHERE id = :id")
    suspend fun get(id: String): LibraryItemWithPhrases?

    @Query("SELECT fileName FROM library_items")
    suspend fun fileNames(): List<String>

    @Insert
    suspend fun insertItem(item: LibraryItemEntity)

    @Update
    suspend fun updateItem(item: LibraryItemEntity)

    @Insert
    suspend fun insertPhrases(phrases: List<LibraryPhraseEntity>)

    @Query("DELETE FROM library_phrases WHERE itemId = :itemId")
    suspend fun deletePhrases(itemId: String)

    @Query("DELETE FROM library_items WHERE id = :id")
    suspend fun deleteItem(id: String)

    /** Inserts or updates the entry and replaces its phrases atomically. */
    @Transaction
    suspend fun save(item: LibraryItemEntity, phrases: List<LibraryPhraseEntity>, isNew: Boolean) {
        if (isNew) insertItem(item) else updateItem(item)
        deletePhrases(item.id)
        if (phrases.isNotEmpty()) insertPhrases(phrases)
    }

    @Transaction
    suspend fun delete(id: String) {
        deletePhrases(id)
        deleteItem(id)
    }
}

/** v1 → v2: adds the audio library. Conversation history is left untouched. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `library_items` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `fileName` TEXT NOT NULL, " +
                "`originalName` TEXT NOT NULL, `mimeType` TEXT NOT NULL, `sizeBytes` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, " +
                "`createdAtMs` INTEGER NOT NULL, `updatedAtMs` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `library_phrases` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `itemId` TEXT NOT NULL, " +
                "`phrase` TEXT NOT NULL, `normalized` TEXT NOT NULL, `position` INTEGER NOT NULL, " +
                "FOREIGN KEY(`itemId`) REFERENCES `library_items`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_library_phrases_itemId` ON `library_phrases` (`itemId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_library_phrases_normalized` ON `library_phrases` (`normalized`)")
    }
}
