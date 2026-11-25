package com.ilearn.app.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.ui.PlayerView
import com.ilearn.app.data.AudioTrackMode
import com.ilearn.app.data.Lesson
import com.ilearn.app.data.LessonNote
import com.ilearn.app.player.ILearnPlayerController
import com.ilearn.app.player.SubtitleMode
import com.ilearn.app.player.SubtitleSize
import com.ilearn.app.ui.components.ContextualEmptyState
import com.ilearn.app.ui.components.FileUploadBottomSheet
import com.ilearn.app.ui.components.GestureTutorialOverlay
import com.ilearn.app.utils.AudioExtractorHelper
import com.ilearn.app.youtube.LessonDownloadState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

fun formatTimeMs(timeMs: Long): String {
    val totalSecs = (timeMs / 1000).coerceAtLeast(0)
    val mins = totalSecs / 60
    val secs = totalSecs % 60
    return String.format("%02d:%02d", mins, secs)
}

@OptIn(ExperimentalMaterial3Api::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun VideoPlayerScreen(
    lesson: Lesson,
    courseTitle: String,
    onTutor: (Long) -> Unit,
    allLessons: List<Lesson>,
    notes: List<LessonNote>,
    playerController: ILearnPlayerController,
    onBack: () -> Unit,
    onNavigateLesson: (Lesson) -> Unit,
    onSaveProgress: (lessonId: String, courseId: String, pos: Long, dur: Long) -> Unit,
    onToggleBookmark: (Boolean) -> Unit,
    onAddNote: (timestampMs: Long, text: String) -> Unit,
    onDeleteNote: (noteId: String) -> Unit,
    onExtractAudio: suspend (Lesson) -> File?,
    onAttachArabicAudio: suspend (Lesson, Uri) -> Boolean,
    onRemoveArabicAudio: suspend (Lesson) -> Boolean,
    hasConfiguredAi: Boolean = false,
    translationState: LessonDownloadState? = null,
    translationError: String? = null,
    onTranslateSubtitlesToArabic: () -> Unit = {},
    onCancelTranslation: () -> Unit = {},
    onDismissTranslationError: () -> Unit = {},
    initialSeekMs: Long = -1L,
    onStudyPack: () -> Unit = {},
    onSnapFrameTutor: (Long, String) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val playbackSession = remember(lesson.id) { com.ilearn.app.player.LocalPlaybackSession(playerController, lesson) }
    val activity = remember(context) { context.findActivity() }
    val configuration = LocalConfiguration.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val haptic = LocalHapticFeedback.current

    val audioManager = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume = remember(audioManager) { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }

    val isPhysicalLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    var isManualFullscreen by rememberSaveable { mutableStateOf(false) }
    val isFullscreen = isManualFullscreen || isPhysicalLandscape

    val subtitleMode by playerController.subtitleMode.collectAsState()
    val subtitleSize by playerController.subtitleSize.collectAsState()
    val dualSubtitles by playerController.dualSubtitles.collectAsState()
    val audioTrackMode by playerController.audioTrackMode.collectAsState()
    val playbackSpeed by playerController.playbackSpeed.collectAsState()
    val isPlaying by playerController.isPlaying.collectAsState()
    val isBuffering by playerController.isBuffering.collectAsState()
    val playerError by playerController.playerError.collectAsState()
    val currentPosition by playerController.currentPosition.collectAsState()
    val duration by playerController.duration.collectAsState()

    var showSubtitleDialog by remember { mutableStateOf(false) }
    var showSpeedDialog by remember { mutableStateOf(false) }
    var showNotesDialog by remember { mutableStateOf(false) }
    var showAudioDialog by remember { mutableStateOf(false) }
    var showAttachAudioBottomSheet by remember { mutableStateOf(false) }
    var showTranscriptSheet by remember { mutableStateOf(false) }
    var isCapturingFrame by remember { mutableStateOf(false) }
    var consumedInitialSeek by remember(lesson.id, initialSeekMs) { mutableStateOf(false) }
    var showGestureTutorial by rememberSaveable { mutableStateOf(true) }
    var areControlsVisible by remember { mutableStateOf(true) }
    var isExtractingAudio by remember { mutableStateOf(false) }

    // Gesture State
    var touchStartX by remember { mutableFloatStateOf(0f) }
    var initialVolume by remember { mutableIntStateOf(0) }
    var initialBrightness by remember { mutableFloatStateOf(0.5f) }
    var isHudVisible by remember { mutableStateOf(false) }
    var gestureHudTitle by remember { mutableStateOf("") }
    var gestureHudPercent by remember { mutableIntStateOf(0) }
    var gestureHudIcon by remember { mutableStateOf(Icons.AutoMirrored.Filled.VolumeUp) }
    var seekRippleText by remember { mutableStateOf<String?>(null) }
    var seekRippleSide by remember { mutableStateOf<String?>(null) }

    // Launcher for attaching Arabic Audio File (.m4a, .mp3, etc.) with safe cancellation
    val attachAudioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                val success = onAttachArabicAudio(lesson, uri)
                if (success) {
                    playerController.loadLesson(lesson.copy(audioArPath = "attached"))
                    snackbarHostState.showSnackbar("تم ربط الدبلجة الصوتية العربية بنجاح! 🇸🇦")
                } else {
                    snackbarHostState.showSnackbar("فشل ربط الملف الصوتي")
                }
            }
        } else {
            // User cancelled file selection - completely safe, no exit
            coroutineScope.launch {
                snackbarHostState.showSnackbar("تم إلغاء اختيار الملف الصوتي")
            }
        }
    }

    // Handler for audio extraction
    val handleExtractAudio: () -> Unit = {
        coroutineScope.launch {
            isExtractingAudio = true
            val file = onExtractAudio(lesson)
            isExtractingAudio = false
            if (file != null && file.exists()) {
                AudioExtractorHelper.shareAudioFile(context, file, lesson.title)
            } else {
                snackbarHostState.showSnackbar("تعذّر استخراج الصوت من ملف الفيديو")
            }
        }
    }

    // Handler for paused-frame AI Vision capture
    val captureFrameAndAsk: () -> Unit = {
        if (!isCapturingFrame) {
            val capturePos = currentPosition
            playerController.pause()
            coroutineScope.launch {
                isCapturingFrame = true
                val base64 = com.ilearn.app.player.VideoFrameCapture.captureFrameBase64(
                    context = context,
                    videoPath = lesson.videoPath,
                    positionMs = capturePos
                )
                isCapturingFrame = false
                if (base64 != null) {
                    onSnapFrameTutor(capturePos, base64)
                } else {
                    snackbarHostState.showSnackbar("تعذّر التقاط لقطة من الفيديو في هذه اللحظة")
                }
            }
        }
    }

    // Auto-hide Seek Ripple
    LaunchedEffect(seekRippleSide) {
        if (seekRippleSide != null) {
            delay(700L)
            seekRippleSide = null
            seekRippleText = null
        }
    }

    fun setFullscreen(enter: Boolean) {
        isManualFullscreen = enter
        activity?.let { act ->
            val insetsController = WindowCompat.getInsetsController(act.window, act.window.decorView)
            if (enter) {
                insetsController.hide(WindowInsetsCompat.Type.systemBars())
                insetsController.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                insetsController.show(WindowInsetsCompat.Type.systemBars())
                act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }

    // Sync system bars with device rotation
    LaunchedEffect(isPhysicalLandscape, isManualFullscreen) {
        activity?.let { act ->
            val insetsController = WindowCompat.getInsetsController(act.window, act.window.decorView)
            if (isPhysicalLandscape || isManualFullscreen) {
                insetsController.hide(WindowInsetsCompat.Type.systemBars())
                insetsController.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // Auto-hide controls in fullscreen after 4 seconds of inactivity
    LaunchedEffect(areControlsVisible, isFullscreen, isPlaying) {
        if (isFullscreen && areControlsVisible && isPlaying) {
            delay(4000L)
            areControlsVisible = false
        }
    }

    LaunchedEffect(translationError) {
        if (!translationError.isNullOrBlank()) {
            snackbarHostState.showSnackbar(translationError, duration = SnackbarDuration.Long)
            onDismissTranslationError()
        }
    }

    // Load active lesson when it changes or when subtitles/dubbing update live
    LaunchedEffect(lesson.id, lesson.audioArPath, lesson.subArPath) {
        val sameLessonReload = playerController.activeLessonId == lesson.id && currentPosition > 0L
        if (sameLessonReload && lesson.hasArabicSubtitles) {
            playerController.setSubtitleMode(SubtitleMode.ARABIC)
        }
        val overridePos = when {
            sameLessonReload -> currentPosition
            initialSeekMs > 0L && !consumedInitialSeek -> {
                consumedInitialSeek = true
                initialSeekMs
            }
            else -> null
        }
        playerController.loadLesson(
            lesson = lesson,
            overridePositionMs = overridePos
        )
    }

    // Periodic progress saver (250ms tick in Dual Subtitle mode for crisp AR+EN sync)
    LaunchedEffect(lesson.id, isPlaying, subtitleMode) {
        var ticks = 0
        val tickDelay = if (subtitleMode == SubtitleMode.DUAL) 250L else 1000L
        while (true) {
            delay(tickDelay)
            playbackSession.sample()
            ticks++
            if (tickDelay >= 1000L || ticks % 4 == 0) {
                val pos = playbackSession.state.value.positionMs
                val dur = playbackSession.state.value.durationMs
                if (dur > 0 && pos > 0) {
                    onSaveProgress(lesson.id, lesson.courseId, pos, dur)
                }
            }
        }
    }

    // Stop player when leaving screen or switching lessons
    DisposableEffect(lesson.id) {
        onDispose {
            playbackSession.sample()
            playbackSession.pause()
            val pos = playbackSession.state.value.positionMs
            val dur = playbackSession.state.value.durationMs
            if (dur > 0) {
                onSaveProgress(lesson.id, lesson.courseId, pos, dur)
            }
        }
    }

    // Pause playback when app is backgrounded / screen locked
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                playerController.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            playerController.pause()
            activity?.let { act ->
                val insetsController = WindowCompat.getInsetsController(act.window, act.window.decorView)
                insetsController.show(WindowInsetsCompat.Type.systemBars())
                act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }

    // Intercept back button: exit fullscreen first, otherwise exit player
    BackHandler(enabled = true) {
        if (isFullscreen) {
            setFullscreen(false)
        } else {
            playerController.pause()
            onBack()
        }
    }

    val handleBackPress: () -> Unit = {
        if (isFullscreen) {
            setFullscreen(false)
        } else {
            playerController.pause()
            onBack()
        }
    }

    val currentIndex = allLessons.indexOfFirst { it.id == lesson.id }
    val prevLesson = if (currentIndex > 0) allLessons[currentIndex - 1] else null
    val nextLesson = if (currentIndex >= 0 && currentIndex < allLessons.size - 1) allLessons[currentIndex + 1] else null

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Color.Black
    ) { scaffoldPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding)
                .background(Color.Black)
        ) {
            if (isFullscreen) {
                // ==========================================
                // FULLSCREEN MODE (Landscape / Immersive)
                // ==========================================
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { areControlsVisible = !areControlsVisible },
                                onDoubleTap = { offset ->
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    if (offset.x < size.width / 2) {
                                        playerController.seekBackward(10000)
                                        seekRippleSide = "left"
                                        seekRippleText = "-10 ثوانٍ"
                                    } else {
                                        playerController.seekForward(10000)
                                        seekRippleSide = "right"
                                        seekRippleText = "+10 ثوانٍ"
                                    }
                                }
                            )
                        }
                        .pointerInput(Unit) {
                            detectVerticalDragGestures(
                                onDragStart = { offset ->
                                    touchStartX = offset.x
                                    initialVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                                    val currentBr = activity?.window?.attributes?.screenBrightness ?: -1f
                                    initialBrightness = if (currentBr < 0) 0.5f else currentBr
                                    isHudVisible = true
                                },
                                onDragEnd = { isHudVisible = false },
                                onDragCancel = { isHudVisible = false },
                                onVerticalDrag = { change, dragAmount ->
                                    change.consume()
                                    val isLeft = touchStartX < size.width / 2
                                    if (isLeft) {
                                        activity?.window?.let { window ->
                                            val lp = window.attributes
                                            val cur = if (lp.screenBrightness < 0) 0.5f else lp.screenBrightness
                                            val delta = -dragAmount / size.height
                                            val newBr = (cur + delta).coerceIn(0.01f, 1.0f)
                                            lp.screenBrightness = newBr
                                            window.attributes = lp

                                            gestureHudIcon = Icons.Default.BrightnessMedium
                                            gestureHudTitle = "السطوع"
                                            gestureHudPercent = (newBr * 100).toInt()
                                            isHudVisible = true
                                        }
                                    } else {
                                        val delta = -dragAmount / size.height
                                        val newVol = (initialVolume + delta * maxVolume * 1.5f).toInt().coerceIn(0, maxVolume)
                                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
                                        initialVolume = newVol

                                        gestureHudIcon = if (newVol == 0) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp
                                        gestureHudTitle = "مستوى الصوت"
                                        gestureHudPercent = if (maxVolume > 0) ((newVol * 100) / maxVolume) else 0
                                        isHudVisible = true
                                    }
                                }
                            )
                        }
                ) {
                    // ExoPlayer Viewport
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                player = playerController.exoPlayer
                                useController = false
                                subtitleView?.apply {
                                    visibility = android.view.View.VISIBLE
                                    setFractionalTextSize(androidx.media3.ui.SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * subtitleSize.scale)
                                    setBottomPaddingFraction(0.08f)
                                    setStyle(
                                        androidx.media3.ui.CaptionStyleCompat(
                                            android.graphics.Color.WHITE,
                                            android.graphics.Color.argb(180, 15, 23, 42),
                                            android.graphics.Color.TRANSPARENT,
                                            androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
                                            android.graphics.Color.BLACK,
                                            null
                                        )
                                    )
                                }
                                layoutParams = FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                            }
                        },
                        update = { playerView ->
                            if (playerView.player != playerController.exoPlayer) {
                                playerView.player = playerController.exoPlayer
                            }
                            playerView.subtitleView?.setFractionalTextSize(
                                androidx.media3.ui.SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * subtitleSize.scale
                            )
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    if (subtitleMode == SubtitleMode.DUAL && dualSubtitles != null) {
                        DualSubtitleOverlay(
                            dual = dualSubtitles!!,
                            subtitleSize = subtitleSize,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(horizontal = 32.dp, vertical = 22.dp)
                        )
                    }

                    // Seek Ripple Indicators
                    seekRippleSide?.let { side ->
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(180.dp)
                                .align(if (side == "left") Alignment.CenterStart else Alignment.CenterEnd)
                                .background(Color.White.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = if (side == "left") Icons.Default.Replay10 else Icons.Default.Forward10,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = seekRippleText ?: "",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }

                    // Volume / Brightness HUD Center Pill
                    AnimatedVisibility(
                        visible = isHudVisible,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier.align(Alignment.Center)
                    ) {
                        Surface(
                            color = Color.Black.copy(alpha = 0.75f),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(gestureHudIcon, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(gestureHudTitle, color = Color(0xFFCBD5E1), fontSize = 12.sp)
                                    Text("$gestureHudPercent%", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                }
                            }
                        }
                    }

                    // Overlaid Controls
                    AnimatedVisibility(
                        visible = areControlsVisible,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            Color.Black.copy(alpha = 0.75f),
                                            Color.Transparent,
                                            Color.Black.copy(alpha = 0.85f)
                                        )
                                    )
                                )
                        ) {
                            // Top Bar in Fullscreen
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .statusBarsPadding()
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                                    .align(Alignment.TopCenter),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    IconButton(
                                        onClick = handleBackPress,
                                        modifier = Modifier
                                            .size(48.dp)
                                            .semantics { contentDescription = "الرجوع" }
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = null,
                                            tint = Color.White
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = lesson.title,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = if (lesson.sourceType == com.ilearn.app.data.SourceType.YOUTUBE) "$courseTitle • أوفلاين 360p" else courseTitle,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color(0xFF94A3B8)
                                        )
                                    }
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    // Interactive Transcript Sheet
                                    IconButton(
                                        onClick = { showTranscriptSheet = true },
                                        modifier = Modifier
                                            .size(44.dp)
                                            .semantics { contentDescription = "النص التفاعلي للدرس" }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Article,
                                            contentDescription = null,
                                            tint = Color.White
                                        )
                                    }
                                    // Smart Study Pack (Summary + Quiz + Flashcards)
                                    IconButton(
                                        onClick = onStudyPack,
                                        modifier = Modifier
                                            .size(44.dp)
                                            .semantics { contentDescription = "حزمة الدراسة الذكية" }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.AutoAwesome,
                                            contentDescription = null,
                                            tint = Color(0xFF38BDF8)
                                        )
                                    }
                                    // Paused-Frame AI Vision Explainer
                                    IconButton(
                                        onClick = captureFrameAndAsk,
                                        enabled = !isCapturingFrame,
                                        modifier = Modifier
                                            .size(44.dp)
                                            .semantics { contentDescription = "اشرح اللقطة الحالية بالذكاء الاصطناعي" }
                                    ) {
                                        if (isCapturingFrame) {
                                            CircularProgressIndicator(
                                                color = Color(0xFF38BDF8),
                                                strokeWidth = 2.dp,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        } else {
                                            Icon(
                                                imageVector = Icons.Default.PhotoCamera,
                                                contentDescription = null,
                                                tint = Color.White
                                            )
                                        }
                                    }
                                    // Audio Track & Dubbing Button 🎙️
                                    IconButton(
                                        onClick = { showAudioDialog = true },
                                        modifier = Modifier
                                            .size(48.dp)
                                            .semantics { contentDescription = "المسار الصوتي والدبلجة العربية" }
                                    ) {
                                        Icon(
                                            imageVector = if (lesson.hasArabicAudio) Icons.Default.RecordVoiceOver else Icons.Default.Audiotrack,
                                            contentDescription = null,
                                            tint = if (audioTrackMode == AudioTrackMode.ARABIC) Color(0xFF10B981) else Color.White
                                        )
                                    }
                                    // Bookmark ⭐
                                    IconButton(
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            onToggleBookmark(!lesson.isBookmarked)
                                        },
                                        modifier = Modifier
                                            .size(48.dp)
                                            .semantics { contentDescription = if (lesson.isBookmarked) "إزالة من المفضلة" else "إضافة للمفضلة" }
                                    ) {
                                        Icon(
                                            imageVector = if (lesson.isBookmarked) Icons.Default.Star else Icons.Default.StarBorder,
                                            contentDescription = null,
                                            tint = if (lesson.isBookmarked) Color(0xFFF59E0B) else Color.White
                                        )
                                    }
                                    // Notes
                                    IconButton(
                                        onClick = { showNotesDialog = true },
                                        modifier = Modifier
                                            .size(48.dp)
                                            .semantics { contentDescription = "ملاحظات الدرس" }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.EditNote,
                                            contentDescription = null,
                                            tint = Color.White
                                        )
                                    }
                                    // Subtitles
                                    IconButton(
                                        onClick = { showSubtitleDialog = true },
                                        modifier = Modifier
                                            .size(48.dp)
                                            .semantics { contentDescription = "خيارات الترجمة المرافقة" }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Subtitles,
                                            contentDescription = null,
                                            tint = if (subtitleMode != SubtitleMode.OFF) Color(0xFF06B6D4) else Color.White
                                        )
                                    }
                                    // Speed
                                    TextButton(
                                        onClick = { showSpeedDialog = true },
                                        modifier = Modifier
                                            .heightIn(min = 48.dp)
                                            .semantics { contentDescription = "سرعة التشغيل: ${playbackSpeed}x" }
                                    ) {
                                        Text(
                                            text = "${playbackSpeed}x",
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp
                                        )
                                    }
                                    // Exit Fullscreen
                                    IconButton(
                                        onClick = { setFullscreen(false) },
                                        modifier = Modifier
                                            .size(48.dp)
                                            .semantics { contentDescription = "الخروج من ملء الشاشة" }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.FullscreenExit,
                                            contentDescription = null,
                                            tint = Color.White
                                        )
                                    }
                                }
                            }

                            // Center Playback Actions (Rewind 10s / Play-Pause / Forward 10s)
                            Row(
                                modifier = Modifier.align(Alignment.Center),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(24.dp)
                            ) {
                                IconButton(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        playerController.seekBackward(10000)
                                    },
                                    modifier = Modifier
                                        .size(54.dp)
                                        .clip(CircleShape)
                                        .background(Color.Black.copy(alpha = 0.4f))
                                        .semantics { contentDescription = "تأخير 10 ثوانٍ" }
                                ) {
                                    Icon(Icons.Default.Replay10, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
                                }

                                IconButton(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        playerController.togglePlayPause()
                                    },
                                    modifier = Modifier
                                        .size(70.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary)
                                        .semantics { contentDescription = if (isPlaying) "إيقاف مؤقت" else "تشغيل" }
                                ) {
                                    Icon(
                                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(38.dp)
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        playerController.seekForward(10000)
                                    },
                                    modifier = Modifier
                                        .size(54.dp)
                                        .clip(CircleShape)
                                        .background(Color.Black.copy(alpha = 0.4f))
                                        .semantics { contentDescription = "تقديم 10 ثوانٍ" }
                                ) {
                                    Icon(Icons.Default.Forward10, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
                                }
                            }

                            // Bottom Navigation & Time Info in Fullscreen
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .navigationBarsPadding()
                                    .padding(horizontal = 24.dp, vertical = 12.dp)
                                    .align(Alignment.BottomCenter)
                            ) {
                                if (duration > 0) {
                                    Slider(
                                        value = currentPosition.toFloat(),
                                        onValueChange = { playerController.seekTo(it.toLong()) },
                                        valueRange = 0f..duration.toFloat(),
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = SliderDefaults.colors(
                                            thumbColor = MaterialTheme.colorScheme.primary,
                                            activeTrackColor = MaterialTheme.colorScheme.primary,
                                            inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                                        )
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "${formatTimeMs(currentPosition)} / ${formatTimeMs(duration)}",
                                            color = Color.White,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                        if (lesson.hasArabicAudio && audioTrackMode == AudioTrackMode.ARABIC) {
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Surface(
                                                color = Color(0xFF10B981).copy(alpha = 0.2f),
                                                shape = RoundedCornerShape(4.dp)
                                            ) {
                                                Text(
                                                    text = "🎙️ صوت عربي",
                                                    color = Color(0xFF34D399),
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }

                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (prevLesson != null) {
                                            IconButton(
                                                onClick = { onNavigateLesson(prevLesson) },
                                                modifier = Modifier
                                                    .size(48.dp)
                                                    .semantics { contentDescription = "الدرس السابق" }
                                            ) {
                                                Icon(Icons.Default.SkipPrevious, contentDescription = null, tint = Color.White)
                                            }
                                        }
                                        if (nextLesson != null) {
                                            FilledTonalIconButton(
                                                onClick = { onNavigateLesson(nextLesson) },
                                                modifier = Modifier
                                                    .size(48.dp)
                                                    .semantics { contentDescription = "الدرس التالي" }
                                            ) {
                                                Icon(Icons.Default.SkipNext, contentDescription = null)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

            } else {
                // ==========================================
                // NORMAL (PORTRAIT) MODE
                // ==========================================
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0F172A))
                ) {
                    // Top App Bar
                    TopAppBar(
                        title = {
                            Column {
                                Text(
                                    text = lesson.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = courseTitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF94A3B8)
                                )
                            }
                        },
                        navigationIcon = {
                            IconButton(
                                onClick = handleBackPress,
                                modifier = Modifier
                                    .size(48.dp)
                                    .semantics { contentDescription = "الرجوع" }
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = null,
                                    tint = Color.White
                                )
                            }
                        },
                        actions = {
                            // Audio Track & Dubbing Button 🎙️
                            IconButton(
                                onClick = { showAudioDialog = true },
                                modifier = Modifier
                                    .size(48.dp)
                                    .semantics { contentDescription = "المسار الصوتي والدبلجة العربية" }
                            ) {
                                Icon(
                                    imageVector = if (lesson.hasArabicAudio) Icons.Default.RecordVoiceOver else Icons.Default.Audiotrack,
                                    contentDescription = null,
                                    tint = if (audioTrackMode == AudioTrackMode.ARABIC) Color(0xFF10B981) else Color.White
                                )
                            }
                            // Bookmark ⭐
                            IconButton(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onToggleBookmark(!lesson.isBookmarked)
                                },
                                modifier = Modifier
                                    .size(48.dp)
                                    .semantics { contentDescription = if (lesson.isBookmarked) "إزالة من المفضلة" else "إضافة للمفضلة" }
                            ) {
                                Icon(
                                    imageVector = if (lesson.isBookmarked) Icons.Default.Star else Icons.Default.StarBorder,
                                    contentDescription = null,
                                    tint = if (lesson.isBookmarked) Color(0xFFF59E0B) else Color.White
                                )
                            }
                            // Subtitles
                            IconButton(
                                onClick = { showSubtitleDialog = true },
                                modifier = Modifier
                                    .size(48.dp)
                                    .semantics { contentDescription = "خيارات الترجمة المرافقة" }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Subtitles,
                                    contentDescription = null,
                                    tint = if (subtitleMode != SubtitleMode.OFF) Color(0xFF06B6D4) else Color.White
                                )
                            }
                            // Speed
                            TextButton(
                                onClick = { showSpeedDialog = true },
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .semantics { contentDescription = "سرعة التشغيل: ${playbackSpeed}x" }
                            ) {
                                Text(
                                    text = "${playbackSpeed}x",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                            }
                            // Fullscreen Enter
                            IconButton(
                                onClick = { setFullscreen(true) },
                                modifier = Modifier
                                    .size(48.dp)
                                    .semantics { contentDescription = "ملء الشاشة" }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Fullscreen,
                                    contentDescription = null,
                                    tint = Color.White
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color(0xFF0F172A)
                        )
                    )

                    // 16:9 Video Viewport with Gestures
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .background(Color.Black)
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onDoubleTap = { offset ->
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        if (offset.x < size.width / 2) {
                                            playerController.seekBackward(10000)
                                            seekRippleSide = "left"
                                            seekRippleText = "-10 ثوانٍ"
                                        } else {
                                            playerController.seekForward(10000)
                                            seekRippleSide = "right"
                                            seekRippleText = "+10 ثوانٍ"
                                        }
                                    }
                                )
                            }
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                PlayerView(ctx).apply {
                                    player = playerController.exoPlayer
                                    useController = true
                                    subtitleView?.apply {
                                        visibility = android.view.View.VISIBLE
                                        setFractionalTextSize(androidx.media3.ui.SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * subtitleSize.scale)
                                        setBottomPaddingFraction(0.06f)
                                        setStyle(
                                            androidx.media3.ui.CaptionStyleCompat(
                                                android.graphics.Color.WHITE,
                                                android.graphics.Color.argb(180, 15, 23, 42),
                                                android.graphics.Color.TRANSPARENT,
                                                androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
                                                android.graphics.Color.BLACK,
                                                null
                                            )
                                        )
                                    }
                                    layoutParams = FrameLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                }
                            },
                            update = { playerView ->
                                if (playerView.player != playerController.exoPlayer) {
                                    playerView.player = playerController.exoPlayer
                                }
                                playerView.subtitleView?.setFractionalTextSize(
                                    androidx.media3.ui.SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * subtitleSize.scale
                                )
                            },
                            modifier = Modifier.fillMaxSize()
                        )

                        if (subtitleMode == SubtitleMode.DUAL && dualSubtitles != null) {
                            DualSubtitleOverlay(
                                dual = dualSubtitles!!,
                                subtitleSize = subtitleSize,
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(horizontal = 16.dp, vertical = 12.dp)
                            )
                        }

                        if (playerError != null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.85f))
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        Icons.Default.ErrorOutline,
                                        contentDescription = null,
                                        tint = Color(0xFFFCA5A5),
                                        modifier = Modifier.size(34.dp)
                                    )
                                    Text(
                                        text = playerError ?: "",
                                        color = Color.White,
                                        style = MaterialTheme.typography.bodySmall,
                                        textAlign = TextAlign.Center
                                    )
                                    FilledTonalIconButton(
                                        onClick = { playerController.retry() },
                                        modifier = Modifier.size(48.dp).semantics { contentDescription = "إعادة محاولة التشغيل" }
                                    ) {
                                        Icon(Icons.Default.Refresh, contentDescription = null)
                                    }
                                }
                            }
                        } else if (isBuffering) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    color = Color(0xFF38BDF8),
                                    strokeWidth = 3.dp,
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                        }

                        // Seek Ripple in Portrait
                        seekRippleSide?.let { side ->
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .width(100.dp)
                                    .align(if (side == "left") Alignment.CenterStart else Alignment.CenterEnd)
                                    .background(Color.White.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        imageVector = if (side == "left") Icons.Default.Replay10 else Icons.Default.Forward10,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(36.dp)
                                    )
                                    Text(
                                        text = seekRippleText ?: "",
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }

                        // Fullscreen Corner Button (>=48dp touch target)
                        IconButton(
                            onClick = { setFullscreen(true) },
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(8.dp)
                                .size(48.dp)
                                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                                .semantics { contentDescription = "ملء الشاشة" }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Fullscreen,
                                contentDescription = null,
                                tint = Color.White
                            )
                        }
                    }

                    // Below Video: Navigation, Notes & Study Tools
                    Surface(
                        color = Color(0xFF1E293B),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            // Previous / Next / Seek / AI Tutor Control Row
                            item {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceEvenly,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconButton(
                                        onClick = { prevLesson?.let { onNavigateLesson(it) } },
                                        enabled = prevLesson != null,
                                        modifier = Modifier
                                            .size(48.dp)
                                            .semantics { contentDescription = "الدرس السابق" }
                                    ) {
                                        Icon(
                                            Icons.Default.SkipPrevious,
                                            contentDescription = null,
                                            tint = if (prevLesson != null) Color.White else Color(0xFF475569)
                                        )
                                    }

                                    IconButton(
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            playerController.seekBackward(10000)
                                        },
                                        modifier = Modifier
                                            .size(48.dp)
                                            .semantics { contentDescription = "تأخير 10 ثوانٍ" }
                                    ) {
                                        Icon(Icons.Default.Replay10, contentDescription = null, tint = Color.White)
                                    }

                                    FilledTonalIconButton(
                                        onClick = { onTutor(currentPosition) },
                                        modifier = Modifier
                                            .size(48.dp)
                                            .semantics { contentDescription = "المعلّم الذكي" }
                                    ) {
                                        Icon(Icons.Default.SmartToy, contentDescription = null)
                                    }

                                    if (hasConfiguredAi && !lesson.hasArabicSubtitles && lesson.hasEnglishSubtitles) {
                                        if (translationState != null) {
                                            Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                                                CircularProgressIndicator(
                                                    progress = { translationState.progress.coerceIn(0f, 1f) },
                                                    color = Color(0xFF38BDF8),
                                                    strokeWidth = 2.5.dp,
                                                    modifier = Modifier.size(32.dp)
                                                )
                                                IconButton(
                                                    onClick = onCancelTranslation,
                                                    modifier = Modifier
                                                        .size(48.dp)
                                                        .semantics { contentDescription = "إلغاء الترجمة العربية" }
                                                ) {
                                                    Icon(Icons.Default.Close, contentDescription = null, tint = Color(0xFFFCA5A5), modifier = Modifier.size(16.dp))
                                                }
                                            }
                                        } else {
                                            FilledTonalIconButton(
                                                onClick = onTranslateSubtitlesToArabic,
                                                modifier = Modifier
                                                    .size(48.dp)
                                                    .semantics { contentDescription = "ترجمة النص الإنجليزي للعربية بالذكاء الاصطناعي" }
                                            ) {
                                                Icon(Icons.Default.Translate, contentDescription = null)
                                            }
                                        }
                                    }

                                    IconButton(
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            playerController.seekForward(10000)
                                        },
                                        modifier = Modifier
                                            .size(48.dp)
                                            .semantics { contentDescription = "تقديم 10 ثوانٍ" }
                                    ) {
                                        Icon(Icons.Default.Forward10, contentDescription = null, tint = Color.White)
                                    }

                                    FilledIconButton(
                                        onClick = { nextLesson?.let { onNavigateLesson(it) } },
                                        enabled = nextLesson != null,
                                        modifier = Modifier
                                            .size(48.dp)
                                            .semantics { contentDescription = "الدرس التالي" },
                                        colors = IconButtonDefaults.filledIconButtonColors(
                                            containerColor = MaterialTheme.colorScheme.primary,
                                            disabledContainerColor = Color(0xFF334155)
                                        )
                                    ) {
                                        Icon(Icons.Default.SkipNext, contentDescription = null)
                                    }
                                }
                            }

                            // Compact Study & AI Vision Icon Bar (Transcript / Study Pack / Frame Vision)
                            item {
                                Surface(
                                    color = Color(0xFF0F172A),
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 8.dp, vertical = 6.dp),
                                        horizontalArrangement = Arrangement.SpaceEvenly,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            IconButton(
                                                onClick = { showTranscriptSheet = true },
                                                modifier = Modifier
                                                    .size(48.dp)
                                                    .semantics { contentDescription = "النص التفاعلي للدرس" }
                                            ) {
                                                Icon(Icons.Default.Article, contentDescription = null, tint = Color.White)
                                            }
                                            Text(
                                                text = "النص التفاعلي",
                                                color = Color(0xFFE2E8F0),
                                                fontSize = 11.sp
                                            )
                                        }

                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            IconButton(
                                                onClick = onStudyPack,
                                                modifier = Modifier
                                                    .size(48.dp)
                                                    .semantics { contentDescription = "حزمة الدراسة الذكية" }
                                            ) {
                                                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Color(0xFF38BDF8))
                                            }
                                            Text(
                                                text = "ملخص واختبار",
                                                color = Color(0xFF38BDF8),
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }

                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            IconButton(
                                                onClick = captureFrameAndAsk,
                                                enabled = !isCapturingFrame,
                                                modifier = Modifier
                                                    .size(48.dp)
                                                    .semantics { contentDescription = "اشرح اللقطة الحالية بالذكاء الاصطناعي" }
                                            ) {
                                                if (isCapturingFrame) {
                                                    CircularProgressIndicator(
                                                        color = Color(0xFF38BDF8),
                                                        strokeWidth = 2.dp,
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                } else {
                                                    Icon(Icons.Default.PhotoCamera, contentDescription = null, tint = Color.White)
                                                }
                                            }
                                            Text(
                                                text = "اشرح اللقطة",
                                                color = Color(0xFFE2E8F0),
                                                fontSize = 11.sp
                                            )
                                        }
                                    }
                                }
                            }

                            translationState?.let { tr ->
                                item {
                                    Surface(
                                        color = Color(0xFF0F172A),
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(12.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = tr.stage,
                                                    color = Color.White,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                Text(
                                                    text = "${(tr.progress * 100).toInt()}%",
                                                    color = Color(0xFF38BDF8),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                            LinearProgressIndicator(
                                                progress = { tr.progress.coerceIn(0f, 1f) },
                                                color = Color(0xFF38BDF8),
                                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(3.dp))
                                            )
                                        }
                                    }
                                }
                            }

                            // Audio & Dubbing Banner Card
                            item {
                                Card(
                                    shape = RoundedCornerShape(14.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (lesson.hasArabicAudio) Color(0xFF064E3B) else Color(0xFF0F172A)
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(role = Role.Button, onClickLabel = "إعدادات المسار الصوتي") {
                                            showAudioDialog = true
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .padding(14.dp)
                                            .fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = if (lesson.hasArabicAudio) Icons.Default.RecordVoiceOver else Icons.Default.Audiotrack,
                                                contentDescription = null,
                                                tint = if (lesson.hasArabicAudio) Color(0xFF34D399) else MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(24.dp)
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Column {
                                                Text(
                                                    text = if (lesson.hasArabicAudio) "دبلجة عربية مدمجة بالدرس 🎙️" else "استخراج الصوت أو دبلجة الدرس بالعربية",
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color.White,
                                                    fontSize = 14.sp
                                                )
                                                Text(
                                                    text = if (lesson.hasArabicAudio) "الصوت الحالي: ${audioTrackMode.labelAr}" else "انقر لاستخراج الصوت لترجمته بـ Gemini أو ربط دبلجة",
                                                    color = Color(0xFF94A3B8),
                                                    fontSize = 12.sp
                                                )
                                            }
                                        }

                                        Icon(
                                            imageVector = Icons.Default.ChevronLeft,
                                            contentDescription = null,
                                            tint = Color.White.copy(alpha = 0.7f)
                                        )
                                    }
                                }
                            }

                            // Study Notes Header & Add Box
                            item {
                                StudyNotesSection(
                                    currentPosition = currentPosition,
                                    notes = notes,
                                    onSeekToTimestamp = { ts -> playerController.seekTo(ts) },
                                    onAddNote = { text -> onAddNote(currentPosition, text) },
                                    onDeleteNote = onDeleteNote
                                )
                            }
                        }
                    }
                }
            }

            // Audio Extraction & Dubbing Dialog
            if (showAudioDialog) {
                AlertDialog(
                    onDismissRequest = { showAudioDialog = false },
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.RecordVoiceOver, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("المسار الصوتي والدبلجة (Audio & AI)", fontWeight = FontWeight.Bold)
                        }
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            if (lesson.hasArabicAudio) {
                                Text("اختر المسار الصوتي لتشغيل الدرس:", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)

                                // Radio options
                                AudioTrackMode.values().forEach { mode ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable {
                                                playerController.setAudioTrackMode(mode)
                                                showAudioDialog = false
                                            }
                                            .padding(vertical = 10.dp, horizontal = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = audioTrackMode == mode,
                                            onClick = {
                                                playerController.setAudioTrackMode(mode)
                                                showAudioDialog = false
                                            }
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(mode.labelAr, style = MaterialTheme.typography.bodyMedium)
                                    }
                                }

                                HorizontalDivider()

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            showAudioDialog = false
                                            showAttachAudioBottomSheet = true
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .heightIn(min = 44.dp),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text("استبدال الصوت", fontSize = 12.sp)
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            coroutineScope.launch {
                                                onRemoveArabicAudio(lesson)
                                                playerController.loadLesson(lesson.copy(audioArPath = null))
                                                showAudioDialog = false
                                                snackbarHostState.showSnackbar("تمت إزالة الدبلجة الصوتية والعودة للصوت الأصلي")
                                            }
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .heightIn(min = 44.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text("حذف الدبلجة", fontSize = 12.sp)
                                    }
                                }
                            } else {
                                Text(
                                    text = "يعمل هذا الدرس بالصوت الإنجليزي الأصلي. يمكنك الآن استخراج الصوت بضغطة زر وإرساله إلى Gemini أو منصات الذكاء الاصطناعي لدبلجته، ثم ربط ملف الصوت الناتج ليعمل متزامناً باللغة العربية!",
                                    style = MaterialTheme.typography.bodySmall,
                                    lineHeight = 20.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Spacer(modifier = Modifier.height(4.dp))

                                Button(
                                    onClick = {
                                        showAudioDialog = false
                                        handleExtractAudio()
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("استخراج الصوت ومشاركته (Gemini / AI)")
                                }

                                FilledTonalButton(
                                    onClick = {
                                        showAudioDialog = false
                                        showAttachAudioBottomSheet = true
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("ربط ملف دبلجة عربي (.m4a / .mp3)")
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showAudioDialog = false }) {
                            Text("إغلاق")
                        }
                    }
                )
            }

            // Pre-flight Audio Upload Bottom Sheet (Safe Selection with Cancel)
            if (showAttachAudioBottomSheet) {
                FileUploadBottomSheet(
                    title = "ربط ملف الدبلجة الصوتية العربية",
                    description = "اختر ملف الصوت العربي (.m4a أو .mp3) لدبلجة درس \"${lesson.title}\". سيعمل الصوت العربي متزامناً بالكامل مع الفيديو.",
                    supportedFormatsHint = "الصيغ الصوتية المدعومة: M4A, MP3, AAC, WAV",
                    icon = Icons.Default.RecordVoiceOver,
                    iconTint = Color(0xFF10B981),
                    confirmButtonText = "اختيار ملف صوتي من الذاكرة",
                    onConfirmBrowse = {
                        showAttachAudioBottomSheet = false
                        attachAudioLauncher.launch(arrayOf("audio/*", "application/octet-stream"))
                    },
                    onDismiss = {
                        showAttachAudioBottomSheet = false
                    }
                )
            }

            // Extraction Loading Dialog
            if (isExtractingAudio) {
                AlertDialog(
                    onDismissRequest = {},
                    confirmButton = {},
                    title = { Text("جاري استخراج الصوت...", fontWeight = FontWeight.Bold) },
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(16.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(36.dp))
                            Spacer(modifier = Modifier.width(16.dp))
                            Text("يتم فك وضغط المسار الصوتي بصيغة .m4a عالية النقاء...", fontSize = 13.sp)
                        }
                    }
                )
            }

            // Notes Sheet / Dialog for Fullscreen
            if (showNotesDialog) {
                AlertDialog(
                    onDismissRequest = { showNotesDialog = false },
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.EditNote, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("ملاحظات الدرس", fontWeight = FontWeight.Bold)
                        }
                    },
                    text = {
                        Box(modifier = Modifier.sizeIn(maxHeight = 360.dp, maxWidth = 480.dp)) {
                            StudyNotesSection(
                                currentPosition = currentPosition,
                                notes = notes,
                                onSeekToTimestamp = { ts ->
                                    playerController.seekTo(ts)
                                    showNotesDialog = false
                                },
                                onAddNote = { text -> onAddNote(currentPosition, text) },
                                onDeleteNote = onDeleteNote
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showNotesDialog = false }) {
                            Text("إغلاق")
                        }
                    }
                )
            }

            // Subtitle Language & Font Size Settings Dialog
            if (showSubtitleDialog) {
                AlertDialog(
                    onDismissRequest = { showSubtitleDialog = false },
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Subtitles,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("إعدادات الترجمة والخط", fontWeight = FontWeight.Bold)
                        }
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            // Section 1: Subtitle Language
                            Text(
                                text = "لغة الترجمة المرافقة:",
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.titleSmall
                            )

                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                SubtitleMode.values().forEach { mode ->
                                    Surface(
                                        color = if (subtitleMode == mode) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else Color.Transparent,
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                playerController.setSubtitleMode(mode)
                                            }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            RadioButton(
                                                selected = subtitleMode == mode,
                                                onClick = {
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    playerController.setSubtitleMode(mode)
                                                }
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = mode.labelAr,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = if (subtitleMode == mode) FontWeight.Bold else FontWeight.Normal
                                            )
                                        }
                                    }
                                }
                            }

                            if (hasConfiguredAi && !lesson.hasArabicSubtitles && lesson.hasEnglishSubtitles) {
                                Surface(
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = translationState?.stage
                                                ?: "تتوفر ترجمة إنجليزية فقط — أنتج نسخة عربية بالذكاء الاصطناعي",
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (translationState != null) {
                                            IconButton(
                                                onClick = onCancelTranslation,
                                                modifier = Modifier.size(44.dp).semantics { contentDescription = "إلغاء الترجمة العربية" }
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    CircularProgressIndicator(
                                                        progress = { translationState.progress.coerceIn(0f, 1f) },
                                                        strokeWidth = 2.5.dp,
                                                        modifier = Modifier.size(28.dp)
                                                    )
                                                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                                                }
                                            }
                                        } else {
                                            FilledTonalIconButton(
                                                onClick = {
                                                    onTranslateSubtitlesToArabic()
                                                    showSubtitleDialog = false
                                                },
                                                modifier = Modifier.size(44.dp).semantics { contentDescription = "ترجمة الإنجليزية للعربية بالذكاء الاصطناعي" }
                                            ) {
                                                Icon(Icons.Default.Translate, contentDescription = null)
                                            }
                                        }
                                    }
                                }
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                            // Section 2: Subtitle Font Size Controls
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.FormatSize,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "حجم خط الترجمة:",
                                        fontWeight = FontWeight.SemiBold,
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                }

                                Text(
                                    text = subtitleSize.labelAr,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            }

                            // 4 Size Segmented Chips
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                SubtitleSize.values().forEach { size ->
                                    FilterChip(
                                        selected = subtitleSize == size,
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            playerController.setSubtitleSize(size)
                                        },
                                        label = {
                                            Text(
                                                text = size.labelAr,
                                                fontSize = 11.sp,
                                                maxLines = 1,
                                                textAlign = TextAlign.Center,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }

                            // Live Subtitle Preview Box
                            Surface(
                                color = Color(0xFF0F172A),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(10.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = "معاينة حجم الخط الفعلي:",
                                        fontSize = 11.sp,
                                        color = Color(0xFF94A3B8),
                                        modifier = Modifier.padding(bottom = 6.dp)
                                    )
                                    Surface(
                                        color = Color.Black.copy(alpha = 0.75f),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            text = if (subtitleMode == SubtitleMode.ENGLISH) {
                                                "This is a preview of the subtitles font."
                                            } else {
                                                "هكذا سيظهر حجم خط الترجمة أثناء دراسة الدرس."
                                            },
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = subtitleSize.previewSp.sp,
                                            textAlign = TextAlign.Center,
                                            lineHeight = (subtitleSize.previewSp + 6).sp,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                        )
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = { showSubtitleDialog = false },
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("تم", fontWeight = FontWeight.Bold)
                        }
                    }
                )
            }

            // Playback Speed Selection Dialog
            if (showSpeedDialog) {
                val speeds = listOf(0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
                AlertDialog(
                    onDismissRequest = { showSpeedDialog = false },
                    title = { Text("سرعة تشغيل الفيديو") },
                    text = {
                        Column {
                            speeds.forEach { spd ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            playerController.setPlaybackSpeed(spd)
                                            showSpeedDialog = false
                                        }
                                        .padding(vertical = 10.dp, horizontal = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = playbackSpeed == spd,
                                        onClick = {
                                            playerController.setPlaybackSpeed(spd)
                                            showSpeedDialog = false
                                        }
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = if (spd == 1.0f) "1.0x (السرعة الطبيعية)" else "${spd}x",
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showSpeedDialog = false }) {
                            Text("إغلاق")
                        }
                    }
                )
            }

            // First-run non-intrusive gesture hints overlay
            GestureTutorialOverlay(
                isVisible = showGestureTutorial,
                onDismiss = { showGestureTutorial = false }
            )

            if (showTranscriptSheet) {
                com.ilearn.app.ui.components.TranscriptSheet(
                    lesson = lesson,
                    currentPositionMs = currentPosition,
                    onSeek = {
                        showTranscriptSheet = false
                        playerController.seekTo(it)
                    },
                    onDismiss = { showTranscriptSheet = false }
                )
            }
        }
    }
}

@Composable
private fun DualSubtitleOverlay(
    dual: Pair<String?, String?>,
    subtitleSize: SubtitleSize,
    modifier: Modifier = Modifier
) {
    val ar = dual.first?.takeIf { it.isNotBlank() }
    val en = dual.second?.takeIf { it.isNotBlank() }
    if (ar == null && en == null) return

    Surface(
        color = Color(0xDE0F172A),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.widthIn(max = 540.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            if (ar != null) {
                Text(
                    text = ar,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = (14f * subtitleSize.scale).sp,
                    lineHeight = (19f * subtitleSize.scale).sp,
                    textAlign = TextAlign.Center
                )
            }
            if (en != null) {
                Text(
                    text = en,
                    color = Color(0xFF7DD3FC),
                    fontWeight = FontWeight.Medium,
                    fontSize = (12.5f * subtitleSize.scale).sp,
                    lineHeight = (17f * subtitleSize.scale).sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun StudyNotesSection(
    currentPosition: Long,
    notes: List<LessonNote>,
    onSeekToTimestamp: (Long) -> Unit,
    onAddNote: (String) -> Unit,
    onDeleteNote: (String) -> Unit
) {
    var noteInput by remember { mutableStateOf("") }
    val haptic = LocalHapticFeedback.current
    val maxChars = 300

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.EditNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "ملاحظات الدرس (${notes.size})",
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall
                    )
                }

                Surface(
                    color = Color(0xFF38BDF8).copy(alpha = 0.18f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = formatTimeMs(currentPosition),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF38BDF8),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Add Note Input Box with character count and validation
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                OutlinedTextField(
                    value = noteInput,
                    onValueChange = {
                        if (it.length <= maxChars) {
                            noteInput = it
                        }
                    },
                    placeholder = { Text("أضف ملاحظة عند ${formatTimeMs(currentPosition)}...", fontSize = 13.sp) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    singleLine = true,
                    supportingText = {
                        Text(
                            text = "${noteInput.length} / $maxChars",
                            fontSize = 11.sp,
                            color = if (noteInput.length >= maxChars) MaterialTheme.colorScheme.error else Color(0xFF94A3B8)
                        )
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = Color(0xFF334155)
                    )
                )

                Spacer(modifier = Modifier.width(8.dp))

                IconButton(
                    onClick = {
                        if (noteInput.isNotBlank()) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onAddNote(noteInput.trim())
                            noteInput = ""
                        }
                    },
                    enabled = noteInput.isNotBlank(),
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (noteInput.isNotBlank()) MaterialTheme.colorScheme.primary
                            else Color(0xFF334155)
                        )
                        .semantics { contentDescription = "حفظ الملاحظة" }
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.NoteAdd,
                        contentDescription = null,
                        tint = if (noteInput.isNotBlank()) Color.White else Color(0xFF94A3B8)
                    )
                }
            }

            // Existing Notes List or Contextual Empty State
            if (notes.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    notes.forEach { note ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF1E293B))
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Clickable Timestamp Badge
                            Surface(
                                color = Color(0xFF38BDF8).copy(alpha = 0.2f),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier
                                    .clickable { onSeekToTimestamp(note.timestampMs) }
                                    .semantics {
                                        contentDescription = "الانتقال إلى التوقيت ${formatTimeMs(note.timestampMs)}"
                                    }
                            ) {
                                Text(
                                    text = formatTimeMs(note.timestampMs),
                                    color = Color(0xFF38BDF8),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(10.dp))

                            Text(
                                text = note.noteText,
                                color = Color.White,
                                fontSize = 13.sp,
                                modifier = Modifier.weight(1f)
                            )

                            IconButton(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onDeleteNote(note.id)
                                },
                                modifier = Modifier
                                    .size(48.dp)
                                    .semantics { contentDescription = "حذف الملاحظة" }
                            ) {
                                Icon(
                                    Icons.Default.DeleteOutline,
                                    contentDescription = null,
                                    tint = Color(0xFF94A3B8),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            } else {
                Spacer(modifier = Modifier.height(8.dp))
                ContextualEmptyState(
                    icon = Icons.Default.EditNote,
                    iconTint = MaterialTheme.colorScheme.primary,
                    title = "لا توجد ملاحظات لهذا الدرس بعد",
                    description = "دوّن أهم الأفكار والمعادلات الرياضية أثناء المشاهدة للرجوع إليها فوراً بنقرة واحدة."
                )
            }
        }
    }
}
