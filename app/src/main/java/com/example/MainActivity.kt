package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.example.ui.screens.EditorScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.WatermarkViewModel

class MainActivity : ComponentActivity() {

  private val viewModel: WatermarkViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    setContent {
      MyApplicationTheme(darkTheme = true) {
        val uiState by viewModel.uiState.collectAsState()
        val snackbarHostState = remember { SnackbarHostState() }

        LaunchedEffect(uiState.errorMessage) {
          uiState.errorMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearError()
          }
        }

        Scaffold(
          modifier = Modifier.fillMaxSize(),
          snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { _ ->
          if (uiState.videoMetadata == null) {
            HomeScreen(
              isGeneratingDemo = uiState.isGeneratingDemo,
              onVideoSelected = { uri -> viewModel.loadVideo(uri) },
              onDemoSelected = { viewModel.loadDemoVideo() }
            )
          } else {
            BackHandler {
              viewModel.clearVideo()
            }

            EditorScreen(
              viewModel = viewModel,
              onNavigateBack = {
                viewModel.clearVideo()
              }
            )
          }
        }
      }
    }
  }
}

