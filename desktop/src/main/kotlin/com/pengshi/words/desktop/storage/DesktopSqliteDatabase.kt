package com.pengshi.words.desktop.storage

import com.pengshi.words.model.CardState
import com.pengshi.words.model.CardStatus
import com.pengshi.words.model.DailyItemStatus
import com.pengshi.words.model.DailyPlan
import com.pengshi.words.model.DailyPlanItem
import com.pengshi.words.model.DailyPlanStatus
import com.pengshi.words.model.Deck
import com.pengshi.words.model.DeckSourceType
import com.pengshi.words.model.DeckWord
import com.pengshi.words.model.ExampleSentence
import com.pengshi.words.model.Feedback
import com.pengshi.words.model.IntradayEventStatus
import com.pengshi.words.model.IntradayReviewEvent
import com.pengshi.words.model.PlanSource
import com.pengshi.words.model.ReviewEventType
import com.pengshi.words.model.ReviewLog
import com.pengshi.words.model.StudyMode
import com.pengshi.words.model.Word
import java.io.File
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Statement
import java.sql.Types
import java.time.Instant
import java.time.LocalDate

class DesktopSqliteDatabase private constructor(
    internal val connection: Connection,
) : AutoCloseable {
    private val lock = Any()

    init {
        synchronized(lock) {
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA foreign_keys = ON")
                statement.execute("PRAGMA busy_timeout = 5000")
                SCHEMA.forEach(statement::execute)
            }
            ensureSyncEventColumns()
            ensureContentSourceColumns()
        }
    }

    private fun ensureSyncEventColumns() {
        val existingColumns = connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info(sync_events)").use { result ->
                buildSet {
                    while (result.next()) add(result.getString("name"))
                }
            }
        }
        val additions = listOf(
            "apply_state" to "TEXT NOT NULL DEFAULT 'RECEIVED'",
            "apply_attempts" to "INTEGER NOT NULL DEFAULT 0",
            "last_apply_error" to "TEXT",
            "applied_at" to "INTEGER",
            "source_file" to "TEXT",
        )
        connection.createStatement().use { statement ->
            additions
                .filterNot { (name, _) -> name in existingColumns }
                .forEach { (name, definition) ->
                    statement.execute("ALTER TABLE sync_events ADD COLUMN $name $definition")
                }
        }
    }

    private fun ensureContentSourceColumns() {
        fun columns(table: String): Set<String> = connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info($table)").use { result ->
                buildSet { while (result.next()) add(result.getString("name")) }
            }
        }
        connection.createStatement().use { statement ->
            if ("definition_source" !in columns("words")) {
                statement.execute("ALTER TABLE words ADD COLUMN definition_source TEXT NOT NULL DEFAULT '来源待核实'")
            }
            if ("source" !in columns("examples")) {
                statement.execute("ALTER TABLE examples ADD COLUMN source TEXT NOT NULL DEFAULT '来源待核实'")
            }
            if ("mnemonic" !in columns("words")) {
                statement.execute("ALTER TABLE words ADD COLUMN mnemonic TEXT NOT NULL DEFAULT ''")
            }
            statement.executeUpdate("UPDATE words SET definition_source = 'ECDICT' WHERE tags LIKE '%source:ecdict%' AND definition_source IN ('来源待核实', 'ECDICT（MIT）', 'ECDICT (MIT)')")
        }
    }

    internal fun <T> read(block: (Connection) -> T): T = synchronized(lock) {
        check(!connection.isClosed) { "Desktop database is closed" }
        block(connection)
    }

    internal fun <T> transaction(block: (Connection) -> T): T = synchronized(lock) {
        check(!connection.isClosed) { "Desktop database is closed" }
        check(connection.autoCommit) { "Nested desktop database transactions are not supported" }
        connection.autoCommit = false
        try {
            block(connection).also { connection.commit() }
        } catch (failure: Throwable) {
            connection.rollback()
            throw failure
        } finally {
            connection.autoCommit = true
        }
    }

    override fun close() = synchronized(lock) {
        if (!connection.isClosed) connection.close()
    }

    companion object {
        fun open(path: Path): DesktopSqliteDatabase = open(path.toAbsolutePath().toString())

        fun open(file: File): DesktopSqliteDatabase = open(file.absolutePath)

        fun open(path: String): DesktopSqliteDatabase {
            Class.forName("org.sqlite.JDBC")
            return DesktopSqliteDatabase(DriverManager.getConnection("jdbc:sqlite:$path"))
        }

        private val SCHEMA = listOf(
            """
            CREATE TABLE IF NOT EXISTS words (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                spelling TEXT NOT NULL,
                normalized_spelling TEXT NOT NULL UNIQUE,
                phonetic TEXT,
                part_of_speech TEXT NOT NULL,
                definition_cn TEXT NOT NULL,
                tags TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                frequency_rank INTEGER
                ,definition_source TEXT NOT NULL DEFAULT '来源待核实'
                ,mnemonic TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS examples (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                word_id INTEGER NOT NULL REFERENCES words(id) ON DELETE CASCADE,
                sentence_en TEXT NOT NULL,
                sentence_cn TEXT,
                sort_order INTEGER NOT NULL,
                source TEXT NOT NULL DEFAULT '来源待核实'
            )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS index_examples_word_id ON examples(word_id)",
            """
            CREATE TABLE IF NOT EXISTS word_senses (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                word_id INTEGER NOT NULL REFERENCES words(id) ON DELETE CASCADE,
                part_of_speech TEXT NOT NULL,
                definition_cn TEXT NOT NULL,
                definition_source TEXT NOT NULL DEFAULT '来源待核实',
                normalized_key TEXT NOT NULL,
                sort_order INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                UNIQUE (word_id, normalized_key)
            )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS index_word_senses_word_id ON word_senses(word_id)",
            """
            CREATE TABLE IF NOT EXISTS decks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                source_type TEXT NOT NULL,
                source_file_name TEXT NOT NULL,
                word_count INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS deck_words (
                deck_id INTEGER NOT NULL REFERENCES decks(id) ON DELETE CASCADE,
                word_id INTEGER NOT NULL REFERENCES words(id) ON DELETE CASCADE,
                position INTEGER NOT NULL,
                added_at INTEGER NOT NULL,
                PRIMARY KEY (deck_id, word_id)
            )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS index_deck_words_word_id ON deck_words(word_id)",
            """
            CREATE TABLE IF NOT EXISTS card_states (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                word_id INTEGER NOT NULL REFERENCES words(id) ON DELETE CASCADE,
                study_mode TEXT NOT NULL,
                status TEXT NOT NULL,
                difficulty REAL NOT NULL,
                stability REAL NOT NULL,
                retrievability REAL NOT NULL,
                due_at INTEGER,
                last_reviewed_at INTEGER,
                scheduled_days INTEGER NOT NULL,
                repetitions INTEGER NOT NULL,
                lapses INTEGER NOT NULL,
                learning_step INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                UNIQUE (word_id, study_mode)
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS daily_plans (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                local_date TEXT NOT NULL UNIQUE,
                quota INTEGER NOT NULL,
                planned_unique_word_count INTEGER NOT NULL,
                completed_unique_word_count INTEGER NOT NULL,
                status TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS daily_plan_items (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                daily_plan_id INTEGER NOT NULL REFERENCES daily_plans(id) ON DELETE CASCADE,
                word_id INTEGER NOT NULL REFERENCES words(id),
                source_type TEXT NOT NULL,
                selection_rank INTEGER NOT NULL,
                status TEXT NOT NULL,
                UNIQUE (daily_plan_id, word_id)
            )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS index_daily_plan_items_word_id ON daily_plan_items(word_id)",
            """
            CREATE TABLE IF NOT EXISTS intraday_review_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                daily_plan_item_id INTEGER NOT NULL REFERENCES daily_plan_items(id) ON DELETE CASCADE,
                word_id INTEGER NOT NULL REFERENCES words(id),
                study_mode TEXT NOT NULL,
                step_index INTEGER NOT NULL,
                scheduled_at INTEGER NOT NULL,
                completed_at INTEGER,
                status TEXT NOT NULL,
                feedback TEXT,
                UNIQUE (daily_plan_item_id, step_index)
            )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS index_intraday_review_events_word_id ON intraday_review_events(word_id)",
            """
            CREATE TABLE IF NOT EXISTS review_logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                word_id INTEGER NOT NULL REFERENCES words(id),
                study_mode TEXT NOT NULL,
                reviewed_at INTEGER NOT NULL,
                feedback TEXT NOT NULL,
                response_time_ms INTEGER NOT NULL,
                previous_state_snapshot TEXT NOT NULL,
                next_state_snapshot TEXT NOT NULL,
                previous_due_at INTEGER,
                next_due_at INTEGER,
                event_type TEXT NOT NULL
            )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS index_review_logs_word_id ON review_logs(word_id)",
            "CREATE INDEX IF NOT EXISTS index_review_logs_reviewed_at ON review_logs(reviewed_at)",
            """
            CREATE TABLE IF NOT EXISTS sync_devices (
                device_id TEXT PRIMARY KEY,
                next_sequence INTEGER NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS sync_metadata (
                `key` TEXT PRIMARY KEY,
                value TEXT NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS sync_events (
                event_id TEXT PRIMARY KEY,
                device_id TEXT NOT NULL,
                sequence INTEGER NOT NULL,
                kind TEXT NOT NULL,
                occurred_at_utc INTEGER NOT NULL,
                plan_key TEXT,
                word_key TEXT,
                payload TEXT NOT NULL,
                replaces_event_id TEXT,
                uploaded_revision TEXT,
                apply_state TEXT NOT NULL DEFAULT 'RECEIVED',
                apply_attempts INTEGER NOT NULL DEFAULT 0,
                last_apply_error TEXT,
                applied_at INTEGER,
                source_file TEXT,
                UNIQUE (device_id, sequence)
            )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS index_sync_events_plan_key ON sync_events(plan_key)",
            "CREATE INDEX IF NOT EXISTS index_sync_events_word_key ON sync_events(word_key)",
            "CREATE INDEX IF NOT EXISTS index_sync_events_uploaded_revision ON sync_events(uploaded_revision)",
        )
    }
}

internal fun Connection.generatedId(sql: String, bind: (PreparedStatement) -> Unit): Long =
    prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { statement ->
        bind(statement)
        statement.executeUpdate()
        statement.generatedKeys.use { keys ->
            check(keys.next()) { "SQLite did not return a generated id" }
            keys.getLong(1)
        }
    }

internal fun Connection.executeUpdate(sql: String, bind: (PreparedStatement) -> Unit = {}): Int =
    prepareStatement(sql).use { statement ->
        bind(statement)
        statement.executeUpdate()
    }

internal fun <T> Connection.queryList(
    sql: String,
    bind: (PreparedStatement) -> Unit = {},
    map: (ResultSet) -> T,
): List<T> = prepareStatement(sql).use { statement ->
    bind(statement)
    statement.executeQuery().use { results ->
        buildList {
            while (results.next()) add(map(results))
        }
    }
}

internal fun <T> Connection.queryOne(
    sql: String,
    bind: (PreparedStatement) -> Unit = {},
    map: (ResultSet) -> T,
): T? = prepareStatement(sql).use { statement ->
    bind(statement)
    statement.executeQuery().use { results -> if (results.next()) map(results) else null }
}

internal fun PreparedStatement.setInstant(index: Int, value: Instant?) {
    if (value == null) setNull(index, Types.BIGINT) else setLong(index, value.toEpochMilli())
}

internal fun PreparedStatement.setNullableString(index: Int, value: String?) {
    if (value == null) setNull(index, Types.VARCHAR) else setString(index, value)
}

private fun ResultSet.nullableLong(column: String): Long? = getLong(column).let { if (wasNull()) null else it }
private fun ResultSet.nullableString(column: String): String? = getString(column)
private fun ResultSet.instant(column: String): Instant = Instant.ofEpochMilli(getLong(column))
private fun ResultSet.nullableInstant(column: String): Instant? = nullableLong(column)?.let(Instant::ofEpochMilli)

internal fun ResultSet.toWord() = Word(
    id = getLong("id"), spelling = getString("spelling"), normalizedSpelling = getString("normalized_spelling"),
    phonetic = nullableString("phonetic"), partOfSpeech = getString("part_of_speech"),
    definitionCn = getString("definition_cn"), tags = getString("tags"), createdAt = instant("created_at"),
    updatedAt = instant("updated_at"), frequencyRank = nullableLong("frequency_rank")?.toInt(),
    definitionSource = getString("definition_source"),
    mnemonic = getString("mnemonic").orEmpty(),
)

internal fun ResultSet.toExample() = ExampleSentence(
    getLong("id"), getLong("word_id"), getString("sentence_en"), nullableString("sentence_cn"), getInt("sort_order"), getString("source"),
)

internal fun ResultSet.toWordSense() = com.pengshi.words.model.WordSense(
    id = getLong("id"), wordId = getLong("word_id"), partOfSpeech = getString("part_of_speech"),
    definitionCn = getString("definition_cn"), definitionSource = getString("definition_source"),
    normalizedKey = getString("normalized_key"), sortOrder = getInt("sort_order"), createdAt = instant("created_at"),
)

internal fun ResultSet.toDeck() = Deck(
    getLong("id"), getString("name"), DeckSourceType.valueOf(getString("source_type")),
    getString("source_file_name"), getInt("word_count"), instant("created_at"), instant("updated_at"),
)

internal fun ResultSet.toDeckWord() = DeckWord(
    getLong("deck_id"), getLong("word_id"), getInt("position"), instant("added_at"),
)

internal fun ResultSet.toCardState() = CardState(
    getLong("id"), getLong("word_id"), StudyMode.valueOf(getString("study_mode")),
    CardStatus.valueOf(getString("status")), getDouble("difficulty"), getDouble("stability"),
    getDouble("retrievability"), nullableInstant("due_at"), nullableInstant("last_reviewed_at"),
    getInt("scheduled_days"), getInt("repetitions"), getInt("lapses"), getInt("learning_step"),
    instant("created_at"), instant("updated_at"),
)

internal fun ResultSet.toDailyPlan() = DailyPlan(
    getLong("id"), LocalDate.parse(getString("local_date")), getInt("quota"),
    getInt("planned_unique_word_count"), getInt("completed_unique_word_count"),
    DailyPlanStatus.valueOf(getString("status")), instant("created_at"), instant("updated_at"),
)

internal fun ResultSet.toDailyPlanItem() = DailyPlanItem(
    getLong("id"), getLong("daily_plan_id"), getLong("word_id"), PlanSource.valueOf(getString("source_type")),
    getInt("selection_rank"), DailyItemStatus.valueOf(getString("status")),
)

internal fun ResultSet.toIntradayEvent() = IntradayReviewEvent(
    getLong("id"), getLong("daily_plan_item_id"), getLong("word_id"),
    StudyMode.valueOf(getString("study_mode")), getInt("step_index"), instant("scheduled_at"),
    nullableInstant("completed_at"), IntradayEventStatus.valueOf(getString("status")),
    nullableString("feedback")?.let(Feedback::valueOf),
)

internal fun ResultSet.toReviewLog() = ReviewLog(
    getLong("id"), getLong("word_id"), StudyMode.valueOf(getString("study_mode")), instant("reviewed_at"),
    Feedback.valueOf(getString("feedback")), getLong("response_time_ms"), getString("previous_state_snapshot"),
    getString("next_state_snapshot"), nullableInstant("previous_due_at"), nullableInstant("next_due_at"),
    ReviewEventType.valueOf(getString("event_type")),
)
