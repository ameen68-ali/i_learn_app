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
    var url by remember { mutableStateOf(initialPlaylist?.let { "https://www.youtube.com/playlist?list=$it" } ?: "") }
    var apiKey by remember { mutableStateOf(builtInApiKey) }
    var preview by remember { mutableStateOf<PlaylistPreview?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var busy by remember { mutableStateOf(false) }

    val parsedPlaylistId = remember(url) { PlaylistUrlParser.parse(url) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("إضافة كورس من YouTube", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            text = if (initialPlaylist != null) "تحديث قائمة التشغيل" else "استيراد قائمة تشغيل تعليمية",
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
                            text = "حوّل أي قائمة تشغيل يوتيوب إلى كورس منظم",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "تُحفظ دروسك وتقدّم المشاهدة والملاحظات محلياً على جهازك، مع تشغيل الفيديو عبر الإنترنت والمعلّم الذكي.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 18.sp
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it.take(2000); preview = null; errorMessage = null; statusMessage = null },
                    label = { Text("رابط قائمة التشغيل • YouTube playlist") },
                    placeholder = { Text("https://www.youtube.com/playlist?list=...") },
                    leadingIcon = { Icon(Icons.Default.Link, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    trailingIcon = {
                        if (url.isNotEmpty() && !busy) {
                            IconButton(onClick = { url = ""; preview = null; errorMessage = null; statusMessage = null }) {
                                Icon(Icons.Default.Close, contentDescription = "مسح الرابط")
                            }
                        }
                    },
                    supportingText = {
                        if (url.isNotBlank() && parsedPlaylistId == null) {
                            Text("أدخل رابط قائمة تشغيل YouTube صحيح بصيغة HTTPS يحتوي على list=", color = MaterialTheme.colorScheme.error)
                        }
                    },
                    isError = url.isNotBlank() && parsedPlaylistId == null,
                    singleLine = true,
                    enabled = !busy,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f)
                )

                FilledIconButton(
                    enabled = !busy && apiKey.isNotBlank() && parsedPlaylistId != null,
                    onClick = {
                        job = scope.launch {
                            busy = true
                            errorMessage = null
                            statusMessage = "جاري جلب بيانات قائمة التشغيل…"
                            preview = null
                            try {
                                preview = YouTubeMetadataClient(context).preview(url, apiKey)
                                statusMessage = "راجِع الدروس قبل إضافتها للمكتبة"
                            } catch (e: CancellationException) {
                                statusMessage = "تم إلغاء المعاينة"
                                throw e
                            } catch (e: Exception) {
                                statusMessage = null
                                errorMessage = e.message ?: "تعذّر تحميل قائمة التشغيل"
                            } finally {
                                busy = false
                            }
                        }
                    },
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
                                .size(40.dp)
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
                    Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(err, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            preview?.let { result ->
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
                                enabled = !busy,
                                onClick = {
                                    job = scope.launch {
                                        busy = true
                                        errorMessage = null
                                        try {
                                            val existing = repository.courses.value.firstOrNull { it.id == result.course.id }
                                            repository.dbHelper.insertCourseWithLessons(
                                                result.course.copy(dateAdded = existing?.dateAdded ?: result.course.dateAdded),
                                                result.lessons
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

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val availableCount = result.lessons.count { it.isAvailable }
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = "${result.lessons.size} درس",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                            Surface(
                                color = Color(0xFF10B981).copy(alpha = 0.16f),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = "$availableCount متاح للتشغيل",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color(0xFF10B981),
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }

                        HorizontalDivider()

                        result.lessons.take(12).forEach { lesson ->
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
                                        text = formatTimeMs(lesson.durationMs),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        if (result.lessons.size > 12) {
                            Text(
                                text = "… و ${result.lessons.size - 12} درساً إضافياً",
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
