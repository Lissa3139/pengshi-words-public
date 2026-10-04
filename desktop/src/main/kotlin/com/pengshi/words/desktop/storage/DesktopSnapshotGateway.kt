package com.pengshi.words.desktop.storage

import com.pengshi.words.model.CardState
import com.pengshi.words.model.DailyPlan
import com.pengshi.words.model.DailyPlanItem
import com.pengshi.words.model.Deck
import com.pengshi.words.model.DeckWord
import com.pengshi.words.model.ExampleSentence
import com.pengshi.words.model.IntradayReviewEvent
import com.pengshi.words.model.ReviewLog
import com.pengshi.words.model.StudyDataSnapshot
import com.pengshi.words.model.StudyDataSnapshotGateway
import com.pengshi.words.model.Word
import com.pengshi.words.model.WordSense
import com.pengshi.words.model.SOURCE_UNVERIFIED
import com.pengshi.words.model.normalizeDictionaryText
import com.pengshi.words.model.sourceLabelNeedsUpgrade
import com.pengshi.words.model.wordSenseKey
import java.sql.Connection
import java.sql.Types

class DesktopSnapshotGateway(
    private val database: DesktopSqliteDatabase,
) : StudyDataSnapshotGateway {
    override suspend fun snapshot(): StudyDataSnapshot = database.transaction { connection ->
        StudyDataSnapshot(
            words = connection.queryList("SELECT * FROM words ORDER BY id", map = { it.toWord() }),
            exampleSentences = connection.queryList("SELECT * FROM examples ORDER BY id", map = { it.toExample() }),
            decks = connection.queryList("SELECT * FROM decks ORDER BY id", map = { it.toDeck() }),
            deckWords = connection.queryList("SELECT * FROM deck_words ORDER BY deck_id, word_id", map = { it.toDeckWord() }),
            cardStates = connection.queryList("SELECT * FROM card_states ORDER BY id", map = { it.toCardState() }),
            dailyPlans = connection.queryList("SELECT * FROM daily_plans ORDER BY id", map = { it.toDailyPlan() }),
            dailyPlanItems = connection.queryList("SELECT * FROM daily_plan_items ORDER BY id", map = { it.toDailyPlanItem() }),
            intradayReviewEvents = connection.queryList("SELECT * FROM intraday_review_events ORDER BY id", map = { it.toIntradayEvent() }),
            reviewLogs = connection.queryList("SELECT * FROM review_logs ORDER BY id", map = { it.toReviewLog() }),
            wordSenses = connection.queryList("SELECT * FROM word_senses ORDER BY word_id, sort_order, id", map = { it.toWordSense() }),
        )
    }

    override suspend fun restore(snapshot: StudyDataSnapshot) {
        requireUniqueSnapshotKeys(snapshot)
        database.transaction { connection ->
            validateForeignKeys(connection, snapshot)
            validateNormalizedSpellings(connection, snapshot.words)
            snapshot.words.forEach(connection::insertWord)
            snapshot.wordSenses.forEach(connection::insertWordSense)
            snapshot.exampleSentences.forEach(connection::insertExample)
            snapshot.decks.forEach(connection::insertDeck)
            snapshot.deckWords.forEach(connection::insertDeckWord)
            snapshot.cardStates.forEach(connection::insertCard)
            snapshot.dailyPlans.forEach(connection::insertDailyPlan)
            snapshot.dailyPlanItems.forEach(connection::insertDailyPlanItem)
            snapshot.intradayReviewEvents.forEach(connection::insertIntradayEvent)
            snapshot.reviewLogs.forEach(connection::insertReviewLogWithId)
        }
    }

    override suspend fun replace(snapshot: StudyDataSnapshot) {
        requireUniqueSnapshotKeys(snapshot)
        database.transaction { connection ->
            connection.executeUpdate("DELETE FROM review_logs")
            connection.executeUpdate("DELETE FROM intraday_review_events")
            connection.executeUpdate("DELETE FROM daily_plan_items")
            connection.executeUpdate("DELETE FROM daily_plans")
            connection.executeUpdate("DELETE FROM card_states")
            connection.executeUpdate("DELETE FROM deck_words")
            connection.executeUpdate("DELETE FROM examples")
            connection.executeUpdate("DELETE FROM word_senses")
            connection.executeUpdate("DELETE FROM decks")
            connection.executeUpdate("DELETE FROM words")
            validateForeignKeys(connection, snapshot)
            validateNormalizedSpellings(connection, snapshot.words)
            snapshot.words.forEach(connection::insertWord)
            snapshot.wordSenses.forEach(connection::insertWordSense)
            snapshot.exampleSentences.forEach(connection::insertExample)
            snapshot.decks.forEach(connection::insertDeck)
            snapshot.deckWords.forEach(connection::insertDeckWord)
            snapshot.cardStates.forEach(connection::insertCard)
            snapshot.dailyPlans.forEach(connection::insertDailyPlan)
            snapshot.dailyPlanItems.forEach(connection::insertDailyPlanItem)
            snapshot.intradayReviewEvents.forEach(connection::insertIntradayEvent)
            snapshot.reviewLogs.forEach(connection::insertReviewLogWithId)
        }
    }

    /**
     * Merges remote dictionary content into a local database without replacing
     * learning cards, plans, intraday events or review history.
     */
    override suspend fun mergeContent(remote: StudyDataSnapshot) {
        requireUniqueSnapshotKeys(remote)
        database.transaction { connection ->
            val localWordIds = connection.queryList(
                "SELECT normalized_spelling, id FROM words",
                map = { it.getString(1) to it.getLong(2) },
            ).toMap().toMutableMap()
            val remoteWordIds = mutableMapOf<Long, Long>()
            remote.words.forEach { word ->
                val localId = localWordIds[word.normalizedSpelling]
                if (localId != null) {
                    remoteWordIds[word.id] = localId
                    val localWord = connection.queryOne(
                        "SELECT * FROM words WHERE id = ?",
                        { it.setLong(1, localId) },
                    ) { it.toWord() } ?: return@forEach
                    val localHasMeaning = localWord.definitionCn.isNotBlank()
                    val remoteHasMeaning = word.definitionCn.isNotBlank()
                    val sameMeaning = localHasMeaning && remoteHasMeaning &&
                        wordSenseKey(localWord.partOfSpeech, localWord.definitionCn) == wordSenseKey(word.partOfSpeech, word.definitionCn)
                    val conflictingMeaning = localHasMeaning && remoteHasMeaning && !sameMeaning
                    if (conflictingMeaning) {
                        connection.mergeMeaning(localWord, word.partOfSpeech, word.definitionCn, word.definitionSource, word.updatedAt)
                    }
                    val mainSource = when {
                        !localHasMeaning && remoteHasMeaning -> word.definitionSource
                        localHasMeaning && !remoteHasMeaning -> localWord.definitionSource
                        sameMeaning && sourceLabelNeedsUpgrade(localWord.definitionSource, word.definitionSource) -> word.definitionSource
                        else -> localWord.definitionSource
                    }
                    val mergedWord = when {
                        word.updatedAt.isAfter(localWord.updatedAt) -> word.copy(
                            id = localId,
                            partOfSpeech = if (localHasMeaning && !sameMeaning) localWord.partOfSpeech else word.partOfSpeech,
                            definitionCn = if (localHasMeaning && !sameMeaning) localWord.definitionCn else word.definitionCn,
                            definitionSource = mainSource,
                        )
                        !localHasMeaning && remoteHasMeaning -> localWord.copy(
                            partOfSpeech = word.partOfSpeech,
                            definitionCn = word.definitionCn,
                            definitionSource = word.definitionSource,
                        )
                        mainSource != localWord.definitionSource -> localWord.copy(definitionSource = mainSource)
                        else -> null
                    }
                    if (mergedWord != null) connection.executeUpdate(
                        """UPDATE words SET spelling = ?, phonetic = ?, part_of_speech = ?,
                            definition_cn = ?, tags = ?, definition_source = ?, updated_at = ?, frequency_rank = ?, mnemonic = ?
                            WHERE id = ?""".trimIndent(),
                    ) { statement ->
                        statement.setString(1, mergedWord.spelling)
                        statement.setNullableString(2, mergedWord.phonetic)
                        statement.setString(3, mergedWord.partOfSpeech)
                        statement.setString(4, mergedWord.definitionCn)
                        statement.setString(5, mergedWord.tags)
                        statement.setString(6, mergedWord.definitionSource)
                        statement.setInstant(7, mergedWord.updatedAt)
                        val frequencyRank = mergedWord.frequencyRank
                        if (frequencyRank == null) statement.setNull(8, Types.INTEGER) else statement.setInt(8, frequencyRank)
                        statement.setString(9, mergedWord.mnemonic)
                        statement.setLong(10, localId)
                    }
                } else {
                    val idInUse = connection.queryOne(
                        "SELECT 1 FROM words WHERE id = ? LIMIT 1",
                        { it.setLong(1, word.id) },
                    ) { true } == true
                    val localId = if (idInUse) connection.insertGeneratedWord(word) else {
                        connection.insertWord(word)
                        word.id
                    }
                    localWordIds[word.normalizedSpelling] = localId
                    remoteWordIds[word.id] = localId
                }
            }

            remote.exampleSentences.forEach { example ->
                val wordId = remoteWordIds[example.wordId] ?: return@forEach
                val existingExample = connection.queryOne(
                    """SELECT id, source FROM examples
                        WHERE word_id = ? AND sentence_en = ? AND COALESCE(sentence_cn, '') = COALESCE(?, '')
                        LIMIT 1""".trimIndent(),
                    bind = { statement ->
                        statement.setLong(1, wordId)
                        statement.setString(2, example.sentenceEn)
                        statement.setNullableString(3, example.sentenceCn)
                    },
                ) { it.getLong("id") to it.getString("source") }
                if (existingExample != null) {
                    if (com.pengshi.words.model.sourceLabelNeedsUpgrade(existingExample.second, example.source)) connection.executeUpdate("UPDATE examples SET source = ? WHERE id = ?") {
                        it.setString(1, example.source)
                        it.setLong(2, existingExample.first)
                    }
                } else {
                    connection.generatedId(
                        "INSERT INTO examples (word_id, sentence_en, sentence_cn, sort_order, source) VALUES (?, ?, ?, ?, ?)",
                    ) { statement ->
                        statement.setLong(1, wordId)
                        statement.setString(2, example.sentenceEn)
                        statement.setNullableString(3, example.sentenceCn)
                        statement.setInt(4, example.sortOrder)
                        statement.setString(5, example.source)
                    }
                }
            }

            remote.wordSenses.forEach { sense ->
                val wordId = remoteWordIds[sense.wordId] ?: return@forEach
                val localWord = connection.queryOne(
                    "SELECT * FROM words WHERE id = ?",
                    { it.setLong(1, wordId) },
                ) { it.toWord() } ?: return@forEach
                connection.mergeMeaning(localWord, sense.partOfSpeech, sense.definitionCn, sense.definitionSource, sense.createdAt)
            }

            val localDecks = connection.queryList("SELECT * FROM decks", map = { it.toDeck() }).toMutableList()
            val remoteDeckIds = mutableMapOf<Long, Long>()
            remote.decks.forEach { deck ->
                val key = DeckKey(deck.name, deck.sourceType.name, deck.sourceFileName)
                val existing = localDecks.firstOrNull {
                    DeckKey(it.name, it.sourceType.name, it.sourceFileName) == key
                } ?: localDecks.firstOrNull {
                    deck.sourceType != com.pengshi.words.model.DeckSourceType.BUILTIN &&
                        it.sourceType == deck.sourceType && it.name == deck.name &&
                        (it.sourceFileName == com.pengshi.words.model.DELETED_USER_DECK_SOURCE ||
                            deck.sourceFileName == com.pengshi.words.model.DELETED_USER_DECK_SOURCE) &&
                        !(it.sourceFileName == com.pengshi.words.model.DELETED_USER_DECK_SOURCE &&
                            deck.sourceFileName != com.pengshi.words.model.DELETED_USER_DECK_SOURCE &&
                            deck.createdAt.isAfter(it.updatedAt)) &&
                        !(deck.sourceFileName == com.pengshi.words.model.DELETED_USER_DECK_SOURCE &&
                            it.sourceFileName != com.pengshi.words.model.DELETED_USER_DECK_SOURCE &&
                            it.createdAt.isAfter(deck.updatedAt))
                }
                val localId = existing?.id ?: run {
                    val preferredId = connection.queryOne(
                        "SELECT id FROM decks WHERE id = ? LIMIT 1",
                        bind = { it.setLong(1, deck.id) },
                    ) { it.getLong(1) }
                    val insertedId = if (preferredId == null) {
                        connection.executeUpdate(
                            """INSERT INTO decks
                                (id, name, source_type, source_file_name, word_count, created_at, updated_at)
                                VALUES (?, ?, ?, ?, ?, ?, ?)""".trimIndent(),
                        ) { statement ->
                            statement.setLong(1, deck.id)
                            statement.setString(2, deck.name)
                            statement.setString(3, deck.sourceType.name)
                            statement.setString(4, deck.sourceFileName)
                            statement.setInt(5, deck.wordCount)
                            statement.setInstant(6, deck.createdAt)
                            statement.setInstant(7, deck.updatedAt)
                        }
                        deck.id
                    } else {
                        connection.generatedId(
                            """INSERT INTO decks
                                (name, source_type, source_file_name, word_count, created_at, updated_at)
                                VALUES (?, ?, ?, ?, ?, ?)""".trimIndent(),
                        ) { statement ->
                            statement.setString(1, deck.name)
                            statement.setString(2, deck.sourceType.name)
                            statement.setString(3, deck.sourceFileName)
                            statement.setInt(4, deck.wordCount)
                            statement.setInstant(5, deck.createdAt)
                            statement.setInstant(6, deck.updatedAt)
                        }
                    }
                    localDecks += deck.copy(id = insertedId)
                    insertedId
                }
                val remoteWins = existing == null || deck.updatedAt.isAfter(existing.updatedAt)
                if (existing != null && remoteWins && deck.sourceType != com.pengshi.words.model.DeckSourceType.BUILTIN) {
                    connection.executeUpdate(
                        "UPDATE decks SET source_file_name = ?, word_count = ?, updated_at = ? WHERE id = ?",
                    ) { statement ->
                        statement.setString(1, deck.sourceFileName)
                        statement.setInt(2, deck.wordCount)
                        statement.setInstant(3, deck.updatedAt)
                        statement.setLong(4, localId)
                    }
                    localDecks.remove(existing)
                    localDecks += deck.copy(id = localId)
                }
                val deleted = if (remoteWins) {
                    deck.sourceFileName == com.pengshi.words.model.DELETED_USER_DECK_SOURCE
                } else {
                    existing?.sourceFileName == com.pengshi.words.model.DELETED_USER_DECK_SOURCE
                }
                if (deleted) {
                    connection.executeUpdate("DELETE FROM deck_words WHERE deck_id = ?") { it.setLong(1, localId) }
                } else {
                    remoteDeckIds[deck.id] = localId
                }
            }

            remote.deckWords.forEach { link ->
                val deckId = remoteDeckIds[link.deckId] ?: return@forEach
                val wordId = remoteWordIds[link.wordId] ?: return@forEach
                val exists = connection.queryOne(
                    "SELECT 1 FROM deck_words WHERE deck_id = ? AND word_id = ? LIMIT 1",
                    bind = { statement ->
                        statement.setLong(1, deckId)
                        statement.setLong(2, wordId)
                    },
                ) { true } ?: false
                if (!exists) {
                    connection.executeUpdate(
                        "INSERT INTO deck_words (deck_id, word_id, position, added_at) VALUES (?, ?, ?, ?)",
                    ) { statement ->
                        statement.setLong(1, deckId)
                        statement.setLong(2, wordId)
                        statement.setInt(3, link.position)
                        statement.setInstant(4, link.addedAt)
                    }
                }
            }
            remoteDeckIds.values.toSet().forEach { deckId ->
                val count = connection.queryOne(
                    "SELECT COUNT(*) FROM deck_words WHERE deck_id = ?",
                    { it.setLong(1, deckId) },
                ) { it.getInt(1) } ?: 0
                connection.executeUpdate(
                    "UPDATE decks SET word_count = ? WHERE id = ?",
                ) {
                    it.setInt(1, count)
                    it.setLong(2, deckId)
                }
            }
        }
    }
}

private fun Connection.mergeMeaning(
    word: Word,
    partOfSpeech: String,
    definitionCn: String,
    source: String,
    createdAt: java.time.Instant,
) {
    val definition = definitionCn.trim().normalizeDictionaryText()
    if (definition.isBlank()) return
    val key = wordSenseKey(partOfSpeech, definition)
    val cleanSource = source.trim().ifBlank { SOURCE_UNVERIFIED }
    if (key == wordSenseKey(word.partOfSpeech, word.definitionCn)) {
        if (sourceLabelNeedsUpgrade(word.definitionSource, cleanSource)) {
            executeUpdate("UPDATE words SET definition_source = ? WHERE id = ?") {
                it.setString(1, cleanSource)
                it.setLong(2, word.id)
            }
        }
        return
    }

    val existing = queryOne(
        "SELECT id, definition_source FROM word_senses WHERE word_id = ? AND normalized_key = ? LIMIT 1",
        { it.setLong(1, word.id); it.setString(2, key) },
    ) { it.getLong("id") to it.getString("definition_source") }
    if (existing != null) {
        if (sourceLabelNeedsUpgrade(existing.second, cleanSource)) executeUpdate(
            "UPDATE word_senses SET definition_source = ? WHERE id = ?",
        ) {
            it.setString(1, cleanSource)
            it.setLong(2, existing.first)
        }
        return
    }

    val order = queryOne(
        "SELECT COALESCE(MAX(sort_order), -1) + 1 FROM word_senses WHERE word_id = ?",
        { it.setLong(1, word.id) },
    ) { it.getInt(1) } ?: 0
    generatedId(
        """INSERT INTO word_senses (word_id, part_of_speech, definition_cn, definition_source, normalized_key, sort_order, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)""".trimIndent(),
    ) {
        it.setLong(1, word.id)
        it.setString(2, partOfSpeech.trim())
        it.setString(3, definition)
        it.setString(4, cleanSource)
        it.setString(5, key)
        it.setInt(6, order)
        it.setInstant(7, createdAt)
    }
}

private data class DeckKey(
    val name: String,
    val sourceType: String,
    val sourceFileName: String,
)

private fun requireUniqueSnapshotKeys(snapshot: StudyDataSnapshot) {
    fun <T, K> requireUnique(values: List<T>, description: String, key: (T) -> K) {
        require(values.map(key).distinct().size == values.size) { "Snapshot contains duplicate $description" }
    }

    requireUnique(snapshot.words, "word ids", Word::id)
    requireUnique(snapshot.words, "normalized spellings", Word::normalizedSpelling)
    requireUnique(snapshot.exampleSentences, "example ids", ExampleSentence::id)
    requireUnique(snapshot.wordSenses, "word sense ids", WordSense::id)
    requireUnique(snapshot.wordSenses, "word sense keys") { it.wordId to wordSenseKey(it.partOfSpeech, it.definitionCn) }
    requireUnique(snapshot.decks, "deck ids", Deck::id)
    requireUnique(snapshot.deckWords, "deck-word keys") { it.deckId to it.wordId }
    requireUnique(snapshot.cardStates, "card ids", CardState::id)
    requireUnique(snapshot.cardStates, "card keys") { it.wordId to it.mode }
    requireUnique(snapshot.dailyPlans, "daily plan ids", DailyPlan::id)
    requireUnique(snapshot.dailyPlans, "daily plan dates", DailyPlan::localDate)
    requireUnique(snapshot.dailyPlanItems, "daily plan item ids", DailyPlanItem::id)
    requireUnique(snapshot.dailyPlanItems, "daily plan item keys") { it.dailyPlanId to it.wordId }
    requireUnique(snapshot.intradayReviewEvents, "intraday event ids", IntradayReviewEvent::id)
    requireUnique(snapshot.intradayReviewEvents, "intraday event step keys") { it.dailyPlanItemId to it.stepIndex }
    requireUnique(snapshot.reviewLogs, "review log ids", ReviewLog::id)
}

private fun validateForeignKeys(connection: Connection, snapshot: StudyDataSnapshot) {
    val wordIds = connection.idSet("words") + snapshot.words.map(Word::id)
    val deckIds = connection.idSet("decks") + snapshot.decks.map(Deck::id)
    val planIds = connection.idSet("daily_plans") + snapshot.dailyPlans.map(DailyPlan::id)
    val itemIds = connection.idSet("daily_plan_items") + snapshot.dailyPlanItems.map(DailyPlanItem::id)

    require(snapshot.exampleSentences.all { it.wordId in wordIds }) { "Snapshot example references a missing word" }
    require(snapshot.wordSenses.all { it.wordId in wordIds }) { "Snapshot meaning references a missing word" }
    require(snapshot.deckWords.all { it.deckId in deckIds && it.wordId in wordIds }) {
        "Snapshot deck word references a missing deck or word"
    }
    require(snapshot.cardStates.all { it.wordId in wordIds }) { "Snapshot card references a missing word" }
    require(snapshot.dailyPlanItems.all { it.dailyPlanId in planIds && it.wordId in wordIds }) {
        "Snapshot plan item references a missing plan or word"
    }
    require(snapshot.intradayReviewEvents.all { it.dailyPlanItemId in itemIds && it.wordId in wordIds }) {
        "Snapshot event references a missing plan item or word"
    }
    require(snapshot.reviewLogs.all { it.wordId in wordIds }) { "Snapshot review log references a missing word" }
}

private fun validateNormalizedSpellings(connection: Connection, words: List<Word>) {
    if (words.isEmpty()) return
    val existing = connection.queryList("SELECT normalized_spelling FROM words", map = { it.getString(1) }).toSet()
    require(words.none { it.normalizedSpelling in existing }) {
        "Snapshot normalized spelling conflicts with existing data"
    }
}

private fun Connection.idSet(table: String): Set<Long> =
    queryList("SELECT id FROM $table", map = { it.getLong(1) }).toSet()

private fun Connection.insertWord(word: Word) {
    executeUpdate(
        """INSERT INTO words
            (id, spelling, normalized_spelling, phonetic, part_of_speech, definition_cn, tags, created_at, updated_at, frequency_rank, definition_source, mnemonic)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".trimIndent(),
    ) { statement ->
        statement.setLong(1, word.id)
        statement.setString(2, word.spelling)
        statement.setString(3, word.normalizedSpelling)
        statement.setNullableString(4, word.phonetic)
        statement.setString(5, word.partOfSpeech)
        statement.setString(6, word.definitionCn)
        statement.setString(7, word.tags)
        statement.setInstant(8, word.createdAt)
        statement.setInstant(9, word.updatedAt)
        val frequencyRank = word.frequencyRank
        if (frequencyRank == null) statement.setNull(10, java.sql.Types.INTEGER) else statement.setInt(10, frequencyRank)
        statement.setString(11, word.definitionSource)
        statement.setString(12, word.mnemonic)
    }
}

private fun Connection.insertGeneratedWord(word: Word): Long = generatedId(
    """INSERT INTO words
        (spelling, normalized_spelling, phonetic, part_of_speech, definition_cn, tags, created_at, updated_at, frequency_rank, definition_source, mnemonic)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".trimIndent(),
) { statement ->
    statement.setString(1, word.spelling)
    statement.setString(2, word.normalizedSpelling)
    statement.setNullableString(3, word.phonetic)
    statement.setString(4, word.partOfSpeech)
    statement.setString(5, word.definitionCn)
    statement.setString(6, word.tags)
    statement.setInstant(7, word.createdAt)
    statement.setInstant(8, word.updatedAt)
    val frequencyRank = word.frequencyRank
    if (frequencyRank == null) statement.setNull(9, java.sql.Types.INTEGER) else statement.setInt(9, frequencyRank)
    statement.setString(10, word.definitionSource)
    statement.setString(11, word.mnemonic)
}

private fun Connection.insertExample(example: ExampleSentence) {
    executeUpdate(
        "INSERT INTO examples (id, word_id, sentence_en, sentence_cn, sort_order, source) VALUES (?, ?, ?, ?, ?, ?)",
    ) { statement ->
        statement.setLong(1, example.id)
        statement.setLong(2, example.wordId)
        statement.setString(3, example.sentenceEn)
        statement.setNullableString(4, example.sentenceCn)
        statement.setInt(5, example.sortOrder)
        statement.setString(6, example.source)
    }
}

private fun Connection.insertWordSense(sense: WordSense) {
    executeUpdate(
        """INSERT INTO word_senses (id, word_id, part_of_speech, definition_cn, definition_source, normalized_key, sort_order, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)""".trimIndent(),
    ) {
        it.setLong(1, sense.id); it.setLong(2, sense.wordId); it.setString(3, sense.partOfSpeech)
        it.setString(4, sense.definitionCn); it.setString(5, sense.definitionSource)
        it.setString(6, wordSenseKey(sense.partOfSpeech, sense.definitionCn)); it.setInt(7, sense.sortOrder); it.setInstant(8, sense.createdAt)
    }
}

private fun Connection.insertDeck(deck: Deck) {
    executeUpdate(
        """INSERT INTO decks (id, name, source_type, source_file_name, word_count, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)""".trimIndent(),
    ) { statement ->
        statement.setLong(1, deck.id)
        statement.setString(2, deck.name)
        statement.setString(3, deck.sourceType.name)
        statement.setString(4, deck.sourceFileName)
        statement.setInt(5, deck.wordCount)
        statement.setInstant(6, deck.createdAt)
        statement.setInstant(7, deck.updatedAt)
    }
}

private fun Connection.insertDeckWord(deckWord: DeckWord) {
    executeUpdate(
        "INSERT INTO deck_words (deck_id, word_id, position, added_at) VALUES (?, ?, ?, ?)",
    ) { statement ->
        statement.setLong(1, deckWord.deckId)
        statement.setLong(2, deckWord.wordId)
        statement.setInt(3, deckWord.position)
        statement.setInstant(4, deckWord.addedAt)
    }
}

private fun Connection.insertCard(card: CardState) {
    executeUpdate(
        """INSERT INTO card_states
            (id, word_id, study_mode, status, difficulty, stability, retrievability, due_at, last_reviewed_at,
             scheduled_days, repetitions, lapses, learning_step, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".trimIndent(),
    ) { statement ->
        statement.setLong(1, card.id)
        statement.setLong(2, card.wordId)
        statement.setString(3, card.mode.name)
        statement.setString(4, card.status.name)
        statement.setDouble(5, card.difficulty)
        statement.setDouble(6, card.stability)
        statement.setDouble(7, card.retrievability)
        statement.setInstant(8, card.dueAt)
        statement.setInstant(9, card.lastReviewedAt)
        statement.setInt(10, card.scheduledDays)
        statement.setInt(11, card.repetitions)
        statement.setInt(12, card.lapses)
        statement.setInt(13, card.learningStep)
        statement.setInstant(14, card.createdAt)
        statement.setInstant(15, card.updatedAt)
    }
}

private fun Connection.insertDailyPlan(plan: DailyPlan) {
    executeUpdate(
        """INSERT INTO daily_plans
            (id, local_date, quota, planned_unique_word_count, completed_unique_word_count, status, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)""".trimIndent(),
    ) { statement ->
        statement.setLong(1, plan.id)
        statement.setString(2, plan.localDate.toString())
        statement.setInt(3, plan.quota)
        statement.setInt(4, plan.plannedUniqueWordCount)
        statement.setInt(5, plan.completedUniqueWordCount)
        statement.setString(6, plan.status.name)
        statement.setInstant(7, plan.createdAt)
        statement.setInstant(8, plan.updatedAt)
    }
}

private fun Connection.insertDailyPlanItem(item: DailyPlanItem) {
    executeUpdate(
        """INSERT INTO daily_plan_items (id, daily_plan_id, word_id, source_type, selection_rank, status)
            VALUES (?, ?, ?, ?, ?, ?)""".trimIndent(),
    ) { statement ->
        statement.setLong(1, item.id)
        statement.setLong(2, item.dailyPlanId)
        statement.setLong(3, item.wordId)
        statement.setString(4, item.source.name)
        statement.setInt(5, item.selectionRank)
        statement.setString(6, item.status.name)
    }
}

private fun Connection.insertIntradayEvent(event: IntradayReviewEvent) {
    executeUpdate(
        """INSERT INTO intraday_review_events
            (id, daily_plan_item_id, word_id, study_mode, step_index, scheduled_at, completed_at, status, feedback)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""".trimIndent(),
    ) { statement ->
        statement.setLong(1, event.id)
        statement.setLong(2, event.dailyPlanItemId)
        statement.setLong(3, event.wordId)
        statement.setString(4, event.mode.name)
        statement.setInt(5, event.stepIndex)
        statement.setInstant(6, event.scheduledAt)
        statement.setInstant(7, event.completedAt)
        statement.setString(8, event.status.name)
        statement.setNullableString(9, event.feedback?.name)
    }
}

private fun Connection.insertReviewLogWithId(log: ReviewLog) {
    executeUpdate(
        """INSERT INTO review_logs
            (id, word_id, study_mode, reviewed_at, feedback, response_time_ms, previous_state_snapshot,
             next_state_snapshot, previous_due_at, next_due_at, event_type)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".trimIndent(),
    ) { statement ->
        statement.setLong(1, log.id)
        statement.setLong(2, log.wordId)
        statement.setString(3, log.mode.name)
        statement.setInstant(4, log.reviewedAt)
        statement.setString(5, log.feedback.name)
        statement.setLong(6, log.responseTimeMs)
        statement.setString(7, log.previousStateSnapshot)
        statement.setString(8, log.nextStateSnapshot)
        statement.setInstant(9, log.previousDueAt)
        statement.setInstant(10, log.nextDueAt)
        statement.setString(11, log.eventType.name)
    }
}
