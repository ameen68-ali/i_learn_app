package com.ilearn.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ilearn.app.data.Lesson
import com.ilearn.app.tutor.LessonSourceStore
import com.ilearn.app.tutor.SubtitleLine
import com.ilearn.app.tutor.TranscriptParser
import com.ilearn.app.ui.screens.formatTimeMs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Interactive searchable transcript sheet synced with current playback position.
 * Works offline from lesson AR/EN files and cached YouTube transcripts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscriptSheet(
    lesson: Lesson,
    currentPositionMs: Long,
    onSeek: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var arLines by remember(lesson.id, lesson.subArPath) { mutableStateOf<List<SubtitleLine>>(emptyList()) }
    var enLines by remember(lesson.id, lesson.subEnPath) { mutableStateOf<List<SubtitleLine>>(emptyList()) }
    var selectedLang by remember(lesson.id) { mutableStateOf("ar") }
    var query by remember { mutableStateOf("") }
    var loaded by remember(lesson.id) { mutableStateOf(false) }

    LaunchedEffect(lesson.id, lesson.subArPath, lesson.subEnPath) {
        withContext(Dispatchers.IO) {
            val ar = lesson.subArPath?.let(::File)?.takeIf { it.isFile }?.let { f ->
                runCatching { TranscriptParser.parseLines(f.readText(), f.extension) }.getOrDefault(emptyList())
            }.orEmpty()
            val en = lesson.subEnPath?.let(::File)?.takeIf { it.isFile }?.let { f ->
                runCatching { TranscriptParser.parseLines(f.readText(), f.extension) }.getOrDefault(emptyList())
            }.orEmpty()
            val fallback = if (ar.isEmpty() && en.isEmpty()) {
                LessonSourceStore(context).exportSource(lesson)?.let { (ext, text) ->
                    runCatching {
                        TranscriptParser.parseLines(text, ext).ifEmpty {
                            TranscriptParser.parse(text, ext).mapNotNull { p ->
                                p.startMs?.let { s -> SubtitleLine(s, p.endMs ?: (s + 4000L), p.text) }
                            }
                        }
                    }.getOrDefault(emptyList())
                }.orEmpty()
            } else {
                emptyList()
            }

            if (ar.isEmpty() && en.isEmpty() && fallback.isEmpty() &&
                lesson.sourceType == com.ilearn.app.data.SourceType.YOUTUBE && !lesson.remoteId.isNullOrBlank()
            ) {
                val (rawAr, rawEn) = com.ilearn.app.youtube.YouTubeTranscriptClient().downloadPreferredTracksAsVtt(lesson.remoteId)
                val slicedAr = rawAr?.let { vtt ->
                    if (lesson.isChapterClip) {
                        com.ilearn.app.youtube.YouTubeChapterParser.sliceAndRebaseVtt(vtt, lesson.clipStartMs, lesson.clipEndMs)
                    } else vtt
                }
                val slicedEn = rawEn?.let { vtt ->
                    if (lesson.isChapterClip) {
                        com.ilearn.app.youtube.YouTubeChapterParser.sliceAndRebaseVtt(vtt, lesson.clipStartMs, lesson.clipEndMs)
                    } else vtt
                }
                arLines = slicedAr?.let { runCatching { TranscriptParser.parseLines(it, "vtt") }.getOrDefault(emptyList()) }.orEmpty()
                enLines = slicedEn?.let { runCatching { TranscriptParser.parseLines(it, "vtt") }.getOrDefault(emptyList()) }.orEmpty()
                val preferredRaw = slicedAr ?: slicedEn
                if (!preferredRaw.isNullOrBlank()) {
                    runCatching { LessonSourceStore(context).saveTranscript(lesson, preferredRaw, "vtt", "YouTube captions") }
                }
            } else {
                arLines = ar
                enLines = if (en.isNotEmpty()) en else fallback
            }
            selectedLang = if (arLines.isNotEmpty()) "ar" else "en"
            loaded = true
        }
    }

    val activeSource = remember(selectedLang, arLines, enLines) {
        when (selectedLang) {
            "ar" -> arLines.ifEmpty { enLines }
            "en" -> enLines.ifEmpty { arLines }
            else -> arLines.ifEmpty { enLines }
        }
    }
    val filtered = remember(activeSource, query) {
        val q = query.trim()
        if (q.isEmpty()) activeSource else activeSource.filter { it.text.contains(q, ignoreCase = true) }
    }
    val currentActiveIndex = remember(activeSource, currentPositionMs) {
        activeSource.indexOfLast { currentPositionMs in it.startMs..it.endMs }
    }
    val listState = rememberLazyListState()

    LaunchedEffect(loaded, selectedLang) {
        if (loaded && query.isBlank() && currentActiveIndex > 1) {
            runCatching { listState.scrollToItem((currentActiveIndex - 1).coerceAtLeast(0)) }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Article, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(
                        text = "النص التفاعلي للدرس",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (arLines.isNotEmpty() && enLines.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilterChip(
                            selected = selectedLang == "ar",
                            onClick = { selectedLang = "ar" },
                            label = { Text("عربي") }
                        )
                        FilterChip(
                            selected = selectedLang == "en",
                            onClick = { selectedLang = "en" },
                            label = { Text("EN") }
                        )
                    }
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(44.dp).semantics { contentDescription = "إغلاق" }
                ) {
                    Icon(Icons.Default.Close, contentDescription = null)
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("ابحث داخل هذا الدرس...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "مسح البحث")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )

            when {
                !loaded -> {
                    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                activeSource.isEmpty() -> {
                    ContextualEmptyState(
                        icon = Icons.Default.Article,
                        title = "لا يوجد نص موقّت لهذا الدرس",
                        description = "حمّل أو أرفق ترجمة للدرس من المعلّم الذكي أو أعد التحميل ليظهر النص التفاعلي هنا."
                    )
                }
                filtered.isEmpty() -> {
                    ContextualEmptyState(
                        icon = Icons.Default.Search,
                        title = "لا توجد عبارات مطابقة",
                        description = "لم يُعثر على \"$query\" داخل هذا الدرس.",
                        actionButtonText = "مسح البحث",
                        onActionClick = { query = "" }
                    )
                }
                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        itemsIndexed(filtered) { _, line ->
                            val isCurrent = currentPositionMs in line.startMs..line.endMs
                            Surface(
                                color = if (isCurrent) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceContainerLow,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        onSeek(line.startMs)
                                        onDismiss()
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.Top,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Surface(
                                        color = if (isCurrent) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.surfaceVariant,
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.PlayArrow,
                                                contentDescription = null,
                                                tint = if (isCurrent) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(12.dp)
                                            )
                                            Text(
                                                text = formatTimeMs(line.startMs),
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isCurrent) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                    Text(
                                        text = line.text,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                        lineHeight = 20.sp,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
