package com.pengshi.words.backup

import com.pengshi.words.model.StudyDataSnapshotGateway
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

class JsonBackupWriter(
    private val gateway: StudyDataSnapshotGateway,
    private val appVersion: String = "1.0",
    private val clock: Clock = Clock.systemUTC(),
    private val statsStartDateProvider: () -> LocalDate? = { null },
    private val settingsProvider: () -> BackupSettingsSnapshot? = { null },
) : BackupWriter {
    override suspend fun write(destination: OutputStream): BackupSummary {
        val snapshot = gateway.snapshot()
        val envelope = BackupEnvelope(
            CURRENT_BACKUP_SCHEMA,
            Instant.now(clock),
            appVersion,
            BackupCodec.checksum(snapshot),
            snapshot,
            statsStartDateProvider(),
            settingsProvider(),
        )
        destination.write(BackupCodec.encodeJson(envelope).toByteArray(StandardCharsets.UTF_8))
        return BackupSummary(snapshot.words.size, snapshot.cardStates.size, snapshot.reviewLogs.size, envelope.checksum)
    }
}
