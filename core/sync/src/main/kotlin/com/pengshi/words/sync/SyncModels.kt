package com.pengshi.words.sync

import com.pengshi.words.model.PlanSource
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

enum class SyncEventKind {
    FEEDBACK_RECORDED,
    FEEDBACK_REVISED,
    PLAN_LOCKED,
    EXTRA_WORD_ADDED,
    DECK_CHANGED,
    CONTENT_CHANGED,
    PLAN_LOCKED_V2,
    PLAN_RECONCILED_V2,
    FEEDBACK_APPLIED_V2,
    FEEDBACK_REVISED_V2,
}

data class SyncEventRecord(
    val eventId: String,
    val deviceId: String,
    val sequence: Long,
    val kind: SyncEventKind,
    val occurredAtUtc: Instant,
    val planKey: String?,
    val wordKey: String?,
    /** Versioned canonical payload. The sync layer does not interpret business fields. */
    val payload: String,
    val replacesEventId: String? = null,
)

enum class SyncApplyState {
    LOCAL_PENDING,
    RECEIVED,
    APPLIED,
    DEFERRED,
    CONFLICT,
    REJECTED,
}

object SyncKeyFactory {
    fun normalizeSpelling(spelling: String): String = spelling.trim().lowercase(java.util.Locale.ROOT).also {
        require(it.isNotEmpty()) { "Word spelling must not be blank" }
    }

    fun wordKey(normalizedSpelling: String): String = "word:${normalizeSpelling(normalizedSpelling)}"

    fun planKey(localDate: LocalDate): String = "plan:$localDate"

    fun itemKey(planKey: String, wordKey: String, source: PlanSource): String =
        "item:$planKey:$wordKey:${source.name.lowercase(java.util.Locale.ROOT)}"
}

data class SyncFileRef(
    val path: String,
    val sha: String,
    val byteCount: Long,
)

data class EventChunkRef(
    val deviceId: String,
    val firstSequence: Long,
    val lastSequence: Long,
    val contentHash: String,
    val fileRef: SyncFileRef,
)

object SyncChunkNaming {
    fun eventPath(
        deviceId: String,
        occurredAtUtc: Instant,
        firstSequence: Long,
        lastSequence: Long,
        contentHash: String,
    ): String {
        require(deviceId.isNotBlank() && !deviceId.contains('/')) { "Invalid device id" }
        require(firstSequence >= 0L && lastSequence >= firstSequence) { "Invalid event sequence range" }
        require(contentHash.isNotBlank() && !contentHash.contains('/')) { "Invalid content hash" }
        val month = occurredAtUtc.atZone(ZoneOffset.UTC).toLocalDate().toString().substring(0, 7)
        return "events/$deviceId/$month/$firstSequence-$lastSequence-$contentHash.enc"
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
}

object SyncManifestMerge {
    fun mergeImmutableChunks(old: List<SyncFileRef>, additions: List<SyncFileRef>): List<SyncFileRef> {
        val byPath = linkedMapOf<String, SyncFileRef>()
        for (ref in old + additions) {
            val existing = byPath[ref.path]
            if (existing == null) {
                byPath[ref.path] = ref
            } else if (existing.sha != ref.sha || existing.byteCount != ref.byteCount) {
                throw SyncConflict("Immutable sync chunk changed at path ${ref.path}")
            }
        }
        return byPath.values.sortedBy(SyncFileRef::path)
    }
}

fun SyncFileRef.cursorKey(): String = "$path#$sha"

data class SyncManifest(
    val schemaVersion: Int,
    val revision: String,
    val bootstrap: SyncFileRef?,
    val chunks: List<SyncFileRef>,
    val plans: List<SyncFileRef>,
    val content: List<SyncFileRef>,
)

data class SyncCursor(
    val revision: String? = null,
    val knownEventIds: Set<String> = emptySet(),
    /** Events whose payload has been applied to the local business database. */
    val appliedEventIds: Set<String> = emptySet(),
    val perDeviceHighWatermarks: Map<String, Long> = emptyMap(),
    val knownFiles: Set<String> = emptySet(),
    /** Stable event identity at each per-device sequence, used to reject forks. */
    val eventIdsBySequence: Map<SyncEventSequence, String> = emptyMap(),
    /** Full records allow idempotent redelivery checks to reject mutated duplicates. */
    val knownEventsById: Map<String, SyncEventRecord> = emptyMap(),
)

data class SyncEventSequence(val deviceId: String, val sequence: Long)

fun SyncCursor.appliedEventDigest(): String =
    "sha256:" + SyncChunkNaming.sha256(appliedEventIds.toList().sorted().joinToString("\n").encodeToByteArray())

data class SyncDownload(
    val manifest: SyncManifest,
    val files: Map<String, ByteArray>,
)

data class SyncApplyResult(
    val insertedEventIds: Set<String>,
    val changedPlanKeys: Set<String>,
)

data class SyncUploadBatch(
    val files: Map<String, ByteArray>,
    val baseRevision: String,
)

data class SyncWriteResult(
    val revision: String,
    val uploadedPaths: Set<String>,
)

data class ManifestResponse(
    val manifest: SyncManifest?,
    val etag: String?,
    val notModified: Boolean,
)
