package com.ilearn.app.youtube

import android.content.Context
import android.media.MediaMetadataRetriever
import com.ilearn.app.data.CourseDatabaseHelper
import com.ilearn.app.data.Lesson
import com.ilearn.app.data.SourceType
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class LessonDownloadState(
    val lessonId: String,
    val progress: Float = 0f,
    val stage: String = "",
    val etaSeconds: Long = -1L
)

class YouTubeOfflineDownloader(
    context: Context,
    private val dbHelper: CourseDatabaseHelper,
    private val transcriptClient: YouTubeTranscriptClient = YouTubeTranscriptClient()
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initMutex = Mutex()
    @Volatile
    private var initialized = false
    private val activeJobs = ConcurrentHashMap<String, Job>()

    private val _downloads = MutableStateFlow<Map<String, LessonDownloadState>>(emptyMap())
    val downloads: StateFlow<Map<String, LessonDownloadState>> = _downloads.asStateFlow()

    private val _errors = MutableStateFlow<Map<String, String>>(emptyMap())
    val errors: StateFlow<Map<String, String>> = _errors.asStateFlow()

    fun dismissError(lessonId: String) {
        _errors.update { it - lessonId }
    }

    fun startDownload(lesson: Lesson) {
        val videoId = lesson.remoteId
        if (lesson.sourceType != SourceType.YOUTUBE || videoId.isNullOrBlank() || !videoId.matches(Regex("[A-Za-z0-9_-]{11}"))) {
            _errors.update { it + (lesson.id to "هذا الدرس لا يحتوي على معرف فيديو صالح") }
            return
        }
        if (activeJobs.containsKey(lesson.id)) return

        dismissError(lesson.id)
        _downloads.update { it + (lesson.id to LessonDownloadState(lesson.id, 0f, "تجهيز التحميل (360p)…")) }

        val job = scope.launch {
            val coursesRoot = coursesRootDir(appContext)
            val courseDir = courseDirFor(coursesRoot, lesson.courseId)
            val stem = lessonFileStem(lesson.orderIndex, lesson.id)
            val tempDir = File(courseDir, ".tmp_$stem").apply {
                deleteRecursively()
                mkdirs()
            }
            val procId = processIdFor(lesson.id)
            try {
                val transcriptsDeferred = async {
                    transcriptClient.downloadPreferredTracksAsVtt(videoId)
                }

                ensureInitialized()
                updateProgress(lesson.id, 0.02f, "جاري تحميل الفيديو (360p)…", -1L)

                val downloadedVideo = download360pWithRetry(lesson.id, videoId, tempDir, procId)
                val videosDir = File(courseDir, "compressed_videos").apply { mkdirs() }
                val ext = downloadedVideo.extension.lowercase(Locale.US).ifBlank { "mp4" }
                val finalVideo = File(videosDir, "$stem.$ext")
                if (finalVideo.exists()) finalVideo.delete()
                if (!downloadedVideo.renameTo(finalVideo)) {
                    downloadedVideo.copyTo(finalVideo, overwrite = true)
                    downloadedVideo.delete()
                }
                check(finalVideo.isFile && finalVideo.length() > 0L) { "الملف المحمل غير صالح" }

                updateProgress(lesson.id, 0.96f, "جاري حفظ الترجمة…", 0L)
                val (arVtt, enVtt) = transcriptsDeferred.await()

                val arFile = arVtt?.let { vtt ->
                    File(courseDir, "subtitles_ar").apply { mkdirs() }
                        .let { dir -> File(dir, "$stem.vtt").apply { writeText(vtt, Charsets.UTF_8) } }
                }
                val enFile = enVtt?.let { vtt ->
                    File(courseDir, "subtitles_en").apply { mkdirs() }
                        .let { dir -> File(dir, "$stem.vtt").apply { writeText(vtt, Charsets.UTF_8) } }
                }

                val durationMs = if (lesson.durationMs > 0L) lesson.durationMs else probeDurationMs(finalVideo)
                dbHelper.setLessonOfflineMedia(
                    lessonId = lesson.id,
                    videoPath = finalVideo.absolutePath,
                    subArPath = arFile?.absolutePath ?: lesson.subArPath,
                    subEnPath = enFile?.absolutePath ?: lesson.subEnPath,
                    durationMs = durationMs
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: YoutubeDL.CanceledException) {
                // Cancelled explicitly by user
            } catch (e: Exception) {
                val msg = e.message?.take(220).orEmpty().ifBlank { "تعذّر تحميل الدرس" }
                _errors.update { it + (lesson.id to "فشل التحميل: $msg") }
            } finally {
                activeJobs.remove(lesson.id)
                _downloads.update { it - lesson.id }
                tempDir.deleteRecursively()
            }
        }
        activeJobs[lesson.id] = job
    }

    fun cancelDownload(lessonId: String) {
        scope.launch {
            runCatching { YoutubeDL.getInstance().destroyProcessById(processIdFor(lessonId)) }
        }
        activeJobs.remove(lessonId)?.cancel()
        _downloads.update { it - lessonId }
    }

    suspend fun removeOfflineVideo(lesson: Lesson): Boolean = withContext(Dispatchers.IO) {
        if (activeJobs.containsKey(lesson.id)) return@withContext false
        val root = coursesRootDir(appContext).canonicalFile
        runCatching {
            if (lesson.videoPath.isNotBlank()) {
                val videoFile = File(lesson.videoPath).canonicalFile
                if (videoFile.path.startsWith(root.path + File.separator) && videoFile.exists()) {
                    check(videoFile.delete()) { "Unable to delete offline video" }
                }
            }
            dbHelper.setLessonOfflineMedia(
                lessonId = lesson.id,
                videoPath = "",
                subArPath = lesson.subArPath,
                subEnPath = lesson.subEnPath
            )
            true
        }.getOrDefault(false)
    }

    private suspend fun ensureInitialized() {
        if (initialized) return
        initMutex.withLock {
            if (!initialized) {
                YoutubeDL.getInstance().init(appContext)
                FFmpeg.getInstance().init(appContext)
                initialized = true
            }
        }
    }

    private fun download360pWithRetry(
        lessonId: String,
        videoId: String,
        tempDir: File,
        procId: String
    ): File {
        val url = "https://www.youtube.com/watch?v=$videoId"
        return try {
            executeDownload(lessonId, url, tempDir, procId)
        } catch (e: YoutubeDL.CanceledException) {
            throw e
        } catch (firstError: Exception) {
            updateProgress(lessonId, 0.01f, "تحديث محرّك التحميل وإعادة المحاولة…", -1L)
            val updated = runCatching {
                YoutubeDL.getInstance().updateYoutubeDL(appContext) == YoutubeDL.UpdateStatus.DONE
            }.getOrDefault(false)
            if (!updated) throw firstError
            tempDir.listFiles()?.forEach { it.deleteRecursively() }
            executeDownload(lessonId, url, tempDir, procId)
        }
    }

    private fun executeDownload(
        lessonId: String,
        url: String,
        tempDir: File,
        procId: String
    ): File {
        val request = YoutubeDLRequest(url).apply {
            addOption("--no-playlist")
            addOption("--no-mtime")
            addOption("-f", FORMAT_360P)
            addOption("--merge-output-format", "mp4")
            addOption("-o", "${tempDir.absolutePath}/video.%(ext)s")
        }
        YoutubeDL.getInstance().execute(request, procId) { progress, eta, _ ->
            if (progress >= 0f) {
                val normalized = (progress / 100f).coerceIn(0f, 0.95f)
                updateProgress(lessonId, normalized, "جاري التحميل (360p)…", eta)
            }
        }
        return tempDir.listFiles()
            ?.filter { file ->
                file.isFile &&
                    file.length() > 0L &&
                    !file.name.endsWith(".part", ignoreCase = true) &&
                    !file.name.endsWith(".ytdl", ignoreCase = true) &&
                    !file.name.endsWith(".temp", ignoreCase = true)
            }
            ?.maxByOrNull { it.length() }
            ?: error("لم يتم العثور على ملف الفيديو بعد التحميل")
    }

    private fun updateProgress(lessonId: String, progress: Float, stage: String, eta: Long) {
        _downloads.update { current ->
            if (lessonId !in current && progress <= 0f) current
            else current + (lessonId to LessonDownloadState(lessonId, progress, stage, eta))
        }
    }

    private fun probeDurationMs(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    companion object {
        const val FORMAT_360P =
            "bv*[height<=360][vcodec^=avc][ext=mp4]+ba[ext=m4a]/b[height<=360][ext=mp4]/bv*[height<=360]+ba/b[height<=360]"

        fun coursesRootDir(context: Context): File =
            (context.getExternalFilesDir(null)?.let { File(it, "courses") }
                ?: File(context.filesDir, "courses")).apply { mkdirs() }

        fun safeSlug(value: String): String =
            value.replace(Regex("[^A-Za-z0-9_-]"), "_").take(48).ifBlank { "course" }

        fun courseDirFor(coursesRoot: File, courseId: String): File =
            File(coursesRoot, "yt_${safeSlug(courseId)}")

        fun lessonFileStem(orderIndex: Int, lessonId: String): String {
            val hash = MessageDigest.getInstance("SHA-256")
                .digest(lessonId.toByteArray(Charsets.UTF_8))
                .take(6)
                .joinToString("") { "%02x".format(it) }
            return String.format(Locale.US, "%03d_%s", orderIndex.coerceAtLeast(0), hash)
        }

        fun processIdFor(lessonId: String): String =
            "ytdl_" + MessageDigest.getInstance("SHA-256")
                .digest(lessonId.toByteArray(Charsets.UTF_8))
                .take(8)
                .joinToString("") { "%02x".format(it) }
    }
}
