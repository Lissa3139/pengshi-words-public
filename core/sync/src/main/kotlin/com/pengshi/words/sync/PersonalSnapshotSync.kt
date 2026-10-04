package com.pengshi.words.sync

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import com.pengshi.words.model.StudyDataSnapshot
import com.pengshi.words.model.wordSenseKey

data class SnapshotSummary(
    val wordCount: Int,
    val cardStateCount: Int,
    val reviewLogCount: Int,
    val dailyPlanCount: Int,
    val reviewedCardCount: Int = 0,
    val completedPlanCount: Int = 0,
    val userDeckCount: Int = 0,
    val userDeckWordCount: Int = 0,
    val exampleSentenceCount: Int = 0,
    /** Stable digest of dictionary content so source/mnemonic-only updates can sync. */
    val contentDigest: String = "",
)

data class CheckpointMetadata(
    val protocolVersion: Int,
    val revision: String,
    val perDeviceHighWatermarks: Map<String, Long>,
    val appliedEventDigest: String,
    val tableCounts: Map<String, Int>,
    val createdAt: Instant,
    val deviceId: String,
)

enum class CheckpointDecision {
    RESTORE_EMPTY_DEVICE,
    MERGE_CONTENT_ONLY,
    MERGE_INCREMENTAL,
    CREATE_NEW,
    KEEP_REMOTE,
    BLOCKED_BEHIND,
    BLOCKED_CONFLICT,
}

data class DecryptedCheckpoint(
    val metadata: CheckpointMetadata,
    val snapshotBytes: ByteArray,
    val appliedEvents: List<SyncEventRecord> = emptyList(),
)

object PersonalSnapshotSync {
    fun encrypt(password: String, snapshotBytes: ByteArray): ByteArray = SyncCrypto.encrypt(password, snapshotBytes)

    fun decrypt(password: String, encryptedSnapshot: ByteArray): ByteArray = SyncCrypto.decrypt(password, encryptedSnapshot)

    fun encryptCheckpoint(
        password: String,
        metadata: CheckpointMetadata,
        snapshotBytes: ByteArray,
        appliedEvents: List<SyncEventRecord> = emptyList(),
    ): ByteArray = SyncCrypto.encrypt(password, encodeCheckpoint(metadata, snapshotBytes, appliedEvents))

    fun decryptCheckpoint(password: String, encryptedCheckpoint: ByteArray): DecryptedCheckpoint =
        decodeCheckpoint(SyncCrypto.decrypt(password, encryptedCheckpoint))

    /**
     * Summarizes mergeable dictionary content without database ids or timestamps,
     * which may differ between devices for otherwise identical content.
     */
    fun contentDigest(snapshot: StudyDataSnapshot): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun field(value: String?) {
            val bytes = (value ?: "").toByteArray(StandardCharsets.UTF_8)
            digest.update(byteArrayOf(
                (bytes.size ushr 24).toByte(),
                (bytes.size ushr 16).toByte(),
                (bytes.size ushr 8).toByte(),
                bytes.size.toByte(),
            ))
            digest.update(bytes)
        }

        val wordsById = snapshot.words.associateBy { it.id }
        snapshot.words.sortedBy { it.normalizedSpelling }.forEach { word ->
            field("word")
            field(word.normalizedSpelling)
            field(word.spelling)
            field(word.phonetic)
            field(word.tags)
            field(word.mnemonic)
            field(word.frequencyRank?.toString())
        }
        val meanings = snapshot.words.mapNotNull { word ->
            word.definitionCn.takeIf(String::isNotBlank)?.let {
                Triple(word.normalizedSpelling, wordSenseKey(word.partOfSpeech, it), word.definitionSource)
            }
        } + snapshot.wordSenses.mapNotNull { sense ->
            val spelling = wordsById[sense.wordId]?.normalizedSpelling ?: return@mapNotNull null
            sense.definitionCn.takeIf(String::isNotBlank)?.let {
                Triple(spelling, wordSenseKey(sense.partOfSpeech, it), sense.definitionSource)
            }
        }
        meanings.distinct()
            .sortedWith(compareBy({ it.first }, { it.second }, { it.third }))
            .forEach { (spelling, meaningKey, source) ->
                field("meaning")
                field(spelling)
                field(meaningKey)
                field(source)
            }
        snapshot.exampleSentences
            .map { example -> (wordsById[example.wordId]?.normalizedSpelling.orEmpty()) to example }
            .sortedWith(compareBy({ it.first }, { it.second.sortOrder }, { it.second.sentenceEn }, { it.second.sentenceCn.orEmpty() }, { it.second.source }))
            .forEach { (spelling, example) ->
                field("example")
                field(spelling)
                field(example.sortOrder.toString())
                field(example.sentenceEn)
                field(example.sentenceCn)
                field(example.source)
            }
        val deckKeys = snapshot.decks.associate { deck ->
            deck.id to listOf(deck.sourceType.name, deck.name, deck.sourceFileName).joinToString("\u001e")
        }
        snapshot.decks.sortedWith(compareBy({ it.sourceType.name }, { it.name }, { it.sourceFileName })).forEach { deck ->
            field("deck")
            field(deck.sourceType.name)
            field(deck.name)
            field(deck.sourceFileName)
        }
        snapshot.deckWords
            .map { link ->
                Triple(deckKeys[link.deckId].orEmpty(), wordsById[link.wordId]?.normalizedSpelling.orEmpty(), link.position)
            }
            .sortedWith(compareBy({ it.first }, { it.second }, { it.third }))
            .forEach { (deckKey, spelling, position) ->
                field("deck-word")
                field(deckKey)
                field(spelling)
                field(position.toString())
            }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    fun decideCheckpoint(
        local: SnapshotSummary,
        remote: SnapshotSummary,
        localCheckpoint: CheckpointMetadata?,
        remoteCheckpoint: CheckpointMetadata?,
        localEvents: List<SyncEventRecord>? = null,
        remoteEvents: List<SyncEventRecord>? = null,
    ): CheckpointDecision {
        if (remoteCheckpoint == null) return if (hasLearningData(local)) {
            if (shouldMergeRemoteContent(local, remote)) CheckpointDecision.MERGE_CONTENT_ONLY
            else CheckpointDecision.CREATE_NEW
        } else {
            CheckpointDecision.KEEP_REMOTE
        }
        if (!hasLearningData(local)) {
            return when {
                shouldRestoreRemote(local, remote) -> CheckpointDecision.RESTORE_EMPTY_DEVICE
                shouldMergeRemoteContent(local, remote) -> CheckpointDecision.MERGE_CONTENT_ONLY
                else -> CheckpointDecision.KEEP_REMOTE
            }
        }
        if (localCheckpoint == null) return CheckpointDecision.BLOCKED_CONFLICT
        if (hasSequenceFork(localEvents, remoteEvents)) return CheckpointDecision.BLOCKED_CONFLICT
        if (remoteCheckpoint.appliedEventDigest == localCheckpoint.appliedEventDigest &&
            remoteCheckpoint.perDeviceHighWatermarks == localCheckpoint.perDeviceHighWatermarks
        ) return when {
            shouldMergeRemoteContent(local, remote) -> CheckpointDecision.MERGE_CONTENT_ONLY
            else -> CheckpointDecision.KEEP_REMOTE
        }
        // Snapshot table counts describe projections, not increments. Merge
        // immutable events through SyncCoordinator; never replace a learning DB.
        return CheckpointDecision.MERGE_INCREMENTAL
    }

    fun canContinueAfter(decision: CheckpointDecision): Boolean =
        decision != CheckpointDecision.BLOCKED_BEHIND && decision != CheckpointDecision.BLOCKED_CONFLICT

    fun canUploadCheckpoint(
        local: CheckpointMetadata,
        remote: CheckpointMetadata?,
        pendingApplicationCount: Int,
        localEvents: List<SyncEventRecord>? = null,
        remoteEvents: List<SyncEventRecord>? = null,
    ): Boolean = canUploadCheckpoint(
        local = local,
        remote = remote,
        remoteManifestRevision = remote?.revision,
        pendingApplicationCount = pendingApplicationCount,
        localEvents = localEvents,
        remoteEvents = remoteEvents,
    )

    /**
     * Allows a new bootstrap to replace an older bootstrap only after the local
     * database has caught up with the current immutable event manifest.
     *
     * A remote bootstrap can legitimately lag one manifest revision behind when
     * a device has just uploaded new event chunks. In that case the old
     * bootstrap metadata must not block publishing the newly merged checkpoint,
     * but the local cursor must equal the current remote manifest revision.
     */
    fun canUploadCheckpoint(
        local: CheckpointMetadata,
        remote: CheckpointMetadata?,
        remoteManifestRevision: String?,
        pendingApplicationCount: Int,
        localEvents: List<SyncEventRecord>? = null,
        remoteEvents: List<SyncEventRecord>? = null,
    ): Boolean {
        if (pendingApplicationCount != 0) return false
        if (!remoteManifestRevision.isNullOrBlank()) {
            // A current manifest revision proves the coordinator has pulled
            // every immutable chunk before checkpointing; stale snapshot row
            // counts must not prevent publishing the merged projection.
            if (local.revision != remoteManifestRevision) return false
            if (remote == null) return true
            val remoteAhead = remote.perDeviceHighWatermarks.any { (device, watermark) ->
                watermark > (local.perDeviceHighWatermarks[device] ?: 0L)
            }
            if (remoteAhead) return false
            val localHasAdditionalEvents = local.perDeviceHighWatermarks.any { (device, watermark) ->
                watermark > (remote.perDeviceHighWatermarks[device] ?: 0L)
            }
            if (localEvents != null && remoteEvents != null) {
                val localByCoordinate = localEvents.associateBy { SyncEventSequence(it.deviceId, it.sequence) }
                return remoteEvents.all { remoteEvent ->
                    localByCoordinate[SyncEventSequence(remoteEvent.deviceId, remoteEvent.sequence)]?.eventId == remoteEvent.eventId
                }
            }
            return localHasAdditionalEvents ||
                (local.perDeviceHighWatermarks == remote.perDeviceHighWatermarks &&
                    local.appliedEventDigest == remote.appliedEventDigest)
        }
        if (remote == null) {
            // A conflict retry can move the GitHub manifest ahead of the
            // local cursor without downloading the winning event chunk. Do
            // not create the first bootstrap from that stale local snapshot.
            return remoteManifestRevision.isNullOrBlank() || local.revision == remoteManifestRevision
        }
        return local.revision == remote.revision &&
            local.appliedEventDigest == remote.appliedEventDigest &&
            local.perDeviceHighWatermarks == remote.perDeviceHighWatermarks
    }

    /** A device with no learning progress can accept a remote learning snapshot.
     * Callers preserve local dictionary additions separately before replacing it.
     */
    fun shouldRestoreRemote(local: SnapshotSummary, remote: SnapshotSummary): Boolean {
        val localHasLearningData = local.reviewLogCount > 0 ||
            local.reviewedCardCount > 0 ||
            local.completedPlanCount > 0
        val remoteHasLearningData = remote.reviewLogCount > 0 ||
            remote.reviewedCardCount > 0 ||
            remote.completedPlanCount > 0
        return !localHasLearningData && remoteHasLearningData
    }

    /**
     * A local study history must not be replaced just to recover a remote
     * user deck. In that case callers merge dictionary content separately.
     */
    fun shouldMergeRemoteContent(local: SnapshotSummary, remote: SnapshotSummary): Boolean =
        remote.wordCount > local.wordCount ||
            remote.exampleSentenceCount > local.exampleSentenceCount ||
            remote.userDeckCount > local.userDeckCount ||
            remote.userDeckWordCount > local.userDeckWordCount ||
            (local.contentDigest.isNotBlank() && remote.contentDigest.isNotBlank() &&
                remote.contentDigest != local.contentDigest)

    private fun hasLearningData(summary: SnapshotSummary): Boolean =
        summary.reviewLogCount > 0 || summary.reviewedCardCount > 0 || summary.completedPlanCount > 0

    private fun encodeCheckpoint(
        metadata: CheckpointMetadata,
        snapshotBytes: ByteArray,
        appliedEvents: List<SyncEventRecord>,
    ): ByteArray {
        val values = listOf(
            metadata.protocolVersion.toString(),
            metadata.revision,
            metadata.perDeviceHighWatermarks.toSortedMap().entries.joinToString(",") { "${it.key}=${it.value}" },
            metadata.appliedEventDigest,
            metadata.tableCounts.toSortedMap().entries.joinToString(",") { "${it.key}=${it.value}" },
            metadata.createdAt.toString(),
            metadata.deviceId,
        ).joinToString("\u001f") { encodeField(it) }
        val events = Base64.getEncoder().encodeToString(SyncCodec.encodeEvents(appliedEvents))
        return "PENGSHI_CHECKPOINT_V2\n$values\n${Base64.getEncoder().encodeToString(snapshotBytes)}\n$events"
            .toByteArray(StandardCharsets.UTF_8)
    }

    private fun decodeCheckpoint(bytes: ByteArray): DecryptedCheckpoint {
        val lines = bytes.toString(StandardCharsets.UTF_8).split('\n')
        val version = lines.firstOrNull()
        require(
            (version == "PENGSHI_CHECKPOINT_V1" && lines.size == 3) ||
                (version == "PENGSHI_CHECKPOINT_V2" && lines.size == 4),
        ) { "Unsupported checkpoint envelope" }
        val values = lines[1].split("\u001f").map(::decodeField)
        require(values.size == 7) { "Invalid checkpoint metadata" }
        fun longMap(value: String): Map<String, Long> = value.split(',')
            .filter(String::isNotBlank)
            .associate { entry ->
                val separator = entry.lastIndexOf('=')
                require(separator > 0) { "Invalid checkpoint watermark" }
                entry.substring(0, separator) to entry.substring(separator + 1).toLong()
            }
        fun intMap(value: String): Map<String, Int> = value.split(',')
            .filter(String::isNotBlank)
            .associate { entry ->
                val separator = entry.lastIndexOf('=')
                require(separator > 0) { "Invalid checkpoint table count" }
                entry.substring(0, separator) to entry.substring(separator + 1).toInt()
            }
        return DecryptedCheckpoint(
            metadata = CheckpointMetadata(
                protocolVersion = values[0].toInt(),
                revision = values[1],
                perDeviceHighWatermarks = longMap(values[2]),
                appliedEventDigest = values[3],
                tableCounts = intMap(values[4]),
                createdAt = Instant.parse(values[5]),
                deviceId = values[6],
            ),
            snapshotBytes = Base64.getDecoder().decode(lines[2]),
            appliedEvents = if (version == "PENGSHI_CHECKPOINT_V2") {
                SyncCodec.decodeEvents(Base64.getDecoder().decode(lines[3]))
            } else {
                emptyList()
            },
        )
    }

    private fun compareMonotonicHistory(
        local: Map<String, Int>,
        remote: Map<String, Int>,
    ): HistoryRelation {
        var localGreater = false
        var remoteGreater = false
        for (key in MONOTONIC_HISTORY_TABLES) {
            val localCount = local[key] ?: 0
            val remoteCount = remote[key] ?: 0
            if (localCount > remoteCount) localGreater = true
            if (remoteCount > localCount) remoteGreater = true
        }
        return when {
            localGreater && remoteGreater -> HistoryRelation.DIVERGED
            localGreater -> HistoryRelation.LOCAL_DOMINATES
            remoteGreater -> HistoryRelation.REMOTE_DOMINATES
            else -> HistoryRelation.EQUAL
        }
    }

    private enum class HistoryRelation { EQUAL, LOCAL_DOMINATES, REMOTE_DOMINATES, DIVERGED }

    private fun hasSequenceFork(
        localEvents: List<SyncEventRecord>?,
        remoteEvents: List<SyncEventRecord>?,
    ): Boolean {
        if (localEvents == null || remoteEvents == null) return false
        val localBySequence = localEvents.associateBy { SyncEventSequence(it.deviceId, it.sequence) }
        return remoteEvents.any { remote ->
            localBySequence[SyncEventSequence(remote.deviceId, remote.sequence)]
                ?.eventId
                ?.let { it != remote.eventId }
                ?: false
        }
    }

    private val MONOTONIC_HISTORY_TABLES = listOf(
        "dailyPlans",
        "dailyPlanItems",
        "intradayReviewEvents",
        "reviewLogs",
        "syncEvents",
    )

    private fun encodeField(value: String): String = Base64.getEncoder().encodeToString(value.toByteArray(StandardCharsets.UTF_8))
    private fun decodeField(value: String): String = String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8)
}
