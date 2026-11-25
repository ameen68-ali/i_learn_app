package com.ilearn.app.data

import android.content.Context
import android.net.Uri
import com.ilearn.app.importer.CourseArchiveExporter
import com.ilearn.app.importer.CourseArchiveImporter
import com.ilearn.app.importer.ImportState
import com.ilearn.app.utils.AudioExtractorHelper
import com.ilearn.app.youtube.LessonDownloadState
import com.ilearn.app.youtube.YouTubeOfflineDownloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class CourseRepository(private val context: Context) {

    val dbHelper = CourseDatabaseHelper(context)
    val importer = CourseArchiveImporter(context, dbHelper)
    private val youtubeDownloader = YouTubeOfflineDownloader(context, dbHelper)

    private val scope = CoroutineScope(Dispatchers.IO)

    private val _courses = MutableStateFlow<List<Course>>(emptyList())
    val courses: StateFlow<List<Course>> = _courses.asStateFlow()

    private val _resumeLesson = MutableStateFlow<ResumeLessonInfo?>(null)
    val resumeLesson: StateFlow<ResumeLessonInfo?> = _resumeLesson.asStateFlow()

    private val _stats = MutableStateFlow(StudyStats())
    val stats: StateFlow<StudyStats> = _stats.asStateFlow()

    val importState: StateFlow<ImportState> = importer.importState
    val lessonDownloads: StateFlow<Map<String, LessonDownloadState>> = youtubeDownloader.downloads
    val lessonDownloadErrors: StateFlow<Map<String, String>> = youtubeDownloader.errors

    init {
        // Listen to database updates reactively
        scope.launch {
            dbHelper.databaseUpdates.collect {
                refreshData()
            }
        }
    }

    suspend fun refreshData() = withContext(Dispatchers.IO) {
        var loaded = dbHelper.getAllCourses()
        val cutoff = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
        if (loaded.any { it.sourceType == SourceType.YOUTUBE && it.lastSyncedAt < cutoff && (it.lastSyncedAt != 0L || it.title != "YouTube course (refresh required)") }) {
            dbHelper.expireRemoteMetadata()
            loaded = dbHelper.getAllCourses()
        }
        _courses.value = loaded
        _resumeLesson.value = dbHelper.getResumeLesson()
        _stats.value = dbHelper.getStudyStats()
    }

    suspend fun getLessonsForCourse(courseId: String): List<Lesson> {
        return dbHelper.getLessonsForCourse(courseId)
    }

    suspend fun updateLessonProgress(
        lessonId: String,
        courseId: String,
        positionMs: Long,
        totalDurationMs: Long
    ) {
        dbHelper.updateLessonProgress(lessonId, courseId, positionMs, totalDurationMs)
    }

    suspend fun toggleLessonCompleted(lessonId: String, courseId: String, completed: Boolean) {
        dbHelper.toggleLessonCompleted(lessonId, courseId, completed)
    }

    suspend fun toggleBookmark(lessonId: String, courseId: String, isBookmarked: Boolean) {
        dbHelper.toggleLessonBookmark(lessonId, courseId, isBookmarked)
    }

    suspend fun getNotesForLesson(lessonId: String): List<LessonNote> {
        return dbHelper.getNotesForLesson(lessonId)
    }

    suspend fun insertNote(note: LessonNote) {
        dbHelper.insertNote(note)
    }

    suspend fun deleteNote(noteId: String) {
        dbHelper.deleteNote(noteId)
    }

    suspend fun exportBackupJson(): String {
        return dbHelper.exportProgressJson()
    }

    suspend fun importBackupJson(jsonString: String): Boolean {
        val success = dbHelper.importProgressJson(jsonString)
        if (success) {
            refreshData()
        }
        return success
    }

    suspend fun extractLessonAudio(lesson: Lesson): File? = withContext(Dispatchers.IO) {
        val videoFile = File(lesson.videoPath)
        if (!videoFile.exists()) return@withContext null

        val outputDir = File(context.cacheDir, "extracted_audio").apply { mkdirs() }
        val safeTitle = lesson.title.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(30)
        val outputFile = File(outputDir, "lesson_${lesson.orderIndex}_${safeTitle}.m4a")

        val success = AudioExtractorHelper.extractAudioFromVideo(videoFile, outputFile)
        if (success && outputFile.exists()) {
            outputFile
        } else {
            null
        }
    }

    suspend fun attachArabicAudio(lesson: Lesson, audioUri: Uri): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val videoFile = File(lesson.videoPath)
            val courseDir = videoFile.parentFile ?: context.filesDir
            val audioArDir = File(courseDir, "audio_ar").apply { mkdirs() }

            val targetFile = File(audioArDir, "ar_${lesson.id}.m4a")
            context.contentResolver.openInputStream(audioUri)?.use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext false

            if (targetFile.exists() && targetFile.length() > 0) {
                dbHelper.setLessonArabicAudio(lesson.id, targetFile.absolutePath)
                refreshData()
                true
            } else {
                false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun removeArabicAudio(lesson: Lesson): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            lesson.audioArPath?.let { path ->
                val f = File(path)
                if (f.exists()) f.delete()
            }
            dbHelper.setLessonArabicAudio(lesson.id, null)
            refreshData()
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun importCourseArchive(uri: Uri) {
        importer.importArchive(uri)
    }

    fun resetImportState() {
        importer.resetState()
    }

    fun startYouTubeLessonDownload(lesson: Lesson) {
        youtubeDownloader.startDownload(lesson)
    }

    fun cancelYouTubeLessonDownload(lessonId: String) {
        youtubeDownloader.cancelDownload(lessonId)
    }

    fun dismissDownloadError(lessonId: String) {
        youtubeDownloader.dismissError(lessonId)
    }

    suspend fun removeOfflineYouTubeVideo(lesson: Lesson): Boolean {
        return youtubeDownloader.removeOfflineVideo(lesson)
    }

    suspend fun exportOfflineCourseZip(
        course: Course,
        lessons: List<Lesson>,
        targetUri: Uri,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ) {
        CourseArchiveExporter.exportCourseToZip(context, course, lessons, targetUri, onProgress)
    }

    suspend fun deleteCourse(course: Course) = withContext(Dispatchers.IO) {
        val root = (context.getExternalFilesDir(null)?.let { File(it, "courses") }
            ?: File(context.filesDir, "courses")).canonicalFile
        if (course.sourceType == SourceType.LOCAL) {
            val folder = File(course.folderPath).canonicalFile
            require(folder != root && folder.path.startsWith(root.path + File.separator)) { "Invalid course storage path" }
            if (folder.exists()) check(folder.deleteRecursively()) { "Unable to delete course files" }
        } else if (course.sourceType == SourceType.YOUTUBE) {
            val offlineFolder = YouTubeOfflineDownloader.courseDirFor(root, course.id).canonicalFile
            if (offlineFolder != root && offlineFolder.path.startsWith(root.path + File.separator) && offlineFolder.exists()) {
                offlineFolder.deleteRecursively()
            }
        }
        dbHelper.deleteCourse(course.id)
    }

    suspend fun getStorageSizeInfo(): Pair<Long, Long> = withContext(Dispatchers.IO) {
        val coursesDir = context.getExternalFilesDir("courses") ?: File(context.filesDir, "courses")
        val cacheDir = context.cacheDir

        fun dirSize(dir: File?): Long {
            if (dir == null || !dir.exists()) return 0L
            return dir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
        }

        Pair(dirSize(coursesDir), dirSize(cacheDir))
    }

    suspend fun clearCache() = withContext(Dispatchers.IO) {
        context.cacheDir.deleteRecursively()
    }
}
