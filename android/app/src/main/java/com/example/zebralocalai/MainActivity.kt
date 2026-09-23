package com.example.zebralocalai

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.zebralocalai.theme.ZebraLocalAITheme
import com.example.zebralocalai.ui.main.MainScreen
import com.example.zebralocalai.ui.main.Experience
import com.example.zebralocalai.ui.main.P0ViewModel

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    enableEdgeToEdge()
    setContent {
      ZebraLocalAITheme(dynamicColor = false) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
          val viewModel: P0ViewModel = viewModel()
          val state by viewModel.state.collectAsStateWithLifecycle()
          val p1State by viewModel.p1State.collectAsStateWithLifecycle()
          var selectedTab by rememberSaveable { mutableStateOf(0) }
          LaunchedEffect(selectedTab) {
            if (selectedTab == 1) viewModel.prepareP1()
          }
          var requestedExperience by remember { mutableStateOf(Experience.P0) }
          val microphonePermission =
            rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
              viewModel.onMicrophonePermissionResult(granted, requestedExperience)
            }
          val modelPicker =
            rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
              if (uris.isNotEmpty()) viewModel.importModels(uris)
            }

          MainScreen(
            state = state,
            p1State = p1State,
            selectedTab = selectedTab,
            onSelectTab = { selectedTab = it },
            onImportModels = { modelPicker.launch(arrayOf("application/octet-stream", "*/*")) },
            onTalk = {
              if (state.microphonePermissionGranted) viewModel.toggleRecording()
              else {
                requestedExperience = Experience.P0
                microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
              }
            },
            onP1Talk = {
              if (state.microphonePermissionGranted) viewModel.toggleP1Recording()
              else {
                requestedExperience = Experience.P1
                microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
              }
            },
            onCancel = viewModel::cancel,
            onP1Cancel = viewModel::cancelP1,
            onClear = viewModel::clearResult,
            onP1Clear = viewModel::clearP1Result,
            onConfirmP1 = viewModel::confirmP1Action,
            onCancelP1Action = viewModel::cancelP1Action,
            onP1Task = viewModel::submitP1Text,
          )
        }
      }
    }
  }
}
