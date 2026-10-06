package com.pengshi.words.desktop.storage

import com.pengshi.words.importer.DefaultWordImportParser
import com.pengshi.words.importer.ImportFormat
import com.pengshi.words.importer.ImportPreview
import com.pengshi.words.importer.ImportRepository
import com.pengshi.words.importer.ImportResult
import com.pengshi.words.importer.ImportRow
import com.pengshi.words.importer.WordImportParser
import com.pengshi.words.model.CardState
import com.pengshi.words.model.DeckSourceType
import com.pengshi.words.model.StudyMode
import com.pengshi.words.model.normalizeDictionaryText
import com.pengshi.words.model.wordSenseKey
import com.pengshi.words.model.BatchImportSources
import com.pengshi.words.model.UserDeckBulkResult
import com.pengshi.words.importer.withBatchSources
import java.io.InputStream
import java.sql.Connection
import java.time.Instant
import java.util.Locale

class DesktopImportRepository(
    private val database: DesktopSqliteDatabase,
    private val parser: WordImportParser = DefaultWordImportParser(),
    private val now: () -> Instant = Instant::now,
) : ImportRepository {
    suspend fun addWordToUserDeck(deckId: Long, input: com.pengshi.words.model.PersonalWordInput): UserDeckBulkResult {
        val example = input.examples.firstOrNull()
        val row = ImportRow(
            spelling = input.spelling,
            phonetic = input.phonetic,
            partOfSpeech = input.partOfSpeech,
            definitionCn = input.definitionCn,
            exampleEn = example?.sentenceEn,
            exampleCn = example?.sentenceCn,
            tags = null,
            definitionSource = input.definitionSource,
            exampleSource = example?.source,
            mnemonic = input.mnemonic,
        )
        require(row.spelling.isNotBlank()) { "单词不能为空" }
        return importRowsIntoExistingDeck(ImportPreview(listOf(row), emptyList(), ImportFormat.TXT), deckId)
    }
    fun preview(input: InputStream, format: ImportFormat): ImportPreview = parser.parse(input, format)

    fun previewForUserDeck(input: InputStream, format: ImportFormat): ImportPreview = parser.parseForUserDeck(input, format)

    suspend fun import(input: InputStream, format: ImportFormat): ImportResult = importDeck(preview(input, format))

    suspend fun importIntoUserDeck(
        input: InputStream,
        format: ImportFormat,
        deckId: Long,
        sources: BatchImportSources = BatchImportSources("", ""),
    ): UserDeckBulkResult = importRowsIntoExistingDeck(previewForUserDeck(input, format).withBatchSources(sources), deckId)

    fun previewCet6Seed(): ImportPreview = openCet6Seed().use { parser.parse(it, ImportFormat.CSV) }

    suspend fun importCet6Seed(): ImportResult = importDeck(previewCet6Seed())

    fun createUserDeck(name: String): Long {
        val cleanName = name.trim()
        require(cleanName.isNotBlank()) { "词库名称不能为空" }
        return database.transaction { connection ->
            val timestamp = now()
            connection.insertDeck(
                name = cleanName,
                sourceType = DeckSourceType.USER,
                sourceFileName = "user-${timestamp.toEpochMilli()}",
                wordCount = 0,
                timestamp = timestamp,
            )
        }
    }

    /** Collapse legacy CET6 aliases into the user-facing six-level core deck. */
    fun repairDuplicateCet6Decks(expectedSpellings: Set<String>): Map<Long, Long> = database.transaction { connection ->
        if (expectedSpellings.isEmpty()) return@transaction emptyMap()
        val candidates = connection.queryList(
            """SELECT id, name, source_type, source_file_name FROM decks
                WHERE source_type = ? AND (source_file_name IN (?, ?) OR name IN (?, ?))
                ORDER BY id""".trimIndent(),
            { statement ->
                statement.setString(1, DeckSourceType.BUILTIN.name)
                statement.setString(2, CET6_DECK_SOURCE_FILE)
                statement.setString(3, "imported.csv")
                statement.setString(4, CET6_DECK_NAME)
                statement.setString(5, LEGACY_CET6_DECK_NAME)
            },
        ) { row ->
            Cet6DeckCandidate(
                id = row.getLong("id"),
                name = row.getString("name"),
                sourceFileName = row.getString("source_file_name"),
            )
        }
        val linksByDeck = candidates.associate { candidate ->
            candidate.id to connection.queryList(
                "SELECT w.normalized_spelling FROM deck_words dw JOIN words w ON w.id = dw.word_id WHERE dw.deck_id = ? ORDER BY w.normalized_spelling",
                { it.setLong(1, candidate.id) },
                { it.getString(1) },
            ).toSet()
        }
        val matching = candidates.filter { linksByDeck[it.id] == expectedSpellings }
        if (matching.isEmpty()) return@transaction emptyMap()
        val canonical = matching.firstOrNull { it.name == CET6_DECK_NAME }
            ?: matching.firstOrNull { it.sourceFileName == CET6_DECK_SOURCE_FILE }
            ?: matching.first()

        val aliases = linkedMapOf<Long, Long>()
        matching.filter { it.id != canonical.id }.forEach { duplicate ->
            connection.executeUpdate(
                """INSERT OR IGNORE INTO deck_words (deck_id, word_id, position, added_at)
                    SELECT ?, word_id, position, added_at FROM deck_words WHERE deck_id = ?""".trimIndent(),
            ) {
                it.setLong(1, canonical.id)
                it.setLong(2, duplicate.id)
            }
            connection.executeUpdate("DELETE FROM decks WHERE id = ?") { it.setLong(1, duplicate.id) }
            aliases[duplicate.id] = canonical.id
        }
        if (canonical.name != CET6_DECK_NAME || canonical.sourceFileName != CET6_DECK_SOURCE_FILE ||
            linksByDeck.getValue(canonical.id).size != expectedSpellings.size || aliases.isNotEmpty()
        ) {
            connection.executeUpdate(
                """UPDATE decks SET name = ?, source_file_name = ?, word_count =
                    (SELECT COUNT(*) FROM deck_words WHERE deck_id = ?), updated_at = ? WHERE id = ?""".trimIndent(),
            ) {
                it.setString(1, CET6_DECK_NAME)
                it.setString(2, CET6_DECK_SOURCE_FILE)
                it.setLong(3, canonical.id)
                it.setInstant(4, now())
                it.setLong(5, canonical.id)
            }
        }
        aliases
    }

    suspend fun importBuiltinCet6(preview: ImportPreview): ImportResult = importBuiltinPack(
        preview = preview,
        deckName = CET6_DECK_NAME,
        sourceFileName = CET6_DECK_SOURCE_FILE,
    )

    suspend fun importBuiltinPack(
        preview: ImportPreview,
        deckName: String,
        sourceFileName: String,
    ): ImportResult = importIntoDeck(
        preview = preview,
        builtinDeck = BuiltinDeck(deckName, sourceFileName),
    )

    override suspend fun importDeck(preview: ImportPreview): ImportResult = importIntoDeck(preview, builtinDeck = null)

    private suspend fun importIntoDeck(preview: ImportPreview, builtinDeck: BuiltinDeck?): ImportResult {
        if (preview.errors.isNotEmpty()) {
            return ImportResult(0, 0, preview.rows.size, preview.errors)
        }
        if (preview.rows.isEmpty()) return ImportResult(0, 0, 0, emptyList())

        return database.transaction { connection ->
            val timestamp = now()
            val isCet6 = preview.rows.any { "cet6-core" in it.tags.orEmpty().split(Regex("\\s+")) }
            val deckId = if (builtinDeck != null) {
                connection.queryOne(
                    "SELECT id FROM decks WHERE source_type = ? AND source_file_name = ? ORDER BY id LIMIT 1",
                    { statement ->
                        statement.setString(1, DeckSourceType.BUILTIN.name)
                        statement.setString(2, builtinDeck.sourceFileName)
                    },
                    { it.getLong("id") },
                ) ?: connection.insertDeck(
                    name = builtinDeck.name,
                    sourceType = DeckSourceType.BUILTIN,
                    sourceFileName = builtinDeck.sourceFileName,
                    wordCount = preview.rows.size,
                    timestamp = timestamp,
                )
            } else {
                connection.insertDeck(
                    name = if (isCet6) "六级核心词汇" else "导入词库 ${preview.detectedFormat.name}",
                    sourceType = DeckSourceType.IMPORTED,
                    sourceFileName = "imported.${preview.detectedFormat.name.lowercase(Locale.ROOT)}",
                    wordCount = preview.rows.size,
                    timestamp = timestamp,
                )
            }
            val knownDeckWordIds = connection.queryList(
                "SELECT word_id FROM deck_words WHERE deck_id = ?",
                { it.setLong(1, deckId) },
                { it.getLong("word_id") },
            ).toMutableSet()
            val existingExamples = connection.queryList(
                "SELECT word_id, sentence_en FROM examples ORDER BY id",
                map = { it.getLong("word_id") to it.getString("sentence_en") },
            ).groupBy({ it.first }, { it.second }).mapValues { it.value.toMutableSet() }.toMutableMap()

            preview.rows.forEachIndexed { position, row ->
                val wordId = connection.upsertWord(row, timestamp)
                val englishExamples = row.exampleEn.splitExamples()
                val chineseExamples = row.exampleCn.splitExamples()
                val knownExamples = existingExamples.getOrPut(wordId) { mutableSetOf() }
                englishExamples.forEachIndexed { index, example ->
                    if (knownExamples.add(example)) {
                        connection.executeUpdate(
                            """INSERT INTO examples (word_id, sentence_en, sentence_cn, sort_order, source)
                                VALUES (?, ?, ?, ?, ?)""".trimIndent(),
                        ) { statement ->
                            statement.setLong(1, wordId)
                            statement.setString(2, example)
                            statement.setNullableString(3, chineseExamples.getOrNull(index))
                            statement.setInt(4, index)
                            statement.setString(5, row.exampleSource?.takeIf(String::isNotBlank)
                                ?: com.pengshi.words.model.inlineExampleSourceFromTags(row.tags.orEmpty(), example))
                        }
                    }
                }
                StudyMode.entries.forEach { mode -> connection.insertNewCardIfMissing(wordId, mode, timestamp) }
                if (knownDeckWordIds.add(wordId)) {
                    connection.executeUpdate(
                        "INSERT INTO deck_words (deck_id, word_id, position, added_at) VALUES (?, ?, ?, ?)",
                    ) { statement ->
                        statement.setLong(1, deckId)
                        statement.setLong(2, wordId)
                        statement.setInt(3, position)
                        statement.setInstant(4, timestamp)
                    }
                }
            }
            if (builtinDeck != null) {
                connection.executeUpdate(
                    "UPDATE decks SET name = ?, source_file_name = ?, word_count = ?, updated_at = ? WHERE id = ?",
                ) { statement ->
                    statement.setString(1, builtinDeck.name)
                    statement.setString(2, builtinDeck.sourceFileName)
                    statement.setInt(3, knownDeckWordIds.size)
                    statement.setInstant(4, timestamp)
                    statement.setLong(5, deckId)
                }
            }
            ImportResult(deckId, preview.rows.size, 0, emptyList())
        }
    }

    private suspend fun importRowsIntoExistingDeck(preview: ImportPreview, deckId: Long): UserDeckBulkResult {
        if (preview.rows.isEmpty()) return UserDeckBulkResult(0, 0, 0, preview.errors.size, preview.errors)
        return database.transaction { connection ->
            val deck = connection.queryOne(
                "SELECT source_type FROM decks WHERE id = ? LIMIT 1",
                { it.setLong(1, deckId) },
                { DeckSourceType.valueOf(it.getString("source_type")) },
            ) ?: error("词库不存在")
            require(deck != DeckSourceType.BUILTIN) { "内置词库不能直接修改" }
            val timestamp = now()
            var importedRows = 0
            var updatedRows = 0
            var linkedRows = 0
            var protectedBuiltinRows = 0
            var addedDefinitions = 0
            val knownDeckWordIds = connection.queryList(
                "SELECT word_id FROM deck_words WHERE deck_id = ?",
                { it.setLong(1, deckId) },
                { it.getLong("word_id") },
            ).toMutableSet()
            val existingExamples = connection.queryList(
                "SELECT word_id, sentence_en FROM examples",
                map = { it.getLong("word_id") to it.getString("sentence_en") },
            ).groupBy({ it.first }, { it.second }).mapValues { it.value.toMutableSet() }.toMutableMap()
            preview.rows.forEach { row ->
                val normalizedSpelling = row.spelling.trim().lowercase(Locale.ROOT)
                val existingWordId = connection.queryOne(
                    "SELECT id FROM words WHERE normalized_spelling = ? LIMIT 1",
                    { it.setString(1, normalizedSpelling) },
                    { it.getLong(1) },
                )
                val alreadyLinked = existingWordId?.let { wordId ->
                    connection.queryOne(
                        "SELECT 1 FROM deck_words WHERE deck_id = ? AND word_id = ? LIMIT 1",
                        { it.setLong(1, deckId); it.setLong(2, wordId) },
                        { true },
                    ) == true
                } ?: false
                val protectedBuiltinWordId = existingWordId?.takeIf { connection.hasBuiltinAssociation(it, deckId) }
                val protectedBuiltinWord = protectedBuiltinWordId != null
                if (protectedBuiltinWord) protectedBuiltinRows++
                val wordId = if (protectedBuiltinWordId != null) {
                    val mnemonic = row.mnemonic?.trim().orEmpty()
                    if (mnemonic.isNotBlank()) {
                        connection.executeUpdate(
                            "UPDATE words SET mnemonic = ?, updated_at = ? WHERE id = ? AND trim(mnemonic) = ''",
                        ) {
                            it.setString(1, mnemonic)
                            it.setInstant(2, timestamp)
                            it.setLong(3, protectedBuiltinWordId)
                        }
                    }
                    val builtin = connection.queryOne("SELECT * FROM words WHERE id = ?", { it.setLong(1, protectedBuiltinWordId) }) { it.toWord() }
                    if (builtin != null && connection.addWordSense(
                            builtin, row.partOfSpeech.orEmpty(), row.definitionCn,
                            row.definitionSource ?: com.pengshi.words.model.definitionSourceFromTags(row.tags.orEmpty()), timestamp,
                        )) addedDefinitions++
                    protectedBuiltinWordId
                } else {
                    val existing = existingWordId?.let { id ->
                        connection.queryOne("SELECT * FROM words WHERE id = ?", { it.setLong(1, id) }) { it.toWord() }
                    }
                    if (existing == null) {
                        connection.upsertWord(row, timestamp, preserveMeaningfulSource = true)
                    } else {
                        val incoming = row.definitionCn.trim().normalizeDictionaryText()
                        val mainBlank = existing.definitionCn.isBlank()
                        val incomingSource = row.definitionSource?.takeIf(String::isNotBlank)
                            ?: com.pengshi.words.model.definitionSourceFromTags(row.tags.orEmpty())
                        connection.executeUpdate(
                            """UPDATE words SET spelling = ?, phonetic = ?, part_of_speech = ?, definition_cn = ?, tags = ?,
                                definition_source = ?, updated_at = ?, frequency_rank = ?, mnemonic = ? WHERE id = ?""".trimIndent(),
                        ) { statement ->
                            statement.setString(1, existing.spelling.ifBlank { row.spelling.trim() })
                            statement.setNullableString(2, existing.phonetic ?: row.phonetic?.trim()?.ifBlank { null })
                            statement.setString(3, existing.partOfSpeech.ifBlank { row.partOfSpeech.orEmpty() })
                            statement.setString(4, if (mainBlank) incoming else existing.definitionCn)
                            val tags = (existing.tags.split(Regex("\\s+")) + row.tags.orEmpty().split(Regex("\\s+")) + "user-content")
                                .filter(String::isNotBlank).distinct().joinToString(" ")
                            statement.setString(5, tags)
                            statement.setString(6, if (mainBlank) incomingSource else existing.definitionSource)
                            statement.setInstant(7, if (mainBlank || existing.phonetic.isNullOrBlank() && !row.phonetic.isNullOrBlank() || existing.mnemonic.isBlank() && !row.mnemonic.isNullOrBlank()) timestamp else existing.updatedAt)
                            val rank = row.frequencyRank ?: existing.frequencyRank
                            if (rank == null) statement.setNull(8, java.sql.Types.INTEGER) else statement.setInt(8, rank)
                            statement.setString(9, row.mnemonic?.trim()?.ifBlank { existing.mnemonic } ?: existing.mnemonic)
                            statement.setLong(10, existing.id)
                        }
                        if (!mainBlank && connection.addWordSense(existing, row.partOfSpeech.orEmpty(), incoming, incomingSource, timestamp)) {
                            addedDefinitions++
                        }
                        existing.id
                    }
                }
                val known = existingExamples.getOrPut(wordId) { mutableSetOf() }
                val english = row.exampleEn.splitExamples()
                val chinese = row.exampleCn.splitExamples()
                if (!protectedBuiltinWord) english.forEachIndexed { index, sentence ->
                    if (known.add(sentence)) {
                        connection.executeUpdate(
                            "INSERT INTO examples (word_id, sentence_en, sentence_cn, sort_order, source) VALUES (?, ?, ?, ?, ?)",
                        ) {
                            it.setLong(1, wordId)
                            it.setString(2, sentence)
                            it.setNullableString(3, chinese.getOrNull(index))
                            it.setInt(4, index)
                            it.setString(5, row.exampleSource?.takeIf(String::isNotBlank)
                                ?: com.pengshi.words.model.SOURCE_UNVERIFIED)
                        }
                    } else {
                        val source = row.exampleSource?.takeIf(String::isNotBlank)
                            ?: com.pengshi.words.model.SOURCE_UNVERIFIED
                        if (!com.pengshi.words.model.isUnverifiedSource(source)) {
                            val current = connection.queryOne(
                                "SELECT id, source FROM examples WHERE word_id = ? AND lower(sentence_en) = lower(?) AND COALESCE(sentence_cn, '') = COALESCE(?, '') LIMIT 1",
                                { it.setLong(1, wordId); it.setString(2, sentence); it.setNullableString(3, chinese.getOrNull(index)) },
                            ) { it.getLong("id") to it.getString("source") }
                            if (current != null && com.pengshi.words.model.isUnverifiedSource(current.second)) {
                                connection.executeUpdate("UPDATE examples SET source = ? WHERE id = ?") {
                                    it.setString(1, source)
                                    it.setLong(2, current.first)
                                }
                            }
                        }
                    }
                }
                StudyMode.entries.forEach { mode -> connection.insertNewCardIfMissing(wordId, mode, timestamp) }
                if (knownDeckWordIds.add(wordId)) {
                    connection.executeUpdate(
                        "INSERT INTO deck_words (deck_id, word_id, position, added_at) VALUES (?, ?, ?, ?)",
                    ) {
                        it.setLong(1, deckId)
                        it.setLong(2, wordId)
                        it.setInt(3, knownDeckWordIds.size - 1)
                        it.setInstant(4, timestamp)
                    }
                    importedRows++
                    if (existingWordId != null) linkedRows++
                } else if (alreadyLinked) {
                    updatedRows++
                }
            }
            connection.executeUpdate(
                "UPDATE decks SET word_count = ?, updated_at = ? WHERE id = ?",
            ) {
                it.setInt(1, knownDeckWordIds.size)
                it.setInstant(2, timestamp)
                it.setLong(3, deckId)
            }
            UserDeckBulkResult(importedRows, updatedRows, linkedRows, preview.errors.size, preview.errors, protectedBuiltinRows, addedDefinitions)
        }
    }

    private fun Connection.addWordSense(
        word: com.pengshi.words.model.Word,
        partOfSpeech: String,
        definitionCn: String,
        source: String,
        timestamp: Instant,
    ): Boolean {
        val meaning = definitionCn.trim().normalizeDictionaryText()
        if (meaning.isBlank()) return false
        val key = wordSenseKey(partOfSpeech, meaning)
        val cleanSource = source.trim().ifBlank { com.pengshi.words.model.SOURCE_UNVERIFIED }
        if (key == wordSenseKey(word.partOfSpeech, word.definitionCn)) {
            if (com.pengshi.words.model.sourceLabelNeedsUpgrade(word.definitionSource, cleanSource)) {
                executeUpdate("UPDATE words SET definition_source = ?, updated_at = ? WHERE id = ?") {
                    it.setString(1, cleanSource); it.setInstant(2, timestamp); it.setLong(3, word.id)
                }
            }
            return false
        }
        val existing = queryOne(
            "SELECT id, definition_source FROM word_senses WHERE word_id = ? AND normalized_key = ? LIMIT 1",
            { it.setLong(1, word.id); it.setString(2, key) },
        ) { it.getLong("id") to it.getString("definition_source") }
        if (existing != null) {
            if (com.pengshi.words.model.sourceLabelNeedsUpgrade(existing.second, cleanSource)) {
                executeUpdate("UPDATE word_senses SET definition_source = ? WHERE id = ?") {
                    it.setString(1, cleanSource); it.setLong(2, existing.first)
                }
            }
            return false
        }
        val order = queryOne("SELECT COALESCE(MAX(sort_order), -1) + 1 FROM word_senses WHERE word_id = ?", { it.setLong(1, word.id) }) { it.getInt(1) } ?: 0
        generatedId(
            """INSERT INTO word_senses (word_id, part_of_speech, definition_cn, definition_source, normalized_key, sort_order, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)""".trimIndent(),
        ) {
            it.setLong(1, word.id); it.setString(2, partOfSpeech.trim()); it.setString(3, meaning)
            it.setString(4, cleanSource); it.setString(5, key); it.setInt(6, order); it.setInstant(7, timestamp)
        }
        return true
    }

    private fun Connection.hasBuiltinAssociation(wordId: Long, exceptDeckId: Long): Boolean = queryOne(
        """SELECT 1 FROM deck_words dw JOIN decks d ON d.id = dw.deck_id
            WHERE dw.word_id = ? AND dw.deck_id <> ? AND d.source_type = ? LIMIT 1""".trimIndent(),
        { it.setLong(1, wordId); it.setLong(2, exceptDeckId); it.setString(3, DeckSourceType.BUILTIN.name) },
    ) { true } == true

    private fun openCet6Seed(): InputStream = requireNotNull(
        javaClass.getResourceAsStream("/wordpacks/cet6/words.csv"),
    ) { "Bundled CET6 seed was not found" }

    private fun Connection.insertDeck(
        name: String,
        sourceType: DeckSourceType,
        sourceFileName: String,
        wordCount: Int,
        timestamp: Instant,
    ): Long = generatedId(
        """INSERT INTO decks (name, source_type, source_file_name, word_count, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?)""".trimIndent(),
    ) { statement ->
        statement.setString(1, name)
        statement.setString(2, sourceType.name)
        statement.setString(3, sourceFileName)
        statement.setInt(4, wordCount)
        statement.setInstant(5, timestamp)
        statement.setInstant(6, timestamp)
    }

    companion object {
        const val CET6_DECK_NAME = "六级核心词汇"
        private const val LEGACY_CET6_DECK_NAME = "CET6"
        const val CET6_DECK_SOURCE_FILE = "words.csv"
    }
}

private data class Cet6DeckCandidate(
    val id: Long,
    val name: String,
    val sourceFileName: String,
)

private data class BuiltinDeck(
    val name: String,
    val sourceFileName: String,
)

private fun Connection.upsertWord(row: ImportRow, timestamp: Instant, preserveMeaningfulSource: Boolean = false): Long {
    val incomingDefinitionSource = row.definitionSource?.takeIf(String::isNotBlank)
        ?: com.pengshi.words.model.definitionSourceFromTags(row.tags.orEmpty())
    val normalized = row.spelling.trim().lowercase(Locale.ROOT)
    val existing = queryOne(
        "SELECT * FROM words WHERE normalized_spelling = ? LIMIT 1",
        { it.setString(1, normalized) },
        { it.toWord() },
    )
    if (existing != null) {
        val definitionSource = if (preserveMeaningfulSource && !com.pengshi.words.model.isUnverifiedSource(existing.definitionSource)) {
            existing.definitionSource
        } else incomingDefinitionSource
        executeUpdate(
            """UPDATE words SET spelling = ?, phonetic = ?, part_of_speech = ?, definition_cn = ?, tags = ?,
                definition_source = ?, updated_at = ?, frequency_rank = ?, mnemonic = ? WHERE id = ?""".trimIndent(),
        ) { statement ->
            statement.setString(1, row.spelling.trim())
            statement.setNullableString(2, row.phonetic)
            statement.setString(3, row.partOfSpeech.orEmpty())
            statement.setString(4, row.definitionCn.trim().normalizeDictionaryText())
            statement.setString(5, row.tags.orEmpty())
            statement.setString(6, definitionSource)
            statement.setInstant(7, timestamp)
            val frequencyRank = row.frequencyRank ?: existing.frequencyRank
            if (frequencyRank == null) statement.setNull(8, java.sql.Types.INTEGER) else statement.setInt(8, frequencyRank)
            statement.setString(9, row.mnemonic?.trim()?.ifBlank { existing.mnemonic } ?: existing.mnemonic)
            statement.setLong(10, existing.id)
        }
        return existing.id
    }
    return generatedId(
        """INSERT INTO words
            (spelling, normalized_spelling, phonetic, part_of_speech, definition_cn, tags, created_at, updated_at, frequency_rank, definition_source, mnemonic)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".trimIndent(),
    ) { statement ->
        statement.setString(1, row.spelling.trim())
        statement.setString(2, normalized)
        statement.setNullableString(3, row.phonetic)
        statement.setString(4, row.partOfSpeech.orEmpty())
        statement.setString(5, row.definitionCn.trim().normalizeDictionaryText())
        statement.setString(6, row.tags.orEmpty())
        statement.setInstant(7, timestamp)
        statement.setInstant(8, timestamp)
        val frequencyRank = row.frequencyRank
        if (frequencyRank == null) statement.setNull(9, java.sql.Types.INTEGER) else statement.setInt(9, frequencyRank)
        statement.setString(10, incomingDefinitionSource)
        statement.setString(11, row.mnemonic?.trim().orEmpty())
    }
}

private fun Connection.insertNewCardIfMissing(wordId: Long, mode: StudyMode, timestamp: Instant) {
    val exists = queryOne(
        "SELECT 1 FROM card_states WHERE word_id = ? AND study_mode = ? LIMIT 1",
        { statement -> statement.setLong(1, wordId); statement.setString(2, mode.name) },
        { true },
    ) == true
    if (!exists) {
        val card = CardState.new(com.pengshi.words.model.CardKey(wordId, mode), timestamp)
        executeUpdate(
            """INSERT INTO card_states
                (word_id, study_mode, status, difficulty, stability, retrievability, due_at, last_reviewed_at,
                 scheduled_days, repetitions, lapses, learning_step, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".trimIndent(),
        ) { it.bindCard(card, includeId = false) }
    }
}

private fun String?.splitExamples(): List<String> = orEmpty()
    .split("||")
    .map(String::trim)
    .filter(String::isNotBlank)
