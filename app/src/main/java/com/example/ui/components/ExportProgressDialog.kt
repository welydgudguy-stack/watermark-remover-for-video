package com.example.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.model.ExportState
import java.io.File
import kotlin.math.roundToInt

@Composable
fun ExportProgressDialog(
  exportState: ExportState,
  onCancelExport: () -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current

  when (exportState) {
    is ExportState.Idle -> {}

    is ExportState.Preparing, is ExportState.Exporting -> {
      val progress = if (exportState is ExportState.Exporting) exportState.progress else 0f
      val percent = (progress * 100).roundToInt().coerceIn(0, 100)
      val statusText = if (exportState is ExportState.Exporting) exportState.statusText else stringResource(R.string.exporting_video)
      val frameInfo = if (exportState is ExportState.Exporting && exportState.totalFrames > 0) {
        "Frame ${exportState.currentFrame} of ${exportState.totalFrames}"
      } else {
        "Preparing video encoder…"
      }

      Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
      ) {
        Surface(
          shape = RoundedCornerShape(24.dp),
          color = MaterialTheme.colorScheme.surface,
          tonalElevation = 8.dp,
          modifier = modifier
            .fillMaxWidth()
            .testTag("export_progress_dialog")
        ) {
          Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
          ) {
            Text(
              text = stringResource(R.string.exporting_video),
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(20.dp))

            Box(contentAlignment = Alignment.Center) {
              CircularProgressIndicator(
                progress = { progress },
                modifier = Modifier.size(80.dp),
                strokeWidth = 7.dp,
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
              )
              Text(
                text = "$percent%",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
              )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
              text = statusText,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              textAlign = TextAlign.Center
            )

            Text(
              text = frameInfo,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.primary,
              fontWeight = FontWeight.Medium,
              modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(modifier = Modifier.height(20.dp))

            LinearProgressIndicator(
              progress = { progress },
              modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
              color = MaterialTheme.colorScheme.primary,
              trackColor = MaterialTheme.colorScheme.surfaceVariant
            )

            Spacer(modifier = Modifier.height(20.dp))

            OutlinedButton(
              onClick = onCancelExport,
              modifier = Modifier.testTag("cancel_export_button")
            ) {
              Text(stringResource(R.string.cancel_export))
            }
          }
        }
      }
    }

    is ExportState.Completed -> {
      AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
          Box(
            modifier = Modifier
              .size(56.dp)
              .clip(CircleShape)
              .background(Color(0xFF10B981).copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
          ) {
            Icon(
              imageVector = Icons.Default.CheckCircle,
              contentDescription = null,
              tint = Color(0xFF10B981),
              modifier = Modifier.size(36.dp)
            )
          }
        },
        title = {
          Text(
            text = stringResource(R.string.export_success),
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
          )
        },
        text = {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
              text = stringResource(R.string.export_success_desc),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
              Button(
                onClick = { playSavedVideo(context, exportState.outputUri, exportState.outputFile) },
                modifier = Modifier
                  .weight(1f)
                  .testTag("play_exported_button"),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
              ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.play_video), fontSize = 13.sp)
              }

              OutlinedButton(
                onClick = { shareSavedVideo(context, exportState.outputUri, exportState.outputFile) },
                modifier = Modifier
                  .weight(1f)
                  .testTag("share_exported_button")
              ) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.share_video), fontSize = 13.sp)
              }
            }
          }
        },
        confirmButton = {
          Button(
            onClick = onDismiss,
            modifier = Modifier.testTag("done_export_button")
          ) {
            Text(stringResource(R.string.done))
          }
        },
        modifier = Modifier.testTag("export_success_dialog")
      )
    }

    is ExportState.Error -> {
      AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
          Icon(
            imageVector = Icons.Default.Error,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(40.dp)
          )
        },
        title = { Text(stringResource(R.string.export_error)) },
        text = { Text(exportState.message) },
        confirmButton = {
          Button(onClick = onDismiss) {
            Text(stringResource(R.string.close))
          }
        },
        modifier = Modifier.testTag("export_error_dialog")
      )
    }
  }
}

private fun playSavedVideo(context: Context, uri: Uri, file: File) {
  try {
    val intent = Intent(Intent.ACTION_VIEW).apply {
      setDataAndType(uri, "video/mp4")
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Play Cleaned Video"))
  } catch (_: Exception) {
    try {
      val fileUri = androidx.core.content.FileProvider.getUriForFile(
        context,
        "${context.packageName}.provider",
        file
      )
      val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(fileUri, "video/mp4")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
      context.startActivity(Intent.createChooser(intent, "Play Cleaned Video"))
    } catch (_: Exception) {}
  }
}

private fun shareSavedVideo(context: Context, uri: Uri, file: File) {
  try {
    val intent = Intent(Intent.ACTION_SEND).apply {
      type = "video/mp4"
      putExtra(Intent.EXTRA_STREAM, uri)
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share Cleaned Video"))
  } catch (_: Exception) {
    try {
      val fileUri = androidx.core.content.FileProvider.getUriForFile(
        context,
        "${context.packageName}.provider",
        file
      )
      val intent = Intent(Intent.ACTION_SEND).apply {
        type = "video/mp4"
        putExtra(Intent.EXTRA_STREAM, fileUri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
      context.startActivity(Intent.createChooser(intent, "Share Cleaned Video"))
    } catch (_: Exception) {}
  }
}
