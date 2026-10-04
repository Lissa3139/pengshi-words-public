package com.pengshi.words.desktop

import com.pengshi.words.desktop.storage.DesktopImportRepository
import com.pengshi.words.desktop.storage.DesktopSnapshotGateway
import com.pengshi.words.desktop.storage.DesktopSqliteDatabase
import com.pengshi.words.desktop.storage.DesktopStudySessionRepository
import com.pengshi.words.desktop.storage.DesktopSyncStore
import com.pengshi.words.desktop.storage.DesktopContentRepository
import com.pengshi.words.desktop.storage.DesktopUserDeckRepository
import com.pengshi.words.desktop.speech.EmbeddedWindowsSpeechEngine
import com.pengshi.words.desktop.speech.ClasspathDesktopSpeechResources
import com.pengshi.words.desktop.speech.DesktopSpeechModelPaths
import com.pengshi.words.desktop.speech.WindowsSpeechSettings
import com.pengshi.words.desktop.sync.DesktopSyncSettingsState
import com.pengshi.words.desktop.sync.DesktopSyncSettingsStore
import com.pengshi.words.desktop.sync.DesktopPersonalSettingsStore
import com.pengshi.words.desktop.sync.DesktopAppSettingsStore
import com.pengshi.words.desktop.wordpool.DesktopWordPoolStore
import com.pengshi.words.backup.JsonBackupReader
import com.pengshi.words.backup.BackupSettingsSnapshot
import com.pengshi.words.domain.AddNewWordsUseCase
import com.pengshi.words.domain.AdjustDailyQuotaUseCase
import com.pengshi.words.domain.AppendExtraWordsUseCase
import com.pengshi.words.domain.AddWordToTodayUseCase
import com.pengshi.words.domain.ResumeDailyStudyUseCase
import com.pengshi.words.domain.ReconcileDailyPlanUseCase
import com.pengshi.words.domain.StartDailyStudyUseCase
import com.pengshi.words.domain.StudySessionState
import com.pengshi.words.domain.SubmitFeedbackUseCase
import com.pengshi.words.importer.ImportFormat
import com.pengshi.words.importer.ImportResult
import com.pengshi.words.model.DailyItemStatus
import com.pengshi.words.model.DailyPlan
import com.pengshi.words.model.DailyQuota
import com.pengshi.words.model.DailyPlanEntry
import com.pengshi.words.model.DailyPlanItem
import com.pengshi.words.model.DailyPlanStatus
import com.pengshi.words.model.IntradayReviewEvent
import com.pengshi.words.model.DeckSourceType
import com.pengshi.words.model.Feedback
import com.pengshi.words.sync.SyncEventKind
import com.pengshi.words.sync.SyncEventRecord
import com.pengshi.words.sync.SyncRemoteEventApplier
import com.pengshi.words.sync.SyncPayloadV2
import com.pengshi.words.sync.FeedbackPayloadV2
import com.pengshi.words.sync.PlanLockedV2
import com.pengshi.words.sync.PlanReconciledV2
import com.pengshi.words.sync.PlanLockCompatibility
import com.pengshi.words.sync.SyncKeyFactory
import com.pengshi.words.model.FeedbackSubmission
import com.pengshi.words.model.FeedbackUndoToken
import com.pengshi.words.model.IntradayEventStatus
import com.pengshi.words.model.PlanSource
import com.pengshi.words.model.isNewWord
import com.pengshi.words.model.StudyDataSnapshot
import com.pengshi.words.model.StudyMode
import com.pengshi.words.model.BatchImportSources
import com.pengshi.words.model.UserDeckBulkResult
import com.pengshi.words.model.WordPoolSelection
import com.pengshi.words.model.ManualWordCatalog
import com.pengshi.words.model.manualWordCatalog
import com.pengshi.words.model.AutomaticWordPool
import com.pengshi.words.model.resolve
import com.pengshi.words.model.PersonalWordInput
import com.pengshi.words.model.isDeletedUserDeck
import com.pengshi.words.scheduler.DefaultStudyScheduler
import com.pengshi.words.sync.GitHubSyncSettings
import com.pengshi.words.sync.CheckpointDecision
import com.pengshi.words.sync.CheckpointMetadata
import com.pengshi.words.sync.PersonalSnapshotSync
import com.pengshi.words.sync.SnapshotSummary
import com.pengshi.words.sync.SyncCursor
import com.pengshi.words.sync.cursorKey
import com.pengshi.words.sync.SyncCoordinator
import com.pengshi.words.sync.SyncCrypto
import com.pengshi.words.sync.SyncManifestCodec
import com.pengshi.words.sync.SyncResult
import com.pengshi.words.sync.SyncStatus
import com.pengshi.words.sync.github.GitHubApi
import com.pengshi.words.sync.github.GitHubRemoteStore
import com.pengshi.words.backup.JsonBackupWriter
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import java.util.Properties
import com.pengshi.words.sync.appliedEventDigest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DesktopContainer private constructor(
    private val database: DesktopSqliteDatabase,
    val dataDirectory: Path,
    private val defaultMode: StudyMode,
    private val favoritePath: Path,
    private val modePath: Path,
    private val speechSettingsPath: Path,
    private val syncSettingsPath: Path,
    private val personalSettingsPath: Path,
    private val wordPoolPath: Path,
    private val statsStartPath: Path,
    deviceIdPath: Path,
    seedResource: String,
    private val appSettingsStore: DesktopAppSettingsStore?,
    legacySyncSettingsPaths: List<Path>,
) : AutoCloseable {
    private val operationMutex = Mutex()
    private val syncStore = DesktopSyncStore(database, DesktopSyncStore.loadOrCreateDeviceId(deviceIdPath))
    private val repository = DesktopStudySessionRepository(database, syncStore)
    private val scheduler = DefaultStudyScheduler()
    private val wordPoolStore = DesktopWordPoolStore(wordPoolPath)
    private val startUseCase = StartDailyStudyUseCase(
        repository = repository,
        scheduler = scheduler,
        quotaProvider = { DailyQuota.normalize(savedLearningSettings.dailyQuota) },
        eligibleWordIdsProvider = { eligibleAutomaticWordIds() },
        automaticWordPoolProvider = { automaticWordPool() },
    )
    private val resumeUseCase = ResumeDailyStudyUseCase(
        repository = repository,
        defaultMode = defaultMode,
        scheduler = scheduler,
        quotaProvider = { DailyQuota.normalize(savedLearningSettings.dailyQuota) },
        reconciler = ReconcileDailyPlanUseCase(
            repository = repository,
            scheduler = scheduler,
            eligibleWordIdsProvider = { eligibleAutomaticWordIds() },
            automaticWordPoolProvider = { automaticWordPool() },
        ),
    )
    private val adjustDailyQuotaUseCase = AdjustDailyQuotaUseCase(repository, startUseCase)
    private val submitUseCase = SubmitFeedbackUseCase(repository, scheduler, startUseCase)
    private val addNewWordsUseCase = AddNewWordsUseCase(
        repository = repository,
        scheduler = scheduler,
        renderer = startUseCase,
        eligibleWordIdsProvider = { eligibleAutomaticWordIds() },
        automaticWordPoolProvider = { automaticWordPool() },
    )
    private val appendExtraWordsUseCase = AppendExtraWordsUseCase(
        repository = repository,
        scheduler = scheduler,
        renderer = startUseCase,
        eligibleWordIdsProvider = { eligibleAutomaticWordIds() },
        automaticWordPoolProvider = { automaticWordPool() },
        deckWordIdsProvider = { deckId ->
            snapshotGateway.snapshot().deckWords.filter { it.deckId == deckId }.mapTo(linkedSetOf()) { it.wordId }
        },
    )
    private val addWordToTodayUseCase = AddWordToTodayUseCase(repository, startUseCase)
    private val importRepository = DesktopImportRepository(database)
    private val snapshotGateway = DesktopSnapshotGateway(database)
    private val contentRepository = DesktopContentRepository(database)
    private val userDeckRepository = DesktopUserDeckRepository(database)
    private val seedLoader = DesktopSeedLoader(
        importRepository = importRepository,
        snapshotGateway = snapshotGateway,
        contentRepository = contentRepository,
        resourcePath = seedResource,
        onDeckAliases = wordPoolStore::replaceDeckIds,
    )
    private val favoriteIds = readFavoriteIds().toMutableSet()
    private val desktopSpeechSettings = WindowsSpeechSettings(speechSettingsPath)
    private val syncSettingsStore = DesktopSyncSettingsStore(syncSettingsPath, legacySyncSettingsPaths)
    private val personalSettingsStore = DesktopPersonalSettingsStore(personalSettingsPath)
    @Volatile private var savedLearningSettings = personalSettingsStore.load()
    private val activeSpeechModelDirectory = appSettingsStore?.speechModelDirectory()
        ?: DesktopSpeechModelPaths.defaultExternal()
    val speech = EmbeddedWindowsSpeechEngine(
        resources = ClasspathDesktopSpeechResources(activeSpeechModelDirectory.parent),
        settings = desktopSpeechSettings,
        externalModelsRoot = activeSpeechModelDirectory,
    )
        .also { it.restoreVoiceKey(desktopSpeechSettings.selectedVoiceKey) }
    var preferredMode: StudyMode = readPreferredMode()
        private set

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
        val word = repository.getWordByNormalizedSpelling(normalizedWord) ?: return false
        val plan = repository.getPlan(localDate) ?: return false
        val item = repository.getItems(plan.id).firstOrNull { it.wordId == word.id && it.source != PlanSource.EXTRA } ?: return false
        val feedbackName = Regex("(?:^|\\|)feedback=([^|]+)").find(event.payload)?.groupValues?.get(1) ?: return false
        val feedback = runCatching { Feedback.valueOf(feedbackName) }.getOrNull() ?: return false
        if (repository.getEvents(item.id).none { it.status == IntradayEventStatus.PENDING }) return true
        return runCatching {
            submitUseCase.submitFeedbackWithUndo(
                item.id,
                feedback,
                event.occurredAtUtc,
                submission = FeedbackSubmission(recordSyncEvent = false),
            )
            true
        }.getOrDefault(false)
    }

    private suspend fun applyRemotePlan(payload: PlanLockedV2): Boolean {
        val planKey = SyncKeyFactory.planKey(payload.localDate)
        val existing = repository.getPlan(payload.localDate)
        val entries = payload.items.map { item ->
            val normalized = item.wordKey.removePrefix("word:").trim().lowercase(Locale.ROOT)
            val word = repository.getWordByNormalizedSpelling(normalized) ?: return false
            repository.ensureCard(word.id, payload.studyMode, payload.createdAtUtc)
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
        val remoteRepository = DesktopStudySessionRepository(database)
        if (existing == null) {
            remoteRepository.createPlan(
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
            val word = repository.getWord(entry.item.wordId) ?: return@filterNot false
            SyncKeyFactory.itemKey(planKey, SyncKeyFactory.wordKey(word.normalizedSpelling), entry.item.source) in existingKeys
        }
        val mainCount = existingItems.count { it.source != PlanSource.EXTRA }
        val targetMainCount = mainCount - staleUnseenNewItems.size + missing.count { it.item.source != PlanSource.EXTRA }
        if (targetMainCount > maxOf(existing.quota, payload.quota)) return false
        if (staleUnseenNewItems.isEmpty() && missing.isEmpty() && existing.quota == payload.quota) return true
        remoteRepository.replaceUnseenNewItems(
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
            val word = repository.getWordByNormalizedSpelling(normalized) ?: return false
            repository.ensureCard(word.id, payload.studyMode, Instant.now())
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
            val word = repository.getWord(entry.item.wordId) ?: return@filterNot false
            SyncKeyFactory.itemKey(planKey, SyncKeyFactory.wordKey(word.normalizedSpelling), entry.item.source) in existingKeys
        }
        val updatedQuota = payload.quota ?: existing.quota
        if (removedItems.isEmpty()) {
            if (missingEntries.isEmpty() && updatedQuota == existing.quota) return true
            val mainCount = existingItems.count { it.source != PlanSource.EXTRA }
            if (missingEntries.isEmpty()) {
                DesktopStudySessionRepository(database).replaceUnseenNewItems(
                    existing.copy(quota = updatedQuota, plannedUniqueWordCount = mainCount, updatedAt = Instant.now()),
                    emptySet(),
                    emptyList(),
                    Instant.now(),
                )
            } else DesktopStudySessionRepository(database).appendToPlan(
                existing.copy(
                    quota = updatedQuota,
                    plannedUniqueWordCount = mainCount + missingEntries.count { it.item.source != PlanSource.EXTRA },
                    updatedAt = Instant.now(),
                ),
                missingEntries,
            )
        } else {
            val mainCount = existingItems.count { it.source != PlanSource.EXTRA }
            DesktopStudySessionRepository(database).replaceUnseenNewItems(
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
        val word = repository.getWordByNormalizedSpelling(payload.wordKey.removePrefix("word:").trim().lowercase(Locale.ROOT)) ?: return false
        val plan = repository.getPlan(localDate) ?: return false
        val item = repository.getItems(plan.id).firstOrNull {
            it.wordId == word.id && SyncKeyFactory.itemKey(payload.planKey, payload.wordKey, it.source) == payload.itemKey
        } ?: return false
        if (repository.getEvents(item.id).none { it.status == IntradayEventStatus.PENDING }) return true
        return runCatching {
            submitUseCase.submitFeedbackWithUndo(
                item.id,
                payload.feedback,
                payload.reviewedAtUtc,
                responseTimeMs = payload.responseTimeMs,
                submission = FeedbackSubmission(recordSyncEvent = false),
            )
            true
        }.getOrDefault(false)
    }

    suspend fun seedIfNeeded(): ImportResult = seedLoader.seedIfNeeded().also {
        userDeckRepository.labelUnattributedLiteratureContent()
    }

    /**
     * Restores a newer encrypted snapshot that was already pulled into the local repository.
     * This keeps the desktop client usable without making a network request on startup.
     */
    suspend fun restoreLocalRepositoryBootstrapIfNeeded(): Boolean {
        val settings = syncSettingsStore.settings() ?: return false
        val files = DesktopLocalBootstrap.resolve(dataDirectory) ?: return false
        return runCatching {
            val manifest = SyncManifestCodec.decode(
                SyncCrypto.decrypt(settings.syncPassword, Files.readAllBytes(files.manifestPath)),
            )
            val reference = manifest.bootstrap ?: return@runCatching false
            val encrypted = dataDirectory.resolve(reference.path)
            if (!Files.isRegularFile(encrypted)) return@runCatching false
            val preview = JsonBackupReader().read(
                SyncCrypto.decrypt(settings.syncPassword, Files.readAllBytes(encrypted)).inputStream(),
            )
            val localSnapshot = snapshotGateway.snapshot()
            val remoteSnapshot = preview.snapshot
            val localSummary = localSnapshot.summary()
            val remoteSummary = remoteSnapshot.summary()
            when {
                PersonalSnapshotSync.shouldRestoreRemote(localSummary, remoteSummary) -> {
                    restoreRemoteLearningSnapshot(localSnapshot, remoteSnapshot)
                    preview.settings?.let { applyRemoteSettings(it, includeDeviceVoice = false) }
                    restoreSyncedStatsStart(preview.statsStartDate, remoteSnapshot)
                    true
                }
                PersonalSnapshotSync.shouldMergeRemoteContent(localSummary, remoteSummary) -> {
                    snapshotGateway.mergeContent(remoteSnapshot)
                    true
                }
                else -> false
            }
        }.getOrDefault(false)
    }

    suspend fun homeState(
        localDate: LocalDate = LocalDate.now(),
        now: Instant = Instant.now(),
        mode: StudyMode = preferredMode,
    ): DesktopHomeState {
        val plan = repository.getPlan(localDate)
        val candidates = repository.getCandidates(mode, now)
        val items = plan?.let { repository.getItems(it.id) }.orEmpty()
        val reviewItems = items.filter { it.source == PlanSource.DUE_REVIEW }
        val newItems = items.filter { it.source.isNewWord }
        val extraItems = items.filter { it.source == PlanSource.EXTRA }
        val hasPendingExtra = items
            .filter { it.source == PlanSource.EXTRA }
            .any { item -> repository.getEvents(item.id).any { it.status == IntradayEventStatus.PENDING } }
        val reviewCompleted = reviewItems.count { it.status == DailyItemStatus.COMPLETED }
        val newCompleted = newItems.count { it.status == DailyItemStatus.COMPLETED }
        val phase = when {
            plan == null -> DesktopStudyPhase.NOT_STARTED
            reviewCompleted < reviewItems.size -> DesktopStudyPhase.REVIEW
            newCompleted < newItems.size -> DesktopStudyPhase.NEW_WORDS
            hasPendingExtra -> DesktopStudyPhase.EXTRA
            items.count { it.source != PlanSource.EXTRA } < plan.quota && candidates.any { it.isNew &&
                items.none { item -> item.wordId == it.wordId } } -> DesktopStudyPhase.CHOOSE_NEW
            else -> DesktopStudyPhase.COMPLETE
        }
        val snapshot = snapshotGateway.snapshot()
        return DesktopHomeState(
            completedUniqueWordCount = plan?.completedUniqueWordCount ?: 0,
            quota = plan?.quota ?: 30,
            dueCount = candidates.count { !it.isNew },
            availableNewCount = candidates.count { it.isNew },
            reviewTotal = reviewItems.size,
            reviewCompleted = reviewCompleted,
            newTotal = newItems.size,
            newCompleted = newCompleted,
            extraTotal = extraItems.size,
            extraCompleted = extraItems.count { it.status == DailyItemStatus.COMPLETED },
            phase = phase,
            isTodayComplete = phase == DesktopStudyPhase.COMPLETE || phase == DesktopStudyPhase.EXTRA,
            checkInDates = snapshot.dailyPlans
                .filter { it.status == DailyPlanStatus.COMPLETED }
                .mapTo(linkedSetOf()) { it.localDate },
        )
    }

    fun wordPoolSelection(): WordPoolSelection = wordPoolStore.load()

    suspend fun selectionDashboardState(
        mode: StudyMode = preferredMode,
        now: Instant = Instant.now(),
    ): DesktopSelectionDashboardState {
        val snapshot = snapshotGateway.snapshot()
        val selection = wordPoolStore.load()
        val candidates = repository.getCandidates(mode, now)
        val eligibleIds = eligibleAutomaticWordIds(snapshot, selection)
        val eligibleCandidates = candidates.filter { it.wordId in eligibleIds }
        val dueIds = eligibleCandidates.filterNot { it.isNew }.map { it.wordId }.toSet()
        val newIds = eligibleCandidates.filter { it.isNew }.map { it.wordId }.toSet()
        val deckSummaries = snapshot.decks.filterNot { it.isDeletedUserDeck() }.map { deck ->
            val deckWordIds = snapshot.deckWords.filter { it.deckId == deck.id }.map { it.wordId }.toSet()
            DesktopDeckSummary(
                id = deck.id,
                name = deck.name,
                wordCount = deckWordIds.size,
                dueCount = deckWordIds.count { it in dueIds },
                newCount = deckWordIds.count { it in newIds },
                sourceType = deck.sourceType,
            )
        }
        return DesktopSelectionDashboardState(
            selection = selection,
            decks = deckSummaries,
            candidateCount = eligibleCandidates.map { it.wordId }.toSet().size,
            dueCount = dueIds.size,
            newCount = newIds.size,
            excludedWords = snapshot.words.filter { it.id in selection.excludedWordIds },
            deckWeights = selection.resolve(deckSummaries.map { it.id }.toSet()).deckWeights,
        )
    }

    suspend fun toggleDeckInWordPool(deckId: Long) {
        val allDeckIds = snapshotGateway.snapshot().decks.mapTo(linkedSetOf()) { it.id }
        val current = wordPoolStore.load()
        val nextIncluded = when {
            current.includedDeckIds.isEmpty() -> allDeckIds - deckId
            deckId in current.includedDeckIds -> current.includedDeckIds - deckId
            else -> current.includedDeckIds + deckId
        }.let { included -> if (included == allDeckIds) emptySet() else included }
        val nextSelection = current.copy(includedDeckIds = nextIncluded)
        wordPoolStore.save(nextSelection.copy(deckWeights = nextSelection.resolve(allDeckIds).deckWeights))
    }

    suspend fun saveWordPoolSelection(selection: WordPoolSelection) {
        val allDeckIds = snapshotGateway.snapshot().decks.mapTo(linkedSetOf()) { it.id }
        val resolved = selection.resolve(allDeckIds)
        val includedDeckIds = if (resolved.includedDeckIds == allDeckIds) emptySet() else resolved.includedDeckIds
        wordPoolStore.save(
            selection.copy(
                includedDeckIds = includedDeckIds,
                deckWeights = resolved.deckWeights,
            ),
        )
    }

    suspend fun setDeckWeightInWordPool(deckId: Long, percent: Int) {
        val allDeckIds = snapshotGateway.snapshot().decks.mapTo(linkedSetOf()) { it.id }
        val current = wordPoolStore.load()
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
        wordPoolStore.save(current.copy(deckWeights = weights))
    }

    fun clearWordPoolDecks() {
        wordPoolStore.save(WordPoolSelection())
    }

    fun toggleExcludedWord(wordId: Long) {
        val current = wordPoolStore.load()
        val next = if (wordId in current.excludedWordIds) {
            current.excludedWordIds - wordId
        } else {
            current.excludedWordIds + wordId
        }
        wordPoolStore.save(current.copy(excludedWordIds = next))
    }

    private fun applyRemoteSettings(settings: BackupSettingsSnapshot, includeDeviceVoice: Boolean = true) {
        val voiceKey = if (includeDeviceVoice) settings.selectedVoiceKey else speech.selectedVoiceKey
        val normalizedSettings = settings.copy(
            dailyQuota = DailyQuota.normalize(settings.dailyQuota), selectedVoiceKey = voiceKey,
        )
        personalSettingsStore.save(normalizedSettings)
        savedLearningSettings = normalizedSettings
        runCatching { setPreferredMode(StudyMode.valueOf(normalizedSettings.defaultMode)) }
        wordPoolStore.save(
            WordPoolSelection(
                includedDeckIds = normalizedSettings.includedDeckIds,
                deckWeights = normalizedSettings.deckWeights,
                excludedWordIds = normalizedSettings.excludedWordIds,
            ),
        )
        synchronized(favoriteIds) {
            favoriteIds.clear()
            favoriteIds.addAll(normalizedSettings.favoriteWordIds)
            Files.writeString(
                favoritePath,
                favoriteIds.sorted().joinToString("\n"),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
            )
        }
        if (includeDeviceVoice) speech.restoreVoiceKey(normalizedSettings.selectedVoiceKey)
        desktopSpeechSettings.save(voiceKey, normalizedSettings.speechRate)
    }

    suspend fun createUserDeck(name: String): Long = importRepository.createUserDeck(name)

    suspend fun addWordToUserDeck(deckId: Long, input: PersonalWordInput): UserDeckBulkResult =
        importRepository.addWordToUserDeck(deckId, input)

    fun deleteUserDeck(deckId: Long): Boolean = userDeckRepository.deleteDeck(deckId)

    fun updateWordInUserDeck(deckId: Long, wordId: Long, input: PersonalWordInput) =
        userDeckRepository.updateWord(deckId, wordId, input)

    fun deleteWordFromUserDeck(deckId: Long, wordId: Long): Boolean =
        userDeckRepository.deleteWordFromDeck(deckId, wordId)

    fun addManualExample(wordId: Long, sentenceEn: String, sentenceCn: String?, source: String): Boolean =
        userDeckRepository.addExample(wordId, sentenceEn, sentenceCn, source)

    fun updateManualExample(exampleId: Long, sentenceEn: String, sentenceCn: String?, source: String): Boolean =
        userDeckRepository.updateExample(exampleId, sentenceEn, sentenceCn, source)

    fun deleteManualExample(exampleId: Long): Boolean = userDeckRepository.deleteExample(exampleId)

    private suspend fun eligibleAutomaticWordIds(): Set<Long> = eligibleAutomaticWordIds(
        snapshotGateway.snapshot(),
        wordPoolStore.load(),
    )

    private suspend fun automaticWordPool(): AutomaticWordPool {
        val snapshot = snapshotGateway.snapshot()
        val selection = wordPoolStore.load()
        val resolved = selection.resolve(snapshot.decks.map { it.id }.toSet())
        val examDeckId = snapshot.decks.firstOrNull {
            it.sourceType == DeckSourceType.BUILTIN &&
                it.sourceFileName == com.pengshi.words.model.ElementaryEnglishWords.KAOYAN_SOURCE_FILE
        }?.id
        val elementaryIds = snapshot.words.asSequence()
            .filter { com.pengshi.words.model.ElementaryEnglishWords.contains(it.spelling) }
            .map { it.id }.toSet()
        val deckIdsByWordId = snapshot.deckWords
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

    private fun eligibleAutomaticWordIds(
        snapshot: StudyDataSnapshot,
        selection: WordPoolSelection,
    ): Set<Long> {
        val includedDeckIds = selection.includedDeckIds
        val selectedDeckIds = if (includedDeckIds.isEmpty()) {
            snapshot.decks.mapTo(linkedSetOf()) { it.id }
        } else includedDeckIds
        val examDeckId = snapshot.decks.firstOrNull {
            it.sourceType == DeckSourceType.BUILTIN &&
                it.sourceFileName == com.pengshi.words.model.ElementaryEnglishWords.KAOYAN_SOURCE_FILE
        }?.id
        val elementaryIds = snapshot.words.asSequence()
            .filter { com.pengshi.words.model.ElementaryEnglishWords.contains(it.spelling) }
            .map { it.id }.toSet()
        return snapshot.deckWords
            .asSequence()
            .filter { it.deckId in selectedDeckIds && it.wordId !in selection.excludedWordIds }
            .filterNot { it.deckId == examDeckId && it.wordId in elementaryIds }
            .map { it.wordId }
            .toSet()
    }

    suspend fun startStudy(
        localDate: LocalDate = LocalDate.now(),
        now: Instant = Instant.now(),
        mode: StudyMode = preferredMode,
    ): StudySessionState = operationMutex.withLock { startUseCase.startDailyStudy(localDate, now, mode) }

    suspend fun resumeStudy(
        localDate: LocalDate = LocalDate.now(),
        now: Instant = Instant.now(),
    ): StudySessionState = operationMutex.withLock { resumeUseCase.resumeDailyStudy(localDate, now) }

    suspend fun submitFeedback(
        itemId: Long,
        feedback: Feedback,
        now: Instant = Instant.now(),
    ): StudySessionState = operationMutex.withLock { submitUseCase.submitFeedback(itemId, feedback, now) }

    suspend fun submitFeedbackWithUndo(
        itemId: Long,
        feedback: Feedback,
        now: Instant = Instant.now(),
    ): SubmitFeedbackUseCase.FeedbackSessionResult = operationMutex.withLock { submitUseCase.submitFeedbackWithUndo(
        itemId = itemId,
        feedback = feedback,
        now = now,
    ) }

    suspend fun reviseFeedback(
        token: FeedbackUndoToken,
        feedback: Feedback,
        now: Instant = Instant.now(),
    ): SubmitFeedbackUseCase.FeedbackSessionResult = operationMutex.withLock { submitUseCase.reviseFeedback(
        token = token,
        feedback = feedback,
        now = now,
    ) }

    suspend fun wordDetails(wordId: Long): DesktopWordDetails = DesktopWordDetails(
        word = requireNotNull(repository.getWord(wordId)) { "Unknown word: $wordId" },
        examples = repository.getExamples(wordId),
        relatedWords = repository.getRelatedWords(wordId),
        senses = repository.getWordSenses(wordId),
    )

    fun isFavorite(wordId: Long): Boolean = synchronized(favoriteIds) { wordId in favoriteIds }

    fun toggleFavorite(wordId: Long): Boolean = synchronized(favoriteIds) {
        if (!favoriteIds.add(wordId)) favoriteIds.remove(wordId)
        Files.writeString(
            favoritePath,
            favoriteIds.sorted().joinToString("\n"),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
        )
        wordId in favoriteIds
    }

    fun setPreferredMode(mode: StudyMode) {
        preferredMode = mode
        Files.writeString(
            modePath,
            mode.name,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
        )
    }

    val savedSpeechRate: Float
        get() = desktopSpeechSettings.speechRate

    val savedAutoPlayWord: Boolean
        get() = savedLearningSettings.autoPlayWord

    val savedAutoPlaySentence: Boolean
        get() = savedLearningSettings.autoPlaySentence

    val dailyQuota: Int
        get() = DailyQuota.normalize(savedLearningSettings.dailyQuota)

    suspend fun updateDailyQuota(quota: Int, localDate: LocalDate = LocalDate.now()) = operationMutex.withLock {
        DailyQuota.requireValid(quota)
        val previous = savedLearningSettings
        if (DailyQuota.normalize(previous.dailyQuota) == quota) return@withLock
        val updated = previous.copy(dailyQuota = quota)
        personalSettingsStore.save(updated)
        savedLearningSettings = updated
        try {
            adjustDailyQuotaUseCase.adjust(localDate, quota, Instant.now(), preferredMode)
        } catch (failure: Throwable) {
            savedLearningSettings = previous
            personalSettingsStore.save(previous)
            throw failure
        }
    }

    suspend fun applySavedDailyQuotaToToday(localDate: LocalDate = LocalDate.now()) = operationMutex.withLock {
        adjustDailyQuotaUseCase.adjust(localDate, dailyQuota, Instant.now(), preferredMode)
    }

    fun saveSpeechSettings(rate: Float) {
        desktopSpeechSettings.save(speech.selectedVoiceKey, rate)
        savedLearningSettings = savedLearningSettings.copy(
            speechRate = rate,
            selectedVoiceKey = speech.selectedVoiceKey,
        )
        personalSettingsStore.save(savedLearningSettings)
    }

    fun saveAutoPlaySettings(autoPlayWord: Boolean, autoPlaySentence: Boolean) {
        savedLearningSettings = savedLearningSettings.copy(
            autoPlayWord = autoPlayWord,
            autoPlaySentence = autoPlaySentence,
        )
        personalSettingsStore.save(savedLearningSettings)
    }

    fun stopSpeech() = speech.stop()

    fun syncSettingsState(): DesktopSyncSettingsState = syncSettingsStore.state()

    fun hasSyncConfiguration(): Boolean = syncSettingsStore.state().let { state ->
        state.owner.isNotBlank() || state.repository.isNotBlank() || state.hasPassword || state.hasToken
    }

    fun saveSyncConnection(owner: String, repository: String, branch: String, password: String, token: String) {
        syncSettingsStore.save(owner, repository, branch, password, token)
    }

    fun selectedDataDirectory(): Path = appSettingsStore?.dataDirectory() ?: dataDirectory

    fun selectedSpeechModelDirectory(): Path = appSettingsStore?.speechModelDirectory() ?: activeSpeechModelDirectory

    fun saveSelectedSpeechModelDirectory(directory: Path) {
        require(Files.isDirectory(directory)) { "所选位置不是一个文件夹" }
        requireNotNull(appSettingsStore) { "应用设置不可用" }.saveSpeechModelDirectory(directory)
    }

    fun saveSelectedDataDirectory(directory: Path) {
        require(Files.isDirectory(directory)) { "所选位置不是一个文件夹" }
        requireNotNull(appSettingsStore) { "应用设置不可用" }.saveDataDirectory(directory)
    }

    fun resetSelectedDataDirectory() {
        appSettingsStore?.clearDataDirectory()
    }

    suspend fun syncNow(): SyncResult = operationMutex.withLock { syncNowUnlocked() }

    private suspend fun syncNowUnlocked(): SyncResult {
        userDeckRepository.labelUnattributedLiteratureContent()
        val settings = syncSettingsStore.settings()
            ?: return SyncResult(SyncStatus.AUTH_REQUIRED, 0, 0, 0).also {
                appendSyncDiagnostic("status=${it.status}")
            }
        val token = syncSettingsStore.token()
            ?: return SyncResult(SyncStatus.AUTH_REQUIRED, 0, 0, 0).also {
                appendSyncDiagnostic("status=${it.status}")
            }
        val remote = GitHubRemoteStore(
            api = GitHubApi(token),
            config = com.pengshi.words.sync.github.GitHubRepositoryConfig(
                settings.owner,
                settings.repository,
                settings.branch,
            ),
            syncPassword = settings.syncPassword,
        )
        return runCatching {
            val checkpointAction = restoreRemoteBootstrapIfNeeded(remote, settings.syncPassword)
            userDeckRepository.labelUnattributedLiteratureContent()
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
                uploadCurrentBootstrapIfChanged(remote, settings.syncPassword)
            } else false
            syncResult.copy(
                checkpointAction = if (uploadedCheckpoint && checkpointAction == com.pengshi.words.sync.SyncCheckpointAction.NOT_CHECKED)
                    com.pengshi.words.sync.SyncCheckpointAction.CREATED else checkpointAction,
            )
        }.getOrElse { failure ->
            val message = "failure=${failure::class.java.simpleName}: ${failure.message?.take(240) ?: "no message"}"
            System.err.println("PengshiWords sync $message")
            appendSyncDiagnostic(message)
            SyncResult(SyncStatus.FAILED, 0, 0, 0)
        }.also { result ->
            appendSyncDiagnostic(
                "status=${result.status},received=${result.receivedEvents},applied=${result.appliedEvents}," +
                    "waiting=${result.deferredEvents},conflicts=${result.conflictEvents},revision=${result.remoteRevision ?: ""}",
            )
        }
    }

    private fun appendSyncDiagnostic(message: String) {
        runCatching {
            Files.writeString(
                dataDirectory.resolve("pengshi-sync.log"),
                "${Instant.now()} $message${System.lineSeparator()}",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
        }
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
        val localSummary = localSnapshot.summary()
        val remoteSummary = remotePreview.snapshot.summary()
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
                    restoreRemoteLearningSnapshot(localSnapshot, remotePreview.snapshot)
                    syncStore.restoreCheckpointEvents(decodedCheckpoint.appliedEvents, decodedCheckpoint.metadata.revision)
                    remotePreview.settings?.let { applyRemoteSettings(it, includeDeviceVoice = false) }
                    restoreSyncedStatsStart(remotePreview.statsStartDate, remotePreview.snapshot)
                    seedLoader.reconcileBundledSourcesAfterRemoteSnapshot()
                    return com.pengshi.words.sync.SyncCheckpointAction.RESTORED_EMPTY_DEVICE
                }
                CheckpointDecision.MERGE_CONTENT_ONLY -> {
                    snapshotGateway.mergeContent(remotePreview.snapshot)
                    seedLoader.reconcileBundledSourcesAfterRemoteSnapshot()
                    return com.pengshi.words.sync.SyncCheckpointAction.MERGED_CONTENT_ONLY
                }
                CheckpointDecision.MERGE_INCREMENTAL -> {
                    if (PersonalSnapshotSync.shouldMergeRemoteContent(localSummary, remoteSummary)) {
                        snapshotGateway.mergeContent(remotePreview.snapshot)
                        seedLoader.reconcileBundledSourcesAfterRemoteSnapshot()
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
                restoreRemoteLearningSnapshot(localSnapshot, remotePreview.snapshot)
                remotePreview.settings?.let { applyRemoteSettings(it, includeDeviceVoice = false) }
                restoreSyncedStatsStart(remotePreview.statsStartDate, remotePreview.snapshot)
                seedLoader.reconcileBundledSourcesAfterRemoteSnapshot()
                return com.pengshi.words.sync.SyncCheckpointAction.RESTORED_EMPTY_DEVICE
            }
            if (PersonalSnapshotSync.shouldMergeRemoteContent(localSummary, remoteSummary)) {
                snapshotGateway.mergeContent(remotePreview.snapshot)
                seedLoader.reconcileBundledSourcesAfterRemoteSnapshot()
                return com.pengshi.words.sync.SyncCheckpointAction.MERGED_CONTENT_ONLY
            }
            return com.pengshi.words.sync.SyncCheckpointAction.KEPT_LOCAL
        }
    }

    private suspend fun restoreRemoteLearningSnapshot(local: StudyDataSnapshot, remote: StudyDataSnapshot) {
        val output = ByteArrayOutputStream()
        JsonBackupWriter(
            gateway = snapshotGateway,
            statsStartDateProvider = { statsStartDate() },
            settingsProvider = { backupSettings() },
        ).write(output)
        Files.write(dataDirectory.resolve("pengshi-pre-sync-recovery.backup.json"), output.toByteArray())
        try {
            snapshotGateway.replace(remote)
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

    private suspend fun uploadCurrentBootstrapIfChanged(remote: GitHubRemoteStore, password: String): Boolean {
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
            JsonBackupReader().read(
                (remoteCheckpoint?.snapshotBytes
                    ?: PersonalSnapshotSync.decrypt(password, it.encryptedBytes)).inputStream(),
            )
        }
        if (remoteBootstrap != null) {
            val remoteSnapshot = remotePreview?.snapshot
            val remoteSettings = remotePreview?.settings
            if (remoteSnapshot == localSnapshot &&
                remoteSettings == backupSettings().copy(selectedVoiceKey = null) &&
                remotePreview?.statsStartDate == statsStartDate()
            ) return false
        }
        val output = ByteArrayOutputStream()
        JsonBackupWriter(
            gateway = snapshotGateway,
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
        deviceId = syncStore.deviceId,
    )

    private fun readPreferredMode(): StudyMode = if (Files.exists(modePath)) {
        runCatching { StudyMode.valueOf(Files.readString(modePath).trim()) }.getOrDefault(defaultMode)
    } else defaultMode

    private fun readFavoriteIds(): Set<Long> = if (Files.exists(favoritePath)) {
        Files.readAllLines(favoritePath).mapNotNull(String::toLongOrNull).toSet()
    } else emptySet()

    private fun backupSettings(): BackupSettingsSnapshot = savedLearningSettings.copy(
        defaultMode = preferredMode.name,
        speechRate = savedSpeechRate,
        selectedVoiceKey = speech.selectedVoiceKey,
        includedDeckIds = wordPoolStore.load().includedDeckIds,
        deckWeights = wordPoolStore.load().deckWeights,
        excludedWordIds = wordPoolStore.load().excludedWordIds,
        favoriteWordIds = synchronized(favoriteIds) { favoriteIds.toSet() },
    )

    fun statsStartDate(): LocalDate {
        if (Files.isRegularFile(statsStartPath)) {
            runCatching { LocalDate.parse(Files.readString(statsStartPath).trim()) }
                .getOrNull()
                ?.let { return it }
        }
        val start = LocalDate.now()
        saveStatsStartDate(start)
        return start
    }

    private fun saveStatsStartDate(date: LocalDate) {
        Files.writeString(
            statsStartPath,
            date.toString(),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
        )
    }

    suspend fun addNewWords(
        count: Int,
        localDate: LocalDate = LocalDate.now(),
        now: Instant = Instant.now(),
        mode: StudyMode = preferredMode,
    ): StudySessionState = operationMutex.withLock { addNewWordsUseCase.addNewWords(localDate, now, mode, count) }

    suspend fun manualWordCatalog(
        localDate: LocalDate = LocalDate.now(),
        now: Instant = Instant.now(),
        mode: StudyMode = preferredMode,
    ): ManualWordCatalog = operationMutex.withLock {
        val plan = repository.getPlan(localDate)
        val items = plan?.let { repository.getItems(it.id) }.orEmpty()
        val candidateIds = repository.getNewCandidates(mode, now).mapTo(hashSetOf()) { it.wordId }
        snapshotGateway.snapshot().manualWordCatalog(
            candidateIds,
            items.mapTo(hashSetOf()) { it.wordId },
            (plan?.quota ?: 30) - items.count { it.source != PlanSource.EXTRA },
        )
    }

    suspend fun addSelectedNewWords(
        wordIds: List<Long>,
        localDate: LocalDate = LocalDate.now(),
        now: Instant = Instant.now(),
        mode: StudyMode = preferredMode,
    ): StudySessionState = operationMutex.withLock { addNewWordsUseCase.addSelectedNewWords(localDate, now, mode, wordIds) }

    suspend fun appendExtraWords(
        count: Int,
        localDate: LocalDate = LocalDate.now(),
        now: Instant = Instant.now(),
        mode: StudyMode = preferredMode,
        sourceDeckId: Long? = null,
    ) = operationMutex.withLock { appendExtraWordsUseCase.append(localDate, now, mode, count, sourceDeckId) }

    suspend fun addWordToToday(
        wordId: Long,
        localDate: LocalDate = LocalDate.now(),
        now: Instant = Instant.now(),
        mode: StudyMode = preferredMode,
    ): StudySessionState = operationMutex.withLock { addWordToTodayUseCase.addWordToToday(localDate, now, mode, wordId) }

    suspend fun importWords(input: InputStream, format: ImportFormat): ImportResult =
        importRepository.import(input, format)

    suspend fun importWordsIntoUserDeck(
        input: InputStream,
        format: ImportFormat,
        deckId: Long,
        sources: BatchImportSources = BatchImportSources("", ""),
    ): UserDeckBulkResult = importRepository.importIntoUserDeck(input, format, deckId, sources)

    suspend fun snapshot(): StudyDataSnapshot = snapshotGateway.snapshot()

    override fun close() {
        speech.release()
        repository.close()
    }

    companion object {
        fun open(path: Path): DesktopContainer {
            val properties = loadProperties()
            val absolutePath = path.toAbsolutePath().normalize()
            LegacyAiConfigCleaner.remove(absolutePath.parent ?: absolutePath, absolutePath.fileName.toString())
            return DesktopContainer(
                database = DesktopSqliteDatabase.open(absolutePath),
                dataDirectory = absolutePath.parent ?: absolutePath.toAbsolutePath().parent,
                defaultMode = properties.defaultMode(),
                favoritePath = absolutePath.resolveSibling("${absolutePath.fileName}.favorites"),
                modePath = absolutePath.resolveSibling("${absolutePath.fileName}.mode"),
                speechSettingsPath = absolutePath.resolveSibling("${absolutePath.fileName}.speech.properties"),
                syncSettingsPath = absolutePath.resolveSibling("${absolutePath.fileName}.sync.properties"),
                personalSettingsPath = absolutePath.resolveSibling("${absolutePath.fileName}.personal.properties"),
                wordPoolPath = absolutePath.resolveSibling("${absolutePath.fileName}.word-pool.properties"),
                statsStartPath = absolutePath.resolveSibling("${absolutePath.fileName}.stats-start"),
                deviceIdPath = absolutePath.resolveSibling("${absolutePath.fileName}.device-id"),
                seedResource = properties.getProperty(SEED_RESOURCE_KEY, DEFAULT_SEED_RESOURCE),
                appSettingsStore = null,
                legacySyncSettingsPaths = emptyList(),
            )
        }

        fun open(): DesktopContainer {
            val properties = loadProperties()
            val dataDirectoryOverride = System.getenv(DATA_DIRECTORY_ENV_VAR)
                ?.takeIf(String::isNotBlank)
                ?.let(Paths::get)
                ?.toAbsolutePath()
                ?.normalize()
            val dataDirectoryName = properties.getProperty(DATA_DIRECTORY_KEY, DEFAULT_DATA_DIRECTORY)
            val databaseFileName = properties.getProperty(DATABASE_FILE_KEY, DEFAULT_DATABASE_FILE)
            val localDataRoot = System.getenv("LOCALAPPDATA")
                ?.takeIf(String::isNotBlank)
                ?.let(Paths::get)
                ?: Paths.get(System.getProperty("user.home"))
            val appConfigDirectory = localDataRoot.resolve(APP_CONFIG_DIRECTORY)
            val appSettingsStore = DesktopAppSettingsStore(appConfigDirectory.resolve(APP_SETTINGS_FILE))
            val selectedDataDirectory = appSettingsStore.dataDirectory()
            val legacyDataDirectory = localDataRoot.resolve(dataDirectoryName)
            val configuredRepositoryContainer = if (dataDirectoryOverride == null && selectedDataDirectory == null) {
                properties.getProperty(REPOSITORY_DIRECTORY_KEY)
                    ?.takeIf(String::isNotBlank)
                    ?.let(Paths::get)
            } else {
                null
            }
            val repositoryDirectory = configuredRepositoryContainer
                ?.takeIf(Files::isDirectory)
                ?.toAbsolutePath()
                ?.normalize()
                ?.also { DesktopRepositoryLocation.migrateLegacyFiles(legacyDataDirectory, it, databaseFileName) }
            val dataDirectory = dataDirectoryOverride ?: selectedDataDirectory ?: repositoryDirectory ?: legacyDataDirectory
            Files.createDirectories(dataDirectory)
            LegacyAiConfigCleaner.remove(dataDirectory, databaseFileName)
            return DesktopContainer(
                database = DesktopSqliteDatabase.open(dataDirectory.resolve(databaseFileName)),
                dataDirectory = dataDirectory,
                defaultMode = properties.defaultMode(),
                favoritePath = dataDirectory.resolve("$databaseFileName.favorites"),
                modePath = dataDirectory.resolve("$databaseFileName.mode"),
                speechSettingsPath = dataDirectory.resolve("$databaseFileName.speech.properties"),
                syncSettingsPath = appConfigDirectory.resolve(SYNC_SETTINGS_FILE),
                personalSettingsPath = dataDirectory.resolve("$databaseFileName.personal.properties"),
                wordPoolPath = dataDirectory.resolve("$databaseFileName.word-pool.properties"),
                statsStartPath = dataDirectory.resolve("$databaseFileName.stats-start"),
                deviceIdPath = dataDirectory.resolve("$databaseFileName.device-id"),
                seedResource = properties.getProperty(SEED_RESOURCE_KEY, DEFAULT_SEED_RESOURCE),
                appSettingsStore = appSettingsStore,
                legacySyncSettingsPaths = listOf(
                    dataDirectory.resolve("$databaseFileName.sync.properties"),
                    localDataRoot.resolve(LEGACY_DATA_DIRECTORY).resolve("$databaseFileName.sync.properties"),
                ).distinct(),
            )
        }

        private fun loadProperties(): Properties = Properties().apply {
            DesktopContainer::class.java.getResourceAsStream("/desktop.properties")?.use { input ->
                InputStreamReader(input, StandardCharsets.UTF_8).use(::load)
            }
        }

        internal fun loadPropertiesForTest(): Properties = loadProperties()

        private fun Properties.defaultMode(): StudyMode = StudyMode.valueOf(
            getProperty(DEFAULT_MODE_KEY, StudyMode.EN_TO_CN.name),
        )

        private const val DATA_DIRECTORY_KEY = "data.directory"
        private const val DATA_DIRECTORY_ENV_VAR = "PENGSHI_WORDS_DATA_DIR"
        private const val APP_CONFIG_DIRECTORY = "PengshiWordsOpenSource/config"
        private const val APP_SETTINGS_FILE = "desktop.properties"
        private const val SYNC_SETTINGS_FILE = "github-sync.properties"
        private const val LEGACY_DATA_DIRECTORY = "PengshiWords"
        private const val DATABASE_FILE_KEY = "database.file"
        private const val REPOSITORY_DIRECTORY_KEY = "repository.directory"
        private const val SEED_RESOURCE_KEY = "seed.cet6.resource"
        private const val DEFAULT_MODE_KEY = "study.defaultMode"
        private const val DEFAULT_DATA_DIRECTORY = "PengshiWordsOpenSource"
        private const val DEFAULT_DATABASE_FILE = "pengshi-words.db"
        private const val DEFAULT_SEED_RESOURCE = "/wordpacks/cet6/words.csv"
    }

}

private fun StudyDataSnapshot.summary(): SnapshotSummary = SnapshotSummary(
    wordCount = words.size,
    cardStateCount = cardStates.size,
    reviewLogCount = reviewLogs.size,
    dailyPlanCount = dailyPlans.size,
    reviewedCardCount = cardStates.count { it.lastReviewedAt != null || it.repetitions > 0 },
    completedPlanCount = dailyPlans.count { it.status == com.pengshi.words.model.DailyPlanStatus.COMPLETED },
    userDeckCount = decks.count { it.sourceType != DeckSourceType.BUILTIN },
    userDeckWordCount = deckWords.count { link -> decks.any { it.id == link.deckId && it.sourceType != DeckSourceType.BUILTIN } },
    exampleSentenceCount = exampleSentences.size,
    contentDigest = PersonalSnapshotSync.contentDigest(this),
)
