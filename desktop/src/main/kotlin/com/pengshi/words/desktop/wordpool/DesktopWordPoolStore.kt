package com.pengshi.words.desktop.wordpool

import com.pengshi.words.model.WordPoolSelection
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.util.Properties

class DesktopWordPoolStore(private val path: Path) {
    fun load(): WordPoolSelection {
        if (!Files.isRegularFile(path)) return WordPoolSelection()
        return runCatching {
            val properties = Properties()
            Files.newInputStream(path).use(properties::load)
            WordPoolSelection(
                includedDeckIds = parseIds(properties.getProperty(INCLUDED_DECKS)),
                excludedWordIds = parseIds(properties.getProperty(EXCLUDED_WORDS)),
                deckWeights = parseWeights(properties.getProperty(DECK_WEIGHTS)),
            )
        }.getOrDefault(WordPoolSelection())
    }

    fun save(selection: WordPoolSelection) {
        Files.createDirectories(path.toAbsolutePath().parent)
        val properties = Properties()
        properties.setProperty(INCLUDED_DECKS, selection.includedDeckIds.sorted().joinToString(","))
        properties.setProperty(EXCLUDED_WORDS, selection.excludedWordIds.sorted().joinToString(","))
        properties.setProperty(DECK_WEIGHTS, selection.deckWeights.entries.sortedBy { it.key }.joinToString(",") { "${it.key}:${it.value}" })
        val tempPath = path.resolveSibling("${path.fileName}.tmp-${System.nanoTime()}")
        Files.newOutputStream(
            tempPath,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
        ).use { output -> properties.store(output, "彭式背单词自动选词仪表盘") }
        try {
            Files.move(tempPath, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tempPath, path, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun replaceDeckIds(aliases: Map<Long, Long>) {
        if (aliases.isEmpty()) return
        val current = load()
        val remapped = current.includedDeckIds.mapTo(linkedSetOf()) { aliases[it] ?: it }
        val remappedWeights = current.deckWeights.entries
            .groupBy { aliases[it.key] ?: it.key }
            .mapValues { (_, entries) -> entries.sumOf { it.value }.coerceAtMost(100) }
        save(current.copy(includedDeckIds = remapped, deckWeights = remappedWeights))
    }

    private fun parseIds(value: String?): Set<Long> = value.orEmpty()
        .split(',')
        .mapNotNull { it.trim().toLongOrNull() }
        .toSet()

    private fun parseWeights(value: String?): Map<Long, Int> = value.orEmpty()
        .split(',')
        .mapNotNull { pair ->
            val parts = pair.trim().split(':', limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val deckId = parts[0].toLongOrNull() ?: return@mapNotNull null
            val weight = parts[1].toIntOrNull() ?: return@mapNotNull null
            deckId to weight
        }
        .toMap()

    private companion object {
        const val INCLUDED_DECKS = "includedDeckIds"
        const val EXCLUDED_WORDS = "excludedWordIds"
        const val DECK_WEIGHTS = "deckWeights"
    }
}
