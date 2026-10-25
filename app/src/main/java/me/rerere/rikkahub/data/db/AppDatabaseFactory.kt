package me.rerere.rikkahub.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.fts.createMessageFtsTables
import me.rerere.rikkahub.data.db.migrations.Migration_6_7
import me.rerere.rikkahub.data.db.migrations.Migration_11_12
import me.rerere.rikkahub.data.db.migrations.Migration_13_14
import me.rerere.rikkahub.data.db.migrations.Migration_14_15
import me.rerere.rikkahub.data.db.migrations.Migration_15_16
import me.rerere.rikkahub.data.db.migrations.Migration_23_24
import me.rerere.rikkahub.data.db.migrations.Migration_30_31
import me.rerere.rikkahub.data.db.migrations.Migration_31_32
import me.rerere.rikkahub.data.db.migrations.Migration_32_33
import java.io.File

/** Shared schema, migrations and extensions for the app and staged backup validation. */
internal object AppDatabaseFactory {
    fun create(context: Context, name: String = SQLiteConfiguration.DATABASE_NAME): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, name)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addMigrations(Migration_6_7, Migration_11_12, Migration_13_14, Migration_14_15, Migration_15_16, Migration_23_24, Migration_30_31, Migration_31_32, Migration_32_33)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    // Best-effort FTS setup: remove legacy ~14MB jieba dictionary directory
                    // and create FTS5 + message_fts_rowid tables without crashing DB open on error.
                    try {
                        File(context.filesDir, "simple_dict").takeIf { it.exists() }?.deleteRecursively()
                    } catch (e: Exception) {
                        android.util.Log.e("DataSourceModule", "onOpen: simple_dict cleanup failed", e)
                    }
                    try {
                        createMessageFtsTables(db)
                    } catch (e: Exception) {
                        android.util.Log.e("DataSourceModule", "onOpen: message_fts table creation failed", e)
                    }
                }
            })
            .openHelperFactory(SQLiteConfiguration.openHelperFactory(context))
            .build()
}
