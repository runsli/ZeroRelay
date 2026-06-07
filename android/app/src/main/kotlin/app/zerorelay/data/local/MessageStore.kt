package app.zerorelay.data.local

import android.content.Context
import app.zerorelay.data.model.ChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MessageStore(context: Context) {
    private val dao = MessageDatabase.get(context.applicationContext).messageDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun persist(message: ChatMessage) {
        if (message.roomId.isBlank()) return
        scope.launch {
            dao.upsert(message.toEntity())
        }
    }

    suspend fun loadForRoom(roomId: String): List<ChatMessage> =
        withContext(Dispatchers.IO) {
            dao.getByRoom(roomId).map { it.toChatMessage() }
        }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        dao.deleteAll()
    }

    suspend fun deleteByRoom(roomId: String) = withContext(Dispatchers.IO) {
        dao.deleteByRoom(roomId)
    }
}
