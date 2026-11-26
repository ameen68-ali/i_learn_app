package com.ilearn.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ilearn.app.BuildConfig
import com.ilearn.app.data.CourseRepository
import com.ilearn.app.youtube.PlaylistPreview
import com.ilearn.app.youtube.PlaylistUrlParser
import com.ilearn.app.youtube.YouTubeMetadataClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YouTubeImportScreen(
    repository: CourseRepository,
    initialPlaylist: String? = null,
    onBack: () -> Unit,
    onImported: (String) -> Unit
) {
    val builtInApiKey = BuildConfig.YOUTUBE_API_KEY.trim()
    val hasBuiltInApiKey = builtInApiKey.isNotEmpty()
    if (!hasBuiltInApiKey) {
        ProtectCredentialScreen()
    }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current.applicationContext
    var url by remember {
        mutableStateOf(
            when {
                initialPlaylist == null -> ""
                initialPlaylist.startsWith("video_") && initialPlaylist.length == 17 ->
                    "https://www.youtube.com/watch?v=${initialPlaylist.removePrefix("video_")}"
                else -> "https://www.youtube.com/playlist?list=$initialPlaylist"
            }
        )
    }
    var apiKey by remember { mutableStateOf(builtInApiKey) }
    var preview by remember { mutableStateOf<PlaylistPreview?>(null) }
    var splitIntoChapters by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var busy by remember { mutableStateOf(false) }
    var showUrlError by remember { mutableStateOf(false) }
    var hadUrlFocus by remember { mutableStateOf(false) }

    val parsedTarget = remember(url) { PlaylistUrlParser.parseTarget(url) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("إضافة كورس من YouTube", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            text = if (initialPlaylist != null) "تحديث بيانات الكورس" else "استيراد قائمة تشغيل أو فيديو مقسّم لفصول",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = { job?.cancel(); onBack() },
                        modifier = Modifier.size(48.dp).semantics { contentDescription = "رجوع" }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
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
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            ElevatedCard(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFFEF4444).copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.OndemandVideo,
                            contentDescription = null,
                            tint = Color(0xFFEF4444),
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "حوّل قوائم التشغيل أو الفيديوهات الطويلة إلى دروس",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "تُكتشف فصول الفيديو الطويل تلقائياً لتقسيمه إلى دروس مستقلة تعمل أونلاين أو تُقسم أوفلاين (360p) مع المعلّم الذكي.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 18.sp
                        )
                    }
                }
            }

            fun startPreview() {
                if (busy || apiKey.isBlank() || parsedTarget == null) return
                showUrlError = true
                job = scope.launch {
                    busy = true
                    errorMessage = null
                    statusMessage = "جاري جلب البيانات واكتشاف الدروس والفصول…"
                    preview = null
                    try {
                        val loadedPreview = YouTubeMetadataClient(context).preview(url, apiKey)
                        preview = loadedPreview
                        splitIntoChapters = loadedPreview.detectedChaptersCount >= 2
                        statusMessage = when {
                            loadedPreview.lessons.isEmpty() -> "لم يُعثر على دروس متاحة"
                            loadedPreview.detectedChaptersCount >= 2 ->
                                "تم اكتشاف ${loadedPreview.detectedChaptersCount} فصلاً داخل الفيديو — راجِعها قبل الإضافة"
                            else -> "راجِع الدروس قبل إضافتها للمكتبة"
                        }
                    } catch (e: CancellationException) {
                        statusMessage = "تم إلغاء المعاينة"
                        throw e
                    } catch (e: Exception) {
                        statusMessage = null
                        errorMessage = e.message ?: "تعذّر تحميل الرابط"
                    } finally {
                        busy = false
                    }
                }
            }

            val isUrlInvalid = url.isNotBlank() && parsedTarget == null && (showUrlError || url.length > 35)

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it.take(2000); preview = null; errorMessage = null; statusMessage = null },
                    label = { Text("رابط قائمة تشغيل أو فيديو • YouTube URL") },
                    placeholder = { Text("https://www.youtube.com/playlist?list=... أو watch?v=...") },
                    leadingIcon = { Icon(Icons.Default.Link, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    trailingIcon = {
                        if (url.isNotEmpty() && !busy) {
                            IconButton(onClick = { url = ""; showUrlError = false; preview = null; errorMessage = null; statusMessage = null }) {
                                Icon(Icons.Default.Close, contentDescription = "مسح الرابط")
                            }
                        }
                    },
                    supportingText = {
                        if (isUrlInvalid) {
                            Text("أدخل رابط YouTube صحيحاً بصيغة HTTPS (قائمة تشغيل أو فيديو)", color = MaterialTheme.colorScheme.error)
                        }
                    },
                    isError = isUrlInvalid,
                    singleLine = true,
                    enabled = !busy,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { focusState ->
                            if (focusState.isFocused) {
                                hadUrlFocus = true
                            } else if (hadUrlFocus && url.isNotBlank()) {
                                showUrlError = true
                            }
                        }
                )

                FilledIconButton(
                    enabled = !busy && apiKey.isNotBlank() && parsedTarget != null,
                    onClick = { startPreview() },
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .size(52.dp)
                        .semantics { contentDescription = "معاينة الكورس" }
                ) {
                    Icon(Icons.AutoMirrored.Filled.PlaylistPlay, contentDescription = null)
                }
            }

            if (!hasBuiltInApiKey) {
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it.take(4096); preview = null; errorMessage = null },
                    label = { Text("YouTube Data API key") },
                    enabled = !busy,
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "مفتاح جلب بيانات قوائم YouTube منفصل عن مفتاح المعلّم الذكي، ويُستخدم في هذه الشاشة فقط.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (busy) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = statusMessage ?: "جاري التحميل…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        IconButton(
                            onClick = { job?.cancel() },
                            modifier = Modifier
                                .size(44.dp)
                                .semantics { contentDescription = "إلغاء المعاينة" }
                        ) {
                            Icon(Icons.Default.Close, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            } else if (!statusMessage.isNullOrBlank()) {
                Text(
                    text = statusMessage!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            errorMessage?.let { err ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                        Text(
                            text = err,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        if (parsedTarget != null && apiKey.isNotBlank()) {
                            IconButton(
                                onClick = { startPreview() },
                                modifier = Modifier.size(44.dp).semantics { contentDescription = "إعادة المحاولة" }
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                            }
                        }
                        IconButton(
                            onClick = { errorMessage = null },
                            modifier = Modifier.size(44.dp).semantics { contentDescription = "إخفاء الخطأ" }
                        ) {
                            Icon(Icons.Default.Close, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                }
            }

            preview?.let { result ->
                val shownLessons = remember(result, splitIntoChapters) {
                    if (result.detectedChaptersCount >= 2 && !splitIntoChapters && result.unsplitFallbackLesson != null) {
                        listOf(result.unsplitFallbackLesson)
                    } else {
                        result.lessons
                    }
                }
                ElevatedCard(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = result.course.title,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            FilledIconButton(
                                enabled = !busy && shownLessons.isNotEmpty(),
                                onClick = {
                                    job = scope.launch {
                                        busy = true
                                        errorMessage = null
                                        try {
                                            val existing = repository.courses.value.firstOrNull { it.id == result.course.id }
                                            repository.dbHelper.insertCourseWithLessons(
                                                result.course.copy(dateAdded = existing?.dateAdded ?: result.course.dateAdded),
                                                shownLessons
                                            )
                                            repository.refreshData()
                                            onImported(result.course.id)
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            errorMessage = "تعذّر حفظ الكورس: ${e.message}"
                                        } finally {
                                            busy = false
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .size(48.dp)
                                    .semantics { contentDescription = "إضافة أو تحديث الكورس" }
                            ) {
                                Icon(Icons.Default.Check, contentDescription = null)
                            }
                        }

                        if (result.detectedChaptersCount >= 2 && result.unsplitFallbackLesson != null) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "تقسيم الفيديو حسب الفصول",
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = if (splitIntoChapters) {
                                                "سيُضاف كـ ${result.detectedChaptersCount} درساً مستقلاً"
                                            } else {
                                                "سيُضاف كدرس واحد كامل"
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Switch(
                                        checked = splitIntoChapters,
                                        onCheckedChange = { splitIntoChapters = it }
                                    )
                                }
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val availableCount = shownLessons.count { it.isAvailable }
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = "${shownLessons.size} درس",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = "$availableCount متاح للتشغيل",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }

                        HorizontalDivider()

                        if (shownLessons.isEmpty()) {
                            Text(
                                text = "لم يُعثر على دروس متاحة داخل هذا الرابط.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            shownLessons.take(12).forEach { lesson ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = String.format("%02d", lesson.orderIndex),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = lesson.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                if (lesson.durationMs > 0) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (lesson.isChapterClip) {
                                            "${formatTimeMs(lesson.clipStartMs)} • ${formatTimeMs(lesson.durationMs)}"
                                        } else {
                                            formatTimeMs(lesson.durationMs)
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        if (shownLessons.size > 12) {
                            Text(
                                text = "… و ${shownLessons.size - 12} درساً إضافياً",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        }
                    }
                }
            }
        }
    }
}
