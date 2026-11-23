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
import com.ilearn.app.data.CourseRepository
import com.ilearn.app.data.Lesson
import com.ilearn.app.data.LessonNote
import com.ilearn.app.player.ILearnPlayerController
import com.ilearn.app.ui.screens.CourseCatalogScreen
import com.ilearn.app.ui.screens.CourseDetailScreen
import com.ilearn.app.ui.screens.VideoPlayerScreen
import com.ilearn.app.ui.theme.ILearnTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class MainActivity : ComponentActivity() {

    private lateinit var repository: CourseRepository
    private lateinit var playerController: ILearnPlayerController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        repository = CourseRepository(this)
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

                        NavHost(navController = navController, startDestination = "catalog") {
                            // 1. Home / Course Catalog Screen (Zero preloaded, direct view, stats & backup)
                            composable("catalog") {
                                CourseCatalogScreen(
                                    courses = courses,
                                    resumeLesson = resumeLesson,
                                    stats = stats,
                                    importState = importState,
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
                                            repository.deleteCourse(course)
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
                                    onClearCache = {
                                        repository.clearCache()
                                    }
                                )
                            }

                            // 2. Course Detail / Lessons List Screen
                            composable(
                                route = "course_detail/{courseId}",
                                arguments = listOf(navArgument("courseId") { type = NavType.StringType })
                            ) { backStackEntry ->
                                val courseId = backStackEntry.arguments?.getString("courseId") ?: ""
                                val currentCourse = courses.firstOrNull { it.id == courseId }

                                var lessons by remember { mutableStateOf<List<Lesson>>(emptyList()) }

                                LaunchedEffect(courseId, courses) {
                                    lessons = repository.getLessonsForCourse(courseId)
                                }

                                if (currentCourse != null) {
                                    CourseDetailScreen(
                                        course = currentCourse,
                                        lessons = lessons,
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
                                        }
                                    )
                                }
                            }

                            // 3. Video Player Screen with Subtitles, Gestures, Audio Tracks & Dubbing
                            composable(
                                route = "player/{courseId}/{lessonId}",
                                arguments = listOf(
                                    navArgument("courseId") { type = NavType.StringType },
                                    navArgument("lessonId") { type = NavType.StringType }
                                )
                            ) { backStackEntry ->
                                val courseId = backStackEntry.arguments?.getString("courseId") ?: ""
                                val lessonId = backStackEntry.arguments?.getString("lessonId") ?: ""

                                val currentCourse = courses.firstOrNull { it.id == courseId }
                                var allLessons by remember { mutableStateOf<List<Lesson>>(emptyList()) }
                                var activeLesson by remember { mutableStateOf<Lesson?>(null) }
                                var notes by remember { mutableStateOf<List<LessonNote>>(emptyList()) }

                                LaunchedEffect(courseId, lessonId) {
                                    allLessons = repository.getLessonsForCourse(courseId)
                                    activeLesson = allLessons.firstOrNull { it.id == lessonId }
                                    notes = repository.getNotesForLesson(lessonId)
                                }

                                activeLesson?.let { lesson ->
                                    VideoPlayerScreen(
                                        lesson = lesson,
                                        courseTitle = currentCourse?.title ?: "iLearn",
                                        allLessons = allLessons,
                                        notes = notes,
                                        playerController = playerController,
                                        onBack = {
                                            playerController.pause()
                                            navController.popBackStack()
                                        },
                                        onNavigateLesson = { targetLesson ->
                                            activeLesson = targetLesson
                                            lifecycleScope.launch {
                                                notes = repository.getNotesForLesson(targetLesson.id)
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
                                        }
                                    )
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
