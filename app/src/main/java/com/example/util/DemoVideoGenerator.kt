package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.sin

object DemoVideoGenerator {

  private const val TAG = "DemoVideoGenerator"

  suspend fun getOrCreateDemoVideo(context: Context): Uri = withContext(Dispatchers.IO) {
    val demoFile = File(context.cacheDir, "sample_watermark_video.mp4")
    if (demoFile.exists() && demoFile.length() > 50_000) {
      return@withContext Uri.fromFile(demoFile)
    }

    val width = 720
    val height = 480
    val fps = 24
    val durationSec = 4
    val totalFrames = fps * durationSec
    val mimeType = MediaFormat.MIMETYPE_VIDEO_AVC
    val frameDurationUs = 1000000L / fps

    val format = MediaFormat.createVideoFormat(mimeType, width, height).apply {
      setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
      setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000)
      setInteger(MediaFormat.KEY_FRAME_RATE, fps)
      setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
    }

    val encoder = MediaCodec.createEncoderByType(mimeType)
    encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    encoder.start()

    val muxer = MediaMuxer(demoFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    var trackIndex = -1
    var muxerStarted = false
    val bufferInfo = MediaCodec.BufferInfo()

    val yuvData = ByteArray(width * height * 3 / 2)
    val argbData = IntArray(width * height)

    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val bgPaint = Paint()
    val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    val watermarkBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.parseColor("#E6000000")
      style = Paint.Style.FILL
    }
    val watermarkTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.parseColor("#FFD700") // Golden yellow
      textSize = 28f
      typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val watermarkSubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.WHITE
      textSize = 18f
    }

    try {
      for (frame in 0 until totalFrames) {
        val t = frame.toFloat() / totalFrames
        val frameTimeUs = frame * frameDurationUs

        // Dynamic background gradient
        val c1 = Color.rgb(
          (30 + sin(t * 6.28) * 20).toInt(),
          (60 + sin(t * 6.28 + 1.5) * 30).toInt(),
          (130 + sin(t * 6.28 + 3.0) * 40).toInt()
        )
        val c2 = Color.rgb(
          (80 + sin(t * 6.28 + 2.0) * 40).toInt(),
          (20 + sin(t * 6.28 + 4.0) * 20).toInt(),
          (80 + sin(t * 6.28) * 30).toInt()
        )
        bgPaint.shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), c1, c2, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // Animated glowing circle
        val circleX = width * 0.35f + sin(t * 12.56f) * 120f
        val circleY = height * 0.55f + sin(t * 6.28f) * 80f
        shapePaint.color = Color.argb(180, 56, 189, 248)
        canvas.drawCircle(circleX, circleY, 65f, shapePaint)

        // Another floating element
        val elemX = width * 0.65f + sin(t * 6.28f + 1.0f) * 100f
        val elemY = height * 0.4f + sin(t * 12.56f + 2.0f) * 60f
        shapePaint.color = Color.argb(190, 168, 85, 247)
        canvas.drawCircle(elemX, elemY, 45f, shapePaint)

        // Content title
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
          color = Color.WHITE
          textSize = 36f
          typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText("Demo Video Clip", 60f, height * 0.85f, titlePaint)

        // The Watermark in the top-right corner (Target to remove!)
        val wmLeft = width * 0.62f
        val wmTop = height * 0.08f
        val wmRight = width * 0.94f
        val wmBottom = height * 0.24f
        val wmRect = RectF(wmLeft, wmTop, wmRight, wmBottom)

        canvas.drawRoundRect(wmRect, 16f, 16f, watermarkBgPaint)
        canvas.drawText("★ WATERMARK LOGO", wmLeft + 20f, wmTop + 42f, watermarkTextPaint)
        canvas.drawText("© 2026 Sample Channel", wmLeft + 20f, wmTop + 68f, watermarkSubPaint)

        // Convert to YUV420SP
        bitmap.getPixels(argbData, 0, width, 0, 0, width, height)
        encodeYUV420SP(yuvData, argbData, width, height)

        // Feed to encoder
        var inputDone = false
        var retryCount = 0
        while (!inputDone && retryCount < 50) {
          retryCount++
          val inputIndex = encoder.dequeueInputBuffer(10000L)
          if (inputIndex >= 0) {
            val buf = encoder.getInputBuffer(inputIndex)
            buf?.clear()
            buf?.put(yuvData)
            encoder.queueInputBuffer(inputIndex, 0, yuvData.size, frameTimeUs, 0)
            inputDone = true
          }
        }

        // Drain encoder
        while (true) {
          val outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 10000L)
          when {
            outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> break
            outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
              trackIndex = muxer.addTrack(encoder.outputFormat)
              muxer.start()
              muxerStarted = true
            }
            outputIndex >= 0 -> {
              val outBuf = encoder.getOutputBuffer(outputIndex)
              if (outBuf != null && bufferInfo.size > 0 && muxerStarted) {
                outBuf.position(bufferInfo.offset)
                outBuf.limit(bufferInfo.offset + bufferInfo.size)
                muxer.writeSampleData(trackIndex, outBuf, bufferInfo)
              }
              encoder.releaseOutputBuffer(outputIndex, false)
            }
          }
        }
      }

      // End of Stream
      val eosIndex = encoder.dequeueInputBuffer(10000L)
      if (eosIndex >= 0) {
        encoder.queueInputBuffer(eosIndex, 0, 0, totalFrames * frameDurationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
      }

      while (true) {
        val outIdx = encoder.dequeueOutputBuffer(bufferInfo, 10000L)
        if (outIdx >= 0) {
          val outBuf = encoder.getOutputBuffer(outIdx)
          if (outBuf != null && bufferInfo.size > 0 && muxerStarted) {
            outBuf.position(bufferInfo.offset)
            outBuf.limit(bufferInfo.offset + bufferInfo.size)
            muxer.writeSampleData(trackIndex, outBuf, bufferInfo)
          }
          encoder.releaseOutputBuffer(outIdx, false)
          if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
        } else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
          break
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Demo video generation error: ${e.message}")
    } finally {
      bitmap.recycle()
      try { encoder.stop() } catch (_: Exception) {}
      encoder.release()
      if (muxerStarted) {
        try { muxer.stop() } catch (_: Exception) {}
      }
      try { muxer.release() } catch (_: Exception) {}
    }

    Uri.fromFile(demoFile)
  }

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

        val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
        val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
        val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128

        yuv420sp[yIndex++] = y.coerceIn(0, 255).toByte()
        if (j % 2 == 0 && i % 2 == 0) {
          yuv420sp[uvIndex++] = u.coerceIn(0, 255).toByte()
          yuv420sp[uvIndex++] = v.coerceIn(0, 255).toByte()
        }
      }
    }
  }
}
