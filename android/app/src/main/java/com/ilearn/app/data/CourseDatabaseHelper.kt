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
class CourseDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "ilearn.db"
        private const val DATABASE_VERSION = 3

        // Tables
        private const val TABLE_COURSES = "courses"
        private const val TABLE_LESSONS = "lessons"
        private const val TABLE_PROGRESS = "student_progress"
        private const val TABLE_NOTES = "lesson_notes"

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

        if (oldVersion < 3) {
            try {
                db.execSQL("ALTER TABLE $TABLE_LESSONS ADD COLUMN $COL_AUDIO_AR_PATH TEXT")
            } catch (_: Exception) {}
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
            }
            db.insertWithOnConflict(TABLE_COURSES, null, courseValues, SQLiteDatabase.CONFLICT_REPLACE)

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
                }
                db.insertWithOnConflict(TABLE_LESSONS, null, lessonValues, SQLiteDatabase.CONFLICT_REPLACE)
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
                   SUM(CASE WHEN p.$COL_IS_COMPLETED = 1 THEN 1 ELSE 0 END) as completed_lessons
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
                        completedLessons = completed
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
                   COALESCE(p.$COL_IS_BOOKMARKED, 0) as is_bookmarked
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
                        isBookmarked = it.getInt(11) == 1
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
                   p.$COL_IS_COMPLETED, p.$COL_IS_BOOKMARKED
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
                    isBookmarked = isBook
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
        root.put("version", 1)
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

        root.toString(2)
    }

    suspend fun importProgressJson(jsonString: String): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val root = JSONObject(jsonString)
            val progressArray = root.optJSONArray("progress") ?: JSONArray()
            val notesArray = root.optJSONArray("notes") ?: JSONArray()

            val db = writableDatabase
            db.beginTransaction()
            try {
                for (i in 0 until progressArray.length()) {
                    val p = progressArray.getJSONObject(i)
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

    suspend fun deleteCourse(courseId: String) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.beginTransaction()
        try {
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
