package com.pengshi.words.desktop.storage

import com.pengshi.words.sync.LocalChangeDraft
import com.pengshi.words.sync.SyncApplyResult
import com.pengshi.words.sync.SyncApplyState
import com.pengshi.words.sync.SyncCodec
import com.pengshi.words.sync.SyncCursor
import com.pengshi.words.sync.SyncDownload
import com.pengshi.words.sync.SyncEventRecord
import com.pengshi.words.sync.PlanSyncVersion
import com.pengshi.words.sync.SyncEventSequence
import com.pengshi.words.sync.SyncLocalStore
import com.pengshi.words.sync.SyncRemoteEventApplier
import com.pengshi.words.sync.StudySyncReducer
import com.pengshi.words.sync.cursorKey
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Types
import java.time.Instant
import java.util.UUID

class DesktopSyncStore(
    private val database: DesktopSqliteDatabase,
    val deviceId: String,
    var remoteEventApplier: SyncRemoteEventApplier? = null,
) : SyncLocalStore {
    init {
        require(deviceId.isNotBlank()) { "Device id must not be blank" }
    }

    override suspend fun readCursor(): SyncCursor = database.read { connection ->
        val events = connection.queryList("SELECT * FROM sync_events ORDER BY device_id, sequence") { it.toSyncEvent() }
        SyncCursor(
            revision = connection.queryOne(
                "SELECT value FROM sync_metadata WHERE `key` = ? LIMIT 1",
                { it.setString(1, REVISION_KEY) },
            ) { it.getString("value") },
            knownEventIds = events.mapTo(linkedSetOf(), SyncEventRecord::eventId),
            appliedEventIds = connection.queryList(
                "SELECT event_id FROM sync_events WHERE apply_state = ? ORDER BY device_id, sequence",
                { it.setString(1, SyncApplyState.APPLIED.name) },
                map = { it.getString("event_id") },
            ).toSet(),
            perDeviceHighWatermarks = connection.queryList(
                "SELECT device_id, MAX(sequence) AS high_watermark FROM sync_events GROUP BY device_id",
                map = { it.getString("device_id") to it.getLong("high_watermark") },
            ).toMap(),
            knownFiles = connection.queryOne(
                "SELECT value FROM sync_metadata WHERE `key` = ? LIMIT 1",
                { it.setString(1, FILES_KEY) },
            ) { it.getString("value") }
                ?.split('\n')
                ?.filter(String::isNotBlank)
                ?.toSet()
                ?: emptySet(),
            eventIdsBySequence = events.associate { SyncEventSequence(it.deviceId, it.sequence) to it.eventId },
            knownEventsById = events.associateBy(SyncEventRecord::eventId),
        )
    }

    override suspend fun pendingEvents(): List<SyncEventRecord> = database.read { connection ->
        connection.queryList(
            "SELECT * FROM sync_events WHERE uploaded_revision IS NULL ORDER BY occurred_at_utc, device_id, sequence",
            map = { it.toSyncEvent() },
        )
    }

    override suspend fun checkpointEvents(): List<SyncEventRecord> = database.read { connection ->
        connection.queryList(
            "SELECT * FROM sync_events ORDER BY device_id, sequence",
            map = { it.toSyncEvent() },
        )
    }

    override suspend fun restoreCheckpointEvents(events: List<SyncEventRecord>, revision: String) {
        database.transaction { connection ->
            connection.executeUpdate("DELETE FROM sync_events")
            events.sortedWith(compareBy(SyncEventRecord::deviceId, SyncEventRecord::sequence)).forEach { event ->
                connection.executeUpdate(
                    """INSERT INTO sync_events
                        (event_id, device_id, sequence, kind, occurred_at_utc, plan_key, word_key, payload, replaces_event_id,
                         uploaded_revision, apply_state, apply_attempts, last_apply_error, applied_at, source_file)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, NULL, ?, ?)""".trimIndent(),
                ) { statement ->
                    statement.setString(1, event.eventId)
                    statement.setString(2, event.deviceId)
                    statement.setLong(3, event.sequence)
                    statement.setString(4, event.kind.name)
                    statement.setLong(5, event.occurredAtUtc.toEpochMilli())
                    statement.setNullableString(6, event.planKey)
                    statement.setNullableString(7, event.wordKey)
                    statement.setString(8, event.payload)
                    statement.setNullableString(9, event.replacesEventId)
                    statement.setString(10, revision)
                    statement.setString(11, SyncApplyState.APPLIED.name)
                    statement.setLong(12, Instant.now().toEpochMilli())
                    statement.setString(13, CHECKPOINT_SOURCE)
                }
            }
            events.groupBy(SyncEventRecord::deviceId).forEach { (eventDeviceId, deviceEvents) ->
                val restoredSequence = deviceEvents.maxOf(SyncEventRecord::sequence)
                connection.executeUpdate(
                    """INSERT INTO sync_devices (device_id, next_sequence) VALUES (?, ?)
                        ON CONFLICT(device_id) DO UPDATE SET next_sequence = MAX(next_sequence, excluded.next_sequence)""".trimIndent(),
                ) {
                    it.setString(1, eventDeviceId)
                    it.setLong(2, restoredSequence)
                }
            }
            connection.putSyncMetadata(REVISION_KEY, revision)
        }
    }

    override suspend fun applyRemote(bundle: SyncDownload): SyncApplyResult {
        val inserted = linkedSetOf<String>()
        val changedPlans = linkedSetOf<String>()
        val events = bundle.manifest.chunks
            .filter { it.path in bundle.files }
            .flatMap { ref ->
                val bytes = bundle.files[ref.path] ?: error("Missing sync chunk: ${ref.path}")
                SyncCodec.decodeEvents(bytes).map { event -> ref.path to event }
            }
        database.transaction { connection ->
            events.forEach { (sourceFile, event) ->
                val count = connection.executeUpdate(
                    """INSERT OR IGNORE INTO sync_events
                        (event_id, device_id, sequence, kind, occurred_at_utc, plan_key, word_key, payload, replaces_event_id, uploaded_revision,
                         apply_state, apply_attempts, last_apply_error, applied_at, source_file)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".trimIndent(),
                ) { statement ->
                    statement.setString(1, event.eventId)
                    statement.setString(2, event.deviceId)
                    statement.setLong(3, event.sequence)
                    statement.setString(4, event.kind.name)
                    statement.setLong(5, event.occurredAtUtc.toEpochMilli())
                    statement.setNullableString(6, event.planKey)
                    statement.setNullableString(7, event.wordKey)
                    statement.setString(8, event.payload)
                    statement.setNullableString(9, event.replacesEventId)
                    statement.setString(10, bundle.manifest.revision)
                    statement.setString(11, SyncApplyState.RECEIVED.name)
                    statement.setInt(12, 0)
                    statement.setNull(13, Types.VARCHAR)
                    statement.setNull(14, Types.INTEGER)
                    statement.setString(15, sourceFile)
                }
                if (count > 0) {
                    inserted += event.eventId
                    event.planKey?.let(changedPlans::add)
                }
            }
            val files = buildSet {
                bundle.manifest.bootstrap?.let { add(it.cursorKey()) }
                bundle.manifest.chunks.forEach { add(it.cursorKey()) }
                bundle.manifest.plans.forEach { add(it.cursorKey()) }
                bundle.manifest.content.forEach { add(it.cursorKey()) }
            }
            connection.putSyncMetadata(REVISION_KEY, bundle.manifest.revision)
            connection.putSyncMetadata(FILES_KEY, files.sorted().joinToString("\n"))
        }
        for (event in StudySyncReducer.orderEvents(pendingApplicationEvents())) {
            applyRemoteEvent(event)
        }
        return SyncApplyResult(inserted, changedPlans)
    }

    override suspend fun pendingApplicationEvents(): List<SyncEventRecord> = database.read { connection ->
        connection.queryList(
            "SELECT * FROM sync_events WHERE apply_state IN (?, ?) ORDER BY occurred_at_utc, device_id, sequence",
            {
                it.setString(1, SyncApplyState.RECEIVED.name)
                it.setString(2, SyncApplyState.DEFERRED.name)
            },
            map = { it.toSyncEvent() },
        )
    }

    override suspend fun conflictApplicationEvents(): List<SyncEventRecord> = database.read { connection ->
        connection.queryList(
            "SELECT * FROM sync_events WHERE apply_state = ? ORDER BY occurred_at_utc, device_id, sequence",
            { it.setString(1, SyncApplyState.CONFLICT.name) },
            map = { it.toSyncEvent() },
        )
    }

    override suspend fun retryDeferredApplications() {
        for (event in StudySyncReducer.orderEvents(pendingApplicationEvents())) {
            applyRemoteEvent(event)
        }
    }

    suspend fun retryRemoteApplications() = retryDeferredApplications()

    override suspend fun markUploaded(eventIds: Set<String>, revision: String) {
        if (eventIds.isEmpty()) return
        database.transaction { connection ->
            eventIds.forEach { eventId ->
                connection.executeUpdate("UPDATE sync_events SET uploaded_revision = ? WHERE event_id = ?") {
                    it.setString(1, revision)
                    it.setString(2, eventId)
                }
            }
            connection.putSyncMetadata(REVISION_KEY, revision)
        }
    }

    fun appendChangeInTransaction(connection: java.sql.Connection, draft: LocalChangeDraft, occurredAtUtc: Instant): SyncEventRecord {
        val current = connection.queryOne(
            "SELECT next_sequence FROM sync_devices WHERE device_id = ? LIMIT 1",
            { it.setString(1, deviceId) },
        ) { it.getLong("next_sequence") } ?: 0L
        val next = current + 1
        if (current == 0L) {
            connection.executeUpdate("INSERT OR IGNORE INTO sync_devices (device_id, next_sequence) VALUES (?, ?)") {
                it.setString(1, deviceId)
                it.setLong(2, next)
            }
        } else {
            connection.executeUpdate("UPDATE sync_devices SET next_sequence = ? WHERE device_id = ?") {
                it.setLong(1, next)
                it.setString(2, deviceId)
            }
        }
        val event = SyncEventRecord(
            eventId = UUID.randomUUID().toString(),
            deviceId = deviceId,
            sequence = next,
            kind = draft.kind,
            occurredAtUtc = occurredAtUtc,
            planKey = draft.planKey,
            wordKey = draft.wordKey,
            payload = draft.payload,
            replacesEventId = draft.replacesEventId,
        )
        connection.executeUpdate(
            """INSERT INTO sync_events
                (event_id, device_id, sequence, kind, occurred_at_utc, plan_key, word_key, payload, replaces_event_id, uploaded_revision,
                 apply_state, apply_attempts)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, 0)""".trimIndent(),
        ) { statement ->
            statement.setString(1, event.eventId)
            statement.setString(2, event.deviceId)
            statement.setLong(3, event.sequence)
            statement.setString(4, event.kind.name)
            statement.setLong(5, event.occurredAtUtc.toEpochMilli())
            statement.setNullableString(6, event.planKey)
            statement.setNullableString(7, event.wordKey)
            statement.setString(8, event.payload)
            statement.setNullableString(9, event.replacesEventId)
            statement.setString(10, SyncApplyState.APPLIED.name)
        }
        return event
    }

    fun currentPlanVersionInTransaction(connection: java.sql.Connection, planKey: String): Int =
        PlanSyncVersion.fromPayloads(connection.queryList(
            "SELECT payload FROM sync_events WHERE plan_key = ? AND apply_state = 'APPLIED' AND kind IN ('PLAN_LOCKED_V2', 'PLAN_RECONCILED_V2')",
            { it.setString(1, planKey) },
            { it.getString(1) },
        ))

    private suspend fun applyRemoteEvent(event: SyncEventRecord) {
        val applier = remoteEventApplier ?: return
        val (state, error) = try {
            if (applier.apply(event)) {
                SyncApplyState.APPLIED to null
            } else {
                SyncApplyState.DEFERRED to "Remote event dependencies are not ready"
            }
        } catch (failure: Throwable) {
            SyncApplyState.DEFERRED to (failure.message ?: failure::class.simpleName ?: "Remote event application failed")
        }
        database.transaction { connection ->
            connection.executeUpdate(
                """UPDATE sync_events
                   SET apply_state = ?,
                       apply_attempts = apply_attempts + 1,
                       last_apply_error = ?,
                       applied_at = ?
                 WHERE event_id = ?""".trimIndent(),
            ) {
                it.setString(1, state.name)
                it.setNullableString(2, error)
                if (state == SyncApplyState.APPLIED) {
                    it.setLong(3, Instant.now().toEpochMilli())
                } else {
                    it.setNull(3, Types.INTEGER)
                }
                it.setString(4, event.eventId)
            }
        }
    }

    companion object {
        fun loadOrCreateDeviceId(path: Path): String {
            Files.createDirectories(path.toAbsolutePath().parent)
            val existing = if (Files.exists(path)) Files.readString(path).trim() else ""
            if (existing.isNotBlank()) return existing
            val created = "desktop-${UUID.randomUUID()}"
            Files.writeString(path, created)
            return created
        }

        private const val REVISION_KEY = "sync.cursor.revision"
        private const val FILES_KEY = "sync.cursor.files"
        private const val CHECKPOINT_SOURCE = "checkpoint"
    }
}

private fun java.sql.Connection.putSyncMetadata(key: String, value: String) {
    executeUpdate(
        "INSERT INTO sync_metadata (`key`, value) VALUES (?, ?) ON CONFLICT(`key`) DO UPDATE SET value = excluded.value",
    ) {
        it.setString(1, key)
        it.setString(2, value)
    }
}

private fun java.sql.ResultSet.toSyncEvent() = SyncEventRecord(
    eventId = getString("event_id"),
    deviceId = getString("device_id"),
    sequence = getLong("sequence"),
    kind = com.pengshi.words.sync.SyncEventKind.valueOf(getString("kind")),
    occurredAtUtc = Instant.ofEpochMilli(getLong("occurred_at_utc")),
    planKey = getString("plan_key"),
    wordKey = getString("word_key"),
    payload = getString("payload"),
    replacesEventId = getString("replaces_event_id"),
)
