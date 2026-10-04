package com.pengshi.words.sync.github

import com.pengshi.words.sync.SyncConflict
import com.pengshi.words.sync.SyncAuthenticationRequired
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64
import java.util.concurrent.TimeUnit

class GitHubApi(
    private val token: String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build(),
    private val baseUrl: String = "https://api.github.com",
) {
    init {
        require(token.isNotBlank()) { "GitHub token is required" }
    }

    suspend fun readBranchState(config: GitHubRepositoryConfig, ifNoneMatch: String? = null): BranchReadResult = withContext(Dispatchers.IO) {
        val refResponse = execute(
            method = "GET",
            path = "/repos/${config.owner}/${config.repository}/git/ref/heads/${config.branch}",
            ifNoneMatch = ifNoneMatch,
        )
        if (refResponse.status == 304) return@withContext BranchReadResult.NotModified(refResponse.etag)
        val commitSha = refResponse.json().requiredObject("object").requiredString("sha")
        val commit = execute("GET", "/repos/${config.owner}/${config.repository}/git/commits/$commitSha").json()
        val treeSha = commit.requiredObject("tree").requiredString("sha")
        val tree = execute("GET", "/repos/${config.owner}/${config.repository}/git/trees/$treeSha?recursive=1").json()
        val entries = tree.requiredArray("tree").map { item ->
            val value = item.jsonObject
            GitHubTreeEntry(
                path = value.requiredString("path"),
                type = value.requiredString("type"),
                sha = value.requiredString("sha"),
            )
        }
        BranchReadResult.State(GitHubBranchState(commitSha, treeSha, entries), refResponse.etag)
    }

    suspend fun downloadBlob(config: GitHubRepositoryConfig, sha: String): ByteArray = withContext(Dispatchers.IO) {
        val json = execute("GET", "/repos/${config.owner}/${config.repository}/git/blobs/$sha").json()
        Base64.getMimeDecoder().decode(json.requiredString("content"))
    }

    suspend fun createBlob(config: GitHubRepositoryConfig, bytes: ByteArray): String = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("content", Base64.getEncoder().encodeToString(bytes))
            put("encoding", "base64")
        }
        execute("POST", "/repos/${config.owner}/${config.repository}/git/blobs", body).json().requiredString("sha")
    }

    suspend fun createTree(config: GitHubRepositoryConfig, baseTreeSha: String, blobs: Map<String, String>): String = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("base_tree", baseTreeSha)
            put("tree", buildJsonArray {
                blobs.toSortedMap().forEach { (path, sha) ->
                    add(buildJsonObject {
                        put("path", path)
                        put("mode", "100644")
                        put("type", "blob")
                        put("sha", sha)
                    })
                }
            })
        }
        execute("POST", "/repos/${config.owner}/${config.repository}/git/trees", body).json().requiredString("sha")
    }

    suspend fun createCommit(config: GitHubRepositoryConfig, message: String, treeSha: String, parentSha: String): String = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("message", message)
            put("tree", treeSha)
            put("parents", buildJsonArray { add(JsonPrimitive(parentSha)) })
        }
        execute("POST", "/repos/${config.owner}/${config.repository}/git/commits", body).json().requiredString("sha")
    }

    suspend fun updateBranch(config: GitHubRepositoryConfig, commitSha: String) {
        withContext(Dispatchers.IO) {
        val body = buildJsonObject { put("sha", commitSha); put("force", false) }
        execute("PATCH", "/repos/${config.owner}/${config.repository}/git/refs/heads/${config.branch}", body)
        }
    }

    private suspend fun execute(
        method: String,
        path: String,
        body: JsonObject? = null,
        ifNoneMatch: String? = null,
    ): RawResponse = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(baseUrl.trimEnd('/') + path)
            .header("Accept", "application/vnd.github+json")
            .header("Authorization", "Bearer $token")
            .header("X-GitHub-Api-Version", "2022-11-28")
        if (ifNoneMatch != null) builder.header("If-None-Match", ifNoneMatch)
        val request = when (method) {
            "GET" -> builder.get().build()
            "POST" -> builder.post(requireNotNull(body).toString().toRequestBody(JSON)).build()
            "PATCH" -> builder.patch(requireNotNull(body).toString().toRequestBody(JSON)).build()
            else -> error("Unsupported GitHub method: $method")
        }
        client.newCall(request).execute().use { response ->
            val bytes = response.body?.bytes() ?: ByteArray(0)
            if (response.code !in 200..299 && response.code != 304) {
                if (response.code == 401 || response.code == 403) throw SyncAuthenticationRequired("GitHub authentication failed (${response.code})")
                if (response.code == 409) throw SyncConflict("GitHub branch changed during sync")
                throw GitHubSyncException(response.code, "GitHub API ${response.code}: ${bytes.decodeToString()}")
            }
            RawResponse(response.code, response.header("ETag"), bytes)
        }
    }

    private fun RawResponse.json(): JsonObject = Json.parseToJsonElement(body.decodeToString()).jsonObject

    private data class RawResponse(val status: Int, val etag: String?, val body: ByteArray)

    sealed interface BranchReadResult {
        data class State(val state: GitHubBranchState, val etag: String?) : BranchReadResult
        data class NotModified(val etag: String?) : BranchReadResult
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()

        fun JsonObject.requiredString(key: String): String = getValue(key).jsonPrimitive.content
        fun JsonObject.requiredObject(key: String): JsonObject = getValue(key).jsonObject
        fun JsonObject.requiredArray(key: String): JsonArray = getValue(key).jsonArray
    }
}
