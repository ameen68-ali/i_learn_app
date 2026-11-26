package com.ilearn.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/**
 * Native Android SQLite database for persisting courses, lessons, student progress,
 * bookmarks, notes, audio tracks, and study analytics.
 */
class CourseDatabaseHelper(context: Context, databaseName: String = DATABASE_NAME) : SQLiteOpenHelper(context, databaseName, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "ilearn.db"
        private const val DATABASE_VERSION = 6

        // Tables
        private const val TABLE_COURSES = "courses"
        private const val TABLE_LESSONS = "lessons"
        private const val TABLE_PROGRESS = "student_progress"
        private const val TABLE_NOTES = "lesson_notes"
        private const val TABLE_STUDY_PACKS = "lesson_study_packs"
        private const val TABLE_FLASHCARDS = "flashcards"

        // Common
        private const val COL_ID = "id"
        private const val COL_TITLE = "title"

        // Courses
        private const val COL_FOLDER_PATH = "folder_path"
        private const val COL_DATE_ADDED = "date_added"

        // Lessons
        private const val COL_COURSE_ID = "course_id"
        private const val COL_ORDER_INDEX = "order_index"
        private const val COL_VIDEO_PATH = "video_path"
        private const val COL_SUB_AR_PATH = "sub_ar_path"
        private const val COL_SUB_EN_PATH = "sub_en_path"
        private const val COL_AUDIO_AR_PATH = "audio_ar_path"
        private const val COL_DURATION_MS = "duration_ms"

        // Student Progress
        private const val COL_LESSON_ID = "lesson_id"
        private const val COL_IS_COMPLETED = "is_completed"
        private const val COL_LAST_POSITION_MS = "last_position_ms"
        private const val COL_TOTAL_DURATION_MS = "total_duration_ms"
        private const val COL_LAST_WATCHED_TIMESTAMP = "last_watched_timestamp"
        private const val COL_IS_BOOKMARKED = "is_bookmarked"

        // Lesson Notes
        private const val COL_NOTE_TIMESTAMP_MS = "timestamp_ms"
        private const val COL_NOTE_TEXT = "note_text"
        private const val COL_CREATED_AT = "created_at"
    }

    private val _databaseUpdates = MutableStateFlow(0L)
    val databaseUpdates: StateFlow<Long> = _databaseUpdates.asStateFlow()

    private fun notifyChanged() {
        _databaseUpdates.value = System.currentTimeMillis()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE_COURSES (
                $COL_ID TEXT PRIMARY KEY,
                $COL_TITLE TEXT NOT NULL,
                $COL_FOLDER_PATH TEXT NOT NULL,
                $COL_DATE_ADDED INTEGER NOT NULL
            )
        """)

        db.execSQL("""
            CREATE TABLE $TABLE_LESSONS (
                $COL_ID TEXT PRIMARY KEY,
                $COL_COURSE_ID TEXT NOT NULL,
                $COL_ORDER_INDEX INTEGER NOT NULL,
                $COL_TITLE TEXT NOT NULL,
                $COL_VIDEO_PATH TEXT NOT NULL,
                $COL_SUB_AR_PATH TEXT,
                $COL_SUB_EN_PATH TEXT,
                $COL_AUDIO_AR_PATH TEXT,
                $COL_DURATION_MS INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY ($COL_COURSE_ID) REFERENCES $TABLE_COURSES($COL_ID) ON DELETE CASCADE
            )
        """)

        db.execSQL("""
            CREATE TABLE $TABLE_PROGRESS (
                $COL_LESSON_ID TEXT PRIMARY KEY,
                $COL_COURSE_ID TEXT NOT NULL,
                $COL_IS_COMPLETED INTEGER NOT NULL DEFAULT 0,
                $COL_LAST_POSITION_MS INTEGER NOT NULL DEFAULT 0,
                $COL_TOTAL_DURATION_MS INTEGER NOT NULL DEFAULT 0,
                $COL_LAST_WATCHED_TIMESTAMP INTEGER NOT NULL DEFAULT 0,
                $COL_IS_BOOKMARKED INTEGER NOT NULL DEFAULT 0
            )
        """)

        db.execSQL("""
            CREATE TABLE $TABLE_NOTES (
                $COL_ID TEXT PRIMARY KEY,
                $COL_LESSON_ID TEXT NOT NULL,
                $COL_COURSE_ID TEXT NOT NULL,
                $COL_NOTE_TIMESTAMP_MS INTEGER NOT NULL,
                $COL_NOTE_TEXT TEXT NOT NULL,
                $COL_CREATED_AT INTEGER NOT NULL,
                FOREIGN KEY ($COL_LESSON_ID) REFERENCES $TABLE_LESSONS($COL_ID) ON DELETE CASCADE
            )
        """)
        addSourceColumns(db)
        addClipColumns(db)
        createStudyPackTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            try {
                db.execSQL("ALTER TABLE $TABLE_PROGRESS ADD COLUMN $COL_IS_BOOKMARKED INTEGER NOT NULL DEFAULT 0")
            } catch (_: Exception) {}

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS $TABLE_NOTES (
                    $COL_ID TEXT PRIMARY KEY,
                    $COL_LESSON_ID TEXT NOT NULL,
                    $COL_COURSE_ID TEXT NOT NULL,
                    $COL_NOTE_TIMESTAMP_MS INTEGER NOT NULL,
                    $COL_NOTE_TEXT TEXT NOT NULL,
                    $COL_CREATED_AT INTEGER NOT NULL,
                    FOREIGN KEY ($COL_LESSON_ID) REFERENCES $TABLE_LESSONS($COL_ID) ON DELETE CASCADE
                )
            """)
        }

        if (oldVersion < 4) addSourceColumns(db)

        if (oldVersion < 3) {
            try {
                db.execSQL("ALTER TABLE $TABLE_LESSONS ADD COLUMN $COL_AUDIO_AR_PATH TEXT")
            } catch (_: Exception) {}
        }

        if (oldVersion < 5) {
            createStudyPackTables(db)
        }

        if (oldVersion < 6) {
            addClipColumns(db)
        }
    }

    private fun createStudyPackTables(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_STUDY_PACKS (
                $COL_LESSON_ID TEXT PRIMARY KEY,
                $COL_COURSE_ID TEXT NOT NULL,
                summary_markdown TEXT NOT NULL,
                quiz_json TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                FOREIGN KEY ($COL_LESSON_ID) REFERENCES $TABLE_LESSONS($COL_ID) ON DELETE CASCADE
            )
        """)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_FLASHCARDS (
                $COL_ID TEXT PRIMARY KEY,
                $COL_LESSON_ID TEXT NOT NULL,
                $COL_COURSE_ID TEXT NOT NULL,
                front TEXT NOT NULL,
                back TEXT NOT NULL,
                timestamp_ms INTEGER,
                repetitions INTEGER NOT NULL DEFAULT 0,
                interval_days INTEGER NOT NULL DEFAULT 0,
                ease_factor REAL NOT NULL DEFAULT 2.5,
                due_at INTEGER NOT NULL,
                FOREIGN KEY ($COL_LESSON_ID) REFERENCES $TABLE_LESSONS($COL_ID) ON DELETE CASCADE
            )
        """)
    }

    private fun addSourceColumns(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE courses ADD COLUMN source_type TEXT NOT NULL DEFAULT 'LOCAL'")
        db.execSQL("ALTER TABLE courses ADD COLUMN remote_id TEXT")
        db.execSQL("ALTER TABLE courses ADD COLUMN last_synced_at INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE lessons ADD COLUMN source_type TEXT NOT NULL DEFAULT 'LOCAL'")
        db.execSQL("ALTER TABLE lessons ADD COLUMN remote_id TEXT")
        db.execSQL("ALTER TABLE lessons ADD COLUMN available INTEGER NOT NULL DEFAULT 1")
    }

    private fun addClipColumns(db: SQLiteDatabase) {
        try {
            db.execSQL("ALTER TABLE lessons ADD COLUMN clip_start_ms INTEGER NOT NULL DEFAULT 0")
        } catch (_: Exception) {}
        try {
            db.execSQL("ALTER TABLE lessons ADD COLUMN clip_end_ms INTEGER")
        } catch (_: Exception) {}
    }

    private fun upsert(db: SQLiteDatabase, table: String, values: ContentValues) {
        if (db.insertWithOnConflict(table, null, values, SQLiteDatabase.CONFLICT_IGNORE) == -1L) {
            check(db.update(table, values, "id = ?", arrayOf(values.getAsString("id"))) == 1)
        }
    }

    private fun upsertRemoteLessonPreservingOfflineMedia(db: SQLiteDatabase, values: ContentValues) {
        if (db.insertWithOnConflict(TABLE_LESSONS, null, values, SQLiteDatabase.CONFLICT_IGNORE) == -1L) {
            val id = values.getAsString(COL_ID)
            val updateValues = ContentValues(values)
            db.rawQuery(
                "SELECT $COL_VIDEO_PATH, $COL_SUB_AR_PATH, $COL_SUB_EN_PATH, $COL_AUDIO_AR_PATH, $COL_DURATION_MS FROM $TABLE_LESSONS WHERE $COL_ID = ?",
                arrayOf(id)
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    val existingVideo = cursor.getString(0).orEmpty()
                    val existingSubAr = cursor.getString(1)
                    val existingSubEn = cursor.getString(2)
                    val existingAudioAr = cursor.getString(3)
                    val existingDuration = cursor.getLong(4)
                    if (existingVideo.isNotBlank() && updateValues.getAsString(COL_VIDEO_PATH).isNullOrBlank()) {
                        updateValues.put(COL_VIDEO_PATH, existingVideo)
                    }
                    if (!existingSubAr.isNullOrBlank() && updateValues.getAsString(COL_SUB_AR_PATH).isNullOrBlank()) {
                        updateValues.put(COL_SUB_AR_PATH, existingSubAr)
                    }
                    if (!existingSubEn.isNullOrBlank() && updateValues.getAsString(COL_SUB_EN_PATH).isNullOrBlank()) {
                        updateValues.put(COL_SUB_EN_PATH, existingSubEn)
                    }
                    if (!existingAudioAr.isNullOrBlank() && updateValues.getAsString(COL_AUDIO_AR_PATH).isNullOrBlank()) {
                        updateValues.put(COL_AUDIO_AR_PATH, existingAudioAr)
                    }
                    if (existingDuration > 0L && (updateValues.getAsLong(COL_DURATION_MS) ?: 0L) <= 0L) {
                        updateValues.put(COL_DURATION_MS, existingDuration)
                    }
                }
            }
            check(db.update(TABLE_LESSONS, updateValues, "$COL_ID = ?", arrayOf(id)) == 1)
        }
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    suspend fun insertCourseWithLessons(course: Course, lessons: List<Lesson>) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val courseValues = ContentValues().apply {
                put(COL_ID, course.id)
                put(COL_TITLE, course.title)
                put(COL_FOLDER_PATH, course.folderPath)
                put(COL_DATE_ADDED, course.dateAdded)
                put("source_type", course.sourceType.name)
                put("remote_id", course.remoteId)
                put("last_synced_at", course.lastSyncedAt)
            }
            upsert(db, TABLE_COURSES, courseValues)
            if (course.sourceType == SourceType.YOUTUBE) {
                db.execSQL("UPDATE lessons SET available = 0 WHERE course_id = ?", arrayOf(course.id))
            }

            for (lesson in lessons) {
                val lessonValues = ContentValues().apply {
                    put(COL_ID, lesson.id)
                    put(COL_COURSE_ID, lesson.courseId)
                    put(COL_ORDER_INDEX, lesson.orderIndex)
                    put(COL_TITLE, lesson.title)
                    put(COL_VIDEO_PATH, lesson.videoPath)
                    put(COL_SUB_AR_PATH, lesson.subArPath)
                    put(COL_SUB_EN_PATH, lesson.subEnPath)
                    put(COL_AUDIO_AR_PATH, lesson.audioArPath)
                    put(COL_DURATION_MS, lesson.durationMs)
                    put("source_type", lesson.sourceType.name)
                    put("remote_id", lesson.remoteId)
                    put("available", if (lesson.isAvailable) 1 else 0)
                    put("clip_start_ms", lesson.clipStartMs)
                    if (lesson.clipEndMs != null) put("clip_end_ms", lesson.clipEndMs) else putNull("clip_end_ms")
                }
                if (course.sourceType == SourceType.YOUTUBE) {
                    upsertRemoteLessonPreservingOfflineMedia(db, lessonValues)
                } else {
                    upsert(db, TABLE_LESSONS, lessonValues)
                }
            }
            if (course.sourceType == SourceType.YOUTUBE && course.remoteId?.startsWith("video_") == true && lessons.isNotEmpty()) {
                val keepIds = lessons.map { it.id }.toSet()
                val placeholders = keepIds.joinToString(",") { "?" }
                val args = arrayOf(course.id) + keepIds.toTypedArray()
                db.execSQL(
                    "DELETE FROM $TABLE_LESSONS WHERE $COL_COURSE_ID = ? AND $COL_ID NOT IN ($placeholders) AND COALESCE($COL_VIDEO_PATH, '') = '' AND NOT EXISTS (SELECT 1 FROM $TABLE_NOTES n WHERE n.$COL_LESSON_ID = $TABLE_LESSONS.$COL_ID)",
                    args
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        notifyChanged()
    }

    suspend fun getAllCourses(): List<Course> = withContext(Dispatchers.IO) {
        val db = readableDatabase
        val list = mutableListOf<Course>()

        val query = """
            SELECT c.$COL_ID, c.$COL_TITLE, c.$COL_FOLDER_PATH, c.$COL_DATE_ADDED,
                   COUNT(l.$COL_ID) as total_lessons,
                   SUM(CASE WHEN p.$COL_IS_COMPLETED = 1 THEN 1 ELSE 0 END) as completed_lessons, c.source_type, c.remote_id, c.last_synced_at
            FROM $TABLE_COURSES c
            LEFT JOIN $TABLE_LESSONS l ON c.$COL_ID = l.$COL_COURSE_ID
            LEFT JOIN $TABLE_PROGRESS p ON l.$COL_ID = p.$COL_LESSON_ID
            GROUP BY c.$COL_ID
            ORDER BY c.$COL_DATE_ADDED DESC
        """

        val cursor = db.rawQuery(query, null)
        cursor.use {
            while (it.moveToNext()) {
                val id = it.getString(0)
                val title = it.getString(1)
                val path = it.getString(2)
                val date = it.getLong(3)
                val total = it.getInt(4)
                val completed = it.getInt(5)

                list.add(
                    Course(
                        id = id,
                        title = title,
                        folderPath = path,
                        dateAdded = date,
                        totalLessons = total,
                        completedLessons = completed,
                        sourceType = SourceType.valueOf(it.getString(6)),
                        remoteId = it.getString(7),
                        lastSyncedAt = it.getLong(8)
                    )
                )
            }
        }
        list
    }

    suspend fun getLessonsForCourse(courseId: String): List<Lesson> = withContext(Dispatchers.IO) {
        val db = readableDatabase
        val list = mutableListOf<Lesson>()

        val query = """
            SELECT l.$COL_ID, l.$COL_COURSE_ID, l.$COL_ORDER_INDEX, l.$COL_TITLE,
                   l.$COL_VIDEO_PATH, l.$COL_SUB_AR_PATH, l.$COL_SUB_EN_PATH, l.$COL_AUDIO_AR_PATH, l.$COL_DURATION_MS,
                   COALESCE(p.$COL_IS_COMPLETED, 0) as is_completed,
                   COALESCE(p.$COL_LAST_POSITION_MS, 0) as last_position_ms,
                   COALESCE(p.$COL_IS_BOOKMARKED, 0) as is_bookmarked, l.source_type, l.remote_id, l.available,
                   l.clip_start_ms, l.clip_end_ms
            FROM $TABLE_LESSONS l
            LEFT JOIN $TABLE_PROGRESS p ON l.$COL_ID = p.$COL_LESSON_ID
            WHERE l.$COL_COURSE_ID = ?
            ORDER BY l.$COL_ORDER_INDEX ASC
        """

        val cursor = db.rawQuery(query, arrayOf(courseId))
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    Lesson(
                        id = it.getString(0),
                        courseId = it.getString(1),
                        orderIndex = it.getInt(2),
                        title = it.getString(3),
                        videoPath = it.getString(4),
                        subArPath = it.getString(5),
                        subEnPath = it.getString(6),
                        audioArPath = it.getString(7),
                        durationMs = it.getLong(8),
                        isCompleted = it.getInt(9) == 1,
                        lastPositionMs = it.getLong(10),
                        isBookmarked = it.getInt(11) == 1,
                        sourceType = SourceType.valueOf(it.getString(12)),
                        remoteId = it.getString(13),
                        isAvailable = it.getInt(14) == 1,
                        clipStartMs = it.getLong(15),
                        clipEndMs = if (it.isNull(16)) null else it.getLong(16)
                    )
                )
            }
        }
        list
    }

    suspend fun setLessonArabicAudio(lessonId: String, audioPath: String?) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_AUDIO_AR_PATH, audioPath)
        }
        db.update(TABLE_LESSONS, values, "$COL_ID = ?", arrayOf(lessonId))
        notifyChanged()
    }

    suspend fun setLessonSubtitles(
        lessonId: String,
        subArPath: String?,
        subEnPath: String?
    ) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_SUB_AR_PATH, subArPath)
            put(COL_SUB_EN_PATH, subEnPath)
        }
        db.update(TABLE_LESSONS, values, "$COL_ID = ?", arrayOf(lessonId))
        notifyChanged()
    }

    suspend fun setLessonOfflineMedia(
        lessonId: String,
        videoPath: String,
        subArPath: String?,
        subEnPath: String?,
        durationMs: Long? = null
    ) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_VIDEO_PATH, videoPath)
            put(COL_SUB_AR_PATH, subArPath)
            put(COL_SUB_EN_PATH, subEnPath)
            if (durationMs != null && durationMs > 0L) {
                put(COL_DURATION_MS, durationMs)
            }
        }
        db.update(TABLE_LESSONS, values, "$COL_ID = ?", arrayOf(lessonId))
        notifyChanged()
    }

    suspend fun updateLessonProgress(
        lessonId: String,
        courseId: String,
        positionMs: Long,
        totalDurationMs: Long,
        autoMarkCompleteThreshold: Float = 0.90f
    ) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        val isCompleted = if (totalDurationMs > 0 && (positionMs.toFloat() / totalDurationMs) >= autoMarkCompleteThreshold) 1 else 0

        val existingCursor = db.rawQuery(
            "SELECT $COL_IS_COMPLETED, $COL_IS_BOOKMARKED FROM $TABLE_PROGRESS WHERE $COL_LESSON_ID = ?",
            arrayOf(lessonId)
        )
        var wasCompleted = false
        var wasBookmarked = 0
        existingCursor.use {
            if (it.moveToFirst()) {
                wasCompleted = it.getInt(0) == 1
                wasBookmarked = it.getInt(1)
            }
        }

        val values = ContentValues().apply {
            put(COL_LESSON_ID, lessonId)
            put(COL_COURSE_ID, courseId)
            put(COL_LAST_POSITION_MS, positionMs)
            put(COL_TOTAL_DURATION_MS, totalDurationMs)
            put(COL_LAST_WATCHED_TIMESTAMP, System.currentTimeMillis())
            put(COL_IS_COMPLETED, if (wasCompleted || isCompleted == 1) 1 else 0)
            put(COL_IS_BOOKMARKED, wasBookmarked)
        }

        db.insertWithOnConflict(TABLE_PROGRESS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        notifyChanged()
    }

    suspend fun toggleLessonCompleted(lessonId: String, courseId: String, completed: Boolean) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        val existingCursor = db.rawQuery(
            "SELECT $COL_LAST_POSITION_MS, $COL_TOTAL_DURATION_MS, $COL_IS_BOOKMARKED FROM $TABLE_PROGRESS WHERE $COL_LESSON_ID = ?",
            arrayOf(lessonId)
        )
        var lastPos = 0L
        var totalDur = 0L
        var bookmarked = 0
        existingCursor.use {
            if (it.moveToFirst()) {
                lastPos = it.getLong(0)
                totalDur = it.getLong(1)
                bookmarked = it.getInt(2)
            }
        }

        val values = ContentValues().apply {
            put(COL_LESSON_ID, lessonId)
            put(COL_COURSE_ID, courseId)
            put(COL_IS_COMPLETED, if (completed) 1 else 0)
            put(COL_LAST_POSITION_MS, lastPos)
            put(COL_TOTAL_DURATION_MS, totalDur)
            put(COL_IS_BOOKMARKED, bookmarked)
            put(COL_LAST_WATCHED_TIMESTAMP, System.currentTimeMillis())
        }
        db.insertWithOnConflict(TABLE_PROGRESS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        notifyChanged()
    }

    suspend fun toggleLessonBookmark(lessonId: String, courseId: String, isBookmarked: Boolean) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        val existingCursor = db.rawQuery(
            "SELECT $COL_IS_COMPLETED, $COL_LAST_POSITION_MS, $COL_TOTAL_DURATION_MS FROM $TABLE_PROGRESS WHERE $COL_LESSON_ID = ?",
            arrayOf(lessonId)
        )
        var isCompleted = 0
        var lastPos = 0L
        var totalDur = 0L
        existingCursor.use {
            if (it.moveToFirst()) {
                isCompleted = it.getInt(0)
                lastPos = it.getLong(1)
                totalDur = it.getLong(2)
            }
        }

        val values = ContentValues().apply {
            put(COL_LESSON_ID, lessonId)
            put(COL_COURSE_ID, courseId)
            put(COL_IS_BOOKMARKED, if (isBookmarked) 1 else 0)
            put(COL_IS_COMPLETED, isCompleted)
            put(COL_LAST_POSITION_MS, lastPos)
            put(COL_TOTAL_DURATION_MS, totalDur)
            put(COL_LAST_WATCHED_TIMESTAMP, System.currentTimeMillis())
        }
        db.insertWithOnConflict(TABLE_PROGRESS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        notifyChanged()
    }

    suspend fun getResumeLesson(): ResumeLessonInfo? = withContext(Dispatchers.IO) {
        val db = readableDatabase
        val query = """
            SELECT p.$COL_LESSON_ID, p.$COL_COURSE_ID, p.$COL_LAST_POSITION_MS, p.$COL_TOTAL_DURATION_MS,
                   c.$COL_TITLE as course_title,
                   l.$COL_ORDER_INDEX, l.$COL_TITLE as lesson_title,
                   l.$COL_VIDEO_PATH, l.$COL_SUB_AR_PATH, l.$COL_SUB_EN_PATH, l.$COL_AUDIO_AR_PATH, l.$COL_DURATION_MS,
                   p.$COL_IS_COMPLETED, p.$COL_IS_BOOKMARKED, l.source_type, l.remote_id, l.available
            FROM $TABLE_PROGRESS p
            JOIN $TABLE_COURSES c ON p.$COL_COURSE_ID = c.$COL_ID
            JOIN $TABLE_LESSONS l ON p.$COL_LESSON_ID = l.$COL_ID
            ORDER BY p.$COL_LAST_WATCHED_TIMESTAMP DESC
            LIMIT 1
        """

        val cursor = db.rawQuery(query, null)
        cursor.use {
            if (it.moveToFirst()) {
                val lessonId = it.getString(0)
                val courseId = it.getString(1)
                val lastPos = it.getLong(2)
                val totalDur = it.getLong(3)
                val courseTitle = it.getString(4)
                val order = it.getInt(5)
                val lessonTitle = it.getString(6)
                val videoPath = it.getString(7)
                val subAr = it.getString(8)
                val subEn = it.getString(9)
                val audioAr = it.getString(10)
                val dur = it.getLong(11)
                val isComp = it.getInt(12) == 1
                val isBook = it.getInt(13) == 1

                val lesson = Lesson(
                    id = lessonId,
                    courseId = courseId,
                    orderIndex = order,
                    title = lessonTitle,
                    videoPath = videoPath,
                    subArPath = subAr,
                    subEnPath = subEn,
                    audioArPath = audioAr,
                    durationMs = dur,
                    isCompleted = isComp,
                    lastPositionMs = lastPos,
                    isBookmarked = isBook,
                    sourceType = SourceType.valueOf(it.getString(14)),
                    remoteId = it.getString(15),
                    isAvailable = it.getInt(16) == 1
                )

                return@withContext ResumeLessonInfo(
                    courseId = courseId,
                    courseTitle = courseTitle,
                    lesson = lesson,
                    lastPositionMs = lastPos,
                    totalDurationMs = totalDur
                )
            }
        }
        null
    }

    // --- Note CRUD Operations ---
    suspend fun insertNote(note: LessonNote) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_ID, note.id)
            put(COL_LESSON_ID, note.lessonId)
            put(COL_COURSE_ID, note.courseId)
            put(COL_NOTE_TIMESTAMP_MS, note.timestampMs)
            put(COL_NOTE_TEXT, note.noteText)
            put(COL_CREATED_AT, note.createdAt)
        }
        db.insertWithOnConflict(TABLE_NOTES, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        notifyChanged()
    }

    suspend fun deleteNote(noteId: String) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.delete(TABLE_NOTES, "$COL_ID = ?", arrayOf(noteId))
        notifyChanged()
    }

    suspend fun getNotesForLesson(lessonId: String): List<LessonNote> = withContext(Dispatchers.IO) {
        val db = readableDatabase
        val notes = mutableListOf<LessonNote>()
        val cursor = db.rawQuery(
            "SELECT $COL_ID, $COL_LESSON_ID, $COL_COURSE_ID, $COL_NOTE_TIMESTAMP_MS, $COL_NOTE_TEXT, $COL_CREATED_AT FROM $TABLE_NOTES WHERE $COL_LESSON_ID = ? ORDER BY $COL_NOTE_TIMESTAMP_MS ASC",
            arrayOf(lessonId)
        )
        cursor.use {
            while (it.moveToNext()) {
                notes.add(
                    LessonNote(
                        id = it.getString(0),
                        lessonId = it.getString(1),
                        courseId = it.getString(2),
                        timestampMs = it.getLong(3),
                        noteText = it.getString(4),
                        createdAt = it.getLong(5)
                    )
                )
            }
        }
        notes
    }

    // --- Study Stats & Learning Analytics ---
    suspend fun getStudyStats(): StudyStats = withContext(Dispatchers.IO) {
        val db = readableDatabase

        var totalWatchedMinutes = 0L
        var completedLessons = 0
        var totalLessons = 0
        var bookmarkedLessons = 0
        var totalCourses = 0

        // Total watched minutes & completed lessons & bookmarked
        val progressCursor = db.rawQuery(
            "SELECT SUM($COL_LAST_POSITION_MS), SUM(CASE WHEN $COL_IS_COMPLETED = 1 THEN 1 ELSE 0 END), SUM(CASE WHEN $COL_IS_BOOKMARKED = 1 THEN 1 ELSE 0 END) FROM $TABLE_PROGRESS",
            null
        )
        progressCursor.use {
            if (it.moveToFirst()) {
                val totalMs = it.getLong(0)
                totalWatchedMinutes = totalMs / (1000 * 60)
                completedLessons = it.getInt(1)
                bookmarkedLessons = it.getInt(2)
            }
        }

        // Total lessons count
        val lessonsCursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_LESSONS", null)
        lessonsCursor.use {
            if (it.moveToFirst()) {
                totalLessons = it.getInt(0)
            }
        }

        // Total courses count
        val coursesCursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_COURSES", null)
        coursesCursor.use {
            if (it.moveToFirst()) {
                totalCourses = it.getInt(0)
            }
        }

        // Streak calculation (consecutive active days)
        val streakDays = calculateStreakDays(db)

        StudyStats(
            totalWatchedMinutes = totalWatchedMinutes,
            completedLessonsCount = completedLessons,
            totalLessonsCount = totalLessons,
            bookmarkedLessonsCount = bookmarkedLessons,
            totalCoursesCount = totalCourses,
            streakDays = streakDays
        )
    }

    private fun calculateStreakDays(db: SQLiteDatabase): Int {
        val query = """
            SELECT DISTINCT strftime('%Y-%m-%d', datetime($COL_LAST_WATCHED_TIMESTAMP / 1000, 'unixepoch', 'localtime')) as study_date
            FROM $TABLE_PROGRESS
            WHERE $COL_LAST_WATCHED_TIMESTAMP > 0
            ORDER BY study_date DESC
        """
        val cursor = db.rawQuery(query, null)
        val dates = mutableListOf<String>()
        cursor.use {
            while (it.moveToNext()) {
                it.getString(0)?.let { dateStr -> dates.add(dateStr) }
            }
        }

        if (dates.isEmpty()) return 0

        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val todayStr = sdf.format(Date())
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -1)
        val yesterdayStr = sdf.format(cal.time)

        if (!dates.contains(todayStr) && !dates.contains(yesterdayStr)) {
            return 0
        }

        var streak = 0
        val checkCal = Calendar.getInstance()
        if (!dates.contains(todayStr)) {
            checkCal.add(Calendar.DAY_OF_YEAR, -1)
        }

        while (true) {
            val dateToCheck = sdf.format(checkCal.time)
            if (dates.contains(dateToCheck)) {
                streak++
                checkCal.add(Calendar.DAY_OF_YEAR, -1)
            } else {
                break
            }
        }

        return maxOf(1, streak)
    }

    // --- JSON Backup & Restore ---
    suspend fun exportProgressJson(): String = withContext(Dispatchers.IO) {
        val db = readableDatabase
        val root = JSONObject()
        root.put("version", 2)
        root.put("exportDate", System.currentTimeMillis())

        val progressArray = JSONArray()
        val progCursor = db.rawQuery(
            "SELECT $COL_LESSON_ID, $COL_COURSE_ID, $COL_IS_COMPLETED, $COL_LAST_POSITION_MS, $COL_TOTAL_DURATION_MS, $COL_IS_BOOKMARKED, $COL_LAST_WATCHED_TIMESTAMP FROM $TABLE_PROGRESS",
            null
        )
        progCursor.use {
            while (it.moveToNext()) {
                val item = JSONObject().apply {
                    put("lessonId", it.getString(0))
                    put("courseId", it.getString(1))
                    put("isCompleted", it.getInt(2) == 1)
                    put("lastPositionMs", it.getLong(3))
                    put("totalDurationMs", it.getLong(4))
                    put("isBookmarked", it.getInt(5) == 1)
                    put("lastWatchedTimestamp", it.getLong(6))
                }
                progressArray.put(item)
            }
        }
        root.put("progress", progressArray)

        val notesArray = JSONArray()
        val noteCursor = db.rawQuery(
            "SELECT $COL_ID, $COL_LESSON_ID, $COL_COURSE_ID, $COL_NOTE_TIMESTAMP_MS, $COL_NOTE_TEXT, $COL_CREATED_AT FROM $TABLE_NOTES",
            null
        )
        noteCursor.use {
            while (it.moveToNext()) {
                val item = JSONObject().apply {
                    put("id", it.getString(0))
                    put("lessonId", it.getString(1))
                    put("courseId", it.getString(2))
                    put("timestampMs", it.getLong(3))
                    put("noteText", it.getString(4))
                    put("createdAt", it.getLong(5))
                }
                notesArray.put(item)
            }
        }
        root.put("notes", notesArray)
        root.put("youtubeCourses", JSONArray().apply {
            getAllCourses().filter { it.sourceType == SourceType.YOUTUBE }.forEach { course ->
                put(JSONObject().put("id", course.id).put("title", course.title).put("playlistId", course.remoteId)
                    .put("dateAdded", course.dateAdded).put("lastSyncedAt", course.lastSyncedAt)
                    .put("lessons", JSONArray().apply {
                        getLessonsForCourse(course.id).forEach { lesson ->
                            put(JSONObject().put("id", lesson.id).put("title", lesson.title).put("videoId", lesson.remoteId)
                                .put("order", lesson.orderIndex).put("durationMs", lesson.durationMs).put("available", lesson.isAvailable))
                        }
                    }))
            }
        })
        root.toString(2)
    }

    suspend fun importProgressJson(jsonString: String): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            require(jsonString.length <= 10_000_000) { "Backup exceeds 10 MB" }
            val root = JSONObject(jsonString)
            require(root.optInt("version", 1) in 1..2) { "Unsupported backup version" }
            val progressArray = root.optJSONArray("progress") ?: JSONArray()
            val notesArray = root.optJSONArray("notes") ?: JSONArray()
            require(progressArray.length() <= 50000 && notesArray.length() <= 50000)

            val db = writableDatabase
            db.beginTransaction()
            try {
                restoreYouTubeCourses(db, root.optJSONArray("youtubeCourses") ?: JSONArray())
                for (i in 0 until progressArray.length()) {
                    val p = progressArray.getJSONObject(i)
                    requireLessonOwner(db, p.getString("lessonId"), p.getString("courseId"))
                    require(p.optLong("lastPositionMs", 0L) >= 0 && p.optLong("totalDurationMs", 0L) >= 0)
                    val values = ContentValues().apply {
                        put(COL_LESSON_ID, p.getString("lessonId"))
                        put(COL_COURSE_ID, p.getString("courseId"))
                        put(COL_IS_COMPLETED, if (p.optBoolean("isCompleted", false)) 1 else 0)
                        put(COL_LAST_POSITION_MS, p.optLong("lastPositionMs", 0L))
                        put(COL_TOTAL_DURATION_MS, p.optLong("totalDurationMs", 0L))
                        put(COL_IS_BOOKMARKED, if (p.optBoolean("isBookmarked", false)) 1 else 0)
                        put(COL_LAST_WATCHED_TIMESTAMP, p.optLong("lastWatchedTimestamp", System.currentTimeMillis()))
                    }
                    db.insertWithOnConflict(TABLE_PROGRESS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
                }

                for (i in 0 until notesArray.length()) {
                    val n = notesArray.getJSONObject(i)
                    requireLessonOwner(db, n.getString("lessonId"), n.getString("courseId"))
                    require(n.getString("noteText").length <= 10000 && n.getLong("timestampMs") >= 0)
                    val values = ContentValues().apply {
                        put(COL_ID, n.getString("id"))
                        put(COL_LESSON_ID, n.getString("lessonId"))
                        put(COL_COURSE_ID, n.getString("courseId"))
                        put(COL_NOTE_TIMESTAMP_MS, n.getLong("timestampMs"))
                        put(COL_NOTE_TEXT, n.getString("noteText"))
                        put(COL_CREATED_AT, n.optLong("createdAt", System.currentTimeMillis()))
                    }
                    db.insertWithOnConflict(TABLE_NOTES, null, values, SQLiteDatabase.CONFLICT_REPLACE)
                }

                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            notifyChanged()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun requireLessonOwner(db: SQLiteDatabase, lessonId: String, courseId: String) {
        db.rawQuery("SELECT 1 FROM lessons WHERE id = ? AND course_id = ?", arrayOf(lessonId, courseId)).use {
            require(it.moveToFirst()) { "Import the original local course before restoring its notes/progress" }
        }
    }

    private fun restoreYouTubeCourses(db: SQLiteDatabase, courses: JSONArray) {
        require(courses.length() <= 1000)
        for (i in 0 until courses.length()) {
            val c = courses.getJSONObject(i); val playlist = c.getString("playlistId"); val id = c.getString("id")
            require(playlist.matches(Regex("[A-Za-z0-9_-]{5,150}")) && id == "youtube:$playlist")
            require(c.getString("title").length <= 1000)
            val course = ContentValues().apply {
                put("id", id); put("title", c.getString("title")); put("folder_path", "")
                put("date_added", c.getLong("dateAdded")); put("source_type", "YOUTUBE"); put("remote_id", playlist)
                put("last_synced_at", c.optLong("lastSyncedAt", 0L))
            }
            val incomingSync = c.optLong("lastSyncedAt", 0L)
            val existingSync = db.rawQuery("SELECT last_synced_at FROM courses WHERE id = ?", arrayOf(id)).use { if (it.moveToFirst()) it.getLong(0) else -1L }
            if (existingSync <= incomingSync) upsert(db, TABLE_COURSES, course)
            val lessons = c.getJSONArray("lessons"); require(lessons.length() <= 10000)
            for (j in 0 until lessons.length()) {
                val l = lessons.getJSONObject(j); val lessonId = l.getString("id")
                require(lessonId.startsWith("$id:") && lessonId.removePrefix("$id:").matches(Regex("[A-Za-z0-9_-]{1,200}")))
                val video = l.optString("videoId").takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
                require(l.getString("title").length <= 1000 && l.getInt("order") >= 0 && l.optLong("durationMs", 0L) >= 0)
                val values = ContentValues().apply {
                    put("id", lessonId); put("course_id", id); put("order_index", l.getInt("order")); put("title", l.getString("title"))
                    put("video_path", ""); put("duration_ms", l.optLong("durationMs", 0L)); put("source_type", "YOUTUBE")
                    put("remote_id", video); put("available", if (video != null && l.optBoolean("available", true)) 1 else 0)
                }
                if (existingSync <= incomingSync) upsertRemoteLessonPreservingOfflineMedia(db, values)
                else db.insertWithOnConflict(TABLE_LESSONS, null, values, SQLiteDatabase.CONFLICT_IGNORE)
            }
        }
    }

    suspend fun expireRemoteMetadata() = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
        writableDatabase.beginTransaction()
        try {
            writableDatabase.execSQL("UPDATE lessons SET title = 'YouTube lesson (refresh required)', available = 0, duration_ms = 0 WHERE course_id IN (SELECT id FROM courses WHERE source_type = 'YOUTUBE' AND last_synced_at < ?)", arrayOf(cutoff))
            writableDatabase.execSQL("UPDATE courses SET title = 'YouTube course (refresh required)', last_synced_at = 0 WHERE source_type = 'YOUTUBE' AND last_synced_at < ?", arrayOf(cutoff))
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }

    // --- Study Packs & Spaced-Repetition Flashcards (SQLite v5) ---
    suspend fun saveStudyPack(
        pack: LessonStudyPack,
        flashcards: List<Flashcard>
    ) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        val quizJson = com.ilearn.app.tutor.StudyPackGenerator.serializeQuizJson(pack.quizQuestions)
        db.beginTransaction()
        try {
            val packValues = ContentValues().apply {
                put(COL_LESSON_ID, pack.lessonId)
                put(COL_COURSE_ID, pack.courseId)
                put("summary_markdown", pack.summaryMarkdown)
                put("quiz_json", quizJson)
                put("created_at", pack.createdAt)
            }
            db.insertWithOnConflict(TABLE_STUDY_PACKS, null, packValues, SQLiteDatabase.CONFLICT_REPLACE)

            db.delete(TABLE_FLASHCARDS, "$COL_LESSON_ID = ?", arrayOf(pack.lessonId))
            flashcards.forEach { card ->
                val cardValues = ContentValues().apply {
                    put(COL_ID, card.id)
                    put(COL_LESSON_ID, card.lessonId)
                    put(COL_COURSE_ID, card.courseId)
                    put("front", card.front)
                    put("back", card.back)
                    if (card.timestampMs != null) put("timestamp_ms", card.timestampMs) else putNull("timestamp_ms")
                    put("repetitions", card.repetitions)
                    put("interval_days", card.intervalDays)
                    put("ease_factor", card.easeFactor)
                    put("due_at", card.dueAt)
                }
                db.insertWithOnConflict(TABLE_FLASHCARDS, null, cardValues, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        notifyChanged()
    }

    suspend fun getStudyPack(lessonId: String): LessonStudyPack? = withContext(Dispatchers.IO) {
        val db = readableDatabase
        db.rawQuery(
            "SELECT $COL_LESSON_ID, $COL_COURSE_ID, summary_markdown, quiz_json, created_at FROM $TABLE_STUDY_PACKS WHERE $COL_LESSON_ID = ?",
            arrayOf(lessonId)
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@withContext null
            LessonStudyPack(
                lessonId = cursor.getString(0),
                courseId = cursor.getString(1),
                summaryMarkdown = cursor.getString(2),
                quizQuestions = com.ilearn.app.tutor.StudyPackGenerator.parseQuizJson(cursor.getString(3)),
                createdAt = cursor.getLong(4)
            )
        }
    }

    suspend fun getFlashcardsForLesson(lessonId: String): List<Flashcard> = withContext(Dispatchers.IO) {
        queryFlashcards("SELECT $COL_ID, $COL_LESSON_ID, $COL_COURSE_ID, front, back, timestamp_ms, repetitions, interval_days, ease_factor, due_at FROM $TABLE_FLASHCARDS WHERE $COL_LESSON_ID = ? ORDER BY due_at ASC", arrayOf(lessonId))
    }

    suspend fun getDueFlashcards(courseId: String? = null, nowMs: Long = System.currentTimeMillis()): List<Flashcard> = withContext(Dispatchers.IO) {
        val sql = if (courseId != null) {
            "SELECT $COL_ID, $COL_LESSON_ID, $COL_COURSE_ID, front, back, timestamp_ms, repetitions, interval_days, ease_factor, due_at FROM $TABLE_FLASHCARDS WHERE $COL_COURSE_ID = ? AND due_at <= ? ORDER BY due_at ASC"
        } else {
            "SELECT $COL_ID, $COL_LESSON_ID, $COL_COURSE_ID, front, back, timestamp_ms, repetitions, interval_days, ease_factor, due_at FROM $TABLE_FLASHCARDS WHERE due_at <= ? ORDER BY due_at ASC"
        }
        val args = if (courseId != null) arrayOf(courseId, nowMs.toString()) else arrayOf(nowMs.toString())
        queryFlashcards(sql, args)
    }

    suspend fun getDueFlashcardsCount(courseId: String? = null, nowMs: Long = System.currentTimeMillis()): Int = withContext(Dispatchers.IO) {
        val sql = if (courseId != null) {
            "SELECT COUNT(*) FROM $TABLE_FLASHCARDS WHERE $COL_COURSE_ID = ? AND due_at <= ?"
        } else {
            "SELECT COUNT(*) FROM $TABLE_FLASHCARDS WHERE due_at <= ?"
        }
        val args = if (courseId != null) arrayOf(courseId, nowMs.toString()) else arrayOf(nowMs.toString())
        readableDatabase.rawQuery(sql, args).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    suspend fun recordFlashcardReview(cardId: String, quality: Int, nowMs: Long = System.currentTimeMillis()) = withContext(Dispatchers.IO) {
        val existing = queryFlashcards(
            "SELECT $COL_ID, $COL_LESSON_ID, $COL_COURSE_ID, front, back, timestamp_ms, repetitions, interval_days, ease_factor, due_at FROM $TABLE_FLASHCARDS WHERE $COL_ID = ?",
            arrayOf(cardId)
        ).firstOrNull() ?: return@withContext
        val updated = com.ilearn.app.tutor.SpacedRepetition.review(existing, quality, nowMs)
        val values = ContentValues().apply {
            put("repetitions", updated.repetitions)
            put("interval_days", updated.intervalDays)
            put("ease_factor", updated.easeFactor)
            put("due_at", updated.dueAt)
        }
        writableDatabase.update(TABLE_FLASHCARDS, values, "$COL_ID = ?", arrayOf(cardId))
        notifyChanged()
    }

    private fun queryFlashcards(sql: String, args: Array<String>): List<Flashcard> {
        val list = mutableListOf<Flashcard>()
        readableDatabase.rawQuery(sql, args).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(
                    Flashcard(
                        id = cursor.getString(0),
                        lessonId = cursor.getString(1),
                        courseId = cursor.getString(2),
                        front = cursor.getString(3),
                        back = cursor.getString(4),
                        timestampMs = if (cursor.isNull(5)) null else cursor.getLong(5),
                        repetitions = cursor.getInt(6),
                        intervalDays = cursor.getInt(7),
                        easeFactor = cursor.getDouble(8),
                        dueAt = cursor.getLong(9)
                    )
                )
            }
        }
        return list
    }

    suspend fun deleteCourse(courseId: String) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(TABLE_FLASHCARDS, "$COL_COURSE_ID = ?", arrayOf(courseId))
            db.delete(TABLE_STUDY_PACKS, "$COL_COURSE_ID = ?", arrayOf(courseId))
            db.delete(TABLE_NOTES, "$COL_COURSE_ID = ?", arrayOf(courseId))
            db.delete(TABLE_PROGRESS, "$COL_COURSE_ID = ?", arrayOf(courseId))
            db.delete(TABLE_LESSONS, "$COL_COURSE_ID = ?", arrayOf(courseId))
            db.delete(TABLE_COURSES, "$COL_ID = ?", arrayOf(courseId))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        notifyChanged()
    }
}
