package com.example.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.example.model.ExportState
import com.example.model.WatermarkRegion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class VideoExportEngine(private val context: Context) {

  private val tag = "VideoExportEngine"

  suspend fun exportVideo(
    sourceUri: Uri,
    regions: List<WatermarkRegion>,
    onProgress: (ExportState) -> Unit
  ): ExportState.Completed = withContext(Dispatchers.IO) {
    onProgress(ExportState.Preparing)

    val retriever = MediaMetadataRetriever()
    try {
      retriever.setDataSource(context, sourceUri)
    } catch (e: Exception) {
      Log.e(tag, "Failed to set data source: ${e.message}")
      throw IllegalStateException("Unable to open source video: ${e.localizedMessage}")
    }

    val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
    val durationMs = durationStr?.toLongOrNull() ?: 5000L
    val origWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1280
    val origHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 720
    val rotationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
    val rotation = rotationStr?.toIntOrNull() ?: 0

    // Swap dimensions if rotation is 90 or 270
    val isRotated = rotation == 90 || rotation == 270
    val targetWidth = if (isRotated) origHeight else origWidth
    val targetHeight = if (isRotated) origWidth else origHeight

    // Ensure dimensions are even and capped for performance/compatibility
    val maxDimension = 1280
    var encWidth = targetWidth
    var encHeight = targetHeight
    if (encWidth > maxDimension || encHeight > maxDimension) {
      val ratio = encWidth.toFloat() / encHeight.toFloat()
      if (encWidth > encHeight) {
        encWidth = maxDimension
        encHeight = (maxDimension / ratio).roundToInt()
      } else {
        encHeight = maxDimension
        encWidth = (maxDimension * ratio).roundToInt()
      }
    }
    encWidth = ((encWidth + 1) / 2) * 2
    encHeight = ((encHeight + 1) / 2) * 2

    val fps = 24
    val frameDurationUs = 1000000L / fps
    val totalFrames = max(1, ((durationMs * fps) / 1000).toInt())

    // Prepare temp output file
    val tempDir = File(context.cacheDir, "exports").apply { mkdirs() }
    val tempOutputFile = File(tempDir, "clean_video_${System.currentTimeMillis()}.mp4")
    if (tempOutputFile.exists()) tempOutputFile.delete()

    val mimeType = MediaFormat.MIMETYPE_VIDEO_AVC
    val bitrate = (encWidth * encHeight * 3.5f).toInt().coerceIn(1_500_000, 6_000_000)

    val format = MediaFormat.createVideoFormat(mimeType, encWidth, encHeight).apply {
      setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
      setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
      setInteger(MediaFormat.KEY_FRAME_RATE, fps)
      setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
    }

    val encoder = MediaCodec.createEncoderByType(mimeType)
    encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    encoder.start()

    val muxer = MediaMuxer(tempOutputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    var videoTrackIndex = -1
    var muxerStarted = false
    val bufferInfo = MediaCodec.BufferInfo()

    val yuvBuffer = ByteArray(encWidth * encHeight * 3 / 2)
    val argbBuffer = IntArray(encWidth * encHeight)

    try {
      for (frameIndex in 0 until totalFrames) {
        if (!coroutineContext.isActive) {
          throw InterruptedException("Export cancelled by user")
        }

        val frameTimeUs = (frameIndex * frameDurationUs).coerceAtMost(durationMs * 1000L)
        val rawBitmap = retriever.getFrameAtTime(frameTimeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
          ?: retriever.getFrameAtTime(frameTimeUs, MediaMetadataRetriever.OPTION_CLOSEST)

        val frameBitmap = if (rawBitmap != null) {
          if (rawBitmap.width != encWidth || rawBitmap.height != encHeight) {
            val scaled = Bitmap.createScaledBitmap(rawBitmap, encWidth, encHeight, true)
            rawBitmap.recycle()
            scaled
          } else {
            rawBitmap
          }
        } else {
          // Fallback canvas if frame read fails
          Bitmap.createBitmap(encWidth, encHeight, Bitmap.Config.ARGB_8888).apply {
            val canvas = Canvas(this)
            canvas.drawColor(Color.BLACK)
          }
        }

        // Apply watermark removal
        val cleanedBitmap = WatermarkFilter.processBitmap(frameBitmap, regions)
        if (cleanedBitmap !== frameBitmap) {
          frameBitmap.recycle()
        }

        // Convert cleanedBitmap to NV12 (YUV420SemiPlanar)
        cleanedBitmap.getPixels(argbBuffer, 0, encWidth, 0, 0, encWidth, encHeight)
        cleanedBitmap.recycle()
        encodeYUV420SP(yuvBuffer, argbBuffer, encWidth, encHeight)

        // Feed to encoder
        feedEncoder(encoder, yuvBuffer, frameTimeUs, false)

        // Drain encoder
        drainEncoder(encoder, muxer, bufferInfo) { trackIndex ->
          videoTrackIndex = trackIndex
          muxerStarted = true
        }

        val progress = (frameIndex + 1).toFloat() / totalFrames.toFloat()
        onProgress(
          ExportState.Exporting(
            progress = progress,
            currentFrame = frameIndex + 1,
            totalFrames = totalFrames,
            statusText = "Processing frame ${frameIndex + 1} of $totalFrames"
          )
        )
      }

      // Signal End of Stream
      feedEncoder(encoder, ByteArray(0), totalFrames * frameDurationUs, true)

      // Drain remaining output
      drainEncoder(encoder, muxer, bufferInfo, endOfStream = true) { trackIndex ->
        videoTrackIndex = trackIndex
        muxerStarted = true
      }

    } finally {
      try {
        encoder.stop()
      } catch (e: Exception) {
        Log.w(tag, "Encoder stop failed: ${e.message}")
      }
      encoder.release()
      retriever.release()

      if (muxerStarted) {
        try {
          muxer.stop()
        } catch (e: Exception) {
          Log.w(tag, "Muxer stop failed: ${e.message}")
        }
      }
      try {
        muxer.release()
      } catch (e: Exception) {
        Log.w(tag, "Muxer release failed: ${e.message}")
      }
    }

    // Now save the exported video to device's public MediaStore (Movies/WatermarkRemover)
    onProgress(
      ExportState.Exporting(
        progress = 1.0f,
        currentFrame = totalFrames,
        totalFrames = totalFrames,
        statusText = "Saving clean video to your device…"
      )
    )

    val savedUri = saveToMediaStore(tempOutputFile, encWidth, encHeight, durationMs)

    ExportState.Completed(
      outputUri = savedUri,
      outputFile = tempOutputFile,
      durationMs = durationMs
    )
  }

  private fun feedEncoder(encoder: MediaCodec, yuvData: ByteArray, ptsUs: Long, isEos: Boolean) {
    var fed = false
    var attempts = 0
    while (!fed && attempts < 50) {
      attempts++
      val inputIndex = encoder.dequeueInputBuffer(10000L)
      if (inputIndex >= 0) {
        val inputBuffer = encoder.getInputBuffer(inputIndex) ?: continue
        inputBuffer.clear()
        if (isEos) {
          encoder.queueInputBuffer(inputIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        } else {
          inputBuffer.put(yuvData)
          encoder.queueInputBuffer(inputIndex, 0, yuvData.size, ptsUs, 0)
        }
        fed = true
      }
    }
  }

  private fun drainEncoder(
    encoder: MediaCodec,
    muxer: MediaMuxer,
    bufferInfo: MediaCodec.BufferInfo,
    endOfStream: Boolean = false,
    onTrackAdded: (Int) -> Unit
  ) {
    var trackIndex = -1
    var muxerStarted = false

    while (true) {
      val outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 10000L)
      when {
        outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
          if (!endOfStream) break
        }
        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
          val newFormat = encoder.outputFormat
          trackIndex = muxer.addTrack(newFormat)
          muxer.start()
          muxerStarted = true
          onTrackAdded(trackIndex)
        }
        outputIndex >= 0 -> {
          val encodedData = encoder.getOutputBuffer(outputIndex) ?: continue
          if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            bufferInfo.size = 0
          }
          if (bufferInfo.size != 0) {
            encodedData.position(bufferInfo.offset)
            encodedData.limit(bufferInfo.offset + bufferInfo.size)
            try {
              muxer.writeSampleData(if (trackIndex >= 0) trackIndex else 0, encodedData, bufferInfo)
            } catch (e: Exception) {
              Log.e(tag, "Error writing sample data: ${e.message}")
            }
          }
          encoder.releaseOutputBuffer(outputIndex, false)
          if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            break
          }
        }
      }
    }
  }

  /**
   * Fast RGB to YUV420SP (NV12) conversion.
   */
  private fun encodeYUV420SP(yuv420sp: ByteArray, argb: IntArray, width: Int, height: Int) {
    val frameSize = width * height
    var yIndex = 0
    var uvIndex = frameSize
    var index = 0

    for (j in 0 until height) {
      for (i in 0 until width) {
        val c = argb[index++]
        val r = (c shr 16) and 0xff
        val g = (c shr 8) and 0xff
        val b = c and 0xff

        // Standard ITU-R BT.601 conversion
        val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
        val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
        val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128

        yuv420sp[yIndex++] = y.coerceIn(0, 255).toByte()
        if (j % 2 == 0 && i % 2 == 0) {
          // NV12: U then V
          yuv420sp[uvIndex++] = u.coerceIn(0, 255).toByte()
          yuv420sp[uvIndex++] = v.coerceIn(0, 255).toByte()
        }
      }
    }
  }

  /**
   * Saves the exported MP4 video to Android's Public MediaStore so the user
   * can view it in their device's Photos/Gallery app immediately.
   */
  private fun saveToMediaStore(file: File, width: Int, height: Int, durationMs: Long): Uri {
    val fileName = "Clean_Video_${System.currentTimeMillis()}.mp4"
    val contentValues = ContentValues().apply {
      put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
      put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
      put(MediaStore.Video.Media.WIDTH, width)
      put(MediaStore.Video.Media.HEIGHT, height)
      put(MediaStore.Video.Media.DURATION, durationMs)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/WatermarkRemover")
        put(MediaStore.Video.Media.IS_PENDING, 1)
      }
    }

    val resolver = context.contentResolver
    val collectionUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    } else {
      MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    }

    val insertedUri = resolver.insert(collectionUri, contentValues)
      ?: throw IllegalStateException("Failed to create MediaStore entry")

    resolver.openOutputStream(insertedUri)?.use { output ->
      FileInputStream(file).use { input ->
        input.copyTo(output)
      }
    } ?: throw IllegalStateException("Failed to open MediaStore output stream")

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      contentValues.clear()
      contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
      resolver.update(insertedUri, contentValues, null, null)
    }

    return insertedUri
  }
}
