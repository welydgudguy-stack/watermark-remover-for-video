package com.example.ui.components

import android.graphics.Bitmap
import android.media.MediaPlayer
import android.net.Uri
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun VideoPlayerSurface(
  videoUri: Uri,
  isPlaying: Boolean,
  currentPositionMs: Long,
  videoAspect: Float,
  showProcessedPreview: Boolean,
  processedBitmap: Bitmap?,
  rawBitmap: Bitmap?,
  onVideoPrepared: (durationMs: Long, width: Int, height: Int) -> Unit,
  onPlaybackComplete: () -> Unit,
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
  var isPrepared by remember { mutableStateOf(false) }

  // Sync isPlaying with MediaPlayer
  LaunchedEffect(isPlaying, isPrepared) {
    val player = mediaPlayer ?: return@LaunchedEffect
    if (isPrepared) {
      if (isPlaying && !player.isPlaying) {
        player.start()
      } else if (!isPlaying && player.isPlaying) {
        player.pause()
      }
    }
  }

  // Handle seeking when player is paused or scrubbed
  LaunchedEffect(currentPositionMs) {
    val player = mediaPlayer ?: return@LaunchedEffect
    if (isPrepared && !isPlaying) {
      val diff = kotlin.math.abs(player.currentPosition - currentPositionMs)
      if (diff > 250) {
        player.seekTo(currentPositionMs.toInt())
      }
    }
  }

  Box(
    modifier = modifier
      .fillMaxWidth()
      .aspectRatio(videoAspect.coerceIn(0.5f, 2.5f))
      .background(Color.Black)
      .testTag("video_player_container"),
    contentAlignment = Alignment.Center
  ) {
    // Underlying hardware video surface
    AndroidView(
      modifier = Modifier.fillMaxSize(),
      factory = { ctx ->
        SurfaceView(ctx).apply {
          holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
              val player = MediaPlayer().apply {
                setDataSource(ctx, videoUri)
                setDisplay(holder)
                isLooping = false
                setOnPreparedListener { mp ->
                  isPrepared = true
                  onVideoPrepared(mp.duration.toLong(), mp.videoWidth, mp.videoHeight)
                  if (isPlaying) mp.start()
                }
                setOnCompletionListener {
                  onPlaybackComplete()
                }
                prepareAsync()
              }
              mediaPlayer = player
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

            override fun surfaceDestroyed(holder: SurfaceHolder) {
              mediaPlayer?.release()
              mediaPlayer = null
              isPrepared = false
            }
          })
        }
      },
      update = { _ -> }
    )

    // Real-time Cleaned Watermark Preview Overlay
    if (showProcessedPreview && processedBitmap != null) {
      Image(
        bitmap = processedBitmap.asImageBitmap(),
        contentDescription = "Removed watermark preview",
        modifier = Modifier
          .fillMaxSize()
          .testTag("cleaned_preview_image"),
        contentScale = ContentScale.Fit
      )
    }

    DisposableEffect(videoUri) {
      onDispose {
        mediaPlayer?.release()
        mediaPlayer = null
        isPrepared = false
      }
    }
  }
}
