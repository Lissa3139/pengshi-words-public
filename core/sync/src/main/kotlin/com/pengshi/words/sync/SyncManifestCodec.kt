package com.pengshi.words.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object SyncManifestCodec {
    private val json = Json { prettyPrint = false; encodeDefaults = true }

    fun encode(manifest: SyncManifest): ByteArray = buildJsonObject {
        put("schemaVersion", JsonPrimitive(manifest.schemaVersion))
        put("revision", JsonPrimitive(manifest.revision))
        put("bootstrap", manifest.bootstrap?.toJson() ?: JsonNull)
        put("chunks", manifest.chunks.toJsonArray())
        put("plans", manifest.plans.toJsonArray())
        put("content", manifest.content.toJsonArray())
    }.let { element -> json.encodeToString(JsonElement.serializer(), element) }.encodeToByteArray()

    fun decode(encoded: ByteArray): SyncManifest {
        val root = json.parseToJsonElement(encoded.decodeToString()).jsonObject
        return SyncManifest(
            schemaVersion = root.requiredInt("schemaVersion"),
            revision = root.requiredString("revision"),
            bootstrap = root["bootstrap"]?.takeUnless { it is JsonNull }?.let(::syncFileRefFromJson),
            chunks = root.requiredArray("chunks").map(::syncFileRefFromJson),
            plans = root.requiredArray("plans").map(::syncFileRefFromJson),
            content = root.requiredArray("content").map(::syncFileRefFromJson),
        )
    }

    private fun SyncFileRef.toJson() = buildJsonObject {
        put("path", JsonPrimitive(path))
        put("sha", JsonPrimitive(sha))
        put("byteCount", JsonPrimitive(byteCount))
    }

    private fun List<SyncFileRef>.toJsonArray(): JsonArray = buildJsonArray { forEach { add(it.toJson()) } }

    private fun syncFileRefFromJson(element: JsonElement): SyncFileRef {
        val json = element.jsonObject
        return SyncFileRef(
            path = json.requiredString("path"),
            sha = json.requiredString("sha"),
            byteCount = json.requiredLong("byteCount"),
        )
    }

    private fun JsonObject.requiredString(key: String): String = getValue(key).jsonPrimitive.content
    private fun JsonObject.requiredInt(key: String): Int = requiredString(key).toInt()
    private fun JsonObject.requiredLong(key: String): Long = requiredString(key).toLong()
    private fun JsonObject.requiredArray(key: String): JsonArray = getValue(key).jsonArray
}
