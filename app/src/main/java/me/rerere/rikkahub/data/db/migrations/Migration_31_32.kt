package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration from v31 to v32: adds memory_file, memory_consolidation, media_creation tables,
 * translation_history, and config column on ConversationEntity.
 */
class Migration_31_32 : AutoMigrationSpec {
    override fun onPostMigrate(db: SupportSQLiteDatabase) {
        // Mark existing conversations as consolidated so upgrading users don't process their entire history at once
        db.execSQL(
            "INSERT OR IGNORE INTO memory_consolidation (conversation_id, consolidated_at) " +
                "SELECT id, update_at FROM ConversationEntity"
        )
    }
}
