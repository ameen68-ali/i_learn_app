package com.ilearn.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ilearn.app.data.Course
import com.ilearn.app.data.ResumeLessonInfo
import com.ilearn.app.data.StudyStats
import com.ilearn.app.importer.ImportState
import com.ilearn.app.ui.components.ContextualEmptyState
import com.ilearn.app.ui.components.FileUploadBottomSheet
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseCatalogScreen(
    courses: List<Course>,
    resumeLesson: ResumeLessonInfo?,
    stats: StudyStats,
    importState: ImportState,
    onSelectCourse: (Course) -> Unit,
    onResumeLesson: (ResumeLessonInfo) -> Unit,
    onImportArchive: (Uri) -> Unit,
    onDeleteCourse: (Course) -> Unit,
    onDismissImportState: () -> Unit,
    onExportBackupToUri: suspend (Uri) -> Boolean,
    onImportBackupFromUri: suspend (Uri) -> Boolean,
    onGetStorageInfo: suspend () -> Pair<Long, Long>,
    onClearCache: suspend () -> Unit,
    onAddYouTube: () -> Unit,
    onApiSettings: () -> Unit,
    dueFlashcardsCount: Int = 0,
    onOpenDueFlashcards: () -> Unit = {}
) {
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // Search query
    var searchQuery by remember { mutableStateOf("") }

    // Filter courses based on search query
    val filteredCourses = remember(courses, searchQuery) {
        if (searchQuery.isBlank()) courses
        else courses.filter { it.title.contains(searchQuery.trim(), ignoreCase = true) }
    }

    // Overflow menu & dialog states
    var showMenu by remember { mutableStateOf(false) }
    var showStorageDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showCourseImportSheet by remember { mutableStateOf(false) }
    var showBackupRestoreSheet by remember { mutableStateOf(false) }

    var storageCoursesSize by remember { mutableLongStateOf(0L) }
    var storageCacheSize by remember { mutableLongStateOf(0L) }
    var courseToDelete by remember { mutableStateOf<Course?>(null) }
    var persistentImportError by remember { mutableStateOf<String?>(null) }

    // File Pickers with Graceful Cancellation
    val archivePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            onImportArchive(uri)
        } else {
            coroutineScope.launch {
                snackbarHostState.showSnackbar("تم إلغاء اختيار ملف الكورس")
            }
        }
    }

    val exportBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                val success = onExportBackupToUri(uri)
                if (success) {
                    snackbarHostState.showSnackbar("تم تصدير النسخة الاحتياطية بنجاح!")
                } else {
                    snackbarHostState.showSnackbar("فشل تصدير النسخة الاحتياطية")
                }
            }
        } else {
            coroutineScope.launch {
                snackbarHostState.showSnackbar("تم إلغاء تصدير النسخة الاحتياطية")
            }
        }
    }

    val importBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                val success = onImportBackupFromUri(uri)
                if (success) {
                    snackbarHostState.showSnackbar("تمت استعادة تقدّم الدراسة والملاحظات بنجاح!")
                } else {
                    snackbarHostState.showSnackbar("الملف المحدد غير صالح أو تالف")
                }
            }
        } else {
            coroutineScope.launch {
                snackbarHostState.showSnackbar("تم إلغاء استعادة النسخة الاحتياطية")
            }
        }
    }

    // React to importer status
    LaunchedEffect(importState) {
        when (importState) {
            is ImportState.Success -> {
                persistentImportError = null
                snackbarHostState.showSnackbar(
                    message = "تم استيراد كورس \"${importState.courseTitle}\" بنجاح (${importState.lessonCount} درساً)!",
                    duration = SnackbarDuration.Short
                )
                onDismissImportState()
            }
            is ImportState.Error -> {
                persistentImportError = importState.message
                onDismissImportState()
            }
            else -> {}
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    Brush.linearGradient(
                                        colors = listOf(Color(0xFF06B6D4), Color(0xFF1E3A8A))
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "iL",
                                color = Color.White,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 18.sp,
                                letterSpacing = (-0.5).sp
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "iLearn",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleLarge
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Surface(
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        text = "Offline + YT",
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Text(
                                text = "منصة التعلّم الذاتي",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    if (dueFlashcardsCount > 0) {
                        IconButton(
                            onClick = onOpenDueFlashcards,
                            modifier = Modifier
                                .size(44.dp)
                                .semantics { contentDescription = "مراجعة البطاقات المستحقة ($dueFlashcardsCount)" }
                        ) {
                            BadgedBox(
                                badge = {
                                    Badge {
                                        Text(dueFlashcardsCount.coerceAtMost(99).toString())
                                    }
                                }
                            ) {
                                Icon(Icons.Default.Quiz, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    FilledTonalIconButton(
                        onClick = { showCourseImportSheet = true },
                        modifier = Modifier
                            .size(44.dp)
                            .semantics { contentDescription = "استيراد كورس" }
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                    }
                    IconButton(
                        onClick = onAddYouTube,
                        modifier = Modifier
                            .size(44.dp)
                            .semantics { contentDescription = "إضافة كورس من YouTube" }
                    ) {
                        Icon(Icons.Default.OndemandVideo, contentDescription = null, tint = Color(0xFFEF4444))
                    }

                    // 3-dots overflow menu
                    Box {
                        IconButton(
                            onClick = { showMenu = !showMenu },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.Default.MoreVert, contentDescription = "المزيد من الخيارات والأدوات")
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("حول التطبيق والمطوّر (About)") },
                                leadingIcon = { Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                                onClick = {
                                    showMenu = false
                                    showAboutDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("إضافة كورس من YouTube") },
                                leadingIcon = { Icon(Icons.Default.OndemandVideo, contentDescription = null, tint = Color(0xFFEF4444)) },
                                onClick = { showMenu = false; onAddYouTube() }
                            )
                            DropdownMenuItem(
                                text = { Text("المعلّم الذكي • إعدادات AI") },
                                leadingIcon = { Icon(Icons.Default.SmartToy, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                                onClick = { showMenu = false; onApiSettings() }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("تصدير نسخة احتياطية (JSON)") },
                                leadingIcon = { Icon(Icons.Default.Upload, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    val timeStamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
                                    exportBackupLauncher.launch("ilearn_backup_$timeStamp.json")
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("استعادة نسخة احتياطية (JSON)") },
                                leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    showBackupRestoreSheet = true
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("بيانات التخزين والمساحة") },
                                leadingIcon = { Icon(Icons.Default.Storage, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    coroutineScope.launch {
                                        val info = onGetStorageInfo()
                                        storageCoursesSize = info.first
                                        storageCacheSize = info.second
                                        showStorageDialog = true
                                    }
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            if (courses.isNotEmpty()) {
                FloatingActionButton(
                    onClick = { showCourseImportSheet = true },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.semantics { contentDescription = "زر إضافة واستيراد كورس جديد" }
                ) {
                    Icon(Icons.Default.UploadFile, contentDescription = null)
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                persistentImportError?.let { errMsg ->
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                            Text(
                                text = errMsg,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = {
                                    persistentImportError = null
                                    showCourseImportSheet = true
                                },
                                modifier = Modifier.size(44.dp).semantics { contentDescription = "إعادة محاولة الاستيراد" }
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                            }
                            IconButton(
                                onClick = { persistentImportError = null },
                                modifier = Modifier.size(44.dp).semantics { contentDescription = "إخفاء الخطأ" }
                            ) {
                                Icon(Icons.Default.Close, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                            }
                        }
                    }
                }

                if (courses.isEmpty()) {
                    EmptyCoursesView(
                        onImportClick = { showCourseImportSheet = true },
                        onAddYouTube = onAddYouTube
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                    // 1. Search Bar
                    item {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { contentDescription = "شريط البحث في الكورسات المتاحة" },
                            placeholder = { Text("ابحث في الكورسات المتاحة...") },
                            leadingIcon = {
                                Icon(Icons.Default.Search, contentDescription = "Search", tint = MaterialTheme.colorScheme.primary)
                            },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(
                                        onClick = { searchQuery = "" },
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = "مسح نص البحث")
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

                    // 2. Study Analytics Dashboard Card
                    item {
                        StudyStatsCard(stats = stats)
                    }

                    // 3. "Continue Learning" Hero Card
                    resumeLesson?.let { resume ->
                        item {
                            ContinueLearningHeroCard(
                                resumeInfo = resume,
                                onClick = { onResumeLesson(resume) }
                            )
                        }
                    }

                    // 4. Section Header
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (searchQuery.isBlank()) "الكورسات المحملة (${courses.size})" else "نتائج البحث (${filteredCourses.size})",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            val totalCompleted = courses.sumOf { it.completedLessons }
                            val totalLessons = courses.sumOf { it.totalLessons }
                            if (totalLessons > 0) {
                                Text(
                                    text = "$totalCompleted / $totalLessons درساً مُكتمل",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    // 5. Courses List / Contextual Empty State
                    if (filteredCourses.isEmpty() && searchQuery.isNotBlank()) {
                        item {
                            ContextualEmptyState(
                                icon = Icons.Default.SearchOff,
                                title = "لا توجد كورسات مطابقة للبحث",
                                description = "لم نتمكن من العثور على أي كورس يحتوي على \"$searchQuery\". تأكد من صحة الكلمات المكتوبة أو امسح البحث.",
                                actionButtonText = "مسح البحث",
                                onActionClick = { searchQuery = "" }
                            )
                        }
                    } else {
                        items(filteredCourses, key = { it.id }) { course ->
                            CourseCard(
                                course = course,
                                onClick = { onSelectCourse(course) },
                                onDelete = { courseToDelete = course }
                            )
                        }
                    }

                    // 6. About Footer Banner
                    item {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { showAboutDialog = true }
                                .semantics { contentDescription = "فتح معلومات حول التطبيق والمطور المهندس أمين علي" },
                            color = MaterialTheme.colorScheme.surfaceContainerLowest
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "iLearn v1.0 • تصميم وتطوير: Eng. Ameen Ali",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    item {
                        Spacer(modifier = Modifier.height(72.dp))
                    }
                }
            }
            }

            // Extraction Loading Dialog
            if (importState is ImportState.Progress) {
                ImportProgressDialog(progressState = importState)
            }

            // Pre-flight Course Import BottomSheet (Flexible Cancel & Browse)
            if (showCourseImportSheet) {
                FileUploadBottomSheet(
                    title = "استيراد كورس جديد",
                    description = "اختر ملف أرشيف الكورس الذي استلمته (.zip أو .rar) ليتم فك ضغطه وفهرسة دروسه وترجماته تلقائياً لتعمل بالكامل دون إنترنت.",
                    supportedFormatsHint = ".zip, .rar",
                    icon = Icons.Default.FolderZip,
                    confirmButtonText = "اختيار ملف الكورس من الذاكرة",
                    onConfirmBrowse = {
                        archivePickerLauncher.launch(
                            arrayOf(
                                "application/zip",
                                "application/x-zip-compressed",
                                "application/x-rar-compressed",
                                "application/octet-stream",
                                "*/*"
                            )
                        )
                    },
                    onDismiss = { showCourseImportSheet = false }
                )
            }

            // Pre-flight Backup Restore BottomSheet (Flexible Cancel & Browse)
            if (showBackupRestoreSheet) {
                FileUploadBottomSheet(
                    title = "استعادة النسخة الاحتياطية",
                    description = "اختر ملف النسخة الاحتياطية (ilearn_backup.json) لاسترجاع تقدمك في المشاهدة والملاحظات المدونة وقائمة الدروس المفضلة.",
                    supportedFormatsHint = ".json",
                    icon = Icons.Default.Download,
                    confirmButtonText = "اختيار ملف النسخة الاحتياطية",
                    onConfirmBrowse = {
                        importBackupLauncher.launch(arrayOf("application/json", "*/*"))
                    },
                    onDismiss = { showBackupRestoreSheet = false }
                )
            }

            // Storage Info Dialog
            if (showStorageDialog) {
                AlertDialog(
                    onDismissRequest = { showStorageDialog = false },
                    icon = { Icon(Icons.Default.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    title = { Text("بيانات التخزين والمساحة", fontWeight = FontWeight.Bold) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = "مساحة الكورسات المحملة: ${formatStorageSize(storageCoursesSize)}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "المساحة المؤقتة (الكاش): ${formatStorageSize(storageCacheSize)}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            FilledTonalButton(
                                onClick = {
                                    coroutineScope.launch {
                                        onClearCache()
                                        val info = onGetStorageInfo()
                                        storageCoursesSize = info.first
                                        storageCacheSize = info.second
                                        snackbarHostState.showSnackbar("تم تنظيف الذاكرة المؤقتة بنجاح")
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.CleaningServices, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("تنظيف المساحة المؤقتة")
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showStorageDialog = false }) {
                            Text("إغلاق")
                        }
                    }
                )
            }

            // About Dialog
            if (showAboutDialog) {
                AboutAppDialog(onDismiss = { showAboutDialog = false })
            }

            // Delete Confirmation Dialog
            courseToDelete?.let { course ->
                AlertDialog(
                    onDismissRequest = { courseToDelete = null },
                    icon = { Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                    title = { Text("حذف الكورس؟") },
                    text = { Text("هل أنت متأكد من رغبتك في حذف كورس \"${course.title}\" وجميع ملفات الفيديو والترجمة المرتبطة به من الجهاز لتحرير المساحة؟") },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                onDeleteCourse(course)
                                courseToDelete = null
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("تأكيد الحذف")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { courseToDelete = null }) {
                            Text("إلغاء")
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun AboutAppDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current

    fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            context.startActivity(intent)
        } catch (_: Exception) {}
    }

    fun openEmail(email: String) {
        try {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:$email")
            }
            context.startActivity(intent)
        } catch (_: Exception) {}
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            Brush.linearGradient(
                                colors = listOf(Color(0xFF06B6D4), Color(0xFF1E3A8A))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "iL",
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 18.sp,
                        letterSpacing = (-0.5).sp
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text("حول منصة iLearn", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text("الإصدار v1.0 • منصة التعلّم الذاتي", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Developer Profile Card
                ElevatedCard(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "LEAD ARCHITECT & DEVELOPER",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            letterSpacing = 0.5.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Eng. Ameen Ali",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = "Lead AI Platform Architect & Developer",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        // Social Links Row (LinkedIn, GitHub, Email)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // LinkedIn Button
                            Button(
                                onClick = { openUrl("https://www.linkedin.com/in/eng-ameen-ali/") },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0A66C2)),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                            ) {
                                Text("LinkedIn", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }

                            // GitHub Button
                            Button(
                                onClick = { openUrl("https://github.com/ameen68-ali") },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF24292F)),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                            ) {
                                Text("GitHub", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }

                            // Email Button
                            FilledTonalButton(
                                onClick = { openEmail("ameen68.ali@gmail.com") },
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                            ) {
                                Text("Email", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // App Mission Card
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "رؤية المنصة والأهداف",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "صُممت منصة iLearn لتكون بيئة تعليمية ذكية ومستقلة 100% تعمل محلياً دون الحاجة للإنترنت، مخصصة لدراسة علوم الرياضيات والذكاء الاصطناعي مع دعم متقدم للترجمة والدبلجة الصوتية الذكية وحفظ التقدم والملاحظات.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 18.sp
                        )
                    }
                }

                Text(
                    text = "جميع الحقوق محفوظة © 2026 م. أمين علي",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("إغلاق")
            }
        }
    )
}

@Composable
fun StudyStatsCard(stats: StudyStats) {
    ElevatedCard(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Analytics,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "لوحة تقدّم التعلّم",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                if (stats.streakDays > 0) {
                    Surface(
                        color = Color(0xFFEA580C).copy(alpha = 0.15f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "🔥 ${stats.streakDays} ${if (stats.streakDays == 1) "يوم" else "أيام"} تتابع",
                            color = Color(0xFFEA580C),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 3-Metric Metric Grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Time Studied
                MetricTile(
                    modifier = Modifier.weight(1f),
                    title = "وقت الدراسة",
                    value = stats.formattedStudyTime,
                    icon = Icons.Default.Schedule,
                    tint = Color(0xFF0284C7)
                )

                // Completed Lessons
                MetricTile(
                    modifier = Modifier.weight(1f),
                    title = "دروس مكتملة",
                    value = "${stats.completedLessonsCount} / ${stats.totalLessonsCount}",
                    icon = Icons.Default.CheckCircleOutline,
                    tint = Color(0xFF10B981)
                )

                // Bookmarks
                MetricTile(
                    modifier = Modifier.weight(1f),
                    title = "المفضلة",
                    value = "${stats.bookmarkedLessonsCount} درس",
                    icon = Icons.Default.StarOutline,
                    tint = Color(0xFFF59E0B)
                )
            }

            if (stats.totalLessonsCount > 0) {
                Spacer(modifier = Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { (stats.overallProgressPercentage / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = Color(0xFF10B981),
                    trackColor = MaterialTheme.colorScheme.surfaceDim
                )
            }
        }
    }
}

@Composable
fun MetricTile(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = value,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun EmptyCoursesView(
    onImportClick: () -> Unit,
    onAddYouTube: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(108.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.FolderZip,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(54.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "مرحباً بك في iLearn",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = "تطبيقك السريع للتعلّم دون إنترنت وعبر قوائم يوتيوب.\nاستورد ملف كورس (.zip أو .rar) أو أضف قائمة تشغيل من YouTube.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp
        )

        Spacer(modifier = Modifier.height(28.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilledIconButton(
                onClick = onImportClick,
                modifier = Modifier
                    .size(56.dp)
                    .semantics { contentDescription = "استيراد ملف كورس ZIP أو RAR" }
            ) {
                Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(26.dp))
            }
            FilledTonalIconButton(
                onClick = onAddYouTube,
                modifier = Modifier
                    .size(56.dp)
                    .semantics { contentDescription = "إضافة كورس من YouTube" }
            ) {
                Icon(Icons.Default.OndemandVideo, contentDescription = null, tint = Color(0xFFEF4444), modifier = Modifier.size(26.dp))
            }
        }
    }
}

@Composable
fun ContinueLearningHeroCard(
    resumeInfo: ResumeLessonInfo,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .semantics { contentDescription = "متابعة دراسة الدرس: ${resumeInfo.lesson.title}" },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "تابع التعلّم",
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                val mins = (resumeInfo.lastPositionMs / 1000) / 60
                val secs = (resumeInfo.lastPositionMs / 1000) % 60
                Text(
                    text = String.format("توقفت عند %02d:%02d", mins, secs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = resumeInfo.lesson.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = resumeInfo.courseTitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
            )

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                FilledIconButton(
                    onClick = onClick,
                    modifier = Modifier
                        .size(48.dp)
                        .semantics { contentDescription = "متابعة الدرس" }
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                }
            }
        }
    }
}

@Composable
fun CourseCard(
    course: Course,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .semantics { contentDescription = "كورس ${course.title}، ${course.completedLessons} من ${course.totalLessons} درساً مكتمل" },
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val isYouTubeCourse = course.sourceType == com.ilearn.app.data.SourceType.YOUTUBE
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (isYouTubeCourse) Color(0xFFEF4444).copy(alpha = 0.14f)
                            else MaterialTheme.colorScheme.secondaryContainer
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when {
                            course.isCompleted -> Icons.Default.CheckCircle
                            isYouTubeCourse -> Icons.Default.OndemandVideo
                            else -> Icons.AutoMirrored.Filled.MenuBook
                        },
                        contentDescription = null,
                        tint = when {
                            course.isCompleted -> Color(0xFF10B981)
                            isYouTubeCourse -> Color(0xFFEF4444)
                            else -> MaterialTheme.colorScheme.secondary
                        },
                        modifier = Modifier.size(28.dp)
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = course.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (isYouTubeCourse) "${course.totalLessons} درساً • YouTube" else "${course.totalLessons} درساً • ترجمة عربية وإنجليزية",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "حذف كورس ${course.title}",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Progress Indicator
            LinearProgressIndicator(
                progress = { (course.progressPercentage / 100f).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = if (course.isCompleted) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${course.completedLessons} من ${course.totalLessons} درساً مكتمل",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${course.progressPercentage}%",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (course.isCompleted) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
fun ImportProgressDialog(progressState: ImportState.Progress) {
    AlertDialog(
        onDismissRequest = {},
        confirmButton = {},
        title = { Text("جاري استيراد الكورس...", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                LinearProgressIndicator(
                    progress = { progressState.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                )
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = progressState.currentTask,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "${(progressState.progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    )
}

fun formatStorageSize(bytes: Long): String {
    if (bytes <= 0) return "0 ميجابايت"
    val mb = bytes / (1024 * 1024)
    return if (mb >= 1024) {
        String.format(Locale.US, "%.2f جيجابايت", mb / 1024.0)
    } else {
        "$mb ميجابايت"
    }
}
