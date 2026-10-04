package com.pengshi.words.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Resolves the user's checked-out repository. The configured folder itself is
 * the data directory; nested-clone discovery remains available for migration
 * and compatibility with older layouts.
 */
internal object DesktopRepositoryLocation {
    fun resolve(container: Path): Path? {
        val root = container.toAbsolutePath().normalize()
        if (!Files.isDirectory(root)) return null
        if (Files.isDirectory(root.resolve(".git"))) return root
        Files.list(root).use { children ->
            return children
                .filter { Files.isDirectory(it) && Files.isDirectory(it.resolve(".git")) }
                .sorted()
                .findFirst()
                .orElse(null)
                ?.toAbsolutePath()
                ?.normalize()
        }
    }

    fun migrateLegacyFiles(legacyDirectory: Path, repositoryDirectory: Path, databaseFileName: String) {
        Files.createDirectories(repositoryDirectory)
        val names = listOf(
            databaseFileName,
            "$databaseFileName-wal",
            "$databaseFileName-shm",
            "$databaseFileName.favorites",
            "$databaseFileName.mode",
            "$databaseFileName.speech.properties",
            "$databaseFileName.sync.properties",
            "$databaseFileName.device-id",
        )
        names.forEach { name ->
            val source = legacyDirectory.resolve(name)
            val target = repositoryDirectory.resolve(name)
            if (Files.exists(source) && !Files.exists(target)) {
                Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES)
            }
        }
    }
}
