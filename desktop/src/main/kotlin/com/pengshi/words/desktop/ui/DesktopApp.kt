package com.pengshi.words.desktop.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.AlertDialog
import androidx.compose.material.TextButton
import androidx.compose.material.Button
import androidx.compose.material.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pengshi.words.desktop.DesktopContainer
import com.pengshi.words.desktop.DesktopDateState
import com.pengshi.words.desktop.DesktopHomeState
import com.pengshi.words.desktop.DesktopSelectionDashboardState
import com.pengshi.words.desktop.DesktopStudyPhase
import com.pengshi.words.desktop.DesktopWordDetails
import com.pengshi.words.desktop.desktopStudySpeechContent
import com.pengshi.words.desktop.formatImportResult
import com.pengshi.words.desktop.formatUserDeckBulkResult
import com.pengshi.words.desktop.retainCompletedStudyView
import com.pengshi.words.domain.StudySessionState
import com.pengshi.words.model.FeedbackUndoToken
import com.pengshi.words.model.ImportFormat
import com.pengshi.words.model.BatchImportSources
import com.pengshi.words.model.StudyDataSnapshot
import com.pengshi.words.model.StartupWordFieldData
import com.pengshi.words.model.StartupWordPoint
import com.pengshi.words.model.StudyMode
import com.pengshi.words.model.ManualWordCatalog
import com.pengshi.words.model.PlanSource
import com.pengshi.words.speech.SpeechTextPolicy
import com.pengshi.words.speech.SpeechModelDownloads
import com.pengshi.words.sync.userMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.time.LocalDate
import java.util.Locale
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

@Composable
private fun DesktopStartupScreen(
    state: DesktopStartupState,
    wordFieldData: StartupWordFieldData,
    onRetry: () -> Unit,
    onContinueOffline: () -> Unit,
) {
    when (state) {
        is DesktopStartupState.Failed -> Column(
            Modifier.fillMaxSize().background(DesktopPalette.canvas).padding(36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("彭式背单词", style = androidx.compose.material.MaterialTheme.typography.h4)
            Spacer(Modifier.size(18.dp))
            Text("同步或数据加载未完成", style = androidx.compose.material.MaterialTheme.typography.h6)
            Text(state.message, modifier = Modifier.padding(vertical = 12.dp))
            Button(onClick = onRetry) { Text("重试") }
            if (state.canContinueOffline) {
                OutlinedButton(onClick = onContinueOffline, modifier = Modifier.padding(top = 8.dp)) {
                    Text("离线进入")
                }
            }
        }
        DesktopStartupState.Loading, DesktopStartupState.Syncing -> DesktopStartupBrandAnimation(wordFieldData)
        DesktopStartupState.Ready -> Unit
    }
}

@Composable
private fun DesktopStartupBrandAnimation(wordFieldData: StartupWordFieldData) {
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
    val logoScale by rememberInfiniteTransition(label = "desktop-startup-logo-breath").animateFloat(
        initialValue = .985f,
        targetValue = 1.015f,
        animationSpec = infiniteRepeatable(
            tween(2_200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "desktop-startup-logo-scale",
    )
    val motion = rememberInfiniteTransition(label = "desktop-startup-word-field-motion")
    val motionSeconds by motion.animateFloat(0f, 120f,
        infiniteRepeatable(tween(120_000, easing = LinearEasing)), label = "desktop-startup-point-motion")
    val glow by motion.animateFloat(.35f, .95f,
        infiniteRepeatable(tween(1_600, easing = FastOutSlowInEasing), repeatMode = RepeatMode.Reverse),
        label = "desktop-startup-title-glow")
    val logo = remember {
        DesktopStartupCoordinator::class.java.getResourceAsStream("/pengshi_logo.png")?.use(::loadImageBitmap)
    }
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
        DesktopStartupPointerRipple(points)
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            logo?.let {
                Image(
                    bitmap = it,
                    contentDescription = "彭式背单词",
                    colorFilter = ColorFilter.tint(Color(0xFFC9ECE3), BlendMode.SrcIn),
                    modifier = Modifier.size(176.dp).graphicsLayer {
                        scaleX = logoScale
                        scaleY = logoScale
                    },
                    filterQuality = FilterQuality.High,
                )
            }
            Text(
                "彭式背单词",
                color = Color(0xFFF0F7F6),
                fontSize = 38.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
                style = TextStyle(shadow = Shadow(Color(0xFF70E8D8).copy(alpha = glow), blurRadius = 24f)),
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                "记一词，志更远",
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
private fun DesktopStartupPointerRipple(points: List<StartupWordPoint>) {
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
        waveRunning = true
        try {
            waveProgress.snapTo(0f)
            waveProgress.animateTo(1f, tween(720, easing = FastOutSlowInEasing))
            cursor = null
            nearby = emptyList()
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
                            cursor = change.position
                            nearby = nearbyDesktopStartupPoints(grid, change.position, cellSizePx, radiusPx)
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

private fun nearbyDesktopStartupPoints(
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
fun DesktopApp(
    container: DesktopContainer,
    stopSpeech: () -> Unit = {},
    onExitApplication: () -> Unit = {},
    onWindowControlsVisible: (Boolean) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var destination by remember { mutableStateOf(DesktopDestination.HOME) }
    var home by remember { mutableStateOf(DesktopHomeState(isAvailable = false)) }
    var snapshot by remember { mutableStateOf<StudyDataSnapshot?>(null) }
    var selectionDashboard by remember { mutableStateOf(DesktopSelectionDashboardState()) }
    var history by remember { mutableStateOf<DesktopStudyHistory<DesktopStudyViewState>?>(null) }
    var selectedDetails by remember { mutableStateOf<DesktopWordDetails?>(null) }
    var latestUndoToken by remember { mutableStateOf<FeedbackUndoToken?>(null) }
    var preferredMode by remember(container) { mutableStateOf(container.preferredMode) }
    var selectedDataDirectory by remember(container) { mutableStateOf(container.selectedDataDirectory()) }
    var syncSettingsState by remember(container) { mutableStateOf(container.syncSettingsState()) }
    var startupState by remember(container) { mutableStateOf<DesktopStartupState>(DesktopStartupState.Loading) }
    val startupStartedAt = remember(container) { System.nanoTime() }
    var startupElapsedMs by remember(container) { mutableStateOf(0L) }
    LaunchedEffect(startupState, startupElapsedMs >= 4_000L, startupElapsedMs >= 10_000L) {
        onWindowControlsVisible(
            startupState is DesktopStartupState.Failed ||
                (startupState is DesktopStartupState.Ready && startupElapsedMs >= 4_000L) ||
                startupElapsedMs >= 10_000L,
        )
    }
    var startupWordFieldData by remember(container) { mutableStateOf(StartupWordFieldData.Empty) }
    var message by remember { mutableStateOf<String?>(null) }
    var syncSummary by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var studyTransitionMessage by remember { mutableStateOf<String?>(null) }
    var showNewWordChoice by remember { mutableStateOf(false) }
    var showStudyCompletion by remember { mutableStateOf(false) }
    var manualCatalog by remember { mutableStateOf<ManualWordCatalog?>(null) }
    var selectedManualIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var dateState by remember { mutableStateOf(DesktopDateState(LocalDate.now())) }
    val dataDirectoryChangePending = selectedDataDirectory.toAbsolutePath().normalize() !=
        container.dataDirectory.toAbsolutePath().normalize()

    fun navigate(to: DesktopDestination) {
        stopSpeech()
        if (to != DesktopDestination.STUDY || history != null) destination = to
    }

    suspend fun refresh(localDate: LocalDate = dateState.localDate) {
        val now = java.time.Instant.now()
        val next = withContext(Dispatchers.IO) {
            Triple(
                container.homeState(localDate = localDate, now = now),
                container.snapshot(),
                container.selectionDashboardState(),
            )
        }
        home = next.first
        snapshot = next.second
        startupWordFieldData = StartupWordFieldData.fromSnapshot(next.second)
        selectionDashboard = next.third
    }

    suspend fun syncAfterMutation() {
        if (dataDirectoryChangePending) {
            syncSummary = "请退出并重新打开应用，再同步新位置的数据。"
            return
        }
        val result = withContext(Dispatchers.IO) { container.syncNow() }
        syncSummary = result.toUserMessage()
    }

    suspend fun loadStartupData(today: LocalDate) {
        dateState = DesktopDateState(today)
        refresh(today)
        if (home.phase in setOf(DesktopStudyPhase.REVIEW, DesktopStudyPhase.NEW_WORDS, DesktopStudyPhase.EXTRA)) {
            val session = withContext(Dispatchers.IO) { container.resumeStudy(localDate = today) }
            if (session.currentWord != null) {
                history = DesktopStudyHistory(
                    DesktopStudyViewState.from(session, container.isFavorite(session.currentWord.id)),
                )
                refresh(today)
            }
        }
    }

    val startupCoordinator = remember(container) {
        DesktopStartupCoordinator(
            scope = scope,
            hasSyncConfiguration = container::hasSyncConfiguration,
            syncNow = {
                val result = withContext(Dispatchers.IO) { container.syncNow() }
                syncSummary = result.toUserMessage()
                result
            },
            prepareLocalData = {
                withContext(Dispatchers.IO) { container.applySavedDailyQuotaToToday() }
            },
            loadLocalData = { loadStartupData(LocalDate.now()) },
            refreshAfterBackgroundSync = { refresh(LocalDate.now()) },
        )
    }

    fun present(session: StudySessionState, resetHistory: Boolean, undoToken: FeedbackUndoToken? = null) {
        val word = session.currentWord
        if (word == null) {
            history = null
            latestUndoToken = null
            navigate(DesktopDestination.HOME)
            return
        }
        val next = DesktopStudyViewState.from(session, container.isFavorite(word.id))
        history = if (resetHistory || history == null) DesktopStudyHistory(next)
        else if (history?.current?.session?.currentEvent?.id == session.currentEvent?.id) {
            history?.refreshCurrent(next, hasMatchingUndoToken = undoToken != null)
        } else history?.present(next, revisablePrevious = undoToken != null)
        latestUndoToken = undoToken
        navigate(DesktopDestination.STUDY)
    }

    fun runAction(block: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true
            try {
                block()
            } catch (failure: Exception) {
                message = failure.message ?: "操作未完成，请重试。"
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(startupCoordinator) {
        startupCoordinator.state.collect { startupState = it }
    }
    LaunchedEffect(startupCoordinator) { startupCoordinator.start() }
    LaunchedEffect(startupStartedAt) {
        while (startupElapsedMs < 10_000L) {
            delay(100)
            startupElapsedMs = (System.nanoTime() - startupStartedAt) / 1_000_000L
        }
    }

    LaunchedEffect(container) {
        var trackedDate = dateState.localDate
        while (isActive) {
            delay(30_000)
            if (startupState !is DesktopStartupState.Ready) continue
            val observedDate = LocalDate.now()
            val rollover = DesktopDateState(
                localDate = trackedDate,
                hasStudyHistory = history != null,
                hasUndoToken = latestUndoToken != null,
            ).observe(observedDate)
            if (rollover.shouldRefresh) {
                trackedDate = observedDate
                dateState = rollover
                stopSpeech()
                history = null
                latestUndoToken = null
                selectedDetails = null
                destination = DesktopDestination.HOME
                runCatching {
                    refresh(observedDate)
                    if (home.phase in setOf(DesktopStudyPhase.REVIEW, DesktopStudyPhase.NEW_WORDS, DesktopStudyPhase.EXTRA)) {
                        val session = withContext(Dispatchers.IO) {
                            container.resumeStudy(localDate = observedDate)
                        }
                        if (session.currentWord != null) {
                            history = DesktopStudyHistory(DesktopStudyViewState.from(
                                session,
                                container.isFavorite(session.currentWord.id),
                            ))
                        }
                        refresh(observedDate)
                    }
                }.onFailure { message = it.message ?: "新的一天无法加载本地学习数据。" }
            }
        }
    }

    if (startupState !is DesktopStartupState.Ready || startupElapsedMs < 4_000L) {
        DesktopTheme {
            Surface(Modifier.fillMaxSize(), color = DesktopPalette.canvas) {
                if (startupElapsedMs >= 10_000L && startupState in listOf(DesktopStartupState.Loading, DesktopStartupState.Syncing)) {
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center) {
                        Text("正在准备本地词库，请稍候…")
                        LinearProgressIndicator(modifier = Modifier.padding(top = 16.dp))
                    }
                } else DesktopStartupScreen(
                    state = if (startupState is DesktopStartupState.Ready) DesktopStartupState.Loading else startupState,
                    wordFieldData = startupWordFieldData,
                    onRetry = startupCoordinator::retry,
                    onContinueOffline = startupCoordinator::continueOffline,
                )
            }
        }
        return
    }

    DesktopTheme {
        Surface(Modifier.fillMaxSize(), color = DesktopPalette.canvas) {
            Row(Modifier.fillMaxSize()) {
                DesktopNavigation(destination, history != null, ::navigate)
                Column(Modifier.weight(1f).fillMaxSize()) {
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    studyTransitionMessage?.let {
                        Text(it, color = DesktopPalette.teal, modifier = Modifier.padding(horizontal = 36.dp, vertical = 10.dp))
                    }
                    message?.let {
                        Text(it, color = DesktopPalette.teal, modifier = Modifier.padding(horizontal = 36.dp, vertical = 10.dp))
                    }
                    val data = snapshot
                    if (data == null) {
                        Box(Modifier.fillMaxSize().padding(40.dp)) { Text("正在打开本地词库…") }
                    } else {
                        when (destination) {
                            DesktopDestination.HOME -> DesktopHomeScreen(home,
                                availableDecks = selectionDashboard.decks,
                                onStart = {
                                    runAction {
                                        stopSpeech()
                                        val session = withContext(Dispatchers.IO) {
                                            if (home.phase == DesktopStudyPhase.NOT_STARTED) container.startStudy(mode = preferredMode)
                                            else container.resumeStudy()
                                         }
                                         present(session, resetHistory = true)
                                         refresh()
                                         if (session.currentWord == null && home.phase == DesktopStudyPhase.CHOOSE_NEW) {
                                             showNewWordChoice = true
                                         }
                                     }
                                 },
                                 onAddNewWords = { count ->
                                    runAction {
                                        stopSpeech()
                                        val session = withContext(Dispatchers.IO) {
                                            container.addNewWords(count, mode = preferredMode)
                                         }
                                         present(session, resetHistory = true)
                                         refresh()
                                         if (session.currentWord == null && home.phase == DesktopStudyPhase.CHOOSE_NEW) {
                                             showNewWordChoice = true
                                         }
                                     }
                                 },
                                 onAppendExtraWords = { count, deckId ->
                                    runAction {
                                        stopSpeech()
                                        val result = withContext(Dispatchers.IO) {
                                            container.appendExtraWords(count, mode = preferredMode, sourceDeckId = deckId)
                                        }
                                        if (result.appended > 0) {
                                            present(result.session, resetHistory = true)
                                        } else {
                                            message = "所选词库暂无可加入的未学习词。"
                                        }
                                        refresh()
                                    }
                                 },
                                 )
                            DesktopDestination.DECKS -> DesktopDecksScreen(data, selectionDashboard, selectedDetails,
                                onSelectWord = { id ->
                                    runAction { selectedDetails = withContext(Dispatchers.IO) { container.wordDetails(id) } }
                                },
                                speechVoices = container.speech.availableVoices(),
                                onSpeakWordWithVoice = { voice ->
                                    selectedDetails?.let { details ->
                                        stopSpeech()
                                        container.speech.speakWithVoice(
                                            details.word.spelling,
                                            voice,
                                            "library-word-${details.word.id}-${voice.key}",
                                            container.savedSpeechRate,
                                        )
                                    }
                                },
                                onSpeakWord = {
                                    selectedDetails?.let { details ->
                                        stopSpeech()
                                        container.speech.speak(
                                            details.word.spelling,
                                            "library-word-${details.word.id}",
                                            container.savedSpeechRate,
                                        )
                                    }
                                },
                                onSpeakExamples = {
                                    selectedDetails?.let { details ->
                                        stopSpeech()
                                        container.speech.speakSequence(
                                            details.examples.map { it.sentenceEn },
                                            container.savedSpeechRate,
                                        )
                                    }
                                },
                                onAddWordToToday = { id ->
                                    runAction {
                                        stopSpeech()
                                        val session = withContext(Dispatchers.IO) {
                                            container.addWordToToday(id, mode = preferredMode)
                                         }
                                         present(session, resetHistory = false)
                                         refresh()
                                     }
                                 },
                                onCreateUserDeck = { name ->
                                    runAction {
                                        withContext(Dispatchers.IO) { container.createUserDeck(name) }
                                        message = "个人词库已创建。"
                                        refresh()
                                        syncAfterMutation()
                                    }
                                },
                                onAddWord = { deckId, input ->
                                    runAction {
                                        val result = withContext(Dispatchers.IO) { container.addWordToUserDeck(deckId, input) }
                                        message = formatUserDeckBulkResult(result)
                                        refresh()
                                        syncAfterMutation()
                                    }
                                },
                                onBatchAddWords = { deckId, content, sources ->
                                    runAction {
                                        val result = withContext(Dispatchers.IO) {
                                            container.importWordsIntoUserDeck(content.byteInputStream(), ImportFormat.TXT, deckId, sources)
                                        }
                                        message = formatUserDeckBulkResult(result)
                                        refresh()
                                        syncAfterMutation()
                                    }
                                },
                                onDeleteDeck = { deckId ->
                                    runAction {
                                        withContext(Dispatchers.IO) { container.deleteUserDeck(deckId) }
                                        selectedDetails = null
                                        message = "词库已删除，学习记录仍保留。"
                                        refresh()
                                        syncAfterMutation()
                                    }
                                },
                                onImportIntoDeck = { deckId, sources ->
                                    val chooser = JFileChooser().apply {
                                        dialogTitle = "批量导入到当前词库"
                                        fileFilter = FileNameExtensionFilter("词库文件 (CSV, TXT, XLSX)", "csv", "txt", "xlsx")
                                    }
                                    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                                        val file = chooser.selectedFile.toPath()
                                        val format = runCatching {
                                            ImportFormat.valueOf(file.fileName.toString().substringAfterLast('.').uppercase(Locale.ROOT))
                                        }.getOrNull()
                                        if (format == null) {
                                            message = "请选择 CSV、TXT 或 XLSX 文件。"
                                        } else {
                                            runAction {
                                                val result = withContext(Dispatchers.IO) {
                                                    Files.newInputStream(file).use { container.importWordsIntoUserDeck(it, format, deckId, sources) }
                                                }
                                                message = formatUserDeckBulkResult(result)
                                                refresh()
                                                syncAfterMutation()
                                            }
                                        }
                                    }
                                },
                                onUpdateWord = { deckId, wordId, input ->
                                    runAction {
                                        withContext(Dispatchers.IO) { container.updateWordInUserDeck(deckId, wordId, input) }
                                        selectedDetails = withContext(Dispatchers.IO) { container.wordDetails(wordId) }
                                        message = "词条已更新。"
                                        refresh()
                                        syncAfterMutation()
                                    }
                                },
                                onDeleteWord = { deckId, wordId ->
                                    runAction {
                                        withContext(Dispatchers.IO) { container.deleteWordFromUserDeck(deckId, wordId) }
                                        selectedDetails = null
                                        message = "词条已从当前词库移除。"
                                        refresh()
                                        syncAfterMutation()
                                    }
                                },
                                onAddExample = { wordId, sentenceEn, sentenceCn, source ->
                                    runAction {
                                        withContext(Dispatchers.IO) { container.addManualExample(wordId, sentenceEn, sentenceCn, source) }
                                        selectedDetails = withContext(Dispatchers.IO) { container.wordDetails(wordId) }
                                        message = "例句已保存。"
                                        refresh()
                                        syncAfterMutation()
                                    }
                                },
                                onUpdateExample = { exampleId, sentenceEn, sentenceCn, source ->
                                    runAction {
                                        withContext(Dispatchers.IO) { container.updateManualExample(exampleId, sentenceEn, sentenceCn, source) }
                                        selectedDetails?.word?.id?.let { wordId ->
                                            selectedDetails = withContext(Dispatchers.IO) { container.wordDetails(wordId) }
                                        }
                                        message = "例句已更新。"
                                        refresh()
                                        syncAfterMutation()
                                    }
                                },
                                onDeleteExample = { exampleId ->
                                    runAction {
                                        withContext(Dispatchers.IO) { container.deleteManualExample(exampleId) }
                                        selectedDetails?.word?.id?.let { wordId ->
                                            selectedDetails = withContext(Dispatchers.IO) { container.wordDetails(wordId) }
                                        }
                                        message = "例句已删除。"
                                        refresh()
                                        syncAfterMutation()
                                    }
                                },
                                onSaveSelection = { selection ->
                                    runAction {
                                        withContext(Dispatchers.IO) { container.saveWordPoolSelection(selection) }
                                        refresh()
                                        syncAfterMutation()
                                    }
                                },
                                onToggleExcludedWord = { wordId ->
                                    runAction {
                                        withContext(Dispatchers.IO) { container.toggleExcludedWord(wordId) }
                                        refresh()
                                        syncAfterMutation()
                                    }
                                })
                            DesktopDestination.STATS -> DesktopStatsScreen(data, container.statsStartDate())
                            DesktopDestination.SETTINGS -> DesktopSettingsScreen(preferredMode, message,
                                voices = container.speech.availableVoices(),
                                voiceCatalog = container.speech.voiceCatalog,
                                modelInstallDirectory = container.speech.modelInstallDirectory.toString(),
                                selectedVoiceKey = container.speech.selectedVoiceKey,
                                savedSpeechRate = container.savedSpeechRate,
                                autoPlayWord = container.savedAutoPlayWord,
                                autoPlaySentence = container.savedAutoPlaySentence,
                                dailyQuota = container.dailyQuota,
                                onSaveDailyQuota = { quota ->
                                    runAction {
                                        withContext(Dispatchers.IO) { container.updateDailyQuota(quota) }
                                        message = "每日额度已保存；今天尚未开始的自动新词已按新额度调整。"
                                        refresh()
                                        syncAfterMutation()
                                    }
                                },
                                onImport = {
                                    val chooser = JFileChooser().apply {
                                        dialogTitle = "导入本地词库"
                                        fileFilter = FileNameExtensionFilter("词库文件 (CSV, TXT, XLSX)", "csv", "txt", "xlsx")
                                    }
                                    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                                        val file = chooser.selectedFile.toPath()
                                        val format = runCatching {
                                            ImportFormat.valueOf(file.fileName.toString().substringAfterLast('.').uppercase(Locale.ROOT))
                                        }.getOrNull()
                                        if (format == null) message = "请选择 CSV、TXT 或 XLSX 文件。"
                                        else runAction {
                                            val result = withContext(Dispatchers.IO) {
                                                Files.newInputStream(file).use { container.importWords(it, format) }
                                            }
                                            message = formatImportResult(result)
                                            refresh()
                                            syncAfterMutation()
                                        }
                                    }
                                },
                                onSwitchMode = {
                                    val next = if (preferredMode == StudyMode.EN_TO_CN) StudyMode.CN_TO_EN else StudyMode.EN_TO_CN
                                    preferredMode = next
                                    container.setPreferredMode(next)
                                    scope.launch { syncAfterMutation() }
                                },
                                onSelectVoice = { voice ->
                                    container.speech.selectVoice(voice)
                                    scope.launch { syncAfterMutation() }
                                },
                                onSetVoiceEnabled = { voice, enabled -> container.speech.setVoiceEnabled(voice, enabled) },
                                onRefreshVoices = { container.speech.refreshVoices() },
                                onPreviewVoice = { voice ->
                                    container.speech.speakWithVoice(
                                        "Hello! This is your English reading voice.", voice, "voice-preview", container.savedSpeechRate,
                                    )
                                },
                                onOpenModelDirectory = {
                                    runCatching {
                                        Files.createDirectories(container.speech.modelInstallDirectory)
                                        java.awt.Desktop.getDesktop().open(container.speech.modelInstallDirectory.toFile())
                                    }.onFailure { message = "无法打开模型目录：${it.message ?: "请从资源管理器手动打开"}" }
                                },
                                onChooseModelDirectory = {
                                    val current = container.selectedSpeechModelDirectory()
                                    val chooser = JFileChooser(current.toFile()).apply {
                                        dialogTitle = "选择语音模型文件夹"
                                        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                                        selectedFile = current.toFile()
                                    }
                                    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                                        runCatching { container.saveSelectedSpeechModelDirectory(chooser.selectedFile.toPath()) }
                                            .onSuccess { message = "模型位置已保存；重新打开应用后生效。原模型文件不会自动移动。" }
                                            .onFailure { message = "无法保存模型位置：${it.message ?: "请检查文件夹权限"}" }
                                    }
                                },
                                onOpenModelGuide = {
                                    runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(SpeechModelDownloads.guideUrl)) }
                                        .onFailure { message = "无法打开模型目录：${it.message ?: "请查看语音模型指南"}" }
                                },
                                onDownloadModel = { model ->
                                    runCatching {
                                        java.awt.Desktop.getDesktop().browse(java.net.URI(SpeechModelDownloads.desktopArchiveUrl(model)))
                                    }.onFailure { message = "无法打开下载页面：${it.message ?: "请查看语音模型指南"}" }
                                },
                                onSaveSpeechSettings = { rate ->
                                    container.saveSpeechSettings(rate)
                                    scope.launch { syncAfterMutation() }
                                },
                                onSaveAutoPlaySettings = { autoPlayWord, autoPlaySentence ->
                                    container.saveAutoPlaySettings(autoPlayWord, autoPlaySentence)
                                    scope.launch { syncAfterMutation() }
                                },
                                onSyncNow = {
                                    runAction {
                                        syncAfterMutation()
                                        refresh()
                                    }
                                },
                                localRepositoryPath = container.dataDirectory.toString(),
                                selectedDataDirectory = selectedDataDirectory.toString(),
                                dataDirectoryChangePending = dataDirectoryChangePending,
                                onChooseDataDirectory = {
                                    val chooser = JFileChooser(selectedDataDirectory.toFile()).apply {
                                        dialogTitle = "选择数据库文件夹"
                                        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                                        selectedFile = selectedDataDirectory.toFile()
                                    }
                                    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                                        val selected = chooser.selectedFile.toPath().toAbsolutePath().normalize()
                                        runCatching { container.saveSelectedDataDirectory(selected) }
                                            .onSuccess {
                                                selectedDataDirectory = selected
                                                syncSummary = if (selected == container.dataDirectory.toAbsolutePath().normalize()) {
                                                    "数据库位置未改变。"
                                                } else {
                                                    "新位置已保存。退出并重新打开应用后，会在新位置打开或新建数据库；原数据库保持不动。"
                                                }
                                            }
                                            .onFailure { failure ->
                                                syncSummary = "无法保存数据库位置：${failure.message ?: "请检查文件夹权限"}"
                                            }
                                    }
                                },
                                onCancelDataDirectoryChange = {
                                    runCatching { container.saveSelectedDataDirectory(container.dataDirectory) }
                                        .onSuccess {
                                            selectedDataDirectory = container.dataDirectory
                                            syncSummary = "已取消数据库位置更改。"
                                        }
                                        .onFailure { failure ->
                                            syncSummary = "无法取消数据库位置更改：${failure.message ?: "请重试"}"
                                        }
                                },
                                onExitForDataDirectoryChange = onExitApplication,
                                syncSettingsState = syncSettingsState,
                                onSaveSyncSettings = { owner, repository, branch, password, token ->
                                    runCatching {
                                        container.saveSyncConnection(owner, repository, branch, password, token)
                                        syncSettingsState = container.syncSettingsState()
                                        syncSummary = "GitHub 配置已安全保存。可以点“立即同步”连接私有仓库。"
                                        true
                                    }.onFailure { failure ->
                                        syncSummary = failure.message ?: "GitHub 配置保存失败。"
                                    }.getOrDefault(false)
                                },
                                syncSummary = syncSummary)
                            DesktopDestination.STUDY -> history?.let { currentHistory ->
                                DesktopStudyScreen(currentHistory, data.words, wordSenses = data.wordSenses,
                                    onReveal = {
                                        stopSpeech()
                                        val revealedView = currentHistory.displayed.copy(revealed = true)
                                        history = history?.replaceDisplayed(revealedView)
                                        val content = desktopStudySpeechContent(
                                            mode = revealedView.session.mode,
                                            spelling = revealedView.word.spelling,
                                            examples = SpeechTextPolicy.firstSentence(
                                                revealedView.session.currentExamples.map { it.sentenceEn },
                                            ),
                                            answersVisible = true,
                                        )
                                        val speechTexts = buildList {
                                            if (container.savedAutoPlayWord) content.word?.let(::add)
                                            if (container.savedAutoPlaySentence) addAll(content.examples)
                                        }
                                        container.speech.speakSequence(speechTexts, container.savedSpeechRate)
                                    },
                                    onFeedback = { feedback ->
                                        val view = history?.displayed ?: return@DesktopStudyScreen
                                        val revisingCurrent = history?.isHistorical == false && history?.canReviseDisplayed == true
                                        if ((!view.revealed && !revisingCurrent) || history?.isHistorical == true && history?.canReviseDisplayed != true) return@DesktopStudyScreen
                                        val itemId = view.session.currentItem?.id ?: return@DesktopStudyScreen
                                        runAction {
                                            stopSpeech()
                                            val browsing = history?.isHistorical == true
                                            try {
                                                val result = withContext(Dispatchers.IO) {
                                                    if (browsing || revisingCurrent) {
                                                        container.reviseFeedback(
                                                            requireNotNull(latestUndoToken),
                                                            feedback,
                                                        )
                                                    } else {
                                                        container.submitFeedbackWithUndo(
                                                            itemId,
                                                            feedback,
                                                        )
                                                    }
                                                }
                                                if (browsing) {
                                                    history = null
                                                    present(result.session, resetHistory = true)
                                                } else present(result.session, resetHistory = false, undoToken = result.undoToken)
                                                refresh()
                                                if (result.session.currentWord == null) {
                                                    if (view.session.currentItem?.source == PlanSource.DUE_REVIEW &&
                                                        home.phase == DesktopStudyPhase.CHOOSE_NEW
                                                    ) showNewWordChoice = true
                                                    else showStudyCompletion = true
                                                }
                                            } finally {
                                                studyTransitionMessage = null
                                            }
                                         }
                                     },
                                    onFavorite = {
                                        val view = history?.displayed ?: return@DesktopStudyScreen
                                        val favorite = container.toggleFavorite(view.word.id)
                                        history = history?.replaceDisplayed(view.copy(favorite = favorite))
                                     },
                                     onPrevious = { stopSpeech(); history = history?.goPrevious() },
                                     onNext = { stopSpeech(); history = history?.goNext() },
                                     onBack = {
                                         navigate(DesktopDestination.HOME)
                                         runAction { syncAfterMutation() }
                                     },
                                    onAddWordToToday = { id ->
                                        runAction {
                                            stopSpeech()
                                            val session = withContext(Dispatchers.IO) {
                                                container.addWordToToday(id, mode = preferredMode)
                                             }
                                             present(session, resetHistory = false)
                                             refresh()
                                         }
                                     },
                                    onSpeakWord = {
                                        val view = history?.displayed ?: return@DesktopStudyScreen
                                        val content = desktopStudySpeechContent(
                                            mode = view.session.mode,
                                            spelling = view.word.spelling,
                                            examples = view.session.currentExamples.map { it.sentenceEn },
                                            answersVisible = view.revealed || history?.isHistorical == true,
                                        )
                                        content.word?.let { text ->
                                            container.speech.speak(text, "study-word-${view.word.id}", container.savedSpeechRate)
                                        }
                                    },
                                    onSpeakExamples = {
                                        val view = history?.displayed ?: return@DesktopStudyScreen
                                        val content = desktopStudySpeechContent(
                                            mode = view.session.mode,
                                            spelling = view.word.spelling,
                                            examples = view.session.currentExamples.map { it.sentenceEn },
                                            answersVisible = view.revealed || history?.isHistorical == true,
                                        )
                                        container.speech.speakSequence(content.examples, container.savedSpeechRate)
                                    },
                                    speechVoices = container.speech.availableVoices(),
                                    onSpeakWordWithVoice = { voice ->
                                        val view = history?.displayed ?: return@DesktopStudyScreen
                                        val content = desktopStudySpeechContent(
                                            mode = view.session.mode,
                                            spelling = view.word.spelling,
                                            examples = view.session.currentExamples.map { it.sentenceEn },
                                            answersVisible = view.revealed || history?.isHistorical == true,
                                        )
                                        content.word?.let { text ->
                                            stopSpeech()
                                            container.speech.speakWithVoice(
                                                text,
                                                voice,
                                                "study-word-${view.word.id}-${voice.key}",
                                                container.savedSpeechRate,
                                            )
                                        }
                                    })
                            }
                        }
                    }
                }
            }
        }
        if (showNewWordChoice) DesktopNewWordChoiceDialog(
            remaining = home.remainingMainQuota,
            onAutomatic = {
                showNewWordChoice = false
                runAction {
                    val session = withContext(Dispatchers.IO) {
                        container.addNewWords(home.remainingMainQuota, mode = preferredMode)
                    }
                    present(session, resetHistory = true)
                    refresh()
                    if (session.currentWord == null && home.phase == DesktopStudyPhase.CHOOSE_NEW) {
                        message = "仪表盘当前没有可自动选取的新词，可以手动挑选。"
                        showNewWordChoice = true
                    }
                }
            },
            onManual = {
                showNewWordChoice = false
                runAction {
                    val catalog = withContext(Dispatchers.IO) { container.manualWordCatalog(mode = preferredMode) }
                    val validIds = catalog.words.mapTo(hashSetOf()) { it.word.id }
                    selectedManualIds = selectedManualIds.filterTo(linkedSetOf()) { it in validIds }
                    manualCatalog = catalog
                }
            },
            onLater = { showNewWordChoice = false },
        )
        manualCatalog?.let { catalog ->
            DesktopManualNewWordDialog(
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
                    runAction {
                        val session = withContext(Dispatchers.IO) {
                            container.addSelectedNewWords(ids, mode = preferredMode)
                        }
                        present(session, resetHistory = true)
                        refresh()
                        if (session.currentWord == null && home.phase == DesktopStudyPhase.CHOOSE_NEW) {
                            showNewWordChoice = true
                        }
                    }
                },
                onDismiss = { manualCatalog = null },
            )
        }
        if (showStudyCompletion) AlertDialog(
            onDismissRequest = { showStudyCompletion = false },
            title = { Text("本轮学习完成！") },
            text = { Text(if (home.phase == DesktopStudyPhase.CHOOSE_NEW)
                "本轮选出的新词已经学完，辛苦了。今天还可以继续挑选新词，休息一下再开始也可以。"
            else "今天已经完成 ${home.completedUniqueWordCount} 个词，辛苦了。可以在首页查看进度，明天继续保持。") },
            confirmButton = { TextButton(onClick = { showStudyCompletion = false }) { Text("返回首页") } },
        )
    }
}

private fun com.pengshi.words.sync.SyncResult.toUserMessage(): String = userMessage()
