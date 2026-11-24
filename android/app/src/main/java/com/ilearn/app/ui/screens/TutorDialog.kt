package com.ilearn.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ilearn.app.data.Lesson
import com.ilearn.app.tutor.*
import kotlinx.coroutines.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TutorDialog(lesson: Lesson, positionMs: Long, profiles: ProviderProfileStore, onSeek: (Long) -> Unit, onSettings: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val sourceStore = remember { LessonSourceStore(context) }
    val scope = rememberCoroutineScope()
    val profile = profiles.active()
    var sources by remember(lesson.id) { mutableStateOf<List<SourcePassage>>(emptyList()) }
    var messages by remember(lesson.id, profile?.id) { mutableStateOf<List<TutorMessage>>(emptyList()) }
    var question by remember(lesson.id) { mutableStateOf("") }
    var language by remember { mutableStateOf("Arabic") }
    var general by remember(lesson.id) { mutableStateOf(false) }
    var answer by remember(lesson.id, profile?.id) { mutableStateOf("") }
    var evidence by remember(lesson.id, profile?.id) { mutableStateOf<List<SourcePassage>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var usage by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var work by remember { mutableStateOf<Job?>(null) }
    var busy by remember { mutableStateOf(false) }
    val historyScope = remember(profile?.id, general, sources) { "${profile?.id}|$general|" + sources.joinToString("|") { it.id } }
    LaunchedEffect(lesson.id) {
        try {
            sources = withContext(Dispatchers.IO) { sourceStore.sources(lesson) }
        } catch (e: Exception) { status = "Unable to read lesson sources/history: ${e.message}" }
        finally { loading = false }
    }
    LaunchedEffect(lesson.id, historyScope) {
        messages = emptyList()
        try { messages = withContext(Dispatchers.IO) { sourceStore.history(lesson, historyScope) } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { status = "Saved conversation could not be read. You can start a new conversation." }
    }
    DisposableEffect(lesson.id, profile?.id) { onDispose { work?.cancel() } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            loading = true
            try { withContext(Dispatchers.IO) { sourceStore.attach(lesson, uri) }; sources = withContext(Dispatchers.IO) { sourceStore.sources(lesson) }; messages = emptyList(); answer = ""; evidence = emptyList(); status = "Transcript attached" }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { status = e.message ?: "Unable to attach transcript" }
            finally { loading = false }
        }
    }
    fun send() {
        if (busy || loading) return
        val p = profile ?: return
        val q = question.trim(); if (q.isEmpty()) return
        val requestScope = historyScope
        busy = true
        work = scope.launch {
            status = ""; answer = ""; evidence = emptyList(); usage = ""
            try {
                val key = profiles.key(p) ?: error("Enter your API key in AI provider settings")
                require(general || sources.isNotEmpty()) { "Lesson content is unavailable. Attach a transcript or explicitly choose general help." }
                val snapshot = sources.toList()
                val history = messages.takeLast(6).dropWhile { it.role != "user" }.takeIf { it.sumOf { m -> m.text.length } < p.contextChars / 3 } ?: emptyList()
                val budget = p.contextChars - q.length - history.sumOf { it.text.length } - 3000
                require(budget >= 500) { "Question/history exceeds this profile's input budget" }
                val selected = if (general) emptyList() else TranscriptParser.retrieve(snapshot, q, positionMs, budget)
                require(general || selected.isNotEmpty()) { "No source passage fits this input budget. Increase it in settings." }
                val prompt = TutorGrounding.prompt(language, selected, general)
                require(prompt.length + q.length + history.sumOf { it.text.length } <= p.contextChars) { "Input budget is too small; increase it in settings" }
                val reply = ProviderTransport().generate(p, key, prompt, history + TutorMessage("user", q)) { partial ->
                    withContext(Dispatchers.Main) { answer = partial }
                }
                ensureActive()
                require(profiles.activeId == p.id) { "Provider changed; answer discarded" }
                val currentSources = withContext(Dispatchers.IO) { sourceStore.sources(lesson) }
                require(general || currentSources == snapshot) { "Lesson source changed; ask again" }
                evidence = if (general) emptyList() else TutorGrounding.references(reply.text, selected)
                if (!general && evidence.isEmpty()) status = "No source references returned. Verify this answer against the lesson."
                messages = (messages + TutorMessage("user", q) + TutorMessage("assistant", "${if (general) "[General help]" else if (evidence.isNotEmpty()) "[Lesson evidence]" else "[Unverified answer]"}\n${reply.text}")).takeLast(20)
                withContext(Dispatchers.IO) { sourceStore.saveHistory(lesson, requestScope, messages) }
                usage = reply.usage; question = ""; answer = ""
            } catch (e: CancellationException) { status = "Cancelled. The provider may still charge for work already performed."; throw e }
            catch (e: Exception) { status = e.message ?: "Tutor request failed" }
            finally { busy = false }
        }
    }
    Dialog(onDismissRequest = { work?.cancel(); onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(topBar = { TopAppBar(title = { Text("Lesson tutor • المعلّم") }, navigationIcon = { TextButton(onClick = { work?.cancel(); onClose() }) { Text("Close") } }, actions = { TextButton(onClick = { work?.cancel(); onClose(); onSettings() }) { Text("API settings") } }) }) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                      item { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(lesson.title, style = MaterialTheme.typography.titleMedium)
                    Text("${profile?.name ?: "Choose an API profile"} • ${if (general) "General help, not lesson-grounded" else "${sources.size} lesson passages"}")
                    Text("Questions, selected passages and recent conversation are sent to your chosen provider. API charges may apply.", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("English", "Arabic", "Bilingual").forEach { lang -> FilterChip(selected = language == lang, enabled = !busy, onClick = { language = lang }, label = { Text(lang) }) }
                    }
                    Row {
                        TextButton(enabled = !busy && !loading, onClick = { picker.launch(arrayOf("text/*", "application/x-subrip", "application/octet-stream")) }) { Text("Attach transcript") }
                        TextButton(enabled = !busy, onClick = { scope.launch { withContext(Dispatchers.IO) { sourceStore.deleteHistory(lesson, historyScope) }; messages = emptyList(); answer = ""; evidence = emptyList() } }) { Text("Clear chat") }
                    }
                    if (sources.isEmpty() && !loading) Text("Lesson content is unavailable. YouTube captions are not automatically accessible. Attach SRT/VTT/TXT to enable lesson answers.")
                    Row { Checkbox(general, { if (!busy) { general = it; messages = emptyList(); answer = ""; evidence = emptyList() } }, enabled = !busy); Text("General subject help (without lesson evidence)", Modifier.padding(top = 10.dp)) }
                      } }
                        items(messages) { message -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                            Text(if (message.role == "user") "You" else "Tutor", style = MaterialTheme.typography.labelMedium)
                            SelectionContainer { Text(message.text) }
                            if (message.role == "assistant") runCatching { TutorGrounding.references(message.text, sources) }.getOrDefault(emptyList()).forEach { source ->
                                if (source.startMs != null) TextButton(onClick = { onSeek(source.startMs) }) { Text("Source at ${formatTimeMs(source.startMs)}") }
                            }
                        } } }
                        if (busy || (status.isNotBlank() && answer.isNotBlank())) item { SelectionContainer { Text(answer) } }
                        items(evidence) { source -> TextButton(enabled = source.startMs != null, onClick = { source.startMs?.let(onSeek) }) { Text(if (source.startMs != null) "Source at ${formatTimeMs(source.startMs)}" else "Untimed source: ${source.text.take(120)}") } }
                        if (status.isNotBlank()) item { Text(status, color = MaterialTheme.colorScheme.error) }
                        if (usage.isNotBlank()) item { Text("Provider usage: $usage", style = MaterialTheme.typography.bodySmall) }
                    }
                    if (loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (!busy) Row {
                        TextButton(onClick = { question = "Explain the lesson around ${formatTimeMs(positionMs)} simply." }) { Text("Explain here") }
                        TextButton(onClick = { question = "Give me one practice question about this lesson. Wait for my answer before giving the solution." }) { Text("Quiz me") }
                    }
                    OutlinedTextField(question, { question = it.take(6000) }, label = { Text("Ask about this lesson…") }, enabled = !busy, modifier = Modifier.fillMaxWidth(), maxLines = 4)
                    Row {
                        Button(enabled = profile != null && !busy && !loading && question.isNotBlank() && (general || sources.isNotEmpty()), onClick = ::send) { Text("Send") }
                        if (busy) TextButton(onClick = { work?.cancel() }) { Text("Cancel") }
                    }
                }
            }
        }
    }
}
