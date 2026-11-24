package com.ilearn.app

import com.ilearn.app.youtube.PlaylistUrlParser
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
}
