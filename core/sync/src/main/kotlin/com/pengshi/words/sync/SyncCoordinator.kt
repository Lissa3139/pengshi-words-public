package com.pengshi.words.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneOffset

data class SyncResult(
    val status: SyncStatus,
    val downloadedFiles: Int,
    val uploadedEvents: Int,
    val elapsedMs: Long,
    val receivedEvents: Int = 0,
    val appliedEvents: Int = 0,
    val deferredEvents: Int = 0,
    val conflictEvents: Int = 0,
    val downloadedCheckpoints: Int = 0,
    val checkpointAction: SyncCheckpointAction = SyncCheckpointAction.NOT_CHECKED,
    val remoteRevision: String? = null,
    val detail: String? = null,
)

enum class SyncCheckpointAction {
    NOT_CHECKED,
    RESTORED_EMPTY_DEVICE,
    MERGED_CONTENT_ONLY,
    MERGING_INCREMENTAL,
    KEPT_LOCAL,
    CREATED,
    BLOCKED_BEHIND,
    BLOCKED_CONFLICT,
}

fun SyncResult.userMessage(): String {
    val revision = remoteRevision?.takeIf(String::isNotBlank)?.let { "，远端版本 $it" }.orEmpty()
    val details = "上传 $uploadedEvents 条 · 接收 $receivedEvents 条 · 应用 $appliedEvents 条 · 等待 $deferredEvents 条 · 冲突 $conflictEvents 条$revision"
    val completedTransferDetails = "上传 $uploadedEvents 条 · 接收 $receivedEvents 条 · 应用 $appliedEvents 条 · 冲突 $conflictEvents 条$revision"
    return when (status) {
        SyncStatus.UP_TO_DATE -> when (checkpointAction) {
            SyncCheckpointAction.RESTORED_EMPTY_DEVICE -> "已恢复学习历史、词库和选词设置；同步完成：$details"
            SyncCheckpointAction.MERGING_INCREMENTAL -> "已按事件增量核对检查点；同步完成：$details"
            SyncCheckpointAction.MERGED_CONTENT_ONLY -> "已合并词库内容且保留本地学习记录；同步完成：$details"
            else -> if (downloadedCheckpoints > 0 && checkpointAction == SyncCheckpointAction.NOT_CHECKED) {
                "同步完成：检查到恢复检查点，已按安全门禁处理；$details"
            } else {
                "同步完成：$details"
            }
        }
        SyncStatus.WAITING_FOR_DEPENDENCIES ->
            "同步尚未完成：有 $deferredEvents 条记录等待依赖补齐，请稍后重试；$completedTransferDetails"
        SyncStatus.AUTH_REQUIRED -> "请先在设置中填写 GitHub 仓库、同步密码和 Token。"
        SyncStatus.OFFLINE -> "GitHub 暂不可用，已保留本地待同步数据。"
        SyncStatus.CONFLICT -> "同步存在冲突，已暂停上传以防止旧数据覆盖${detail?.let { "（$it）" }.orEmpty()}；$details"
        SyncStatus.FAILED -> "同步失败，请检查仓库权限、同步密码和网络。"
        SyncStatus.SYNCING -> "正在同步……"
        SyncStatus.PENDING_UPLOAD -> "本地有待上传内容：$details"
    }
}

class SyncAuthenticationRequired(message: String = "GitHub sync authentication is required") : IllegalStateException(message)
class SyncConflict(message: String) : IllegalStateException(message)

class SyncCoordinator(
    private val localStore: SyncLocalStore,
    private val remote: SyncRemote,
    private val syncPassword: String?,
    private val now: () -> Instant = Instant::now,
) {
    /**
     * A sync is also triggered in the background after local feedback. Each
     * trigger creates a coordinator, so an instance-level mutex is not enough:
     * concurrent calls could read the same GitHub revision and race while
     * uploading a bootstrap snapshot. Keep the critical section process-wide.
     */
    suspend fun run(): SyncResult = processMutex.withLock { runInternal() }

    private suspend fun runInternal(): SyncResult {
        val startedAt = now()
        var downloadedFiles = 0
        var receivedEvents = 0
        var downloadedCheckpoints = 0
        var remoteRevision: String? = null
        var appliedEvents = 0
        return try {
            val password = syncPassword?.takeIf(String::isNotEmpty)
                ?: throw SyncAuthenticationRequired()
            val cursor = localStore.readCursor()
            val manifestResponse = remote.readManifest(cursor.revision)
            remoteRevision = manifestResponse.manifest?.revision ?: manifestResponse.etag ?: cursor.revision
            if (!manifestResponse.notModified && manifestResponse.manifest != null) {
                val manifest = manifestResponse.manifest
                val refs = allRefs(manifest).filterNot { it.cursorKey() in cursor.knownFiles }
                val files = refs.associate { ref ->
                    ref.path to SyncCrypto.decrypt(password, remote.download(ref))
                }
                downloadedFiles = files.size
                downloadedCheckpoints = refs.count { it.path == CHECKPOINT_PATH }
                remoteRevision = manifest.revision
                validateIncomingEventSequences(cursor, manifest, files)
                val applyResult = localStore.applyRemote(SyncDownload(manifest, files))
                receivedEvents = applyResult.insertedEventIds.size
            } else {
                localStore.retryDeferredApplications()
            }

            val afterPull = localStore.readCursor()
            appliedEvents = (afterPull.appliedEventIds - cursor.appliedEventIds).size
            val pendingEvents = localStore.pendingEvents()
            if (pendingEvents.isEmpty()) {
                val applicationState = applicationState(localStore)
                return result(
                    status = applicationState.status,
                    downloadedFiles = downloadedFiles,
                    uploadedEvents = 0,
                    startedAt = startedAt,
                    receivedEvents = receivedEvents,
                    appliedEvents = appliedEvents,
                    deferredEvents = applicationState.deferredEvents,
                    conflictEvents = applicationState.conflictEvents,
                    downloadedCheckpoints = downloadedCheckpoints,
                    remoteRevision = remoteRevision,
                )
            }

            val uploadFiles = pendingEvents
                .groupBy { event ->
                    event.deviceId to event.occurredAtUtc.atZone(ZoneOffset.UTC).toLocalDate().toString().substring(0, 7)
                }
                .map { (_, events) ->
                    val orderedEvents = events.sortedBy(SyncEventRecord::sequence)
                    val encrypted = SyncCrypto.encrypt(password, SyncCodec.encodeEvents(orderedEvents))
                    val first = orderedEvents.first()
                    val last = orderedEvents.last()
                    val path = SyncChunkNaming.eventPath(
                        deviceId = first.deviceId,
                        occurredAtUtc = first.occurredAtUtc,
                        firstSequence = first.sequence,
                        lastSequence = last.sequence,
                        contentHash = SyncChunkNaming.sha256(encrypted),
                    )
                    path to encrypted
                }
                .toMap()
            val baseRevision = manifestResponse.manifest?.revision ?: cursor.revision.orEmpty()
            val upload = uploadWithConflictRetry(uploadFiles, baseRevision)
            val cursorRevision = if (upload.retriedAfterConflict) {
                // The retry only changed the CAS base. We did not pull the
                // remote chunk that won the race, so advancing the local
                // cursor would make the next sync skip that remote event.
                cursor.revision.orEmpty()
            } else {
                upload.writeResult.revision
            }
            localStore.markUploaded(pendingEvents.mapTo(linkedSetOf()) { it.eventId }, cursorRevision)
            remoteRevision = upload.writeResult.revision
            val applicationState = applicationState(localStore)
            result(
                status = applicationState.status,
                downloadedFiles = downloadedFiles,
                uploadedEvents = pendingEvents.size,
                startedAt = startedAt,
                receivedEvents = receivedEvents,
                appliedEvents = appliedEvents,
                deferredEvents = applicationState.deferredEvents,
                conflictEvents = applicationState.conflictEvents,
                downloadedCheckpoints = downloadedCheckpoints,
                remoteRevision = remoteRevision,
            )
        } catch (_: SyncAuthenticationRequired) {
            result(SyncStatus.AUTH_REQUIRED, 0, 0, startedAt)
        } catch (conflict: SyncConflict) {
            val state = runCatching { applicationState(localStore) }.getOrNull()
            SyncResult(
                status = SyncStatus.CONFLICT,
                downloadedFiles = downloadedFiles,
                uploadedEvents = 0,
                elapsedMs = (now().toEpochMilli() - startedAt.toEpochMilli()).coerceAtLeast(0),
                receivedEvents = receivedEvents,
                appliedEvents = appliedEvents,
                deferredEvents = state?.deferredEvents ?: 0,
                conflictEvents = (state?.conflictEvents ?: 0) + 1,
                downloadedCheckpoints = downloadedCheckpoints,
                remoteRevision = remoteRevision,
                detail = conflict.message,
            )
        } catch (_: java.io.IOException) {
            result(SyncStatus.OFFLINE, 0, 0, startedAt)
        } catch (_: Throwable) {
            result(SyncStatus.FAILED, 0, 0, startedAt)
        }
    }

    private fun allRefs(manifest: SyncManifest): List<SyncFileRef> = buildList {
        manifest.bootstrap?.let(::add)
        addAll(manifest.chunks)
        addAll(manifest.plans)
        addAll(manifest.content)
    }

    private fun validateIncomingEventSequences(
        cursor: SyncCursor,
        manifest: SyncManifest,
        files: Map<String, ByteArray>,
    ) {
        val knownBySequence = cursor.eventIdsBySequence.toMutableMap()
        val knownCoordinateById = cursor.eventIdsBySequence.entries
            .associate { (coordinate, eventId) -> eventId to coordinate }
            .toMutableMap()
        val knownEventsById = cursor.knownEventsById.toMutableMap()
        val incoming = manifest.chunks
            .filter { it.path in files }
            .flatMap { ref -> SyncCodec.decodeEvents(requireNotNull(files[ref.path])) }
        for (event in incoming) {
            val coordinate = SyncEventSequence(event.deviceId, event.sequence)
            val existingEventId = knownBySequence[coordinate]
            if (existingEventId != null && existingEventId != event.eventId) {
                throw SyncConflict("同一设备的序号 ${event.deviceId}/${event.sequence} 对应不同事件；已保留两端原始数据")
            }
            val existingCoordinate = knownCoordinateById[event.eventId]
            if (existingCoordinate != null && existingCoordinate != coordinate) {
                throw SyncConflict("事件 ${event.eventId} 被用于不同设备序号；已暂停应用以防止重复计数")
            }
            val existingEvent = knownEventsById[event.eventId]
            if (existingEvent != null && existingEvent != event) {
                throw SyncConflict("同一事件 ID 对应了不同内容；已暂停同步以保留原始数据")
            }
            knownBySequence[coordinate] = event.eventId
            knownCoordinateById[event.eventId] = coordinate
            knownEventsById[event.eventId] = event
        }
    }

    private suspend fun applicationState(localStore: SyncLocalStore): ApplicationState {
        val conflictEvents = localStore.conflictApplicationEvents().size
        val deferredEvents = localStore.pendingApplicationEvents().size
        return ApplicationState(
            status = when {
                conflictEvents > 0 -> SyncStatus.CONFLICT
                deferredEvents > 0 -> SyncStatus.WAITING_FOR_DEPENDENCIES
                else -> SyncStatus.UP_TO_DATE
            },
            deferredEvents = deferredEvents,
            conflictEvents = conflictEvents,
        )
    }

    private suspend fun uploadWithConflictRetry(files: Map<String, ByteArray>, initialRevision: String): UploadAttempt {
        var baseRevision = initialRevision
        var retriedAfterConflict = false
        repeat(2) { attempt ->
            try {
                return UploadAttempt(
                    writeResult = remote.upload(SyncUploadBatch(files, baseRevision)),
                    retriedAfterConflict = retriedAfterConflict,
                )
            } catch (conflict: SyncConflict) {
                if (attempt == 1) throw conflict
                retriedAfterConflict = true
                baseRevision = remote.readManifest(null).manifest?.revision.orEmpty()
            }
        }
        error("Unreachable")
    }

    private data class UploadAttempt(
        val writeResult: SyncWriteResult,
        val retriedAfterConflict: Boolean,
    )

    private fun result(
        status: SyncStatus,
        downloadedFiles: Int,
        uploadedEvents: Int,
        startedAt: Instant,
        receivedEvents: Int = 0,
        appliedEvents: Int = 0,
        deferredEvents: Int = 0,
        conflictEvents: Int = 0,
        downloadedCheckpoints: Int = 0,
        checkpointAction: SyncCheckpointAction = SyncCheckpointAction.NOT_CHECKED,
        remoteRevision: String? = null,
    ) = SyncResult(
        status = status,
        downloadedFiles = downloadedFiles,
        uploadedEvents = uploadedEvents,
        elapsedMs = (now().toEpochMilli() - startedAt.toEpochMilli()).coerceAtLeast(0),
        receivedEvents = receivedEvents,
        appliedEvents = appliedEvents,
        deferredEvents = deferredEvents,
        conflictEvents = conflictEvents,
        downloadedCheckpoints = downloadedCheckpoints,
        checkpointAction = checkpointAction,
        remoteRevision = remoteRevision,
    )

    private data class ApplicationState(
        val status: SyncStatus,
        val deferredEvents: Int,
        val conflictEvents: Int,
    )

    private companion object {
        val processMutex = Mutex()
        const val CHECKPOINT_PATH = "sync/state/bootstrap.enc"
    }
}
