package com.ilearn.app

import com.ilearn.app.data.Flashcard
import com.ilearn.app.tutor.SourcePassage
import com.ilearn.app.tutor.SpacedRepetition
import com.ilearn.app.tutor.StudyPackGenerator
import com.ilearn.app.tutor.TranscriptParser
import org.junit.Assert.*
import org.junit.Test

class StudyPackAndVisionTest {

    @Test fun spacedRepetitionIntervalsFollowSm2Rules() {
        val now = 1_700_000_000_000L
        val fresh = Flashcard(
            id = "fc1",
            lessonId = "l1",
            courseId = "c1",
            front = "Eigenvalue equation",
            back = "Av = λv",
            dueAt = now
        )

        val good1 = SpacedRepetition.review(fresh, quality = 3, nowMs = now)
        assertEquals(1, good1.repetitions)
        assertEquals(1, good1.intervalDays)
        assertTrue(good1.dueAt > now)

        val good2 = SpacedRepetition.review(good1, quality = 3, nowMs = now)
        assertEquals(2, good2.repetitions)
        assertEquals(6, good2.intervalDays)

        val easyFromStart = SpacedRepetition.review(fresh, quality = 5, nowMs = now)
        assertEquals(1, easyFromStart.repetitions)
        assertEquals(4, easyFromStart.intervalDays)
        assertTrue(easyFromStart.easeFactor >= 2.5)

        val lapse = SpacedRepetition.review(good2, quality = 1, nowMs = now)
        assertEquals(0, lapse.repetitions)
        assertEquals(1, lapse.intervalDays)
        assertEquals(now + 10L * 60_000L, lapse.dueAt)
    }

    @Test fun studyPackParserHandlesNoisyJsonAndRoundTripsQuiz() {
        val raw = """
            nullnull```json
            {
              "summaryMarkdown": "## الملخص\n- المشتقة تعبر عن معدل التغير (Derivative)",
              "quiz": [
                {
                  "question": "ما معنى المشتقة الأولى؟",
                  "options": ["المساحة", "ميل المماس", "الحجم", "المتوسط"],
                  "correctIndex": 1,
                  "explanation": "المشتقة تساوي ميل المماس عند النقطة.",
                  "timestampMs": 95000
                }
              ],
              "flashcards": [
                {
                  "front": "Gradient ∇f",
                  "back": "متجه يشير لاتجاه أقصى تزايد للدالة",
                  "timestampMs": 140000
                }
              ]
            }
            ```
        """.trimIndent()

        val (pack, cards) = StudyPackGenerator.parseResponse(raw, lessonId = "les-1", courseId = "crs-1", nowMs = 1234L)
        assertTrue(pack.summaryMarkdown.contains("المشتقة"))
        assertEquals(1, pack.quizQuestions.size)
        assertEquals(1, pack.quizQuestions.first().correctIndex)
        assertEquals(95_000L, pack.quizQuestions.first().timestampMs)
        assertEquals(1, cards.size)
        assertEquals("les-1", cards.first().lessonId)
        assertEquals(140_000L, cards.first().timestampMs)

        val serialized = StudyPackGenerator.serializeQuizJson(pack.quizQuestions)
        val restored = StudyPackGenerator.parseQuizJson(serialized)
        assertEquals(pack.quizQuestions, restored)
    }

    @Test fun samplePassagesCoversFullTimelineWithinCharBudget() {
        val passages = (0 until 100).map { i ->
            SourcePassage(
                id = "p-$i",
                startMs = i * 10_000L,
                endMs = (i + 1) * 10_000L,
                text = "Lecture excerpt number $i covering linear transformations.",
                label = "transcript"
            )
        }
        val sampled = StudyPackGenerator.samplePassagesEvenly(passages, maxChars = 900)
        assertTrue(sampled.size in 5..20)
        assertEquals("p-0", sampled.first().id)
        assertTrue((sampled.last().startMs ?: 0L) >= 900_000L)
    }

    @Test fun transcriptParserFindsActiveDualCueLinesAccurately() {
        val vtt = """
            WEBVTT

            00:00:01.000 --> 00:00:04.500
            Welcome to Linear Algebra

            00:00:05.000 --> 00:00:09.000
            Today we study determinants
        """.trimIndent()

        val lines = TranscriptParser.parseLines(vtt, "vtt")
        assertEquals(2, lines.size)
        assertNull(TranscriptParser.findActiveLine(lines, 500L))
        assertEquals("Welcome to Linear Algebra", TranscriptParser.findActiveLine(lines, 2500L)?.text)
        assertNull(TranscriptParser.findActiveLine(lines, 4700L))
        assertEquals("Today we study determinants", TranscriptParser.findActiveLine(lines, 7000L)?.text)
    }
}
