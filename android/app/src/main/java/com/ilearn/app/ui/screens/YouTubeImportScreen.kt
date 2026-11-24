package com.ilearn.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ilearn.app.data.CourseRepository
import com.ilearn.app.youtube.*
import kotlinx.coroutines.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YouTubeImportScreen(repository: CourseRepository, initialPlaylist: String? = null, onBack: () -> Unit, onImported: (String) -> Unit) {
    ProtectCredentialScreen()
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    var url by remember { mutableStateOf(initialPlaylist?.let { "https://www.youtube.com/playlist?list=$it" } ?: "") }
    var apiKey by remember { mutableStateOf(com.ilearn.app.BuildConfig.YOUTUBE_API_KEY) }
    var preview by remember { mutableStateOf<PlaylistPreview?>(null) }
    var status by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }
    var busy by remember { mutableStateOf(false) }
    Scaffold(topBar = { TopAppBar(title = { Text("YouTube course • كورس يوتيوب") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Add an online playlist as a course. Playback uses YouTube and requires internet. Your notes and progress stay on this device.")
            OutlinedTextField(url, { url = it.take(2000); preview = null }, label = { Text("YouTube playlist link") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(apiKey, { apiKey = it.take(4096); preview = null }, label = { Text("YouTube Data API key") }, enabled = !busy, visualTransformation = PasswordVisualTransformation(), keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password, autoCorrect = false), modifier = Modifier.fillMaxWidth())
            Text("The YouTube metadata key is separate from your tutor API key. A developer-configured key can be supplied when building the app; a key entered here lasts only for this screen.", style = MaterialTheme.typography.bodySmall)
            Button(enabled = !busy && apiKey.isNotBlank() && PlaylistUrlParser.parse(url) != null, onClick = {
                job = scope.launch {
                    busy = true
                    status = "Loading playlist…"; preview = null
                    try { preview = YouTubeMetadataClient(context).preview(url, apiKey); status = "Review before adding or refreshing" }
                    catch (e: CancellationException) { status = "Cancelled"; throw e }
                    catch (e: Exception) { status = e.message ?: "Unable to load playlist" }
                    finally { busy = false }
                }
            }) { Text("Preview playlist") }
            if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); TextButton(onClick = { job?.cancel() }) { Text("Cancel") } }
            if (status.isNotBlank()) Text(status)
            preview?.let { result ->
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(result.course.title, style = MaterialTheme.typography.titleLarge)
                    Text("${result.lessons.size} entries • ${result.lessons.count { it.isAvailable }} available")
                    result.lessons.take(12).forEach { Text("${it.orderIndex}. ${it.title}") }
                    if (result.lessons.size > 12) Text("… ${result.lessons.size - 12} more entries")
                    Button(enabled = !busy, onClick = {
                        job = scope.launch {
                            busy = true
                            try {
                                val existing = repository.courses.value.firstOrNull { it.id == result.course.id }
                                repository.dbHelper.insertCourseWithLessons(result.course.copy(dateAdded = existing?.dateAdded ?: result.course.dateAdded), result.lessons)
                                repository.refreshData(); onImported(result.course.id)
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) { status = "Unable to save course: ${e.message}" }
                            finally { busy = false }
                        }
                    }) { Text("Add / refresh course") }
                } }
            }
        }
    }
}
