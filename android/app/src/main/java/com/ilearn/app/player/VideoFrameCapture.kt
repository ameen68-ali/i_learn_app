package com.ilearn.app.player

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Extracts and compresses a paused local video frame to Base64 JPEG so multimodal AI providers
 * can explain handwritten equations, graphs, and slides on screen.
 */
object VideoFrameCapture {

    suspend fun captureFrameBase64(
        context: Context,
        videoPath: String,
        positionMs: Long
    ): String? = withContext(Dispatchers.IO) {
        if (videoPath.isBlank()) return@withContext null
        val retriever = MediaMetadataRetriever()
        var frame: Bitmap? = null
        var scaled: Bitmap? = null
        try {
            val file = File(videoPath)
            if (file.isFile) {
                retriever.setDataSource(file.absolutePath)
            } else if (videoPath.startsWith("content://", ignoreCase = true)) {
                retriever.setDataSource(context, Uri.parse(videoPath))
            } else {
                return@withContext null
            }

            frame = retriever.getFrameAtTime(
                positionMs.coerceAtLeast(0L) * 1000L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            ) ?: return@withContext null

            val maxDim = 1024
            val w = frame.width
            val h = frame.height
            val targetBitmap = if (w > maxDim || h > maxDim) {
                val ratio = minOf(maxDim.toFloat() / w, maxDim.toFloat() / h)
                scaled = Bitmap.createScaledBitmap(
                    frame,
                    (w * ratio).toInt().coerceAtLeast(1),
                    (h * ratio).toInt().coerceAtLeast(1),
                    true
                )
                scaled
            } else {
                frame
            }

            val out = ByteArrayOutputStream()
            targetBitmap.compress(Bitmap.CompressFormat.JPEG, 78, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        } finally {
            scaled?.takeIf { !it.isRecycled }?.recycle()
            frame?.takeIf { !it.isRecycled }?.recycle()
            runCatching { retriever.release() }
        }
    }
}
