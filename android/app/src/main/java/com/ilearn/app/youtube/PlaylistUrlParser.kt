package com.ilearn.app.youtube

import java.net.URI
import java.net.URLDecoder
import java.util.Locale

sealed class YouTubeImportTarget {
    data class Playlist(val playlistId: String) : YouTubeImportTarget()
    data class Video(val videoId: String) : YouTubeImportTarget()
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
            else -> queryParams(uri)["v"]?.singleOrNull()
        }
        candidate?.let { URLDecoder.decode(it, "UTF-8") }?.takeIf { it.matches(VIDEO_ID_REGEX) }
    }.getOrNull()

    fun parseTarget(input: String): YouTubeImportTarget? {
        parse(input)?.let { return YouTubeImportTarget.Playlist(it) }
        parseVideoId(input)?.let { return YouTubeImportTarget.Video(it) }
        return null
    }

    private fun parseValidatedUri(input: String): URI {
        val uri = URI(input.trim())
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
