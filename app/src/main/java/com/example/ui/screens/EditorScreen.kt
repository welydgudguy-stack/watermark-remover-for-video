package com.example.ui.screens

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.model.RemovalMode
import com.example.ui.components.ExportProgressDialog
import com.example.ui.components.VideoPlayerSurface
import com.example.ui.components.WatermarkSelectionOverlay
import com.example.viewmodel.WatermarkViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
  viewModel: WatermarkViewModel,
  onNavigateBack: () -> Unit,
  modifier: Modifier = Modifier
) {
  val uiState by viewModel.uiState.collectAsState()
  val videoMeta = uiState.videoMetadata ?: return

  val videoAspect = if (videoMeta.height > 0) {
    videoMeta.width.toFloat() / videoMeta.height.toFloat()
  } else {
    16f / 9f
  }

  val selectedRegion = uiState.selectedRegion
  val verticalScroll = rememberScrollState()

  Surface(
    modifier = modifier
      .fillMaxSize()
      .statusBarsPadding()
      .navigationBarsPadding(),
    color = MaterialTheme.colorScheme.background
  ) {
    Column(modifier = Modifier.fillMaxSize()) {
      // Top Navigation Bar
      TopAppBar(
        title = {
          Column {
            Text(
              text = videoMeta.title,
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
            Text(
              text = "${videoMeta.resolutionLabel} • ${videoMeta.formattedDuration}",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
        },
        navigationIcon = {
          IconButton(
            onClick = onNavigateBack,
            modifier = Modifier.testTag("back_button")
          ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
          }
        },
        actions = {
          // Preview Cleaned Toggle
          IconButton(
            onClick = { viewModel.togglePreviewProcessed() },
            modifier = Modifier.testTag("preview_toggle_button")
          ) {
            Icon(
              imageVector = if (uiState.isPreviewProcessed) Icons.Default.Visibility else Icons.Default.VisibilityOff,
              contentDescription = stringResource(R.string.preview),
              tint = if (uiState.isPreviewProcessed) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
            )
          }

          // Export Video Button
          Button(
            onClick = { viewModel.startExport() },
            modifier = Modifier
              .padding(end = 8.dp)
              .testTag("export_button"),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            shape = RoundedCornerShape(12.dp)
          ) {
            Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text(stringResource(R.string.export), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
          }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
      )

      Column(
        modifier = Modifier
          .fillMaxSize()
          .verticalScroll(verticalScroll)
      ) {
        // Video Preview & Interactive Watermark Overlay Stage
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black)
        ) {
          VideoPlayerSurface(
            videoUri = videoMeta.uri,
            isPlaying = uiState.isPlaying,
            currentPositionMs = uiState.currentPositionMs,
            videoAspect = videoAspect,
            showProcessedPreview = uiState.isPreviewProcessed,
            processedBitmap = uiState.cleanedFrameBitmap,
            rawBitmap = uiState.rawFrameBitmap,
            onVideoPrepared = { _, _, _ -> },
            onPlaybackComplete = { viewModel.setPlaying(false) }
          )

          // Selection Box Overlay (only active when not in cleaned preview mode)
          if (!uiState.isPreviewProcessed) {
            WatermarkSelectionOverlay(
              regions = uiState.watermarkRegions,
              selectedRegionId = uiState.selectedRegionId,
              videoAspect = videoAspect,
              onRegionSelected = { viewModel.selectRegion(it) },
              onRegionBoundsChanged = { id, l, t, r, b ->
                viewModel.updateRegionBounds(id, l, t, r, b)
              }
            )
          } else {
            // Pill indicator for preview mode
            Surface(
              modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 12.dp),
              color = Color(0xCC0F172A),
              shape = RoundedCornerShape(20.dp)
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
              ) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Cleaned Preview Mode", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
              }
            }
          }
        }

        // Playback Timeline & Transport Controls
        Surface(
          color = MaterialTheme.colorScheme.surface,
          tonalElevation = 2.dp,
          modifier = Modifier.fillMaxWidth()
        ) {
          Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            // Slider Row
            Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically
            ) {
              val currentSec = uiState.currentPositionMs / 1000
              val totalSec = videoMeta.durationMs / 1000
              val posText = String.format("%02d:%02d", currentSec / 60, currentSec % 60)
              val durText = String.format("%02d:%02d", totalSec / 60, totalSec % 60)

              Text(
                text = posText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(42.dp)
              )

              Slider(
                value = uiState.currentPositionMs.toFloat(),
                onValueChange = { viewModel.seekTo(it.toLong()) },
                valueRange = 0f..videoMeta.durationMs.toFloat(),
                modifier = Modifier
                  .weight(1f)
                  .testTag("timeline_slider"),
                colors = SliderDefaults.colors(
                  thumbColor = MaterialTheme.colorScheme.primary,
                  activeTrackColor = MaterialTheme.colorScheme.primary
                )
              )

              Text(
                text = durText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(42.dp)
              )
            }

            // Transport Action Buttons
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.Center,
              verticalAlignment = Alignment.CenterVertically
            ) {
              IconButton(
                onClick = { viewModel.stepSeconds(-1) },
                modifier = Modifier.testTag("step_backward_button")
              ) {
                Icon(Icons.Default.Replay10, contentDescription = stringResource(R.string.step_backward))
              }

              Spacer(modifier = Modifier.width(16.dp))

              Box(
                modifier = Modifier
                  .size(52.dp)
                  .clip(CircleShape)
                  .background(MaterialTheme.colorScheme.primary)
                  .clickable { viewModel.setPlaying(!uiState.isPlaying) }
                  .testTag("play_pause_button"),
                contentAlignment = Alignment.Center
              ) {
                Icon(
                  imageVector = if (uiState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                  contentDescription = if (uiState.isPlaying) stringResource(R.string.pause) else stringResource(R.string.play),
                  tint = Color.White,
                  modifier = Modifier.size(32.dp)
                )
              }

              Spacer(modifier = Modifier.width(16.dp))

              IconButton(
                onClick = { viewModel.stepSeconds(1) },
                modifier = Modifier.testTag("step_forward_button")
              ) {
                Icon(Icons.Default.Forward10, contentDescription = stringResource(R.string.step_forward))
              }
            }
          }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Region Management Row: Area Tabs & Add/Delete
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text(
              text = "Removal Areas (${uiState.watermarkRegions.size})",
              style = MaterialTheme.typography.titleSmall,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onBackground
            )

            Row {
              // Add Area
              OutlinedButton(
                onClick = { viewModel.addRegion() },
                modifier = Modifier.testTag("add_region_button"),
                shape = RoundedCornerShape(10.dp)
              ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Add Area", fontSize = 12.sp)
              }

              if (selectedRegion != null) {
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                  onClick = { viewModel.removeSelectedRegion() },
                  modifier = Modifier.testTag("delete_region_button")
                ) {
                  Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.remove_selected_region), tint = MaterialTheme.colorScheme.error)
                }
              }
            }
          }

          // Active Region Selection Chips
          val chipScroll = rememberScrollState()
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .horizontalScroll(chipScroll)
              .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            uiState.watermarkRegions.forEachIndexed { index, region ->
              val isSelected = region.id == uiState.selectedRegionId
              FilterChip(
                selected = isSelected,
                onClick = { viewModel.selectRegion(region.id) },
                label = { Text("Area ${index + 1}: ${region.mode.displayName}") },
                colors = FilterChipDefaults.filterChipColors(
                  selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                  selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                modifier = Modifier.testTag("region_chip_$index")
              )
            }
          }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Removal Method Settings for Selected Region
        if (selectedRegion != null) {
          Card(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 16.dp)
              .testTag("region_settings_card"),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
          ) {
            Column(modifier = Modifier.padding(16.dp)) {
              Text(
                text = stringResource(R.string.removal_mode),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
              )

              Spacer(modifier = Modifier.height(10.dp))

              // Mode Selection Chips
              val modeScroll = rememberScrollState()
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .horizontalScroll(modeScroll),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
              ) {
                RemovalMode.values().forEach { mode ->
                  val isCurrent = selectedRegion.mode == mode
                  FilterChip(
                    selected = isCurrent,
                    onClick = { viewModel.updateSelectedMode(mode) },
                    label = { Text(mode.displayName) },
                    colors = FilterChipDefaults.filterChipColors(
                      selectedContainerColor = MaterialTheme.colorScheme.primary,
                      selectedLabelColor = Color.White
                    ),
                    modifier = Modifier.testTag("mode_${mode.name.lowercase()}")
                  )
                }
              }

              Spacer(modifier = Modifier.height(16.dp))

              // Strength / Intensity Slider
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
              ) {
                Text(
                  text = stringResource(R.string.intensity),
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                  text = "${(selectedRegion.intensity * 100).toInt()}%",
                  style = MaterialTheme.typography.bodyMedium,
                  fontWeight = FontWeight.SemiBold,
                  color = MaterialTheme.colorScheme.primary
                )
              }

              Slider(
                value = selectedRegion.intensity,
                onValueChange = { viewModel.updateSelectedIntensity(it) },
                valueRange = 0.1f..1.0f,
                modifier = Modifier.testTag("intensity_slider")
              )

              Spacer(modifier = Modifier.height(8.dp))

              // Feather Slider
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
              ) {
                Text(
                  text = stringResource(R.string.feather),
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                  text = "${(selectedRegion.feather * 100).toInt()}%",
                  style = MaterialTheme.typography.bodyMedium,
                  fontWeight = FontWeight.SemiBold,
                  color = MaterialTheme.colorScheme.primary
                )
              }

              Slider(
                value = selectedRegion.feather,
                onValueChange = { viewModel.updateSelectedFeather(it) },
                valueRange = 0f..0.8f,
                modifier = Modifier.testTag("feather_slider")
              )

              Spacer(modifier = Modifier.height(12.dp))

              // Quick Position Presets
              Text(
                text = stringResource(R.string.preset_watermarks),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )

              val presetScroll = rememberScrollState()
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .horizontalScroll(presetScroll)
                  .padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
              ) {
                PresetChip("Top Right", "top_right") { viewModel.applyPreset("top_right") }
                PresetChip("Top Left", "top_left") { viewModel.applyPreset("top_left") }
                PresetChip("Bottom Right", "bottom_right") { viewModel.applyPreset("bottom_right") }
                PresetChip("Bottom Left", "bottom_left") { viewModel.applyPreset("bottom_left") }
                PresetChip("Center", "center") { viewModel.applyPreset("center") }
              }
            }
          }
        } else {
          // Empty State Prompt
          Card(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
          ) {
            Column(
              modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
              horizontalAlignment = Alignment.CenterHorizontally
            ) {
              Text(
                text = stringResource(R.string.no_watermark_regions_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
              )
              Spacer(modifier = Modifier.height(12.dp))
              Button(
                onClick = { viewModel.addRegion() },
                modifier = Modifier.testTag("add_first_region_button")
              ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.add_watermark_region))
              }
            }
          }
        }

        Spacer(modifier = Modifier.height(32.dp))
      }
    }

    // Export Progress & Result Dialog
    ExportProgressDialog(
      exportState = uiState.exportState,
      onCancelExport = { viewModel.cancelExport() },
      onDismiss = { viewModel.dismissExportDialog() }
    )
  }
}

@Composable
private fun PresetChip(
  label: String,
  tag: String,
  onClick: () -> Unit
) {
  OutlinedButton(
    onClick = onClick,
    shape = RoundedCornerShape(10.dp),
    modifier = Modifier.testTag("preset_$tag")
  ) {
    Text(label, fontSize = 11.sp)
  }
}
