package com.ilearn.app.importer

import android.content.Context
import android.net.Uri
import com.ilearn.app.data.Course
import com.ilearn.app.data.Lesson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext

/**
 * Exports a fully downloaded course (videos + Arabic/English subtitles) as a standard .zip
 * archive matching [CourseArchiveImporter]'s expected layout so another user can import it offline.
 */
object CourseArchiveExporter {

    fun canExportOfflineCourse(lessons: List<Lesson>): Boolean =
        lessons.isNotEmpty() && lessons.all { it.hasOfflineVideo }

    fun safeArchiveName(raw: String): String {
        val cleaned = raw
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ")
            .trim()
            .replace(Regex("\\s+"), "_")
            .trim('_')
            .take(64)
        return cleaned.ifBlank { "iLearn_Course" }
    }

    fun lessonExportStem(orderIndex: Int, title: String, usedStems: MutableSet<String>): String {
        val withoutPrefix = title.replace(Regex("^\\d+\\s*[-–._]\\s*"), "").trim().ifBlank { title }
        val cleanTitle = safeArchiveName(withoutPrefix).take(50).ifBlank { "lesson" }
        val base = String.format(Locale.US, "%03d_%s", orderIndex.coerceAtLeast(1), cleanTitle)
        var candidate = base
        var suffix = 2
        while (!usedStems.add(candidate)) {
            candidate = "${base}_$suffix"
            suffix++
        }
        return candidate
    }

    suspend fun exportCourseToZip(
        context: Context,
        course: Course,
        lessons: List<Lesson>,
        targetUri: Uri,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ) = withContext(Dispatchers.IO) {
        val out = context.contentResolver.openOutputStream(targetUri)
            ?: error("تعذّر فتح ملف الوجهة لحفظ الأرشيف")
        out.use { stream ->
            writeCourseZip(course, lessons, stream, onProgress) {
                coroutineContext.ensureActive()
            }
        }
    }

    fun writeCourseZip(
        course: Course,
        lessons: List<Lesson>,
        outputStream: OutputStream,
        onProgress: (Float, String) -> Unit = { _, _ -> },
        checkCancelled: () -> Unit = {}
    ) {
        require(lessons.isNotEmpty()) { "لا توجد دروس لتصديرها" }
        val sortedLessons = lessons.sortedBy { it.orderIndex }
        val rootFolder = safeArchiveName(course.title)
        val usedStems = mutableSetOf<String>()
        val total = sortedLessons.size.coerceAtLeast(1)

        ZipOutputStream(BufferedOutputStream(outputStream, 65536)).use { zos ->
            sortedLessons.forEachIndexed { idx, lesson ->
                checkCancelled()
                val videoFile = File(lesson.videoPath)
                require(videoFile.isFile && videoFile.length() > 0L) {
                    "الدرس ${lesson.orderIndex} غير محمّل أوفلاين بالكامل"
                }

                val stem = lessonExportStem(lesson.orderIndex, lesson.title, usedStems)
                val videoExt = videoFile.extension.lowercase(Locale.US).ifBlank { "mp4" }
                onProgress((idx.toFloat() / total).coerceIn(0f, 0.99f), lesson.title)

                // Store already-compressed video without wasting CPU on re-deflating MP4 bytes.
                zos.setLevel(Deflater.NO_COMPRESSION)
                writeFileEntry(zos, "$rootFolder/compressed_videos/$stem.$videoExt", videoFile, checkCancelled)

                zos.setLevel(Deflater.DEFAULT_COMPRESSION)
                lesson.subArPath?.let { path ->
                    val sub = File(path)
                    if (sub.isFile && sub.length() > 0L) {
                        val subExt = sub.extension.lowercase(Locale.US).ifBlank { "vtt" }
                        writeFileEntry(zos, "$rootFolder/subtitles_ar/$stem.$subExt", sub, checkCancelled)
                    }
                }
                lesson.subEnPath?.let { path ->
                    val sub = File(path)
                    if (sub.isFile && sub.length() > 0L) {
                        val subExt = sub.extension.lowercase(Locale.US).ifBlank { "vtt" }
                        writeFileEntry(zos, "$rootFolder/subtitles_en/$stem.$subExt", sub, checkCancelled)
                    }
                }
            }
            zos.finish()
        }
        onProgress(1f, "تم تجهيز الأرشيف")
    }

    private fun writeFileEntry(
        zos: ZipOutputStream,
        entryName: String,
        sourceFile: File,
        checkCancelled: () -> Unit
    ) {
        zos.putNextEntry(ZipEntry(entryName))
        BufferedInputStream(FileInputStream(sourceFile), 65536).use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                checkCancelled()
                val read = input.read(buffer)
                if (read < 0) break
                zos.write(buffer, 0, read)
            }
        }
        zos.closeEntry()
    }
}
