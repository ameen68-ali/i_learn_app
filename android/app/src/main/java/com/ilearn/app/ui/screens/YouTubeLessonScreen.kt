package com.ilearn.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.ilearn.app.data.*
import com.ilearn.app.youtube.YouTubePlaybackSession
import com.ilearn.app.tutor.ProviderProfileStore
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YouTubeLessonScreen(lesson: Lesson, lessons: List<Lesson>, notes: List<LessonNote>, profiles: ProviderProfileStore,
    onBack: () -> Unit, onNavigate: (Lesson) -> Unit, onProgress: (Long, Long) -> Unit,
    onBookmark: (Boolean) -> Unit, onAddNote: (Long, String) -> Unit, onDeleteNote: (String) -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val session = remember(lesson.id) { YouTubePlaybackSession(context, lesson) }
    val state by session.state.collectAsState()
    val fullscreen by session.fullscreen.collectAsState()
    var tutor by remember(lesson.id) { mutableStateOf(false) }
    val saveProgress by rememberUpdatedState(onProgress)
    LaunchedEffect(session) {
        while (true) {
            session.sample(); val s = session.state.value
            if (s.ready && s.durationMs > 0 && s.positionMs in 1..s.durationMs) saveProgress(s.positionMs, s.durationMs)
            delay(1000)
        }
    }
    DisposableEffect(session, lifecycle) {
        fun save() { val s = session.state.value; if (s.ready && s.durationMs > 0 && s.positionMs in 1..s.durationMs) saveProgress(s.positionMs, s.durationMs) }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) { save(); session.pause() } }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { save(); lifecycle.lifecycle.removeObserver(observer); session.release() }
    }
    Scaffold(topBar = { TopAppBar(title = { Text(lesson.title, maxLines = 1) }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (lesson.isAvailable && lesson.remoteId != null) {
                key(lesson.id) { AndroidView(factory = { session.createView() }, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).heightIn(min = 200.dp)
                    .testTag("youtube-player").semantics { stateDescription = when { state.error != null -> "Error"; state.playing -> "Playing"; state.ready -> "Ready"; else -> "Loading" } }) }
            } else Text("This playlist entry is unavailable. Your saved notes and progress are retained.", Modifier.padding(16.dp))
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { session.pause(); tutor = true }) { Text("AI tutor • المعلّم") }
                TextButton(onClick = { onBookmark(!lesson.isBookmarked) }) { Text(if (lesson.isBookmarked) "★ Saved" else "☆ Bookmark") }
                TextButton(enabled = lesson.remoteId != null, onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=${lesson.remoteId}"))) } }) { Text("YouTube ↗") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val index = lessons.indexOfFirst { it.id == lesson.id }
                TextButton(enabled = index > 0, onClick = { if (index > 0) onNavigate(lessons[index - 1]) }) { Text("Previous") }
                Text("${formatTimeMs(state.positionMs)} / ${formatTimeMs(state.durationMs)}")
                TextButton(enabled = index >= 0 && index < lessons.lastIndex, onClick = { if (index >= 0 && index < lessons.lastIndex) onNavigate(lessons[index + 1]) }) { Text("Next") }
            }
            Box(Modifier.padding(12.dp)) { StudyNotesSection(state.positionMs, notes, { session.seek(it) }, { onAddNote(state.positionMs, it) }, onDeleteNote) }
        }
    }
    if (tutor) TutorDialog(lesson, state.positionMs, profiles, { session.seek(it); tutor = false }, onSettings, { tutor = false })
    fullscreen?.let { customView ->
        androidx.compose.ui.window.Dialog(onDismissRequest = { session.exitFullscreen() }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            AndroidView(factory = {
                (customView.parent as? android.view.ViewGroup)?.removeView(customView)
                android.widget.FrameLayout(it).apply { addView(customView, android.widget.FrameLayout.LayoutParams(-1, -1)) }
            }, modifier = Modifier.fillMaxSize())
        }
    }
}
