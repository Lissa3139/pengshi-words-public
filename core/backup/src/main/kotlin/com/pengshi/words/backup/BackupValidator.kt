package com.pengshi.words.backup

class DefaultBackupValidator : BackupValidator {
    override fun validate(preview: BackupPreview): List<BackupError> {
        val errors = mutableListOf<BackupError>()
        if (preview.schemaVersion !in 1..CURRENT_BACKUP_SCHEMA) errors += BackupError("schemaVersion", "Unsupported backup schema")
        if (preview.appVersion.isBlank()) errors += BackupError("appVersion", "App version is required")
        if (preview.checksum != BackupCodec.checksum(preview.snapshot, preview.schemaVersion)) errors += BackupError("checksum", "Backup payload checksum does not match")
        uniqueIds(preview.snapshot.words.map { it.id }, "words", errors)
        uniqueIds(preview.snapshot.cardStates.map { it.id }, "cardStates", errors)
        uniqueIds(preview.snapshot.reviewLogs.map { it.id }, "reviewLogs", errors)
        if (preview.snapshot.cardStates.any { it.wordId <= 0 }) errors += BackupError("cardStates.wordId", "Word id must be positive")
        return errors
    }

    private fun uniqueIds(ids: List<Long>, field: String, errors: MutableList<BackupError>) {
        if (ids.size != ids.toSet().size) errors += BackupError(field, "Ids must be unique")
    }
}
