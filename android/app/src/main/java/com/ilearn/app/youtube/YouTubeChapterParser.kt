package com.ilearn.app.youtube

import com.ilearn.app.tutor.TranscriptParser
import org.json.JSONArray
import org.json.JSONObject

data class VideoChapter(
    val title: String,
    val startMs: Long,
    val endMs: Long
)

/**
 * Parses chapter timestamps from YouTube descriptions or watch-page markers, and slices full-video
 * WebVTT captions cleanly into chapter-relative (`00:00..clipDur`) WebVTT tracks.
 */
object YouTubeChapterParser {

    private const val MIN_CHAPTER_MS = 15_000L
    private val TIMESTAMP_TOKEN = Regex("(?:(\\d{1,2}):)?(\\d{1,2}):([0-5]\\d)")

    fun parseChaptersFromDescription(
        description: String,
        videoDurationMs: Long
    ): List<VideoChapter> {
        if (description.isBlank()) return emptyList()
        val rawMarkers = mutableListOf<Pair<Long, String>>()

        for (rawLine in description.replace("\r\n", "\n").lines()) {
            val line = rawLine.trim()
            if (line.isBlank()) continue
            val match = TIMESTAMP_TOKEN.find(line) ?: continue
            // Only treat as chapter line if timestamp sits near the start or end after stripping bullets.
            val before = line.substring(0, match.range.first).trim()
            val after = line.substring(match.range.last + 1).trim()
            val startsNearLeft = before.isEmpty() || before.matches(Regex("[\\d.\\-)(\\[\\]•*\\s]{1,12}"))
            val endsNearRight = after.isEmpty() || after.matches(Regex("[\\])\\s-]{1,6}"))
            if (!startsNearLeft && !endsNearRight) continue

            val startMs = parseTimestampMs(match) ?: continue
            if (videoDurationMs > 0L && startMs >= videoDurationMs - 2_000L) continue

            val rawTitle = if (before.length > 12 || after.isNotBlank() && !endsNearRight) {
                (if (!before.matches(Regex("[\\d.\\-)(\\[\\]•*\\s]*"))) "$before " else "") + after
            } else {
                before.replace(Regex("^[\\d]+\\s*[.\\-)]\\s*"), "")
            }
            val cleanedTitle = rawTitle
                .replace(Regex("^[\\s\\-–—:|•*\\]\\)]+"), "")
                .replace(Regex("[\\s\\-–—:|\\[\\(]+$"), "")
                .trim()
                .take(180)
            rawMarkers.add(startMs to cleanedTitle)
        }

        return normalizeMarkers(rawMarkers, videoDurationMs)
    }

    fun parseChaptersFromWatchHtml(
        html: String,
        videoDurationMs: Long
    ): List<VideoChapter> {
        if (html.isBlank()) return emptyList()
        val markers = mutableListOf<Pair<Long, String>>()
        val chapterKey = "\"chapterRenderer\":"
        var cursor = 0
        while (true) {
            val idx = html.indexOf(chapterKey, cursor)
            if (idx < 0) break
            val objStart = idx + chapterKey.length
            val jsonSlice = extractBalancedJsonObject(html, objStart)
            if (jsonSlice != null) {
                runCatching {
                    val obj = JSONObject(jsonSlice)
                    val startMs = obj.optLong("timeRangeStartMillis", -1L)
                    val title = extractRunsOrSimpleText(obj.optJSONObject("title")).trim().take(180)
                    if (startMs >= 0L && title.isNotBlank()) {
                        markers.add(startMs to title)
                    }
                }
                cursor = objStart + jsonSlice.length
            } else {
                cursor = objStart + 1
            }
        }
        return normalizeMarkers(markers, videoDurationMs)
    }

    fun normalizeMarkers(
        rawMarkers: List<Pair<Long, String>>,
        videoDurationMs: Long
    ): List<VideoChapter> {
        if (rawMarkers.isEmpty()) return emptyList()

        // Keep strictly ascending markers before filtering out micro-transitions (<15s apart).
        val ascending = mutableListOf<Pair<Long, String>>()
        for ((ts, title) in rawMarkers) {
            if (videoDurationMs > 0L && ts >= videoDurationMs - 2_000L) continue
            val lastTs = ascending.lastOrNull()?.first ?: -1L
            if (ts > lastTs) {
                ascending.add(ts to title)
            }
        }
        if (ascending.size < 2) return emptyList()

        val anchored = when {
            ascending.first().first == 0L -> ascending
            ascending.first().first < MIN_CHAPTER_MS ->
                listOf(0L to ascending.first().second) + ascending.drop(1)
            else -> listOf(0L to "المقدمة • Intro") + ascending
        }

        val spaced = mutableListOf<Pair<Long, String>>()
        for ((idx, item) in anchored.withIndex()) {
            if (spaced.isEmpty()) {
                spaced.add(item)
            } else if (item.first - spaced.last().first >= MIN_CHAPTER_MS || (spaced.size == 1 && idx == anchored.lastIndex)) {
                spaced.add(item)
            }
        }
        if (spaced.size < 2) return emptyList()

        val resolvedEnd = if (videoDurationMs > 0L) videoDurationMs else Long.MAX_VALUE
        val chapters = mutableListOf<VideoChapter>()
        for (i in spaced.indices) {
            val (startMs, rawTitle) = spaced[i]
            val nextStart = if (i + 1 < spaced.size) spaced[i + 1].first else resolvedEnd
            val endMs = if (nextStart == Long.MAX_VALUE) startMs + 600_000L else nextStart.coerceAtLeast(startMs)
            val title = rawTitle.ifBlank { "الفصل ${i + 1}" }
            chapters.add(VideoChapter(title = title, startMs = startMs, endMs = endMs))
        }
        return chapters
    }

    /**
     * Extracts captions overlapping `[clipStartMs, clipEndMs)` and rebases every cue's timestamps
     * to `0L`-relative chapter coordinates (`cue - clipStartMs`).
     */
    fun sliceAndRebaseVtt(
        fullVtt: String,
        clipStartMs: Long,
        clipEndMs: Long?
    ): String? {
        if (fullVtt.isBlank()) return null
        val safeStart = clipStartMs.coerceAtLeast(0L)
        val safeEnd = clipEndMs?.takeIf { it > safeStart }
        if (safeStart == 0L && safeEnd == null) return fullVtt

        val cues = runCatching { TranscriptParser.parseLines(fullVtt, "vtt") }.getOrDefault(emptyList())
        if (cues.isEmpty()) return null

        val sb = StringBuilder("WEBVTT\n\n")
        var count = 0
        for (cue in cues) {
            if (cue.endMs <= safeStart) continue
            if (safeEnd != null && cue.startMs >= safeEnd) break

            val relStart = (cue.startMs - safeStart).coerceAtLeast(0L)
            val clampedEnd = safeEnd?.let { minOf(cue.endMs, it) } ?: cue.endMs
            val relEnd = (clampedEnd - safeStart).coerceAtLeast(0L)
            if (relEnd <= relStart + 50L) continue

            sb.append(YouTubeTranscriptClient.formatVttTimestamp(relStart))
                .append(" --> ")
                .append(YouTubeTranscriptClient.formatVttTimestamp(relEnd))
                .append('\n')
                .append(cue.text)
                .append("\n\n")
            count++
        }
        return if (count > 0) sb.toString() else null
    }

    private fun parseTimestampMs(match: MatchResult): Long? {
        val hStr = match.groupValues[1]
        val mStr = match.groupValues[2]
        val sStr = match.groupValues[3]
        val h = hStr.toLongOrNull() ?: 0L
        val m = mStr.toLongOrNull() ?: return null
        val s = sStr.toLongOrNull() ?: return null
        if (hStr.isNotEmpty() && m >= 60L) return null
        if (s >= 60L) return null
        return ((h * 3600L) + (m * 60L) + s) * 1000L
    }

    private fun extractRunsOrSimpleText(obj: JSONObject?): String {
        if (obj == null) return ""
        val simple = obj.optString("simpleText").trim()
        if (simple.isNotBlank()) return simple
        val runs: JSONArray = obj.optJSONArray("runs") ?: return ""
        return (0 until runs.length())
            .joinToString("") { runs.optJSONObject(it)?.optString("text").orEmpty() }
    }

    private fun extractBalancedJsonObject(source: String, startIndex: Int): String? {
        var i = startIndex
        while (i < source.length && source[i].isWhitespace()) i++
        if (i >= source.length || source[i] != '{') return null
        var depth = 0
        var inString = false
        var escaped = false
        val start = i
        while (i < source.length && i - start <= 4096) {
            val c = source[i]
            if (escaped) {
                escaped = false
            } else if (c == '\\' && inString) {
                escaped = true
            } else if (c == '"') {
                inString = !inString
            } else if (!inString) {
                if (c == '{') depth++
                else if (c == '}') {
                    depth--
                    if (depth == 0) return source.substring(start, i + 1)
                }
            }
            i++
        }
        return null
    }
}
