package com.ilearn.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ilearn.app.data.Flashcard
import com.ilearn.app.data.Lesson
import com.ilearn.app.data.LessonStudyPack
import com.ilearn.app.data.QuizQuestion
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudyPackDialog(
    lesson: Lesson,
    hasConfiguredAi: Boolean,
    onLoadCachedPack: suspend () -> Pair<LessonStudyPack?, List<Flashcard>>,
    onGeneratePack: suspend () -> Result<LessonStudyPack>,
    onLoadLessonFlashcards: suspend () -> List<Flashcard>,
    onReviewFlashcard: suspend (cardId: String, quality: Int) -> Unit,
    onSeek: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onClose: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var loading by remember(lesson.id) { mutableStateOf(true) }
    var generating by remember(lesson.id) { mutableStateOf(false) }
    var errorMessage by remember(lesson.id) { mutableStateOf<String?>(null) }
    var studyPack by remember(lesson.id) { mutableStateOf<LessonStudyPack?>(null) }
    var flashcards by remember(lesson.id) { mutableStateOf<List<Flashcard>>(emptyList()) }
    var selectedTab by remember(lesson.id) { mutableIntStateOf(0) }
    var selectedAnswers by remember(lesson.id, studyPack?.createdAt) { mutableStateOf<Map<Int, Int>>(emptyMap()) }

    LaunchedEffect(lesson.id) {
        loading = true
        val (pack, cards) = onLoadCachedPack()
        studyPack = pack
        flashcards = cards
        loading = false
    }

    fun triggerGenerate() {
        if (generating) return
        scope.launch {
            generating = true
            errorMessage = null
            val result = onGeneratePack()
            result.onSuccess { pack ->
                studyPack = pack
                flashcards = onLoadLessonFlashcards()
                selectedAnswers = emptyMap()
            }.onFailure { err ->
                errorMessage = err.message ?: "تعذّر إنشاء حزمة الدراسة"
            }
            generating = false
        }
    }

    Dialog(
        onDismissRequest = onClose,
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
            ) {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = "حزمة الدراسة الذكية",
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
                            onClick = onClose,
                            modifier = Modifier
                                .size(48.dp)
                                .semantics { contentDescription = "إغلاق حزمة الدراسة" }
                        ) {
                            Icon(Icons.Default.Close, contentDescription = null)
                        }
                    },
                    actions = {
                        if (hasConfiguredAi && studyPack != null && !generating) {
                            IconButton(
                                onClick = { triggerGenerate() },
                                modifier = Modifier
                                    .size(48.dp)
                                    .semantics { contentDescription = "توليد نسخة محدثة من حزمة الدراسة" }
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                )

                if (generating) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                errorMessage?.let { err ->
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                            Text(
                                text = err,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { errorMessage = null },
                                modifier = Modifier
                                    .size(40.dp)
                                    .semantics { contentDescription = "إخفاء الخطأ" }
                            ) {
                                Icon(Icons.Default.Close, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                            }
                        }
                    }
                }

                when {
                    loading -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }

                    studyPack == null -> {
                        StudyPackEmptyView(
                            hasConfiguredAi = hasConfiguredAi,
                            generating = generating,
                            onGenerate = { triggerGenerate() },
                            onOpenSettings = {
                                onClose()
                                onOpenSettings()
                            }
                        )
                    }

                    else -> {
                        val activePack = studyPack!!
                        val tabTitles = listOf(
                            "ملخص الدرس",
                            "اختبار (${activePack.quizQuestions.size})",
                            "بطاقات (${flashcards.size})"
                        )
                        TabRow(selectedTabIndex = selectedTab) {
                            tabTitles.forEachIndexed { idx, label ->
                                Tab(
                                    selected = selectedTab == idx,
                                    onClick = { selectedTab = idx },
                                    text = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                )
                            }
                        }

                        when (selectedTab) {
                            0 -> SummaryTab(summaryMarkdown = activePack.summaryMarkdown)
                            1 -> QuizTab(
                                questions = activePack.quizQuestions,
                                selectedAnswers = selectedAnswers,
                                onSelectAnswer = { qIdx, optIdx ->
                                    selectedAnswers = selectedAnswers + (qIdx to optIdx)
                                },
                                onResetQuiz = { selectedAnswers = emptyMap() },
                                onSeek = { ts ->
                                    onClose()
                                    onSeek(ts)
                                }
                            )
                            2 -> FlashcardsDeckTab(
                                cards = flashcards,
                                onReview = { card, quality ->
                                    scope.launch {
                                        onReviewFlashcard(card.id, quality)
                                        flashcards = onLoadLessonFlashcards()
                                    }
                                },
                                onJumpToCard = { card ->
                                    card.timestampMs?.let { ts ->
                                        onClose()
                                        onSeek(ts)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DueFlashcardsReviewDialog(
    title: String,
    onLoadDueCards: suspend () -> List<Flashcard>,
    onReviewFlashcard: suspend (cardId: String, quality: Int) -> Unit,
    onJumpToLessonTimestamp: ((lessonId: String, courseId: String, timestampMs: Long) -> Unit)? = null,
    onClose: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var cards by remember { mutableStateOf<List<Flashcard>>(emptyList()) }

    LaunchedEffect(Unit) {
        loading = true
        cards = onLoadDueCards()
        loading = false
    }

    Dialog(
        onDismissRequest = onClose,
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
            ) {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "المتبقي للمراجعة الآن: ${cards.size}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = onClose,
                            modifier = Modifier
                                .size(48.dp)
                                .semantics { contentDescription = "إغلاق مراجعة البطاقات" }
                        ) {
                            Icon(Icons.Default.Close, contentDescription = null)
                        }
                    }
                )

                when {
                    loading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    cards.isEmpty() -> Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Color(0xFF10B981),
                                modifier = Modifier.size(56.dp)
                            )
                            Text(
                                text = "أحسنت! لا توجد بطاقات مستحقة الآن",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = "جميع بطاقات هذا القسم مجدولة للمراجعة في وقت لاحق وفق نظام التكرار المتباعد.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            Button(onClick = onClose) { Text("إنهاء") }
                        }
                    }
                    else -> FlashcardsDeckTab(
                        cards = cards,
                        onReview = { card, quality ->
                            scope.launch {
                                onReviewFlashcard(card.id, quality)
                                cards = onLoadDueCards()
                            }
                        },
                        onJumpToCard = onJumpToLessonTimestamp?.let { jump ->
                            { card ->
                                card.timestampMs?.let { ts ->
                                    onClose()
                                    jump(card.lessonId, card.courseId, ts)
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun StudyPackEmptyView(
    hasConfiguredAi: Boolean,
    generating: Boolean,
    onGenerate: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.AutoAwesome,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(56.dp)
        )
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = "ملخص + اختبار + بطاقات مراجعة",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (hasConfiguredAi) {
                "ولّد حزمة دراسة متكاملة من ترجمة الدرس واحفظها محلياً على جهازك لتعمل أوفلاين بالكامل."
            } else {
                "أضف مزوّد ذكاء اصطناعي ومفتاح API من الإعدادات لتوليد ملخصات واختبارات وبطاقات مراجعة تلقائياً."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(18.dp))
        if (hasConfiguredAi) {
            Button(
                onClick = onGenerate,
                enabled = !generating,
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (generating) "جاري توليد الحزمة…" else "توليد حزمة الدراسة الآن")
            }
        } else {
            Button(onClick = onOpenSettings, shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.Default.Settings, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("إعدادات المزوّد • API settings")
            }
        }
    }
}

@Composable
private fun SummaryTab(summaryMarkdown: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                if (summaryMarkdown.isBlank()) {
                    Text(
                        text = "لم يتم تضمين ملخص نصي في هذه الحزمة.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    SelectionContainer {
                        FormattedChatText(
                            rawText = summaryMarkdown,
                            textColor = MaterialTheme.colorScheme.onSurface,
                            accentColor = MaterialTheme.colorScheme.primary,
                            codeBg = MaterialTheme.colorScheme.surfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuizTab(
    questions: List<QuizQuestion>,
    selectedAnswers: Map<Int, Int>,
    onSelectAnswer: (Int, Int) -> Unit,
    onResetQuiz: () -> Unit,
    onSeek: (Long) -> Unit
) {
    if (questions.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text("لا توجد أسئلة متوفرة في هذا الاختبار.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    val answeredCount = selectedAnswers.size
    val correctCount = selectedAnswers.count { (idx, chosen) ->
        questions.getOrNull(idx)?.correctIndex == chosen
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (answeredCount > 0) {
            item {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "النتيجة: $correctCount / ${questions.size} (أُجيب عن $answeredCount)",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        IconButton(
                            onClick = onResetQuiz,
                            modifier = Modifier
                                .size(40.dp)
                                .semantics { contentDescription = "إعادة الاختبار" }
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }
            }
        }

        itemsIndexed(questions) { qIndex, q ->
            val selected = selectedAnswers[qIndex]
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "${qIndex + 1}. ${q.question}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )

                    q.options.forEachIndexed { optIndex, optText ->
                        val isChosen = selected == optIndex
                        val isCorrect = optIndex == q.correctIndex
                        val borderColor = when {
                            selected == null -> MaterialTheme.colorScheme.outlineVariant
                            isCorrect -> Color(0xFF10B981)
                            isChosen -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        }
                        val bgColor = when {
                            selected == null -> MaterialTheme.colorScheme.surface
                            isCorrect -> Color(0xFF10B981).copy(alpha = 0.14f)
                            isChosen -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
                            else -> MaterialTheme.colorScheme.surface
                        }

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = bgColor,
                            border = BorderStroke(1.dp, borderColor),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = selected == null) { onSelectAnswer(qIndex, optIndex) }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = optText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                if (selected != null && (isCorrect || isChosen)) {
                                    Icon(
                                        imageVector = if (isCorrect) Icons.Default.CheckCircle else Icons.Default.Cancel,
                                        contentDescription = null,
                                        tint = if (isCorrect) Color(0xFF10B981) else MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }

                    if (selected != null) {
                        if (q.explanation.isNotBlank()) {
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = q.explanation,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }
                        q.timestampMs?.let { ts ->
                            AssistChip(
                                onClick = { onSeek(ts) },
                                label = { Text("انتقل إلى الشرح في ${formatTimeMs(ts)}") },
                                leadingIcon = {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FlashcardsDeckTab(
    cards: List<Flashcard>,
    onReview: (Flashcard, Int) -> Unit,
    onJumpToCard: ((Flashcard) -> Unit)?
) {
    if (cards.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text("لا توجد بطاقات مراجعة في هذا الدرس.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    var currentIndex by remember(cards.size) { mutableIntStateOf(0) }
    val safeIndex = currentIndex.coerceIn(0, cards.lastIndex)
    val card = cards[safeIndex]
    var flipped by remember(card.id) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "بطاقة ${safeIndex + 1} من ${cards.size}",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = if (card.repetitions == 0) "بطاقة جديدة" else "الفاصل: ${card.intervalDays} يوم",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Card(
            onClick = { flipped = !flipped },
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (flipped) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
            ),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                AnimatedContent(targetState = flipped, label = "flashcard_flip") { isBack ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = if (isBack) "الإجابة والشرح" else "المفهوم / السؤال (اضغط لكشف الإجابة)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (isBack) card.back else card.front,
                            style = MaterialTheme.typography.titleMedium,
                            fontSize = 18.sp,
                            textAlign = TextAlign.Center
                        )
                        card.timestampMs?.let { ts ->
                            if (onJumpToCard != null) {
                                AssistChip(
                                    onClick = { onJumpToCard(card) },
                                    label = { Text("اللحظة ${formatTimeMs(ts)}") },
                                    leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                )
                            }
                        }
                    }
                }
            }
        }

        if (!flipped) {
            Button(
                onClick = { flipped = true },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("إظهار الإجابة", fontWeight = FontWeight.Bold)
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        onReview(card, 1)
                        flipped = false
                        currentIndex = if (safeIndex + 1 < cards.size) safeIndex + 1 else 0
                    },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("صعب", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
                FilledTonalButton(
                    onClick = {
                        onReview(card, 3)
                        flipped = false
                        currentIndex = if (safeIndex + 1 < cards.size) safeIndex + 1 else 0
                    },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("جيد", fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = {
                        onReview(card, 5)
                        flipped = false
                        currentIndex = if (safeIndex + 1 < cards.size) safeIndex + 1 else 0
                    },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("سهل", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
