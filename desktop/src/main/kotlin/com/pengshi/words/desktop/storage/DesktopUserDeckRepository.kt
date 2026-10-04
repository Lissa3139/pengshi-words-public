package com.pengshi.words.desktop.storage

import com.pengshi.words.model.PersonalExampleInput
import com.pengshi.words.model.PersonalWordInput
import com.pengshi.words.model.DeckSourceType
import com.pengshi.words.model.normalizeDictionaryText
import java.time.Instant
import java.util.Locale

class DesktopUserDeckRepository(private val database: DesktopSqliteDatabase) {
    fun labelUnattributedLiteratureContent() = database.transaction { connection ->
        val literaryWords = "SELECT dw.word_id FROM deck_words dw JOIN decks d ON d.id = dw.deck_id WHERE d.name = '文献阅读'"
        connection.executeUpdate("UPDATE words SET definition_source = 'ChatGPT 生成' WHERE id IN ($literaryWords) AND (trim(definition_source) = '' OR definition_source = '来源待核实')")
        connection.executeUpdate("UPDATE word_senses SET definition_source = 'ChatGPT 生成' WHERE word_id IN ($literaryWords) AND (trim(definition_source) = '' OR definition_source = '来源待核实')")
        connection.executeUpdate("UPDATE examples SET source = 'ChatGPT 生成' WHERE word_id IN ($literaryWords) AND (trim(source) = '' OR source = '来源待核实')")
    }
    fun deleteDeck(deckId: Long): Boolean = database.transaction { connection ->
        val deck = connection.editableDeck(deckId)
        if (deck.sourceFileName == com.pengshi.words.model.DELETED_USER_DECK_SOURCE) return@transaction false
        connection.executeUpdate("DELETE FROM deck_words WHERE deck_id = ?") { it.setLong(1, deckId) }
        connection.executeUpdate("UPDATE decks SET source_file_name = ?, word_count = 0, updated_at = ? WHERE id = ?") {
            it.setString(1, com.pengshi.words.model.DELETED_USER_DECK_SOURCE)
            it.setInstant(2, Instant.now())
            it.setLong(3, deckId)
        }
        true
    }
    fun updateWord(deckId: Long, wordId: Long, input: PersonalWordInput) {
        database.transaction { connection ->
            connection.editableDeck(deckId)
            require(connection.linked(deckId, wordId)) { "单词不在当前词库中" }
            require(!connection.hasBuiltinAssociation(wordId, deckId)) { "六级词条不能修改官方内容，请在个人词库中补充其他单词" }
            val now = Instant.now()
            connection.executeUpdate(
                "UPDATE words SET phonetic = ?, part_of_speech = ?, definition_cn = ?, tags = ?, definition_source = ?, mnemonic = ?, updated_at = ? WHERE id = ?",
            ) {
                it.setNullableString(1, input.phonetic.trim().ifBlank { null })
                it.setString(2, input.partOfSpeech.trim())
                it.setString(3, input.definitionCn.trim().normalizeDictionaryText())
                it.setString(4, mergeTags(connection.queryOne("SELECT tags FROM words WHERE id = ?", { s -> s.setLong(1, wordId) }) { s -> s.getString(1) }.orEmpty(), "user-content"))
                it.setString(5, input.definitionSource.trim().ifBlank { com.pengshi.words.model.SOURCE_UNVERIFIED })
                it.setString(6, input.mnemonic.trim())
                it.setInstant(7, now)
                it.setLong(8, wordId)
            }
            addExamples(connection, wordId, input.examples)
        }
    }

    fun deleteWordFromDeck(deckId: Long, wordId: Long): Boolean = database.transaction { connection ->
        val deck = connection.editableDeck(deckId)
        if (!connection.linked(deckId, wordId)) return@transaction false
        connection.executeUpdate("DELETE FROM deck_words WHERE deck_id = ? AND word_id = ?") {
            it.setLong(1, deckId); it.setLong(2, wordId)
        }
        connection.updateDeckCount(deck.id)
        true
    }

    fun addExample(wordId: Long, sentenceEn: String, sentenceCn: String?, source: String = com.pengshi.words.model.SOURCE_UNVERIFIED): Boolean = database.transaction { connection ->
        val english = sentenceEn.trim()
        require(english.isNotBlank()) { "英文例句不能为空" }
        val exists = connection.queryOne(
            "SELECT 1 FROM examples WHERE word_id = ? AND lower(sentence_en) = lower(?) LIMIT 1",
            { it.setLong(1, wordId); it.setString(2, english) },
        ) { true } == true
        if (exists) return@transaction false
        val nextOrder = connection.queryOne(
            "SELECT COALESCE(MAX(sort_order), -1) + 1 FROM examples WHERE word_id = ?",
            { it.setLong(1, wordId) },
        ) { it.getInt(1) } ?: 0
        connection.executeUpdate("INSERT INTO examples (word_id, sentence_en, sentence_cn, sort_order, source) VALUES (?, ?, ?, ?, ?)") {
            it.setLong(1, wordId); it.setString(2, english); it.setNullableString(3, sentenceCn?.trim()?.ifBlank { null }); it.setInt(4, nextOrder)
            it.setString(5, source.trim().ifBlank { com.pengshi.words.model.SOURCE_UNVERIFIED })
        }
        true
    }

    fun updateExample(exampleId: Long, sentenceEn: String, sentenceCn: String?, source: String = com.pengshi.words.model.SOURCE_UNVERIFIED): Boolean = database.transaction { connection ->
        val english = sentenceEn.trim()
        require(english.isNotBlank()) { "英文例句不能为空" }
        val wordId = connection.queryOne("SELECT word_id FROM examples WHERE id = ?", { it.setLong(1, exampleId) }) { it.getLong(1) }
            ?: return@transaction false
        val duplicate = connection.queryOne(
            "SELECT 1 FROM examples WHERE word_id = ? AND lower(sentence_en) = lower(?) AND id <> ? LIMIT 1",
            { it.setLong(1, wordId); it.setString(2, english); it.setLong(3, exampleId) },
        ) { true } == true
        if (duplicate) return@transaction false
        connection.executeUpdate("UPDATE examples SET sentence_en = ?, sentence_cn = ?, source = ? WHERE id = ?") {
            it.setString(1, english); it.setNullableString(2, sentenceCn?.trim()?.ifBlank { null })
            it.setString(3, source.trim().ifBlank { com.pengshi.words.model.SOURCE_UNVERIFIED }); it.setLong(4, exampleId)
        }
        true
    }

    fun deleteExample(exampleId: Long): Boolean = database.transaction {
        it.executeUpdate("DELETE FROM examples WHERE id = ?") { statement -> statement.setLong(1, exampleId) } > 0
    }

    private fun java.sql.Connection.editableDeck(deckId: Long): com.pengshi.words.model.Deck {
        val deck = queryOne("SELECT * FROM decks WHERE id = ?", { it.setLong(1, deckId) }) { it.toDeck() }
            ?: error("词库不存在")
        require(deck.sourceType != DeckSourceType.BUILTIN) { "内置词库不能修改" }
        return deck
    }

    private fun java.sql.Connection.linked(deckId: Long, wordId: Long): Boolean =
        queryOne("SELECT 1 FROM deck_words WHERE deck_id = ? AND word_id = ?", { it.setLong(1, deckId); it.setLong(2, wordId) }) { true } == true

    private fun java.sql.Connection.hasBuiltinAssociation(wordId: Long, exceptDeckId: Long): Boolean = queryOne(
        """SELECT 1 FROM deck_words dw JOIN decks d ON d.id = dw.deck_id
            WHERE dw.word_id = ? AND dw.deck_id <> ? AND d.source_type = ? LIMIT 1""".trimIndent(),
        { it.setLong(1, wordId); it.setLong(2, exceptDeckId); it.setString(3, DeckSourceType.BUILTIN.name) },
    ) { true } == true

    private fun java.sql.Connection.updateDeckCount(deckId: Long) {
        executeUpdate("UPDATE decks SET word_count = (SELECT COUNT(*) FROM deck_words WHERE deck_id = ?), updated_at = ? WHERE id = ?") {
            it.setLong(1, deckId); it.setInstant(2, Instant.now()); it.setLong(3, deckId)
        }
    }

    private fun addExamples(connection: java.sql.Connection, wordId: Long, examples: List<PersonalExampleInput>) {
        examples.forEach { example ->
            val english = example.sentenceEn.trim()
            if (english.isNotBlank()) addExampleIfMissing(connection, wordId, english, example.sentenceCn, example.source)
        }
    }

    private fun addExampleIfMissing(connection: java.sql.Connection, wordId: Long, english: String, chinese: String?, source: String) {
        val exists = connection.queryOne("SELECT 1 FROM examples WHERE word_id = ? AND lower(sentence_en) = lower(?)", { it.setLong(1, wordId); it.setString(2, english) }) { true } == true
        if (!exists) {
            val order = connection.queryOne("SELECT COALESCE(MAX(sort_order), -1) + 1 FROM examples WHERE word_id = ?", { it.setLong(1, wordId) }) { it.getInt(1) } ?: 0
            connection.executeUpdate("INSERT INTO examples (word_id, sentence_en, sentence_cn, sort_order, source) VALUES (?, ?, ?, ?, ?)") {
                it.setLong(1, wordId); it.setString(2, english); it.setNullableString(3, chinese?.trim()?.ifBlank { null }); it.setInt(4, order)
                it.setString(5, source.trim().ifBlank { com.pengshi.words.model.SOURCE_UNVERIFIED })
            }
        }
    }

    private fun mergeTags(existing: String, addition: String): String =
        (existing.split(Regex("\\s+")) + addition).filter(String::isNotBlank).distinct().joinToString(" ")
}
