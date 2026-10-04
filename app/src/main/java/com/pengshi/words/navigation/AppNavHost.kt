package com.pengshi.words.navigation

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.foundation.Image
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.res.painterResource
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.pengshi.words.R
import com.pengshi.words.startup.AppStartupState
import com.pengshi.words.model.StartupWordFieldData
import com.pengshi.words.model.StartupWordPoint
import com.pengshi.words.feature.decks.DecksScreen
import com.pengshi.words.feature.decks.DeckSummary
import com.pengshi.words.feature.decks.WordListItemUi
import com.pengshi.words.feature.decks.SelectionDashboardScreen
import com.pengshi.words.feature.decks.SelectionDashboardUiState
import com.pengshi.words.feature.home.HomeScreen
import com.pengshi.words.feature.home.HomeUiState
import com.pengshi.words.feature.home.HomeStudyPhase
import com.pengshi.words.feature.home.NewWordChoiceDialog
import com.pengshi.words.feature.home.ManualNewWordDialog
import com.pengshi.words.feature.settings.SettingsScreen
import com.pengshi.words.feature.settings.SettingsAction
import com.pengshi.words.feature.settings.SettingsUiState
import com.pengshi.words.feature.stats.StatsScreen
import com.pengshi.words.feature.stats.StatsUiState
import com.pengshi.words.feature.stats.StatsTab
import com.pengshi.words.feature.study.StudyScreen
import com.pengshi.words.feature.study.StudyScreenState
import com.pengshi.words.feature.study.StudyAction
import com.pengshi.words.model.StudyMode
import com.pengshi.words.model.PersonalWordInput
import com.pengshi.words.model.BatchImportSources
import com.pengshi.words.model.WordPoolSelection
import com.pengshi.words.model.ManualWordCatalog
import com.pengshi.words.speech.SpeechVoiceOption

internal enum class AppDestination(val label: String) {
    HOME("首页"),
    DECKS("词库"),
    SELECTION_DASHBOARD("选词"),
    STATS("统计"),
    SETTINGS("设置"),
    STUDY("学习"),
}

internal sealed interface StudyDestinationContent {
    data class Loading(val message: String) : StudyDestinationContent
    data class Ready(val state: StudyScreenState) : StudyDestinationContent
    data object Empty : StudyDestinationContent
}

internal fun resolveStudyDestinationContent(
    activeState: StudyScreenState?,
    awaitingSession: Boolean,
    transitionMessage: String?,
): StudyDestinationContent = when {
    transitionMessage != null -> StudyDestinationContent.Loading(transitionMessage)
    activeState != null -> StudyDestinationContent.Ready(activeState)
    awaitingSession -> StudyDestinationContent.Loading("正在准备今日学习…")
    else -> StudyDestinationContent.Empty
}

@Composable
fun AppNavHost(
    homeState: HomeUiState = HomeUiState(),
    syncInProgress: Boolean = false,
    syncFeedbackMessage: String? = null,
    onDismissSyncFeedback: () -> Unit = {},
    statsState: StatsUiState = StatsUiState(),
    statsTab: StatsTab = StatsTab.RETENTION,
    wordList: List<WordListItemUi> = emptyList(),
    deckWords: List<WordListItemUi> = emptyList(),
    deckSummaries: List<DeckSummary> = emptyList(),
    isLoadingDecks: Boolean = false,
    isLoadingDeckWords: Boolean = false,
    selectionDashboardState: SelectionDashboardUiState = SelectionDashboardUiState(),
    settingsState: SettingsUiState = SettingsUiState(),
    studyState: StudyScreenState? = null,
    studyTransitionMessage: String? = null,
    onStartStudy: (StudyMode) -> Unit = {},
    onAddNewWords: (Int, StudyMode) -> Unit = { _, _ -> },
    onLoadManualWordCatalog: suspend (StudyMode) -> ManualWordCatalog = { ManualWordCatalog(emptyList(), emptyList(), 0) },
    onAddSelectedNewWords: (List<Long>, StudyMode) -> Unit = { _, _ -> },
    newWordChoiceRequest: Int = 0,
    studyCompletionRequest: Int = 0,
    onAppendExtraWords: (Int, Long, (Int) -> Unit) -> Unit = { _, _, result -> result(0) },
    onToggleExcludedWord: (Long) -> Unit = {},
    createDeckMessage: String? = null,
    onCreateUserDeck: (String) -> Unit = {},
    onDeleteUserDeck: (Long) -> Unit = {},
    addWordMessage: String? = null,
    onAddWordToDeck: (Long, PersonalWordInput) -> Unit = { _, _ -> },
    onUpdateWordInDeck: (Long, Long, PersonalWordInput) -> Unit = { _, _, _ -> },
    onDeleteWordFromDeck: (Long, Long) -> Unit = { _, _ -> },
    onBulkAddWordsToDeck: (Long, String, BatchImportSources) -> Unit = { _, _, _ -> },
    onImportWordsToDeck: (Long, Uri, BatchImportSources) -> Unit = { _, _, _ -> },
    onOpenDeck: (Long) -> Unit = {},
    onSaveSelection: (WordPoolSelection) -> Unit = {},
    manualExampleMessage: String? = null,
    onAddExample: (Long, String, String?, String) -> Unit = { _, _, _, _ -> },
    onUpdateExample: (Long, String, String?, String) -> Unit = { _, _, _, _ -> },
    onDeleteExample: (Long) -> Unit = {},
    speechVoices: List<SpeechVoiceOption> = emptyList(),
    onSpeakWordWithVoice: (String, SpeechVoiceOption) -> Unit = { _, _ -> },
    onStatsQueryChange: (java.time.LocalDate, com.pengshi.words.feature.stats.StatsPeriod) -> Unit = { _, _ -> },
    onStatsEntered: () -> Unit = {},
    onHomeEntered: () -> Unit = {},
    onDecksEntered: () -> Unit = {},
    onSelectionDashboardEntered: () -> Unit = {},
    onStudySearchOpened: () -> Unit = {},
    onStatsTabChange: (StatsTab) -> Unit = {},
    onSettingsAction: (SettingsAction) -> Unit = {},
    onImportWordList: (Uri) -> Unit = {},
    onExportBackup: (Uri) -> Unit = {},
    onRestoreBackup: (Uri) -> Unit = {},
    onSaveGitHubSync: (owner: String, repository: String, branch: String, password: String, token: String) -> Unit = { _, _, _, _, _ -> },
    onSyncNow: () -> Unit = {},
    syncSummary: String? = null,
    settingsMessage: String? = null,
    onStudyAction: (StudyAction) -> Unit = {},
    onAddWordToToday: (Long) -> Unit = {},
    onStudyBack: () -> Unit = {},
    onStudyExited: () -> Unit = {},
    canGoPreviousStudyWord: Boolean = false,
    onPreviousStudyWord: () -> Unit = {},
    canGoNextStudyWord: Boolean = false,
    onNextStudyWord: () -> Unit = {},
    startupState: AppStartupState = AppStartupState.Loading,
    startupWordFieldData: StartupWordFieldData = StartupWordFieldData.Empty,
    onRetryStartup: () -> Unit = {},
    onContinueOfflineStartup: () -> Unit = {},
) {
    val startupStartedAt = remember { SystemClock.elapsedRealtime() }
    var startupElapsedMs by remember { mutableStateOf(0L) }
    LaunchedEffect(startupStartedAt) {
        while (startupElapsedMs < 10_000L) {
            delay(100)
            startupElapsedMs = SystemClock.elapsedRealtime() - startupStartedAt
        }
    }
    when (val state = startupState) {
        AppStartupState.Loading, AppStartupState.Syncing -> {
            if (startupElapsedMs < 10_000L) StartupLoadingScreen(startupWordFieldData)
            else StartupPendingScreen()
            return
        }
        is AppStartupState.Failed -> {
            StartupFailedScreen(state.message, state.canContinueOffline, onRetryStartup, onContinueOfflineStartup)
            return
        }
        is AppStartupState.Ready -> if (startupElapsedMs < 4_000L) {
            StartupLoadingScreen(startupWordFieldData)
            return
        }
    }
    var destination by rememberSaveable { mutableStateOf(AppDestination.HOME.name) }
    var deckScreenResetToken by rememberSaveable { mutableStateOf(0) }
    var activeStudyState by remember { mutableStateOf(studyState) }
    var awaitingStudySession by rememberSaveable { mutableStateOf(false) }
    var observedStudyTransition by rememberSaveable { mutableStateOf(false) }
    val selected = AppDestination.valueOf(destination)
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    var lastHomeBackAt by rememberSaveable { mutableStateOf(-1L) }
    var previousDestination by rememberSaveable { mutableStateOf("") }
    var showNewWordChoice by remember { mutableStateOf(false) }
    var showStudyCompletion by remember { mutableStateOf(false) }
    var manualCatalog by remember { mutableStateOf<ManualWordCatalog?>(null) }
    var selectedManualIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var manualLoadError by remember { mutableStateOf<String?>(null) }
    var selectionStudyMode by rememberSaveable { mutableStateOf(StudyMode.EN_TO_CN.name) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(newWordChoiceRequest) {
        if (newWordChoiceRequest > 0) showNewWordChoice = true
    }
    LaunchedEffect(studyCompletionRequest) {
        if (studyCompletionRequest > 0) showStudyCompletion = true
    }
    LaunchedEffect(selected) {
        val was = runCatching { AppDestination.valueOf(previousDestination) }.getOrNull()
        if (selected != was) {
            if (AppBackPolicy.shouldSyncAfterDestinationChange(was, selected) &&
                homeState.phase != HomeStudyPhase.CHOOSE_NEW && !showNewWordChoice && manualCatalog == null
            ) onStudyExited()
            when (selected) {
                AppDestination.HOME -> if (was != null) onHomeEntered()
                AppDestination.STATS -> onStatsEntered()
                AppDestination.DECKS -> onDecksEntered()
                AppDestination.SELECTION_DASHBOARD -> onSelectionDashboardEntered()
                else -> Unit
            }
        }
        previousDestination = selected.name
    }
    LaunchedEffect(syncInProgress, syncFeedbackMessage) {
        if (!syncInProgress && syncFeedbackMessage != null) {
            delay(4_000)
            onDismissSyncFeedback()
        }
    }
    androidx.compose.runtime.LaunchedEffect(studyState, studyTransitionMessage, selected) {
        if (selected != AppDestination.STUDY) return@LaunchedEffect
        if (studyTransitionMessage != null) observedStudyTransition = true
        if (studyState != null) {
            activeStudyState = studyState
            if (studyTransitionMessage == null) {
                awaitingStudySession = false
                observedStudyTransition = false
            }
        } else if (studyTransitionMessage == null && (!awaitingStudySession || observedStudyTransition)) {
            activeStudyState = null
            awaitingStudySession = false
            observedStudyTransition = false
            destination = AppDestination.HOME.name
        }
    }
    BackHandler {
        val parent = AppBackPolicy.parentOf(selected)
        if (parent != null) {
            when (selected) {
                AppDestination.STUDY -> {
                    onStudyAction(StudyAction.StopSpeech)
                    onStudyBack()
                }
                AppDestination.SELECTION_DASHBOARD -> deckScreenResetToken += 1
                else -> Unit
            }
            destination = parent.name
        } else {
            val now = SystemClock.elapsedRealtime()
            if (AppBackPolicy.shouldExitAtHome(now, lastHomeBackAt)) {
                lastHomeBackAt = -1L
                activity?.finish()
            } else {
                lastHomeBackAt = now
                Toast.makeText(context, "再点一次退出背单词", Toast.LENGTH_SHORT).show()
            }
        }
    }
    Scaffold(
        bottomBar = {
            if (selected != AppDestination.STUDY) {
                NavigationBar {
                    listOf(AppDestination.HOME, AppDestination.DECKS, AppDestination.STATS, AppDestination.SETTINGS).forEach { item ->
                        NavigationBarItem(
                            selected = selected == item || (selected == AppDestination.SELECTION_DASHBOARD && item == AppDestination.DECKS),
                            onClick = {
                                if (item == AppDestination.DECKS) deckScreenResetToken += 1
                                destination = item.name
                            },
                            icon = { Text(item.label.take(1)) },
                            label = { Text(item.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (selected) {
            AppDestination.HOME -> HomeScreen(
                state = homeState,
                decks = deckSummaries,
                onStart = {
                    selectionStudyMode = it.name
                    if (homeState.phase == HomeStudyPhase.CHOOSE_NEW) {
                        showNewWordChoice = true
                    } else {
                        awaitingStudySession = studyState == null
                        activeStudyState = studyState
                        onStartStudy(it)
                        destination = AppDestination.STUDY.name
                    }
                },
                onAddNewWords = {
                    if (homeState.phase == HomeStudyPhase.CHOOSE_NEW) showNewWordChoice = true
                    else {
                        awaitingStudySession = true
                        activeStudyState = null
                        onAddNewWords(it, StudyMode.valueOf(selectionStudyMode))
                        destination = AppDestination.STUDY.name
                    }
                },
                onAppendExtraWords = { count, deckId, onResult ->
                    onAppendExtraWords(count, deckId) { appended ->
                        if (appended > 0) {
                            awaitingStudySession = true
                            activeStudyState = null
                            destination = AppDestination.STUDY.name
                        }
                        onResult(appended)
                    }
                },
            )
            AppDestination.DECKS -> DecksScreen(
                resetToken = deckScreenResetToken,
                decks = deckSummaries,
                words = deckWords,
                isLoadingDecks = isLoadingDecks,
                isLoadingWords = isLoadingDeckWords,
                createDeckMessage = createDeckMessage,
                onCreateUserDeck = onCreateUserDeck,
                onDeleteUserDeck = onDeleteUserDeck,
                addWordMessage = addWordMessage,
                onAddWordToDeck = onAddWordToDeck,
                onUpdateWordInDeck = onUpdateWordInDeck,
                onDeleteWordFromDeck = onDeleteWordFromDeck,
                onBulkAddWordsToDeck = onBulkAddWordsToDeck,
                onImportWordsToDeck = onImportWordsToDeck,
                onOpenDeck = onOpenDeck,
                onAddWordToToday = onAddWordToToday,
                onOpenSelectionDashboard = { destination = AppDestination.SELECTION_DASHBOARD.name },
                manualExampleMessage = manualExampleMessage,
                onAddExample = onAddExample,
                onUpdateExample = onUpdateExample,
                onDeleteExample = onDeleteExample,
                speechVoices = speechVoices,
                onSpeakWordWithVoice = onSpeakWordWithVoice,
            )
            AppDestination.SELECTION_DASHBOARD -> SelectionDashboardScreen(
                state = selectionDashboardState,
                onBack = {
                    deckScreenResetToken += 1
                    destination = AppDestination.DECKS.name
                },
                onToggleExcludedWord = onToggleExcludedWord,
                onSaveSelection = onSaveSelection,
            )
            AppDestination.STATS -> StatsScreen(
                state = statsState,
                onQueryChange = onStatsQueryChange,
                initialTab = statsTab,
                onTabChange = onStatsTabChange,
            )
            AppDestination.SETTINGS -> SettingsScreen(
                state = settingsState,
                onAction = onSettingsAction,
                onImportWordList = onImportWordList,
                onExportBackup = onExportBackup,
                onRestoreBackup = onRestoreBackup,
                onSaveGitHubSync = onSaveGitHubSync,
                onSyncNow = onSyncNow,
                syncSummary = syncSummary,
                message = settingsMessage,
            )
            AppDestination.STUDY -> when (val content = resolveStudyDestinationContent(
                activeState = activeStudyState,
                awaitingSession = awaitingStudySession,
                transitionMessage = studyTransitionMessage,
            )) {
                is StudyDestinationContent.Loading -> StudyTransitionLoadingScreen(content.message)
                is StudyDestinationContent.Ready -> StudyScreen(
                    state = content.state,
                    onAction = { action ->
                        if (action == StudyAction.RevealAnswer) {
                            activeStudyState = content.state.copy(isAnswerRevealed = true, feedbackEnabled = true)
                        }
                        onStudyAction(action)
                    },
                    onBack = { onStudyBack(); destination = AppDestination.HOME.name },
                    canGoPrevious = canGoPreviousStudyWord,
                    onPrevious = onPreviousStudyWord,
                    canGoNext = canGoNextStudyWord,
                    onNext = onNextStudyWord,
                    searchWords = wordList,
                    onSearchOpen = onStudySearchOpened,
                    onAddWordToToday = onAddWordToToday,
                    speechVoices = speechVoices,
                )
                StudyDestinationContent.Empty -> StudyTransitionLoadingScreen("正在结束本次学习…")
            }
            }
            if (selected == AppDestination.SETTINGS && (syncInProgress || syncFeedbackMessage != null)) {
                SyncFeedbackBanner(
                    syncInProgress = syncInProgress,
                    message = syncFeedbackMessage,
                    modifier = Modifier.align(Alignment.TopCenter).padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }
    }
    if (showNewWordChoice) NewWordChoiceDialog(
        remaining = (homeState.quota - homeState.reviewPlanned - homeState.newPlanned).coerceAtLeast(0),
        onAutomatic = {
            showNewWordChoice = false
            awaitingStudySession = true
            activeStudyState = null
            onAddNewWords((homeState.quota - homeState.reviewPlanned - homeState.newPlanned).coerceAtLeast(1), StudyMode.valueOf(selectionStudyMode))
            destination = AppDestination.STUDY.name
        },
        onManual = {
            showNewWordChoice = false
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { onLoadManualWordCatalog(StudyMode.valueOf(selectionStudyMode)) } }
                    .onSuccess { catalog ->
                        val validIds = catalog.words.mapTo(hashSetOf()) { it.word.id }
                        selectedManualIds = selectedManualIds.filterTo(linkedSetOf()) { it in validIds }
                        manualCatalog = catalog
                    }
                    .onFailure { manualLoadError = it.message ?: "无法加载候选词" }
            }
        },
        onLater = { showNewWordChoice = false },
    )
    manualCatalog?.let { catalog ->
        ManualNewWordDialog(
            catalog = catalog,
            selectedIds = selectedManualIds,
            onToggle = { id ->
                selectedManualIds = if (id in selectedManualIds) selectedManualIds - id
                else if (selectedManualIds.size < catalog.selectionLimit) selectedManualIds + id else selectedManualIds
            },
            onConfirm = {
                val ids = catalog.words.map { it.word.id }.filter { it in selectedManualIds }
                manualCatalog = null
                selectedManualIds = emptySet()
                awaitingStudySession = true
                activeStudyState = null
                onAddSelectedNewWords(ids, StudyMode.valueOf(selectionStudyMode))
                destination = AppDestination.STUDY.name
            },
            onDismiss = { manualCatalog = null },
        )
    }
    manualLoadError?.let { error ->
        AlertDialog(onDismissRequest = { manualLoadError = null }, title = { Text("选词未完成") },
            text = { Text(error) }, confirmButton = { TextButton(onClick = { manualLoadError = null; showNewWordChoice = true }) { Text("返回选词") } })
    }
    if (showStudyCompletion) AlertDialog(
        onDismissRequest = { showStudyCompletion = false },
        title = { Text("本轮学习完成！") },
        text = { Text(if (homeState.phase == HomeStudyPhase.CHOOSE_NEW)
            "本轮选出的新词已经学完，辛苦了。今天还可以继续挑选新词，休息一下再开始也可以。"
        else "今天已经完成 ${homeState.completedUniqueWordCount} 个词，辛苦了。可以在首页查看进度，明天继续保持。") },
        confirmButton = { TextButton(onClick = { showStudyCompletion = false }) { Text("返回首页") } },
    )
}

@Composable
private fun StartupPendingScreen() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.size(16.dp))
        Text("正在准备本地词库，请稍候…", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SyncFeedbackBanner(
    syncInProgress: Boolean,
    message: String?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        shadowElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (syncInProgress) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                )
                Spacer(modifier = Modifier.width(12.dp))
            }
            Text(
                text = if (syncInProgress) "正在同步 GitHub 数据…" else message.orEmpty(),
                color = MaterialTheme.colorScheme.inverseOnSurface,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun StudyTransitionLoadingScreen(message: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Text(message, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 18.dp))
        Text(
            "系统会根据已保存的词库范围和比例生成下一阶段单词。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun StartupLoadingScreen(wordFieldData: StartupWordFieldData) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val points = remember(wordFieldData, canvasSize) {
        wordFieldData.layout(canvasSize.width.toFloat(), canvasSize.height.toFloat())
    }
    val unreviewedPoints = remember(points) {
        points.filterNot { it.hasCompletedFeedback }
    }
    val reviewedPoints = remember(points) {
        points.filter { it.hasCompletedFeedback }
    }
    val reveal = remember { Animatable(.14f) }
    LaunchedEffect(wordFieldData, canvasSize) {
        reveal.snapTo(.14f)
        reveal.animateTo(1f, tween(1_800, easing = FastOutSlowInEasing))
    }
    val logoScale by rememberInfiniteTransition(label = "startup-logo-breath").animateFloat(
        initialValue = .985f,
        targetValue = 1.015f,
        animationSpec = infiniteRepeatable(
            tween(2_200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "startup-logo-scale",
    )
    val motion = rememberInfiniteTransition(label = "startup-word-field-motion")
    val motionSeconds by motion.animateFloat(0f, 120f,
        infiniteRepeatable(tween(120_000, easing = LinearEasing)), label = "startup-point-motion")
    val glow by motion.animateFloat(.35f, .95f,
        infiniteRepeatable(tween(1_600, easing = FastOutSlowInEasing), repeatMode = RepeatMode.Reverse),
        label = "startup-title-glow")

    Box(Modifier.fillMaxSize().background(Color(0xFF07151B))) {
        Canvas(
            Modifier.fillMaxSize()
                .onSizeChanged { canvasSize = it }
                .graphicsLayer {
                    val scale = .36f + .64f * reveal.value
                    scaleX = scale
                    scaleY = scale
                    alpha = .2f + .8f * reveal.value
                },
        ) {
            if (unreviewedPoints.isNotEmpty()) {
                drawPoints(
                    points = unreviewedPoints.map { point ->
                        val (x, y) = point.positionAt(motionSeconds)
                        Offset(x, y)
                    },
                    pointMode = PointMode.Points,
                    color = Color(0xFF78969D).copy(alpha = .64f),
                    strokeWidth = 1.55.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
            if (reviewedPoints.isNotEmpty()) {
                drawPoints(
                    points = reviewedPoints.map { point ->
                        val (x, y) = point.positionAt(motionSeconds)
                        Offset(x, y)
                    },
                    pointMode = PointMode.Points,
                    color = Color(0xFF63E3CC).copy(alpha = .96f),
                    strokeWidth = 2.9.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }
        StartupPointerRipple(points)
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.pengshi_logo),
                contentDescription = "彭式背单词",
                colorFilter = ColorFilter.tint(Color(0xFFC9ECE3), BlendMode.SrcIn),
                modifier = Modifier.size(176.dp).graphicsLayer {
                    scaleX = logoScale
                    scaleY = logoScale
                },
            )
            Text(
                text = "彭式背单词",
                color = Color(0xFFF0F7F6),
                fontSize = 38.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
                style = TextStyle(shadow = Shadow(Color(0xFF70E8D8).copy(alpha = glow), blurRadius = 24f)),
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                text = "记一词，志更远",
                color = Color(0xFFA7C0C1),
                fontSize = 21.sp,
                letterSpacing = 2.8.sp,
                style = TextStyle(shadow = Shadow(Color(0xFF70E8D8).copy(alpha = glow * .65f), blurRadius = 15f)),
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun StartupPointerRipple(points: List<StartupWordPoint>) {
    val density = LocalDensity.current
    val radiusPx = with(density) { 132.dp.toPx() }
    val cellSizePx = with(density) { 92.dp.toPx() }
    val grid = remember(points, cellSizePx) {
        points.groupBy { (it.xPx / cellSizePx).toInt() to (it.yPx / cellSizePx).toInt() }
    }
    var cursor by remember { mutableStateOf<Offset?>(null) }
    var nearby by remember { mutableStateOf<List<StartupWordPoint>>(emptyList()) }
    var interactionRevision by remember { mutableIntStateOf(0) }
    var waveRunning by remember { mutableStateOf(false) }
    val waveProgress = remember { Animatable(1f) }

    LaunchedEffect(interactionRevision) {
        if (cursor == null) return@LaunchedEffect
        val activeRevision = interactionRevision
        waveRunning = true
        try {
            waveProgress.snapTo(0f)
            waveProgress.animateTo(1f, tween(720, easing = FastOutSlowInEasing))
            if (interactionRevision == activeRevision) {
                cursor = null
                nearby = emptyList()
            }
        } finally {
            waveRunning = false
        }
    }

    Canvas(
        Modifier.fillMaxSize().pointerInput(grid, cellSizePx) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull() ?: continue
                    when (event.type) {
                        PointerEventType.Move,
                        PointerEventType.Enter,
                        PointerEventType.Press,
                        PointerEventType.Release,
                        -> {
                            val position = change.position
                            cursor = position
                            nearby = nearbyStartupPoints(grid, position, cellSizePx, radiusPx)
                            if (!waveRunning) interactionRevision += 1
                        }
                        PointerEventType.Exit -> {
                            cursor = null
                            nearby = emptyList()
                            interactionRevision += 1
                        }
                        else -> Unit
                    }
                }
            }
        },
    ) {
        val center = cursor ?: return@Canvas
        val progress = waveProgress.value
        val waveRadius = radiusPx * progress
        val waveBand = radiusPx * .16f
        nearby.forEach { point ->
            val dx = point.xPx - center.x
            val dy = point.yPx - center.y
            val distance = kotlin.math.sqrt(dx * dx + dy * dy)
            val strength = (1f - kotlin.math.abs(distance - waveRadius) / waveBand).coerceIn(0f, 1f)
            if (strength > 0f) {
                val drift = strength * (1f - progress) * 4.5.dp.toPx()
                val offset = if (distance > 0f) Offset(dx / distance * drift, dy / distance * drift) else Offset.Zero
                val color = if (point.hasCompletedFeedback) Color(0xFF8AF4E0) else Color(0xFFB5D6D8)
                drawCircle(
                    color = color.copy(alpha = strength * (1f - progress) * .92f),
                    radius = (if (point.hasCompletedFeedback) 2.7f else 1.65f).dp.toPx(),
                    center = Offset(point.xPx, point.yPx) + offset,
                )
            }
        }
    }
}

private fun nearbyStartupPoints(
    grid: Map<Pair<Int, Int>, List<StartupWordPoint>>,
    position: Offset,
    cellSizePx: Float,
    radiusPx: Float,
): List<StartupWordPoint> {
    val cellX = (position.x / cellSizePx).toInt()
    val cellY = (position.y / cellSizePx).toInt()
    val radiusSquared = radiusPx * radiusPx
    val nearby = ArrayList<StartupWordPoint>(64)
    for (x in cellX - 2..cellX + 2) {
        for (y in cellY - 2..cellY + 2) {
            for (point in grid[x to y].orEmpty()) {
                val dx = point.xPx - position.x
                val dy = point.yPx - position.y
                if (dx * dx + dy * dy <= radiusSquared) {
                    nearby += point
                    if (nearby.size >= 64) return nearby
                }
            }
        }
    }
    return nearby
}

@Composable
private fun StartupFailedScreen(
    message: String,
    canContinueOffline: Boolean,
    onRetry: () -> Unit,
    onContinueOffline: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("数据加载失败", style = MaterialTheme.typography.headlineSmall)
        Text(message, modifier = Modifier.padding(vertical = 12.dp))
        Button(onClick = onRetry) { Text("重试") }
        if (canContinueOffline) {
            Spacer(Modifier.size(8.dp))
            TextButton(onClick = onContinueOffline) { Text("离线进入") }
        }
    }
}

@Composable
private fun PlaceholderScreen(title: String, description: String) {
    androidx.compose.foundation.layout.Column(modifier = Modifier.padding(20.dp)) {
        Text(title, style = androidx.compose.material3.MaterialTheme.typography.headlineMedium)
        Text(description, modifier = Modifier.padding(top = 12.dp))
    }
}
