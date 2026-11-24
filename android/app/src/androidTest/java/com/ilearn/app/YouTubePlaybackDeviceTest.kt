package com.ilearn.app

import android.content.Context
import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.ilearn.app.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Live network check; only runs against the isolated validation application ID. */
class YouTubePlaybackDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun officialEmbedPlaysAndPersistsProgressAndANote() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        require(context.packageName == "com.ilearn.app.validation") { "Run device checks with -PvalidationBuild=true to preserve the installed application" }
        val helper = CourseDatabaseHelper(context)
        val courseId = "youtube:PLdevice_fixture"
        val lessonId = "$courseId:sample"
        helper.deleteCourse(courseId)
        helper.insertCourseWithLessons(Course(courseId, "YouTube validation course", "", sourceType = SourceType.YOUTUBE,
            remoteId = "PLdevice_fixture", lastSyncedAt = System.currentTimeMillis()), listOf(
            Lesson(lessonId, courseId, 1, "YouTube validation video", "", sourceType = SourceType.YOUTUBE, remoteId = "M7lc1UVf-VE")))
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        try {
            compose.waitUntil(15000) { compose.onAllNodesWithText("YouTube validation course").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("YouTube validation course").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("YouTube validation video").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("YouTube validation video").performClick()
            compose.waitUntil(60000) {
                compose.onAllNodesWithTag("youtube-player").fetchSemanticsNodes().any { it.config.getOrElse(SemanticsProperties.StateDescription) { "" } in setOf("Ready", "Playing", "Error") }
            }
            compose.onNodeWithTag("youtube-player").assert(SemanticsMatcher("player has no error") { it.config.getOrElse(SemanticsProperties.StateDescription) { "" } != "Error" })
            compose.onNodeWithTag("youtube-player").performTouchInput { click(center) }
            compose.waitUntil(60000) { compose.onNodeWithTag("youtube-player").fetchSemanticsNode().config.getOrElse(SemanticsProperties.StateDescription) { "" } == "Playing" }
            compose.waitUntil(15000) { runBlocking { helper.getLessonsForCourse(courseId).single().lastPositionMs > 1000 } }
            compose.onNode(hasSetTextAction()).performScrollTo().performTextInput("Validation timestamp note")
            compose.onNodeWithContentDescription("حفظ الملاحظة").performScrollTo().performClick()
            compose.waitUntil(10000) { runBlocking { helper.getNotesForLesson(lessonId).any { it.noteText == "Validation timestamp note" } } }
            assertTrue(helper.getLessonsForCourse(courseId).single().lastPositionMs > 0)
        } finally { scenario.close(); helper.deleteCourse(courseId); helper.close() }
    }
}
