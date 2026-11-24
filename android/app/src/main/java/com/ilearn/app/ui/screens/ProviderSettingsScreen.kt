package com.ilearn.app.ui.screens

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ilearn.app.tutor.*
import kotlinx.coroutines.*

@Composable
fun ProtectCredentialScreen() {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity) {
        val wasSecure = activity?.window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_SECURE) != 0
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (!wasSecure) activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderSettingsScreen(store: ProviderProfileStore, onBack: () -> Unit) {
    ProtectCredentialScreen()
    val scope = rememberCoroutineScope()
    var profiles by remember { mutableStateOf(store.profiles()) }
    var editing by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var format by remember { mutableStateOf(ApiFormat.OPENAI_CHAT) }
    var endpoint by remember { mutableStateOf("https://api.openai.com/v1") }
    var model by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("2048") }
    var context by remember { mutableStateOf("24000") }
    var version by remember { mutableStateOf("2023-06-01") }
    var streaming by remember { mutableStateOf(true) }
    var modernTokens by remember { mutableStateOf(false) }
    var rememberKey by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    fun profile() = ProviderProfile(id = editing ?: java.util.UUID.randomUUID().toString(), name = name.trim(), format = format,
        endpoint = endpoint.trim().trimEnd('/'), model = model.trim(), maxOutputTokens = output.toIntOrNull() ?: 0,
        contextChars = context.toIntOrNull() ?: 0, stream = streaming, apiVersion = version, modernChatTokens = modernTokens)
    Scaffold(topBar = { TopAppBar(title = { Text("AI providers • مزودو الذكاء الاصطناعي") }, navigationIcon = { TextButton(onClick = onBack) { Text("رجوع") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Choose your API format, endpoint and model. Questions and selected lesson text are sent directly to that service. Your API account may be charged. Permanent keys on a mobile device have security limitations.")
            store.loadWarning?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            profiles.forEach { p ->
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                    Text("${if (store.activeId == p.id) "✓ " else ""}${p.name} • ${p.model}")
                    Text(p.endpoint, style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = { store.activeId = p.id; profiles = store.profiles(); status = "Selected ${p.name}" }) { Text("Use") }
                        TextButton(onClick = { editing = p.id; name = p.name; format = p.format; endpoint = p.endpoint; model = p.model; output = p.maxOutputTokens.toString(); context = p.contextChars.toString(); version = p.apiVersion; streaming = p.stream; modernTokens = p.modernChatTokens; key = ""; rememberKey = false; status = "Re-enter the key to update this destination" }) { Text("Edit / replace key") }
                        TextButton(onClick = { store.delete(p.id); profiles = store.profiles(); if (editing == p.id) { editing = null; key = "" } }) { Text("Delete") }
                    }
                } }
            }
            HorizontalDivider()
            Text(if (editing == null) "Add API profile" else "Update API profile", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(name, { name = it.take(100) }, label = { Text("Profile name") }, modifier = Modifier.fillMaxWidth())
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ApiFormat.values().forEach { f -> FilterChip(selected = format == f, onClick = {
                    format = f; key = ""; endpoint = when(f) {
                        ApiFormat.ANTHROPIC -> "https://api.anthropic.com/v1"
                        ApiFormat.GEMINI -> "https://generativelanguage.googleapis.com/v1beta"
                        else -> "https://api.openai.com/v1"
                    }
                }, label = { Text(f.name.replace('_', ' ')) }) }
            }
            OutlinedTextField(endpoint, { endpoint = it.take(500); key = "" }, label = { Text("HTTPS API base URL") }, supportingText = { Text("Changing the destination clears the entered key. Verify who receives it.") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(model, { model = it.take(150) }, label = { Text("Model ID") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(key, { key = it.take(4096) }, label = { Text("API key • never included in backups") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password, autoCorrect = false), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(output, { output = it.filter(Char::isDigit).take(5) }, label = { Text("Maximum output tokens (128–8192)") })
            OutlinedTextField(context, { context = it.filter(Char::isDigit).take(6) }, label = { Text("Input character budget (1000–120000)") })
            if (format == ApiFormat.ANTHROPIC) OutlinedTextField(version, { version = it.take(10) }, label = { Text("Anthropic API version") })
            Row { Checkbox(streaming, { streaming = it }); Text("Streaming (disable for endpoints without SSE)", Modifier.padding(top = 12.dp)) }
            if (format == ApiFormat.OPENAI_CHAT) Row { Checkbox(modernTokens, { modernTokens = it }); Text("Use max_completion_tokens for models requiring the newer Chat output parameter", Modifier.padding(top = 12.dp)) }
            Row { Checkbox(rememberKey, { rememberKey = it }); Text("Remember key encrypted on this device", Modifier.padding(top = 12.dp)) }
            Text("Session-only is the default. Remembered credentials use Android Keystore encryption and are excluded from backups. Test connection sends a small request and may incur a charge.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !testing, onClick = {
                    try { val p = profile(); store.save(p, key, rememberKey); profiles = store.profiles(); editing = null; key = ""; status = "Profile saved and selected" } catch (e: Exception) { status = e.message ?: "Unable to save profile" }
                }) { Text("Save & use") }
                OutlinedButton(enabled = !testing, onClick = {
                    scope.launch {
                        testing = true; status = "Testing…"
                        try { ProviderTransport().generate(profile(), key, "Reply with OK only.", listOf(TutorMessage("user", "Connection test"))) {}; status = "Connection succeeded" }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { status = e.message ?: "Connection failed" }
                        finally { testing = false }
                    }
                }) { Text("Test") }
            }
            if (status.isNotBlank()) Text(status)
            TextButton(onClick = { editing = null; name = ""; model = ""; key = ""; status = "" }) { Text("New profile") }
        }
    }
}
