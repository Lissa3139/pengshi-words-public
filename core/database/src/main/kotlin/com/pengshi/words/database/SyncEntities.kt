package com.pengshi.words.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.pengshi.words.sync.SyncEventKind
import com.pengshi.words.sync.SyncApplyState
import java.time.Instant

@Entity(tableName = "sync_devices")
data class SyncDeviceEntity(
    @PrimaryKey val deviceId: String,
    val nextSequence: Long,
)

@Entity(tableName = "sync_metadata")
data class SyncMetadataEntity(
    @PrimaryKey val key: String,
    val value: String,
)

@Entity(
    tableName = "sync_events",
    indices = [
        Index(value = ["deviceId", "sequence"], unique = true),
        Index("planKey"),
        Index("wordKey"),
        Index("uploadedRevision"),
    ],
)
data class SyncEventEntity(
    @PrimaryKey val eventId: String,
    val deviceId: String,
    val sequence: Long,
    val kind: SyncEventKind,
    val occurredAtUtc: Instant,
    val planKey: String?,
    val wordKey: String?,
    val payload: String,
    val replacesEventId: String?,
    val uploadedRevision: String?,
    val applyState: SyncApplyState = SyncApplyState.RECEIVED,
    val applyAttempts: Int = 0,
    val lastApplyError: String? = null,
    val appliedAt: Instant? = null,
    val sourceFile: String? = null,
)
