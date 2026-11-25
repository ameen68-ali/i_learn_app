package com.ilearn.app.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.ilearn.app.data.AudioTrackMode
import com.ilearn.app.data.Lesson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

enum class SubtitleMode(val labelAr: String, val labelEn: String, val langCode: String?) {
    ARABIC("🇸🇦 العربية", "Arabic", "ar"),
    ENGLISH("🇬🇧 English", "English", "en"),
    OFF("🚫 بدون ترجمة", "Off", null)
}

enum class SubtitleSize(val labelAr: String, val scale: Float, val previewSp: Int) {
    SMALL("صغير", 0.90f, 13),
    MEDIUM("متوسط", 1.25f, 16),
    LARGE("كبير", 1.60f, 20),
    EXTRA_LARGE("كبير جداً", 1.95f, 24)
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ILearnPlayerController(private val context: Context) {

    val exoPlayer: ExoPlayer = ExoPlayer.Builder(context).build()

    private val prefs = context.getSharedPreferences("ilearn_player_prefs", Context.MODE_PRIVATE)

    private val _subtitleMode = MutableStateFlow(SubtitleMode.ARABIC)
    val subtitleMode: StateFlow<SubtitleMode> = _subtitleMode.asStateFlow()

    private val _subtitleSize = MutableStateFlow(
        SubtitleSize.values().firstOrNull { it.name == prefs.getString("subtitle_size", SubtitleSize.MEDIUM.name) }
            ?: SubtitleSize.MEDIUM
    )
    val subtitleSize: StateFlow<SubtitleSize> = _subtitleSize.asStateFlow()

    private val _audioTrackMode = MutableStateFlow(AudioTrackMode.ORIGINAL)
    val audioTrackMode: StateFlow<AudioTrackMode> = _audioTrackMode.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _currentSubtitles = MutableStateFlow<String?>(null)
    val currentSubtitles: StateFlow<String?> = _currentSubtitles.asStateFlow()

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    private val _playerError = MutableStateFlow<String?>(null)
    val playerError: StateFlow<String?> = _playerError.asStateFlow()

    private var activeLesson: Lesson? = null
    val activeLessonId: String? get() = activeLesson?.id

    init {
        exoPlayer.playWhenReady = true
        exoPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                _isPlaying.value = playing
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                _isBuffering.value = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) {
                    _playerError.value = null
                    _duration.value = exoPlayer.duration.coerceAtLeast(0L)
                    applyAudioTrackMode(_audioTrackMode.value)
                    applySubtitleMode(_subtitleMode.value)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                _isBuffering.value = false
                _playerError.value = "تعذّر تشغيل الفيديو (${error.errorCodeName}). تأكد من سلامة الملف أو أعد المحاولة."
            }

            override fun onTracksChanged(tracks: Tracks) {
                applyAudioTrackMode(_audioTrackMode.value)
                applySubtitleMode(_subtitleMode.value)
            }

            override fun onCues(cueGroup: CueGroup) {
                if (_subtitleMode.value == SubtitleMode.OFF) {
                    _currentSubtitles.value = null
                } else {
                    val text = cueGroup.cues.joinToString("\n") { it.text?.toString() ?: "" }.trim()
                    _currentSubtitles.value = if (text.isNotBlank()) text else null
                }
            }
        })
    }

    private fun findSubtitleFile(videoFile: File, subFolder: String): File? {
        val courseDir = videoFile.parentFile?.parentFile ?: return null
        val targetDir = File(courseDir, subFolder)
        if (!targetDir.exists()) return null

        val stem = videoFile.nameWithoutExtension
        val vtt = File(targetDir, "$stem.vtt")
        if (vtt.exists()) return vtt

        val srt = File(targetDir, "$stem.srt")
        if (srt.exists()) return srt

        // Fallback: Number prefix match (e.g. "01" vs "1")
        val numMatch = Regex("^(\\d+)").find(videoFile.name)
        val num = numMatch?.groupValues?.get(1)?.toIntOrNull()
        if (num != null) {
            val candidate = targetDir.listFiles { f ->
                val fNum = Regex("^(\\d+)").find(f.name)?.groupValues?.get(1)?.toIntOrNull()
                fNum == num && (f.name.endsWith(".vtt", ignoreCase = true) || f.name.endsWith(".srt", ignoreCase = true))
            }?.firstOrNull()
            if (candidate != null && candidate.exists()) return candidate
        }

        return null
    }

    fun retry() {
        activeLesson?.let { loadLesson(it) }
    }

    fun loadLesson(lesson: Lesson, overridePositionMs: Long? = null) {
        activeLesson = lesson
        _currentSubtitles.value = null
        _playerError.value = null
        _isBuffering.value = true

        val videoFile = File(lesson.videoPath)
        val isLocalPath = !lesson.videoPath.startsWith("http", ignoreCase = true) &&
            !lesson.videoPath.startsWith("content://", ignoreCase = true)
        if (isLocalPath && !videoFile.isFile) {
            _isBuffering.value = false
            _playerError.value = "ملف الفيديو غير موجود على الجهاز. قد يكون تم حذفه أو نقله."
            return
        }
        val videoUri = if (videoFile.exists()) Uri.fromFile(videoFile) else Uri.parse(lesson.videoPath)

        val mediaItemBuilder = MediaItem.Builder().setUri(videoUri)
        val subtitleConfigs = mutableListOf<MediaItem.SubtitleConfiguration>()

        // Arabic Subtitles (from recorded path or smart fallback)
        val arSubFile = lesson.subArPath?.let { File(it) }?.takeIf { it.exists() }
            ?: findSubtitleFile(videoFile, "subtitles_ar")

        arSubFile?.let { file ->
            val mimeType = if (file.name.endsWith(".srt", ignoreCase = true)) {
                MimeTypes.APPLICATION_SUBRIP
            } else {
                MimeTypes.TEXT_VTT
            }
            subtitleConfigs.add(
                MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(file))
                    .setMimeType(mimeType)
                    .setLanguage("ar")
                    .setLabel("العربية")
                    .setSelectionFlags(if (_subtitleMode.value == SubtitleMode.ARABIC) C.SELECTION_FLAG_DEFAULT else 0)
                    .build()
            )
        }

        // English Subtitles (from recorded path or smart fallback)
        val enSubFile = lesson.subEnPath?.let { File(it) }?.takeIf { it.exists() }
            ?: findSubtitleFile(videoFile, "subtitles_en")

        enSubFile?.let { file ->
            val mimeType = if (file.name.endsWith(".srt", ignoreCase = true)) {
                MimeTypes.APPLICATION_SUBRIP
            } else {
                MimeTypes.TEXT_VTT
            }
            subtitleConfigs.add(
                MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(file))
                    .setMimeType(mimeType)
                    .setLanguage("en")
                    .setLabel("English")
                    .setSelectionFlags(if (_subtitleMode.value == SubtitleMode.ENGLISH) C.SELECTION_FLAG_DEFAULT else 0)
                    .build()
            )
        }

        if (subtitleConfigs.isNotEmpty()) {
            mediaItemBuilder.setSubtitleConfigurations(subtitleConfigs)
        }

        // IMPORTANT: Use DefaultMediaSourceFactory so that sideloaded subtitles are automatically parsed and merged!
        val defaultMediaSourceFactory = DefaultMediaSourceFactory(context)
        val videoSource = defaultMediaSourceFactory.createMediaSource(mediaItemBuilder.build())

        // Check if Arabic audio is available and merge
        if (lesson.hasArabicAudio) {
            val audioFile = File(lesson.audioArPath!!)
            if (audioFile.exists()) {
                val audioItem = MediaItem.Builder()
                    .setUri(Uri.fromFile(audioFile))
                    .build()
                val audioSource = ProgressiveMediaSource.Factory(DefaultDataSource.Factory(context))
                    .createMediaSource(audioItem)
                val mergedSource = MergingMediaSource(videoSource, audioSource)
                exoPlayer.setMediaSource(mergedSource)
                _audioTrackMode.value = AudioTrackMode.ARABIC
            } else {
                exoPlayer.setMediaSource(videoSource)
                _audioTrackMode.value = AudioTrackMode.ORIGINAL
            }
        } else {
            exoPlayer.setMediaSource(videoSource)
            _audioTrackMode.value = AudioTrackMode.ORIGINAL
        }

        exoPlayer.prepare()

        // Apply preferred subtitle language
        applySubtitleMode(_subtitleMode.value)

        // Resume playback if reloading at explicit position or if student previously watched part of this lesson
        val targetPositionMs = overridePositionMs?.coerceAtLeast(0L)
            ?: if (lesson.lastPositionMs > 1000L && !lesson.isCompleted) lesson.lastPositionMs else 0L
        if (targetPositionMs > 0L) {
            exoPlayer.seekTo(targetPositionMs)
        }
    }

    fun setSubtitleMode(mode: SubtitleMode) {
        _subtitleMode.value = mode
        if (mode == SubtitleMode.OFF) {
            _currentSubtitles.value = null
        }
        applySubtitleMode(mode)
    }

    fun setSubtitleSize(size: SubtitleSize) {
        _subtitleSize.value = size
        prefs.edit().putString("subtitle_size", size.name).apply()
    }

    private fun applySubtitleMode(mode: SubtitleMode) {
        val paramsBuilder = exoPlayer.trackSelectionParameters.buildUpon()
        if (mode == SubtitleMode.OFF || mode.langCode == null) {
            paramsBuilder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            paramsBuilder.clearOverridesOfType(C.TRACK_TYPE_TEXT)
        } else {
            paramsBuilder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            paramsBuilder.setPreferredTextLanguage(mode.langCode)
            paramsBuilder.setSelectUndeterminedTextLanguage(true)

            // Look for matching text track in currentTracks and enforce override
            val textGroups = exoPlayer.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
            val matchingGroup = textGroups.firstOrNull { group ->
                (0 until group.length).any { i ->
                    val format = group.getTrackFormat(i)
                    format.language?.equals(mode.langCode, ignoreCase = true) == true
                }
            } ?: textGroups.firstOrNull()

            if (matchingGroup != null) {
                val trackIdx = (0 until matchingGroup.length).firstOrNull { i ->
                    matchingGroup.getTrackFormat(i).language?.equals(mode.langCode, ignoreCase = true) == true
                } ?: 0
                paramsBuilder.setOverrideForType(TrackSelectionOverride(matchingGroup.mediaTrackGroup, trackIdx))
            }
        }
        exoPlayer.trackSelectionParameters = paramsBuilder.build()
    }

    fun setAudioTrackMode(mode: AudioTrackMode) {
        _audioTrackMode.value = mode
        applyAudioTrackMode(mode)
    }

    private fun applyAudioTrackMode(mode: AudioTrackMode) {
        val audioGroups = exoPlayer.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        if (audioGroups.isEmpty()) return

        val paramsBuilder = exoPlayer.trackSelectionParameters.buildUpon()

        if (audioGroups.size > 1) {
            val targetGroup = if (mode == AudioTrackMode.ORIGINAL) audioGroups.first() else audioGroups.last()
            paramsBuilder.setOverrideForType(TrackSelectionOverride(targetGroup.mediaTrackGroup, 0))
        } else {
            val singleGroup = audioGroups.first()
            if (singleGroup.length > 1) {
                val trackIdx = if (mode == AudioTrackMode.ORIGINAL) 0 else singleGroup.length - 1
                paramsBuilder.setOverrideForType(TrackSelectionOverride(singleGroup.mediaTrackGroup, trackIdx))
            }
        }

        exoPlayer.trackSelectionParameters = paramsBuilder.build()
    }

    fun setPlaybackSpeed(speed: Float) {
        _playbackSpeed.value = speed
        exoPlayer.playbackParameters = PlaybackParameters(speed)
    }

    fun seekForward(ms: Long = 10000L) {
        val target = (exoPlayer.currentPosition + ms).coerceAtMost(exoPlayer.duration)
        exoPlayer.seekTo(target)
    }

    fun seekBackward(ms: Long = 10000L) {
        val target = (exoPlayer.currentPosition - ms).coerceAtLeast(0L)
        exoPlayer.seekTo(target)
    }

    fun seekTo(positionMs: Long) {
        exoPlayer.seekTo(positionMs.coerceIn(0L, exoPlayer.duration.coerceAtLeast(0L)))
    }

    fun pause() {
        if (exoPlayer.isPlaying) {
            exoPlayer.pause()
        }
    }

    fun stop() {
        exoPlayer.stop()
    }

    fun togglePlayPause() {
        if (exoPlayer.isPlaying) {
            exoPlayer.pause()
        } else {
            exoPlayer.play()
        }
    }

    fun updateProgressState() {
        _currentPosition.value = exoPlayer.currentPosition
        if (exoPlayer.duration > 0) {
            _duration.value = exoPlayer.duration
        }
    }

    fun release() {
        exoPlayer.release()
    }
}
