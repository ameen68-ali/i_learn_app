package com.ilearn.app

import com.ilearn.app.youtube.YouTubeChapterParser
import com.ilearn.app.youtube.YouTubeImportTarget
import com.ilearn.app.youtube.PlaylistUrlParser
import org.junit.Assert.*
import org.junit.Test

class ChapterSplittingTest {

    @Test fun parsesPlaylistsAndSingleVideosAcrossAllowedHttpsHosts() {
        assertEquals(
            YouTubeImportTarget.Playlist("PLZHQObOWTQDPD3MizzM2xVFitgF8hE_ab"),
            PlaylistUrlParser.parseTarget("https://www.youtube.com/playlist?list=PLZHQObOWTQDPD3MizzM2xVFitgF8hE_ab")
        )
        assertEquals(
            YouTubeImportTarget.Video("fNk_zzaMoSs"),
            PlaylistUrlParser.parseTarget("https://www.youtube.com/watch?v=fNk_zzaMoSs")
        )
        assertEquals(
            YouTubeImportTarget.Video("fNk_zzaMoSs"),
            PlaylistUrlParser.parseTarget("https://youtu.be/fNk_zzaMoSs?t=120")
        )
        assertEquals(
            YouTubeImportTarget.Video("fNk_zzaMoSs"),
            PlaylistUrlParser.parseTarget("https://www.youtube.com/live/fNk_zzaMoSs")
        )
        assertEquals(
            YouTubeImportTarget.Video("fNk_zzaMoSs"),
            PlaylistUrlParser.parseTarget("https://www.youtube.com/shorts/fNk_zzaMoSs")
        )
        assertNull(PlaylistUrlParser.parseTarget("http://www.youtube.com/watch?v=fNk_zzaMoSs"))
        assertNull(PlaylistUrlParser.parseTarget("https://example.test/watch?v=fNk_zzaMoSs"))
    }

    @Test fun parsesCreatorDescriptionChaptersWithIntroAndHourTimestamps() {
        val desc = """
            Full Linear Algebra Course
            00:25 Vectors & Linear Combinations
            00:32 Tiny transition blip
            15:30 Matrices and Transformations
            1:05:10 Eigenvalues & Eigenvectors
            Check our notes on 02:14 in the pinned comment
        """.trimIndent()

        val chapters = YouTubeChapterParser.parseChaptersFromDescription(desc, videoDurationMs = 5_400_000L)
        assertEquals(4, chapters.size)
        assertEquals("المقدمة • Intro", chapters[0].title)
        assertEquals(0L, chapters[0].startMs)
        assertEquals(25_000L, chapters[0].endMs)

        assertEquals("Vectors & Linear Combinations", chapters[1].title)
        assertEquals(25_000L, chapters[1].startMs)
        assertEquals(930_000L, chapters[1].endMs)

        assertEquals("Matrices and Transformations", chapters[2].title)
        assertEquals(930_000L, chapters[2].startMs)
        assertEquals(3_910_000L, chapters[2].endMs)

        assertEquals("Eigenvalues & Eigenvectors", chapters[3].title)
        assertEquals(3_910_000L, chapters[3].startMs)
        assertEquals(5_400_000L, chapters[3].endMs)
    }

    @Test fun parsesAutoChaptersFromWatchPageHtmlMarkers() {
        val html = """
            <html><script>var ytInitialData = {"engagementPanels":[{"chapterRenderer":{"title":{"simpleText":"Setup & Goals"},"timeRangeStartMillis":0}},{"chapterRenderer":{"title":{"runs":[{"text":"Core Derivation"}]},"timeRangeStartMillis":180000}},{"chapterRenderer":{"title":{"simpleText":"Worked Example"},"timeRangeStartMillis":600000}}]};</script></html>
        """.trimIndent()

        val chapters = YouTubeChapterParser.parseChaptersFromWatchHtml(html, videoDurationMs = 1_200_000L)
        assertEquals(3, chapters.size)
        assertEquals("Setup & Goals", chapters[0].title)
        assertEquals(0L, chapters[0].startMs)
        assertEquals(180_000L, chapters[0].endMs)
        assertEquals("Core Derivation", chapters[1].title)
        assertEquals(600_000L, chapters[1].endMs)
        assertEquals("Worked Example", chapters[2].title)
        assertEquals(1_200_000L, chapters[2].endMs)
    }

    @Test fun slicesAndRebasesVttWithKeyframeAlignedBoundaries() {
        val fullVtt = """
            WEBVTT

            00:00:02.000 --> 00:00:05.000
            Intro phrase before chapter

            00:09:58.000 --> 00:10:04.000
            Boundary crossing explanation

            00:15:10.000 --> 00:15:14.000
            Second chapter concept
        """.trimIndent()

        // Suppose MediaExtractor snapped Chapter 2 start to keyframe 09:58.000 (598,000 ms) through 20:00.000 (1,200,000 ms)
        val sliced = YouTubeChapterParser.sliceAndRebaseVtt(fullVtt, clipStartMs = 598_000L, clipEndMs = 1_200_000L)
        assertNotNull(sliced)
        assertFalse(sliced!!.contains("Intro phrase"))
        assertTrue(sliced.contains("00:00:00.000 --> 00:00:06.000"))
        assertTrue(sliced.contains("Boundary crossing explanation"))
        assertTrue(sliced.contains("00:05:12.000 --> 00:05:16.000"))
        assertTrue(sliced.contains("Second chapter concept"))
    }
}
