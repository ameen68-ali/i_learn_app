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
    private val TRAILING_RANGE_TIMESTAMP = Regex("^[-–—~to\\s]+(?:\\d{1,2}:)?\\d{1,2}:[0-5]\\d\\b")
    private val BIDI_AND_INVISIBLE = Regex("[\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069\\uFEFF]")
    private val CHAPTER_KEY_REGEX = Regex("\"(chapterRenderer|macroMarkersListItemRenderer)\"\\s*:")

    fun normalizeDigitsAndBidi(raw: String): String {
        val stripped = raw.replace(BIDI_AND_INVISIBLE, "")
        val sb = StringBuilder(stripped.length)
        for (ch in stripped) {
            val mapped = when (ch) {
                in '٠'..'٩' -> ('0' + (ch - '٠'))
                in '۰'..'۹' -> ('0' + (ch - '۰'))
                else -> ch
            }
            sb.append(mapped)
        }
        return sb.toString()
    }

    fun parseChaptersFromDescription(
        description: String,
        videoDurationMs: Long
    ): List<VideoChapter> {
        if (description.isBlank()) return emptyList()
        val rawMarkers = mutableListOf<Pair<Long, String>>()
        val normalizedDesc = normalizeDigitsAndBidi(description.replace("\r\n", "\n"))

        for (rawLine in normalizedDesc.lines()) {
            val line = rawLine.trim()
            if (line.isBlank()) continue
            val match = TIMESTAMP_TOKEN.find(line) ?: continue

            val before = line.substring(0, match.range.first).trim()
            val afterRaw = line.substring(match.range.last + 1).trim()
            val after = afterRaw.replace(TRAILING_RANGE_TIMESTAMP, "").trim()

            // Accept lines where the timestamp sits near the beginning (allowing emojis, numbers, or short chapter labels)
            // OR at the end of a short chapter title like "Introduction (00:00)".
            val prefixLooksLikeMarker = before.length <= 32 && before.split(Regex("\\s+")).size <= 5
            val suffixIsEndOfLine = after.isEmpty() || after.matches(Regex("[\\])\\s.,-]{1,6}"))
            if (!prefixLooksLikeMarker && !suffixIsEndOfLine) continue

            val startMs = parseTimestampMs(match) ?: continue
            if (videoDurationMs > 0L && startMs >= videoDurationMs - 2_000L) continue

            val prefixClean = before.replace(Regex("^(?:[\\d]+[.)\\-]|[-•*📌🔹⏱️🎬]+)\\s*"), "").trim()
            val rawTitle = when {
                after.isNotBlank() && !suffixIsEndOfLine -> {
                    val usefulPrefix = prefixClean
                        .takeIf { it.isNotBlank() && !it.matches(Regex("^(?:chapter|part|lesson|lecture|الفصل|الدرس)\\s*\\d*[:\\-–]?$", RegexOption.IGNORE_CASE)) }
                    if (usefulPrefix != null) "$usefulPrefix — $after" else after
                }
                else -> before.replace(Regex("^[\\d]+\\s*[.)\\-]\\s*"), "")
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
        val chapterRendererMarkers = mutableListOf<Pair<Long, String>>()
        val macroMarkers = mutableListOf<Pair<Long, String>>()

        for (match in CHAPTER_KEY_REGEX.findAll(html)) {
            val rendererType = match.groupValues[1]
            val objStart = match.range.last + 1
            val jsonSlice = extractBalancedJsonObject(html, objStart) ?: continue
            runCatching {
                val obj = JSONObject(jsonSlice)
                val title = extractRunsOrSimpleText(obj.optJSONObject("title")).trim().take(180)
                if (title.isBlank()) return@runCatching
                if (rendererType == "chapterRenderer") {
                    val startMs = obj.optLong("timeRangeStartMillis", -1L)
                    if (startMs >= 0L) chapterRendererMarkers.add(startMs to title)
                } else {
                    val startSecs = obj.optJSONObject("onTap")
                        ?.optJSONObject("watchEndpoint")
                        ?.optLong("startTimeSeconds", -1L) ?: -1L
                    val startMs = if (startSecs >= 0L) {
                        startSecs * 1000L
                    } else {
                        val timeText = extractRunsOrSimpleText(obj.optJSONObject("timeDescription"))
                        TIMESTAMP_TOKEN.find(timeText)?.let { parseTimestampMs(it) } ?: -1L
                    }
                    if (startMs >= 0L) macroMarkers.add(startMs to title)
                }
            }
        }
        val preferred = if (chapterRendererMarkers.size >= 2) chapterRendererMarkers else macroMarkers
        return normalizeMarkers(preferred, videoDurationMs)
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
        while (i < source.length && i - start <= 16_384) {
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
