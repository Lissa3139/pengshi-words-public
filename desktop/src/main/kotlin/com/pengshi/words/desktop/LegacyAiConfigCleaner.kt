package com.pengshi.words.desktop

import java.nio.file.Files
import java.nio.file.Path

object LegacyAiConfigCleaner {
    fun remove(dataDirectory: Path, databaseFileName: String) {
        val directory = dataDirectory.toAbsolutePath().normalize()
        val candidate = directory.resolve("$databaseFileName.llm.properties").normalize()
        require(candidate.parent == directory) { "Invalid legacy AI configuration path" }
        Files.deleteIfExists(candidate)
    }
}
