package com.ilearn.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ilearn.app.data.Lesson
import com.ilearn.app.data.SourceType
import com.ilearn.app.tutor.*
import com.ilearn.app.youtube.YouTubeCaptionTrack
import com.ilearn.app.youtube.YouTubeTranscriptClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TutorDialog(
    lesson: Lesson,
    positionMs: Long,
    profiles: ProviderProfileStore,
    onSeek: (Long) -> Unit,
    onSettings: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val sourceStore = remember(context) { LessonSourceStore(context) }
    val transcriptClient = remember { YouTubeTranscriptClient() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val profile = profiles.active()

    var sources by remember(lesson.id) { mutableStateOf<List<SourcePassage>>(emptyList()) }
    var sourceLabel by remember(lesson.id) { mutableStateOf<String?>(null) }
    var availableTracks by remember(lesson.id) { mutableStateOf<List<YouTubeCaptionTrack>>(emptyList()) }
    var messages by remember(lesson.id, profile?.id) { mutableStateOf<List<TutorMessage>>(emptyList()) }
    var question by remember(lesson.id) { mutableStateOf("") }
    var general by remember(lesson.id) { mutableStateOf(false) }
    var answer by remember(lesson.id, profile?.id) { mutableStateOf("") }
    var status by remember(lesson.id) { mutableStateOf("") }
    var infoBanner by remember(lesson.id) { mutableStateOf("") }
    var usage by remember(lesson.id) { mutableStateOf("") }
    var loading by remember(lesson.id) { mutableStateOf(true) }
    var downloadingTranscript by remember(lesson.id) { mutableStateOf(false) }
    var showOptionsSheet by remember { mutableStateOf(false) }
    var showTrackPicker by remember { mutableStateOf(false) }
    var work by remember { mutableStateOf<Job?>(null) }
    var busy by remember { mutableStateOf(false) }

    val historyScope = remember(profile?.id, general, sources) {
        "${profile?.id}|$general|" + sources.joinToString("|") { it.id }
    }

    suspend fun downloadYouTubeTrack(track: YouTubeCaptionTrack? = null) {
        val videoId = lesson.remoteId ?: return
        downloadingTranscript = true
        status = ""
        try {
            val tracks = availableTracks.ifEmpty {
                withContext(Dispatchers.IO) { transcriptClient.fetchTracks(videoId) }
            }
            availableTracks = tracks
            val chosen = track ?: YouTubeTranscriptClient.choosePreferredTrack(tracks)
            if (chosen == null) {
                infoBanner = "لا توجد ترجمة متاحة لهذا الفيديو على يوتيوب. يمكنك إرفاق ملف نصي أو تفعيل المساعدة العامة."
                return
            }
            val vtt = withContext(Dispatchers.IO) { transcriptClient.downloadAsVtt(chosen) }
            val label = "YouTube: ${chosen.name}"
            withContext(Dispatchers.IO) { sourceStore.saveTranscript(lesson, vtt, "vtt", label) }
            sources = withContext(Dispatchers.IO) { sourceStore.sources(lesson) }
            sourceLabel = label
            answer = ""
            infoBanner = "تم تحميل نص الفيديو (${chosen.name})"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            infoBanner = "تعذّر تحميل نص يوتيوب: ${e.message ?: "تحقق من الاتصال"}"
        } finally {
            downloadingTranscript = false
        }
    }

    LaunchedEffect(lesson.id) {
        loading = true
        try {
            val loaded = withContext(Dispatchers.IO) { sourceStore.sources(lesson) }
            sources = loaded
            sourceLabel = withContext(Dispatchers.IO) { sourceStore.sourceLabel(lesson) }
            if (loaded.isEmpty() && lesson.sourceType == SourceType.YOUTUBE && !lesson.remoteId.isNullOrBlank()) {
                downloadYouTubeTrack()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            status = "Unable to read lesson sources: ${e.message}"
        } finally {
            loading = false
        }
    }

    LaunchedEffect(lesson.id, historyScope) {
        messages = emptyList()
        try {
            messages = withContext(Dispatchers.IO) { sourceStore.history(lesson, historyScope) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            status = "Saved conversation could not be read. You can start a new chat."
        }
    }

    LaunchedEffect(messages.size, busy, answer.length) {
        val total = messages.size + if (busy) 1 else 0
        if (total > 0) {
            runCatching { listState.animateScrollToItem(total - 1) }
        }
    }

    DisposableEffect(lesson.id, profile?.id) {
        onDispose { work?.cancel() }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                loading = true
                status = ""
                try {
                    withContext(Dispatchers.IO) { sourceStore.attach(lesson, uri) }
                    sources = withContext(Dispatchers.IO) { sourceStore.sources(lesson) }
                    sourceLabel = withContext(Dispatchers.IO) { sourceStore.sourceLabel(lesson) }
                    messages = emptyList()
                    answer = ""
                    infoBanner = "تم إرفاق ملف النص بنجاح"
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    status = e.message ?: "Unable to attach transcript"
                } finally {
                    loading = false
                }
            }
        }
    }

    val exportTranscriptLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/vtt")
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                try {
                    val exported = withContext(Dispatchers.IO) { sourceStore.exportSource(lesson) }
                    if (exported == null) {
                        status = "No transcript available to export"
                    } else {
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openOutputStream(uri)?.use { out ->
                                out.write(exported.second.toByteArray(Charsets.UTF_8))
                                out.flush()
                            } ?: error("Cannot open destination file")
                        }
                        infoBanner = "تم حفظ ملف النص بنجاح"
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    status = e.message ?: "Unable to export transcript"
                }
            }
        }
    }

    fun send(overrideQuestion: String? = null) {
        if (busy || loading || downloadingTranscript) return
        val p = profile ?: return
        val q = (overrideQuestion ?: question).trim()
        if (q.isEmpty()) return
        val requestScope = historyScope
        busy = true
        work = scope.launch {
            status = ""
            answer = ""
            usage = ""
            try {
                val key = profiles.key(p) ?: error("أدخل مفتاح API في إعدادات المزوّد • Enter your API key in AI settings")
                require(general || sources.isNotEmpty()) {
                    "نص الدرس غير متوفر. حمّل نص يوتيوب أو أرفق ملف ترجمة أو فعّل المساعدة العامة."
                }
                val snapshot = sources.toList()
                val history = messages.takeLast(6).dropWhile { it.role != "user" }
                    .takeIf { it.sumOf { m -> m.text.length } < p.contextChars / 3 } ?: emptyList()
                val budget = p.contextChars - q.length - history.sumOf { it.text.length } - 3000
                require(budget >= 500) { "السؤال أو سجل المحادثة يتجاوز حد الأحرف المسموح في الإعدادات." }
                val selected = if (general) emptyList() else TranscriptParser.retrieve(snapshot, q, positionMs, budget)
                require(general || selected.isNotEmpty()) { "حجم المقاطع أكبر من حد الإدخال. ارفع الحد في إعدادات المزوّد." }
                val prompt = TutorGrounding.promptForMessage(q, selected, general)
                require(prompt.length + q.length + history.sumOf { it.text.length } <= p.contextChars) {
                    "حد الأحرف صغير جداً؛ قم بزيادته من الإعدادات."
                }
                val reply = ProviderTransport().generate(p, key, prompt, history + TutorMessage("user", q)) { partial ->
                    withContext(Dispatchers.Main) { answer = partial }
                }
                ensureActive()
                require(profiles.activeId == p.id) { "تم تغيير المزوّد؛ أعد إرسال سؤالك." }
                val currentSources = withContext(Dispatchers.IO) { sourceStore.sources(lesson) }
                require(general || currentSources == snapshot) { "تغيّر مصدر الدرس؛ أعد السؤال." }
                val cited = if (general) emptyList() else TutorGrounding.references(reply.text, selected)
                if (!general && cited.isEmpty()) {
                    infoBanner = "لم تُذكر إحالات زمنية؛ راجع الإجابة مع الدرس."
                }
                val tag = when {
                    general -> "[General help]"
                    cited.isNotEmpty() -> "[Lesson evidence]"
                    else -> "[Unverified answer]"
                }
                messages = (messages + TutorMessage("user", q) + TutorMessage("assistant", "$tag\n${reply.text}")).takeLast(20)
                withContext(Dispatchers.IO) { sourceStore.saveHistory(lesson, requestScope, messages) }
                usage = reply.usage
                if (overrideQuestion == null) question = ""
                answer = ""
            } catch (e: CancellationException) {
                status = "تم إلغاء الطلب."
                throw e
            } catch (e: Exception) {
                status = e.message ?: "تعذّر الحصول على رد من المعلّم"
            } finally {
                busy = false
            }
        }
    }

    val canChat = profile != null && !loading && !downloadingTranscript && (general || sources.isNotEmpty())

    Dialog(
        onDismissRequest = { work?.cancel(); onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                // Clean Chat Top Bar
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = "المعلّم الذكي • AI Tutor",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = lesson.title,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = { work?.cancel(); onClose() },
                            modifier = Modifier.size(48.dp).semantics { contentDescription = "Close" }
                        ) {
                            Icon(Icons.Default.Close, contentDescription = null)
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { showOptionsSheet = true },
                            modifier = Modifier.size(48.dp).semantics { contentDescription = "خيارات المعلّم والنص" }
                        ) {
                            Icon(Icons.Default.Tune, contentDescription = null)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                )

                // Slim Context Status Strip
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            val badgeColor = when {
                                downloadingTranscript || loading -> MaterialTheme.colorScheme.primary
                                general -> Color(0xFFF59E0B)
                                sources.isNotEmpty() -> Color(0xFF10B981)
                                else -> MaterialTheme.colorScheme.outline
                            }
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(badgeColor)
                            )
                            val sourceText = when {
                                downloadingTranscript -> "جاري تحميل نص يوتيوب…"
                                loading -> "جاري تجهيز الدرس…"
                                general -> "مساعدة عامة (بدون نص)"
                                sources.isNotEmpty() -> "${sourceLabel ?: "نص الدرس"} (${sources.size})"
                                else -> "لا يوجد نص للدرس"
                            }
                            Text(
                                text = sourceText,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        TextButton(
                            onClick = {
                                if (profile == null) {
                                    work?.cancel()
                                    onClose()
                                    onSettings()
                                } else {
                                    showOptionsSheet = true
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text(
                                text = profile?.name ?: "إعداد المزوّد",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                if (loading || downloadingTranscript || busy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                // Main Chat Area
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (messages.isEmpty() && !busy) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(20.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SmartToy,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(34.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(14.dp))

                            when {
                                profile == null -> {
                                    Text(
                                        text = "أضف مزوّد ذكاء اصطناعي للبدء",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "اختر نموذجك (OpenAI / Anthropic / Gemini أو متوافق) وأدخل مفتاحك من الإعدادات.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(14.dp))
                                    Button(
                                        onClick = { work?.cancel(); onClose(); onSettings() },
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("إعدادات المزوّد • API settings")
                                    }
                                }

                                downloadingTranscript -> {
                                    Text(
                                        text = "جاري تحميل نص الفيديو من يوتيوب…",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "يتم جلب الترجمة التوقيتية ليرتبط شرح المعلّم باللحظة الحالية من الدرس.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center
                                    )
                                }

                                sources.isEmpty() && !general && !loading -> {
                                    Text(
                                        text = "نص الدرس غير متوفر بعد",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "Lesson transcript is unavailable. Attach SRT/VTT/TXT or enable general help.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(14.dp))
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        if (lesson.sourceType == SourceType.YOUTUBE && !lesson.remoteId.isNullOrBlank()) {
                                            Button(
                                                onClick = { scope.launch { downloadYouTubeTrack() } },
                                                shape = RoundedCornerShape(12.dp)
                                            ) {
                                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text("تحميل نص يوتيوب")
                                            }
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            FilledTonalButton(
                                                onClick = { picker.launch(arrayOf("text/*", "application/x-subrip", "application/octet-stream")) },
                                                shape = RoundedCornerShape(12.dp)
                                            ) {
                                                Icon(Icons.Default.AttachFile, contentDescription = null, modifier = Modifier.size(18.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("إرفاق ملف")
                                            }
                                            OutlinedButton(
                                                onClick = { general = true },
                                                shape = RoundedCornerShape(12.dp)
                                            ) {
                                                Text("مساعدة عامة")
                                            }
                                        }
                                    }
                                }

                                else -> {
                                    Text(
                                        text = "اسأل المعلّم بأي لغة",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "سيجيبك المعلّم تلقائياً بنفس اللغة التي تكتب بها، مع إحالات مباشرة لتوقيت الفيديو.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(messages) { message ->
                                ChatBubble(
                                    message = message,
                                    sources = sources,
                                    onSeek = onSeek
                                )
                            }
                            if (busy) {
                                item {
                                    StreamingTutorBubble(partialText = answer)
                                }
                            }
                        }
                    }
                }

                // Compact Status / Error / Usage Notice
                if (status.isNotBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            text = status,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                } else if (infoBanner.isNotBlank()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = infoBanner,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (usage.isNotBlank()) {
                            Text(
                                text = usage,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Quick Suggestion Chips (auto-match language of tapped chip)
                if (canChat && !busy) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val suggestions = listOf(
                            "اشرح هذه اللحظة" to "اشرح لي الفكرة حول التوقيت ${formatTimeMs(positionMs)} ببساطة.",
                            "اختبرني" to "أعطني سؤالاً تدريبياً حول هذا الدرس وانتظر إجابتي قبل عرض الحل.",
                            "لخّص الدرس" to "لخّص أهم نقاط هذا الدرس بشكل مرتب.",
                            "Explain here" to "Explain the lesson around ${formatTimeMs(positionMs)} simply.",
                            "Quiz me" to "Give me one practice question about this lesson. Wait for my answer before giving the solution."
                        )
                        suggestions.forEach { (label, promptText) ->
                            SuggestionChip(
                                onClick = { send(promptText) },
                                label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                                shape = RoundedCornerShape(12.dp)
                            )
                        }
                    }
                }

                // Bottom Chat Composer Bar
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = question,
                            onValueChange = { question = it.take(6000) },
                            placeholder = { Text("Ask about this lesson…") },
                            enabled = !busy,
                            maxLines = 4,
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier.weight(1f)
                        )

                        if (busy) {
                            FilledIconButton(
                                onClick = { work?.cancel() },
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                                ),
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = "Cancel")
                            }
                        } else {
                            FilledIconButton(
                                enabled = canChat && question.isNotBlank(),
                                onClick = { send() },
                                modifier = Modifier
                                    .size(48.dp)
                                    .semantics { contentDescription = "Send" }
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                            }
                        }
                    }
                }
            }
        }
    }

    // Secondary Options Sheet (keeps main chat clean!)
    if (showOptionsSheet) {
        ModalBottomSheet(onDismissRequest = { showOptionsSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "خيارات المعلّم ومصادر الدرس",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "تُرسل الأسئلة والمقاطع المختارة وسجل المحادثة إلى مزوّد الذكاء الاصطناعي المحدد.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                if (lesson.sourceType == SourceType.YOUTUBE && !lesson.remoteId.isNullOrBlank()) {
                    ListItem(
                        headlineContent = { Text("تحميل / تغيير ترجمة يوتيوب") },
                        supportingContent = { Text("جلب الترجمة التوقيتية من الفيديو مباشرة") },
                        leadingContent = { Icon(Icons.Default.Subtitles, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.clickable {
                            showOptionsSheet = false
                            scope.launch {
                                downloadingTranscript = true
                                try {
                                    val tracks = availableTracks.ifEmpty {
                                        withContext(Dispatchers.IO) { transcriptClient.fetchTracks(lesson.remoteId) }
                                    }
                                    availableTracks = tracks
                                    if (tracks.isEmpty()) {
                                        infoBanner = "لا توجد ترجمة متاحة لهذا الفيديو."
                                    } else {
                                        showTrackPicker = true
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    infoBanner = "تعذّر جلب اللغات: ${e.message}"
                                } finally {
                                    downloadingTranscript = false
                                }
                            }
                        }
                    )
                }

                ListItem(
                    headlineContent = { Text("إرفاق ملف نصي (.vtt / .srt / .txt)") },
                    supportingContent = { Text(sourceLabel ?: "استيراد ملف ترجمة من الجهاز") },
                    leadingContent = { Icon(Icons.Default.AttachFile, contentDescription = null) },
                    modifier = Modifier.clickable {
                        showOptionsSheet = false
                        picker.launch(arrayOf("text/*", "application/x-subrip", "application/octet-stream"))
                    }
                )

                if (sources.isNotEmpty()) {
                    ListItem(
                        headlineContent = { Text("حفظ النص كملف (.vtt)") },
                        supportingContent = { Text("تصدير النص الحالي إلى ذاكرة الجهاز") },
                        leadingContent = { Icon(Icons.Default.SaveAlt, contentDescription = null) },
                        modifier = Modifier.clickable {
                            showOptionsSheet = false
                            val safeName = lesson.title.replace(Regex("[^A-Za-z0-9_-]"), "_").take(32).ifBlank { "transcript" }
                            exportTranscriptLauncher.launch("$safeName.vtt")
                        }
                    )
                }

                ListItem(
                    headlineContent = { Text("وضع المساعدة العامة (بدون نص الدرس)") },
                    supportingContent = { Text("الإجابة على أسئلة المادة العامة دون الارتباط بالترجمة") },
                    leadingContent = { Icon(Icons.Default.Public, contentDescription = null) },
                    trailingContent = {
                        Switch(
                            checked = general,
                            enabled = !busy,
                            onCheckedChange = {
                                general = it
                                messages = emptyList()
                                answer = ""
                            }
                        )
                    }
                )

                ListItem(
                    headlineContent = { Text("إعدادات مزوّد الذكاء الاصطناعي") },
                    supportingContent = { Text(profile?.let { "${it.name} • ${it.model}" } ?: "لم يتم اختيار مزوّد") },
                    leadingContent = { Icon(Icons.Default.Settings, contentDescription = null) },
                    modifier = Modifier.clickable {
                        showOptionsSheet = false
                        work?.cancel()
                        onClose()
                        onSettings()
                    }
                )

                ListItem(
                    headlineContent = { Text("مسح سجل المحادثة") },
                    leadingContent = { Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                    modifier = Modifier.clickable(enabled = !busy) {
                        showOptionsSheet = false
                        scope.launch {
                            withContext(Dispatchers.IO) { sourceStore.deleteHistory(lesson, historyScope) }
                            messages = emptyList()
                            answer = ""
                        }
                    }
                )
            }
        }
    }

    if (showTrackPicker) {
        AlertDialog(
            onDismissRequest = { showTrackPicker = false },
            title = { Text("اختر لغة ترجمة يوتيوب", fontWeight = FontWeight.Bold) },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(availableTracks) { track ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showTrackPicker = false
                                    scope.launch { downloadYouTubeTrack(track) }
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(track.name, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        text = "${track.languageCode} • ${if (track.isAutoGenerated) "توليد تلقائي" else "يدوي"}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Icon(Icons.Default.Download, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTrackPicker = false }) { Text("إغلاق") }
            }
        )
    }
}

@Composable
private fun ChatBubble(
    message: TutorMessage,
    sources: List<SourcePassage>,
    onSeek: (Long) -> Unit
) {
    val isUser = message.role == "user"
    val (label, displayBody) = remember(message.text, sources) {
        if (isUser) null to message.text
        else TutorGrounding.stripTagsAndReplaceCitations(message.text, sources, ::formatTimeMs)
    }
    val citations = remember(message.text, sources) {
        if (isUser) emptyList()
        else runCatching { TutorGrounding.references(message.text, sources) }.getOrDefault(emptyList())
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = if (isUser) {
                RoundedCornerShape(topStart = 18.dp, topEnd = 4.dp, bottomStart = 18.dp, bottomEnd = 18.dp)
            } else {
                RoundedCornerShape(topStart = 4.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 18.dp)
            },
            tonalElevation = if (isUser) 0.dp else 1.dp,
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (!isUser && label != null) {
                    val badgeText = when (label) {
                        "Lesson evidence" -> "من نص الدرس"
                        "General help" -> "مساعدة عامة"
                        "Unverified answer" -> "بدون إحالة للدرس"
                        else -> label
                    }
                    Text(
                        text = badgeText,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (label == "Lesson evidence") Color(0xFF10B981) else MaterialTheme.colorScheme.primary
                    )
                }

                SelectionContainer {
                    Text(
                        text = displayBody,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                        lineHeight = 21.sp
                    )
                }

                if (citations.isNotEmpty()) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        citations.forEach { src ->
                            if (src.startMs != null) {
                                AssistChip(
                                    onClick = { onSeek(src.startMs) },
                                    label = { Text(formatTimeMs(src.startMs), style = MaterialTheme.typography.labelSmall) },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.PlayArrow,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    },
                                    shape = RoundedCornerShape(10.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StreamingTutorBubble(partialText: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(topStart = 4.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 18.dp),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "يكتب المعلّم…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                if (partialText.isNotBlank()) {
                    SelectionContainer {
                        Text(
                            text = partialText,
                            style = MaterialTheme.typography.bodyMedium,
                            lineHeight = 21.sp
                        )
                    }
                }
            }
        }
    }
}
