package com.ilearn.app.player

import com.ilearn.app.data.Lesson
import kotlinx.coroutines.flow.StateFlow

data class LessonPlaybackState(val lessonId: String = "", val ready: Boolean = false, val playing: Boolean = false,
    val positionMs: Long = 0, val durationMs: Long = 0, val error: String? = null)

interface LessonPlaybackSession {
    val state: StateFlow<LessonPlaybackState>
    fun play()
    fun pause()
    fun seek(positionMs: Long)
    fun sample()
    fun release()
}

class LocalPlaybackSession(private val controller: ILearnPlayerController, private val lesson: Lesson) : LessonPlaybackSession {
    private val mutable = kotlinx.coroutines.flow.MutableStateFlow(LessonPlaybackState(lesson.id))
    override val state: StateFlow<LessonPlaybackState> = mutable
    override fun play() { controller.exoPlayer.play() }
    override fun pause() { controller.pause(); sample() }
    override fun seek(positionMs: Long) { controller.seekTo(positionMs) }
    override fun sample() {
        if (controller.activeLessonId != lesson.id) return
        controller.updateProgressState()
        mutable.value = LessonPlaybackState(lesson.id, controller.exoPlayer.playbackState == androidx.media3.common.Player.STATE_READY,
            controller.exoPlayer.isPlaying, controller.exoPlayer.currentPosition.coerceAtLeast(0), controller.exoPlayer.duration.coerceAtLeast(0))
    }
    override fun release() { pause() } // The Activity owns the shared native player.
}
