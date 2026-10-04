package com.pengshi.words.backup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Keeps a small rolling backup inside app-private storage without exposing credentials. */
class AutomaticBackupStore(
    private val appFilesDirectory: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val directory: File get() = File(appFilesDirectory, DIRECTORY_NAME)
    private val latestFile: File get() = File(directory, LATEST_NAME)

    suspend fun write(writer: suspend (OutputStream) -> Unit) = withContext(Dispatchers.IO) {
        require(directory.mkdirs() || directory.isDirectory) { "Unable to create automatic backup directory" }
        val temporary = File(directory, "$LATEST_NAME.${now()}.tmp")
        try {
            temporary.outputStream().use { output -> writer(output) }
            if (latestFile.isFile) {
                move(latestFile, File(directory, "backup-${now()}.json"))
            }
            move(temporary, latestFile)
            historyBackups().drop(MAX_HISTORY).forEach(File::delete)
        } finally {
            temporary.delete()
        }
    }

    fun latestBackup(): File? = latestFile.takeIf(File::isFile)

    fun historyBackups(): List<File> = directory.listFiles { file ->
        file.isFile && file.name.startsWith("backup-") && file.name.endsWith(".json")
    }?.sortedByDescending(File::lastModified).orEmpty()

    private fun move(source: File, target: File) {
        target.parentFile?.mkdirs()
        runCatching {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.getOrElse {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        const val DIRECTORY_NAME = "automatic-backups"
        const val LATEST_NAME = "latest.json"
        const val MAX_HISTORY = 3
    }
}
