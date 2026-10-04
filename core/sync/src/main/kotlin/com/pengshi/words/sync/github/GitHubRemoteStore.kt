package com.pengshi.words.sync.github

import com.pengshi.words.sync.ManifestResponse
import com.pengshi.words.sync.SyncCrypto
import com.pengshi.words.sync.SyncFileRef
import com.pengshi.words.sync.SyncManifest
import com.pengshi.words.sync.SyncManifestCodec
import com.pengshi.words.sync.SyncManifestMerge
import com.pengshi.words.sync.SyncRemote
import com.pengshi.words.sync.SyncUploadBatch
import com.pengshi.words.sync.SyncWriteResult
import com.pengshi.words.sync.cursorKey
import java.util.UUID

class GitHubRemoteStore(
    private val api: GitHubApi,
    private val config: GitHubRepositoryConfig,
    private val syncPassword: String,
) : SyncRemote {
    init {
        require(syncPassword.isNotBlank()) { "Sync password is required" }
    }

    override suspend fun readManifest(etag: String?): ManifestResponse {
        return when (val branch = api.readBranchState(config, etag)) {
            is GitHubApi.BranchReadResult.NotModified -> ManifestResponse(null, branch.etag, true)
            is GitHubApi.BranchReadResult.State -> {
                val manifestEntry = branch.state.entries.firstOrNull { it.path == MANIFEST_PATH && it.type == "blob" }
                if (manifestEntry == null) {
                    ManifestResponse(null, branch.state.commitSha, false)
                } else {
                    val manifest = SyncManifestCodec.decode(
                        SyncCrypto.decrypt(syncPassword, api.downloadBlob(config, manifestEntry.sha)),
                    ).copy(revision = branch.state.commitSha)
                    ManifestResponse(manifest, branch.state.commitSha, false)
                }
            }
        }
    }

    override suspend fun download(ref: SyncFileRef): ByteArray = api.downloadBlob(config, ref.sha)

    override suspend fun upload(batch: SyncUploadBatch): SyncWriteResult {
        val branch = when (val result = api.readBranchState(config, batch.baseRevision.takeIf(String::isNotBlank))) {
            is GitHubApi.BranchReadResult.NotModified -> error("GitHub returned not-modified while uploading")
            is GitHubApi.BranchReadResult.State -> result.state
        }
        if (batch.baseRevision.isNotBlank() && branch.commitSha != batch.baseRevision) {
            throw com.pengshi.words.sync.SyncConflict("GitHub branch changed before upload")
        }
        val manifestEntry = branch.entries.firstOrNull { it.path == MANIFEST_PATH && it.type == "blob" }
        val oldManifest = manifestEntry?.let {
            SyncManifestCodec.decode(SyncCrypto.decrypt(syncPassword, api.downloadBlob(config, it.sha)))
        } ?: SyncManifest(1, branch.commitSha, null, emptyList(), emptyList(), emptyList())
        val blobShas = batch.files.mapValues { (_, bytes) -> api.createBlob(config, bytes) }
        val refs = blobShas.map { (path, sha) -> SyncFileRef(path, sha, batch.files.getValue(path).size.toLong()) }
        val manifest = oldManifest.copy(
            revision = "pending-${UUID.randomUUID()}",
            bootstrap = refs.firstOrNull { it.path == BOOTSTRAP_PATH } ?: oldManifest.bootstrap,
            chunks = SyncManifestMerge.mergeImmutableChunks(
                oldManifest.chunks,
                refs.filter { it.path.startsWith("events/") },
            ),
            plans = merge(oldManifest.plans, refs.filter { it.path.startsWith("plans/") }),
            content = merge(oldManifest.content, refs.filter { it.path.startsWith("user-content/") }),
        )
        val manifestBytes = SyncCrypto.encrypt(syncPassword, SyncManifestCodec.encode(manifest))
        val manifestSha = api.createBlob(config, manifestBytes)
        val treeSha = api.createTree(config, branch.treeSha, blobShas + (MANIFEST_PATH to manifestSha))
        val commitSha = api.createCommit(config, "Sync PengshiWords", treeSha, branch.commitSha)
        api.updateBranch(config, commitSha)
        return SyncWriteResult(commitSha, blobShas.keys + MANIFEST_PATH)
    }

    suspend fun readBootstrap(): RemoteBootstrap? {
        val response = readManifest(null)
        val manifest = response.manifest ?: return null
        val ref = manifest.bootstrap ?: return null
        return RemoteBootstrap(manifest.revision, ref, download(ref))
    }

    suspend fun uploadBootstrap(encryptedSnapshot: ByteArray, baseRevision: String): SyncWriteResult =
        upload(
            SyncUploadBatch(
                files = mapOf(BOOTSTRAP_PATH to encryptedSnapshot),
                baseRevision = baseRevision,
            ),
        )

    private fun merge(old: List<SyncFileRef>, additions: List<SyncFileRef>): List<SyncFileRef> =
        (old + additions).associateBy(SyncFileRef::path).values.sortedBy(SyncFileRef::path)

    private companion object {
        const val MANIFEST_PATH = "sync/manifest.enc"
        const val BOOTSTRAP_PATH = "sync/state/bootstrap.enc"
    }
}

data class RemoteBootstrap(
    val revision: String,
    val ref: SyncFileRef,
    val encryptedBytes: ByteArray,
)
