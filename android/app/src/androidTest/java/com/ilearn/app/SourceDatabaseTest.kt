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
}
