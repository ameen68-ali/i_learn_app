package com.ilearn.app.youtube

import java.net.URI
import java.net.URLDecoder

object PlaylistUrlParser {
    fun parse(input: String): String? = runCatching {
        val uri = URI(input.trim())
        require(uri.scheme == "https" && uri.userInfo == null && uri.port == -1)
        require(uri.host?.lowercase() in setOf("youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be", "music.youtube.com"))
        val values = uri.rawQuery.orEmpty().split('&').map { it.split('=', limit = 2) }
            .filter { it.size == 2 && it[0] == "list" }
        require(values.size == 1)
        URLDecoder.decode(values.single()[1], "UTF-8").takeIf { it.matches(Regex("[A-Za-z0-9_-]{5,150}")) }
    }.getOrNull()
}
