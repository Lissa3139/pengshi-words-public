package com.pengshi.words.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.pengshi.words.sync.SyncApplyState
import java.time.Instant

@Dao
interface SyncDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDevice(device: SyncDeviceEntity): Long

    @Update
    suspend fun updateDevice(device: SyncDeviceEntity)

    @Query("SELECT * FROM sync_devices WHERE deviceId = :deviceId LIMIT 1")
    suspend fun getDevice(deviceId: String): SyncDeviceEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEvent(event: SyncEventEntity): Long

    @Query("SELECT * FROM sync_events WHERE uploadedRevision IS NULL ORDER BY occurredAtUtc, deviceId, sequence")
    suspend fun getPendingEvents(): List<SyncEventEntity>

    @Query("SELECT * FROM sync_events WHERE applyState IN (:states) ORDER BY occurredAtUtc, deviceId, sequence")
    suspend fun getPendingApplicationEvents(states: List<SyncApplyState>): List<SyncEventEntity>

    @Query("SELECT * FROM sync_events WHERE eventId = :eventId LIMIT 1")
    suspend fun getEvent(eventId: String): SyncEventEntity?

    @Query("SELECT * FROM sync_events ORDER BY deviceId, sequence")
    suspend fun getAllEvents(): List<SyncEventEntity>

    @Query("SELECT payload FROM sync_events WHERE planKey = :planKey AND applyState = 'APPLIED' AND kind IN ('PLAN_LOCKED_V2', 'PLAN_RECONCILED_V2')")
    suspend fun getAppliedPlanPayloads(planKey: String): List<String>

    @Query("DELETE FROM sync_events")
    suspend fun deleteAllEvents(): Int

    @Query("SELECT eventId FROM sync_events WHERE applyState = :state ORDER BY deviceId, sequence")
    suspend fun getEventIdsByApplyState(state: SyncApplyState): List<String>

    @Query("UPDATE sync_events SET uploadedRevision = :revision WHERE eventId IN (:eventIds)")
    suspend fun markUploaded(eventIds: List<String>, revision: String): Int

    @Query("""
        UPDATE sync_events
        SET applyState = :state,
            applyAttempts = applyAttempts + 1,
            lastApplyError = :error,
            appliedAt = :appliedAt
        WHERE eventId = :eventId
    """)
    suspend fun updateApplyState(
        eventId: String,
        state: SyncApplyState,
        error: String?,
        appliedAt: Instant?,
    ): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putMetadata(metadata: SyncMetadataEntity)

    @Query("SELECT value FROM sync_metadata WHERE `key` = :key LIMIT 1")
    suspend fun getMetadata(key: String): String?
}
