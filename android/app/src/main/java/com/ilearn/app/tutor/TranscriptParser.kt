package com.ilearn.app.tutor

import java.security.MessageDigest

data class SourcePassage(val id: String, val text: String, val startMs: Long? = null, val endMs: Long? = null)

object TranscriptParser {
    private val timestamp = Regex("(?:(\\d{1,3}):)?(\\d{2}):(\\d{2})[.,](\\d{3})")
    fun parse(content: String, extension: String): List<SourcePassage> {
        require(content.length <= 1_000_000) { "Transcript exceeds 1 MB of text" }
        val normalized = content.replace("\r\n", "\n").replace('\r', '\n').removePrefix("\uFEFF")
        val revision = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
        if (extension.lowercase() == "txt") return normalized.split(Regex("\n\\s*\n"))
            .flatMap { it.chunked(1000) }.filter { it.isNotBlank() }.mapIndexed { i, t -> SourcePassage("$revision-$i", t.trim()) }
        require(extension.lowercase() in setOf("srt", "vtt")) { "Use SRT, VTT or TXT" }
        val cues = normalized.split(Regex("\n\\s*\n")).mapIndexedNotNull { blockIndex, block ->
            val lines = block.lines()
            val timeIndex = lines.indexOfFirst { "-->" in it }
            if (timeIndex < 0) return@mapIndexedNotNull null
            val times = timestamp.findAll(lines[timeIndex]).toList()
            if (times.size != 2) return@mapIndexedNotNull null
            fun ms(m: MatchResult): Long? {
                val h = m.groupValues[1].toLongOrNull() ?: 0
                val min = m.groupValues[2].toLong(); val sec = m.groupValues[3].toLong()
                if (min >= 60 || sec >= 60) return null
                return ((h * 60 + min) * 60 + sec) * 1000 + m.groupValues[4].toLong()
            }
            val start = ms(times[0]) ?: return@mapIndexedNotNull null
            val end = ms(times[1]) ?: return@mapIndexedNotNull null
            val text = lines.drop(timeIndex + 1).joinToString("\n").replace(Regex("<[^>]*>"), "").trim()
            if (end <= start || text.isEmpty()) null else SourcePassage("$revision-$blockIndex-$start", text, start, end)
        }.distinctBy { it.id }
        val passages = mutableListOf<SourcePassage>()
        cues.forEach { cue ->
            val previous = passages.lastOrNull()
            if (previous?.startMs != null && previous.endMs != null && cue.startMs != null && cue.endMs != null &&
                cue.startMs >= previous.endMs && cue.startMs - previous.endMs <= 5000 &&
                cue.endMs - previous.startMs <= 60000 && previous.text.length + cue.text.length < 1000) {
                passages[passages.lastIndex] = previous.copy(text = previous.text + "\n" + cue.text, endMs = cue.endMs)
            } else passages.add(cue)
        }
        return passages
    }

    fun retrieve(passages: List<SourcePassage>, question: String, positionMs: Long, maxChars: Int): List<SourcePassage> {
        val stopWords = setOf("explain", "lesson", "simply", "give", "what", "does", "the", "how", "why", "here", "this", "around", "about", "and", "for", "with", "from")
        val terms = question.lowercase().split(Regex("[^\\p{L}\\p{N}]+" )).filter { it.length > 1 && it !in stopWords }.toSet()
        val ranked = passages.indices.sortedByDescending { index ->
            val p = passages[index]
            terms.count { p.text.lowercase().contains(it) } * 10 +
                if (p.startMs != null && kotlin.math.abs(p.startMs - positionMs) < 30_000) 5 else 0
        }
        if (ranked.isEmpty() || passages[ranked.first()].text.length > maxChars) return emptyList()
        var remaining = maxChars
        val selected = linkedSetOf<Int>()
        fun add(index: Int) {
            if (index in passages.indices && index !in selected && selected.size < 24 && passages[index].text.length <= remaining) {
                remaining -= passages[index].text.length; selected.add(index)
            }
        }
        for (index in ranked) {
            if (selected.size >= 24) break
            add(index)
            if (index in selected) { add(index - 1); add(index + 1) }
        }
        return selected.sorted().map { passages[it] }
    }
}
