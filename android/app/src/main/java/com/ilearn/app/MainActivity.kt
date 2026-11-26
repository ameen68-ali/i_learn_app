package com.ilearn.app

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ilearn.app.data.SourceType
import com.ilearn.app.tutor.ProviderProfileStore
import com.ilearn.app.ui.screens.YouTubeImportScreen
import com.ilearn.app.ui.screens.YouTubeLessonScreen
import com.ilearn.app.ui.screens.ProviderSettingsScreen
import com.ilearn.app.ui.screens.TutorDialog
import com.ilearn.app.data.CourseRepository
import com.ilearn.app.data.Lesson
import com.ilearn.app.data.LessonNote
import com.ilearn.app.player.ILearnPlayerController
import com.ilearn.app.ui.screens.CourseCatalogScreen
import com.ilearn.app.ui.screens.CourseDetailScreen
import com.ilearn.app.ui.screens.DueFlashcardsReviewDialog
import com.ilearn.app.ui.screens.StudyPackDialog
import com.ilearn.app.ui.screens.VideoPlayerScreen
import com.ilearn.app.ui.theme.ILearnTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class MainActivity : ComponentActivity() {

    private lateinit var repository: CourseRepository
    private lateinit var providerProfiles: ProviderProfileStore
    private lateinit var playerController: ILearnPlayerController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        providerProfiles = ProviderProfileStore(this)
        repository = CourseRepository(this, providerProfiles)
        playerController = ILearnPlayerController(this)

        // Initial fetch of courses and progress
        lifecycleScope.launch {
            repository.refreshData()
        }

        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                ILearnTheme {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        val navController = rememberNavController()

                        val courses by repository.courses.collectAsState()
                        val resumeLesson by repository.resumeLesson.collectAsState()
                        val stats by repository.stats.collectAsState()
                        val importState by repository.importState.collectAsState()
                        val dbUpdates by repository.dbHelper.databaseUpdates.collectAsState()
                        val lessonDownloads by repository.lessonDownloads.collectAsState()
                        val lessonDownloadErrors by repository.lessonDownloadErrors.collectAsState()
                        val courseDownloads by repository.courseDownloads.collectAsState()
                        val courseDownloadErrors by repository.courseDownloadErrors.collectAsState()
                        val translatingLessons by repository.translatingLessons.collectAsState()
                        val translationErrors by repository.translationErrors.collectAsState()
                        val providerUpdates by providerProfiles.updates.collectAsState()
                        val hasConfiguredAi = remember(providerUpdates) { repository.hasConfiguredAi() }
                        var dueFlashcardsCount by remember { mutableIntStateOf(0) }
                        var showGlobalDueReview by remember { mutableStateOf(false) }

                        LaunchedEffect(dbUpdates) {
                            dueFlashcardsCount = repository.getDueFlashcardsCount()
                        }

                        if (showGlobalDueReview) {
                            DueFlashcardsReviewDialog(
                                title = "مراجعة البطاقات المستحقة",
                                onLoadDueCards = { repository.getDueFlashcards() },
                                onReviewFlashcard = { cardId, quality ->
                                    repository.reviewFlashcard(cardId, quality)
                                },
                                onJumpToLessonTimestamp = { lid, cid, ts ->
                                    navController.navigate("player/$cid/$lid?seekMs=$ts")
                                },
                                onClose = { showGlobalDueReview = false }
                            )
                        }

                        NavHost(navController = navController, startDestination = "catalog") {
                            // 1. Home / Course Catalog Screen (Zero preloaded, direct view, stats & backup)
                            composable("catalog") {
                                CourseCatalogScreen(
                                    courses = courses,
                                    resumeLesson = resumeLesson,
                                    stats = stats,
                                    importState = importState,
                                    dueFlashcardsCount = dueFlashcardsCount,
                                    onOpenDueFlashcards = { showGlobalDueReview = true },
                                    onSelectCourse = { course ->
                                        navController.navigate("course_detail/${course.id}")
                                    },
                                    onResumeLesson = { resume ->
                                        navController.navigate("player/${resume.courseId}/${resume.lesson.id}")
                                    },
                                    onImportArchive = { uri ->
                                        lifecycleScope.launch {
                                            repository.importCourseArchive(uri)
                                        }
                                    },
                                    onDeleteCourse = { course ->
                                        lifecycleScope.launch {
                                            try {
                                                val lessonsToDelete = repository.getLessonsForCourse(course.id)
                                                withContext(Dispatchers.IO) {
                                                    val privateData = com.ilearn.app.tutor.LessonSourceStore(this@MainActivity)
                                                    privateData.deleteLessons(lessonsToDelete)
                                                }
                                                repository.deleteCourse(course)
                                            } catch (e: kotlinx.coroutines.CancellationException) {
                                                throw e
                                            } catch (_: Exception) {
                                                android.widget.Toast.makeText(this@MainActivity, "Unable to remove all course data. Check storage access and try again.", android.widget.Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    },
                                    onDismissImportState = {
                                        repository.resetImportState()
                                    },
                                    onExportBackupToUri = { uri ->
                                        exportBackupToUri(uri)
                                    },
                                    onImportBackupFromUri = { uri ->
                                        importBackupFromUri(uri)
                                    },
                                    onGetStorageInfo = {
                                        repository.getStorageSizeInfo()
                                    },
                                    onClearCache = { repository.clearCache() },
                                    onAddYouTube = { navController.navigate("youtube_import") },
                                    onApiSettings = { playerController.pause(); navController.navigate("api_settings") }
                                )
                            }

                            composable("api_settings") {
                                ProviderSettingsScreen(providerProfiles) { navController.popBackStack() }
                            }
                            composable("youtube_import?playlist={playlist}", arguments = listOf(navArgument("playlist") { defaultValue = "" })) { entry ->
                                YouTubeImportScreen(repository, entry.arguments?.getString("playlist")?.takeIf { it.isNotBlank() },
                                    onBack = { navController.popBackStack() },
                                    onImported = { id -> navController.popBackStack(); navController.navigate("course_detail/$id") { launchSingleTop = true } })
                            }

                            // 2. Course Detail / Lessons List Screen
                            composable(
                                route = "course_detail/{courseId}",
                                arguments = listOf(navArgument("courseId") { type = NavType.StringType })
                            ) { backStackEntry ->
                                val courseId = backStackEntry.arguments?.getString("courseId") ?: ""
                                val currentCourse = courses.firstOrNull { it.id == courseId }

                                var lessons by remember { mutableStateOf<List<Lesson>>(emptyList()) }
                                var courseDueCount by remember(courseId) { mutableIntStateOf(0) }
                                var showCourseDueReview by remember(courseId) { mutableStateOf(false) }

                                LaunchedEffect(courseId, courses, dbUpdates) {
                                    lessons = repository.getLessonsForCourse(courseId)
                                    courseDueCount = repository.getDueFlashcardsCount(courseId)
                                }

                                if (showCourseDueReview && currentCourse != null) {
                                    DueFlashcardsReviewDialog(
                                        title = "بطاقات: ${currentCourse.title}",
                                        onLoadDueCards = { repository.getDueFlashcards(courseId) },
                                        onReviewFlashcard = { cardId, quality ->
                                            repository.reviewFlashcard(cardId, quality)
                                        },
                                        onJumpToLessonTimestamp = { lid, cid, ts ->
                                            navController.navigate("player/$cid/$lid?seekMs=$ts")
                                        },
                                        onClose = { showCourseDueReview = false }
                                    )
                                }

                                if (currentCourse != null) {
                                    CourseDetailScreen(
                                        course = currentCourse,
                                        lessons = lessons,
                                        lessonDownloads = lessonDownloads,
                                        lessonDownloadErrors = lessonDownloadErrors,
                                        dueFlashcardsCount = courseDueCount,
                                        onOpenDueFlashcards = { showCourseDueReview = true },
                                        onSearchTranscripts = { q -> repository.searchCourseTranscripts(lessons, q) },
                                        onOpenTranscriptHit = { lesson, ts ->
                                            navController.navigate("player/${currentCourse.id}/${lesson.id}?seekMs=$ts")
                                        },
                                        courseDownloadState = courseDownloads[currentCourse.id],
                                        courseDownloadError = courseDownloadErrors[currentCourse.id],
                                        onDownloadChapteredCourse = {
                                            repository.startChapteredCourseDownload(currentCourse, lessons)
                                        },
                                        onCancelChapteredCourseDownload = {
                                            repository.cancelChapteredCourseDownload(currentCourse.id)
                                        },
                                        onDismissCourseDownloadError = {
                                            repository.dismissCourseDownloadError(currentCourse.id)
                                        },
                                        onRefresh = { currentCourse.remoteId?.let { navController.navigate("youtube_import?playlist=$it") } },
                                        onBack = {
                                            playerController.pause()
                                            navController.popBackStack()
                                        },
                                        onSelectLesson = { lesson ->
                                            navController.navigate("player/${currentCourse.id}/${lesson.id}")
                                        },
                                        onToggleLessonCompleted = { lesson, completed ->
                                            lifecycleScope.launch {
                                                repository.toggleLessonCompleted(lesson.id, currentCourse.id, completed)
                                                lessons = repository.getLessonsForCourse(courseId)
                                            }
                                        },
                                        onToggleLessonBookmarked = { lesson, bookmarked ->
                                            lifecycleScope.launch {
                                                repository.toggleBookmark(lesson.id, currentCourse.id, bookmarked)
                                                lessons = repository.getLessonsForCourse(courseId)
                                            }
                                        },
                                        onDownloadLesson = { lesson ->
                                            repository.startYouTubeLessonDownload(lesson)
                                        },
                                        onCancelDownload = { lessonId ->
                                            repository.cancelYouTubeLessonDownload(lessonId)
                                        },
                                        onRemoveOfflineLesson = { lesson ->
                                            lifecycleScope.launch {
                                                repository.removeOfflineYouTubeVideo(lesson)
                                                lessons = repository.getLessonsForCourse(courseId)
                                            }
                                        },
                                        onDismissDownloadError = { lessonId ->
                                            repository.dismissDownloadError(lessonId)
                                        },
                                        onExportOfflineCourse = { targetUri, onProgress ->
                                            repository.exportOfflineCourseZip(currentCourse, lessons, targetUri, onProgress)
                                        },
                                        hasConfiguredAi = hasConfiguredAi,
                                        lessonTranslations = translatingLessons,
                                        translationErrors = translationErrors,
                                        onTranslateLessonToArabic = { lesson ->
                                            repository.startLessonArabicTranslation(lesson)
                                        },
                                        onCancelTranslation = { lessonId ->
                                            repository.cancelTranslation(lessonId)
                                        },
                                        onDismissTranslationError = { lessonId ->
                                            repository.dismissTranslationError(lessonId)
                                        },
                                        onTranslateCourseToArabic = { translatable ->
                                            repository.startCourseArabicTranslation(translatable)
                                        }
                                    )
                                }
                            }

                            // 3. Video Player Screen with Subtitles, Gestures, Audio Tracks & Dubbing
                            composable(
                                route = "player/{courseId}/{lessonId}?seekMs={seekMs}",
                                arguments = listOf(
                                    navArgument("courseId") { type = NavType.StringType },
                                    navArgument("lessonId") { type = NavType.StringType },
                                    navArgument("seekMs") { type = NavType.LongType; defaultValue = -1L }
                                )
                            ) { backStackEntry ->
                                val courseId = backStackEntry.arguments?.getString("courseId") ?: ""
                                val lessonId = backStackEntry.arguments?.getString("lessonId") ?: ""
                                val initialSeekMs = backStackEntry.arguments?.getLong("seekMs") ?: -1L

                                val currentCourse = courses.firstOrNull { it.id == courseId }
                                var allLessons by remember { mutableStateOf<List<Lesson>>(emptyList()) }
                                var activeLesson by remember { mutableStateOf<Lesson?>(null) }
                                var notes by remember { mutableStateOf<List<LessonNote>>(emptyList()) }

                                LaunchedEffect(courseId, lessonId, dbUpdates) {
                                    allLessons = repository.getLessonsForCourse(courseId)
                                    activeLesson = allLessons.firstOrNull { it.id == lessonId }
                                    notes = repository.getNotesForLesson(lessonId)
                                }

                                activeLesson?.let { lesson ->
                                    var tutorPosition by remember(lesson.id) { mutableStateOf<Long?>(null) }
                                    var tutorFrameBase64 by remember(lesson.id) { mutableStateOf<String?>(null) }
                                    var showStudyPack by remember(lesson.id) { mutableStateOf(false) }
                                    var youtubeSeekTrigger by remember(lesson.id) { mutableStateOf<Pair<Long, Long>?>(null) }
                                    val useOfflinePlayer = lesson.sourceType == SourceType.LOCAL || lesson.hasOfflineVideo
                                    LaunchedEffect(lesson.id, useOfflinePlayer) {
                                        if (!useOfflinePlayer) playerController.pause()
                                    }
                                    if (!useOfflinePlayer) {
                                        YouTubeLessonScreen(lesson, allLessons, notes, providerProfiles,
                                            downloadState = lessonDownloads[lesson.id],
                                            downloadError = lessonDownloadErrors[lesson.id],
                                            onDownloadOffline = { repository.startYouTubeLessonDownload(lesson) },
                                            onCancelDownload = { repository.cancelYouTubeLessonDownload(lesson.id) },
                                            onDismissDownloadError = { repository.dismissDownloadError(lesson.id) },
                                            hasConfiguredAi = hasConfiguredAi,
                                            translationState = translatingLessons[lesson.id],
                                            translationError = translationErrors[lesson.id],
                                            onTranslateToArabic = { repository.startLessonArabicTranslation(lesson) },
                                            onCancelTranslation = { repository.cancelTranslation(lesson.id) },
                                            onDismissTranslationError = { repository.dismissTranslationError(lesson.id) },
                                            initialSeekMs = initialSeekMs,
                                            externalSeekRequest = youtubeSeekTrigger,
                                            onStudyPack = { showStudyPack = true },
                                            onBack = { navController.popBackStack() },
                                            onNavigate = { target -> navController.navigate("player/${target.courseId}/${target.id}") { popUpTo("player/{courseId}/{lessonId}?seekMs={seekMs}") { inclusive = true } } },
                                            onProgress = { pos, dur -> lifecycleScope.launch { repository.updateLessonProgress(lesson.id, lesson.courseId, pos, dur) } },
                                            onBookmark = { marked -> lifecycleScope.launch {
                                                repository.toggleBookmark(lesson.id, lesson.courseId, marked)
                                                allLessons = repository.getLessonsForCourse(courseId); activeLesson = allLessons.firstOrNull { it.id == lesson.id }
                                            } },
                                            onAddNote = { time, text -> lifecycleScope.launch {
                                                repository.insertNote(LessonNote(UUID.randomUUID().toString(), lesson.id, courseId, time, text)); notes = repository.getNotesForLesson(lesson.id)
                                            } },
                                            onDeleteNote = { id -> lifecycleScope.launch { repository.deleteNote(id); notes = repository.getNotesForLesson(lesson.id) } },
                                            onSettings = { navController.navigate("api_settings") })
                                    } else VideoPlayerScreen(
                                        lesson = lesson,
                                        courseTitle = currentCourse?.title ?: "iLearn",
                                        onTutor = { position -> playerController.pause(); tutorFrameBase64 = null; tutorPosition = position },
                                        allLessons = allLessons,
                                        notes = notes,
                                        playerController = playerController,
                                        onBack = {
                                            playerController.pause()
                                            navController.popBackStack()
                                        },
                                        onNavigateLesson = { targetLesson ->
                                            navController.navigate("player/${targetLesson.courseId}/${targetLesson.id}") {
                                                popUpTo("player/{courseId}/{lessonId}?seekMs={seekMs}") { inclusive = true }
                                            }
                                        },
                                        onSaveProgress = { targetLessonId, targetCourseId, pos, dur ->
                                            lifecycleScope.launch {
                                                repository.updateLessonProgress(targetLessonId, targetCourseId, pos, dur)
                                            }
                                        },
                                        onToggleBookmark = { bookmarked ->
                                            lifecycleScope.launch {
                                                repository.toggleBookmark(lesson.id, lesson.courseId, bookmarked)
                                                allLessons = repository.getLessonsForCourse(courseId)
                                                activeLesson = allLessons.firstOrNull { it.id == lesson.id }
                                            }
                                        },
                                        onAddNote = { timestampMs, noteText ->
                                            lifecycleScope.launch {
                                                val newNote = LessonNote(
                                                    id = UUID.randomUUID().toString(),
                                                    lessonId = lesson.id,
                                                    courseId = lesson.courseId,
                                                    timestampMs = timestampMs,
                                                    noteText = noteText
                                                )
                                                repository.insertNote(newNote)
                                                notes = repository.getNotesForLesson(lesson.id)
                                            }
                                        },
                                        onDeleteNote = { noteId ->
                                            lifecycleScope.launch {
                                                repository.deleteNote(noteId)
                                                notes = repository.getNotesForLesson(lesson.id)
                                            }
                                        },
                                        onExtractAudio = { targetLesson ->
                                            repository.extractLessonAudio(targetLesson)
                                        },
                                        onAttachArabicAudio = { targetLesson, audioUri ->
                                            val success = repository.attachArabicAudio(targetLesson, audioUri)
                                            if (success) {
                                                allLessons = repository.getLessonsForCourse(courseId)
                                                activeLesson = allLessons.firstOrNull { it.id == targetLesson.id }
                                            }
                                            success
                                        },
                                        onRemoveArabicAudio = { targetLesson ->
                                            val success = repository.removeArabicAudio(targetLesson)
                                            if (success) {
                                                allLessons = repository.getLessonsForCourse(courseId)
                                                activeLesson = allLessons.firstOrNull { it.id == targetLesson.id }
                                            }
                                            success
                                        },
                                        hasConfiguredAi = hasConfiguredAi,
                                        translationState = translatingLessons[lesson.id],
                                        translationError = translationErrors[lesson.id],
                                        onTranslateSubtitlesToArabic = { repository.startLessonArabicTranslation(lesson) },
                                        onCancelTranslation = { repository.cancelTranslation(lesson.id) },
                                        onDismissTranslationError = { repository.dismissTranslationError(lesson.id) },
                                        initialSeekMs = initialSeekMs,
                                        onStudyPack = { playerController.pause(); showStudyPack = true },
                                        onSnapFrameTutor = { position, frameBase64 ->
                                            playerController.pause()
                                            tutorFrameBase64 = frameBase64
                                            tutorPosition = position
                                        }
                                    )
                                    tutorPosition?.let { position ->
                                        TutorDialog(
                                            lesson = lesson,
                                            positionMs = position,
                                            profiles = providerProfiles,
                                            translationState = translatingLessons[lesson.id],
                                            translationError = translationErrors[lesson.id],
                                            onTranslateToArabic = if (hasConfiguredAi && !lesson.hasArabicSubtitles) {
                                                { repository.startLessonArabicTranslation(lesson) }
                                            } else null,
                                            onCancelTranslation = { repository.cancelTranslation(lesson.id) },
                                            onDismissTranslationError = { repository.dismissTranslationError(lesson.id) },
                                            initialFrameBase64 = tutorFrameBase64,
                                            initialFramePositionMs = position,
                                            onSeek = { playerController.seekTo(it); tutorPosition = null; tutorFrameBase64 = null },
                                            onSettings = { navController.navigate("api_settings") },
                                            onClose = { tutorPosition = null; tutorFrameBase64 = null }
                                        )
                                    }
                                    if (showStudyPack) {
                                        StudyPackDialog(
                                            lesson = lesson,
                                            hasConfiguredAi = hasConfiguredAi,
                                            onLoadCachedPack = {
                                                repository.getStudyPack(lesson.id) to repository.getFlashcardsForLesson(lesson.id)
                                            },
                                            onGeneratePack = { repository.generateAndSaveStudyPack(lesson) },
                                            onLoadLessonFlashcards = { repository.getFlashcardsForLesson(lesson.id) },
                                            onReviewFlashcard = { cardId, quality ->
                                                repository.reviewFlashcard(cardId, quality)
                                            },
                                            onSeek = { ts ->
                                                showStudyPack = false
                                                if (useOfflinePlayer) {
                                                    playerController.seekTo(ts)
                                                } else {
                                                    youtubeSeekTrigger = ts to System.nanoTime()
                                                }
                                            },
                                            onOpenSettings = { showStudyPack = false; navController.navigate("api_settings") },
                                            onClose = { showStudyPack = false }
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

    private suspend fun exportBackupToUri(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            contentResolver.openOutputStream(uri)?.use { os ->
                val json = repository.exportBackupJson()
                os.write(json.toByteArray(Charsets.UTF_8))
                os.flush()
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun importBackupFromUri(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val json = contentResolver.openInputStream(uri)?.use { inputStream ->
                inputStream.bufferedReader(Charsets.UTF_8).readText()
            } ?: return@withContext false
            repository.importBackupJson(json)
        } catch (_: Exception) {
            false
        }
    }

    override fun onPause() {
        super.onPause()
        playerController.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        playerController.release()
    }
}
