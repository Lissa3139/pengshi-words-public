package com.pengshi.words.desktop

import com.pengshi.words.data.Cet6FrequencyRanks
import com.pengshi.words.desktop.storage.DesktopImportRepository
import com.pengshi.words.desktop.storage.BundledExample
import com.pengshi.words.desktop.storage.DesktopContentRepository
import com.pengshi.words.desktop.storage.DesktopSnapshotGateway
import com.pengshi.words.importer.DelimitedTextParser
import com.pengshi.words.importer.DefaultWordImportParser
import com.pengshi.words.importer.ImportFormat
import com.pengshi.words.importer.ImportResult
import com.pengshi.words.model.DeckSourceType
import java.nio.charset.StandardCharsets
import java.util.Locale

class DesktopSeedLoader(
    private val importRepository: DesktopImportRepository,
    private val snapshotGateway: DesktopSnapshotGateway,
    private val contentRepository: DesktopContentRepository,
    private val resourcePath: String = DEFAULT_CET6_RESOURCE,
    private val onDeckAliases: (Map<Long, Long>) -> Unit = {},
) {
    suspend fun seedIfNeeded(): ImportResult {
        val preview = openSeedResource().use { input ->
            DefaultWordImportParser().parse(input, ImportFormat.CSV)
        }
        require(preview.errors.isEmpty()) {
            "Bundled CET6 seed contains invalid rows: ${preview.errors.joinToString { it.message }}"
        }
        require(preview.rows.all { it.definitionSource?.isNotBlank() == true ||
            com.pengshi.words.model.definitionSourceFromTags(it.tags.orEmpty()) != com.pengshi.words.model.SOURCE_UNVERIFIED
        }) { "内置词库存在未注明释义来源的词条" }
        val rankedPreview = preview.copy(
            rows = preview.rows.map { row ->
                row.copy(frequencyRank = Cet6FrequencyRanks.rank(row.spelling))
            },
        )
        val seedSpellings = rankedPreview.rows.mapTo(linkedSetOf()) {
            it.spelling.trim().lowercase(Locale.ROOT)
        }
        onDeckAliases(importRepository.repairDuplicateCet6Decks(seedSpellings))
        val snapshot = snapshotGateway.snapshot()
        val existingWords = snapshot.words.associateBy { it.normalizedSpelling }
        val wordsById = snapshot.words.associateBy { it.id }
        val cet6Deck = snapshot.decks.firstOrNull {
            it.sourceType == DeckSourceType.BUILTIN &&
                it.sourceFileName == DesktopImportRepository.CET6_DECK_SOURCE_FILE
        }
        val linkedSpellings = cet6Deck?.let { deck ->
            snapshot.deckWords.asSequence()
                .filter { it.deckId == deck.id }
                .mapNotNull { link -> wordsById[link.wordId]?.normalizedSpelling }
                .toSet()
        }.orEmpty()

        val result = if (cet6Deck == null ||
            cet6Deck.name != DesktopImportRepository.CET6_DECK_NAME ||
            cet6Deck.wordCount != seedSpellings.size ||
            !linkedSpellings.containsAll(seedSpellings) ||
            rankedPreview.rows.any { row ->
                val existing = existingWords[row.spelling.trim().lowercase(Locale.ROOT)]
                existing?.frequencyRank == null || (existing.partOfSpeech.isBlank() && !row.partOfSpeech.isNullOrBlank())
            }
        ) {
            importRepository.importBuiltinCet6(rankedPreview)
        } else {
            ImportResult(
                deckId = cet6Deck.id,
                importedRows = 0,
                skippedRows = rankedPreview.rows.size,
                errors = emptyList(),
            )
        }
        if (resourcePath == DEFAULT_CET6_RESOURCE) {
            BUILTIN_PACKS.forEach { seedBuiltinPack(it) }
            contentRepository.reconcileCet6SourceMetadata(rankedPreview)
            contentRepository.mergeBundledExamples(openBundledExamples())
        }
        return result
    }

    suspend fun reconcileBundledSourcesAfterRemoteSnapshot() {
        if (resourcePath != DEFAULT_CET6_RESOURCE) return
        val preview = openSeedResource().use { input ->
            DefaultWordImportParser().parse(input, ImportFormat.CSV)
        }
        val seedSpellings = preview.rows.mapTo(linkedSetOf()) {
            it.spelling.trim().lowercase(Locale.ROOT)
        }
        onDeckAliases(importRepository.repairDuplicateCet6Decks(seedSpellings))
        contentRepository.reconcileCet6SourceMetadata(preview)
        contentRepository.mergeBundledExamples(openBundledExamples())
    }

    private suspend fun seedBuiltinPack(pack: BuiltinPack) {
        val preview = requireNotNull(javaClass.getResourceAsStream(pack.resourcePath)) {
            "Bundled ${pack.packId} seed was not found at ${pack.resourcePath}"
        }.use { input -> DefaultWordImportParser().parse(input, ImportFormat.CSV) }
        require(preview.errors.isEmpty()) {
            "Bundled ${pack.packId} seed contains invalid rows: ${preview.errors.joinToString { it.message }}"
        }
        require(preview.rows.all { it.definitionSource?.isNotBlank() == true ||
            com.pengshi.words.model.definitionSourceFromTags(it.tags.orEmpty()) != com.pengshi.words.model.SOURCE_UNVERIFIED
        }) { "内置词库 ${pack.packId} 存在未注明释义来源的词条" }
        val snapshot = snapshotGateway.snapshot()
        val existingWords = snapshot.words.associateBy { it.normalizedSpelling }
        val wordsById = snapshot.words.associateBy { it.id }
        val deck = snapshot.decks.firstOrNull {
            it.sourceType == DeckSourceType.BUILTIN && it.sourceFileName == pack.sourceFileName
        }
        val seedSpellings = preview.rows.mapTo(linkedSetOf()) { it.spelling.trim().lowercase(Locale.ROOT) }
        val linkedSpellings = deck?.let { current ->
            snapshot.deckWords.asSequence()
                .filter { it.deckId == current.id }
                .mapNotNull { link -> wordsById[link.wordId]?.normalizedSpelling }
                .toSet()
        }.orEmpty()
        val needsImport = deck == null ||
            deck.name != pack.deckName ||
            deck.wordCount != seedSpellings.size ||
            !linkedSpellings.containsAll(seedSpellings) ||
            preview.rows.any { row ->
                val existing = existingWords[row.spelling.trim().lowercase(Locale.ROOT)]
                existing == null || (existing.partOfSpeech.isBlank() && !row.partOfSpeech.isNullOrBlank())
            }
        if (needsImport) {
            importRepository.importBuiltinPack(preview, pack.deckName, pack.sourceFileName)
        }
    }

    private fun openSeedResource() = requireNotNull(javaClass.getResourceAsStream(resourcePath)) {
        "Bundled CET6 seed was not found at $resourcePath"
    }

    private fun openBundledExamples(): List<BundledExample> =
        readBundledExamples(DEFAULT_CET6_EXAMPLES_RESOURCE, "source:tatoeba") +
            readBundledExamples(GENERATED_EXAMPLES_RESOURCE, "source:ai-generated")

    private fun readBundledExamples(resourcePath: String, defaultSourceTag: String): List<BundledExample> {
        val text = requireNotNull(javaClass.getResourceAsStream(resourcePath)) {
            "Bundled examples were not found at $resourcePath"
        }.use { it.readBytes().toString(StandardCharsets.UTF_8).removePrefix("\uFEFF") }
        val records = DelimitedTextParser.parseRecords(text, ',')
        if (records.isEmpty()) return emptyList()
        val headers = records.first().mapIndexed { index, header -> header.trim().lowercase(Locale.ROOT) to index }.toMap()
        val spellingIndex = headers["spelling"] ?: return emptyList()
        val englishIndex = headers["example_en"] ?: return emptyList()
        val chineseIndex = headers["example_cn"]
        val sourceTagIndex = headers["source_tag"]
        return records.drop(1).mapIndexedNotNull { index, cells ->
            val spelling = cells.getOrNull(spellingIndex).orEmpty().trim()
            val sentenceEn = cells.getOrNull(englishIndex).orEmpty().trim()
            if (spelling.isBlank() || sentenceEn.isBlank()) return@mapIndexedNotNull null
            val sourceSentenceId = headers["source_sentence_id"]?.let { cells.getOrNull(it)?.trim()?.ifBlank { null } }
            val sourceTag = sourceTagIndex?.let { cells.getOrNull(it)?.trim()?.ifBlank { null } }
                ?: if (sourceSentenceId != null) defaultSourceTag else null
            require(sourceTag != null) { "例句包 $resourcePath 第 ${index + 2} 行缺少来源" }
            BundledExample(
                spelling = spelling,
                sentenceEn = sentenceEn,
                sentenceCn = chineseIndex?.let { cells.getOrNull(it)?.trim()?.ifBlank { null } },
                sourceTag = sourceTag,
                sourceSentenceId = sourceSentenceId,
                license = headers["license"]?.let { cells.getOrNull(it)?.trim()?.ifBlank { null } },
            )
        }
    }

    private companion object {
        const val DEFAULT_CET6_RESOURCE = "/wordpacks/cet6/words.csv"
        const val DEFAULT_CET6_EXAMPLES_RESOURCE = "/wordpacks/cet6/examples.csv"
        const val GENERATED_EXAMPLES_RESOURCE = "/wordpacks/generated-examples/examples.csv"

        val BUILTIN_PACKS = listOf(
            BuiltinPack("cet4-all", "四级全部词汇", "cet4-all.csv", "/wordpacks/cet4-all/words.csv"),
            BuiltinPack("cet4-core", "四级核心词汇", "cet4-core.csv", "/wordpacks/cet4-core/words.csv"),
            BuiltinPack("cet6-all", "六级全部词汇", "cet6-all.csv", "/wordpacks/cet6-all/words.csv"),
            BuiltinPack("kaoyan-shared", "考研英语(2024大纲)", "kaoyan-shared-2024.csv", "/wordpacks/kaoyan-shared/words.csv"),
        )
    }
}

private data class BuiltinPack(
    val packId: String,
    val deckName: String,
    val sourceFileName: String,
    val resourcePath: String,
)
