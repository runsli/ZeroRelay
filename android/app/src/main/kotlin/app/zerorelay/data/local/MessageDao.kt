package app.zerorelay.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE roomId = :roomId ORDER BY timestamp ASC")
    suspend fun getByRoom(roomId: String): List<MessageEntity>

    @Query("DELETE FROM messages WHERE roomId = :roomId")
    suspend fun deleteByRoom(roomId: String)

    @Query("DELETE FROM messages")
    suspend fun deleteAll()

    @Query("SELECT DISTINCT roomId FROM messages")
    suspend fun distinctRoomIds(): List<String>

    @Query("SELECT * FROM messages WHERE roomId = :roomId ORDER BY timestamp DESC LIMIT 1")
    suspend fun latestByRoom(roomId: String): MessageEntity?
}
