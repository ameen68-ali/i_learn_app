package com.ilearn.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ilearn.app.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class SourceDatabaseTest {
    @Test fun backupRoundTripRestoresRemoteSourcesBeforeNotes() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("ilearn-instrumentation-test.db")
        var helper = CourseDatabaseHelper(context, "ilearn-instrumentation-test.db")
        val course = Course("youtube:PLtest", "Playlist", "", sourceType = SourceType.YOUTUBE, remoteId = "PLtest", lastSyncedAt = System.currentTimeMillis())
        val lesson = Lesson("${course.id}:entry", course.id, 1, "Lesson", "", sourceType = SourceType.YOUTUBE, remoteId = "abcdefghijk")
        helper.insertCourseWithLessons(course, listOf(lesson))
        helper.insertNote(LessonNote("note", lesson.id, course.id, 1234, "Original note"))
        helper.updateLessonProgress(lesson.id, course.id, 5000, 10000)
        val backup = helper.exportProgressJson()
        assertFalse(backup.contains("secret")); assertFalse(backup.contains("history"))
        helper.close(); context.deleteDatabase("ilearn-instrumentation-test.db"); helper = CourseDatabaseHelper(context, "ilearn-instrumentation-test.db")
        assertTrue(helper.importProgressJson(backup))
        assertEquals(SourceType.YOUTUBE, helper.getAllCourses().single().sourceType)
        assertEquals("Original note", helper.getNotesForLesson(lesson.id).single().noteText)
        assertEquals(5000L, helper.getLessonsForCourse(course.id).single().lastPositionMs)
        val malformed = org.json.JSONObject(backup).apply { getJSONArray("notes").getJSONObject(0).put("courseId", "wrong-course") }.toString()
        assertFalse(helper.importProgressJson(malformed))
        assertEquals("Original note", helper.getNotesForLesson(lesson.id).single().noteText)
        helper.close()
    }

    @Test fun profileSessionKeysAreNotPersisted() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val isolatedDir = java.io.File(context.cacheDir, "profile-fixture-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val fixtureContext = object : android.content.ContextWrapper(context) { override fun getNoBackupFilesDir() = isolatedDir }
        val store = com.ilearn.app.tutor.ProviderProfileStore(fixtureContext)
        val profile = com.ilearn.app.tutor.ProviderProfile(name = "Session", format = com.ilearn.app.tutor.ApiFormat.OPENAI_CHAT, endpoint = "https://example.test/v1", model = "test")
        store.save(profile, "test-secret-not-a-real-key", false)
        assertEquals("test-secret-not-a-real-key", store.key(profile))
        assertNull(com.ilearn.app.tutor.ProviderProfileStore(fixtureContext).key(profile))
        assertNull(store.key(profile.copy(endpoint = "https://other.test/v1")))
        assertFalse(java.io.File(isolatedDir, "api_profiles.json").readText().contains("test-secret-not-a-real-key"))
        store.save(profile, "test-secret-not-a-real-key", true)
        assertEquals("test-secret-not-a-real-key", com.ilearn.app.tutor.ProviderProfileStore(fixtureContext).key(profile))
        assertFalse(java.io.File(isolatedDir, "api_profiles.json").readText().contains("test-secret-not-a-real-key"))
        store.delete(profile.id)
        assertNull(store.key(profile))
        check(isolatedDir.canonicalPath.startsWith(context.cacheDir.canonicalPath + java.io.File.separator))
        isolatedDir.deleteRecursively()
    }

    @Test fun conversationsAreIsolatedByProviderAndSourceRevision() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fixtureDir = java.io.File(context.cacheDir, "history-fixture-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : android.content.ContextWrapper(context) { override fun getNoBackupFilesDir() = fixtureDir }
        try {
            val store = com.ilearn.app.tutor.LessonSourceStore(isolated)
            val lesson = Lesson("fixture-lesson", "fixture-course", 1, "Fixture", "")
            store.saveHistory(lesson, "provider-a|source-one", listOf(com.ilearn.app.tutor.TutorMessage("user", "Private question")))
            assertEquals(1, store.history(lesson, "provider-a|source-one").size)
            assertTrue(store.history(lesson, "provider-b|source-one").isEmpty())
            assertTrue(store.history(lesson, "provider-a|source-two").isEmpty())
            store.deleteLesson(lesson)
            assertTrue(store.history(lesson, "provider-a|source-one").isEmpty())
        } finally {
            check(fixtureDir.canonicalPath.startsWith(context.cacheDir.canonicalPath + java.io.File.separator))
            fixtureDir.deleteRecursively()
        }
    }

    @Test fun refreshPreservesNoteAndProgressAndRetainsRemovedEntries() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("ilearn-instrumentation-test.db")
        val helper = CourseDatabaseHelper(context, "ilearn-instrumentation-test.db")
        val course = Course("yt-p", "Playlist", "", sourceType = SourceType.YOUTUBE, remoteId = "PLtest")
        val lesson = Lesson("yt-item", course.id, 1, "First", "", sourceType = SourceType.YOUTUBE, remoteId = "abcdefghijk")
        helper.insertCourseWithLessons(course, listOf(lesson))
        helper.insertNote(LessonNote("note", lesson.id, course.id, 1000, "Keep me"))
        helper.updateLessonProgress(lesson.id, course.id, 2000, 10000)
        helper.insertCourseWithLessons(course.copy(title = "Updated"), listOf(lesson.copy(orderIndex = 2)))
        assertEquals("Keep me", helper.getNotesForLesson(lesson.id).single().noteText)
        assertEquals(2000L, helper.getLessonsForCourse(course.id).single().lastPositionMs)
        helper.setLessonOfflineMedia(lesson.id, "/courses/yt/001.mp4", "/courses/yt/001_ar.vtt", "/courses/yt/001_en.vtt", 9000L)
        helper.insertCourseWithLessons(course.copy(title = "Refreshed"), listOf(lesson.copy(orderIndex = 1, videoPath = "", subArPath = null, subEnPath = null)))
        val refreshed = helper.getLessonsForCourse(course.id).single()
        assertEquals("/courses/yt/001.mp4", refreshed.videoPath)
        assertEquals("/courses/yt/001_ar.vtt", refreshed.subArPath)
        assertEquals("/courses/yt/001_en.vtt", refreshed.subEnPath)
        helper.insertCourseWithLessons(course, emptyList())
        assertFalse(helper.getLessonsForCourse(course.id).single().isAvailable)
        assertEquals(1, helper.getNotesForLesson(lesson.id).size)
        helper.close()
    }

    @Test fun versionThreeUpgradePreservesLocalRecords() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("ilearn-instrumentation-test.db")
        val db = context.openOrCreateDatabase("ilearn-instrumentation-test.db", 0, null)
        db.execSQL("CREATE TABLE courses (id TEXT PRIMARY KEY, title TEXT NOT NULL, folder_path TEXT NOT NULL, date_added INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE lessons (id TEXT PRIMARY KEY, course_id TEXT, order_index INTEGER, title TEXT, video_path TEXT, sub_ar_path TEXT, sub_en_path TEXT, audio_ar_path TEXT, duration_ms INTEGER)")
        db.execSQL("INSERT INTO courses VALUES ('local','Original','/owned/path',123)")
        db.version = 3; db.close()
        val helper = CourseDatabaseHelper(context, "ilearn-instrumentation-test.db")
        helper.readableDatabase.rawQuery("SELECT title, source_type, folder_path FROM courses", null).use {
            assertTrue(it.moveToFirst()); assertEquals("Original", it.getString(0)); assertEquals("LOCAL", it.getString(1)); assertEquals("/owned/path", it.getString(2))
        }
        helper.close()
    }

    @Test fun studyPackAndFlashcardsPersistAcrossV4UpgradeAndReview() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("ilearn-study-test.db")
        val db = context.openOrCreateDatabase("ilearn-study-test.db", 0, null)
        db.execSQL("CREATE TABLE courses (id TEXT PRIMARY KEY, title TEXT NOT NULL, folder_path TEXT NOT NULL, date_added INTEGER NOT NULL, source_type TEXT NOT NULL DEFAULT 'LOCAL', remote_id TEXT, last_synced_at INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE lessons (id TEXT PRIMARY KEY, course_id TEXT NOT NULL, order_index INTEGER NOT NULL, title TEXT NOT NULL, video_path TEXT NOT NULL, sub_ar_path TEXT, sub_en_path TEXT, audio_ar_path TEXT, duration_ms INTEGER NOT NULL DEFAULT 0, is_available INTEGER NOT NULL DEFAULT 1)")
        db.execSQL("CREATE TABLE progress (lesson_id TEXT PRIMARY KEY, course_id TEXT NOT NULL, is_completed INTEGER NOT NULL DEFAULT 0, last_position_ms INTEGER NOT NULL DEFAULT 0, total_duration_ms INTEGER NOT NULL DEFAULT 0, is_bookmarked INTEGER NOT NULL DEFAULT 0, last_watched_timestamp INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE notes (id TEXT PRIMARY KEY, lesson_id TEXT NOT NULL, course_id TEXT NOT NULL, timestamp_ms INTEGER NOT NULL, note_text TEXT NOT NULL, created_at INTEGER NOT NULL)")
        db.execSQL("INSERT INTO courses VALUES ('c-study','Math','/owned/math',100,'LOCAL',NULL,0)")
        db.execSQL("INSERT INTO lessons VALUES ('l-study','c-study',1,'Derivatives','/owned/math/01.mp4',NULL,NULL,NULL,10000,1)")
        db.version = 4
        db.close()

        val helper = CourseDatabaseHelper(context, "ilearn-study-test.db")
        val now = 1_700_000_000_000L
        val pack = LessonStudyPack(
            lessonId = "l-study",
            courseId = "c-study",
            summaryMarkdown = "## المشتقة\n- معدل التغير",
            quizQuestions = listOf(QuizQuestion("ما المشتقة؟", listOf("ميل المماس", "المساحة"), 0, "تعريف المشتقة", 5000L)),
            createdAt = now
        )
        val cards = listOf(
            Flashcard("fc-1", "l-study", "c-study", "Derivative", "Rate of change", timestampMs = 5000L, dueAt = now - 1000L),
            Flashcard("fc-2", "l-study", "c-study", "Integral", "Area under curve", timestampMs = 8000L, dueAt = now - 1000L)
        )
        helper.saveStudyPack(pack, cards)

        val restoredPack = helper.getStudyPack("l-study")
        assertNotNull(restoredPack)
        assertEquals("## المشتقة\n- معدل التغير", restoredPack!!.summaryMarkdown)
        assertEquals(1, restoredPack.quizQuestions.size)
        assertEquals(2, helper.getDueFlashcardsCount("c-study", now))

        helper.recordFlashcardReview("fc-1", quality = 3, nowMs = now)
        assertEquals(1, helper.getDueFlashcardsCount("c-study", now))
        val updatedCard = helper.getFlashcardsForLesson("l-study").first { it.id == "fc-1" }
        assertEquals(1, updatedCard.repetitions)
        assertEquals(1, updatedCard.intervalDays)

        helper.deleteCourse("c-study")
        assertNull(helper.getStudyPack("l-study"))
        assertEquals(0, helper.getDueFlashcardsCount(null, now + 10_000_000_000L))
        helper.close()
        context.deleteDatabase("ilearn-study-test.db")
    }
}
