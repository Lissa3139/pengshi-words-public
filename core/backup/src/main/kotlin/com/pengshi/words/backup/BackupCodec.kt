package com.pengshi.words.backup

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
import com.pengshi.words.model.StudyDataSnapshot
import com.pengshi.words.model.StudyMode
import com.pengshi.words.model.Word
import com.pengshi.words.model.WordSense
import com.pengshi.words.model.definitionSourceFromTags
import com.pengshi.words.model.wordSenseKey
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.Base64

internal data class BackupEnvelope(
    val schemaVersion: Int,
    val createdAt: Instant,
    val appVersion: String,
    val checksum: String,
    val snapshot: StudyDataSnapshot,
    val statsStartDate: LocalDate? = null,
    val settings: BackupSettingsSnapshot? = null,
)

internal object BackupCodec {
    fun encodeJson(envelope: BackupEnvelope): String {
        val payload = Base64.getEncoder().encodeToString(SnapshotCodec.encode(envelope.snapshot, envelope.schemaVersion).toByteArray(StandardCharsets.UTF_8))
        val statsStartDate = envelope.statsStartDate?.let { "\"$it\"" } ?: "null"
        val settingsPayload = envelope.settings?.let { Base64.getEncoder().encodeToString(SettingsCodec.encode(it).toByteArray(StandardCharsets.UTF_8)) }
        val settingsJson = settingsPayload?.let { "\"$it\"" } ?: "null"
        return "{\"schemaVersion\":${envelope.schemaVersion},\"createdAt\":\"${envelope.createdAt}\",\"appVersion\":\"${escape(envelope.appVersion)}\",\"checksum\":\"${envelope.checksum}\",\"statsStartDate\":$statsStartDate,\"settingsPayload\":$settingsJson,\"payload\":\"$payload\"}"
    }

    fun decodeJson(json: String): BackupEnvelope {
        fun required(name: String): String = Regex("\\\"$name\\\":\\\"([^\\\"]*)\\\"").find(json)?.groupValues?.get(1)
            ?: error("Missing backup field: $name")
        val schemaVersion = Regex("\\\"schemaVersion\\\":(\\d+)").find(json)?.groupValues?.get(1)?.toInt()
            ?: error("Missing backup field: schemaVersion")
        val payload = String(Base64.getDecoder().decode(required("payload")), StandardCharsets.UTF_8)
        val statsStartDate = Regex("\\\"statsStartDate\\\":\\\"([^\\\"]*)\\\"")
            .find(json)?.groupValues?.get(1)?.let(LocalDate::parse)
        val settings = Regex("\\\"settingsPayload\\\":\\\"([^\\\"]*)\\\"")
            .find(json)?.groupValues?.get(1)
            ?.let { SettingsCodec.decode(String(Base64.getDecoder().decode(it), StandardCharsets.UTF_8)) }
        return BackupEnvelope(
            schemaVersion = schemaVersion,
            createdAt = Instant.parse(required("createdAt")),
            appVersion = required("appVersion"),
            checksum = required("checksum"),
            snapshot = SnapshotCodec.decode(payload),
            statsStartDate = statsStartDate,
            settings = settings,
        )
    }

    fun checksum(snapshot: StudyDataSnapshot, schemaVersion: Int = CURRENT_BACKUP_SCHEMA): String {
        val bytes = SnapshotCodec.encode(snapshot, schemaVersion).toByteArray(StandardCharsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return "sha256:" + digest.joinToString("") { "%02x".format(it) }
    }

    private fun escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")
}

private object SettingsCodec {
    fun encode(settings: BackupSettingsSnapshot): String = listOf(
        "v2",
        settings.dailyQuota.toString(),
        settings.defaultMode,
        settings.autoPlayWord.toString(),
        settings.autoPlaySentence.toString(),
        settings.speechRate.toString(),
        settings.selectedVoiceKey ?: "",
        settings.includedDeckIds.sorted().joinToString(","),
        settings.excludedWordIds.sorted().joinToString(","),
        settings.favoriteWordIds.sorted().joinToString(","),
        settings.statsAnchorDate?.toString() ?: "",
        settings.statsPeriod ?: "",
        settings.statsTab ?: "",
        settings.deckWeights.entries.sortedBy { it.key }.joinToString(",") { "${it.key}:${it.value}" },
    ).joinToString("\u001f") { Base64.getEncoder().encodeToString(it.toByteArray(StandardCharsets.UTF_8)) }

    fun decode(encoded: String): BackupSettingsSnapshot {
        val values = encoded.split("\u001f").map { String(Base64.getDecoder().decode(it), StandardCharsets.UTF_8) }
        val version = values.firstOrNull()
        require((version == "v1" && values.size == 13) || (version == "v2" && values.size == 14)) {
            "Unsupported backup settings payload"
        }
        fun ids(index: Int): Set<Long> = values[index].split(',').mapNotNull(String::toLongOrNull).toSet()
        fun weights(index: Int): Map<Long, Int> = values.getOrNull(index).orEmpty()
            .split(',')
            .mapNotNull { pair ->
                val parts = pair.split(':', limit = 2)
                if (parts.size != 2) return@mapNotNull null
                val deckId = parts[0].toLongOrNull() ?: return@mapNotNull null
                val weight = parts[1].toIntOrNull() ?: return@mapNotNull null
                deckId to weight
            }
            .toMap()
        return BackupSettingsSnapshot(
            dailyQuota = values[1].toInt(),
            defaultMode = values[2],
            autoPlayWord = values[3].toBooleanStrict(),
            autoPlaySentence = values[4].toBooleanStrict(),
            speechRate = values[5].toFloat(),
            selectedVoiceKey = values[6].takeIf(String::isNotEmpty),
            includedDeckIds = ids(7),
            excludedWordIds = ids(8),
            favoriteWordIds = ids(9),
            statsAnchorDate = values[10].takeIf(String::isNotEmpty)?.let(LocalDate::parse),
            statsPeriod = values[11].takeIf(String::isNotEmpty),
            statsTab = values[12].takeIf(String::isNotEmpty),
            deckWeights = weights(13),
        )
    }
}

private object SnapshotCodec {
    fun encode(snapshot: StudyDataSnapshot, schemaVersion: Int = CURRENT_BACKUP_SCHEMA): String = buildList {
        require(schemaVersion in 1..CURRENT_BACKUP_SCHEMA)
        add("v$schemaVersion")
        snapshot.words.forEach {
            add(when (schemaVersion) {
                1 -> row("W", it.id, it.spelling, it.normalizedSpelling, it.phonetic, it.partOfSpeech, it.definitionCn, it.tags, it.createdAt, it.updatedAt, it.frequencyRank)
                2 -> row("W", it.id, it.spelling, it.normalizedSpelling, it.phonetic, it.partOfSpeech, it.definitionCn, it.tags, it.createdAt, it.updatedAt, it.frequencyRank, it.definitionSource)
                else -> row("W", it.id, it.spelling, it.normalizedSpelling, it.phonetic, it.partOfSpeech, it.definitionCn, it.tags, it.createdAt, it.updatedAt, it.frequencyRank, it.definitionSource, it.mnemonic)
            })
        }
        snapshot.exampleSentences.forEach {
            add(if (schemaVersion == 1) row("E", it.id, it.wordId, it.sentenceEn, it.sentenceCn, it.sortOrder)
            else row("E", it.id, it.wordId, it.sentenceEn, it.sentenceCn, it.sortOrder, it.source))
        }
        snapshot.decks.forEach { add(row("D", it.id, it.name, it.sourceType, it.sourceFileName, it.wordCount, it.createdAt, it.updatedAt)) }
        snapshot.deckWords.forEach { add(row("L", it.deckId, it.wordId, it.position, it.addedAt)) }
        snapshot.cardStates.forEach { add(row("C", it.id, it.wordId, it.mode, it.status, it.difficulty, it.stability, it.retrievability, it.dueAt, it.lastReviewedAt, it.scheduledDays, it.repetitions, it.lapses, it.learningStep, it.createdAt, it.updatedAt)) }
        snapshot.dailyPlans.forEach { add(row("P", it.id, it.localDate, it.quota, it.plannedUniqueWordCount, it.completedUniqueWordCount, it.status, it.createdAt, it.updatedAt)) }
        snapshot.dailyPlanItems.forEach { add(row("I", it.id, it.dailyPlanId, it.wordId, it.source, it.selectionRank, it.status)) }
        snapshot.intradayReviewEvents.forEach { add(row("V", it.id, it.dailyPlanItemId, it.wordId, it.mode, it.stepIndex, it.scheduledAt, it.completedAt, it.status, it.feedback)) }
        snapshot.reviewLogs.forEach { add(row("R", it.id, it.wordId, it.mode, it.reviewedAt, it.feedback, it.responseTimeMs, it.previousStateSnapshot, it.nextStateSnapshot, it.previousDueAt, it.nextDueAt, it.eventType)) }
        if (schemaVersion >= 4) snapshot.wordSenses.forEach {
            add(row("S", it.id, it.wordId, it.partOfSpeech, it.definitionCn, it.definitionSource, it.sortOrder, it.createdAt))
        }
    }.joinToString("\n")

    fun decode(payload: String): StudyDataSnapshot {
        val lines = payload.lines().filter { it.isNotBlank() }
        require(lines.firstOrNull() in setOf("v1", "v2", "v3", "v4")) { "Unsupported snapshot payload" }
        val payloadVersion = lines.first().removePrefix("v").toInt()
        val words = mutableListOf<Word>()
        val examples = mutableListOf<ExampleSentence>()
        val decks = mutableListOf<Deck>()
        val deckWords = mutableListOf<DeckWord>()
        val cards = mutableListOf<CardState>()
        val plans = mutableListOf<DailyPlan>()
        val items = mutableListOf<DailyPlanItem>()
        val events = mutableListOf<IntradayReviewEvent>()
        val logs = mutableListOf<ReviewLog>()
        val senses = mutableListOf<WordSense>()
        lines.drop(1).forEach { line ->
            val fields = line.split('|').drop(1).map(::decodeField)
            when (line.substringBefore('|')) {
                "W" -> words += Word(fields[0].long(), fields[1], fields[2], fields[3].nullable(), fields[4], fields[5], fields[6], fields[7].instant(), fields[8].instant(), fields.getOrNull(9)?.intOrNull(), fields.getOrNull(10)?.takeIf(String::isNotBlank) ?: definitionSourceFromTags(fields[6]), if (payloadVersion >= 3) fields.getOrNull(11).orEmpty() else "")
                "E" -> examples += ExampleSentence(fields[0].long(), fields[1].long(), fields[2], fields[3].nullable(), fields[4].int(), fields.getOrNull(5)?.takeIf(String::isNotBlank) ?: com.pengshi.words.model.SOURCE_UNVERIFIED)
                "D" -> decks += Deck(fields[0].long(), fields[1], DeckSourceType.valueOf(fields[2]), fields[3], fields[4].int(), fields[5].instant(), fields[6].instant())
                "L" -> deckWords += DeckWord(fields[0].long(), fields[1].long(), fields[2].int(), fields[3].instant())
                "C" -> cards += CardState(fields[0].long(), fields[1].long(), StudyMode.valueOf(fields[2]), CardStatus.valueOf(fields[3]), fields[4].double(), fields[5].double(), fields[6].double(), fields[7].instantOrNull(), fields[8].instantOrNull(), fields[9].int(), fields[10].int(), fields[11].int(), fields[12].int(), fields[13].instant(), fields[14].instant())
                "P" -> plans += DailyPlan(fields[0].long(), LocalDate.parse(fields[1]), fields[2].int(), fields[3].int(), fields[4].int(), DailyPlanStatus.valueOf(fields[5]), fields[6].instant(), fields[7].instant())
                "I" -> items += DailyPlanItem(fields[0].long(), fields[1].long(), fields[2].long(), PlanSource.valueOf(fields[3]), fields[4].int(), DailyItemStatus.valueOf(fields[5]))
                "V" -> events += IntradayReviewEvent(fields[0].long(), fields[1].long(), fields[2].long(), StudyMode.valueOf(fields[3]), fields[4].int(), fields[5].instant(), fields[6].instantOrNull(), IntradayEventStatus.valueOf(fields[7]), fields[8].enumOrNull<Feedback>())
                "R" -> logs += ReviewLog(fields[0].long(), fields[1].long(), StudyMode.valueOf(fields[2]), fields[3].instant(), Feedback.valueOf(fields[4]), fields[5].long(), fields[6], fields[7], fields[8].instantOrNull(), fields[9].instantOrNull(), ReviewEventType.valueOf(fields[10]))
                "S" -> senses += WordSense(
                    id = fields[0].long(), wordId = fields[1].long(), partOfSpeech = fields[2],
                    definitionCn = fields[3], definitionSource = fields[4],
                    normalizedKey = wordSenseKey(fields[2], fields[3]), sortOrder = fields[5].int(), createdAt = fields[6].instant(),
                )
                else -> error("Unknown backup row type")
            }
        }
        return StudyDataSnapshot(words, examples, decks, deckWords, cards, plans, items, events, logs, senses)
    }

    private fun row(type: String, vararg values: Any?): String = type + "|" + values.joinToString("|") { encodeField(it?.toString()) }

    private fun encodeField(value: String?): String = Base64.getEncoder().encodeToString((if (value == null) "N" else "V$value").toByteArray(StandardCharsets.UTF_8))
    private fun decodeField(value: String): String = String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8).let { if (it == "N") "" else it.removePrefix("V") }
    private fun String.nullable(): String? = if (this.isEmpty()) null else this
    private fun String.long() = toLong()
    private fun String.int() = toInt()
    private fun String.intOrNull() = takeIf { it.isNotEmpty() }?.toIntOrNull()
    private fun String.double() = toDouble()
    private fun String.instant() = Instant.parse(this)
    private fun String.instantOrNull() = takeIf { it.isNotEmpty() }?.let(Instant::parse)
    private inline fun <reified T : Enum<T>> String.enumOrNull(): T? = takeIf { it.isNotEmpty() }?.let { enumValueOf<T>(it) }
}
