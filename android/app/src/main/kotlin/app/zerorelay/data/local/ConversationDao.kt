package app.zerorelay.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(conversation: ConversationEntity)

    @Query("SELECT * FROM conversations ORDER BY lastMessageTimestamp DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE roomId = :roomId LIMIT 1")
    suspend fun get(roomId: String): ConversationEntity?

    @Query("UPDATE conversations SET unreadCount = 0 WHERE roomId = :roomId")
    suspend fun markRead(roomId: String)

    @Query("DELETE FROM conversations WHERE roomId = :roomId")
    suspend fun deleteByRoom(roomId: String)

    @Query("DELETE FROM conversations")
    suspend fun deleteAll()
}
