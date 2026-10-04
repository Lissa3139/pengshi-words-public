package com.pengshi.words.backup

import java.io.InputStream
import java.nio.charset.StandardCharsets

class JsonBackupReader : BackupReader {
    override suspend fun read(source: InputStream): BackupPreview {
        val envelope = BackupCodec.decodeJson(source.readBytes().toString(StandardCharsets.UTF_8))
        return BackupPreview(
            schemaVersion = envelope.schemaVersion,
            cardStates = envelope.snapshot.cardStates,
            reviewLogs = envelope.snapshot.reviewLogs,
            checksum = envelope.checksum,
            snapshot = envelope.snapshot,
            createdAt = envelope.createdAt,
            appVersion = envelope.appVersion,
            statsStartDate = envelope.statsStartDate,
            settings = envelope.settings,
        )
    }
}
