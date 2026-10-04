package com.pengshi.words.sync

import com.pengshi.words.model.CardStatus
import com.pengshi.words.model.DailyItemStatus
import com.pengshi.words.model.DailyQuota
import com.pengshi.words.model.Feedback
import com.pengshi.words.model.IntradayEventStatus
import com.pengshi.words.model.PlanSource
import com.pengshi.words.model.StudyMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.long
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.LocalDate

private const val V2 = 2

private fun JsonObjectBuilder.put(key: String, value: String) = put(key, JsonPrimitive(value))
private fun JsonObjectBuilder.put(key: String, value: Int) = put(key, JsonPrimitive(value))
private fun JsonObjectBuilder.put(key: String, value: Long) = put(key, JsonPrimitive(value))
private fun JsonObjectBuilder.put(key: String, value: Double) = put(key, JsonPrimitive(value))

sealed interface SyncPayloadV2Model {
    val payloadVersion: Int
    val type: String
}

data class PlanItemV2(
    val itemKey: String,
    val wordKey: String,
    val source: PlanSource,
    val position: Int,
)

data class PlanLockedV2(
    val planKey: String,
    val localDate: LocalDate,
    val studyMode: StudyMode,
    val quota: Int,
    val planVersion: Int,
    val createdAtUtc: Instant,
    val createdByDeviceId: String,
    val items: List<PlanItemV2>,
    val selectionAlgorithmVersion: String,
    val poolDigest: String,
) : SyncPayloadV2Model {
    init {
        DailyQuota.requireValid(quota)
        require(items.count { it.source != PlanSource.EXTRA } <= quota) {
            "A locked main plan cannot exceed its quota"
        }
    }
    override val payloadVersion: Int = V2
    override val type: String = "PLAN_LOCKED_V2"
}

data class PlanReconciledV2(
    val planKey: String,
    val localDate: LocalDate,
    val studyMode: StudyMode,
    val oldPlanVersion: Int,
    val newPlanVersion: Int,
    val retainedItemKeys: List<String>,
    val removedItemKeys: List<String>,
    val addedItems: List<PlanItemV2>,
    val reason: String,
    /** Optional for compatibility with PLAN_RECONCILED_V2 events written by older clients. */
    val quota: Int? = null,
) : SyncPayloadV2Model {
    init {
        quota?.let(DailyQuota::requireValid)
    }
    override val payloadVersion: Int = V2
    override val type: String = "PLAN_RECONCILED_V2"
}

data class CardSnapshotV2(
    val status: CardStatus,
    val difficulty: Double,
    val stability: Double,
    val retrievability: Double,
    val dueAtUtc: Instant?,
    val scheduledDays: Int,
    val lapses: Int,
    val learningStep: Int,
    val lastFeedbackEventId: String?,
)

sealed interface FeedbackPayloadV2 : SyncPayloadV2Model {
    val planKey: String
    val planVersion: Int
    val itemKey: String
    val wordKey: String
    val studyMode: StudyMode
    val feedback: Feedback
    val reviewedAtUtc: Instant
    val responseTimeMs: Long
    val shortTermStep: Int
    val baseCardVersion: Int
    val previousReviewEventId: String?
    val fsrsAlgorithmVersion: String
    val card: CardSnapshotV2
    val itemStatus: DailyItemStatus
    val shortTermEventStatus: IntradayEventStatus
}

data class FeedbackAppliedV2(
    override val planKey: String,
    override val planVersion: Int,
    override val itemKey: String,
    override val wordKey: String,
    override val studyMode: StudyMode,
    override val feedback: Feedback,
    override val reviewedAtUtc: Instant,
    override val responseTimeMs: Long,
    override val shortTermStep: Int,
    override val baseCardVersion: Int,
    override val previousReviewEventId: String?,
    override val fsrsAlgorithmVersion: String,
    override val card: CardSnapshotV2,
    override val itemStatus: DailyItemStatus,
    override val shortTermEventStatus: IntradayEventStatus,
) : FeedbackPayloadV2 {
    override val payloadVersion: Int = V2
    override val type: String = "FEEDBACK_APPLIED_V2"
}

data class FeedbackRevisedV2(
    val replacesEventId: String,
    override val planKey: String,
    override val planVersion: Int,
    override val itemKey: String,
    override val wordKey: String,
    override val studyMode: StudyMode,
    override val feedback: Feedback,
    override val reviewedAtUtc: Instant,
    override val responseTimeMs: Long,
    override val shortTermStep: Int,
    override val baseCardVersion: Int,
    override val previousReviewEventId: String?,
    override val fsrsAlgorithmVersion: String,
    override val card: CardSnapshotV2,
    override val itemStatus: DailyItemStatus,
    override val shortTermEventStatus: IntradayEventStatus,
) : FeedbackPayloadV2 {
    override val payloadVersion: Int = V2
    override val type: String = "FEEDBACK_REVISED_V2"
}

class UnsupportedSyncPayloadVersion(version: Int) : IllegalArgumentException("Unsupported sync payload version: $version")
class InvalidSyncPayload(message: String) : IllegalArgumentException(message)

object SyncPayloadV2 {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    fun encode(payload: SyncPayloadV2Model): String = payload.toJson().toString()

    fun decode(encoded: String): SyncPayloadV2Model {
        val objectValue = runCatching { json.parseToJsonElement(encoded).jsonObject }
            .getOrElse { throw InvalidSyncPayload("Payload is not a JSON object") }
        val version = objectValue.requiredInt("payloadVersion")
        if (version != V2) throw UnsupportedSyncPayloadVersion(version)
        return try {
            when (objectValue.requiredString("type")) {
                "PLAN_LOCKED_V2" -> objectValue.toPlanLocked()
                "PLAN_RECONCILED_V2" -> objectValue.toPlanReconciled()
                "FEEDBACK_APPLIED_V2" -> objectValue.toFeedbackApplied()
                "FEEDBACK_REVISED_V2" -> objectValue.toFeedbackRevised()
                else -> throw InvalidSyncPayload("Unknown sync payload type")
            }
        } catch (invalid: InvalidSyncPayload) {
            throw invalid
        } catch (invalid: IllegalArgumentException) {
            throw InvalidSyncPayload(invalid.message ?: "Invalid payload fields")
        }
    }

    private fun SyncPayloadV2Model.toJson(): JsonObject = when (this) {
        is PlanLockedV2 -> buildJsonObject {
            put("payloadVersion", payloadVersion)
            put("type", type)
            put("planKey", planKey)
            put("localDate", localDate.toString())
            put("studyMode", studyMode.name)
            put("quota", quota)
            put("planVersion", planVersion)
            put("createdAtUtc", createdAtUtc.toString())
            put("createdByDeviceId", createdByDeviceId)
            put("items", items.toPlanItemsJson())
            put("selectionAlgorithmVersion", selectionAlgorithmVersion)
            put("poolDigest", poolDigest)
        }
        is PlanReconciledV2 -> buildJsonObject {
            put("payloadVersion", payloadVersion)
            put("type", type)
            put("planKey", planKey)
            put("localDate", localDate.toString())
            put("studyMode", studyMode.name)
            put("oldPlanVersion", oldPlanVersion)
            put("newPlanVersion", newPlanVersion)
            put("retainedItemKeys", retainedItemKeys.toStringsJson())
            put("removedItemKeys", removedItemKeys.toStringsJson())
            put("addedItems", addedItems.toPlanItemsJson())
            put("reason", reason)
            quota?.let { put("quota", it) }
        }
        is FeedbackAppliedV2 -> feedbackJson(this)
        is FeedbackRevisedV2 -> feedbackJson(this).toMutableMap().apply {
            this["type"] = JsonPrimitive(type)
            this["replacesEventId"] = JsonPrimitive(replacesEventId)
        }.let(::JsonObject)
    }

    private fun feedbackJson(payload: FeedbackPayloadV2): JsonObject = buildJsonObject {
        put("payloadVersion", payload.payloadVersion)
        put("type", payload.type)
        put("planKey", payload.planKey)
        put("planVersion", payload.planVersion)
        put("itemKey", payload.itemKey)
        put("wordKey", payload.wordKey)
        put("studyMode", payload.studyMode.name)
        put("feedback", payload.feedback.name)
        put("reviewedAtUtc", payload.reviewedAtUtc.toString())
        put("responseTimeMs", payload.responseTimeMs)
        put("shortTermStep", payload.shortTermStep)
        put("baseCardVersion", payload.baseCardVersion)
        payload.previousReviewEventId?.let { put("previousReviewEventId", it) }
        put("fsrsAlgorithmVersion", payload.fsrsAlgorithmVersion)
        put("card", payload.card.toJson())
        put("itemStatus", payload.itemStatus.name)
        put("shortTermEventStatus", payload.shortTermEventStatus.name)
    }

    private fun PlanItemV2.toJsonObject() = buildJsonObject {
        put("itemKey", itemKey)
        put("wordKey", wordKey)
        put("source", source.name)
        put("position", position)
    }

    private fun CardSnapshotV2.toJson() = buildJsonObject {
        put("status", status.name)
        put("difficulty", difficulty)
        put("stability", stability)
        put("retrievability", retrievability)
        dueAtUtc?.let { put("dueAtUtc", it.toString()) }
        put("scheduledDays", scheduledDays)
        put("lapses", lapses)
        put("learningStep", learningStep)
        lastFeedbackEventId?.let { put("lastFeedbackEventId", it) }
    }

    private fun List<PlanItemV2>.toPlanItemsJson() = buildJsonArray { forEach { add(it.toJsonObject()) } }
    private fun List<String>.toStringsJson() = buildJsonArray { forEach { add(JsonPrimitive(it)) } }

    private fun JsonObject.toPlanLocked() = PlanLockedV2(
        planKey = requiredString("planKey"),
        localDate = requiredLocalDate("localDate"),
        studyMode = requiredEnum("studyMode"),
        quota = requiredInt("quota"),
        planVersion = requiredInt("planVersion"),
        createdAtUtc = requiredInstant("createdAtUtc"),
        createdByDeviceId = requiredString("createdByDeviceId"),
        items = requiredArray("items").map { it.jsonObject.toPlanItem() },
        selectionAlgorithmVersion = requiredString("selectionAlgorithmVersion"),
        poolDigest = requiredString("poolDigest"),
    )

    private fun JsonObject.toPlanReconciled() = PlanReconciledV2(
        planKey = requiredString("planKey"),
        localDate = requiredLocalDate("localDate"),
        studyMode = requiredEnum("studyMode"),
        oldPlanVersion = requiredInt("oldPlanVersion"),
        newPlanVersion = requiredInt("newPlanVersion"),
        retainedItemKeys = requiredArray("retainedItemKeys").map { it.jsonPrimitive.content },
        removedItemKeys = requiredArray("removedItemKeys").map { it.jsonPrimitive.content },
        addedItems = requiredArray("addedItems").map { it.jsonObject.toPlanItem() },
        reason = requiredString("reason"),
        quota = optionalInt("quota"),
    )

    private fun JsonObject.toPlanItem() = PlanItemV2(
        itemKey = requiredString("itemKey"),
        wordKey = requiredString("wordKey"),
        source = requiredEnum("source"),
        position = requiredInt("position"),
    )

    private fun JsonObject.toFeedbackApplied() = toFeedbackFields().let { fields ->
        FeedbackAppliedV2(
            planKey = fields.planKey,
            planVersion = fields.planVersion,
            itemKey = fields.itemKey,
            wordKey = fields.wordKey,
            studyMode = fields.studyMode,
            feedback = fields.feedback,
            reviewedAtUtc = fields.reviewedAtUtc,
            responseTimeMs = fields.responseTimeMs,
            shortTermStep = fields.shortTermStep,
            baseCardVersion = fields.baseCardVersion,
            previousReviewEventId = fields.previousReviewEventId,
            fsrsAlgorithmVersion = fields.fsrsAlgorithmVersion,
            card = fields.card,
            itemStatus = fields.itemStatus,
            shortTermEventStatus = fields.shortTermEventStatus,
        )
    }

    private fun JsonObject.toFeedbackRevised() = toFeedbackFields().let { fields ->
        FeedbackRevisedV2(
            replacesEventId = requiredString("replacesEventId"),
            planKey = fields.planKey,
            planVersion = fields.planVersion,
            itemKey = fields.itemKey,
            wordKey = fields.wordKey,
            studyMode = fields.studyMode,
            feedback = fields.feedback,
            reviewedAtUtc = fields.reviewedAtUtc,
            responseTimeMs = fields.responseTimeMs,
            shortTermStep = fields.shortTermStep,
            baseCardVersion = fields.baseCardVersion,
            previousReviewEventId = fields.previousReviewEventId,
            fsrsAlgorithmVersion = fields.fsrsAlgorithmVersion,
            card = fields.card,
            itemStatus = fields.itemStatus,
            shortTermEventStatus = fields.shortTermEventStatus,
        )
    }

    private fun JsonObject.toFeedbackFields() = FeedbackFields(
        planKey = requiredString("planKey"),
        planVersion = requiredInt("planVersion"),
        itemKey = requiredString("itemKey"),
        wordKey = requiredString("wordKey"),
        studyMode = requiredEnum("studyMode"),
        feedback = requiredEnum("feedback"),
        reviewedAtUtc = requiredInstant("reviewedAtUtc"),
        responseTimeMs = requiredLong("responseTimeMs"),
        shortTermStep = requiredInt("shortTermStep"),
        baseCardVersion = requiredInt("baseCardVersion"),
        previousReviewEventId = optionalString("previousReviewEventId"),
        fsrsAlgorithmVersion = requiredString("fsrsAlgorithmVersion"),
        card = requiredObject("card").toCardSnapshot(),
        itemStatus = requiredEnum("itemStatus"),
        shortTermEventStatus = requiredEnum("shortTermEventStatus"),
    )

    private fun JsonObject.toCardSnapshot() = CardSnapshotV2(
        status = requiredEnum("status"),
        difficulty = requiredDouble("difficulty"),
        stability = requiredDouble("stability"),
        retrievability = requiredDouble("retrievability"),
        dueAtUtc = optionalString("dueAtUtc")?.let(Instant::parse),
        scheduledDays = requiredInt("scheduledDays"),
        lapses = requiredInt("lapses"),
        learningStep = requiredInt("learningStep"),
        lastFeedbackEventId = optionalString("lastFeedbackEventId"),
    )

    private data class FeedbackFields(
        val planKey: String,
        val planVersion: Int,
        val itemKey: String,
        val wordKey: String,
        val studyMode: StudyMode,
        val feedback: Feedback,
        val reviewedAtUtc: Instant,
        val responseTimeMs: Long,
        val shortTermStep: Int,
        val baseCardVersion: Int,
        val previousReviewEventId: String?,
        val fsrsAlgorithmVersion: String,
        val card: CardSnapshotV2,
        val itemStatus: DailyItemStatus,
        val shortTermEventStatus: IntradayEventStatus,
    )

    private fun JsonObject.requiredString(key: String): String = required(key).jsonPrimitive.content
    private fun JsonObject.optionalString(key: String): String? = this[key]?.jsonPrimitive?.content
    private fun JsonObject.optionalInt(key: String): Int? = this[key]?.jsonPrimitive?.int
    private fun JsonObject.requiredInt(key: String): Int = required(key).jsonPrimitive.int
    private fun JsonObject.requiredLong(key: String): Long = required(key).jsonPrimitive.long
    private fun JsonObject.requiredDouble(key: String): Double = required(key).jsonPrimitive.double
    private fun JsonObject.requiredInstant(key: String): Instant = Instant.parse(requiredString(key))
    private fun JsonObject.requiredLocalDate(key: String): LocalDate = LocalDate.parse(requiredString(key))
    private fun JsonObject.requiredArray(key: String): JsonArray = required(key).jsonArray
    private fun JsonObject.requiredObject(key: String): JsonObject = required(key).jsonObject
    private fun JsonObject.required(key: String): JsonElement = this[key] ?: throw InvalidSyncPayload("Missing field: $key")

    private inline fun <reified T : Enum<T>> JsonObject.requiredEnum(key: String): T =
        runCatching { enumValueOf<T>(requiredString(key)) }
            .getOrElse { throw InvalidSyncPayload("Invalid ${T::class.simpleName}: $key") }
}
