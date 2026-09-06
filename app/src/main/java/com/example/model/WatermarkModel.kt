package com.example.model

import android.net.Uri
import java.io.File
import java.util.UUID

enum class RemovalMode(val displayName: String, val description: String) {
  INPAINT("Smart Inpaint", "Edge-blend interpolation"),
  BLUR("Smooth Blur", "Gaussian style blur"),
  MOSAIC("Mosaic", "Pixelated block disguise"),
  SOLID_FILL("Edge Color", "Matches surrounding background")
}

data class WatermarkRegion(
  val id: String = UUID.randomUUID().toString(),
  val left: Float = 0.65f,
  val top: Float = 0.08f,
  val right: Float = 0.92f,
  val bottom: Float = 0.20f,
  val mode: RemovalMode = RemovalMode.INPAINT,
  val intensity: Float = 0.7f,
  val feather: Float = 0.25f
) {
  val width: Float get() = (right - left).coerceAtLeast(0.04f)
  val height: Float get() = (bottom - top).coerceAtLeast(0.04f)

  fun copyWithBounds(newLeft: Float, newTop: Float, newRight: Float, newBottom: Float): WatermarkRegion {
    val clampedLeft = newLeft.coerceIn(0f, 0.95f)
    val clampedTop = newTop.coerceIn(0f, 0.95f)
    val clampedRight = newRight.coerceIn(clampedLeft + 0.04f, 1f)
    val clampedBottom = newBottom.coerceIn(clampedTop + 0.04f, 1f)
    return this.copy(
      left = clampedLeft,
      top = clampedTop,
      right = clampedRight,
      bottom = clampedBottom
    )
  }
}

data class VideoMetadata(
  val uri: Uri,
  val durationMs: Long,
  val width: Int,
  val height: Int,
  val rotation: Int = 0,
  val title: String = "Selected Video",
  val fileSize: Long = 0L,
  val fps: Float = 30f
) {
  val formattedDuration: String get() {
    val totalSeconds = durationMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%02d:%02d", minutes, seconds)
  }
  
  val resolutionLabel: String get() = "${width}x${height}"
}

sealed class ExportState {
  data object Idle : ExportState()
  data object Preparing : ExportState()
  data class Exporting(
    val progress: Float,
    val currentFrame: Int,
    val totalFrames: Int,
    val statusText: String = "Processing frames…"
  ) : ExportState()
  data class Completed(
    val outputUri: Uri,
    val outputFile: File,
    val durationMs: Long
  ) : ExportState()
  data class Error(val message: String) : ExportState()
}
