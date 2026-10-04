package com.pengshi.words.database

import androidx.room.Database
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        WordEntity::class,
        WordSenseEntity::class,
        ExampleSentenceEntity::class,
        DeckEntity::class,
        DeckWordEntity::class,
        CardStateEntity::class,
        DailyPlanEntity::class,
        DailyPlanItemEntity::class,
        IntradayReviewEventEntity::class,
        ReviewLogEntity::class,
        SyncDeviceEntity::class,
        SyncMetadataEntity::class,
        SyncEventEntity::class,
    ],
    version = 7,
    exportSchema = true,
)
@TypeConverters(DatabaseConverters::class)
abstract class PengshiDatabase : RoomDatabase() {
    abstract fun wordDao(): WordDao
    abstract fun wordSenseDao(): WordSenseDao
    abstract fun exampleSentenceDao(): ExampleSentenceDao
    abstract fun deckDao(): DeckDao
    abstract fun cardStateDao(): CardStateDao
    abstract fun dailyPlanDao(): DailyPlanDao
    abstract fun reviewLogDao(): ReviewLogDao
    abstract fun syncDao(): SyncDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE words ADD COLUMN frequencyRank INTEGER")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS sync_devices (
                        deviceId TEXT NOT NULL,
                        nextSequence INTEGER NOT NULL,
                        PRIMARY KEY(deviceId)
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS sync_metadata (
                        `key` TEXT NOT NULL,
                        value TEXT NOT NULL,
                        PRIMARY KEY(`key`)
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS sync_events (
                        eventId TEXT NOT NULL,
                        deviceId TEXT NOT NULL,
                        sequence INTEGER NOT NULL,
                        kind TEXT NOT NULL,
                        occurredAtUtc INTEGER NOT NULL,
                        planKey TEXT,
                        wordKey TEXT,
                        payload TEXT NOT NULL,
                        replacesEventId TEXT,
                        uploadedRevision TEXT,
                        PRIMARY KEY(eventId)
                    )
                    """.trimIndent(),
                )
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_sync_events_deviceId_sequence ON sync_events(deviceId, sequence)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_sync_events_planKey ON sync_events(planKey)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_sync_events_wordKey ON sync_events(wordKey)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_sync_events_uploadedRevision ON sync_events(uploadedRevision)")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE sync_events ADD COLUMN applyState TEXT NOT NULL DEFAULT 'RECEIVED'")
                database.execSQL("ALTER TABLE sync_events ADD COLUMN applyAttempts INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE sync_events ADD COLUMN lastApplyError TEXT")
                database.execSQL("ALTER TABLE sync_events ADD COLUMN appliedAt INTEGER")
                database.execSQL("ALTER TABLE sync_events ADD COLUMN sourceFile TEXT")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE words ADD COLUMN definitionSource TEXT NOT NULL DEFAULT '来源待核实'")
                database.execSQL("ALTER TABLE example_sentences ADD COLUMN source TEXT NOT NULL DEFAULT '来源待核实'")
                database.execSQL("UPDATE words SET definitionSource = 'ECDICT（MIT）' WHERE tags LIKE '%source:ecdict%'")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE words ADD COLUMN mnemonic TEXT NOT NULL DEFAULT ''")
                database.execSQL("UPDATE words SET definitionSource = 'ECDICT' WHERE definitionSource IN ('ECDICT（MIT）', 'ECDICT (MIT)')")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS word_senses (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        wordId INTEGER NOT NULL,
                        partOfSpeech TEXT NOT NULL,
                        definitionCn TEXT NOT NULL,
                        definitionSource TEXT NOT NULL DEFAULT '来源待核实',
                        normalizedKey TEXT NOT NULL,
                        sortOrder INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        FOREIGN KEY(wordId) REFERENCES words(id) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS index_word_senses_wordId ON word_senses(wordId)")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_word_senses_wordId_normalizedKey ON word_senses(wordId, normalizedKey)")
            }
        }
    }
}
