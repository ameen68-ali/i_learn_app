package com.ilearn.app.ui.screens

import android.view.WindowManager
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ilearn.app.tutor.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

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
    var isError by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }

    fun profile() = ProviderProfile(
        id = editing ?: java.util.UUID.randomUUID().toString(),
        name = name.trim(),
        format = format,
        endpoint = endpoint.trim().trimEnd('/'),
        model = model.trim(),
        maxOutputTokens = output.toIntOrNull() ?: 0,
        contextChars = context.toIntOrNull() ?: 0,
        stream = streaming,
        apiVersion = version,
        modernChatTokens = modernTokens
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("إعدادات المعلّم • AI Providers", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            text = "ربط مزوّد الذكاء الاصطناعي الخاص بك",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.size(48.dp).semantics { contentDescription = "رجوع" }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "اختر صيغة الـ API والرابط والنموذج. تُرسل الأسئلة والمقاطع المختارة مباشرة لمزوّدك، ولا تُضمن المفاتيح في النسخ الاحتياطية.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp
                    )
                }
            }

            store.loadWarning?.let { warning ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(warning, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                }
            }

            if (profiles.isNotEmpty()) {
                Text("المزوّدون المحفوظون", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                profiles.forEach { p ->
                    val isActive = store.activeId == p.id
                    ElevatedCard(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surfaceContainerLow
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(p.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                if (isActive) {
                                    Surface(color = Color(0xFF10B981).copy(alpha = 0.16f), shape = RoundedCornerShape(8.dp)) {
                                        Text(
                                            text = "✓ مفعّل",
                                            color = Color(0xFF10B981),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                            }
                            Text("${p.format.name.replace('_', ' ')} • ${p.model}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            Text(p.endpoint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (!isActive) {
                                    FilledTonalIconButton(
                                        onClick = { store.activeId = p.id; profiles = store.profiles(); isError = false; status = "Selected ${p.name}" },
                                        modifier = Modifier
                                            .size(40.dp)
                                            .semantics { contentDescription = "Use profile ${p.name}" }
                                    ) {
                                        Icon(Icons.Default.CheckCircleOutline, contentDescription = null, modifier = Modifier.size(20.dp))
                                    }
                                    Spacer(modifier = Modifier.width(4.dp))
                                }
                                IconButton(
                                    onClick = {
                                        editing = p.id
                                        name = p.name
                                        format = p.format
                                        endpoint = p.endpoint
                                        model = p.model
                                        output = p.maxOutputTokens.toString()
                                        context = p.contextChars.toString()
                                        version = p.apiVersion
                                        streaming = p.stream
                                        modernTokens = p.modernChatTokens
                                        key = ""
                                        rememberKey = false
                                        isError = false
                                        status = "أعد إدخال المفتاح لتحديث هذا المزوّد"
                                    },
                                    modifier = Modifier
                                        .size(40.dp)
                                        .semantics { contentDescription = "Edit / replace key for ${p.name}" }
                                ) {
                                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(20.dp))
                                }
                                IconButton(
                                    onClick = {
                                        store.delete(p.id)
                                        profiles = store.profiles()
                                        if (editing == p.id) {
                                            editing = null
                                            key = ""
                                        }
                                    },
                                    modifier = Modifier
                                        .size(40.dp)
                                        .semantics { contentDescription = "Delete profile ${p.name}" }
                                ) {
                                    Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                }
            }

            ElevatedCard(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (editing == null) "إضافة مزوّد جديد" else "تحديث المزوّد",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (editing != null) {
                            TextButton(onClick = { editing = null; name = ""; model = ""; key = ""; status = ""; isError = false }) {
                                Text("ملف جديد")
                            }
                        }
                    }

                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(100) },
                        label = { Text("Profile name") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ApiFormat.values().forEach { f ->
                            FilterChip(
                                selected = format == f,
                                onClick = {
                                    format = f
                                    key = ""
                                    endpoint = when (f) {
                                        ApiFormat.ANTHROPIC -> "https://api.anthropic.com/v1"
                                        ApiFormat.GEMINI -> "https://generativelanguage.googleapis.com/v1beta"
                                        else -> "https://api.openai.com/v1"
                                    }
                                },
                                label = { Text(f.name.replace('_', ' ')) },
                                shape = RoundedCornerShape(10.dp)
                            )
                        }
                    }

                    OutlinedTextField(
                        value = endpoint,
                        onValueChange = { endpoint = it.take(500); key = "" },
                        label = { Text("HTTPS API base URL") },
                        supportingText = { Text("Changing the destination clears the entered key. Verify who receives it.") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it.take(150) },
                        label = { Text("Model ID") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = key,
                        onValueChange = { key = it.take(4096) },
                        label = { Text("API key • never included in backups") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = output,
                            onValueChange = { output = it.filter(Char::isDigit).take(5) },
                            label = { Text("Max tokens") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = context,
                            onValueChange = { context = it.filter(Char::isDigit).take(6) },
                            label = { Text("Context chars") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    if (format == ApiFormat.ANTHROPIC) {
                        OutlinedTextField(
                            value = version,
                            onValueChange = { version = it.take(10) },
                            label = { Text("Anthropic API version") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = streaming, onCheckedChange = { streaming = it })
                        Text("Streaming (disable for endpoints without SSE)", style = MaterialTheme.typography.bodySmall)
                    }
                    if (format == ApiFormat.OPENAI_CHAT) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = modernTokens, onCheckedChange = { modernTokens = it })
                            Text("Use max_completion_tokens for newer Chat models", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = rememberKey, onCheckedChange = { rememberKey = it })
                        Text("Remember key encrypted on this device", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        text = "الافتراضي حفظ المفتاح للجلسة فقط؛ التذكّر يشفّر المفتاح بـ Android Keystore ويُستبعد من النسخ الاحتياطية.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Button(
                            enabled = !testing,
                            onClick = {
                                try {
                                    val p = profile()
                                    store.save(p, key, rememberKey)
                                    profiles = store.profiles()
                                    editing = null
                                    key = ""
                                    isError = false
                                    status = "تم حفظ المزوّد وتفعيله بنجاح"
                                } catch (e: Exception) {
                                    isError = true
                                    status = e.message ?: "Unable to save profile"
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                        ) { Text("Save & use", fontWeight = FontWeight.Bold) }

                        OutlinedIconButton(
                            enabled = !testing,
                            onClick = {
                                scope.launch {
                                    testing = true
                                    isError = false
                                    status = "جاري اختبار الاتصال…"
                                    try {
                                        ProviderTransport().generate(profile(), key, "Reply with OK only.", listOf(TutorMessage("user", "Connection test"))) {}
                                        isError = false
                                        status = "نجح الاتصال بالمزوّد ✓"
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        isError = true
                                        status = e.message ?: "Connection failed"
                                    } finally {
                                        testing = false
                                    }
                                }
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .semantics { contentDescription = "Test connection" }
                        ) {
                            Icon(Icons.Default.NetworkCheck, contentDescription = null)
                        }
                    }

                    if (status.isNotBlank()) {
                        Surface(
                            color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = status,
                                color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
