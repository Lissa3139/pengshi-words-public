package com.pengshi.words

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.room.Room
import androidx.room.withTransaction
import com.pengshi.words.database.PengshiDatabase
import com.pengshi.words.database.ExampleSentenceEntity
import com.pengshi.words.database.RoomImportRepository
import com.pengshi.words.database.RoomExamplePackImporter
import com.pengshi.words.database.RoomPersonalWordRepository
import com.pengshi.words.database.RoomUserDeckRepository
import com.pengshi.words.database.RoomManualExampleRepository
import com.pengshi.words.database.RoomStudyDataSnapshotGateway
import com.pengshi.words.database.RoomStudySessionRepository
import com.pengshi.words.database.RoomDailyPlanRepository
import com.pengshi.words.database.CardStateEntity
import com.pengshi.words.database.StatsReviewLogRow
import com.pengshi.words.database.WordEntity
import com.pengshi.words.database.SyncRoomStore
import com.pengshi.words.backup.DefaultBackupValidator
import com.pengshi.words.backup.BackupSettingsSnapshot
import com.pengshi.words.backup.JsonBackupReader
import com.pengshi.words.backup.JsonBackupWriter
import com.pengshi.words.backup.AutomaticBackupStore
import com.pengshi.words.domain.AddNewWordsUseCase
import com.pengshi.words.domain.AdjustDailyQuotaUseCase
import com.pengshi.words.domain.AppendExtraWordsUseCase
import com.pengshi.words.domain.AddWordToTodayUseCase
import com.pengshi.words.domain.ResumeDailyStudyUseCase
import com.pengshi.words.domain.ReconcileDailyPlanUseCase
import com.pengshi.words.domain.StartDailyStudyUseCase
import com.pengshi.words.domain.SubmitFeedbackUseCase
import com.pengshi.words.domain.StudySessionState
import com.pengshi.words.data.Cet6FrequencyRanks
import com.pengshi.words.data.TatoebaExampleService
import com.pengshi.words.feature.home.HomeStudyPhase
import com.pengshi.words.feature.home.HomeUiState
import com.pengshi.words.feature.stats.StatsCalculator
import com.pengshi.words.feature.stats.StatsUiState
import com.pengshi.words.feature.stats.StatsPeriod
import com.pengshi.words.feature.stats.StatsTab
import com.pengshi.words.feature.stats.StatsPoint
import com.pengshi.words.feature.stats.LearningBar
import com.pengshi.words.feature.stats.RetentionSeries
import com.pengshi.words.feature.settings.SettingsAction
import com.pengshi.words.feature.settings.SettingsUiState
import com.pengshi.words.feature.settings.SettingsViewModel
import com.pengshi.words.feature.decks.DeckSummary
import com.pengshi.words.feature.decks.WordListItemUi
import com.pengshi.words.feature.decks.SelectionDashboardUiState
import com.pengshi.words.feature.decks.buildWordListItems
import com.pengshi.words.stats.StatsReviewLog
import com.pengshi.words.stats.StatsStudyData
import com.pengshi.words.feature.study.StudyAction
import com.pengshi.words.feature.study.StudyScreenState
import com.pengshi.words.feature.study.toScreenState
import com.pengshi.words.importer.DefaultWordImportParser
import com.pengshi.words.model.ImportFormat
import com.pengshi.words.model.DailyItemStatus
import com.pengshi.words.model.DailyPlan
import com.pengshi.words.model.DailyQuota
import com.pengshi.words.model.DailyPlanEntry
import com.pengshi.words.model.DailyPlanItem
import com.pengshi.words.model.CardStatus
import com.pengshi.words.model.CardState
import com.pengshi.words.model.IntradayReviewEvent
import com.pengshi.words.model.IntradayEventStatus
import com.pengshi.words.model.DailyPlanStatus
import com.pengshi.words.model.Feedback
import com.pengshi.words.model.FeedbackUndoToken
import com.pengshi.words.model.PlanSource
import com.pengshi.words.model.isNewWord
import com.pengshi.words.model.StudyMode
import com.pengshi.words.model.PersonalWordInput
import com.pengshi.words.model.BatchImportSources
import com.pengshi.words.importer.withBatchSources
import com.pengshi.words.model.UserDeckInput
import com.pengshi.words.model.DeckSourceType
import com.pengshi.words.model.WordPoolSelection
import com.pengshi.words.model.ManualWordCatalog
import com.pengshi.words.model.manualWordCatalog
import com.pengshi.words.model.AutomaticWordPool
import com.pengshi.words.model.resolve
import com.pengshi.words.model.StudyDataSnapshot
import com.pengshi.words.model.StartupWordFieldData
import com.pengshi.words.model.normalizeDictionaryText
import com.pengshi.words.navigation.AppNavHost
import com.pengshi.words.startup.AppStartupCoordinator
import com.pengshi.words.startup.AppStartupState
import com.pengshi.words.startup.InitialAppData
import com.pengshi.words.scheduler.DefaultStudyScheduler
import com.pengshi.words.speech.AndroidSpeechEngine
import com.pengshi.words.speech.SpeechSettings
import com.pengshi.words.speech.SpeechTextPolicy
import com.pengshi.words.speech.SpeechVoiceOption
import com.pengshi.words.sync.AndroidTokenStore
import com.pengshi.words.sync.GitHubSyncSettings
import com.pengshi.words.sync.PersonalSnapshotSync
import com.pengshi.words.sync.SnapshotSummary
import com.pengshi.words.sync.CheckpointDecision
import com.pengshi.words.sync.CheckpointMetadata
import com.pengshi.words.sync.SyncCursor
import com.pengshi.words.sync.cursorKey
import com.pengshi.words.sync.SyncCoordinator
import com.pengshi.words.sync.SyncEventKind
import com.pengshi.words.sync.SyncEventRecord
import com.pengshi.words.sync.SyncRemoteEventApplier
import com.pengshi.words.sync.SyncPayloadV2
import com.pengshi.words.sync.FeedbackPayloadV2
import com.pengshi.words.sync.PlanLockedV2
import com.pengshi.words.sync.PlanReconciledV2
import com.pengshi.words.sync.PlanLockCompatibility
import com.pengshi.words.sync.SyncKeyFactory
import com.pengshi.words.sync.SyncResult
import com.pengshi.words.sync.SyncFeedbackState
import com.pengshi.words.sync.SyncCheckpointAction
import com.pengshi.words.sync.SyncStatus
import com.pengshi.words.sync.github.GitHubApi
import com.pengshi.words.sync.github.GitHubRepositoryConfig
import com.pengshi.words.sync.github.GitHubRemoteStore
import com.pengshi.words.sync.appliedEventDigest
import com.pengshi.words.sync.userMessage
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import java.time.Instant
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import org.json.JSONArray
import org.json.JSONObject

private data class StudyHistoryEntry(
    val screen: StudyScreenState,
    val undoToken: FeedbackUndoToken?,
)

private fun StatsUiState.toCacheJson(): String = JSONObject()
    .put("version", 1)
    .put("totalWords", totalWords)
    .put("newWords", newWords)
    .put("reviewCount", reviewCount)
    .put("rememberedCount", rememberedCount)
    .put("completedDays", completedDays)
    .put("estimatedRecallPercent", estimatedRecallPercent)
    .put("chartLabels", JSONArray(chartLabels))
    .put("chartDates", JSONArray(chartDates.map(LocalDate::toString)))
    .put("forgettingCurve", statsPointsToJson(forgettingCurve))
    .put("referenceForgettingCurve", statsPointsToJson(referenceForgettingCurve))
    .put("personalForgettingCurve", statsPointsToJson(personalForgettingCurve))
    .put("referencePersonalForgettingCurve", statsPointsToJson(referencePersonalForgettingCurve))
    .put("forgettingSampleCount", forgettingSampleCount)
    .put("forgettingPreliminary", forgettingPreliminary)
    .put("learningBars", JSONArray().apply {
        learningBars.forEach { bar ->
            put(JSONObject()
                .put("label", bar.label)
                .put("familiar", bar.familiar)
                .put("remembered", bar.remembered)
                .put("forgotten", bar.forgotten)
                .put("learning", bar.learning)
                .put("planned", bar.planned)
                .put("isToday", bar.isToday)
                .put("studyTimeMs", bar.studyTimeMs))
        }
    })
    .put("retentionSeries", JSONArray().apply {
        retentionSeries.forEach { series ->
            put(JSONObject()
                .put("thresholdDays", series.thresholdDays ?: JSONObject.NULL)
                .put("label", series.label)
                .put("values", JSONArray().apply {
                    series.values.forEach { put(it ?: JSONObject.NULL) }
                })
                .put("total", series.total)
                .put("percent", series.percent))
        }
    })
    .put("anchorDate", anchorDate.toString())
    .put("statsStartDate", statsStartDate.toString())
    .put("period", period.name)
    .toString()

private fun statsPointsToJson(points: List<StatsPoint>) = JSONArray().apply {
    points.forEach { point ->
        put(JSONObject()
            .put("label", point.label)
            .put("value", point.value)
            .put("xDay", point.xDay)
            .put("elapsedMinutes", point.elapsedMinutes))
    }
}

private fun statsStateFromCache(raw: String?): StatsUiState? = runCatching {
    val json = JSONObject(requireNotNull(raw))
    require(json.optInt("version") == 1)
    fun stringList(key: String): List<String> = json.getJSONArray(key).let { values ->
        List(values.length()) { values.getString(it) }
    }
    fun pointList(key: String): List<StatsPoint> = json.getJSONArray(key).let { values ->
        List(values.length()) { index ->
            val point = values.getJSONObject(index)
            StatsPoint(point.getString("label"), point.getDouble("value").toFloat(), point.getInt("xDay"), point.getDouble("elapsedMinutes"))
        }
    }
    val learningBars = json.getJSONArray("learningBars").let { values ->
        List(values.length()) { index ->
            val bar = values.getJSONObject(index)
            LearningBar(
                label = bar.getString("label"),
                familiar = bar.getInt("familiar"),
                remembered = bar.getInt("remembered"),
                forgotten = bar.getInt("forgotten"),
                learning = bar.getInt("learning"),
                planned = bar.getInt("planned"),
                isToday = bar.getBoolean("isToday"),
                studyTimeMs = bar.getLong("studyTimeMs"),
            )
        }
    }
    val retentionSeries = json.getJSONArray("retentionSeries").let { values ->
        List(values.length()) { index ->
            val series = values.getJSONObject(index)
            val encodedValues = series.getJSONArray("values")
            RetentionSeries(
                thresholdDays = if (series.isNull("thresholdDays")) null else series.getInt("thresholdDays"),
                label = series.getString("label"),
                values = List(encodedValues.length()) { valueIndex ->
                    if (encodedValues.isNull(valueIndex)) null else encodedValues.getDouble(valueIndex).toFloat()
                },
                total = series.getInt("total"),
                percent = series.getDouble("percent").toFloat(),
            )
        }
    }
    StatsUiState(
        totalWords = json.getInt("totalWords"),
        newWords = json.getInt("newWords"),
        reviewCount = json.getInt("reviewCount"),
        rememberedCount = json.getInt("rememberedCount"),
        completedDays = json.getInt("completedDays"),
        estimatedRecallPercent = json.getInt("estimatedRecallPercent"),
        chartLabels = stringList("chartLabels"),
        chartDates = stringList("chartDates").map(LocalDate::parse),
        forgettingCurve = pointList("forgettingCurve"),
        referenceForgettingCurve = pointList("referenceForgettingCurve"),
        personalForgettingCurve = pointList("personalForgettingCurve"),
        referencePersonalForgettingCurve = pointList("referencePersonalForgettingCurve"),
        forgettingSampleCount = json.getInt("forgettingSampleCount"),
        forgettingPreliminary = json.getBoolean("forgettingPreliminary"),
        learningBars = learningBars,
        retentionSeries = retentionSeries,
        anchorDate = LocalDate.parse(json.getString("anchorDate")),
        statsStartDate = LocalDate.parse(json.getString("statsStartDate")),
        period = StatsPeriod.valueOf(json.getString("period")),
    )
}.getOrNull()

private fun deckSummariesFromCache(raw: String?): List<DeckSummary> = runCatching {
    val rows = JSONArray(requireNotNull(raw))
    List(rows.length()) { index ->
        val row = rows.getJSONObject(index)
        val sourceType = DeckSourceType.valueOf(row.getString("sourceType"))
        DeckSummary(
            id = row.getLong("id"),
            name = row.getString("name"),
            wordCount = row.getInt("wordCount"),
            dueCount = row.getInt("dueCount"),
            newCount = row.getInt("newCount"),
            totalWordCount = row.optInt("totalWordCount"),
            sourceType = sourceType,
            isEditable = sourceType != DeckSourceType.BUILTIN,
        )
    }
}.getOrDefault(emptyList())

private fun deckSummariesToCache(decks: List<DeckSummary>): String = JSONArray().apply {
    decks.forEach { deck ->
        put(JSONObject()
            .put("id", deck.id)
            .put("name", deck.name)
            .put("wordCount", deck.wordCount)
            .put("dueCount", deck.dueCount)
            .put("newCount", deck.newCount)
            .put("totalWordCount", deck.totalWordCount)
            .put("sourceType", deck.sourceType.name))
    }
}.toString()

private tailrec fun Context.findLifecycleOwner(): LifecycleOwner? = when (this) {
    is LifecycleOwner -> this
    is ContextWrapper -> baseContext.findLifecycleOwner()
    else -> null
}

@Composable
fun App() {
    val context = LocalContext.current
    val container = remember(context) { AppContainer(context) }
    val scope = rememberCoroutineScope()
    val lifecycleOwner = remember(context) { context.findLifecycleOwner() }
    var lastHomeRefreshDate by remember(container) { mutableStateOf(LocalDate.now()) }

    var homeState by remember(container) { mutableStateOf(container.cachedHomeState() ?: HomeUiState()) }
    var studyState by remember { mutableStateOf<StudyScreenState?>(null) }
    var studyTransitionMessage by remember { mutableStateOf<String?>(null) }
    var newWordChoiceRequest by remember { mutableStateOf(0) }
    var studyCompletionRequest by remember { mutableStateOf(0) }
    var previousStudyStates by remember { mutableStateOf<List<StudyHistoryEntry>>(emptyList()) }
    var browsingStudyEntry by remember { mutableStateOf<StudyHistoryEntry?>(null) }
    var forwardStudyState by remember { mutableStateOf<StudyScreenState?>(null) }
    var statsState by remember { mutableStateOf(StatsUiState()) }
    var statsTab by remember { mutableStateOf(container.savedStatsTab) }
    var wordList by remember { mutableStateOf<List<WordListItemUi>>(emptyList()) }
    var hasOpenedStudySearch by remember { mutableStateOf(false) }
    var studyWordListRequestId by remember { mutableStateOf(0L) }
    var studyWordListLoadedVersion by remember { mutableStateOf(-1L) }
    var deckWords by remember { mutableStateOf<List<WordListItemUi>>(emptyList()) }
    var deckWordsDeckId by remember { mutableStateOf<Long?>(null) }
    var selectedDeckId by remember { mutableStateOf<Long?>(null) }
    var loadingDeckWordsId by remember { mutableStateOf<Long?>(null) }
    var deckWordsRequestId by remember { mutableStateOf(0L) }
    var deckWordsLoadedVersion by remember { mutableStateOf(-1L) }
    var libraryContentVersion by remember { mutableStateOf(0L) }
    var deckSummaries by remember(container) { mutableStateOf(container.cachedDeckSummaries()) }
    var isLoadingDecks by remember(container) { mutableStateOf(deckSummaries.isEmpty()) }
    var selectionDashboardState by remember { mutableStateOf(container.cachedSelectionDashboardState()) }
    var createDeckMessage by remember { mutableStateOf<String?>(null) }
    var addWordMessage by remember { mutableStateOf<String?>(null) }
    var manualExampleMessage by remember { mutableStateOf<String?>(null) }
    var settingsMessage by remember { mutableStateOf<String?>(null) }
    var syncFeedbackState by remember { mutableStateOf(SyncFeedbackState()) }
    var startupState by remember(container) { mutableStateOf<AppStartupState>(AppStartupState.Loading) }
    var startupWordFieldData by remember(container) { mutableStateOf(StartupWordFieldData.Empty) }
    var speechInitializationStarted by remember(container) { mutableStateOf(false) }
    val settingsViewModel = remember(container) {
        SettingsViewModel(container.savedSettings(), container::saveSettings)
    }

    suspend fun refreshHomeState() {
        homeState = withContext(Dispatchers.IO) { container.homeState() }
    }

    DisposableEffect(container, lifecycleOwner) {
        val owner = lifecycleOwner
        if (owner == null) {
            onDispose {}
        } else {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    val today = LocalDate.now()
                    if (today != lastHomeRefreshDate) {
                        lastHomeRefreshDate = today
                        scope.launch { refreshHomeState() }
                    }
                }
            }
            owner.lifecycle.addObserver(observer)
            onDispose { owner.lifecycle.removeObserver(observer) }
        }
    }

    suspend fun refreshDeckSummaries() {
        isLoadingDecks = deckSummaries.isEmpty()
        try {
            val refreshed = withContext(Dispatchers.IO) { container.deckSummaries() }
            deckSummaries = refreshed
            container.saveDeckSummariesCache(refreshed)
        } finally {
            isLoadingDecks = false
        }
    }

    suspend fun loadDeckWords(deckId: Long, forceRefresh: Boolean = false) {
        if (!forceRefresh && deckWordsDeckId == deckId && deckWordsLoadedVersion == libraryContentVersion) return
        if (!forceRefresh && loadingDeckWordsId == deckId) return
        val requestId = deckWordsRequestId + 1
        deckWordsRequestId = requestId
        val requestedContentVersion = libraryContentVersion
        if (deckWordsDeckId != deckId) {
            deckWords = emptyList()
            deckWordsDeckId = null
            deckWordsLoadedVersion = -1L
        }
        loadingDeckWordsId = deckId
        try {
            val refreshed = withContext(Dispatchers.IO) { container.wordList(deckId) }
            if (deckWordsRequestId == requestId && libraryContentVersion == requestedContentVersion) {
                deckWords = refreshed
                deckWordsDeckId = deckId
                deckWordsLoadedVersion = requestedContentVersion
            }
        } finally {
            if (deckWordsRequestId == requestId) loadingDeckWordsId = null
        }
    }

    suspend fun refreshActiveDeckWords() {
        selectedDeckId?.let { loadDeckWords(it, forceRefresh = true) }
    }

    suspend fun refreshWordList() {
        val requestId = studyWordListRequestId + 1
        studyWordListRequestId = requestId
        val requestedContentVersion = libraryContentVersion
        val refreshed = withContext(Dispatchers.IO) { container.wordList() }
        if (studyWordListRequestId == requestId && libraryContentVersion == requestedContentVersion) {
            wordList = refreshed
            studyWordListLoadedVersion = requestedContentVersion
        }
    }

    fun invalidateLibraryContent(refreshActiveDeck: Boolean = true) {
        val deckIdToRefresh = selectedDeckId ?: deckWordsDeckId ?: loadingDeckWordsId
        libraryContentVersion += 1L
        studyWordListLoadedVersion = -1L
        studyWordListRequestId += 1L
        deckWordsLoadedVersion = -1L
        deckWordsRequestId += 1L
        loadingDeckWordsId = null
        if (refreshActiveDeck) {
            deckIdToRefresh?.let { activeDeckId ->
                scope.launch { loadDeckWords(activeDeckId, forceRefresh = true) }
            }
        }
        if (hasOpenedStudySearch) scope.launch { refreshWordList() }
    }

    suspend fun refreshSelectionDashboard() {
        selectionDashboardState = withContext(Dispatchers.IO) { container.selectionDashboardState() }
    }

    fun syncMayHaveChangedData(result: SyncResult): Boolean = result.status != SyncStatus.AUTH_REQUIRED &&
            (result.appliedEvents > 0 || result.receivedEvents > 0 || result.downloadedFiles > 0 ||
                result.downloadedCheckpoints > 0 || result.checkpointAction in setOf(
                    SyncCheckpointAction.RESTORED_EMPTY_DEVICE,
                    SyncCheckpointAction.MERGED_CONTENT_ONLY,
                    SyncCheckpointAction.MERGING_INCREMENTAL,
                )
            )

    suspend fun refreshCachesAfterSync(result: SyncResult) {
        val contentMayHaveChanged = syncMayHaveChangedData(result)
        if (!contentMayHaveChanged) return
        invalidateLibraryContent()
        refreshDeckSummaries()
    }

    suspend fun runSyncWithFeedback(): SyncResult {
        syncFeedbackState = syncFeedbackState.started()
        var completionMessage: String? = null
        var syncResult: SyncResult
        try {
            val result = withContext(Dispatchers.IO) { container.syncNow() }
            syncResult = result
            completionMessage = result.toUserMessage()
            settingsMessage = completionMessage

            try {
                refreshCachesAfterSync(result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A cache refresh must not turn a completed network sync into a failure message.
            }
            if (result.status != SyncStatus.AUTH_REQUIRED &&
                syncMayHaveChangedData(result)
            ) {
                try {
                    refreshHomeState()
                    refreshSelectionDashboard()
                    val (date, period) = container.savedStatsQuery()
                    statsState = withContext(Dispatchers.IO) { container.statsState(date, period) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Keep the network sync result visible even if a follow-up local refresh fails.
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            completionMessage = "同步失败，请检查仓库权限、同步密码和网络后重试。"
            settingsMessage = completionMessage
            syncResult = SyncResult(SyncStatus.FAILED, 0, 0, 0, detail = failure.message)
        } finally {
            syncFeedbackState = syncFeedbackState.finished(completionMessage)
        }
        return syncResult
    }

    fun requestAutomaticSyncAfterStudyExit() {
        scope.launch {
            runSyncWithFeedback()
        }
    }

    val startupCoordinator = remember(container) {
        AppStartupCoordinator(
            initialState = AppStartupState.Loading,
            scope = scope,
            hasLocalContent = { withContext(Dispatchers.IO) { container.hasLocalContent() } },
            seedIfNeeded = { withContext(Dispatchers.IO) { container.runStudyOperation { container.seedIfNeeded() } } },
            loadHome = { withContext(Dispatchers.IO) { container.homeState() } },
            loadSettings = { withContext(Dispatchers.IO) { container.savedSettings() } },
            resumeSession = { null },
            writeBackup = { container.writeAutomaticBackup() },
            hasSyncConfiguration = { container.hasSyncConfiguration() },
            syncNow = { runSyncWithFeedback() },
            applySavedQuota = { withContext(Dispatchers.IO) { container.applySavedDailyQuotaToToday() } },
            onSeedCompleted = { refreshHomeState() },
            loadStartupWordFieldData = { withContext(Dispatchers.IO) { container.startupWordFieldData() } },
            onStartupWordFieldData = { startupWordFieldData = it },
        )
    }

    fun presentSession(
        session: StudySessionState,
        resetHistory: Boolean = false,
        historyEntry: StudyHistoryEntry? = null,
    ) {
        val next = session.currentItem?.let { item ->
            session.toScreenState().copy(isFavorite = container.isFavorite(item.wordId))
        }
        if (resetHistory) {
            previousStudyStates = emptyList()
            browsingStudyEntry = null
            forwardStudyState = null
        } else {
            val current = studyState
            if (current != null && next != null && current.eventId != next.eventId && browsingStudyEntry == null) {
                previousStudyStates = previousStudyStates + (historyEntry ?: StudyHistoryEntry(
                    screen = current.copy(isAnswerRevealed = true, feedbackEnabled = false, isHistorical = false),
                    undoToken = null,
                ))
            }
            browsingStudyEntry = null
            forwardStudyState = null
        }
        studyState = next?.copy(isHistorical = false)
        if (next != null) {
            scope.launch {
                val wordId = next.wordId ?: return@launch
                val relatedWords = withContext(Dispatchers.IO) { container.repository.getRelatedWords(wordId) }
                val currentScreen = studyState
                if (currentScreen != null && currentScreen.eventId == next.eventId && currentScreen.relatedWords.isEmpty()) {
                    studyState = currentScreen.copy(
                        relatedWords = relatedWords.map { related ->
                            com.pengshi.words.feature.study.RelatedWordUi(
                                spelling = related.word.spelling,
                                definitionCn = related.word.definitionCn,
                                kindLabel = related.reason,
                                wordId = related.word.id,
                            )
                        },
                    )
                }
            }
            scope.launch {
                val refreshed = container.refreshExamples(session)
                val currentScreen = studyState
                if (currentScreen?.eventId == next.eventId && refreshed.currentExamples != session.currentExamples) {
                    val screen = currentScreen ?: return@launch
                    studyState = refreshed.toScreenState().copy(
                        isFavorite = screen.isFavorite,
                        isAnswerRevealed = screen.isAnswerRevealed,
                        feedbackEnabled = screen.feedbackEnabled,
                        isHistorical = false,
                    )
                }
            }
        }
    }

    LaunchedEffect(container) {
        container.speech.voiceCatalog.collect { catalog ->
            settingsViewModel.setVoiceCatalog(catalog)
            catalog.selectedVoiceKey?.let(container::saveSpeechVoice)
        }
    }
    LaunchedEffect(container) {
        launch {
            startupCoordinator.state.collect { state ->
                startupState = state
                if (state is AppStartupState.Ready) {
                    homeState = state.initial.home
                    settingsViewModel.replaceState(state.initial.settings)
                    settingsViewModel.setVoiceCatalog(container.speech.voiceCatalog.value)
                    if (!speechInitializationStarted) {
                        speechInitializationStarted = true
                        val voices = container.speech.availableVoices()
                        settingsViewModel.setVoices(voices)
                        container.savedSpeechVoiceKey?.let { savedKey ->
                            voices.firstOrNull { it.key == savedKey }?.let { option ->
                                settingsViewModel.onAction(SettingsAction.SelectSpeechVoice(option))
                                container.speech.selectVoice(option)
                            }
                        }
                    }
                }
            }
        }
        startupCoordinator.start()
    }
    LaunchedEffect(container) {
        while (true) {
            val zoneId = java.time.ZoneId.systemDefault()
            val nextMidnight = LocalDate.now(zoneId).plusDays(1).atStartOfDay(zoneId).toInstant()
            val delayMillis = java.time.Duration.between(Instant.now(), nextMidnight).toMillis().coerceAtLeast(1L)
            kotlinx.coroutines.delay(delayMillis)
            lastHomeRefreshDate = LocalDate.now()
            refreshHomeState()
        }
    }
    DisposableEffect(container) {
        onDispose { container.close() }
    }
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        AppNavHost(
            homeState = homeState,
            syncInProgress = syncFeedbackState.isSyncing,
            syncFeedbackMessage = syncFeedbackState.completionMessage,
            onDismissSyncFeedback = { syncFeedbackState = syncFeedbackState.dismissed() },
            startupState = startupState,
            startupWordFieldData = startupWordFieldData,
            onRetryStartup = { startupCoordinator.retry() },
            onContinueOfflineStartup = { startupCoordinator.continueOffline() },
            statsState = statsState,
            wordList = wordList,
            deckWords = deckWords,
            deckSummaries = deckSummaries,
            isLoadingDecks = isLoadingDecks,
            isLoadingDeckWords = loadingDeckWordsId != null,
            selectionDashboardState = selectionDashboardState,
            onSaveSelection = { selection ->
                val previous = selectionDashboardState
                val allDeckIds = selectionDashboardState.decks.mapTo(linkedSetOf()) { it.id }
                val normalized = selection.copy(deckWeights = selection.resolve(allDeckIds).deckWeights)
                selectionDashboardState = previous.copy(
                    wordPoolSelection = normalized,
                    deckWeights = normalized.deckWeights,
                    isSaving = true,
                    saveError = null,
                )
                container.saveSelectionDashboardCache(selectionDashboardState)
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) { container.saveWordPoolSelection(normalized) }
                    }
                        .onSuccess { refreshSelectionDashboard() }
                        .onFailure {
                            val restored = previous.copy(
                                isSaving = false,
                                saveError = it.message ?: "保存失败",
                            )
                            selectionDashboardState = restored
                            container.saveSelectionDashboardCache(restored)
                        }
                }
            },
            onToggleExcludedWord = { wordId ->
                container.toggleExcludedWord(wordId)
                scope.launch {
                    refreshSelectionDashboard()
                }
            },
            createDeckMessage = createDeckMessage,
            onCreateUserDeck = { name ->
                scope.launch {
                    val result = runCatching {
                        container.createUserDeck(name)
                        "词库已创建。"
                    }
                    createDeckMessage = result.getOrElse { "创建失败：${it.message ?: "请输入有效名称"}" }
                    refreshDeckSummaries()
                    refreshSelectionDashboard()
                    if (result.isSuccess && container.hasSyncConfiguration()) scope.launch { runSyncWithFeedback() }
                }
            },
            onDeleteUserDeck = { deckId ->
                scope.launch {
                    val result = runCatching { container.deleteUserDeck(deckId) }
                    createDeckMessage = result.getOrElse { "删除失败：${it.message ?: "请稍后重试"}" }
                    if (result.isSuccess) {
                        invalidateLibraryContent(refreshActiveDeck = false)
                        refreshDeckSummaries()
                        refreshSelectionDashboard()
                        if (container.hasSyncConfiguration()) scope.launch { runSyncWithFeedback() }
                    }
                }
            },
            addWordMessage = addWordMessage,
            onAddWordToDeck = { deckId, input ->
                scope.launch {
                    val result = runCatching { container.addWordToDeck(deckId, input) }
                    addWordMessage = result.getOrElse { "添加失败：${it.message ?: "请输入有效单词"}" }
                    if (result.isSuccess) invalidateLibraryContent()
                    refreshDeckSummaries()
                    refreshSelectionDashboard()
                    if (result.isSuccess && container.hasSyncConfiguration()) scope.launch { runSyncWithFeedback() }
                }
            },
            onUpdateWordInDeck = { deckId, wordId, input ->
                scope.launch {
                    val result = runCatching { container.updateWordInDeck(deckId, wordId, input) }
                    addWordMessage = result.getOrElse { "修改失败：${it.message ?: "请检查词条内容"}" }
                    if (result.isSuccess) invalidateLibraryContent()
                    refreshDeckSummaries()
                    refreshSelectionDashboard()
                    if (result.isSuccess && container.hasSyncConfiguration()) scope.launch { runSyncWithFeedback() }
                }
            },
            onDeleteWordFromDeck = { deckId, wordId ->
                scope.launch {
                    val result = runCatching { container.deleteWordFromDeck(deckId, wordId) }
                    addWordMessage = result.getOrElse { "删除失败：${it.message ?: "请稍后重试"}" }
                    if (result.isSuccess) invalidateLibraryContent()
                    refreshDeckSummaries()
                    refreshSelectionDashboard()
                    if (result.isSuccess && container.hasSyncConfiguration()) scope.launch { runSyncWithFeedback() }
                }
            },
            onBulkAddWordsToDeck = { deckId, text, sources ->
                scope.launch {
                    val result = runCatching { container.bulkAddWordsToDeck(deckId, text, sources) }
                    addWordMessage = result.getOrElse { "批量导入失败：${it.message ?: "请检查输入格式"}" }
                    if (result.isSuccess) invalidateLibraryContent()
                    refreshDeckSummaries()
                    refreshSelectionDashboard()
                    if (result.isSuccess && container.hasSyncConfiguration()) scope.launch { runSyncWithFeedback() }
                }
            },
            onImportWordsToDeck = { deckId, uri, sources ->
                scope.launch {
                    val result = runCatching { container.importWordsToDeck(deckId, uri, sources) }
                    addWordMessage = result.getOrElse { "文件导入失败：${it.message ?: "请检查文件格式"}" }
                    if (result.isSuccess) invalidateLibraryContent()
                    refreshDeckSummaries()
                    refreshSelectionDashboard()
                    if (result.isSuccess && container.hasSyncConfiguration()) scope.launch { runSyncWithFeedback() }
                }
            },
            onOpenDeck = { deckId ->
                selectedDeckId = deckId
                scope.launch { loadDeckWords(deckId, forceRefresh = true) }
            },
            manualExampleMessage = manualExampleMessage,
            onAddExample = { wordId, sentenceEn, sentenceCn, source ->
                scope.launch {
                    val result = runCatching {
                        container.addManualExample(wordId, sentenceEn, sentenceCn, source)
                    }
                    manualExampleMessage = result.getOrElse { "保存失败：${it.message ?: "请输入有效英文例句"}" }
                    if (result.isSuccess) invalidateLibraryContent(refreshActiveDeck = false)
                    refreshActiveDeckWords()
                }
            },
            onUpdateExample = { exampleId, sentenceEn, sentenceCn, source ->
                scope.launch {
                    val result = runCatching {
                        container.updateManualExample(exampleId, sentenceEn, sentenceCn, source)
                    }
                    manualExampleMessage = result.getOrElse { "保存失败：${it.message ?: "请检查例句内容"}" }
                    if (result.isSuccess) invalidateLibraryContent(refreshActiveDeck = false)
                    refreshActiveDeckWords()
                }
            },
            onDeleteExample = { exampleId ->
                scope.launch {
                    val result = runCatching { container.deleteManualExample(exampleId) }
                    manualExampleMessage = result.getOrElse { "删除失败：${it.message ?: "请稍后重试"}" }
                    if (result.isSuccess) invalidateLibraryContent(refreshActiveDeck = false)
                    refreshActiveDeckWords()
                }
            },
            settingsState = settingsViewModel.state,
            speechVoices = settingsViewModel.state.availableVoices,
            onSpeakWordWithVoice = { word, voice ->
                container.speech.stop()
                container.speech.speakWithVoice(word, voice, "deck-word-${voice.key}", settingsViewModel.state.speechRate)
            },
            studyState = studyState,
            studyTransitionMessage = studyTransitionMessage,
            onStartStudy = { mode ->
                studyTransitionMessage = "正在准备今日学习…"
                scope.launch {
                    try {
                        val session = withContext(Dispatchers.IO) {
                            container.runStudyOperation {
                                container.start.startDailyStudy(LocalDate.now(), Instant.now(), mode)
                            }
                        }
                        presentSession(session, resetHistory = true)
                        refreshHomeState()
                        if (session.currentItem == null && homeState.phase == HomeStudyPhase.CHOOSE_NEW) {
                            newWordChoiceRequest += 1
                        }
                    } finally {
                        studyTransitionMessage = null
                    }
                }
            },
            onAddNewWords = { count, mode ->
                studyTransitionMessage = "正在按已保存比例选择新词…"
                scope.launch {
                    try {
                        val session = withContext(Dispatchers.IO) {
                            container.runStudyOperation {
                                container.addNewWords.addNewWords(LocalDate.now(), Instant.now(), mode, count)
                            }
                        }
                        presentSession(session, resetHistory = true)
                        refreshHomeState()
                        if (session.currentItem == null && homeState.phase == HomeStudyPhase.CHOOSE_NEW) {
                            newWordChoiceRequest += 1
                        }
                    } finally {
                        studyTransitionMessage = null
                    }
                }
            },
            onLoadManualWordCatalog = { mode -> container.manualWordCatalog(mode) },
            onAddSelectedNewWords = { ids, mode ->
                studyTransitionMessage = "正在准备你挑选的新词…"
                scope.launch {
                    try {
                        val session = withContext(Dispatchers.IO) {
                            container.runStudyOperation {
                                container.addNewWords.addSelectedNewWords(LocalDate.now(), Instant.now(), mode, ids)
                            }
                        }
                        presentSession(session, resetHistory = true)
                        refreshHomeState()
                        if (session.currentItem == null && homeState.phase == HomeStudyPhase.CHOOSE_NEW) {
                            newWordChoiceRequest += 1
                        }
                    } finally {
                        studyTransitionMessage = null
                    }
                }
            },
            newWordChoiceRequest = newWordChoiceRequest,
            studyCompletionRequest = studyCompletionRequest,
            onAppendExtraWords = { count, deckId, onResult ->
                studyTransitionMessage = "正在选择额外单词…"
                scope.launch {
                    try {
                        val result = withContext(Dispatchers.IO) {
                            container.runStudyOperation {
                                container.appendExtraWords.append(
                                    LocalDate.now(), Instant.now(), StudyMode.EN_TO_CN, count, sourceDeckId = deckId,
                                )
                            }
                        }
                        if (result.appended > 0) {
                            presentSession(result.session, resetHistory = true)
                        }
                        onResult(result.appended)
                    } finally {
                        studyTransitionMessage = null
                    }
                }
            },
            onStatsQueryChange = { anchorDate, period ->
                scope.launch {
                    container.saveStatsQuery(anchorDate, period)
                    container.cachedStatsState(anchorDate, period)?.let { statsState = it }
                    statsState = withContext(Dispatchers.IO) { container.statsState(anchorDate, period) }
                }
            },
            onStatsEntered = {
                scope.launch {
                    val (date, period) = container.savedStatsQuery()
                    container.cachedStatsState(date, period)?.let { statsState = it }
                    statsState = withContext(Dispatchers.IO) { container.statsState(date, period) }
                }
            },
            onHomeEntered = { scope.launch { refreshHomeState() } },
            onDecksEntered = { scope.launch { refreshDeckSummaries() } },
            onSelectionDashboardEntered = { scope.launch { refreshSelectionDashboard() } },
            onStudySearchOpened = {
                hasOpenedStudySearch = true
                if (studyWordListLoadedVersion != libraryContentVersion) scope.launch { refreshWordList() }
            },
            statsTab = statsTab,
            onStatsTabChange = { tab ->
                statsTab = tab
                container.saveStatsTab(tab)
            },
            onSettingsAction = { action ->
                when (action) {
                    is SettingsAction.SetDailyQuota -> {
                        if (action.quota !in DailyQuota.MIN..DailyQuota.MAX) {
                            settingsMessage = "每日额度必须在 1–100 之间。"
                        } else scope.launch {
                            settingsViewModel.setDailyQuotaUpdating(true)
                            try {
                                withContext(Dispatchers.IO) { container.updateDailyQuota(action.quota) }
                                settingsViewModel.replaceState(container.savedSettings())
                                settingsMessage = "每日额度已保存；今天尚未开始的自动新词已按新额度调整。"
                                refreshHomeState()
                                runSyncWithFeedback()
                            } catch (failure: Exception) {
                                settingsViewModel.replaceState(container.savedSettings())
                                settingsMessage = "每日额度未能保存：${failure.message ?: "请重试"}"
                            } finally {
                                settingsViewModel.setDailyQuotaUpdating(false)
                            }
                        }
                    }
                    SettingsAction.RefreshVoices -> container.speech.refreshVoices()
                    is SettingsAction.DownloadSpeechModel -> container.speech.downloadModel(action.model)
                    is SettingsAction.ImportSpeechModel -> container.speech.importModelFolder(action.folder)
                    is SettingsAction.UninstallSpeechModel -> container.speech.uninstallModel(action.model)
                    is SettingsAction.SetVoiceEnabled -> container.speech.setVoiceEnabled(action.option, action.enabled)
                    is SettingsAction.PreviewSpeechVoice -> container.speech.speakWithVoice(
                        "Hello! This is your English reading voice.", action.option, "voice-preview", container.savedSpeechRate,
                    )
                    is SettingsAction.SelectSpeechVoice -> {
                        settingsViewModel.onAction(action)
                        container.saveSpeechVoice(action.option?.key)
                        container.speech.selectVoice(action.option)
                    }
                    is SettingsAction.SetSpeechRate -> {
                        settingsViewModel.onAction(action)
                        container.saveSpeechRate(action.rate)
                    }
                    else -> {
                        settingsViewModel.onAction(action)
                    }
                }
            },
            onImportWordList = { uri ->
                scope.launch {
                    val result = runCatching { container.importWordList(uri) }
                    settingsMessage = result.getOrElse { "导入失败：${it.message ?: "文件格式不正确"}" }
                    if (result.isSuccess) {
                        invalidateLibraryContent()
                        refreshDeckSummaries()
                        if (container.hasSyncConfiguration()) scope.launch { runSyncWithFeedback() }
                    }
                    refreshHomeState()
                }
            },
            onExportBackup = { uri ->
                scope.launch {
                    settingsMessage = runCatching { container.exportBackup(uri) }
                        .getOrElse { "备份失败：${it.message ?: "无法写入文件"}" }
                }
            },
            onRestoreBackup = { uri ->
                scope.launch {
                    val result = runCatching { container.restoreBackup(uri) }
                    settingsMessage = result.getOrElse { "恢复失败：${it.message ?: "文件无效或与当前数据冲突"}" }
                    if (result.isSuccess) {
                        invalidateLibraryContent()
                        refreshDeckSummaries()
                        refreshSelectionDashboard()
                    }
                    refreshHomeState()
                }
            },
            onSaveGitHubSync = { owner, repository, branch, password, token ->
                scope.launch {
                    settingsMessage = runCatching {
                        container.saveSyncConnection(owner, repository, branch, password, token)
                        settingsViewModel.replaceState(container.savedSettings())
                        "GitHub 同步连接已保存。"
                    }.getOrElse { "保存同步连接失败：${it.message ?: "请检查配置"}" }
                }
            },
            onSyncNow = {
                scope.launch {
                    runSyncWithFeedback()
                }
            },
            syncSummary = container.syncConnectionSummary(),
            settingsMessage = settingsMessage,
            onStudyAction = { action ->
                when (action) {
                    StudyAction.RevealAnswer -> studyState?.let {
                        container.speech.stop()
                        val texts = buildList {
                            if (settingsViewModel.state.autoPlayWord) add(it.englishWord.ifBlank { it.prompt })
                            if (settingsViewModel.state.autoPlaySentence) {
                                addAll(SpeechTextPolicy.firstSentence(it.examples.map { example -> example.sentenceEn }))
                            }
                        }
                        container.speech.speakSequence(texts, settingsViewModel.state.speechRate)
                    }
                    StudyAction.SpeakWord -> studyState?.let {
                        container.speech.stop()
                        container.speech.speak(it.englishWord.ifBlank { it.prompt }, "word", settingsViewModel.state.speechRate)
                    }
                    is StudyAction.SpeakWordWithVoice -> studyState?.let {
                        container.speech.stop()
                        container.speech.speakWithVoice(
                            it.englishWord.ifBlank { it.prompt },
                            action.voice,
                            "word-${action.voice.key}",
                            settingsViewModel.state.speechRate,
                        )
                    }
                    StudyAction.SpeakSentence -> studyState?.let {
                        container.speech.stop()
                        container.speech.speakSequence(
                            SpeechTextPolicy.firstSentence(it.examples.map { example -> example.sentenceEn }),
                            settingsViewModel.state.speechRate,
                        )
                    }
                    is StudyAction.SpeakExample -> {
                        container.speech.stop()
                        container.speech.speak(action.sentenceEn, "sentence", settingsViewModel.state.speechRate)
                    }
                    StudyAction.StopSpeech -> container.speech.stop()
                    is StudyAction.SubmitFeedback -> {
                        val currentState = studyState ?: return@AppNavHost
                        val itemId = currentState.itemId ?: return@AppNavHost
                        container.speech.stop()
                        studyState = studyState?.copy(feedbackEnabled = false)
                        if (currentState.stageLabel == "复习" && currentState.remainingCount == 1) {
                            studyTransitionMessage = "正在完成本轮复习…"
                        }
                        scope.launch {
                            val historyEntry = browsingStudyEntry
                            try {
                                val result = withContext(Dispatchers.IO) {
                                    container.runStudyOperation {
                                        if (historyEntry?.undoToken != null) {
                                            container.submit.reviseFeedback(
                                                historyEntry.undoToken, action.feedback, Instant.now(), action.responseTimeMs,
                                            )
                                        } else {
                                            container.submit.submitFeedbackWithUndo(
                                                itemId, action.feedback, Instant.now(), responseTimeMs = action.responseTimeMs,
                                            )
                                        }
                                    }
                                }
                                if (historyEntry != null) {
                                    browsingStudyEntry = null
                                    forwardStudyState = null
                                }
                                result.session.currentItem?.let {
                                    presentSession(
                                        result.session,
                                        historyEntry = StudyHistoryEntry(
                                            screen = currentState.copy(isAnswerRevealed = true, feedbackEnabled = false, isHistorical = false),
                                            undoToken = result.undoToken,
                                        ),
                                    )
                                }
                                container.writeAutomaticBackup()
                                if (result.session.currentItem == null) {
                                    refreshHomeState()
                                    if (currentState.stageLabel == "复习" && homeState.phase == HomeStudyPhase.CHOOSE_NEW) {
                                        newWordChoiceRequest += 1
                                    } else {
                                        studyCompletionRequest += 1
                                    }
                                    studyState = null
                                    previousStudyStates = emptyList()
                                }
                            } finally {
                                studyTransitionMessage = null
                            }
                        }
                    }
                    StudyAction.ToggleFavorite -> {
                        studyState?.wordId?.let { wordId ->
                            studyState = studyState?.copy(isFavorite = container.toggleFavorite(wordId))
                        }
                    }
                }
            },
            onAddWordToToday = { wordId ->
                scope.launch {
                    container.speech.stop()
                    val session = withContext(Dispatchers.IO) {
                        container.runStudyOperation {
                            container.addWordToToday.addWordToToday(LocalDate.now(), Instant.now(), StudyMode.EN_TO_CN, wordId)
                        }
                    }
                    presentSession(session)
                }
            },
            onStudyBack = {
                container.speech.stop()
            },
            onStudyExited = { requestAutomaticSyncAfterStudyExit() },
            canGoPreviousStudyWord = previousStudyStates.isNotEmpty() && browsingStudyEntry == null,
            onPreviousStudyWord = {
                container.speech.stop()
                if (browsingStudyEntry != null) return@AppNavHost
                previousStudyStates.lastOrNull()?.let { previous ->
                    previousStudyStates = previousStudyStates.dropLast(1)
                    browsingStudyEntry = previous
                    forwardStudyState = studyState
                    studyState = previous.screen.copy(
                        isAnswerRevealed = true,
                        feedbackEnabled = previous.undoToken != null,
                        isHistorical = true,
                    )
                }
            },
            canGoNextStudyWord = browsingStudyEntry != null && forwardStudyState != null,
            onNextStudyWord = {
                container.speech.stop()
                val previous = browsingStudyEntry ?: return@AppNavHost
                val forward = forwardStudyState ?: return@AppNavHost
                previousStudyStates = previousStudyStates + previous
                browsingStudyEntry = null
                forwardStudyState = null
                studyState = forward.copy(isHistorical = false)
            },
        )
    }
}

private fun SyncResult.toUserMessage(): String = userMessage()

private class AppContainer(private val context: Context) {
    private val operationMutex = Mutex()

    suspend fun <T> runStudyOperation(block: suspend () -> T): T = operationMutex.withLock { block() }
    private val database = Room.databaseBuilder(context, PengshiDatabase::class.java, "pengshi-words.db")
        .addMigrations(
            PengshiDatabase.MIGRATION_1_2,
            PengshiDatabase.MIGRATION_2_3,
            PengshiDatabase.MIGRATION_3_4,
            PengshiDatabase.MIGRATION_4_5,
            PengshiDatabase.MIGRATION_5_6,
            PengshiDatabase.MIGRATION_6_7,
            PengshiDatabase.MIGRATION_4_5,
            PengshiDatabase.MIGRATION_5_6,
        )
        .build()
    private val syncPreferences = context.getSharedPreferences("pengshi-sync", Context.MODE_PRIVATE)
    private val syncDeviceId = syncPreferences.getString("device_id", null)
        ?: "android-${UUID.randomUUID()}".also { syncPreferences.edit().putString("device_id", it).apply() }
    private val syncStore = SyncRoomStore(database, syncDeviceId)
    private val syncTokenStore = AndroidTokenStore(context)
    val repository = RoomStudySessionRepository(database, syncStore)
    private val scheduler = DefaultStudyScheduler()
    val start = StartDailyStudyUseCase(
        repository = repository,
        scheduler = scheduler,
        quotaProvider = { preferences.getInt(DAILY_QUOTA_KEY, DailyQuota.DEFAULT).let(DailyQuota::normalize) },
        eligibleWordIdsProvider = { currentEligibleWordIds() },
        automaticWordPoolProvider = { currentAutomaticWordPool() },
        loadRelatedWordsImmediately = false,
        emptyEligibleWordSetMeansNoWords = true,
    )
    val resume = ResumeDailyStudyUseCase(
        repository = repository,
        defaultMode = StudyMode.EN_TO_CN,
        scheduler = scheduler,
        quotaProvider = { preferences.getInt(DAILY_QUOTA_KEY, DailyQuota.DEFAULT).let(DailyQuota::normalize) },
            reconciler = ReconcileDailyPlanUseCase(
                repository = repository,
                scheduler = scheduler,
                eligibleWordIdsProvider = { currentEligibleWordIds() },
                automaticWordPoolProvider = { currentAutomaticWordPool() },
                emptyEligibleWordSetMeansNoWords = true,
            ),
    )
    private val adjustDailyQuota = AdjustDailyQuotaUseCase(repository, start)
    val submit = SubmitFeedbackUseCase(repository, scheduler, start)
    val addNewWords = AddNewWordsUseCase(
        repository = repository,
        scheduler = scheduler,
        renderer = start,
        eligibleWordIdsProvider = { currentEligibleWordIds() },
        automaticWordPoolProvider = { currentAutomaticWordPool() },
    )
    val appendExtraWords = AppendExtraWordsUseCase(
        repository = repository,
        scheduler = scheduler,
        renderer = start,
        eligibleWordIdsProvider = { currentEligibleWordIds() },
        automaticWordPoolProvider = { currentAutomaticWordPool() },
        deckWordIdsProvider = { deckId ->
            database.deckDao().getDeckWordsForDecks(listOf(deckId)).mapTo(linkedSetOf()) { it.wordId }
        },
    )
    val addWordToToday = AddWordToTodayUseCase(repository, start)
    private val snapshotGateway = RoomStudyDataSnapshotGateway(database)
    private val automaticBackupStore = AutomaticBackupStore(context.filesDir)
    val speech = AndroidSpeechEngine(context)
    private val exampleService = TatoebaExampleService()

    init {
        syncStore.remoteEventApplier = SyncRemoteEventApplier { event -> applyRemoteEvent(event) }
    }

    private suspend fun applyRemoteEvent(event: SyncEventRecord): Boolean {
        val v2Payload = runCatching { SyncPayloadV2.decode(event.payload) }.getOrNull()
        if (v2Payload != null) {
            return when (v2Payload) {
                is PlanLockedV2 -> applyRemotePlan(v2Payload)
                is PlanReconciledV2 -> applyRemotePlanReconciliation(v2Payload)
                is FeedbackPayloadV2 -> applyRemoteFeedback(v2Payload)
            }
        }
        if (event.kind != SyncEventKind.FEEDBACK_RECORDED && event.kind != SyncEventKind.FEEDBACK_REVISED) return false
        val localDate = event.planKey?.removePrefix("plan:")?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return false
        val normalizedWord = event.wordKey?.removePrefix("word:")?.trim()?.lowercase(Locale.ROOT) ?: return false
        val word = database.wordDao().getByNormalizedSpelling(normalizedWord) ?: return false
        val plan = repository.getPlan(localDate) ?: return false
        val item = repository.getItems(plan.id).firstOrNull { it.wordId == word.id && it.source != PlanSource.EXTRA } ?: return false
        val feedbackName = Regex("(?:^|\\|)feedback=([^|]+)").find(event.payload)?.groupValues?.get(1) ?: return false
        val feedback = runCatching { Feedback.valueOf(feedbackName) }.getOrNull() ?: return false
        val hasPending = repository.getEvents(item.id).any { it.status == com.pengshi.words.model.IntradayEventStatus.PENDING }
        if (!hasPending) return true
        return runCatching {
            submit.submitFeedbackWithUndo(
                item.id,
                feedback,
                event.occurredAtUtc,
                submission = com.pengshi.words.model.FeedbackSubmission(recordSyncEvent = false),
            )
            true
        }.getOrDefault(false)
    }

    private suspend fun applyRemotePlan(payload: PlanLockedV2): Boolean {
        val planKey = SyncKeyFactory.planKey(payload.localDate)
        val existing = repository.getPlan(payload.localDate)
        val entries = payload.items.map { item ->
            val spelling = item.wordKey.removePrefix("word:").trim().lowercase(Locale.ROOT)
            val word = database.wordDao().getByNormalizedSpelling(spelling) ?: return false
            ensureRemoteCard(word.id, payload.studyMode, payload.createdAtUtc)
            DailyPlanEntry(
                item = DailyPlanItem(0, existing?.id ?: 0, word.id, item.source, item.position),
                initialEvent = IntradayReviewEvent(
                    dailyPlanItemId = 0,
                    wordId = word.id,
                    mode = payload.studyMode,
                    stepIndex = 0,
                    scheduledAt = payload.createdAtUtc.plusSeconds(item.position.toLong()),
                    status = IntradayEventStatus.PENDING,
                ),
            )
        }
        val localPlanRepository = RoomDailyPlanRepository(database)
        if (existing == null) {
            localPlanRepository.createPlan(
                DailyPlan(
                    localDate = payload.localDate,
                    quota = payload.quota,
                    plannedUniqueWordCount = entries.count { it.item.source != PlanSource.EXTRA },
                    createdAt = payload.createdAtUtc,
                    updatedAt = payload.createdAtUtc,
                    status = DailyPlanStatus.IN_PROGRESS,
                ),
                entries,
            )
            return true
        }
        val existingKeys = repository.getItems(existing.id).mapNotNull { item ->
            repository.getWord(item.wordId)?.let { word ->
                SyncKeyFactory.itemKey(planKey, SyncKeyFactory.wordKey(word.normalizedSpelling), item.source)
            }
        }.toSet()
        val remoteKeys = payload.items.mapTo(linkedSetOf()) { it.itemKey }
        val existingItems = repository.getItems(existing.id)
        val staleUnseenNewItems = existingItems.filter { item ->
            item.source == PlanSource.NEW &&
                SyncKeyFactory.itemKey(
                    planKey,
                    SyncKeyFactory.wordKey(repository.getWord(item.wordId)?.normalizedSpelling ?: return@filter false),
                    item.source,
                ) !in remoteKeys &&
                item.status == DailyItemStatus.PENDING && repository.getEvents(item.id).let { events ->
                    events.isNotEmpty() && events.all { event ->
                        event.status == IntradayEventStatus.PENDING && event.feedback == null
                    }
                }
        }
        val unmatchedFixedItems = existingItems.filter { item ->
            item.source != PlanSource.EXTRA &&
                item !in staleUnseenNewItems &&
                SyncKeyFactory.itemKey(
                    planKey,
                    SyncKeyFactory.wordKey(repository.getWord(item.wordId)?.normalizedSpelling ?: return@filter false),
                    item.source,
                ) !in remoteKeys
        }
        if (unmatchedFixedItems.isNotEmpty()) {
            return PlanLockCompatibility.canKeepLocalSuperset(
                remoteKeys = remoteKeys,
                localKeys = existingKeys,
                unmatchedLocalSources = unmatchedFixedItems.map { it.source },
            )
        }
        val missing = entries.filterNot { entry ->
            val word = database.wordDao().getById(entry.item.wordId) ?: return@filterNot false
            SyncKeyFactory.itemKey(planKey, SyncKeyFactory.wordKey(word.normalizedSpelling), entry.item.source) in existingKeys
        }
        val mainCount = existingItems.count { it.source != PlanSource.EXTRA }
        val targetMainCount = mainCount - staleUnseenNewItems.size + missing.count { it.item.source != PlanSource.EXTRA }
        if (targetMainCount > maxOf(existing.quota, payload.quota)) return false
        if (staleUnseenNewItems.isEmpty() && missing.isEmpty() && existing.quota == payload.quota) return true
        localPlanRepository.replaceUnseenNewItems(
            existing.copy(
                quota = payload.quota,
                plannedUniqueWordCount = targetMainCount,
                updatedAt = payload.createdAtUtc,
            ),
            staleUnseenNewItems.map { it.id }.toSet(),
            missing,
            payload.createdAtUtc,
        )
        return true
    }

    private suspend fun applyRemotePlanReconciliation(payload: PlanReconciledV2): Boolean {
        val existing = repository.getPlan(payload.localDate) ?: return false
        val planKey = SyncKeyFactory.planKey(payload.localDate)
        val existingItems = repository.getItems(existing.id)
        val itemKeys = existingItems.mapNotNull { item ->
            repository.getWord(item.wordId)?.let { word ->
                SyncKeyFactory.itemKey(planKey, SyncKeyFactory.wordKey(word.normalizedSpelling), item.source) to item
            }
        }.toMap()
        val removedItems = payload.removedItemKeys.mapNotNull { itemKeys[it] }
        if (removedItems.any { item ->
                item.source != PlanSource.NEW || item.status != DailyItemStatus.PENDING ||
                    repository.getEvents(item.id).let { events ->
                        events.isEmpty() || events.any { event -> event.status != IntradayEventStatus.PENDING || event.feedback != null }
                    }
            }) return false
        val addedEntries = payload.addedItems.map { item ->
            val normalized = item.wordKey.removePrefix("word:").trim().lowercase(Locale.ROOT)
            val word = database.wordDao().getByNormalizedSpelling(normalized) ?: return false
            ensureRemoteCard(word.id, payload.studyMode, Instant.now())
            DailyPlanEntry(
                item = DailyPlanItem(0, existing.id, word.id, item.source, item.position),
                initialEvent = IntradayReviewEvent(
                    dailyPlanItemId = 0,
                    wordId = word.id,
                    mode = payload.studyMode,
                    stepIndex = 0,
                    scheduledAt = Instant.now().plusSeconds(item.position.toLong()),
                    status = IntradayEventStatus.PENDING,
                ),
            )
        }
        val existingKeys = itemKeys.keys
        val missingEntries = addedEntries.filterNot { entry ->
            val word = database.wordDao().getById(entry.item.wordId) ?: return@filterNot false
            SyncKeyFactory.itemKey(planKey, SyncKeyFactory.wordKey(word.normalizedSpelling), entry.item.source) in existingKeys
        }
        val updatedQuota = payload.quota ?: existing.quota
        if (removedItems.isEmpty()) {
            if (missingEntries.isEmpty() && updatedQuota == existing.quota) return true
            val mainCount = existingItems.count { it.source != PlanSource.EXTRA }
            if (missingEntries.isEmpty()) {
                RoomDailyPlanRepository(database).replaceUnseenNewItems(
                    existing.copy(quota = updatedQuota, plannedUniqueWordCount = mainCount, updatedAt = Instant.now()),
                    emptySet(),
                    emptyList(),
                    Instant.now(),
                )
            } else RoomDailyPlanRepository(database).appendToPlan(
                existing.copy(
                    quota = updatedQuota,
                    plannedUniqueWordCount = mainCount + missingEntries.count { it.item.source != PlanSource.EXTRA },
                    updatedAt = Instant.now(),
                ),
                missingEntries,
            )
        } else {
            val mainCount = existingItems.count { it.source != PlanSource.EXTRA }
            RoomDailyPlanRepository(database).replaceUnseenNewItems(
                existing.copy(
                    quota = updatedQuota,
                    plannedUniqueWordCount = (mainCount - removedItems.size + missingEntries.count { it.item.source != PlanSource.EXTRA }).coerceAtLeast(0),
                    updatedAt = Instant.now(),
                ),
                removedItems.map { it.id }.toSet(),
                missingEntries,
                Instant.now(),
            )
        }
        return true
    }

    private suspend fun applyRemoteFeedback(payload: FeedbackPayloadV2): Boolean {
        val localDate = payload.planKey.removePrefix("plan:").let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return false
        val normalizedWord = payload.wordKey.removePrefix("word:").trim().lowercase(Locale.ROOT)
        val word = database.wordDao().getByNormalizedSpelling(normalizedWord) ?: return false
        val plan = repository.getPlan(localDate) ?: return false
        val item = repository.getItems(plan.id).firstOrNull {
            it.wordId == word.id && SyncKeyFactory.itemKey(payload.planKey, payload.wordKey, it.source) == payload.itemKey
        } ?: return false
        if (repository.getEvents(item.id).none { it.status == IntradayEventStatus.PENDING }) return true
        return runCatching {
            submit.submitFeedbackWithUndo(
                item.id,
                payload.feedback,
                payload.reviewedAtUtc,
                responseTimeMs = payload.responseTimeMs,
                submission = com.pengshi.words.model.FeedbackSubmission(recordSyncEvent = false),
            )
            true
        }.getOrDefault(false)
    }

    private suspend fun ensureRemoteCard(wordId: Long, mode: StudyMode, now: Instant) {
        if (database.cardStateDao().get(wordId, mode) != null) return
        database.cardStateDao().insert(
            CardStateEntity(
                wordId = wordId,
                studyMode = mode,
                state = CardStatus.NEW,
                difficulty = 0.0,
                stability = 0.0,
                retrievability = 0.0,
                dueAt = null,
                lastReviewedAt = null,
                scheduledDays = 0,
                repetitions = 0,
                lapses = 0,
                learningStep = 0,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    suspend fun hasLocalContent(): Boolean = database.wordDao().count() > 0

    suspend fun startupWordFieldData(): StartupWordFieldData = database.withTransaction {
        StartupWordFieldData(
            wordIds = database.wordDao().getAllActiveIds(),
            completedFeedbackWordIds = database.reviewLogDao().getReviewedWordIds().toSet(),
        )
    }

    suspend fun seedIfNeeded() {
        RoomUserDeckRepository(database).labelUnattributedLiteratureContent()
        val cet6Status = database.wordDao().getTagSeedStatus("cet6-core")
        val hasCet6Pack = cet6Status.wordCount >= CET6_CORE_WORD_COUNT
        val needsCet6PackImport = !hasCet6Pack
        val needsCet6FrequencyUpgrade = cet6Status.missingFrequencyRankCount > 0
        val needsSourceMetadataUpgrade = syncPreferences.getInt(CET6_SOURCE_METADATA_VERSION_KEY, 0) < CET6_SOURCE_METADATA_VERSION
        val needsBuiltinMetadataUpgrade = syncPreferences.getInt(BUILTIN_METADATA_VERSION_KEY, 0) < BUILTIN_METADATA_VERSION
        val parsed = if (needsCet6PackImport || needsCet6FrequencyUpgrade || needsSourceMetadataUpgrade || needsBuiltinMetadataUpgrade) {
            DefaultWordImportParser().parse(
                input = context.assets.open("wordpacks/cet6/words.csv"),
                format = ImportFormat.CSV,
            )
        } else {
            null
        }
        if (hasCet6Pack) {
            // Upgrade an already-installed pack in place so the phone keeps its cards and does not get a duplicate deck.
            if (needsCet6FrequencyUpgrade) {
                val ranksBySpelling = requireNotNull(parsed).rows.associate { row ->
                    row.spelling.trim().lowercase(Locale.ROOT) to Cet6FrequencyRanks.rank(row.spelling)
                }
                val updates = database.wordDao().getByTag("cet6-core")
                    .filter { it.frequencyRank == null }
                    .mapNotNull { current ->
                        ranksBySpelling[current.normalizedSpelling]?.let { rank -> current.copy(frequencyRank = rank) }
                    }
                if (updates.isNotEmpty()) database.wordDao().updateAll(updates)
            }
            database.deckDao().getByName("六级核心词汇")?.let { deck ->
                if (deck.sourceType != com.pengshi.words.database.DeckSourceTypeEntity.BUILTIN) {
                    database.deckDao().update(deck.copy(sourceType = com.pengshi.words.database.DeckSourceTypeEntity.BUILTIN))
                }
            }
        } else {
            val preview = requireNotNull(parsed).copy(rows = parsed.rows.map { it.copy(frequencyRank = Cet6FrequencyRanks.rank(it.spelling)) })
            RoomImportRepository(database).importDeck(preview)
        }
        if (needsSourceMetadataUpgrade) {
            val sourcePreview = requireNotNull(parsed)
            RoomImportRepository(database).reconcileCet6SourceMetadata(sourcePreview)
            syncPreferences.edit().putInt(CET6_SOURCE_METADATA_VERSION_KEY, CET6_SOURCE_METADATA_VERSION).apply()
        }
        if (needsBuiltinMetadataUpgrade && hasCet6Pack) {
            RoomImportRepository(database).reconcileBuiltinWordMetadata(requireNotNull(parsed))
        }
        if (syncPreferences.getInt(CET6_EXAMPLES_VERSION_KEY, 0) < CET6_EXAMPLES_VERSION) {
            context.assets.open("wordpacks/cet6/examples.csv").use { input ->
                RoomExamplePackImporter(database).importCet6Examples(input)
            }
            syncPreferences.edit().putInt(CET6_EXAMPLES_VERSION_KEY, CET6_EXAMPLES_VERSION).apply()
        }
        BUILTIN_ASSET_PACKS.forEach { seedBuiltinAssetPack(it, needsBuiltinMetadataUpgrade) }
        if (needsBuiltinMetadataUpgrade) {
            syncPreferences.edit().putInt(BUILTIN_METADATA_VERSION_KEY, BUILTIN_METADATA_VERSION).apply()
        }
        if (syncPreferences.getInt(GENERATED_EXAMPLES_VERSION_KEY, 0) < GENERATED_EXAMPLES_VERSION) {
            context.assets.open("wordpacks/generated-examples/examples.csv").use { input ->
                RoomExamplePackImporter(database).importBundledExamples(input)
            }
            syncPreferences.edit().putInt(GENERATED_EXAMPLES_VERSION_KEY, GENERATED_EXAMPLES_VERSION).apply()
        }
    }

    /** Reapplies bundled source metadata after a remote snapshot has replaced local seed data. */
    private suspend fun reconcileBundledSourcesAfterRemoteSnapshot() {
        val cet6Preview = context.assets.open("wordpacks/cet6/words.csv").use { input ->
            DefaultWordImportParser().parse(input, ImportFormat.CSV)
        }
        RoomImportRepository(database).reconcileCet6SourceMetadata(cet6Preview)
        RoomImportRepository(database).reconcileBuiltinWordMetadata(cet6Preview)
        BUILTIN_ASSET_PACKS.forEach { seedBuiltinAssetPack(it, needsMetadataUpgrade = true) }
        context.assets.open("wordpacks/cet6/examples.csv").use { input ->
            RoomExamplePackImporter(database).importCet6Examples(input)
        }
        context.assets.open("wordpacks/generated-examples/examples.csv").use { input ->
            RoomExamplePackImporter(database).importBundledExamples(input)
        }
    }

    private suspend fun seedBuiltinAssetPack(pack: BuiltinAssetPack, needsMetadataUpgrade: Boolean) {
        val existing = database.deckDao().getBySourceFileName(pack.sourceFileName)
        if (existing?.sourceType == com.pengshi.words.database.DeckSourceTypeEntity.BUILTIN &&
            existing.wordCount == pack.wordCount
        ) {
            if (existing.name != pack.deckName) {
                database.deckDao().update(existing.copy(name = pack.deckName, updatedAt = Instant.now()))
            }
            if (needsMetadataUpgrade) {
                val preview = context.assets.open(pack.resourcePath).use { input ->
                    DefaultWordImportParser().parse(input, ImportFormat.CSV)
                }
                require(preview.errors.isEmpty()) { "Bundled ${pack.packId} seed contains invalid rows" }
                RoomImportRepository(database).reconcileBuiltinWordMetadata(preview)
            }
            return
        }
        val parsed = DefaultWordImportParser().parse(
            input = context.assets.open(pack.resourcePath),
            format = ImportFormat.CSV,
        )
        require(parsed.errors.isEmpty()) {
            "Bundled ${pack.packId} seed contains invalid rows: ${parsed.errors.joinToString { it.message }}"
        }
        require(parsed.rows.size == pack.wordCount) {
            "Bundled ${pack.packId} expected ${pack.wordCount} rows but found ${parsed.rows.size}"
        }
        val result = RoomImportRepository(database).importBuiltinDeck(
            preview = parsed,
            deckName = pack.deckName,
            sourceFileName = pack.sourceFileName,
        )
        require(result.errors.isEmpty()) {
            "Bundled ${pack.packId} import failed: ${result.errors.joinToString { it.message }}"
        }
    }

    suspend fun addPersonalWord(input: PersonalWordInput): String {
        val result = RoomPersonalWordRepository(database).addWord(input)
        writeAutomaticBackup()
        return if (result.createdNewWord) {
            "已加入个人词库；纳入自动选词后会参与每日 30 个单词。"
        } else if (result.addedDefinition) {
            "同词的新释义已追加，并保存了本次来源。"
        } else {
            "这个单词已存在，个人词库信息已更新或释义已去重。"
        }
    }

    suspend fun createUserDeck(name: String): Long =
        RoomUserDeckRepository(database).createDeck(UserDeckInput(name)).also { writeAutomaticBackup() }

    suspend fun deleteUserDeck(deckId: Long): String {
        val deleted = RoomUserDeckRepository(database).deleteDeck(deckId)
        if (deleted) writeAutomaticBackup()
        return if (deleted) "词库已删除，学习记录仍保留。" else "词库已不存在。"
    }

    suspend fun addWordToDeck(deckId: Long, input: PersonalWordInput): String {
        val result = RoomUserDeckRepository(database).addWord(deckId, input)
        writeAutomaticBackup()
        return if (result.createdNewWord) {
            "单词已加入词库。"
        } else if (result.addedDefinition) {
            "同词的新释义已追加，并保存了本次来源。"
        } else {
            "这个单词已存在，已关联到当前词库；相同释义不会重复添加。"
        }
    }

    suspend fun updateWordInDeck(deckId: Long, wordId: Long, input: PersonalWordInput): String {
        RoomUserDeckRepository(database).updateWord(deckId, wordId, input)
        writeAutomaticBackup()
        return "词条已更新。"
    }

    suspend fun deleteWordFromDeck(deckId: Long, wordId: Long): String {
        val deleted = RoomUserDeckRepository(database).deleteWord(deckId, wordId)
        writeAutomaticBackup()
        return if (deleted) "词条已从当前词库移除。" else "词条已经不在当前词库中。"
    }

    suspend fun bulkAddWordsToDeck(deckId: Long, text: String, sources: BatchImportSources): String = withContext(Dispatchers.IO) {
        require(text.isNotBlank()) { "请先粘贴词库内容" }
        val preview = DefaultWordImportParser().parseForUserDeck(text.byteInputStream(), ImportFormat.TXT).withBatchSources(sources)
        formatBulkImportResult(RoomUserDeckRepository(database).bulkUpsert(deckId, preview)).also { writeAutomaticBackup() }
    }

    suspend fun importWordsToDeck(deckId: Long, uri: Uri, sources: BatchImportSources): String = withContext(Dispatchers.IO) {
        val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        val format = when {
            name?.lowercase(Locale.ROOT)?.endsWith(".xlsx") == true -> ImportFormat.XLSX
            name?.lowercase(Locale.ROOT)?.endsWith(".csv") == true -> ImportFormat.CSV
            else -> ImportFormat.TXT
        }
        val input = requireNotNull(context.contentResolver.openInputStream(uri)) { "无法读取文件" }
        input.use {
            val preview = DefaultWordImportParser().parseForUserDeck(it, format).withBatchSources(sources)
            formatBulkImportResult(RoomUserDeckRepository(database).bulkUpsert(deckId, preview)).also { writeAutomaticBackup() }
        }
    }

    private fun formatBulkImportResult(result: com.pengshi.words.model.UserDeckBulkResult): String {
        val processed = result.importedRows + result.updatedRows
        val detail = buildList {
            add("新增/关联 ${result.importedRows}")
            add("更新 ${result.updatedRows}")
            if (result.linkedRows > 0) add("复用总词典 ${result.linkedRows}")
            if (result.skippedRows > 0) add("跳过 ${result.skippedRows}")
            if (result.protectedBuiltinRows > 0) add("内置词条 ${result.protectedBuiltinRows} 条：保留官方释义和例句，仅补空助记")
            if (result.addedDefinitions > 0) add("新增不同释义 ${result.addedDefinitions} 条")
        }.joinToString("，")
        val firstError = result.errors.firstOrNull()?.let { "；第${it.rowNumber}行：${it.message}" }.orEmpty()
        return "批量处理完成：共处理 $processed 行，$detail$firstError"
    }

    suspend fun addManualExample(wordId: Long, sentenceEn: String, sentenceCn: String?, source: String): String {
        val added = RoomManualExampleRepository(database).addExample(wordId, sentenceEn, sentenceCn, source)
        writeAutomaticBackup()
        return if (added) "例句和翻译已保存。" else "这条英文例句已经存在。"
    }

    suspend fun updateManualExample(exampleId: Long, sentenceEn: String, sentenceCn: String?, source: String): String {
        val updated = RoomManualExampleRepository(database).updateExample(exampleId, sentenceEn, sentenceCn, source)
        writeAutomaticBackup()
        return if (updated) "例句和翻译已更新。" else "这条例句重复或已经不存在。"
    }

    suspend fun deleteManualExample(exampleId: Long): String {
        val deleted = RoomManualExampleRepository(database).deleteExample(exampleId)
        writeAutomaticBackup()
        return if (deleted) "例句已删除。" else "这条例句已经不存在。"
    }

    suspend fun refreshExamples(session: StudySessionState): StudySessionState {
        val word = session.currentWord ?: return session
        if (!session.currentExamples.needsOnlineExamples()) return session
        val fetched = exampleService.fetch(word.spelling)
        if (fetched.isEmpty()) return session
        val existingExamples = database.exampleSentenceDao().getForWord(word.id)
        val placeholderIds = existingExamples
            .filter { it.sentenceEn.isGenericExamplePlaceholder() }
            .map { it.id }
        val existingSentences = existingExamples
            .filterNot { it.id in placeholderIds }
            .mapTo(mutableSetOf()) { it.sentenceEn }
        val newExamples = fetched
            .distinctBy { it.sentenceEn }
            .filterNot { it.sentenceEn in existingSentences }
        database.withTransaction {
            placeholderIds.forEach { id -> database.exampleSentenceDao().deleteById(id) }
            if (newExamples.isNotEmpty()) {
                database.exampleSentenceDao().shiftSortOrderForWord(word.id, newExamples.size)
                database.exampleSentenceDao().insertAll(newExamples.mapIndexed { index, example ->
                    ExampleSentenceEntity(
                        wordId = word.id,
                        sentenceEn = example.sentenceEn,
                        sentenceCn = example.sentenceCn,
                        sortOrder = index,
                        source = example.source,
                    )
                })
            }
        }
        val examples = database.exampleSentenceDao().getForWord(word.id).map { example ->
            com.pengshi.words.model.ExampleSentence(
                id = example.id,
                wordId = example.wordId,
                sentenceEn = example.sentenceEn,
                sentenceCn = example.sentenceCn,
                sortOrder = example.sortOrder,
                source = example.source,
            )
        }
        return session.copy(currentExamples = examples)
    }

    suspend fun homeState(): HomeUiState {
        val today = LocalDate.now()
        val now = Instant.now()
        val counts = database.wordDao().getDashboardPoolCounts(
            includeAllDecks = true,
            includedDeckIds = listOf(-1L),
            restrictToWordIds = 0,
            wordIds = listOf(-1L),
            nowEpochMillis = now.toEpochMilli(),
            mode = StudyMode.EN_TO_CN,
        )
        val plan = repository.getPlan(today)
        val items = plan?.let { repository.getItems(it.id) }.orEmpty()
        val reviewItems = items.filter { it.source == PlanSource.DUE_REVIEW }
        val newItems = items.filter { it.source.isNewWord }
        val extraItems = items.filter { it.source == PlanSource.EXTRA }
        val reviewCompleted = reviewItems.count { it.status == DailyItemStatus.COMPLETED }
        val newCompleted = newItems.count { it.status == DailyItemStatus.COMPLETED }
        val phase = when {
            plan == null -> HomeStudyPhase.NOT_STARTED
            reviewCompleted < reviewItems.size -> HomeStudyPhase.REVIEW
            newCompleted < newItems.size -> HomeStudyPhase.NEW_WORDS
            items.count { it.source != PlanSource.EXTRA } < plan.quota && counts.newCount > 0 -> HomeStudyPhase.CHOOSE_NEW
            else -> HomeStudyPhase.COMPLETE
        }
        val state = HomeUiState(
            completedUniqueWordCount = plan?.completedUniqueWordCount ?: 0,
            quota = plan?.quota ?: 30,
            dueCount = counts.dueCount,
            newCount = counts.newCount,
            reviewPlanned = reviewItems.size,
            reviewCompleted = reviewCompleted,
            newPlanned = newItems.size,
            newCompleted = newCompleted,
            extraPlanned = extraItems.size,
            extraCompleted = extraItems.count { it.status == DailyItemStatus.COMPLETED },
            phase = phase,
            isTodayComplete = phase == HomeStudyPhase.COMPLETE,
            checkInDates = database.dailyPlanDao().getCompletedPlanDates().toSet(),
        )
        saveCachedHomeState(state, today)
        return state
    }

    fun cachedHomeState(): HomeUiState? = preferences.getString(HOME_STATE_CACHE_KEY, null)
        ?.let(::decodeCachedHomeState)

    fun cachedStartupState(): AppStartupState = cachedHomeState()?.let { cached ->
        AppStartupState.Ready(InitialAppData(cached, savedSettings(), null))
    } ?: AppStartupState.Loading

    private fun decodeCachedHomeState(raw: String): HomeUiState? = runCatching {
        val root = JSONObject(raw)
        if (root.optInt("version", -1) != HOME_STATE_CACHE_VERSION ||
            root.optString("date") != LocalDate.now().toString()
        ) return null
        val checkInDates = root.optJSONArray("checkInDates") ?: JSONArray()
        HomeUiState(
            completedUniqueWordCount = root.optInt("completedUniqueWordCount"),
            quota = root.optInt("quota", 30),
            dueCount = root.optInt("dueCount"),
            newCount = root.optInt("newCount"),
            reviewPlanned = root.optInt("reviewPlanned"),
            reviewCompleted = root.optInt("reviewCompleted"),
            newPlanned = root.optInt("newPlanned"),
            newCompleted = root.optInt("newCompleted"),
            extraPlanned = root.optInt("extraPlanned"),
            extraCompleted = root.optInt("extraCompleted"),
            phase = runCatching { HomeStudyPhase.valueOf(root.optString("phase")) }
                .getOrDefault(HomeStudyPhase.NOT_STARTED),
            isTodayComplete = root.optBoolean("isTodayComplete"),
            checkInDates = buildSet {
                for (index in 0 until checkInDates.length()) {
                    checkInDates.optString(index).let { value ->
                        runCatching { LocalDate.parse(value) }.getOrNull()?.let(::add)
                    }
                }
            },
        )
    }.getOrNull()

    private fun saveCachedHomeState(state: HomeUiState, date: LocalDate) {
        val root = JSONObject()
            .put("version", HOME_STATE_CACHE_VERSION)
            .put("date", date.toString())
            .put("completedUniqueWordCount", state.completedUniqueWordCount)
            .put("quota", state.quota)
            .put("dueCount", state.dueCount)
            .put("newCount", state.newCount)
            .put("reviewPlanned", state.reviewPlanned)
            .put("reviewCompleted", state.reviewCompleted)
            .put("newPlanned", state.newPlanned)
            .put("newCompleted", state.newCompleted)
            .put("extraPlanned", state.extraPlanned)
            .put("extraCompleted", state.extraCompleted)
            .put("phase", state.phase.name)
            .put("isTodayComplete", state.isTodayComplete)
            .put("checkInDates", JSONArray().apply {
                state.checkInDates.sorted().forEach { put(it.toString()) }
            })
        preferences.edit().putString(HOME_STATE_CACHE_KEY, root.toString()).apply()
    }

    private val preferences = context.getSharedPreferences("pengshi-settings", Context.MODE_PRIVATE)

    fun wordPoolSelection(): WordPoolSelection = WordPoolSelection(
        includedDeckIds = preferences.getStringSet(WORD_POOL_DECK_IDS, emptySet())
            .orEmpty()
            .mapNotNull(String::toLongOrNull)
            .toSet(),
        deckWeights = preferences.getStringSet(WORD_POOL_WEIGHTS, emptySet())
            .orEmpty()
            .mapNotNull { value ->
                val parts = value.split(':', limit = 2)
                if (parts.size != 2) null else {
                    val id = parts[0].toLongOrNull()
                    val weight = parts[1].toIntOrNull()
                    if (id == null || weight == null) null else id to weight
                }
            }
            .toMap(),
        excludedWordIds = preferences.getStringSet(WORD_POOL_EXCLUDED_WORD_IDS, emptySet())
            .orEmpty()
            .mapNotNull(String::toLongOrNull)
            .toSet(),
    )

    suspend fun toggleDeckInWordPool(deckId: Long) {
        val selection = wordPoolSelection()
        val allDeckIds = database.deckDao().getAll().map { it.id }.toSet()
        val nextIncluded = when {
            selection.includedDeckIds.isEmpty() -> allDeckIds - deckId
            deckId in selection.includedDeckIds -> selection.includedDeckIds - deckId
            else -> selection.includedDeckIds + deckId
        }
        val normalized = if (nextIncluded == allDeckIds) emptySet() else nextIncluded
        val nextSelection = selection.copy(includedDeckIds = normalized).let { next ->
            next.copy(deckWeights = next.resolve(allDeckIds).deckWeights)
        }
        preferences.edit()
            .putStringSet(WORD_POOL_DECK_IDS, normalized.map(Long::toString).toSet())
            .putStringSet(WORD_POOL_WEIGHTS, nextSelection.deckWeights.entries.map { "${it.key}:${it.value}" }.toSet())
            .apply()
    }

    suspend fun saveWordPoolSelection(selection: WordPoolSelection) {
        val allDeckIds = database.deckDao().getAll().mapTo(linkedSetOf()) { it.id }
        val resolved = selection.resolve(allDeckIds)
        val includedDeckIds = if (resolved.includedDeckIds == allDeckIds) emptySet() else resolved.includedDeckIds
        preferences.edit()
            .putStringSet(WORD_POOL_DECK_IDS, includedDeckIds.map(Long::toString).toSet())
            .putStringSet(WORD_POOL_WEIGHTS, resolved.deckWeights.entries.map { "${it.key}:${it.value}" }.toSet())
            .putStringSet(WORD_POOL_EXCLUDED_WORD_IDS, selection.excludedWordIds.map(Long::toString).toSet())
            .apply()
    }

    fun toggleExcludedWord(wordId: Long): WordPoolSelection {
        val current = wordPoolSelection().excludedWordIds.toMutableSet()
        if (!current.add(wordId)) current.remove(wordId)
        val next = wordPoolSelection().copy(excludedWordIds = current)
        preferences.edit()
            .putStringSet(WORD_POOL_EXCLUDED_WORD_IDS, current.map(Long::toString).toSet())
            .apply()
        return next
    }

    fun clearWordPoolDecks() {
        preferences.edit().remove(WORD_POOL_DECK_IDS).remove(WORD_POOL_WEIGHTS).apply()
    }

    fun cachedSelectionDashboardState(): SelectionDashboardUiState {
        val selection = wordPoolSelection()
        val raw = preferences.getString(SELECTION_DASHBOARD_CACHE_KEY, null) ?: return SelectionDashboardUiState(
            wordPoolSelection = selection,
            deckWeights = selection.deckWeights,
        )
        return runCatching {
            val root = JSONObject(raw)
            val decks = root.optJSONArray("decks").toUiDeckSummaries()
            val excluded = root.optJSONArray("excluded").toUiWords()
            SelectionDashboardUiState(
                wordPoolSelection = selection,
                decks = decks,
                candidateCount = root.optInt("candidateCount", 0),
                dueCount = root.optInt("dueCount", 0),
                newCount = root.optInt("newCount", 0),
                excludedWords = excluded,
                deckWeights = root.optJSONObject("deckWeights").toWeights().ifEmpty { selection.deckWeights },
            )
        }.getOrElse {
            SelectionDashboardUiState(wordPoolSelection = selection, deckWeights = selection.deckWeights)
        }
    }

    fun saveSelectionDashboardCache(state: SelectionDashboardUiState) {
        val root = JSONObject()
            .put("candidateCount", state.candidateCount)
            .put("dueCount", state.dueCount)
            .put("newCount", state.newCount)
            .put("deckWeights", JSONObject().apply {
                state.deckWeights.forEach { (id, weight) -> put(id.toString(), weight) }
            })
            .put("decks", JSONArray().apply {
                state.decks.forEach { deck ->
                    put(JSONObject()
                        .put("id", deck.id)
                        .put("name", deck.name)
                        .put("wordCount", deck.wordCount)
                        .put("dueCount", deck.dueCount)
                        .put("newCount", deck.newCount)
                        .put("sourceType", deck.sourceType.name)
                    )
                }
            })
            .put("excluded", JSONArray().apply {
                state.excludedWords.forEach { word ->
                    put(JSONObject()
                        .put("id", word.id)
                        .put("spelling", word.spelling)
                        .put("phonetic", word.phonetic)
                        .put("partOfSpeech", word.partOfSpeech)
                        .put("definitionCn", word.definitionCn)
                        .put("deckId", word.deckId)
                        .put("deckName", word.deckName)
                    )
                }
            })
        preferences.edit().putString(SELECTION_DASHBOARD_CACHE_KEY, root.toString()).apply()
    }

    suspend fun setDeckWeightInWordPool(deckId: Long, percent: Int) {
        val allDeckIds = database.deckDao().getAll().map { it.id }.toSet()
        val current = wordPoolSelection()
        val selected = if (current.includedDeckIds.isEmpty()) allDeckIds else current.includedDeckIds intersect allDeckIds
        if (deckId !in selected) return
        val target = percent.coerceIn(1, 100)
        val others = selected - deckId
        val currentOthers = current.deckWeights.filterKeys { it in others }
        val baseTotal = currentOthers.values.sum().takeIf { it > 0 } ?: others.size.coerceAtLeast(1)
        val remaining = 100 - target
        val weights = others.associateWith { other -> (remaining.toLong() * (currentOthers[other] ?: 1) / baseTotal).toInt() }.toMutableMap()
        var remainder = remaining - weights.values.sum()
        others.sorted().forEach { other ->
            if (remainder > 0) {
                weights[other] = weights.getValue(other) + 1
                remainder--
            }
        }
        weights[deckId] = if (others.isEmpty()) 100 else target
        preferences.edit().putStringSet(WORD_POOL_WEIGHTS, weights.entries.map { "${it.key}:${it.value}" }.toSet()).apply()
    }

    suspend fun selectionDashboardState(): SelectionDashboardUiState {
        val selection = wordPoolSelection()
        val now = Instant.now()
        val deletedIds = database.deckDao().getAll().filter {
            it.sourceFileName == com.pengshi.words.model.DELETED_USER_DECK_SOURCE
        }.mapTo(hashSetOf()) { it.id }
        val deckRows = database.deckDao().getDashboardCounts(now.toEpochMilli(), StudyMode.EN_TO_CN)
            .filterNot { it.id in deletedIds }
        val allDeckIds = deckRows.mapTo(linkedSetOf()) { it.id }
        val includeAllDecks = selection.includedDeckIds.isEmpty()
        val includedDeckIds = if (includeAllDecks) allDeckIds else selection.includedDeckIds intersect allDeckIds
        val dashboardWordDao = database.wordDao()
        val deckFilter = includedDeckIds.toList().ifEmpty { listOf(-1L) }
        suspend fun poolCounts(wordIds: List<Long>? = null): com.pengshi.words.database.WordPoolDashboardCounts =
            dashboardWordDao.getDashboardPoolCounts(
                includeAllDecks = includeAllDecks,
                includedDeckIds = deckFilter,
                restrictToWordIds = if (wordIds == null) 0 else 1,
                wordIds = wordIds?.takeIf { it.isNotEmpty() } ?: listOf(-1L),
                nowEpochMillis = now.toEpochMilli(),
                mode = StudyMode.EN_TO_CN,
            )
        val baseCounts = poolCounts()
        var dueCount = baseCounts.dueCount
        var newCount = baseCounts.newCount
        selection.excludedWordIds.toList().chunked(DASHBOARD_QUERY_CHUNK_SIZE).forEach { excludedChunk ->
            val excludedCounts = poolCounts(excludedChunk)
            dueCount -= excludedCounts.dueCount
            newCount -= excludedCounts.newCount
        }
        dueCount = dueCount.coerceAtLeast(0)
        newCount = newCount.coerceAtLeast(0)
        val deckSummaries = deckRows.map { row ->
            val source = DeckSourceType.valueOf(row.sourceType)
            DeckSummary(
                name = row.name,
                wordCount = row.wordCount,
                dueCount = row.dueCount,
                id = row.id,
                newCount = row.newCount,
                totalWordCount = row.totalWordCount,
                sourceType = source,
                isEditable = source != DeckSourceType.BUILTIN,
            )
        }
        val excludedRows = mutableListOf<com.pengshi.words.database.ExcludedWordRow>()
        for (chunk in selection.excludedWordIds.toList().chunked(DASHBOARD_QUERY_CHUNK_SIZE)) {
            excludedRows += dashboardWordDao.getExcludedWordRows(chunk)
        }
        val excludedWords = excludedRows.map { word ->
            WordListItemUi(
                id = word.id,
                spelling = word.spelling,
                phonetic = word.phonetic,
                partOfSpeech = word.partOfSpeech,
                definitionCn = word.definitionCn.normalizeDictionaryText(),
                deckId = word.deckId,
                deckName = word.deckName,
            )
        }
        val state = SelectionDashboardUiState(
            wordPoolSelection = selection,
            decks = deckSummaries,
            candidateCount = dueCount + newCount,
            dueCount = dueCount,
            newCount = newCount,
            excludedWords = excludedWords,
            deckWeights = selection.resolve(allDeckIds).deckWeights,
        )
        saveSelectionDashboardCache(state)
        return state
    }

    fun savedSettings(): SettingsUiState = SettingsUiState(
        dailyQuota = preferences.getInt(DAILY_QUOTA_KEY, DailyQuota.DEFAULT).let(DailyQuota::normalize),
        defaultMode = preferences.getString(DEFAULT_MODE_KEY, StudyMode.EN_TO_CN.name)
            ?.let { runCatching { StudyMode.valueOf(it) }.getOrDefault(StudyMode.EN_TO_CN) }
            ?: StudyMode.EN_TO_CN,
        autoPlayWord = preferences.getBoolean(AUTO_PLAY_WORD_KEY, true),
        autoPlaySentence = preferences.getBoolean(AUTO_PLAY_SENTENCE_KEY, true),
        speechRate = savedSpeechRate,
        selectedVoiceKey = savedSpeechVoiceKey,
        githubOwner = syncPreferences.getString(SYNC_OWNER_KEY, "").orEmpty(),
        githubRepository = syncPreferences.getString(SYNC_REPOSITORY_KEY, "").orEmpty(),
        githubBranch = syncPreferences.getString(SYNC_BRANCH_KEY, "main") ?: "main",
        hasGithubPassword = syncTokenStore.hasSecret(SYNC_PASSWORD_KEY),
        hasGithubToken = syncTokenStore.hasToken(),
    )

    fun saveSettings(settings: SettingsUiState) {
        preferences.edit()
            .putString(DEFAULT_MODE_KEY, settings.defaultMode.name)
            .putBoolean(AUTO_PLAY_WORD_KEY, settings.autoPlayWord)
            .putBoolean(AUTO_PLAY_SENTENCE_KEY, settings.autoPlaySentence)
            .putFloat(SPEECH_RATE_KEY, SpeechSettings.clampRate(settings.speechRate))
            .commit()
    }

    suspend fun updateDailyQuota(quota: Int, localDate: LocalDate = LocalDate.now()) = runStudyOperation {
        DailyQuota.requireValid(quota)
        val previous = preferences.getInt(DAILY_QUOTA_KEY, DailyQuota.DEFAULT).let(DailyQuota::normalize)
        if (previous == quota) return@runStudyOperation
        check(preferences.edit().putInt(DAILY_QUOTA_KEY, quota).commit()) { "每日额度保存失败" }
        try {
            adjustDailyQuota.adjust(localDate, quota, Instant.now(), savedDefaultMode())
        } catch (failure: Throwable) {
            preferences.edit().putInt(DAILY_QUOTA_KEY, previous).commit()
            throw failure
        }
        runCatching { writeAutomaticBackup() }
    }

    suspend fun applySavedDailyQuotaToToday(localDate: LocalDate = LocalDate.now()) = runStudyOperation {
        val savedQuota = preferences.getInt(DAILY_QUOTA_KEY, DailyQuota.DEFAULT).let(DailyQuota::normalize)
        adjustDailyQuota.adjust(localDate, savedQuota, Instant.now(), savedDefaultMode())
    }

    private fun savedDefaultMode(): StudyMode = preferences.getString(DEFAULT_MODE_KEY, StudyMode.EN_TO_CN.name)
        ?.let { runCatching { StudyMode.valueOf(it) }.getOrDefault(StudyMode.EN_TO_CN) }
        ?: StudyMode.EN_TO_CN

    fun hasSyncConfiguration(): Boolean =
        !syncPreferences.getString(SYNC_OWNER_KEY, "").isNullOrBlank() ||
            !syncPreferences.getString(SYNC_REPOSITORY_KEY, "").isNullOrBlank() ||
            syncTokenStore.hasSecret(SYNC_PASSWORD_KEY) || syncTokenStore.hasToken()

    suspend fun currentEligibleWordIds(): Set<Long> {
        return currentAutomaticWordPool().eligibleWordIds
    }

    private suspend fun currentAutomaticWordPool(): AutomaticWordPool {
        val selection = wordPoolSelection()
        val allDecks = database.deckDao().getAll()
        val allDeckIds = allDecks.mapTo(linkedSetOf()) { it.id }
        val resolved = selection.resolve(allDeckIds)
        val selectedDeckIds = resolved.includedDeckIds.toList()
        val selectedDeckWords = if (selectedDeckIds.isEmpty()) emptyList()
        else database.deckDao().getDeckWordsForDecks(selectedDeckIds)
        val examDeckId = allDecks.firstOrNull {
            it.sourceType == com.pengshi.words.database.DeckSourceTypeEntity.BUILTIN &&
                it.sourceFileName == com.pengshi.words.model.ElementaryEnglishWords.KAOYAN_SOURCE_FILE
        }?.id
        val elementaryIds = if (examDeckId != null && examDeckId in selectedDeckIds) database.wordDao().getAll()
            .filter { com.pengshi.words.model.ElementaryEnglishWords.contains(it.spelling) }
            .mapTo(hashSetOf()) { it.id } else emptySet()
        val deckIdsByWordId = selectedDeckWords
            .filterNot { it.deckId == examDeckId && it.wordId in elementaryIds }
            .groupBy { it.wordId }
            .mapValues { (_, links) -> links.mapTo(linkedSetOf()) { it.deckId } }
        return AutomaticWordPool(
            deckWeights = resolved.deckWeights,
            deckIdsByWordId = deckIdsByWordId,
            excludedWordIds = resolved.excludedWordIds,
            restrictToEligibleWordIds = true,
        )
    }

    val savedSpeechRate: Float
        get() = SpeechSettings.clampRate(preferences.getFloat(SPEECH_RATE_KEY, 1.0f))
    val savedSpeechVoiceKey: String?
        get() = preferences.getString(SPEECH_VOICE_KEY, null)

    fun saveSpeechRate(rate: Float) {
        preferences.edit().putFloat(SPEECH_RATE_KEY, SpeechSettings.clampRate(rate)).commit()
    }

    fun saveSpeechVoice(key: String?) {
        preferences.edit().putString(SPEECH_VOICE_KEY, key).commit()
    }

    init {
        if (!preferences.contains(STATS_START_DATE_KEY)) {
            preferences.edit().putString(STATS_START_DATE_KEY, LocalDate.now().toString()).commit()
        }
    }

    private fun statsStartDate(): LocalDate = preferences.getString(STATS_START_DATE_KEY, LocalDate.now().toString())
        ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        ?: LocalDate.now()

    private fun saveStatsStartDate(date: LocalDate) {
        preferences.edit().putString(STATS_START_DATE_KEY, date.toString()).commit()
    }

    fun savedStatsQuery(): Pair<LocalDate, StatsPeriod> {
        val date = preferences.getString(STATS_ANCHOR_DATE_KEY, LocalDate.now().toString())
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?.coerceAtMost(LocalDate.now())
            ?: LocalDate.now()
        val period = preferences.getString(STATS_PERIOD_KEY, StatsPeriod.DAY.name)
            ?.let { runCatching { StatsPeriod.valueOf(it) }.getOrDefault(StatsPeriod.DAY) }
            ?: StatsPeriod.DAY
        return date to period
    }

    fun cachedStatsState(anchorDate: LocalDate, period: StatsPeriod): StatsUiState? =
        statsStateFromCache(preferences.getString(STATS_STATE_CACHE_KEY, null))
            ?.takeIf { it.anchorDate == anchorDate && it.period == period }

    fun cachedDeckSummaries(): List<DeckSummary> = deckSummariesFromCache(
        preferences.getString(DECK_SUMMARIES_CACHE_KEY, null),
    )

    fun saveDeckSummariesCache(decks: List<DeckSummary>) {
        preferences.edit().putString(DECK_SUMMARIES_CACHE_KEY, deckSummariesToCache(decks)).apply()
    }

    fun saveStatsQuery(anchorDate: LocalDate, period: StatsPeriod) {
        preferences.edit()
            .putString(STATS_ANCHOR_DATE_KEY, anchorDate.toString())
            .putString(STATS_PERIOD_KEY, period.name)
            .commit()
    }

    val savedStatsTab: StatsTab
        get() = preferences.getString(STATS_TAB_KEY, StatsTab.RETENTION.name)
            ?.let { runCatching { StatsTab.valueOf(it) }.getOrDefault(StatsTab.RETENTION) }
            ?: StatsTab.RETENTION

    fun saveStatsTab(tab: StatsTab) {
        preferences.edit().putString(STATS_TAB_KEY, tab.name).commit()
    }

    suspend fun statsState(anchorDate: LocalDate, period: StatsPeriod): StatsUiState {
        val startDate = statsStartDate()
        val effectiveAnchor = anchorDate.coerceAtLeast(startDate)
        val (itemsStart, itemsEnd) = statsPlanItemDateRange(effectiveAnchor, startDate, period)
        val statsData = database.withTransaction {
            StatsStudyData(
                totalWordCount = database.wordDao().count(),
                cardStates = database.cardStateDao().getAll().map { entity ->
                    CardState(
                        id = entity.id,
                        wordId = entity.wordId,
                        mode = entity.studyMode,
                        status = entity.state,
                        difficulty = entity.difficulty,
                        stability = entity.stability,
                        retrievability = entity.retrievability,
                        dueAt = entity.dueAt,
                        lastReviewedAt = entity.lastReviewedAt,
                        scheduledDays = entity.scheduledDays,
                        repetitions = entity.repetitions,
                        lapses = entity.lapses,
                        learningStep = entity.learningStep,
                        createdAt = entity.createdAt,
                        updatedAt = entity.updatedAt,
                    )
                },
                dailyPlans = database.dailyPlanDao().getAllPlans().map { entity ->
                    DailyPlan(
                        id = entity.id,
                        localDate = entity.localDate,
                        quota = entity.quota,
                        plannedUniqueWordCount = entity.plannedUniqueWordCount,
                        completedUniqueWordCount = entity.completedUniqueWordCount,
                        status = DailyPlanStatus.valueOf(entity.status.name),
                        createdAt = entity.createdAt,
                        updatedAt = entity.updatedAt,
                    )
                },
                dailyPlanItems = database.dailyPlanDao().getItemsForDateRange(itemsStart, itemsEnd).map { entity ->
                    DailyPlanItem(
                        id = entity.id,
                        dailyPlanId = entity.dailyPlanId,
                        wordId = entity.wordId,
                        source = entity.sourceType,
                        selectionRank = entity.selectionRank,
                        status = DailyItemStatus.valueOf(entity.status.name),
                    )
                },
                reviewLogs = database.reviewLogDao().getStatsRows().map { it.toStatsModel() },
            )
        }
        val result = StatsCalculator.calculate(statsData, anchorDate, startDate, period)
        preferences.edit().putString(STATS_STATE_CACHE_KEY, result.toCacheJson()).apply()
        return result
    }

    private fun statsPlanItemDateRange(
        anchorDate: LocalDate,
        startDate: LocalDate,
        period: StatsPeriod,
    ): Pair<LocalDate, LocalDate> {
        val (start, end) = when (period) {
            StatsPeriod.DAY -> anchorDate.minusDays(5) to anchorDate.plusDays(5)
            StatsPeriod.WEEK -> {
                val weekStart = anchorDate.with(DayOfWeek.MONDAY)
                weekStart.minusWeeks(5) to weekStart.plusWeeks(5).plusDays(6)
            }
            StatsPeriod.MONTH -> {
                val monthStart = anchorDate.withDayOfMonth(1)
                val lastMonth = monthStart.plusMonths(5)
                monthStart.minusMonths(5) to lastMonth.withDayOfMonth(lastMonth.lengthOfMonth())
            }
        }
        return start.coerceAtLeast(startDate) to end
    }

    private fun StatsReviewLogRow.toStatsModel() = StatsReviewLog(
        id = id,
        wordId = wordId,
        mode = mode,
        reviewedAt = reviewedAt,
        feedback = feedback,
        responseTimeMs = responseTimeMs,
        nextStateSnapshot = nextStateSnapshot,
    )

    suspend fun wordList(): List<WordListItemUi> {
        val linkedRows = database.wordDao().getLibraryRows()
        val rows = linkedRows.ifEmpty { database.wordDao().getAllLibraryRows() }
        val examples = database.exampleSentenceDao().getAll()
        val senses = database.wordSenseDao().getAll().map { sense ->
            com.pengshi.words.model.WordSense(sense.id, sense.wordId, sense.partOfSpeech, sense.definitionCn,
                sense.definitionSource, sense.normalizedKey, sense.sortOrder, sense.createdAt)
        }
        return buildWordListItems(rows, examples, senses)
    }

    suspend fun manualWordCatalog(mode: StudyMode = StudyMode.EN_TO_CN): ManualWordCatalog = operationMutex.withLock {
        val plan = repository.getPlan(LocalDate.now())
        val items = plan?.let { repository.getItems(it.id) }.orEmpty()
        val candidateIds = repository.getNewCandidates(mode, Instant.now()).mapTo(hashSetOf()) { it.wordId }
        snapshotGateway.snapshot().manualWordCatalog(
            candidateIds,
            items.mapTo(hashSetOf()) { it.wordId },
            (plan?.quota ?: 30) - items.count { it.source != PlanSource.EXTRA },
        )
    }

    suspend fun wordList(deckId: Long): List<WordListItemUi> {
        val words = database.wordDao().getLibraryRowsForDeck(deckId)
        val examples = database.exampleSentenceDao().getForDeck(deckId)
        val senses = database.wordSenseDao().getAll().map { sense ->
            com.pengshi.words.model.WordSense(sense.id, sense.wordId, sense.partOfSpeech, sense.definitionCn,
                sense.definitionSource, sense.normalizedKey, sense.sortOrder, sense.createdAt)
        }
        return buildWordListItems(words, examples, senses)
    }

    suspend fun deckSummaries(): List<DeckSummary> {
        val deletedIds = database.deckDao().getAll().filter {
            it.sourceFileName == com.pengshi.words.model.DELETED_USER_DECK_SOURCE
        }.mapTo(hashSetOf()) { it.id }
        return database.deckDao().getDashboardCounts(Instant.now().toEpochMilli(), StudyMode.EN_TO_CN)
            .filterNot { it.id in deletedIds }.map { row ->
            val sourceType = DeckSourceType.valueOf(row.sourceType)
            DeckSummary(
                name = row.name,
                wordCount = row.wordCount,
                dueCount = row.dueCount,
                newCount = row.newCount,
                id = row.id,
                totalWordCount = row.totalWordCount,
                sourceType = sourceType,
                isEditable = sourceType != DeckSourceType.BUILTIN,
            )
        }
    }

    fun isFavorite(wordId: Long): Boolean = wordId.toString() in preferences.getStringSet(FAVORITE_WORD_IDS, emptySet()).orEmpty()

    fun toggleFavorite(wordId: Long): Boolean {
        val current = preferences.getStringSet(FAVORITE_WORD_IDS, emptySet()).orEmpty().toMutableSet()
        val key = wordId.toString()
        if (!current.add(key)) current.remove(key)
        preferences.edit().putStringSet(FAVORITE_WORD_IDS, current).commit()
        return key in current
    }

    suspend fun importWordList(uri: Uri): String = withContext(Dispatchers.IO) {
        val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        val format = when {
            name?.lowercase()?.endsWith(".xlsx") == true -> ImportFormat.XLSX
            name?.lowercase()?.endsWith(".txt") == true -> ImportFormat.TXT
            else -> ImportFormat.CSV
        }
        val input = requireNotNull(context.contentResolver.openInputStream(uri)) { "无法读取文件" }
        input.use {
            val preview = DefaultWordImportParser().parse(it, format)
            val result = RoomImportRepository(database).importDeck(preview)
            "已导入 ${result.importedRows} 个词条，词库页现在可以查看。"
        }
    }

    suspend fun exportBackup(uri: Uri): String = withContext(Dispatchers.IO) {
        val output = requireNotNull(context.contentResolver.openOutputStream(uri)) { "无法写入文件" }
        output.use {
            JsonBackupWriter(
                snapshotGateway,
                statsStartDateProvider = { statsStartDate() },
                settingsProvider = { backupSettings() },
            ).write(it)
        }
        "学习备份已导出，可用于换机或恢复学习进度。"
    }

    suspend fun writeAutomaticBackup() {
        automaticBackupStore.write { output ->
            JsonBackupWriter(
                snapshotGateway,
                statsStartDateProvider = { statsStartDate() },
                settingsProvider = { backupSettings() },
            ).write(output)
        }
    }

    suspend fun restoreBackup(uri: Uri): String = withContext(Dispatchers.IO) {
        val input = requireNotNull(context.contentResolver.openInputStream(uri)) { "无法读取备份" }
        input.use {
            val preview = JsonBackupReader().read(it)
            val errors = DefaultBackupValidator().validate(preview)
            require(errors.isEmpty()) { errors.joinToString { error -> error.message } }
            snapshotGateway.restore(preview.snapshot)
            preview.statsStartDate?.let(::saveStatsStartDate)
            preview.settings?.let { restoreBackupSettings(it) }
            writeAutomaticBackup()
        }
        "学习备份已恢复；词库导入请使用上面的“导入本地词库”。"
    }

    private fun backupSettings(): BackupSettingsSnapshot {
        val statsQuery = savedStatsQuery()
        return BackupSettingsSnapshot(
            dailyQuota = preferences.getInt(DAILY_QUOTA_KEY, DailyQuota.DEFAULT).let(DailyQuota::normalize),
            defaultMode = preferences.getString(DEFAULT_MODE_KEY, StudyMode.EN_TO_CN.name) ?: StudyMode.EN_TO_CN.name,
            autoPlayWord = preferences.getBoolean(AUTO_PLAY_WORD_KEY, true),
            autoPlaySentence = preferences.getBoolean(AUTO_PLAY_SENTENCE_KEY, true),
            speechRate = savedSpeechRate,
            selectedVoiceKey = savedSpeechVoiceKey,
            includedDeckIds = wordPoolSelection().includedDeckIds,
            deckWeights = wordPoolSelection().deckWeights,
            excludedWordIds = wordPoolSelection().excludedWordIds,
            favoriteWordIds = preferences.getStringSet(FAVORITE_WORD_IDS, emptySet())
                .orEmpty().mapNotNull(String::toLongOrNull).toSet(),
            statsAnchorDate = statsQuery.first,
            statsPeriod = statsQuery.second.name,
            statsTab = savedStatsTab.name,
        )
    }

    private fun restoreBackupSettings(settings: BackupSettingsSnapshot, includeDeviceVoice: Boolean = true) {
        val defaultMode = runCatching { StudyMode.valueOf(settings.defaultMode) }.getOrDefault(StudyMode.EN_TO_CN)
        val period = runCatching { StatsPeriod.valueOf(settings.statsPeriod.orEmpty()) }.getOrDefault(StatsPeriod.DAY)
        val tab = runCatching { StatsTab.valueOf(settings.statsTab.orEmpty()) }.getOrDefault(StatsTab.RETENTION)
        preferences.edit()
            .putInt(DAILY_QUOTA_KEY, DailyQuota.normalize(settings.dailyQuota))
            .putString(DEFAULT_MODE_KEY, defaultMode.name)
            .putBoolean(AUTO_PLAY_WORD_KEY, settings.autoPlayWord)
            .putBoolean(AUTO_PLAY_SENTENCE_KEY, settings.autoPlaySentence)
            .putFloat(SPEECH_RATE_KEY, SpeechSettings.clampRate(settings.speechRate))
            .putStringSet(WORD_POOL_DECK_IDS, settings.includedDeckIds.map(Long::toString).toSet())
            .putStringSet(WORD_POOL_WEIGHTS, settings.deckWeights.entries.map { "${it.key}:${it.value}" }.toSet())
            .putStringSet(WORD_POOL_EXCLUDED_WORD_IDS, settings.excludedWordIds.map(Long::toString).toSet())
            .putStringSet(FAVORITE_WORD_IDS, settings.favoriteWordIds.map(Long::toString).toSet())
            .putString(STATS_ANCHOR_DATE_KEY, (settings.statsAnchorDate ?: LocalDate.now()).toString())
            .putString(STATS_PERIOD_KEY, period.name)
            .putString(STATS_TAB_KEY, tab.name)
            .apply()
        if (includeDeviceVoice) saveSpeechVoice(settings.selectedVoiceKey)
    }

    suspend fun statsState(): StatsUiState = statsState(LocalDate.now(), StatsPeriod.DAY)

    suspend fun saveSyncConnection(
        owner: String,
        repository: String,
        branch: String,
        password: String,
        token: String,
    ) {
        val resolvedPassword = password.trim().ifBlank { syncTokenStore.readSecret(SYNC_PASSWORD_KEY).orEmpty() }
        val resolvedToken = token.trim().ifBlank { syncTokenStore.readToken().orEmpty() }
        val settings = GitHubSyncSettings(owner.trim(), repository.trim(), branch.trim(), resolvedPassword)
        require(resolvedToken.isNotBlank()) { "GitHub Token 不能为空" }
        if (token.isNotBlank()) syncTokenStore.saveToken(resolvedToken)
        if (password.isNotBlank()) syncTokenStore.saveSecret(SYNC_PASSWORD_KEY, resolvedPassword)
        syncPreferences.edit()
            .putString(SYNC_OWNER_KEY, settings.owner)
            .putString(SYNC_REPOSITORY_KEY, settings.repository)
            .putString(SYNC_BRANCH_KEY, settings.branch)
            .apply()
    }

    suspend fun syncNow(): SyncResult = operationMutex.withLock { syncNowUnlocked() }

    private suspend fun syncNowUnlocked(): SyncResult {
        RoomUserDeckRepository(database).labelUnattributedLiteratureContent()
        val owner = syncPreferences.getString(SYNC_OWNER_KEY, null)
        val repositoryName = syncPreferences.getString(SYNC_REPOSITORY_KEY, null)
        val branch = syncPreferences.getString(SYNC_BRANCH_KEY, "main") ?: "main"
        val password = syncTokenStore.readSecret(SYNC_PASSWORD_KEY)
        val token = syncTokenStore.readToken()
        if (owner.isNullOrBlank() || repositoryName.isNullOrBlank() || password.isNullOrBlank() || token.isNullOrBlank()) {
            return SyncResult(SyncStatus.AUTH_REQUIRED, 0, 0, 0)
        }
        val settings = GitHubSyncSettings(owner, repositoryName, branch, password)
        val remote = GitHubRemoteStore(
            api = GitHubApi(token),
            config = GitHubRepositoryConfig(settings.owner, settings.repository, settings.branch),
            syncPassword = settings.syncPassword,
        )
        val result = runCatching {
            syncStore.recoverReplayedLocalFeedback()
            val checkpointAction = restoreRemoteBootstrapIfNeeded(remote, settings.syncPassword)
            RoomUserDeckRepository(database).labelUnattributedLiteratureContent()
            val syncResult = SyncCoordinator(
                localStore = syncStore,
                remote = remote,
                syncPassword = settings.syncPassword,
            ).run()
            if (checkpointAction == com.pengshi.words.sync.SyncCheckpointAction.BLOCKED_BEHIND ||
                checkpointAction == com.pengshi.words.sync.SyncCheckpointAction.BLOCKED_CONFLICT
            ) return@runCatching syncResult.copy(
                status = SyncStatus.CONFLICT,
                checkpointAction = checkpointAction,
                detail = "检查点事件账本未能证明一致，已保留本地学习数据并跳过快照替换",
            )
            val uploadedCheckpoint = if (syncResult.status == SyncStatus.UP_TO_DATE) {
                uploadCurrentBootstrap(remote, settings.syncPassword)
            } else false
            syncResult.copy(
                checkpointAction = if (uploadedCheckpoint && checkpointAction == com.pengshi.words.sync.SyncCheckpointAction.NOT_CHECKED)
                    com.pengshi.words.sync.SyncCheckpointAction.CREATED else checkpointAction,
            )
        }.getOrElse { failure ->
            Log.e(
                "PengshiSync",
                "sync failed: ${failure::class.java.simpleName}: ${failure.message?.take(240) ?: "no message"}",
            )
            SyncResult(SyncStatus.FAILED, 0, 0, 0)
        }
        syncPreferences.edit()
            .putLong(SYNC_LAST_SYNC_KEY, System.currentTimeMillis())
            .putString(SYNC_LAST_RESULT_KEY, result.userMessage())
            .apply()
        return result
    }

    private suspend fun restoreRemoteBootstrapIfNeeded(remote: GitHubRemoteStore, password: String): com.pengshi.words.sync.SyncCheckpointAction {
        val bootstrap = remote.readBootstrap() ?: return com.pengshi.words.sync.SyncCheckpointAction.NOT_CHECKED
        val decodedCheckpoint = runCatching {
            PersonalSnapshotSync.decryptCheckpoint(password, bootstrap.encryptedBytes)
        }.getOrNull()
        val remotePreview = JsonBackupReader().read(
            (decodedCheckpoint?.snapshotBytes
                ?: PersonalSnapshotSync.decrypt(password, bootstrap.encryptedBytes)).inputStream(),
        )
        val localSnapshot = snapshotGateway.snapshot()
        val localSummary = localSnapshot.syncSummary()
        val remoteSnapshot = remotePreview.snapshot
        val remoteSummary = remoteSnapshot.syncSummary()
        if (decodedCheckpoint != null) {
            val localCursor = syncStore.readCursor()
            val localCheckpoint = checkpointMetadata(localSnapshot, localCursor, syncStore.checkpointEvents().size)
            when (PersonalSnapshotSync.decideCheckpoint(
                localSummary,
                remoteSummary,
                localCheckpoint,
                decodedCheckpoint.metadata,
                localEvents = syncStore.checkpointEvents(),
                remoteEvents = decodedCheckpoint.appliedEvents.takeIf { decodedCheckpoint.metadata.protocolVersion >= 2 },
            )) {
                CheckpointDecision.RESTORE_EMPTY_DEVICE -> {
                    restoreRemoteLearningSnapshot(localSnapshot, remoteSnapshot)
                    syncStore.restoreCheckpointEvents(decodedCheckpoint.appliedEvents, decodedCheckpoint.metadata.revision)
                    restoreSyncedStatsStart(remotePreview.statsStartDate, remoteSnapshot)
                    remotePreview.settings?.let { restoreBackupSettings(it, includeDeviceVoice = false) }
                    reconcileBundledSourcesAfterRemoteSnapshot()
                    return com.pengshi.words.sync.SyncCheckpointAction.RESTORED_EMPTY_DEVICE
                }
                CheckpointDecision.MERGE_CONTENT_ONLY -> {
                    snapshotGateway.mergeContent(remoteSnapshot)
                    reconcileBundledSourcesAfterRemoteSnapshot()
                    return com.pengshi.words.sync.SyncCheckpointAction.MERGED_CONTENT_ONLY
                }
                CheckpointDecision.MERGE_INCREMENTAL -> {
                    if (PersonalSnapshotSync.shouldMergeRemoteContent(localSummary, remoteSummary)) {
                        snapshotGateway.mergeContent(remoteSnapshot)
                        reconcileBundledSourcesAfterRemoteSnapshot()
                    }
                    return com.pengshi.words.sync.SyncCheckpointAction.MERGING_INCREMENTAL
                }
                CheckpointDecision.CREATE_NEW,
                CheckpointDecision.KEEP_REMOTE,
                -> return com.pengshi.words.sync.SyncCheckpointAction.KEPT_LOCAL
                CheckpointDecision.BLOCKED_BEHIND -> return com.pengshi.words.sync.SyncCheckpointAction.BLOCKED_BEHIND
                CheckpointDecision.BLOCKED_CONFLICT -> return com.pengshi.words.sync.SyncCheckpointAction.BLOCKED_CONFLICT
            }
        } else {
            if (PersonalSnapshotSync.shouldRestoreRemote(localSummary, remoteSummary)) {
                restoreRemoteLearningSnapshot(localSnapshot, remoteSnapshot)
                reconcileBundledSourcesAfterRemoteSnapshot()
                restoreSyncedStatsStart(remotePreview.statsStartDate, remoteSnapshot)
                remotePreview.settings?.let { restoreBackupSettings(it, includeDeviceVoice = false) }
                return com.pengshi.words.sync.SyncCheckpointAction.RESTORED_EMPTY_DEVICE
            }
            if (PersonalSnapshotSync.shouldMergeRemoteContent(localSummary, remoteSummary)) {
                snapshotGateway.mergeContent(remoteSnapshot)
                reconcileBundledSourcesAfterRemoteSnapshot()
                return com.pengshi.words.sync.SyncCheckpointAction.MERGED_CONTENT_ONLY
            }
            return com.pengshi.words.sync.SyncCheckpointAction.KEPT_LOCAL
        }
    }

    private suspend fun restoreRemoteLearningSnapshot(local: StudyDataSnapshot, remote: StudyDataSnapshot) {
        writeAutomaticBackup()
        try {
            snapshotGateway.replace(remote)
            // Keep words and personal decks added on this device before it had learning history.
            snapshotGateway.mergeContent(local)
        } catch (failure: Exception) {
            snapshotGateway.replace(local)
            throw failure
        }
    }

    private fun restoreSyncedStatsStart(saved: LocalDate?, remote: StudyDataSnapshot) {
        val firstFeedback = remote.reviewLogs.minOfOrNull { it.reviewedAt.atZone(java.time.ZoneId.systemDefault()).toLocalDate() }
        listOfNotNull(saved, firstFeedback).minOrNull()?.let(::saveStatsStartDate)
    }

    private suspend fun uploadCurrentBootstrap(remote: GitHubRemoteStore, password: String): Boolean {
        val localSnapshot = snapshotGateway.snapshot()
        val localCursor = syncStore.readCursor()
        val remoteManifest = remote.readManifest(null).manifest
        val remoteBootstrap = remote.readBootstrap()
        val remoteCheckpoint = remoteBootstrap?.let {
            runCatching { PersonalSnapshotSync.decryptCheckpoint(password, it.encryptedBytes) }.getOrNull()
        }
        val appliedEvents = syncStore.checkpointEvents()
        val localCheckpoint = checkpointMetadata(localSnapshot, localCursor, appliedEvents.size)
        if (!PersonalSnapshotSync.canUploadCheckpoint(
                local = localCheckpoint,
                remote = remoteCheckpoint?.metadata,
                remoteManifestRevision = remoteManifest?.revision,
                pendingApplicationCount = syncStore.pendingApplicationEvents().size,
                localEvents = appliedEvents,
                remoteEvents = remoteCheckpoint?.appliedEvents,
            )
        ) return false
        val remotePreview = remoteBootstrap?.let {
            val decoded = runCatching { PersonalSnapshotSync.decryptCheckpoint(password, it.encryptedBytes) }.getOrNull()
            JsonBackupReader().read(
                (decoded?.snapshotBytes ?: PersonalSnapshotSync.decrypt(password, it.encryptedBytes)).inputStream(),
            )
        }
        if (remotePreview != null &&
            remotePreview.snapshot == localSnapshot &&
            remotePreview.settings == backupSettings().copy(selectedVoiceKey = null) &&
            remotePreview.statsStartDate == statsStartDate()
        ) return false
        val output = ByteArrayOutputStream()
        JsonBackupWriter(
            snapshotGateway,
            statsStartDateProvider = { statsStartDate() },
            settingsProvider = { backupSettings().copy(selectedVoiceKey = null) },
        ).write(output)
        val manifest = remote.readManifest(null)
        remote.uploadBootstrap(
            encryptedSnapshot = PersonalSnapshotSync.encryptCheckpoint(
                password,
                localCheckpoint,
                output.toByteArray(),
                appliedEvents,
            ),
            baseRevision = manifest.manifest?.revision.orEmpty(),
        )
        syncPreferences.edit().putLong(SYNC_LAST_SNAPSHOT_KEY, System.currentTimeMillis()).apply()
        return true
    }

    private fun checkpointMetadata(
        snapshot: StudyDataSnapshot,
        cursor: SyncCursor,
        syncEventCount: Int,
    ): CheckpointMetadata = CheckpointMetadata(
        protocolVersion = 2,
        revision = cursor.revision.orEmpty(),
        perDeviceHighWatermarks = cursor.perDeviceHighWatermarks,
        appliedEventDigest = cursor.appliedEventDigest(),
        tableCounts = mapOf(
            "words" to snapshot.words.size,
            "examples" to snapshot.exampleSentences.size,
            "decks" to snapshot.decks.size,
            "deckWords" to snapshot.deckWords.size,
            "cardStates" to snapshot.cardStates.size,
            "dailyPlans" to snapshot.dailyPlans.size,
            "dailyPlanItems" to snapshot.dailyPlanItems.size,
            "intradayReviewEvents" to snapshot.intradayReviewEvents.size,
            "reviewLogs" to snapshot.reviewLogs.size,
            "syncEvents" to syncEventCount,
        ),
        createdAt = Instant.now(),
        deviceId = syncDeviceId,
    )

    private fun StudyDataSnapshot.syncSummary(): SnapshotSummary = SnapshotSummary(
        wordCount = words.size,
        cardStateCount = cardStates.size,
        reviewLogCount = reviewLogs.size,
        dailyPlanCount = dailyPlans.size,
        reviewedCardCount = cardStates.count { it.lastReviewedAt != null || it.repetitions > 0 },
        completedPlanCount = dailyPlans.count { it.status == DailyPlanStatus.COMPLETED },
        userDeckCount = decks.count { it.sourceType != DeckSourceType.BUILTIN },
        userDeckWordCount = deckWords.count { link -> decks.any { it.id == link.deckId && it.sourceType != DeckSourceType.BUILTIN } },
        exampleSentenceCount = exampleSentences.size,
        contentDigest = PersonalSnapshotSync.contentDigest(this),
    )

    fun syncConnectionSummary(): String = if (syncPreferences.getString(SYNC_OWNER_KEY, null).isNullOrBlank()) {
        "未连接 GitHub，当前使用离线模式。\n本地自动备份：${automaticBackupLabel()}"
    } else {
        val uploadedAt = syncPreferences.getLong(SYNC_LAST_SNAPSHOT_KEY, 0L)
            .takeIf { it > 0L }
            ?.let { java.text.SimpleDateFormat("MM-dd HH:mm", Locale.ROOT).format(java.util.Date(it)) }
            ?: "尚未上传"
        "已配置 GitHub：${syncPreferences.getString(SYNC_OWNER_KEY, "")}/${syncPreferences.getString(SYNC_REPOSITORY_KEY, "")}\n" +
            "本地自动备份：${automaticBackupLabel()} · 最近快照：$uploadedAt\n" +
            "最近同步：${syncPreferences.getLong(SYNC_LAST_SYNC_KEY, 0L).takeIf { it > 0L }?.let { java.text.SimpleDateFormat("MM-dd HH:mm", Locale.ROOT).format(java.util.Date(it)) } ?: "尚未记录"}\n" +
            syncPreferences.getString(SYNC_LAST_RESULT_KEY, "尚未记录同步结果")
    }

    private fun automaticBackupLabel(): String = automaticBackupStore.latestBackup()
        ?.let { java.text.SimpleDateFormat("MM-dd HH:mm", Locale.ROOT).format(java.util.Date(it.lastModified())) }
        ?: "尚未生成"

    fun close() {
        speech.release()
        database.close()
    }

    private companion object {
        const val CET6_CORE_WORD_COUNT = 2219
        const val FAVORITE_WORD_IDS = "favorite_word_ids"
        const val SPEECH_RATE_KEY = "speech_rate"
        const val SPEECH_VOICE_KEY = "speech_voice_key"
        const val DAILY_QUOTA_KEY = "daily_quota"
        const val DEFAULT_MODE_KEY = "default_mode"
        const val AUTO_PLAY_WORD_KEY = "auto_play_word"
        const val AUTO_PLAY_SENTENCE_KEY = "auto_play_sentence"
        const val WORD_POOL_DECK_IDS = "word_pool_deck_ids"
        const val WORD_POOL_EXCLUDED_WORD_IDS = "word_pool_excluded_word_ids"
        const val WORD_POOL_WEIGHTS = "word_pool_weights"
        const val SELECTION_DASHBOARD_CACHE_KEY = "selection_dashboard_cache"
        const val HOME_STATE_CACHE_KEY = "home_state_cache"
        const val HOME_STATE_CACHE_VERSION = 1
        const val DASHBOARD_QUERY_CHUNK_SIZE = 800
        const val DECK_SUMMARIES_CACHE_KEY = "deck_summaries_cache_v1"
        const val STATS_STATE_CACHE_KEY = "stats_state_cache_v1"
        const val STATS_ANCHOR_DATE_KEY = "stats_anchor_date"
        const val STATS_PERIOD_KEY = "stats_period"
        const val STATS_TAB_KEY = "stats_tab"
        const val STATS_START_DATE_KEY = "stats_start_date"
        const val SYNC_OWNER_KEY = "github_owner"
        const val SYNC_REPOSITORY_KEY = "github_repository"
        const val SYNC_BRANCH_KEY = "github_branch"
        const val SYNC_PASSWORD_KEY = "github_sync_password"
        const val SYNC_LAST_SNAPSHOT_KEY = "github_last_snapshot"
        const val SYNC_LAST_SYNC_KEY = "github_last_sync"
        const val SYNC_LAST_RESULT_KEY = "github_last_result"
        const val CET6_EXAMPLES_VERSION_KEY = "cet6_examples_version"
        const val CET6_EXAMPLES_VERSION = 3
        const val GENERATED_EXAMPLES_VERSION_KEY = "generated_examples_version"
        const val GENERATED_EXAMPLES_VERSION = 4
        const val CET6_SOURCE_METADATA_VERSION_KEY = "cet6_source_metadata_version"
        const val CET6_SOURCE_METADATA_VERSION = 1
        const val BUILTIN_METADATA_VERSION_KEY = "builtin_word_metadata_version"
        const val BUILTIN_METADATA_VERSION = 1

        val BUILTIN_ASSET_PACKS = listOf(
            BuiltinAssetPack("cet4-all", "四级全部词汇", "cet4-all.csv", "wordpacks/cet4-all/words.csv", 4_533),
            BuiltinAssetPack("cet4-core", "四级核心词汇", "cet4-core.csv", "wordpacks/cet4-core/words.csv", 2_000),
            BuiltinAssetPack("cet6-all", "六级全部词汇", "cet6-all.csv", "wordpacks/cet6-all/words.csv", 5_407),
            BuiltinAssetPack("kaoyan-shared", "考研英语(2024大纲)", "kaoyan-shared-2024.csv", "wordpacks/kaoyan-shared/words.csv", 5_528),
        )
    }
}

private data class BuiltinAssetPack(
    val packId: String,
    val deckName: String,
    val sourceFileName: String,
    val resourcePath: String,
    val wordCount: Int,
)

private fun JSONArray?.toUiDeckSummaries(): List<DeckSummary> = if (this == null) emptyList() else buildList {
    for (index in 0 until length()) {
        val item = optJSONObject(index) ?: continue
        val source = runCatching { DeckSourceType.valueOf(item.optString("sourceType", DeckSourceType.IMPORTED.name)) }
            .getOrDefault(DeckSourceType.IMPORTED)
        add(DeckSummary(
            name = item.optString("name"),
            wordCount = item.optInt("wordCount"),
            dueCount = item.optInt("dueCount"),
            newCount = item.optInt("newCount"),
            totalWordCount = item.optInt("totalWordCount"),
            id = item.optLong("id"),
            sourceType = source,
            isEditable = source != DeckSourceType.BUILTIN,
        ))
    }
}

private fun JSONArray?.toUiWords(): List<WordListItemUi> = if (this == null) emptyList() else buildList {
    for (index in 0 until length()) {
        val item = optJSONObject(index) ?: continue
        add(WordListItemUi(
            id = item.optLong("id"),
            spelling = item.optString("spelling"),
            phonetic = item.optString("phonetic").takeIf { it.isNotBlank() },
            partOfSpeech = item.optString("partOfSpeech"),
            definitionCn = item.optString("definitionCn"),
            deckId = item.optLong("deckId").takeIf { it > 0L },
            deckName = item.optString("deckName").takeIf { it.isNotBlank() },
        ))
    }
}

private fun JSONObject?.toWeights(): Map<Long, Int> = if (this == null) emptyMap() else keys().asSequence().mapNotNull { key ->
    key.toLongOrNull()?.let { id -> id to optInt(key) }
}.toMap()

private fun List<com.pengshi.words.model.ExampleSentence>.needsOnlineExamples(): Boolean =
    size < 2 || any { example ->
        example.sentenceEn.contains("appears in many academic texts.", ignoreCase = true) ||
            example.sentenceEn.startsWith("Try to use", ignoreCase = true)
    }

private fun String.isGenericExamplePlaceholder(): Boolean =
    matches(Regex("^The word \\\"?.*\\\"? appears in many academic texts\\.$")) ||
        matches(Regex("^Try to use \\\"?.*\\\"? in a sentence\\.$"))
