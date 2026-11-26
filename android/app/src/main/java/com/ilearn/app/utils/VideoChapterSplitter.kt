package com.ilearn.app.utils

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext

data class ChapterSplitResult(
    val outputFile: File,
    val actualStartMs: Long,
    val actualEndMs: Long,
    val durationMs: Long
)

/**
 * Lossless on-device MP4 chapter splitter built on Android's native [MediaExtractor] + [MediaMuxer].
 * Snaps cleanly to the preceding video IDR keyframe (`SEEK_TO_PREVIOUS_SYNC`) and reports exact cut
 * boundaries (`actualStartMs`, `actualEndMs`) so `.vtt` subtitles align without GOP drift.
 */
object VideoChapterSplitter {

    suspend fun splitChapter(
        sourceFile: File,
        outputFile: File,
        startMs: Long,
        endMs: Long?
    ): ChapterSplitResult = withContext(Dispatchers.IO) {
        require(sourceFile.isFile && sourceFile.length() > 0L) { "ملف الفيديو المصدر غير موجود" }
        val safeStartUs = startMs.coerceAtLeast(0L) * 1000L
        val safeEndUs = endMs?.takeIf { it > startMs }?.let { it * 1000L }

        outputFile.parentFile?.mkdirs()
        val tmpFile = File(outputFile.parentFile, ".tmp_split_${outputFile.name}")
        if (tmpFile.exists()) tmpFile.delete()

        val probeExtractor = MediaExtractor()
        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null

        try {
            probeExtractor.setDataSource(sourceFile.absolutePath)
            var videoTrackIdx = -1
            var audioTrackIdx = -1
            var videoFormat: MediaFormat? = null
            var audioFormat: MediaFormat? = null

            for (i in 0 until probeExtractor.trackCount) {
                val format = probeExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (videoTrackIdx < 0 && mime.startsWith("video/")) {
                    videoTrackIdx = i
                    videoFormat = format
                } else if (audioTrackIdx < 0 && mime.startsWith("audio/")) {
                    audioTrackIdx = i
                    audioFormat = format
                }
            }
            require(videoTrackIdx >= 0 || audioTrackIdx >= 0) { "لا يحتوي الملف على مسار فيديو أو صوت صالح" }

            val anchorTrack = if (videoTrackIdx >= 0) videoTrackIdx else audioTrackIdx
            probeExtractor.selectTrack(anchorTrack)
            probeExtractor.seekTo(safeStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val anchorStartUs = probeExtractor.sampleTime.takeIf { it >= 0L } ?: safeStartUs
            probeExtractor.release()

            val activeExtractor = MediaExtractor().apply {
                setDataSource(sourceFile.absolutePath)
                if (videoTrackIdx >= 0) selectTrack(videoTrackIdx)
                if (audioTrackIdx >= 0) selectTrack(audioTrackIdx)
                seekTo(anchorStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            }
            extractor = activeExtractor

            val activeMuxer = MediaMuxer(tmpFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = activeMuxer
            val muxTrackMap = HashMap<Int, Int>(2)
            videoFormat?.let { vf ->
                muxTrackMap[videoTrackIdx] = activeMuxer.addTrack(vf)
                if (vf.containsKey(MediaFormat.KEY_ROTATION)) {
                    activeMuxer.setOrientationHint(vf.getInteger(MediaFormat.KEY_ROTATION))
                }
            }
            audioFormat?.let { af ->
                muxTrackMap[audioTrackIdx] = activeMuxer.addTrack(af)
            }
            activeMuxer.start()

            val videoMax = videoFormat?.let {
                if (it.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) it.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
            } ?: 0
            val audioMax = audioFormat?.let {
                if (it.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) it.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
            } ?: 0
            val bufferCapacity = maxOf(videoMax, audioMax, 1024 * 1024)
            val buffer = ByteBuffer.allocateDirect(bufferCapacity)
            val bufferInfo = MediaCodec.BufferInfo()
            val lastPtsByTrack = HashMap<Int, Long>(2)

            var maxRelativeUs = 0L
            var sampleCount = 0
            var wroteAnySample = false

            while (true) {
                if (++sampleCount % 64 == 0) {
                    coroutineContext.ensureActive()
                }
                val trackIndex = activeExtractor.sampleTrackIndex
                if (trackIndex < 0) break

                buffer.clear()
                val sampleSize = activeExtractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

                val sampleTimeUs = activeExtractor.sampleTime
                if (sampleTimeUs < anchorStartUs) {
                    activeExtractor.advance()
                    continue
                }
                if (safeEndUs != null && sampleTimeUs >= safeEndUs && wroteAnySample) {
                    break
                }

                val muxTrackIndex = muxTrackMap[trackIndex]
                if (muxTrackIndex != null) {
                    val rawRelPts = (sampleTimeUs - anchorStartUs).coerceAtLeast(0L)
                    val prevPts = lastPtsByTrack[muxTrackIndex]
                    val monotonicPts = if (prevPts != null && rawRelPts <= prevPts) prevPts + 1L else rawRelPts
                    lastPtsByTrack[muxTrackIndex] = monotonicPts

                    val sampleFlags = activeExtractor.sampleFlags
                    val codecFlags = buildCodecFlags(sampleFlags)
                    bufferInfo.set(0, sampleSize, monotonicPts, codecFlags)
                    activeMuxer.writeSampleData(muxTrackIndex, buffer, bufferInfo)
                    wroteAnySample = true
                    if (monotonicPts > maxRelativeUs) maxRelativeUs = monotonicPts
                }
                activeExtractor.advance()
            }

            require(wroteAnySample) { "لم يحتوي نطاق الفصل المحدد على بيانات قابلة للقص" }
            activeMuxer.stop()
            activeMuxer.release()
            muxer = null
            activeExtractor.release()
            extractor = null

            if (outputFile.exists()) outputFile.delete()
            if (!tmpFile.renameTo(outputFile)) {
                tmpFile.copyTo(outputFile, overwrite = true)
                tmpFile.delete()
            }
            check(outputFile.isFile && outputFile.length() > 0L) { "فشل حفظ ملف الفصل المقسّم" }

            val actualStartMs = anchorStartUs / 1000L
            val relDurMs = (maxRelativeUs / 1000L).coerceAtLeast(1000L)
            ChapterSplitResult(
                outputFile = outputFile,
                actualStartMs = actualStartMs,
                actualEndMs = actualStartMs + relDurMs,
                durationMs = relDurMs
            )
        } catch (e: Exception) {
            tmpFile.delete()
            throw e
        } finally {
            runCatching { probeExtractor.release() }
            runCatching { muxer?.release() }
            runCatching { extractor?.release() }
        }
    }

    private fun buildCodecFlags(extractorFlags: Int): Int {
        var flags = 0
        if (extractorFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
            flags = flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
        }
        if (extractorFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0) {
            flags = flags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
        }
        return flags
    }
}
