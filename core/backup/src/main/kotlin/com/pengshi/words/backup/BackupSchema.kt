package com.pengshi.words.backup

import com.pengshi.words.model.CardState
import com.pengshi.words.model.ReviewLog
import com.pengshi.words.model.StudyDataSnapshot
import java.time.Instant
import java.time.LocalDate

const val CURRENT_BACKUP_SCHEMA = 4

data class BackupSummary(
    val wordCount: Int,
    val cardStateCount: Int,
    val reviewLogCount: Int,
    val checksum: String,
)

data class BackupPreview(
    val schemaVersion: Int,
    val cardStates: List<CardState>,
    val reviewLogs: List<ReviewLog>,
    val checksum: String,
    val snapshot: StudyDataSnapshot,
    val createdAt: Instant,
    val appVersion: String,
    /** The user's first statistics date; null keeps compatibility with old backups. */
    val statsStartDate: LocalDate? = null,
    /** Non-secret application preferences; null keeps compatibility with old backups. */
    val settings: BackupSettingsSnapshot? = null,
)

data class BackupSettingsSnapshot(
    val dailyQuota: Int = 30,
    val defaultMode: String = "EN_TO_CN",
    val autoPlayWord: Boolean = true,
    val autoPlaySentence: Boolean = true,
    val speechRate: Float = 1.0f,
    val selectedVoiceKey: String? = null,
    val includedDeckIds: Set<Long> = emptySet(),
    val deckWeights: Map<Long, Int> = emptyMap(),
    val excludedWordIds: Set<Long> = emptySet(),
    val favoriteWordIds: Set<Long> = emptySet(),
    val statsAnchorDate: LocalDate? = null,
    val statsPeriod: String? = null,
    val statsTab: String? = null,
)

data class BackupError(val field: String, val message: String)

interface BackupWriter {
    suspend fun write(destination: java.io.OutputStream): BackupSummary
}

interface BackupReader {
    suspend fun read(source: java.io.InputStream): BackupPreview
}

interface BackupValidator {
    fun validate(preview: BackupPreview): List<BackupError>
}
