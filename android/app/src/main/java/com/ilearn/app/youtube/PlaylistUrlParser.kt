package com.ilearn.app.youtube

import java.net.URI
import java.net.URLDecoder
import java.util.Locale

sealed class YouTubeImportTarget {
    data class Playlist(val playlistId: String) : YouTubeImportTarget()
    data class Video(val videoId: String) : YouTubeImportTarget()
    data class VideoAndPlaylist(val videoId: String, val playlistId: String) : YouTubeImportTarget()
}

object PlaylistUrlParser {
    private val ALLOWED_HOSTS = setOf(
        "youtube.com",
        "www.youtube.com",
        "m.youtube.com",
        "youtu.be",
        "music.youtube.com"
    )
    private val VIDEO_ID_REGEX = Regex("[A-Za-z0-9_-]{11}")
    private val PLAYLIST_ID_REGEX = Regex("[A-Za-z0-9_-]{5,150}")
    private val SHARE_URL_REGEX = Regex("https://[^\\s<>\"']+")

    fun parse(input: String): String? = runCatching {
        val uri = parseValidatedUri(input)
        val values = queryParams(uri)["list"].orEmpty()
        require(values.size == 1)
        URLDecoder.decode(values.single(), "UTF-8").takeIf { it.matches(PLAYLIST_ID_REGEX) }
    }.getOrNull()

    fun parseVideoId(input: String): String? = runCatching {
        val uri = parseValidatedUri(input)
        val host = uri.host?.lowercase(Locale.US).orEmpty()
        val pathSegments = uri.path.orEmpty().trim('/').split('/').filter { it.isNotEmpty() }
        val candidate = when {
            host == "youtu.be" -> pathSegments.firstOrNull()
            pathSegments.firstOrNull() in setOf("shorts", "live", "embed") -> pathSegments.getOrNull(1)
            else -> queryParams(uri)["v"]?.firstOrNull()
        }
        candidate?.let { URLDecoder.decode(it, "UTF-8") }?.takeIf { it.matches(VIDEO_ID_REGEX) }
    }.getOrNull()

    fun parseTarget(input: String): YouTubeImportTarget? {
        val listId = parse(input)
        val videoId = parseVideoId(input)
        val isAutoMixOrQueue = listId != null && (
            listId.startsWith("RD") || listId in setOf("WL", "LL", "LM")
            )
        return when {
            videoId != null && (listId == null || isAutoMixOrQueue) -> YouTubeImportTarget.Video(videoId)
            videoId != null && listId != null -> YouTubeImportTarget.VideoAndPlaylist(videoId, listId)
            listId != null -> YouTubeImportTarget.Playlist(listId)
            else -> null
        }
    }

    private fun parseValidatedUri(input: String): URI {
        val trimmed = input.trim()
        val rawUrl = if (' ' in trimmed || '\n' in trimmed) {
            SHARE_URL_REGEX.find(trimmed)?.value ?: trimmed
        } else {
            trimmed
        }
        val uri = URI(rawUrl)
        require(uri.scheme == "https" && uri.userInfo == null && uri.port == -1)
        require(uri.host?.lowercase(Locale.US) in ALLOWED_HOSTS)
        return uri
    }

    private fun queryParams(uri: URI): Map<String, List<String>> =
        uri.rawQuery.orEmpty()
            .split('&')
            .filter { it.isNotBlank() }
            .map { it.split('=', limit = 2) }
            .filter { it.size == 2 }
            .groupBy(keySelector = { it[0] }, valueTransform = { it[1] })
}
