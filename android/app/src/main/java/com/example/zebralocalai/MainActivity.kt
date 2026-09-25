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
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.zebralocalai.theme.ZebraLocalAITheme
import com.example.zebralocalai.ui.main.MainScreen
import com.example.zebralocalai.ui.main.StoreAssistantViewModel

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    enableEdgeToEdge()
    setContent {
      ZebraLocalAITheme(dynamicColor = false) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
          val viewModel: StoreAssistantViewModel = viewModel()
          val state by viewModel.state.collectAsStateWithLifecycle()
          LaunchedEffect(Unit) { viewModel.prepare() }
          val microphonePermission =
            rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
              viewModel.onMicrophonePermissionResult(granted)
            }
          val modelPicker =
            rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
              if (uris.isNotEmpty()) viewModel.importModels(uris)
            }

          MainScreen(
            state = state,
            onImportModels = { modelPicker.launch(arrayOf("application/octet-stream", "*/*")) },
            onTalk = {
              if (state.microphonePermissionGranted) viewModel.toggleRecording()
              else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
            },
            onCancel = viewModel::cancel,
            onClear = viewModel::clearResult,
            onConfirm = viewModel::confirmAction,
            onCancelAction = viewModel::cancelAction,
            onTask = viewModel::submitText,
          )
        }
      }
    }
  }
}
