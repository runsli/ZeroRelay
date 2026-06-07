package app.zerorelay.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MESSAGE_DB_MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS conversations (
                roomId TEXT NOT NULL PRIMARY KEY,
                displayName TEXT NOT NULL,
                peerContactId TEXT NOT NULL,
                kind TEXT NOT NULL,
                lastMessagePreview TEXT NOT NULL,
                lastMessageTimestamp INTEGER NOT NULL,
                lastMessageIsMine INTEGER NOT NULL DEFAULT 0,
                unreadCount INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
    }
}
