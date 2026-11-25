package com.ilearn.app

import com.ilearn.app.tutor.ApiFormat
import com.ilearn.app.tutor.ArabicSubtitleTranslator
import com.ilearn.app.tutor.ProviderProfile
import com.ilearn.app.tutor.RawSubtitleCue
import com.ilearn.app.tutor.TranscriptParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ArabicSubtitleTranslatorTest {

    private val testProfile = ProviderProfile(
        id = "p1",
        name = "Test",
        format = ApiFormat.OPENAI_CHAT,
        endpoint = "https://api.openai.com/v1",
        model = "gpt-4o-mini",
        maxOutputTokens = 2048,
        contextChars = 24000
    )

    @Test fun parsesSrtAndVttCuesWithNormalizedTimestampsAndStrippedTags() {
        val srt = """
            1
            00:01:02,500 --> 00:01:05,750
            <i>Linear</i> transformations

            2
            02:10,000 --> 02:12,250
            preserve <c>addition</c>.
        """.trimIndent()

        val cues = ArabicSubtitleTranslator.parseRawCues(srt, "srt")
        assertEquals(2, cues.size)
        assertEquals("00:01:02.500", cues[0].startTime)
        assertEquals("00:01:05.750", cues[0].endTime)
        assertEquals("Linear transformations", cues[0].text)
        assertEquals("00:02:10.000", cues[1].startTime)
        assertEquals("preserve addition.", cues[1].text)
    }

    @Test fun batchesRespectMaxOutputAndContextLimits() {
        val cues = (1..75).map { idx ->
            RawSubtitleCue(idx, "00:00:01.000", "00:00:02.000", "Sample English subtitle sentence number $idx.")
        }
        val batches = ArabicSubtitleTranslator.buildBatches(cues, contextChars = 4000, maxOutputTokens = 500)
        assertTrue(batches.size >= 3)
        assertEquals(cues, batches.flatten())
    }

    @Test fun translatesVttPreservingTimestampsAndRetryingSplitBatches() = runBlocking {
        val enVtt = """
            WEBVTT

            00:00:01.000 --> 00:00:03.000
            Welcome to linear algebra.

            00:00:03.500 --> 00:00:06.000
            A vector has magnitude.

            00:00:06.500 --> 00:00:09.000
            And direction in space.

            00:00:09.500 --> 00:00:12.000
            Let us add two vectors.
        """.trimIndent()

        var calls = 0
        val (arVtt, outExt) = ArabicSubtitleTranslator.translateTranscript(
            sourceText = enVtt,
            sourceExtension = "vtt",
            profile = testProfile,
            apiKey = "sk-test-1234567890",
            completeBatch = { _, userPrompt ->
                calls++
                val lines = userPrompt.lines()
                if (lines.size == 4) {
                    error("Simulated output limit on large batch")
                }
                lines.joinToString("\n") { line ->
                    val id = Regex("^\\[(\\d+)]").find(line)!!.groupValues[1]
                    "[$id] ترجمة عربية رقم $id"
                }
            }
        )

        assertEquals("vtt", outExt)
        assertEquals(3, calls)
        assertTrue(arVtt.startsWith("WEBVTT"))
        assertTrue(arVtt.contains("00:00:01.000 --> 00:00:03.000"))
        assertTrue(arVtt.contains("00:00:09.500 --> 00:00:12.000"))

        val parsed = TranscriptParser.parse(arVtt, "vtt")
        assertTrue(parsed.isNotEmpty())
        assertTrue(parsed.any { it.text.contains("ترجمة عربية رقم 1") })
        assertTrue(parsed.any { it.text.contains("ترجمة عربية رقم 4") })
    }

    @Test fun parsesFlexibleNumberedFormatsAndTranslatesPlainText() = runBlocking {
        val raw = """
            ```
            [1] مقدمة
            2. الشرح الثاني
            سطر مكمل
            ```
        """.trimIndent()
        val map = ArabicSubtitleTranslator.parseTranslatedLines(raw, listOf(1, 2))
        assertEquals("مقدمة", map[1])
        assertEquals("الشرح الثاني سطر مكمل", map[2])

        val (arTxt, ext) = ArabicSubtitleTranslator.translateTranscript(
            sourceText = "First paragraph.\n\nSecond paragraph.",
            sourceExtension = "txt",
            profile = testProfile,
            apiKey = "sk-test-1234567890",
            completeBatch = { _, usr -> usr.replace("First", "الأولى").replace("Second", "الثانية") }
        )
        assertEquals("txt", ext)
        assertTrue(arTxt.contains("الأولى"))
        assertTrue(arTxt.contains("الثانية"))
    }
}
