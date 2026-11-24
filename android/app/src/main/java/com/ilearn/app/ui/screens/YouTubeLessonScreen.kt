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
    onSettings: () -> Unit
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val lifecycle = LocalLifecycleOwner.current
    val session = remember(lesson.id) { YouTubePlaybackSession(context, lesson) }
    val state by session.state.collectAsState()
    val fullscreen by session.fullscreen.collectAsState()
    var tutor by remember(lesson.id) { mutableStateOf(false) }
    val saveProgress by rememberUpdatedState(onProgress)

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
                            modifier = Modifier.size(48.dp).semantics { contentDescription = "Back" }
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        }
                    },
                    actions = {
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
                    }
                } else {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "هذا الفيديو غير متاح للتشغيل المدمج داخل التطبيق. ملاحظاتك وتقدّمك محفوظان.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (lesson.remoteId != null) {
                                OutlinedButton(onClick = ::openExternalYouTube) {
                                    Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("فتح في YouTube")
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
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = err,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilledTonalButton(onClick = { session.retry() }) {
                                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("إعادة المحاولة")
                                    }
                                    if (lesson.remoteId != null) {
                                        OutlinedButton(onClick = ::openExternalYouTube) {
                                            Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("YouTube ↗")
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { session.pause(); tutor = true },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.Default.SmartToy, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("المعلّم الذكي • AI Tutor", fontWeight = FontWeight.Bold, maxLines = 1)
                        }

                        FilledTonalButton(
                            enabled = lesson.remoteId != null,
                            onClick = ::openExternalYouTube,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("YouTube")
                        }
                    }

                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val index = lessons.indexOfFirst { it.id == lesson.id }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                enabled = index > 0,
                                onClick = { if (index > 0) onNavigate(lessons[index - 1]) }
                            ) {
                                Icon(Icons.AutoMirrored.Filled.NavigateBefore, contentDescription = null)
                                Text("السابق")
                            }

                            Text(
                                text = "${formatTimeMs(state.positionMs)} / ${formatTimeMs(state.durationMs)}",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )

                            TextButton(
                                enabled = index >= 0 && index < lessons.lastIndex,
                                onClick = { if (index >= 0 && index < lessons.lastIndex) onNavigate(lessons[index + 1]) }
                            ) {
                                Text("التالي")
                                Icon(Icons.AutoMirrored.Filled.NavigateNext, contentDescription = null)
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

    if (tutor) {
        TutorDialog(
            lesson = lesson,
            positionMs = state.positionMs,
            profiles = profiles,
            onSeek = { session.seekAndPlay(it); tutor = false },
            onSettings = onSettings,
            onClose = { tutor = false }
        )
    }
}
