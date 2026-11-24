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
}
