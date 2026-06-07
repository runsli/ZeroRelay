package app.zerorelay.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [MessageEntity::class, ConversationEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class MessageDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao

    abstract fun conversationDao(): ConversationDao

    companion object {
        const val DB_NAME = "zero_relay_messages"

        @Volatile
        private var instance: MessageDatabase? = null

        fun get(context: Context): MessageDatabase {
            return instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }
        }

        private fun build(context: Context): MessageDatabase {
            System.loadLibrary("sqlcipher")
            val factory = SupportOpenHelperFactory(MessageDatabasePassphrase.get(context))
            return Room.databaseBuilder(context, MessageDatabase::class.java, DB_NAME)
                .openHelperFactory(factory)
                .addMigrations(MESSAGE_DB_MIGRATION_1_2, MESSAGE_DB_MIGRATION_2_3)
                .build()
        }
    }
}
