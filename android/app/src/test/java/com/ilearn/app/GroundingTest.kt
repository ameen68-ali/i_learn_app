package com.ilearn.app

import com.ilearn.app.tutor.*
import org.junit.Assert.*
import org.junit.Test

class GroundingTest {
    @Test fun referencesMustBelongToTheSuppliedSourceRevision() {
        val a = SourcePassage("abcdef123456-1", "The actual explanation", 1000, 2000)
        assertEquals(listOf(a), TutorGrounding.references("Explanation [abcdef123456-1]", listOf(a)))
        assertTrue(runCatching { TutorGrounding.references("Invented [abcdef999999-2]", listOf(a)) }.isFailure)
        assertTrue(TutorGrounding.references("No citations", listOf(a)).isEmpty())
    }
    @Test fun retrievalHonorsBudgetAndPrioritizesQuestionAndTime() {
        val cues = listOf(SourcePassage("one", "A distant unrelated passage", 1000000, 1000100), SourcePassage("two", "Chain rule definition", 1200, 2200))
        assertEquals("two", TranscriptParser.retrieve(cues, "chain rule", 1500, 25).first().id)
        assertTrue(TranscriptParser.retrieve(cues, "chain rule", 1500, 2).isEmpty())
    }
    @Test fun generalHelpCannotPretendToHaveLessonEvidence() {
        assertTrue(TutorGrounding.prompt("Arabic", emptyList(), true).contains("Never claim to have seen the lesson"))
    }
    @Test fun bilingualExplicitlyMeansArabicAndEnglish() {
        assertTrue(TutorGrounding.prompt("Bilingual", emptyList(), true).contains("Arabic and English"))
    }
    @Test fun retrievedEvidenceKeepsSurroundingContextInLectureOrder() {
        val cues = listOf(SourcePassage("before", "Setup for the argument", 0, 10000), SourcePassage("definition", "Derivative definition", 60000, 70000), SourcePassage("after", "Its mathematical consequence", 120000, 130000))
        assertEquals(listOf("before", "definition", "after"), TranscriptParser.retrieve(cues, "derivative", 65000, 1000).map { it.id })
    }
    @Test fun autoLanguageMatchesArabicAndNonArabicMessages() {
        val arabicPrompt = TutorGrounding.promptForMessage("اشرح لي قاعدة السلسلة", emptyList(), false)
        assertTrue(arabicPrompt.contains("Reply in Arabic"))
        val englishPrompt = TutorGrounding.promptForMessage("Explain the chain rule", emptyList(), false)
        assertTrue(englishPrompt.contains("exact same language as the student's latest message"))
    }
    @Test fun stripTagsReplacesValidCitationsWithTimestamps() {
        val src = SourcePassage("abcdef123456-1", "Derivative rule", 65000, 70000)
        val (tag, body) = TutorGrounding.stripTagsAndReplaceCitations("[Lesson evidence]\nSee [abcdef123456-1] here.", listOf(src)) { ms ->
            val s = ms / 1000
            String.format(java.util.Locale.US, "%02d:%02d", s / 60, s % 60)
        }
        assertEquals("Lesson evidence", tag)
        assertEquals("See [01:05] here.", body)
        val (_, cleanedNulls) = TutorGrounding.stripTagsAndReplaceCitations("[Lesson evidence]\nnullnullnullHello", listOf(src)) { "00:00" }
        assertEquals("Hello", cleanedNulls)
    }
    @Test fun summaryRetrievalCoversFullLectureNotJustOpening() {
        val cues = (0 until 60).map { i -> SourcePassage("p-$i", "Segment $i content", i * 60_000L, (i + 1) * 60_000L) }
        val retrieved = TranscriptParser.retrieve(cues, "لخّص أهم نقاط هذا الدرس", 0L, 10_000)
        assertTrue(retrieved.first().id == "p-0")
        assertTrue(retrieved.last().id == "p-59")
        assertTrue(retrieved.any { it.id == "p-30" })
    }
}
