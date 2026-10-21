package me.rerere.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

private const val TAG = "Migration_31_32"

/**
 * Migration from v31 to v32: adds memory_file, memory_consolidation, media_creation tables,
 * translation_history, and config column on ConversationEntity.
 *
 * Hand-written rather than an AutoMigration so Room does not require 32.json for subsequent
 * manual migrations, and to make table/column creation idempotent across backup imports.
 */
val Migration_31_32 = object : Migration(31, 32) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "migrate: start migrate from 31 to 32")
        db.beginTransaction()
        try {
            if (!hasColumn(db, "ConversationEntity", "config")) {
                db.execSQL("ALTER TABLE `ConversationEntity` ADD COLUMN `config` TEXT NOT NULL DEFAULT ''")
            }
            if (!hasColumn(db, "workspaces", "type")) {
                db.execSQL("ALTER TABLE `workspaces` ADD COLUMN `type` TEXT NOT NULL DEFAULT 'LOCAL'")
            }
            if (!hasColumn(db, "workspaces", "ssh_config")) {
                db.execSQL("ALTER TABLE `workspaces` ADD COLUMN `ssh_config` TEXT NOT NULL DEFAULT ''")
            }
            db.execSQL("CREATE TABLE IF NOT EXISTS `memory_file` (`name` TEXT NOT NULL, `content` TEXT NOT NULL, `update_at` INTEGER NOT NULL, PRIMARY KEY(`name`))")
            db.execSQL("CREATE TABLE IF NOT EXISTS `memory_consolidation` (`conversation_id` TEXT NOT NULL, `consolidated_at` INTEGER NOT NULL, PRIMARY KEY(`conversation_id`))")
            db.execSQL("CREATE TABLE IF NOT EXISTS `media_creation_session` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            db.execSQL("CREATE TABLE IF NOT EXISTS `media_creation_node` (`id` TEXT NOT NULL, `session_id` TEXT NOT NULL, `prompt` TEXT NOT NULL, `model_id` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            db.execSQL("CREATE TABLE IF NOT EXISTS `media_creation_record` (`id` TEXT NOT NULL, `session_id` TEXT NOT NULL, `node_id` TEXT, `parent_id` TEXT, `provider_id` TEXT NOT NULL, `model_id` TEXT NOT NULL, `prompt` TEXT NOT NULL, `params` TEXT NOT NULL, `status` TEXT NOT NULL, `error_code` TEXT, `error_message` TEXT, `metadata` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            db.execSQL("CREATE TABLE IF NOT EXISTS `translation_history` (`id` TEXT NOT NULL, `source_text` TEXT NOT NULL, `target_text` TEXT NOT NULL, `source_lang` TEXT NOT NULL, `target_lang` TEXT NOT NULL, `provider` TEXT NOT NULL, `model` TEXT NOT NULL, `created_at` INTEGER NOT NULL, PRIMARY KEY(`id`))")

            // Mark existing conversations as consolidated so upgrading users don't process their entire history at once
            db.execSQL(
                "INSERT OR IGNORE INTO memory_consolidation (conversation_id, consolidated_at) " +
                    "SELECT id, update_at FROM ConversationEntity"
            )
            db.setTransactionSuccessful()
            Log.i(TAG, "migrate: migrate from 31 to 32 success")
        } finally {
            db.endTransaction()
        }
    }
}

private fun hasColumn(db: SupportSQLiteDatabase, table: String, column: String): Boolean {
    db.query("PRAGMA table_info(`$table`)").use { cursor ->
        val nameIndex = cursor.getColumnIndex("name")
        if (nameIndex < 0) return false
        while (cursor.moveToNext()) {
            if (cursor.getString(nameIndex) == column) return true
        }
    }
    return false
}
