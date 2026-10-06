package com.pengshi.words.desktop.sync

import com.pengshi.words.backup.BackupSettingsSnapshot
import com.pengshi.words.model.DailyQuota
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDate
import java.util.Properties

/** Non-secret learning preferences that are part of the personal database checkpoint. */
class DesktopPersonalSettingsStore(private val path: Path) {
    fun load(): BackupSettingsSnapshot {
        val properties = Properties().also { values ->
            if (Files.isRegularFile(path)) Files.newInputStream(path).use(values::load)
        }
        fun ids(key: String): Set<Long> = properties.getProperty(key, "")
            .split(',')
            .mapNotNull(String::toLongOrNull)
            .toSet()
        return BackupSettingsSnapshot(
            dailyQuota = properties.getProperty(DAILY_QUOTA, DailyQuota.DEFAULT.toString())
                .toIntOrNull()?.let(DailyQuota::normalize) ?: DailyQuota.DEFAULT,
            defaultMode = properties.getProperty(DEFAULT_MODE, "EN_TO_CN"),
            autoPlayWord = properties.getProperty(AUTO_PLAY_WORD, "true").toBooleanStrictOrNull() ?: true,
            autoPlaySentence = properties.getProperty(AUTO_PLAY_SENTENCE, "true").toBooleanStrictOrNull() ?: true,
            speechRate = properties.getProperty(SPEECH_RATE, "1.0").toFloatOrNull() ?: 1.0f,
            selectedVoiceKey = properties.getProperty(VOICE_KEY)?.takeIf(String::isNotBlank),
            includedDeckIds = ids(INCLUDED_DECK_IDS),
            excludedWordIds = ids(EXCLUDED_WORD_IDS),
            favoriteWordIds = ids(FAVORITE_WORD_IDS),
            statsAnchorDate = properties.getProperty(STATS_ANCHOR_DATE)
                ?.takeIf(String::isNotBlank)
                ?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            statsPeriod = properties.getProperty(STATS_PERIOD)?.takeIf(String::isNotBlank),
            statsTab = properties.getProperty(STATS_TAB)?.takeIf(String::isNotBlank),
        )
    }

    fun save(settings: BackupSettingsSnapshot) {
        val properties = Properties().apply {
            setProperty(DAILY_QUOTA, DailyQuota.normalize(settings.dailyQuota).toString())
            setProperty(DEFAULT_MODE, settings.defaultMode)
            setProperty(AUTO_PLAY_WORD, settings.autoPlayWord.toString())
            setProperty(AUTO_PLAY_SENTENCE, settings.autoPlaySentence.toString())
            setProperty(SPEECH_RATE, settings.speechRate.toString())
            setProperty(VOICE_KEY, settings.selectedVoiceKey.orEmpty())
            setProperty(INCLUDED_DECK_IDS, settings.includedDeckIds.sorted().joinToString(","))
            setProperty(EXCLUDED_WORD_IDS, settings.excludedWordIds.sorted().joinToString(","))
            setProperty(FAVORITE_WORD_IDS, settings.favoriteWordIds.sorted().joinToString(","))
            setProperty(STATS_ANCHOR_DATE, settings.statsAnchorDate?.toString().orEmpty())
            setProperty(STATS_PERIOD, settings.statsPeriod.orEmpty())
            setProperty(STATS_TAB, settings.statsTab.orEmpty())
        }
        Files.createDirectories(path.toAbsolutePath().parent)
        Files.newOutputStream(
            path,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        ).use { properties.store(it, "PengshiWords synchronized personal preferences") }
    }

    fun replaceDeckIds(aliases: Map<Long, Long>): BackupSettingsSnapshot {
        if (aliases.isEmpty()) return load()
        val current = load()
        val remapped = current.copy(
            includedDeckIds = current.includedDeckIds.mapTo(linkedSetOf()) { aliases[it] ?: it },
        )
        if (remapped != current) save(remapped)
        return remapped
    }

    private companion object {
        const val DAILY_QUOTA = "dailyQuota"
        const val DEFAULT_MODE = "defaultMode"
        const val AUTO_PLAY_WORD = "autoPlayWord"
        const val AUTO_PLAY_SENTENCE = "autoPlaySentence"
        const val SPEECH_RATE = "speechRate"
        const val VOICE_KEY = "selectedVoiceKey"
        const val INCLUDED_DECK_IDS = "includedDeckIds"
        const val EXCLUDED_WORD_IDS = "excludedWordIds"
        const val FAVORITE_WORD_IDS = "favoriteWordIds"
        const val STATS_ANCHOR_DATE = "statsAnchorDate"
        const val STATS_PERIOD = "statsPeriod"
        const val STATS_TAB = "statsTab"
    }
}
