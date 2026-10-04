package com.pengshi.words.desktop.sync

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties

/** Stable, machine-local preferences that must survive switching the study database folder. */
class DesktopAppSettingsStore(private val path: Path) {
    fun dataDirectory(): Path? = read().getProperty(DATA_DIRECTORY_KEY)
        ?.takeIf(String::isNotBlank)
        ?.let { value -> runCatching { Path.of(value).toAbsolutePath().normalize() }.getOrNull() }

    fun saveDataDirectory(directory: Path) {
        val normalized = directory.toAbsolutePath().normalize()
        val properties = read()
        properties.setProperty(DATA_DIRECTORY_KEY, normalized.toString())
        write(properties)
    }

    fun clearDataDirectory() {
        val properties = read()
        properties.remove(DATA_DIRECTORY_KEY)
        write(properties)
    }

    fun speechModelDirectory(): Path? = read().getProperty(SPEECH_MODEL_DIRECTORY_KEY)
        ?.takeIf(String::isNotBlank)
        ?.let { value -> runCatching { Path.of(value).toAbsolutePath().normalize() }.getOrNull() }

    fun saveSpeechModelDirectory(directory: Path) {
        val properties = read()
        properties.setProperty(SPEECH_MODEL_DIRECTORY_KEY, directory.toAbsolutePath().normalize().toString())
        write(properties)
    }

    private fun read(): Properties = Properties().also { properties ->
        if (Files.isRegularFile(path)) {
            Files.newInputStream(path).use(properties::load)
        }
    }

    private fun write(properties: Properties) {
        val target = path.toAbsolutePath().normalize()
        val parent = requireNotNull(target.parent) { "Application settings path has no parent directory" }
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, "desktop-settings-", ".tmp")
        try {
            Files.newOutputStream(temporary).use { properties.store(it, "PengshiWords desktop preferences") }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private companion object {
        const val DATA_DIRECTORY_KEY = "data.directory"
        const val SPEECH_MODEL_DIRECTORY_KEY = "speech.model.directory"
    }
}
