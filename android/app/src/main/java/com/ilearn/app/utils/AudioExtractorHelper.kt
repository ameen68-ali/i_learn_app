package com.ilearn.app.utils

import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

/**
 * High-performance, zero-dependency audio extractor using native Android MediaExtractor and MediaMuxer.
 * Demuxes the AAC audio track directly from an MP4 video file to an M4A file in 1-2 seconds without re-encoding.
 */
object AudioExtractorHelper {

    suspend fun extractAudioFromVideo(
        videoFile: File,
        outputM4aFile: File
    ): Boolean = withContext(Dispatchers.IO) {
        if (!videoFile.exists()) return@withContext false

        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null

        try {
            extractor = MediaExtractor().apply {
                setDataSource(videoFile.absolutePath)
            }

            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    break
                }
            }

            if (audioTrackIndex < 0 || audioFormat == null) {
                return@withContext false
            }

            extractor.selectTrack(audioTrackIndex)

            // Ensure parent directory exists
            outputM4aFile.parentFile?.mkdirs()
            if (outputM4aFile.exists()) {
                outputM4aFile.delete()
            }

            muxer = MediaMuxer(outputM4aFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxerTrackIndex = muxer.addTrack(audioFormat)
            muxer.start()

            val maxBufferSize = audioFormat.run {
                if (containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(1024 * 512)
                } else {
                    1024 * 512
                }
            }

            val buffer = ByteBuffer.allocateDirect(maxBufferSize)
            val bufferInfo = MediaCodec.BufferInfo()

            while (true) {
                buffer.clear()
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) {
                    break
                }

                bufferInfo.offset = 0
                bufferInfo.size = sampleSize
                bufferInfo.presentationTimeUs = extractor.sampleTime
                bufferInfo.flags = extractor.sampleFlags

                muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
                extractor.advance()
            }

            muxer.stop()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            outputM4aFile.delete()
            false
        } finally {
            try {
                muxer?.release()
            } catch (_: Exception) {}
            try {
                extractor?.release()
            } catch (_: Exception) {}
        }
    }

    /**
     * Launches the Android Share Sheet to send the extracted audio file to Gemini, Google Drive, Telegram, etc.
     */
    fun shareAudioFile(context: Context, audioFile: File, lessonTitle: String) {
        try {
            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                audioFile
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/*"
                putExtra(Intent.EXTRA_STREAM, contentUri)
                putExtra(Intent.EXTRA_SUBJECT, "صوت درس: $lessonTitle")
                putExtra(Intent.EXTRA_TEXT, "ملف الصوت المستخرج لدرس: $lessonTitle")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooser = Intent.createChooser(shareIntent, "مشاركة الصوت للترجمة (Gemini / AI)")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

