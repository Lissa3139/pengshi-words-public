package com.pengshi.words.desktop

import java.nio.file.Files
import java.nio.file.Path

internal data class LocalBootstrapFiles(
    val manifestPath: Path,
    val bootstrapPath: Path,
)

internal object DesktopLocalBootstrap {
    fun resolve(repository: Path): LocalBootstrapFiles? {
        val root = repository.toAbsolutePath().normalize()
        val manifest = root.resolve("sync/manifest.enc")
        val bootstrap = root.resolve("sync/state/bootstrap.enc")
        return if (Files.isRegularFile(manifest) && Files.isRegularFile(bootstrap)) {
            LocalBootstrapFiles(manifest, bootstrap)
        } else {
            null
        }
    }
}
