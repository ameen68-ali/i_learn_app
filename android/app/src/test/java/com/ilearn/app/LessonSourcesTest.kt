package com.ilearn.app

import com.ilearn.app.youtube.PlaylistUrlParser
import com.ilearn.app.youtube.YouTubeTranscriptClient
import com.ilearn.app.tutor.TranscriptParser
import org.junit.Assert.*
import org.junit.Test

class LessonSourcesTest {
    @Test fun durationMetadataUsesAnApi24CompatibleParser() {
        assertEquals(3723000L, com.ilearn.app.youtube.YouTubeMetadataClient.parseDuration("PT1H2M3S"))
        assertEquals(0L, com.ilearn.app.youtube.YouTubeMetadataClient.parseDuration("P0D"))
    }
    @Test fun playlistUrlsRequireExactTrustedHost() {
        assertEquals("PLabc_123", PlaylistUrlParser.parse("https://www.youtube.com/watch?v=abc&list=PLabc_123"))
        assertEquals("PLabc_123", PlaylistUrlParser.parse("https://youtu.be/abc?list=PLabc_123"))
        assertNull(PlaylistUrlParser.parse("https://youtube.com.evil.test/playlist?list=PLabc"))
        assertNull(PlaylistUrlParser.parse("https://evil.test/?list=PLabc"))
        assertNull(PlaylistUrlParser.parse("javascript:alert(1)"))
        assertNull(PlaylistUrlParser.parse("https://youtube.com/watch?v=abc"))
    }

    @Test fun timedAndPlainTranscriptsKeepTheirEvidence() {
        val cues = TranscriptParser.parse("WEBVTT\n\n00:00:01.000 --> 00:00:03.500\nChain rule\nقاعدة السلسلة\n\n", "vtt")
        assertEquals(1, cues.size)
        assertEquals(1000L, cues.first().startMs)
        assertTrue(cues.first().text.contains("قاعدة"))
        val plain = TranscriptParser.parse("A plain explanation", "txt")
        assertNull(plain.first().startMs)
        assertTrue(TranscriptParser.parse("1\n00:00:03,000 --> 00:00:01,000\nInvalid", "srt").isEmpty())
    }
    @Test fun adjacentSubtitleCuesFormCoherentEvidence() {
        val passages = TranscriptParser.parse("WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nThe derivative measures\n\n00:00:02.000 --> 00:00:04.000\nthe local rate of change.\n\n", "vtt")
        assertEquals(1, passages.size)
        assertTrue(passages.single().text.contains("local rate of change"))
        assertEquals(4000L, passages.single().endMs)
    }
    @Test fun simultaneousSubtitleCuesDoNotLoseEvidence() {
        val passages = TranscriptParser.parse("WEBVTT\n\n00:00:01.000 --> 00:00:03.000\nFirst speaker\n\n00:00:01.000 --> 00:00:03.000\nSecond speaker\n\n", "vtt")
        assertTrue(passages.any { it.text.contains("First speaker") })
        assertTrue(passages.any { it.text.contains("Second speaker") })
    }

    @Test fun youtubeTranscriptParserConvertsJson3AndCleansRollingWebVtt() {
        val key = YouTubeTranscriptClient.extractInnertubeApiKey("...\"INNERTUBE_API_KEY\":\"AIzaSyTestKey1234567890abcd\"...")
        assertEquals("AIzaSyTestKey1234567890abcd", key)

        val replaced = YouTubeTranscriptClient.setFormatParam("https://www.youtube.com/api/timedtext?v=abc&fmt=srv3", "json3")
        assertEquals("https://www.youtube.com/api/timedtext?v=abc&fmt=json3", replaced)

        val playerJson = """
            {"captions":{"playerCaptionsTracklistRenderer":{"captionTracks":[
              {"baseUrl":"https://www.youtube.com/api/timedtext?v=abc\u0026lang=en","languageCode":"en","kind":"asr","name":{"simpleText":"English (auto)"}},
              {"baseUrl":"https://www.youtube.com/api/timedtext?v=abc\u0026lang=ar","languageCode":"ar","name":{"runs":[{"text":"العربية"}]}}
            ]}}}
        """.trimIndent()
        val tracks = YouTubeTranscriptClient.parseCaptionTracks(playerJson)
        assertEquals(2, tracks.size)
        val preferred = YouTubeTranscriptClient.choosePreferredTrack(tracks, listOf("en", "ar"))
        assertEquals("ar", preferred?.languageCode)

        val json3 = """
            {"events":[
              {"tStartMs":1200,"dDurationMs":2500,"segs":[{"utf8":"Hello "},{"utf8":"world"}]},
              {"tStartMs":3700,"dDurationMs":1200,"segs":[{"utf8":"\n"}]},
              {"tStartMs":5000,"dDurationMs":2000,"segs":[{"utf8":"مرحبا بكم"}]}
            ]}
        """.trimIndent()
        val vttFromJson3 = YouTubeTranscriptClient.json3ToVtt(json3)
        val parsed = TranscriptParser.parse(vttFromJson3, "vtt")
        assertEquals(1, parsed.size)
        assertEquals(1200L, parsed.single().startMs)
        assertTrue(parsed.single().text.contains("Hello world"))
        assertTrue(parsed.single().text.contains("مرحبا بكم"))

        val dirtyVtt = """
            WEBVTT

            00:00:01.000 --> 00:00:02.000 align:start
            Hello<00:00:01.500><c> world</c>

            00:00:02.000 --> 00:00:04.000 align:start
            Hello world
            Next sentence
        """.trimIndent()
        val cleaned = YouTubeTranscriptClient.cleanWebVtt(dirtyVtt)
        val cleanedParsed = TranscriptParser.parse(cleaned, "vtt")
        assertEquals(1, cleanedParsed.size)
        assertEquals("Hello world\nNext sentence", cleanedParsed.single().text)
    }
}
