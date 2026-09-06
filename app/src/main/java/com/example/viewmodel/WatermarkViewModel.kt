package com.example.viewmodel

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.model.ExportState
import com.example.model.RemovalMode
import com.example.model.VideoMetadata
import com.example.model.WatermarkRegion
import com.example.util.DemoVideoGenerator
import com.example.util.VideoExportEngine
import com.example.util.WatermarkFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class WatermarkUiState(
  val videoMetadata: VideoMetadata? = null,
  val isPlaying: Boolean = false,
  val currentPositionMs: Long = 0L,
  val watermarkRegions: List<WatermarkRegion> = emptyList(),
  val selectedRegionId: String? = null,
  val isPreviewProcessed: Boolean = false,
  val rawFrameBitmap: Bitmap? = null,
  val cleanedFrameBitmap: Bitmap? = null,
  val exportState: ExportState = ExportState.Idle,
  val isGeneratingDemo: Boolean = false,
  val errorMessage: String? = null
) {
  val selectedRegion: WatermarkRegion?
    get() = watermarkRegions.find { it.id == selectedRegionId } ?: watermarkRegions.firstOrNull()
}

class WatermarkViewModel(application: Application) : AndroidViewModel(application) {

  private val tag = "WatermarkViewModel"
  private val exportEngine = VideoExportEngine(application)

  private val _uiState = MutableStateFlow(WatermarkUiState())
  val uiState: StateFlow<WatermarkUiState> = _uiState.asStateFlow()

  private var exportJob: Job? = null
  private var playbackTickerJob: Job? = null
  private var frameExtractionJob: Job? = null

  fun clearVideo() {
    playbackTickerJob?.cancel()
    frameExtractionJob?.cancel()
    _uiState.update {
      it.copy(
        videoMetadata = null,
        isPlaying = false,
        currentPositionMs = 0L,
        rawFrameBitmap = null,
        cleanedFrameBitmap = null,
        watermarkRegions = emptyList(),
        selectedRegionId = null,
        isPreviewProcessed = false,
        errorMessage = null
      )
    }
  }

  fun loadVideo(uri: Uri) {
    if (uri == Uri.EMPTY) {
      clearVideo()
      return
    }
    viewModelScope.launch {
      try {
        val context = getApplication<Application>()
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(context, uri)

        val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
        val durationMs = durationStr?.toLongOrNull() ?: 10000L
        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1280
        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 720
        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0

        // Get file display name
        var fileName = "Selected Video"
        var fileSize = 0L
        try {
          context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
              val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
              val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
              if (nameIndex != -1) fileName = cursor.getString(nameIndex) ?: "Selected Video"
              if (sizeIndex != -1) fileSize = cursor.getLong(sizeIndex)
            }
          }
        } catch (_: Exception) {}

        retriever.release()

        val initialRegion = WatermarkRegion(
          left = 0.65f,
          top = 0.08f,
          right = 0.94f,
          bottom = 0.22f,
          mode = RemovalMode.INPAINT
        )

        _uiState.update {
          it.copy(
            videoMetadata = VideoMetadata(
              uri = uri,
              durationMs = durationMs,
              width = width,
              height = height,
              rotation = rotation,
              title = fileName,
              fileSize = fileSize
            ),
            currentPositionMs = 0L,
            isPlaying = false,
            watermarkRegions = listOf(initialRegion),
            selectedRegionId = initialRegion.id,
            errorMessage = null
          )
        }

        extractCurrentFrame()
      } catch (e: Exception) {
        Log.e(tag, "Failed to load video: ${e.message}")
        _uiState.update { it.copy(errorMessage = "Failed to load video: ${e.localizedMessage}") }
      }
    }
  }

  fun loadDemoVideo() {
    viewModelScope.launch {
      _uiState.update { it.copy(isGeneratingDemo = true, errorMessage = null) }
      try {
        val demoUri = DemoVideoGenerator.getOrCreateDemoVideo(getApplication())
        _uiState.update { it.copy(isGeneratingDemo = false) }
        loadVideo(demoUri)
      } catch (e: Exception) {
        Log.e(tag, "Failed to generate demo video: ${e.message}")
        _uiState.update {
          it.copy(
            isGeneratingDemo = false,
            errorMessage = "Failed to create demo video: ${e.localizedMessage}"
          )
        }
      }
    }
  }

  fun setPlaying(playing: Boolean) {
    _uiState.update { it.copy(isPlaying = playing) }
    if (playing) {
      startPlaybackTicker()
    } else {
      playbackTickerJob?.cancel()
      extractCurrentFrame()
    }
  }

  private fun startPlaybackTicker() {
    playbackTickerJob?.cancel()
    playbackTickerJob = viewModelScope.launch {
      while (isActive) {
        delay(100)
        val state = _uiState.value
        val meta = state.videoMetadata ?: break
        if (!state.isPlaying) break

        val next = state.currentPositionMs + 100
        if (next >= meta.durationMs) {
          _uiState.update { it.copy(currentPositionMs = 0L, isPlaying = false) }
          extractCurrentFrame()
          break
        } else {
          _uiState.update { it.copy(currentPositionMs = next) }
        }
      }
    }
  }

  fun seekTo(positionMs: Long) {
    val duration = _uiState.value.videoMetadata?.durationMs ?: return
    val clamped = positionMs.coerceIn(0L, duration)
    _uiState.update { it.copy(currentPositionMs = clamped) }
    extractCurrentFrame()
  }

  fun stepSeconds(deltaSeconds: Int) {
    val duration = _uiState.value.videoMetadata?.durationMs ?: return
    val target = (_uiState.value.currentPositionMs + deltaSeconds * 1000L).coerceIn(0L, duration)
    seekTo(target)
  }

  fun addRegion() {
    val currentRegions = _uiState.value.watermarkRegions
    val count = currentRegions.size
    // Stagger position for new boxes
    val offset = (count % 3) * 0.1f
    val newRegion = WatermarkRegion(
      left = (0.2f + offset).coerceIn(0.05f, 0.6f),
      top = (0.2f + offset).coerceIn(0.05f, 0.6f),
      right = (0.55f + offset).coerceIn(0.4f, 0.95f),
      bottom = (0.35f + offset).coerceIn(0.25f, 0.95f),
      mode = RemovalMode.INPAINT
    )
    _uiState.update {
      it.copy(
        watermarkRegions = currentRegions + newRegion,
        selectedRegionId = newRegion.id
      )
    }
    updateCleanedPreview()
  }

  fun removeSelectedRegion() {
    val selectedId = _uiState.value.selectedRegionId ?: return
    val updated = _uiState.value.watermarkRegions.filterNot { it.id == selectedId }
    _uiState.update {
      it.copy(
        watermarkRegions = updated,
        selectedRegionId = updated.firstOrNull()?.id
      )
    }
    updateCleanedPreview()
  }

  fun selectRegion(id: String) {
    _uiState.update { it.copy(selectedRegionId = id) }
  }

  fun updateRegionBounds(id: String, left: Float, top: Float, right: Float, bottom: Float) {
    _uiState.update { state ->
      val updated = state.watermarkRegions.map {
        if (it.id == id) it.copyWithBounds(left, top, right, bottom) else it
      }
      state.copy(watermarkRegions = updated)
    }
    updateCleanedPreview()
  }

  fun updateSelectedMode(mode: RemovalMode) {
    val selectedId = _uiState.value.selectedRegionId ?: return
    _uiState.update { state ->
      val updated = state.watermarkRegions.map {
        if (it.id == selectedId) it.copy(mode = mode) else it
      }
      state.copy(watermarkRegions = updated)
    }
    updateCleanedPreview()
  }

  fun updateSelectedIntensity(intensity: Float) {
    val selectedId = _uiState.value.selectedRegionId ?: return
    _uiState.update { state ->
      val updated = state.watermarkRegions.map {
        if (it.id == selectedId) it.copy(intensity = intensity) else it
      }
      state.copy(watermarkRegions = updated)
    }
    updateCleanedPreview()
  }

  fun updateSelectedFeather(feather: Float) {
    val selectedId = _uiState.value.selectedRegionId ?: return
    _uiState.update { state ->
      val updated = state.watermarkRegions.map {
        if (it.id == selectedId) it.copy(feather = feather) else it
      }
      state.copy(watermarkRegions = updated)
    }
    updateCleanedPreview()
  }

  fun applyPreset(preset: String) {
    val selectedId = _uiState.value.selectedRegionId ?: return
    val (l, t, r, b) = when (preset) {
      "top_left" -> listOf(0.05f, 0.05f, 0.35f, 0.18f)
      "top_right" -> listOf(0.65f, 0.05f, 0.95f, 0.18f)
      "bottom_left" -> listOf(0.05f, 0.82f, 0.35f, 0.95f)
      "bottom_right" -> listOf(0.65f, 0.82f, 0.95f, 0.95f)
      "center" -> listOf(0.25f, 0.40f, 0.75f, 0.60f)
      else -> listOf(0.65f, 0.05f, 0.95f, 0.18f)
    }
    updateRegionBounds(selectedId, l, t, r, b)
  }

  fun resetAllRegions() {
    _uiState.update {
      it.copy(watermarkRegions = emptyList(), selectedRegionId = null)
    }
    updateCleanedPreview()
  }

  fun togglePreviewProcessed() {
    _uiState.update { it.copy(isPreviewProcessed = !it.isPreviewProcessed) }
    if (_uiState.value.isPreviewProcessed) {
      updateCleanedPreview()
    }
  }

  private fun extractCurrentFrame() {
    frameExtractionJob?.cancel()
    frameExtractionJob = viewModelScope.launch(Dispatchers.IO) {
      val uri = _uiState.value.videoMetadata?.uri ?: return@launch
      val posMs = _uiState.value.currentPositionMs
      try {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(getApplication(), uri)
        val frame = retriever.getFrameAtTime(posMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
          ?: retriever.getFrameAtTime(posMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
        retriever.release()

        if (frame != null) {
          _uiState.update { it.copy(rawFrameBitmap = frame) }
          updateCleanedPreview()
        }
      } catch (e: Exception) {
        Log.w(tag, "Frame extraction error: ${e.message}")
      }
    }
  }

  private fun updateCleanedPreview() {
    val raw = _uiState.value.rawFrameBitmap ?: return
    val regions = _uiState.value.watermarkRegions
    viewModelScope.launch(Dispatchers.Default) {
      val cleaned = WatermarkFilter.processBitmap(raw, regions)
      _uiState.update { it.copy(cleanedFrameBitmap = cleaned) }
    }
  }

  fun startExport() {
    val metadata = _uiState.value.videoMetadata ?: return
    val regions = _uiState.value.watermarkRegions
    if (regions.isEmpty()) {
      _uiState.update { it.copy(errorMessage = "Please add at least one removal area before exporting.") }
      return
    }

    exportJob?.cancel()
    exportJob = viewModelScope.launch {
      _uiState.update { it.copy(exportState = ExportState.Preparing, errorMessage = null) }
      try {
        val result = exportEngine.exportVideo(
          sourceUri = metadata.uri,
          regions = regions,
          onProgress = { state ->
            _uiState.update { it.copy(exportState = state) }
          }
        )
        _uiState.update { it.copy(exportState = result) }
      } catch (e: Exception) {
        Log.e(tag, "Export failed: ${e.message}", e)
        _uiState.update {
          it.copy(
            exportState = if (e is InterruptedException) ExportState.Idle else ExportState.Error(e.localizedMessage ?: "Unknown export error")
          )
        }
      }
    }
  }

  fun cancelExport() {
    exportJob?.cancel()
    _uiState.update { it.copy(exportState = ExportState.Idle) }
  }

  fun dismissExportDialog() {
    _uiState.update { it.copy(exportState = ExportState.Idle) }
  }

  fun clearError() {
    _uiState.update { it.copy(errorMessage = null) }
  }

  override fun onCleared() {
    super.onCleared()
    playbackTickerJob?.cancel()
    frameExtractionJob?.cancel()
    exportJob?.cancel()
  }
}
