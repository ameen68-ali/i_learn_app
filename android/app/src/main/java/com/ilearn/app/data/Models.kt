package com.ilearn.app.data

/**
 * Domain model representing a course imported from a .zip or .rar archive.
 */
enum class SourceType { LOCAL, YOUTUBE }

data class Course(
    val id: String,
    val title: String,
    val folderPath: String,
    val dateAdded: Long = System.currentTimeMillis(),
    val totalLessons: Int = 0,
    val completedLessons: Int = 0,
    val sourceType: SourceType = SourceType.LOCAL,
    val remoteId: String? = null,
    val lastSyncedAt: Long = 0L
) {
    val progressPercentage: Int
        get() = if (totalLessons > 0) ((completedLessons * 100) / totalLessons).coerceIn(0, 100) else 0

    val isCompleted: Boolean
        get() = totalLessons > 0 && completedLessons >= totalLessons
}

/**
 * Audio track selection mode: Original English vs Custom Arabic Voiceover.
 */
enum class AudioTrackMode(val labelAr: String) {
    ORIGINAL("🇬🇧 الصوت الإنجليزي الأصلي"),
    ARABIC("🇸🇦 الدبلجة العربية (Arabic Dub)")
}

/**
 * Domain model representing an individual video lesson.
 */
data class Lesson(
    val id: String,
    val courseId: String,
    val orderIndex: Int,
    val title: String,
    val videoPath: String,
    val subArPath: String? = null,
    val subEnPath: String? = null,
    val audioArPath: String? = null,
    val durationMs: Long = 0L,
    val isCompleted: Boolean = false,
    val lastPositionMs: Long = 0L,
    val isBookmarked: Boolean = false,
    val sourceType: SourceType = SourceType.LOCAL,
    val remoteId: String? = null,
    val isAvailable: Boolean = true
) {
    val hasArabicSubtitles: Boolean
        get() = !subArPath.isNullOrBlank()

    val hasEnglishSubtitles: Boolean
        get() = !subEnPath.isNullOrBlank()

    val hasArabicAudio: Boolean
        get() = !audioArPath.isNullOrBlank()

    val hasOfflineVideo: Boolean
        get() = videoPath.isNotBlank() && java.io.File(videoPath).isFile
}

/**
 * Information needed to render the "Continue Learning" hero card on the main screen.
 */
data class ResumeLessonInfo(
    val courseId: String,
    val courseTitle: String,
    val lesson: Lesson,
    val lastPositionMs: Long,
    val totalDurationMs: Long
)

/**
 * A student note attached to a specific timestamp within a lesson.
 */
data class LessonNote(
    val id: String,
    val lessonId: String,
    val courseId: String,
    val timestampMs: Long,
    val noteText: String,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * High-level learning analytics and metrics for the student.
 */
data class StudyStats(
    val totalWatchedMinutes: Long = 0L,
    val completedLessonsCount: Int = 0,
    val totalLessonsCount: Int = 0,
    val bookmarkedLessonsCount: Int = 0,
    val totalCoursesCount: Int = 0,
    val streakDays: Int = 1
) {
    val formattedStudyTime: String
        get() {
            val hours = totalWatchedMinutes / 60
            val mins = totalWatchedMinutes % 60
            return if (hours > 0) "${hours} ساعة و ${mins} دقيقة" else "${mins} دقيقة"
        }

    val overallProgressPercentage: Int
        get() = if (totalLessonsCount > 0) ((completedLessonsCount * 100) / totalLessonsCount).coerceIn(0, 100) else 0
}

/**
 * Data structure used for exporting and restoring student progress as JSON.
 */
data class BackupProgressEntry(
    val lessonId: String,
    val courseId: String,
    val isCompleted: Boolean,
    val lastPositionMs: Long,
    val totalDurationMs: Long,
    val isBookmarked: Boolean,
    val lastWatchedTimestamp: Long
)

data class BackupPayload(
    val version: Int = 1,
    val exportDate: Long = System.currentTimeMillis(),
    val progress: List<BackupProgressEntry>,
    val notes: List<LessonNote>
)
