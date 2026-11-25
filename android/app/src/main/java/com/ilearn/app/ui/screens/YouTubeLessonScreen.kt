package com.ilearn.app.ui.screens

import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.ilearn.app.data.Lesson
import com.ilearn.app.data.LessonNote
import com.ilearn.app.tutor.ProviderProfileStore
import com.ilearn.app.youtube.LessonDownloadState
import com.ilearn.app.youtube.YouTubePlaybackSession
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YouTubeLessonScreen(
    lesson: Lesson,
    lessons: List<Lesson>,
    notes: List<LessonNote>,
    profiles: ProviderProfileStore,
    onBack: () -> Unit,
    onNavigate: (Lesson) -> Unit,
    onProgress: (Long, Long) -> Unit,
    onBookmark: (Boolean) -> Unit,
    onAddNote: (Long, String) -> Unit,
    onDeleteNote: (String) -> Unit,
    onSettings: () -> Unit,
    downloadState: LessonDownloadState? = null,
    downloadError: String? = null,
    onDownloadOffline: () -> Unit = {},
    onCancelDownload: () -> Unit = {},
    onDismissDownloadError: () -> Unit = {},
    hasConfiguredAi: Boolean = false,
    translationState: LessonDownloadState? = null,
    translationError: String? = null,
    onTranslateToArabic: () -> Unit = {},
    onCancelTranslation: () -> Unit = {},
    onDismissTranslationError: () -> Unit = {},
    initialSeekMs: Long = -1L,
    externalSeekRequest: Pair<Long, Long>? = null,
    onStudyPack: () -> Unit = {}
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val lifecycle = LocalLifecycleOwner.current
    val session = remember(lesson.id) { YouTubePlaybackSession(context, lesson) }
    val state by session.state.collectAsState()
    val fullscreen by session.fullscreen.collectAsState()
    var tutor by remember(lesson.id) { mutableStateOf(false) }
    var showTranscriptSheet by remember(lesson.id) { mutableStateOf(false) }
    var appliedInitialSeek by remember(lesson.id, initialSeekMs) { mutableStateOf(false) }
    val saveProgress by rememberUpdatedState(onProgress)

    LaunchedEffect(state.ready, initialSeekMs) {
        if (state.ready && initialSeekMs > 0L && !appliedInitialSeek) {
            appliedInitialSeek = true
            session.seekAndPlay(initialSeekMs)
        }
    }

    LaunchedEffect(externalSeekRequest) {
        externalSeekRequest?.first?.let { targetMs ->
            session.seekAndPlay(targetMs)
        }
    }

    fun openExternalYouTube() {
        val videoId = lesson.remoteId ?: return
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$videoId")))
        }
    }

    LaunchedEffect(session) {
        while (true) {
            session.sample()
            val s = session.state.value
            if (s.ready && s.durationMs > 0 && s.positionMs in 1..s.durationMs) {
                saveProgress(s.positionMs, s.durationMs)
            }
            delay(1000)
        }
    }

    DisposableEffect(session, lifecycle) {
        fun save() {
            val s = session.state.value
            if (s.ready && s.durationMs > 0 && s.positionMs in 1..s.durationMs) {
                saveProgress(s.positionMs, s.durationMs)
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    save()
                    session.onHostPause()
                }
                Lifecycle.Event.ON_RESUME -> session.onHostResume()
                else -> Unit
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose {
            save()
            lifecycle.lifecycle.removeObserver(observer)
            session.release()
        }
    }

    LaunchedEffect(fullscreen != null) {
        activity?.let { act ->
            val insetsController = WindowCompat.getInsetsController(act.window, act.window.decorView)
            if (fullscreen != null) {
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

    DisposableEffect(activity) {
        onDispose {
            activity?.let { act ->
                WindowCompat.getInsetsController(act.window, act.window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
                act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }

    BackHandler(enabled = fullscreen != null) {
        session.exitFullscreen()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = lesson.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "درس ${lesson.orderIndex} • YouTube",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.size(48.dp).semantics { contentDescription = "رجوع" }
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { showTranscriptSheet = true },
                            modifier = Modifier.size(44.dp).semantics { contentDescription = "النص التفاعلي للدرس" }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Article,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(
                            onClick = { session.pause(); onStudyPack() },
                            modifier = Modifier.size(44.dp).semantics { contentDescription = "حزمة الدراسة الذكية" }
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        IconButton(
                            onClick = { onBookmark(!lesson.isBookmarked) },
                            modifier = Modifier.size(48.dp).semantics {
                                contentDescription = if (lesson.isBookmarked) "إزالة من المفضلة" else "حفظ في المفضلة"
                            }
                        ) {
                            Icon(
                                imageVector = if (lesson.isBookmarked) Icons.Default.Star else Icons.Default.StarBorder,
                                contentDescription = null,
                                tint = if (lesson.isBookmarked) Color(0xFFF59E0B) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                // Keep the WebView player outside verticalScroll so RenderThread never clips/scrolls GLFunctorDrawable.
                if (lesson.isAvailable && lesson.remoteId != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .background(Color.Black)
                    ) {
                        key(lesson.id) {
                            AndroidView(
                                factory = { session.createContainerView() },
                                onRelease = { session.release() },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .testTag("youtube-player")
                                    .semantics {
                                        stateDescription = when {
                                            state.error != null -> "Error"
                                            state.playing -> "Playing"
                                            state.ready -> "Ready"
                                            else -> "Loading"
                                        }
                                    }
                            )
                        }
                        if (!state.ready && state.error == null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.55f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    CircularProgressIndicator(
                                        color = Color(0xFF38BDF8),
                                        strokeWidth = 3.dp,
                                        modifier = Modifier.size(34.dp)
                                    )
                                    Text(
                                        text = "جاري تحميل الفيديو من يوتيوب…",
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "هذا الفيديو غير متاح للتشغيل المدمج داخل التطبيق. ملاحظاتك وتقدّمك محفوظان.",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            if (lesson.remoteId != null) {
                                OutlinedIconButton(
                                    onClick = ::openExternalYouTube,
                                    modifier = Modifier.size(48.dp).semantics { contentDescription = "فتح في YouTube" }
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                                }
                            }
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    state.error?.let { err ->
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = err,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f)
                                )
                                FilledTonalIconButton(
                                    onClick = { session.retry() },
                                    modifier = Modifier.size(44.dp).semantics { contentDescription = "إعادة المحاولة" }
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = null)
                                }
                                if (lesson.remoteId != null) {
                                    OutlinedIconButton(
                                        onClick = ::openExternalYouTube,
                                        modifier = Modifier.size(44.dp).semantics { contentDescription = "فتح في YouTube" }
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                                    }
                                }
                            }
                        }
                    }

                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val index = lessons.indexOfFirst { it.id == lesson.id }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    enabled = index > 0,
                                    onClick = { if (index > 0) onNavigate(lessons[index - 1]) },
                                    modifier = Modifier.size(44.dp).semantics { contentDescription = "الدرس السابق" }
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.NavigateBefore, contentDescription = null)
                                }
                                Text(
                                    text = "${formatTimeMs(state.positionMs)} / ${formatTimeMs(state.durationMs)}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                IconButton(
                                    enabled = index >= 0 && index < lessons.lastIndex,
                                    onClick = { if (index >= 0 && index < lessons.lastIndex) onNavigate(lessons[index + 1]) },
                                    modifier = Modifier.size(44.dp).semantics { contentDescription = "الدرس التالي" }
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.NavigateNext, contentDescription = null)
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                FilledTonalIconButton(
                                    onClick = { session.pause(); tutor = true },
                                    modifier = Modifier.size(44.dp).semantics { contentDescription = "المعلّم الذكي" }
                                ) {
                                    Icon(Icons.Default.SmartToy, contentDescription = null, modifier = Modifier.size(20.dp))
                                }

                                val canTranslateAr = hasConfiguredAi && !lesson.hasArabicSubtitles &&
                                    (lesson.hasEnglishSubtitles || (lesson.isAvailable && !lesson.remoteId.isNullOrBlank()))
                                if (canTranslateAr) {
                                    if (translationState != null) {
                                        IconButton(
                                            onClick = onCancelTranslation,
                                            modifier = Modifier.size(44.dp).semantics { contentDescription = "إلغاء الترجمة" }
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
                                        IconButton(
                                            onClick = onTranslateToArabic,
                                            modifier = Modifier.size(44.dp).semantics { contentDescription = "ترجمة النص للعربية بالذكاء الاصطناعي" }
                                        ) {
                                            Icon(Icons.Default.Translate, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(20.dp))
                                        }
                                    }
                                }

                                if (lesson.isAvailable && lesson.remoteId != null) {
                                    if (downloadState != null) {
                                        IconButton(
                                            onClick = onCancelDownload,
                                            modifier = Modifier.size(44.dp).semantics { contentDescription = "إلغاء التحميل" }
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                CircularProgressIndicator(
                                                    progress = { downloadState.progress.coerceIn(0f, 1f) },
                                                    strokeWidth = 2.5.dp,
                                                    modifier = Modifier.size(28.dp)
                                                )
                                                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                                            }
                                        }
                                    } else {
                                        IconButton(
                                            onClick = onDownloadOffline,
                                            modifier = Modifier.size(44.dp).semantics { contentDescription = "تحميل أوفلاين 360p" }
                                        ) {
                                            Icon(Icons.Default.DownloadForOffline, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                }

                                IconButton(
                                    enabled = lesson.remoteId != null,
                                    onClick = ::openExternalYouTube,
                                    modifier = Modifier.size(44.dp).semantics { contentDescription = "فتح في YouTube" }
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }

                    translationState?.let { tr ->
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(tr.stage, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                    Text("${(tr.progress * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                }
                                LinearProgressIndicator(
                                    progress = { tr.progress.coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }

                    translationError?.let { err ->
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = err,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = { onDismissTranslationError(); onTranslateToArabic() },
                                    modifier = Modifier.size(40.dp).semantics { contentDescription = "إعادة محاولة الترجمة" }
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                                }
                                IconButton(
                                    onClick = onDismissTranslationError,
                                    modifier = Modifier.size(40.dp).semantics { contentDescription = "إخفاء الخطأ" }
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                                }
                            }
                        }
                    }

                    downloadState?.let { dl ->
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(dl.stage, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                    Text("${(dl.progress * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                }
                                LinearProgressIndicator(
                                    progress = { dl.progress.coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }

                    downloadError?.let { err ->
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = err,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = { onDismissDownloadError(); onDownloadOffline() },
                                    modifier = Modifier.size(40.dp).semantics { contentDescription = "إعادة التحميل" }
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                                }
                                IconButton(
                                    onClick = onDismissDownloadError,
                                    modifier = Modifier.size(40.dp).semantics { contentDescription = "إخفاء الخطأ" }
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                                }
                            }
                        }
                    }

                    StudyNotesSection(
                        currentPosition = state.positionMs,
                        notes = notes,
                        onSeekToTimestamp = { session.seekAndPlay(it) },
                        onAddNote = { onAddNote(state.positionMs, it) },
                        onDeleteNote = onDeleteNote
                    )
                }
            }
        }

        fullscreen?.let { customView ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                AndroidView(
                    factory = { ctx ->
                        (customView.parent as? ViewGroup)?.removeView(customView)
                        FrameLayout(ctx).apply {
                            addView(
                                customView,
                                FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                            )
                        }
                    },
                    onRelease = { (customView.parent as? ViewGroup)?.removeView(customView) },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    if (showTranscriptSheet) {
        com.ilearn.app.ui.components.TranscriptSheet(
            lesson = lesson,
            currentPositionMs = state.positionMs,
            onSeek = {
                showTranscriptSheet = false
                session.seekAndPlay(it)
            },
            onDismiss = { showTranscriptSheet = false }
        )
    }

    if (tutor) {
        TutorDialog(
            lesson = lesson,
            positionMs = state.positionMs,
            profiles = profiles,
            translationState = translationState,
            translationError = translationError,
            onTranslateToArabic = if (hasConfiguredAi && !lesson.hasArabicSubtitles) onTranslateToArabic else null,
            onCancelTranslation = onCancelTranslation,
            onDismissTranslationError = onDismissTranslationError,
            onSeek = { session.seekAndPlay(it); tutor = false },
            onSettings = onSettings,
            onClose = { tutor = false }
        )
    }
}
