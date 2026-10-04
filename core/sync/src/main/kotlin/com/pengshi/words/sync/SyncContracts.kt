package com.pengshi.words.sync

interface SyncLocalStore {
    suspend fun readCursor(): SyncCursor

    suspend fun pendingEvents(): List<SyncEventRecord>

    /** Complete applied ledger embedded in a full personal-database checkpoint. */
    suspend fun checkpointEvents(): List<SyncEventRecord> = emptyList()

    /** Replaces a regressed ledger after the matching business snapshot has been restored. */
    suspend fun restoreCheckpointEvents(events: List<SyncEventRecord>, revision: String) = Unit

    /** Events received locally but not yet applied because a dependency is missing. */
    suspend fun pendingApplicationEvents(): List<SyncEventRecord> = emptyList()

    /** Events that cannot be applied automatically and require conflict handling. */
    suspend fun conflictApplicationEvents(): List<SyncEventRecord> = emptyList()

    suspend fun applyRemote(bundle: SyncDownload): SyncApplyResult

    /** Recheck locally stored deferred events even when the remote manifest is unchanged. */
    suspend fun retryDeferredApplications() = Unit

    suspend fun markUploaded(eventIds: Set<String>, revision: String)
}

/** Applies a downloaded business event to the platform's local database. */
fun interface SyncRemoteEventApplier {
    suspend fun apply(event: SyncEventRecord): Boolean
}

interface SyncRemote {
    suspend fun readManifest(etag: String?): ManifestResponse

    suspend fun download(ref: SyncFileRef): ByteArray

    suspend fun upload(batch: SyncUploadBatch): SyncWriteResult
}

interface GitHubTokenStore {
    suspend fun readToken(): String?

    suspend fun saveToken(token: String)

    suspend fun clearToken()
}

interface SyncPlanStore {
    suspend fun readLockedPlan(planKey: String): LockedPlan?

    /** Returns the existing plan when another device won the first-write race. */
    suspend fun createPlanIfAbsent(plan: LockedPlan): LockedPlan
}
