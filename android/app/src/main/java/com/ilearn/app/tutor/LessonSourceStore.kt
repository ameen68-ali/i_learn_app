package com.ilearn.app.tutor

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.ilearn.app.data.Lesson
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

class LessonSourceStore(private val context: Context) {
    private fun file(lesson: Lesson, suffix: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(lesson.id.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(File(context.noBackupFilesDir, "tutor").apply { mkdirs() }, "$hash.$suffix")
    }
    fun sources(lesson: Lesson): List<SourcePassage> {
        val attached = file(lesson, "source.json")
        read(attached)?.let { val json = JSONObject(it); return TranscriptParser.parse(json.getString("text"), json.getString("extension")) }
        return listOfNotNull(lesson.subArPath, lesson.subEnPath).mapNotNull { path ->
            File(path).takeIf { it.isFile && it.length() <= 1_000_000 }
        }.flatMap { TranscriptParser.parse(it.readText(), it.extension) }.distinctBy { it.id }
    }
    fun attach(lesson: Lesson, uri: Uri) {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else "" }.orEmpty()
        val ext = name.substringAfterLast('.', "").lowercase()
        require(ext in setOf("txt", "srt", "vtt")) { "Choose a TXT, SRT or VTT transcript" }
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) { val n = input.read(buffer); if (n < 0) break; require(out.size() + n <= 1_000_000) { "Transcript exceeds 1 MB" }; out.write(buffer, 0, n) }
            out.toByteArray()
        } ?: error("Cannot open transcript")
        val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        require(TranscriptParser.parse(text, ext).isNotEmpty()) { "No usable transcript passages found" }
        write(file(lesson, "source.json"), JSONObject().put("extension", ext).put("text", text).toString())
    }
    private fun historyFile(lesson: Lesson, scope: String): File {
        val scopeHash = MessageDigest.getInstance("SHA-256").digest(scope.toByteArray()).joinToString("") { "%02x".format(it) }
        return file(lesson, "history-$scopeHash.json")
    }
    fun history(lesson: Lesson, scope: String): List<TutorMessage> {
        val text = read(historyFile(lesson, scope)) ?: return emptyList()
        val a = JSONArray(text); return (0 until a.length()).map { a.getJSONObject(it).let { j -> TutorMessage(j.getString("role"), j.getString("text")) } }
    }
    fun saveHistory(lesson: Lesson, scope: String, messages: List<TutorMessage>) {
        var chars = 0; val kept = messages.takeLast(20).reversed().takeWhile { chars += it.text.length; chars <= 240000 }.reversed().dropWhile { it.role != "user" }
        write(historyFile(lesson, scope), JSONArray().apply { kept.forEach { put(JSONObject().put("role", it.role).put("text", it.text)) } }.toString())
    }
    fun deleteHistory(lesson: Lesson, scope: String) { android.util.AtomicFile(historyFile(lesson, scope)).delete() }
    fun removeSource(lesson: Lesson) { android.util.AtomicFile(file(lesson, "source.json")).delete() }
    fun deleteLesson(lesson: Lesson) {
        deleteLessons(listOf(lesson))
    }
    fun deleteLessons(lessons: List<Lesson>) {
        if (lessons.isEmpty()) return
        val prefixes = lessons.map { file(it, "source.json").name.substringBefore('.') }.toSet()
        file(lessons.first(), "source.json").parentFile?.listFiles()?.filter { it.isFile && it.name.substringBefore('.') in prefixes }
            ?.forEach { check(it.delete()) { "Unable to remove private lesson data" } }
    }
    private fun read(target: File): String? {
        if (!target.exists() && !File(target.path + ".bak").exists()) return null
        return android.util.AtomicFile(target).readFully().toString(Charsets.UTF_8)
    }
    private fun write(target: File, text: String) {
        val atomic = android.util.AtomicFile(target); val out = atomic.startWrite()
        try { out.write(text.toByteArray()); atomic.finishWrite(out) } catch (e: Exception) { atomic.failWrite(out); throw e }
    }
}
