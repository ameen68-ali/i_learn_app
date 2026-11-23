package com.ilearn.app.importer

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.github.junrar.Archive
import com.github.junrar.rarfile.FileHeader
import com.ilearn.app.data.Course
import com.ilearn.app.data.CourseDatabaseHelper
import com.ilearn.app.data.Lesson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

sealed class ImportState {
    object Idle : ImportState()
    data class Progress(val progress: Float, val currentTask: String) : ImportState()
    data class Success(val courseTitle: String, val lessonCount: Int) : ImportState()
    data class Error(val message: String) : ImportState()
}

/**
 * Handles extracting and indexing .zip and .rar course packages into the local app storage and database.
 */
class CourseArchiveImporter(
    private val context: Context,
    private val dbHelper: CourseDatabaseHelper
) {

    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState.asStateFlow()

    fun resetState() {
        _importState.value = ImportState.Idle
    }

    suspend fun importArchive(archiveUri: Uri) = withContext(Dispatchers.IO) {
        val fileName = getFileNameFromUri(archiveUri) ?: "Course_${System.currentTimeMillis()}"
        val isRar = fileName.endsWith(".rar", ignoreCase = true)
        val isZip = fileName.endsWith(".zip", ignoreCase = true)

        val cleanCourseTitle = fileName
            .replace(Regex("\\.(zip|rar)$", RegexOption.IGNORE_CASE), "")
            .replace("_", " ")
            .trim()

        _importState.value = ImportState.Progress(0.05f, "بدء استخراج كورس $cleanCourseTitle...")

        // Courses destination directory in app-scoped external storage
        val coursesRootDir = File(context.getExternalFilesDir(null), "courses")
        if (!coursesRootDir.exists()) coursesRootDir.mkdirs()

        val courseTargetDir = File(coursesRootDir, cleanCourseTitle)
        if (courseTargetDir.exists()) {
            courseTargetDir.deleteRecursively()
        }
        courseTargetDir.mkdirs()

        try {
            if (isRar) {
                extractRar(archiveUri, courseTargetDir)
            } else {
                extractZip(archiveUri, courseTargetDir)
            }

            _importState.value = ImportState.Progress(0.90f, "فهرسة الدروس والترجمات في قاعدة البيانات...")
            val (course, lessons) = scanAndIndexCourse(cleanCourseTitle, courseTargetDir)

            if (lessons.isEmpty()) {
                courseTargetDir.deleteRecursively()
                _importState.value = ImportState.Error("لم يتم العثور على أي ملفات فيديو داخل مجلد compressed_videos/")
                return@withContext
            }

            dbHelper.insertCourseWithLessons(course, lessons)
            _importState.value = ImportState.Success(course.title, lessons.size)

        } catch (e: Exception) {
            e.printStackTrace()
            courseTargetDir.deleteRecursively()
            _importState.value = ImportState.Error("فشل استيراد الكورس: ${e.localizedMessage ?: e.message}")
        }
    }

    private fun extractZip(zipUri: Uri, destinationDir: File) {
        val inputStream = context.contentResolver.openInputStream(zipUri)
            ?: throw IOException("Cannot open archive input stream")

        ZipInputStream(BufferedInputStream(inputStream)).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            var extractedCount = 0

            while (entry != null) {
                val entryFile = File(destinationDir, entry.name)

                // Guard against Zip Slip vulnerability
                if (!entryFile.canonicalPath.startsWith(destinationDir.canonicalPath)) {
                    throw SecurityException("Zip Slip path traversal attempt: ${entry.name}")
                }

                if (entry.isDirectory) {
                    entryFile.mkdirs()
                } else {
                    entryFile.parentFile?.mkdirs()
                    FileOutputStream(entryFile).use { fos ->
                        zis.copyTo(fos)
                    }
                    extractedCount++
                    if (extractedCount % 5 == 0) {
                        _importState.value = ImportState.Progress(
                            0.10f + (extractedCount.toFloat() * 0.01f).coerceAtMost(0.75f),
                            "جاري فك: ${entryFile.name}"
                        )
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private fun extractRar(rarUri: Uri, destinationDir: File) {
        val tempCacheRar = File(context.cacheDir, "temp_import.rar")
        context.contentResolver.openInputStream(rarUri)?.use { input ->
            FileOutputStream(tempCacheRar).use { output ->
                input.copyTo(output)
            }
        } ?: throw IOException("Failed to read RAR stream")

        try {
            val archive = Archive(tempCacheRar)
            var header: FileHeader? = archive.nextFileHeader()
            var extractedCount = 0

            while (header != null) {
                val entryName = if (header.isUnicode) header.fileNameW else header.fileNameString
                val entryFile = File(destinationDir, entryName)

                if (!entryFile.canonicalPath.startsWith(destinationDir.canonicalPath)) {
                    throw SecurityException("RAR path traversal attempt: $entryName")
                }

                if (header.isDirectory) {
                    entryFile.mkdirs()
                } else {
                    entryFile.parentFile?.mkdirs()
                    FileOutputStream(entryFile).use { fos ->
                        archive.extractFile(header, fos)
                    }
                    extractedCount++
                    if (extractedCount % 5 == 0) {
                        _importState.value = ImportState.Progress(
                            0.10f + (extractedCount.toFloat() * 0.01f).coerceAtMost(0.75f),
                            "جاري فك: ${entryFile.name}"
                        )
                    }
                }
                header = archive.nextFileHeader()
            }
        } finally {
            tempCacheRar.delete()
        }
    }

    private fun scanAndIndexCourse(defaultTitle: String, baseExtractedDir: File): Pair<Course, List<Lesson>> {
        var videosDir = File(baseExtractedDir, "compressed_videos")
        var subtitlesArDir = File(baseExtractedDir, "subtitles_ar")
        var subtitlesEnDir = File(baseExtractedDir, "subtitles_en")
        var effectiveTitle = defaultTitle

        if (!videosDir.exists()) {
            val subfolders = baseExtractedDir.listFiles { f -> f.isDirectory } ?: emptyArray()
            for (sub in subfolders) {
                val candidate = File(sub, "compressed_videos")
                if (candidate.exists()) {
                    videosDir = candidate
                    subtitlesArDir = File(sub, "subtitles_ar")
                    subtitlesEnDir = File(sub, "subtitles_en")
                    effectiveTitle = sub.name.replace("_", " ").trim()
                    break
                }
            }
        }

        val videoFiles = (videosDir.listFiles { file ->
            file.isFile && file.name.endsWith(".mp4", ignoreCase = true) && !file.name.startsWith(".")
        } ?: emptyArray()).sortedWith(Comparator { a, b ->
            extractLeadingNumber(a.name).compareTo(extractLeadingNumber(b.name))
        })

        val courseId = effectiveTitle.lowercase()
            .replace(" ", "_")
            .replace(Regex("[^a-z0-9_]"), "")
            .ifBlank { "course_${System.currentTimeMillis()}" }

        val lessons = mutableListOf<Lesson>()
        val mmr = MediaMetadataRetriever()

        for ((index, vid) in videoFiles.withIndex()) {
            val order = index + 1
            val stem = vid.nameWithoutExtension

            val leadingNum = extractLeadingNumber(vid.name)

            var arSubFile = File(subtitlesArDir, "$stem.vtt")
            if (!arSubFile.exists()) arSubFile = File(subtitlesArDir, "$stem.srt")
            if (!arSubFile.exists()) {
                val candidate = subtitlesArDir.listFiles { f ->
                    extractLeadingNumber(f.name) == leadingNum && (f.name.endsWith(".vtt", true) || f.name.endsWith(".srt", true))
                }?.firstOrNull()
                if (candidate != null && candidate.exists()) arSubFile = candidate
            }

            var enSubFile = File(subtitlesEnDir, "$stem.vtt")
            if (!enSubFile.exists()) enSubFile = File(subtitlesEnDir, "$stem.srt")
            if (!enSubFile.exists()) {
                val candidate = subtitlesEnDir.listFiles { f ->
                    extractLeadingNumber(f.name) == leadingNum && (f.name.endsWith(".vtt", true) || f.name.endsWith(".srt", true))
                }?.firstOrNull()
                if (candidate != null && candidate.exists()) enSubFile = candidate
            }

            var durationMs = 0L
            try {
                mmr.setDataSource(vid.absolutePath)
                val durStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                durationMs = durStr?.toLongOrNull() ?: 0L
            } catch (e: Exception) {
                // Duration fallback
            }

            lessons.add(
                Lesson(
                    id = "${courseId}_${order.toString().padStart(3, '0')}",
                    courseId = courseId,
                    orderIndex = order,
                    title = stem,
                    videoPath = vid.absolutePath,
                    subArPath = if (arSubFile.exists()) arSubFile.absolutePath else null,
                    subEnPath = if (enSubFile.exists()) enSubFile.absolutePath else null,
                    durationMs = durationMs,
                    isCompleted = false,
                    lastPositionMs = 0L
                )
            )
        }
        mmr.release()

        val course = Course(
            id = courseId,
            title = effectiveTitle,
            folderPath = baseExtractedDir.absolutePath,
            dateAdded = System.currentTimeMillis(),
            totalLessons = lessons.size,
            completedLessons = 0
        )

        return Pair(course, lessons)
    }

    private fun extractLeadingNumber(name: String): Int {
        val match = Regex("^(\\d+)").find(name)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: 9999
    }

    private fun getFileNameFromUri(uri: Uri): String? {
        var name: String? = null
        if (uri.scheme == "content") {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx != -1) name = it.getString(idx)
                }
            }
        }
        if (name == null) {
            name = uri.path?.substringAfterLast('/')
        }
        return name
    }
}
