package com.ilearn.app.youtube

import com.ilearn.app.data.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class PlaylistPreview(
    val course: Course,
    val lessons: List<Lesson>,
    val detectedChaptersCount: Int = 0,
    val unsplitFallbackLesson: Lesson? = null
)

class YouTubeMetadataClient(private val context: android.content.Context) {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .callTimeout(30, TimeUnit.SECONDS).build()

    suspend fun preview(url: String, apiKey: String): PlaylistPreview = withContext(Dispatchers.IO) {
        val target = PlaylistUrlParser.parseTarget(url)
            ?: error("أدخل رابط قائمة تشغيل أو فيديو YouTube صحيح بصيغة HTTPS")
        com.ilearn.app.net.requireApiKey(apiKey)
        suspend fun get(resource: String, params: Map<String, String>): JSONObject = coroutineScope {
            val endpoint = okhttp3.HttpUrl.Builder().scheme("https").host("www.googleapis.com").addPathSegments("youtube/v3/$resource")
            params.forEach { (k, v) -> endpoint.addQueryParameter(k, v) }
            val pm = context.packageManager
            val signatures = if (android.os.Build.VERSION.SDK_INT >= 28) {
                pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners
            } else pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SIGNATURES).signatures
            val fingerprint = signatures?.firstOrNull()?.let { signature ->
                java.security.MessageDigest.getInstance("SHA-1").digest(signature.toByteArray()).joinToString("") { "%02X".format(it) }
            } ?: error("Application signing identity is unavailable")
            val call = client.newCall(Request.Builder().url(endpoint.build()).header("X-Goog-Api-Key", apiKey)
                .header("X-Android-Package", context.packageName).header("X-Android-Cert", fingerprint).build())
            val guard = launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { call.cancel() } }
            try {
                call.execute().use { response ->
                    check(response.isSuccessful) { "YouTube request failed (HTTP ${response.code}); check API key, quota and link access" }
                    val source = response.body?.source() ?: error("Empty YouTube response")
                    val buffer = okio.Buffer(); var size = 0L
                    while (!source.exhausted()) { ensureActive(); size += source.read(buffer, 8192); require(size <= 2_000_000) }
                    JSONObject(buffer.readUtf8())
                }
            } finally { guard.cancelAndJoin() }
        }

        when (target) {
            is YouTubeImportTarget.Video -> {
                val videoId = target.videoId
                val response = get("videos", mapOf("part" to "snippet,contentDetails,status", "id" to videoId))
                val items = response.getJSONArray("items")
                check(items.length() == 1) { "الفيديو غير متاح أو خاص على YouTube" }
                val item = items.getJSONObject(0)
                val snippet = item.getJSONObject("snippet")
                val title = snippet.getString("title")
                require(title.length <= 1000)
                val description = snippet.optString("description")
                val durationMs = parseDuration(item.getJSONObject("contentDetails").getString("duration"))
                val embeddable = item.getJSONObject("status").optBoolean("embeddable", false)

                val course = Course(
                    id = "youtube:video_$videoId",
                    title = title,
                    folderPath = "",
                    sourceType = SourceType.YOUTUBE,
                    remoteId = "video_$videoId",
                    lastSyncedAt = System.currentTimeMillis()
                )
                val unsplit = Lesson(
                    id = "${course.id}:full",
                    courseId = course.id,
                    orderIndex = 1,
                    title = title,
                    videoPath = "",
                    durationMs = durationMs,
                    sourceType = SourceType.YOUTUBE,
                    remoteId = videoId,
                    isAvailable = embeddable
                )

                val descChapters = YouTubeChapterParser.parseChaptersFromDescription(description, durationMs)
                val chapters = if (descChapters.size >= 2) {
                    descChapters
                } else {
                    runCatching { YouTubeTranscriptClient().fetchAutoChapters(videoId, durationMs) }
                        .getOrDefault(emptyList())
                }

                if (chapters.size >= 2) {
                    val chapterLessons = chapters.mapIndexed { idx, ch ->
                        Lesson(
                            id = "${course.id}:ch_${ch.startMs}",
                            courseId = course.id,
                            orderIndex = idx + 1,
                            title = ch.title,
                            videoPath = "",
                            durationMs = (ch.endMs - ch.startMs).coerceAtLeast(0L),
                            sourceType = SourceType.YOUTUBE,
                            remoteId = videoId,
                            isAvailable = embeddable,
                            clipStartMs = ch.startMs,
                            clipEndMs = ch.endMs
                        )
                    }
                    PlaylistPreview(
                        course = course,
                        lessons = chapterLessons,
                        detectedChaptersCount = chapterLessons.size,
                        unsplitFallbackLesson = unsplit
                    )
                } else {
                    PlaylistPreview(
                        course = course,
                        lessons = listOf(unsplit),
                        detectedChaptersCount = 0,
                        unsplitFallbackLesson = unsplit
                    )
                }
            }

            is YouTubeImportTarget.Playlist -> {
                val playlist = target.playlistId
                val metadata = get("playlists", mapOf("part" to "snippet", "id" to playlist)).getJSONArray("items")
                check(metadata.length() == 1) { "Playlist is private, unavailable or empty" }
                val title = metadata.getJSONObject(0).getJSONObject("snippet").getString("title")
                require(title.length <= 1000)
                val course = Course("youtube:$playlist", title, "",
                    sourceType = SourceType.YOUTUBE, remoteId = playlist, lastSyncedAt = System.currentTimeMillis())
                val lessons = mutableListOf<Lesson>(); var page = ""; val pages = mutableSetOf<String>()
                do {
                    check(pages.add(page)) { "YouTube returned a repeated page" }
                    val result = get("playlistItems", mapOf("part" to "snippet,contentDetails", "playlistId" to playlist, "maxResults" to "50", "pageToken" to page))
                    val items = result.getJSONArray("items")
                    for (i in 0 until items.length()) {
                        val item = items.getJSONObject(i); val snippet = item.getJSONObject("snippet")
                        val itemId = item.getString("id"); require(itemId.matches(Regex("[A-Za-z0-9_-]{1,200}")))
                        val video = item.optJSONObject("contentDetails")?.optString("videoId").orEmpty()
                        val lessonTitle = snippet.getString("title")
                        require(lessonTitle.length <= 1000)
                        lessons.add(Lesson("${course.id}:$itemId", course.id, snippet.getInt("position") + 1, lessonTitle, "",
                            sourceType = SourceType.YOUTUBE, remoteId = video.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) },
                            isAvailable = video.length == 11 && lessonTitle !in setOf("Deleted video", "Private video")))
                    }
                    require(lessons.size <= 10000) { "Playlist exceeds 10,000 items" }
                    page = result.optString("nextPageToken")
                } while (page.isNotBlank())
                require(lessons.isNotEmpty()) { "Playlist has no lessons" }
                val details = mutableMapOf<String, Pair<Long, Boolean>>()
                lessons.mapNotNull { it.remoteId }.distinct().chunked(50).forEach { batch ->
                    val videos = get("videos", mapOf("part" to "contentDetails,status", "id" to batch.joinToString(","))).getJSONArray("items")
                    for (i in 0 until videos.length()) {
                        val item = videos.getJSONObject(i)
                        details[item.getString("id")] = parseDuration(item.getJSONObject("contentDetails").getString("duration")) to item.getJSONObject("status").optBoolean("embeddable", false)
                    }
                }
                PlaylistPreview(course, lessons.distinctBy { it.id }.map { lesson ->
                    val info = details[lesson.remoteId]
                    lesson.copy(durationMs = info?.first ?: 0, isAvailable = lesson.isAvailable && info?.second == true)
                })
            }
        }
    }

    companion object {
        fun parseDuration(value: String): Long {
            val match = Regex("PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?").matchEntire(value) ?: return 0
            val (h, m, s) = match.destructured
            return runCatching { ((h.toLongOrNull() ?: 0) * 3600 + (m.toLongOrNull() ?: 0) * 60 + (s.toLongOrNull() ?: 0)) * 1000 }
                .getOrDefault(0).coerceIn(0, 604800000)
        }
    }
}
