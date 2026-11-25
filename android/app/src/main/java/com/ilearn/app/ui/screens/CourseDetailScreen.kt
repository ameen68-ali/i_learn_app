package com.ilearn.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ilearn.app.data.Course
import com.ilearn.app.data.Lesson
import com.ilearn.app.data.SourceType
import com.ilearn.app.ui.components.CompletionCelebrationDialog
import com.ilearn.app.ui.components.ContextualEmptyState
import com.ilearn.app.youtube.LessonDownloadState

enum class LessonFilter(val label: String) {
    ALL("الكل"),
    UNWATCHED("غير المشاهدة"),
    COMPLETED("المكتملة"),
    BOOKMARKED("المفضلة ⭐"),
    DUBBED("المدبلجة 🎙️")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseDetailScreen(
    course: Course,
    lessons: List<Lesson>,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSelectLesson: (Lesson) -> Unit,
    onToggleLessonCompleted: (Lesson, Boolean) -> Unit,
    onToggleLessonBookmarked: (Lesson, Boolean) -> Unit,
    lessonDownloads: Map<String, LessonDownloadState> = emptyMap(),
    lessonDownloadErrors: Map<String, String> = emptyMap(),
    onDownloadLesson: (Lesson) -> Unit = {},
    onCancelDownload: (String) -> Unit = {},
    onRemoveOfflineLesson: (Lesson) -> Unit = {},
    onDismissDownloadError: (String) -> Unit = {}
) {
    var selectedFilter by remember { mutableStateOf(LessonFilter.ALL) }
    var searchQuery by remember { mutableStateOf("") }
    var showCelebrationDialog by remember { mutableStateOf(false) }
    var hasAutoShownCelebration by remember { mutableStateOf(false) }
    var lessonToDeleteOffline by remember { mutableStateOf<Lesson?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(lessonDownloadErrors) {
        val firstEntry = lessonDownloadErrors.entries.firstOrNull()
        if (firstEntry != null) {
            snackbarHostState.showSnackbar(firstEntry.value, duration = SnackbarDuration.Long)
            onDismissDownloadError(firstEntry.key)
        }
    }

    val completedCount = lessons.count { it.isCompleted }
    val bookmarkedCount = lessons.count { it.isBookmarked }
    val dubbedCount = lessons.count { it.hasArabicAudio }
    val progressPct = if (lessons.isNotEmpty()) (completedCount * 100) / lessons.size else 0

    // Auto-trigger celebration modal once when reaching 100%
    LaunchedEffect(progressPct) {
        if (progressPct == 100 && lessons.isNotEmpty() && !hasAutoShownCelebration) {
            hasAutoShownCelebration = true
            showCelebrationDialog = true
        }
    }

    val filteredLessons = remember(lessons, selectedFilter, searchQuery) {
        val byFilter = when (selectedFilter) {
            LessonFilter.ALL -> lessons
            LessonFilter.UNWATCHED -> lessons.filter { !it.isCompleted }
            LessonFilter.COMPLETED -> lessons.filter { it.isCompleted }
            LessonFilter.BOOKMARKED -> lessons.filter { it.isBookmarked }
            LessonFilter.DUBBED -> lessons.filter { it.hasArabicAudio }
        }
        if (searchQuery.isBlank()) {
            byFilter
        } else {
            val q = searchQuery.trim()
            byFilter.filter {
                it.title.contains(q, ignoreCase = true) ||
                it.orderIndex.toString() == q
            }
        }
    }

    val nextLesson = lessons.firstOrNull { !it.isCompleted } ?: lessons.firstOrNull()

    // 100% Course Completion Celebration Dialog
    if (showCelebrationDialog) {
        CompletionCelebrationDialog(
            course = course,
            onDismiss = { showCelebrationDialog = false }
        )
    }

    lessonToDeleteOffline?.let { target ->
        AlertDialog(
            onDismissRequest = { lessonToDeleteOffline = null },
            icon = { Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("حذف النسخة المحملة؟", fontWeight = FontWeight.Bold) },
            text = { Text("سيتم حذف ملف الفيديو المحمل (360p) للدرس \"${target.title}\" لتوفير المساحة. يمكنك مشاهدته عبر الإنترنت أو إعادة تحميله لاحقاً.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRemoveOfflineLesson(target)
                        lessonToDeleteOffline = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("حذف الملف") }
            },
            dismissButton = {
                TextButton(onClick = { lessonToDeleteOffline = null }) { Text("إلغاء") }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = course.title,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .size(48.dp)
                            .semantics { contentDescription = "الرجوع لقائمة الكورسات" }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    if (course.sourceType == com.ilearn.app.data.SourceType.YOUTUBE) {
                        IconButton(
                            onClick = onRefresh,
                            modifier = Modifier.size(48.dp).semantics { contentDescription = "تحديث دروس يوتيوب" }
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Course Summary & Progress Card
            item {
                ElevatedCard(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                    )
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "مستوى التقدّم في الكورس",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "$progressPct%",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (progressPct == 100) Color(0xFF10B981) else MaterialTheme.colorScheme.primary
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        LinearProgressIndicator(
                            progress = { (progressPct / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(10.dp)
                                .clip(RoundedCornerShape(5.dp)),
                            color = if (progressPct == 100) Color(0xFF10B981) else MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceDim
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "$completedCount من إجمالي ${lessons.size} درساً مكتمل",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (progressPct == 100) {
                            Spacer(modifier = Modifier.height(10.dp))
                            OutlinedButton(
                                onClick = { showCelebrationDialog = true },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = Color(0xFF10B981)
                                )
                            ) {
                                Icon(Icons.Default.EmojiEvents, contentDescription = null, tint = Color(0xFFF59E0B))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("عرض وسام إتمام الكورس 🏆", fontWeight = FontWeight.Bold)
                            }
                        }

                        nextLesson?.let { next ->
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = { onSelectLesson(next) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (completedCount == 0) "بدء دراسة الكورس" else "متابعة: ${next.title}",
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            // In-Course Search Field
            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("ابحث عن درس بالاسم أو الرقم...") },
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = "بحث", tint = MaterialTheme.colorScheme.primary)
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(
                                onClick = { searchQuery = "" },
                                modifier = Modifier
                                    .size(48.dp)
                                    .semantics { contentDescription = "مسح نص البحث" }
                            ) {
                                Icon(Icons.Default.Close, contentDescription = null)
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                    )
                )
            }

            // Filter Chips (Horizontally scrollable to fit all chips and eliminate squishing/empty vertical space)
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val visibleFilters = remember(course.sourceType, dubbedCount) {
                        if (course.sourceType == com.ilearn.app.data.SourceType.YOUTUBE && dubbedCount == 0) {
                            LessonFilter.values().filter { it != LessonFilter.DUBBED }
                        } else {
                            LessonFilter.values().toList()
                        }
                    }
                    visibleFilters.forEach { filter ->
                        val count = when (filter) {
                            LessonFilter.ALL -> lessons.size
                            LessonFilter.UNWATCHED -> lessons.size - completedCount
                            LessonFilter.COMPLETED -> completedCount
                            LessonFilter.BOOKMARKED -> bookmarkedCount
                            LessonFilter.DUBBED -> dubbedCount
                        }
                        FilterChip(
                            selected = selectedFilter == filter,
                            onClick = { selectedFilter = filter },
                            modifier = Modifier.heightIn(min = 44.dp),
                            label = { Text("${filter.label} ($count)", maxLines = 1) },
                            shape = RoundedCornerShape(10.dp)
                        )
                    }
                }
            }

            // Lessons List
            items(filteredLessons, key = { it.id }) { lesson ->
                LessonItemCard(
                    lesson = lesson,
                    downloadState = lessonDownloads[lesson.id],
                    onClick = { onSelectLesson(lesson) },
                    onToggleComplete = { completed -> onToggleLessonCompleted(lesson, completed) },
                    onToggleBookmark = { bookmarked -> onToggleLessonBookmarked(lesson, bookmarked) },
                    onDownloadClick = { onDownloadLesson(lesson) },
                    onCancelDownload = { onCancelDownload(lesson.id) },
                    onRequestRemoveOffline = { lessonToDeleteOffline = lesson }
                )
            }

            // Contextual Empty States (UXKIN 11 UI-States)
            if (filteredLessons.isEmpty()) {
                item {
                    if (searchQuery.isNotBlank()) {
                        ContextualEmptyState(
                            icon = Icons.Default.SearchOff,
                            iconTint = MaterialTheme.colorScheme.outline,
                            title = "لا توجد نتائج مطابقة للبحث",
                            description = "لم نجد أي درس يطابق \"$searchQuery\". يمكنك تجربة كلمة أخرى أو مسح حقل البحث.",
                            actionButtonText = "مسح البحث",
                            onActionClick = { searchQuery = "" }
                        )
                    } else {
                        when (selectedFilter) {
                            LessonFilter.BOOKMARKED -> {
                                ContextualEmptyState(
                                    icon = Icons.Default.StarOutline,
                                    iconTint = Color(0xFFF59E0B),
                                    title = "قائمة المفضلة فارغة حالياً",
                                    description = "يمكنك تمييز أي درس مهم بالنقر على أيقونة النجمة ⭐ لسهولة العودة إليه ومراجعته في أي وقت.",
                                    actionButtonText = "عرض جميع الدروس",
                                    onActionClick = { selectedFilter = LessonFilter.ALL }
                                )
                            }
                            LessonFilter.DUBBED -> {
                                ContextualEmptyState(
                                    icon = Icons.Default.RecordVoiceOver,
                                    iconTint = Color(0xFF10B981),
                                    title = "لا توجد دروس مدبلجة للعربية بعد",
                                    description = "يمكنك استخراج الصوت الإنجليزي من أي درس وإرساله إلى Gemini لدبلجته، ثم ربط الملف العربي ليعمل متزامناً بصوت عربي نقي!",
                                    actionButtonText = "عرض جميع الدروس",
                                    onActionClick = { selectedFilter = LessonFilter.ALL }
                                )
                            }
                            LessonFilter.COMPLETED -> {
                                ContextualEmptyState(
                                    icon = Icons.Default.School,
                                    iconTint = Color(0xFF10B981),
                                    title = "لم تكمل أي درس حتى الآن",
                                    description = "ابدأ دراسة أول درس الآن واضغط على علامة الإكمال عند الانتهاء لتتبع تقدمك نحو الاحتراف.",
                                    actionButtonText = "الانتقال لأول درس",
                                    onActionClick = {
                                        nextLesson?.let { onSelectLesson(it) }
                                    }
                                )
                            }
                            LessonFilter.UNWATCHED -> {
                                ContextualEmptyState(
                                    icon = Icons.Default.CheckCircle,
                                    iconTint = Color(0xFF10B981),
                                    title = "رائع! أتممت جميع الدروس بنجاح!",
                                    description = "لقد أكملت جميع دروس هذا الكورس. يمكنك مراجعة أي درس أو إعادة اختباره في أي وقت.",
                                    actionButtonText = "عرض الدروس المكتملة",
                                    onActionClick = { selectedFilter = LessonFilter.COMPLETED }
                                )
                            }
                            LessonFilter.ALL -> {
                                ContextualEmptyState(
                                    icon = Icons.AutoMirrored.Filled.MenuBook,
                                    iconTint = MaterialTheme.colorScheme.primary,
                                    title = "لا توجد دروس متوفرة",
                                    description = "لا توجد أي دروس مضافة في هذا الكورس حالياً."
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LessonItemCard(
    lesson: Lesson,
    onClick: () -> Unit,
    onToggleComplete: (Boolean) -> Unit,
    onToggleBookmark: (Boolean) -> Unit,
    downloadState: LessonDownloadState? = null,
    onDownloadClick: () -> Unit = {},
    onCancelDownload: () -> Unit = {},
    onRequestRemoveOffline: () -> Unit = {}
) {
    val haptic = LocalHapticFeedback.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = "تشغيل الدرس: ${lesson.title}") { onClick() }
            .semantics(mergeDescendants = false) {
                contentDescription = "درس ${lesson.orderIndex}: ${lesson.title}, ${if (lesson.isCompleted) "مكتمل" else "غير مكتمل"}"
            },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (lesson.isCompleted) {
                MaterialTheme.colorScheme.surfaceContainerLow
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Lesson Index Badge
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (lesson.isCompleted) Color(0xFF10B981).copy(alpha = 0.15f)
                            else MaterialTheme.colorScheme.primaryContainer
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = String.format("%02d", lesson.orderIndex),
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = if (lesson.isCompleted) Color(0xFF047857) else MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                // Lesson Details
                Column(modifier = Modifier.weight(1f)) {
                    val displayTitle = remember(lesson.title) {
                        lesson.title.replace(Regex("^\\d+\\s*[-–.]\\s*"), "").trim().ifBlank { lesson.title }
                    }
                    Text(
                        text = displayTitle,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (lesson.isCompleted) FontWeight.Normal else FontWeight.SemiBold,
                        color = if (lesson.isCompleted) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (lesson.durationMs > 0) {
                            val totalSecs = lesson.durationMs / 1000
                            val m = totalSecs / 60
                            val s = totalSecs % 60
                            Text(
                                text = String.format("%02d:%02d", m, s),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }

                        if (lesson.sourceType == SourceType.YOUTUBE && lesson.hasOfflineVideo) {
                            Surface(
                                color = Color(0xFF10B981).copy(alpha = 0.18f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = "📥 أوفلاين 360p",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF047857),
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                )
                            }
                        }

                        if (lesson.hasArabicAudio) {
                            Surface(
                                color = Color(0xFF10B981).copy(alpha = 0.2f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = "🎙️ مدبلج للعربية",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF047857),
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                        }

                        if (lesson.hasArabicSubtitles) {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = "🇸🇦 ترجمة",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                        }

                        if (lesson.hasEnglishSubtitles && lesson.sourceType == SourceType.YOUTUBE) {
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = "🇬🇧 EN",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                        }

                        if (lesson.lastPositionMs > 1000L && !lesson.isCompleted) {
                            val posM = (lesson.lastPositionMs / 1000) / 60
                            val posS = (lesson.lastPositionMs / 1000) % 60
                            Text(
                                text = String.format("توقفت عند %02d:%02d", posM, posS),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                // Bookmark Star Button (>=48dp touch target, TalkBack semantics, Haptic)
                IconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onToggleBookmark(!lesson.isBookmarked)
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .semantics {
                            contentDescription = if (lesson.isBookmarked) "إزالة من المفضلة" else "إضافة إلى المفضلة"
                        }
                ) {
                    Icon(
                        imageVector = if (lesson.isBookmarked) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = null,
                        tint = if (lesson.isBookmarked) Color(0xFFF59E0B) else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(24.dp)
                    )
                }

                // Completion Checkbox (>=48dp touch target, TalkBack semantics, Haptic)
                Box(
                    modifier = Modifier.size(48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Checkbox(
                        checked = lesson.isCompleted,
                        onCheckedChange = { checked ->
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onToggleComplete(checked)
                        },
                        colors = CheckboxDefaults.colors(
                            checkedColor = Color(0xFF10B981)
                        ),
                        modifier = Modifier.semantics {
                            contentDescription = if (lesson.isCompleted) "تم إكمال الدرس" else "تعليم الدرس كمكتمل"
                        }
                    )
                }

                // Play Button (>=48dp touch target, TalkBack semantics)
                IconButton(
                    onClick = onClick,
                    modifier = Modifier
                        .size(48.dp)
                        .semantics {
                            contentDescription = "تشغيل الدرس"
                        }
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(34.dp)
                    )
                }
            }

            if (lesson.sourceType == SourceType.YOUTUBE && lesson.isAvailable && lesson.remoteId != null) {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(6.dp))
                when {
                    downloadState != null -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = downloadState.stage,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${(downloadState.progress * 100).toInt()}%",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                LinearProgressIndicator(
                                    progress = { downloadState.progress.coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                                )
                            }
                            TextButton(
                                onClick = onCancelDownload,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("إلغاء", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }

                    lesson.hasOfflineVideo -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Default.OfflinePin, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(18.dp))
                                Text(
                                    text = "متاح بدون إنترنت (360p) • بالمشغل المحلي",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF047857),
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            TextButton(
                                onClick = onRequestRemoveOffline,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("حذف التحميل", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }

                    else -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            FilledTonalButton(
                                onClick = onDownloadClick,
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier.heightIn(min = 38.dp)
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("تحميل أوفلاين (360p) + الترجمة", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }
    }
}
