package com.example.zebralocalai

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import com.example.zebralocalai.theme.ZebraLocalAITheme
import com.example.zebralocalai.ui.industrial.IndustrialScreen
import com.example.zebralocalai.ui.industrial.IndustrialViewModel

class MainActivity : ComponentActivity() {
  private lateinit var industrial: IndustrialViewModel

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    industrial = ViewModelProvider(this)[IndustrialViewModel::class.java]
    enableEdgeToEdge()
    setContent {
      val state by industrial.state.collectAsStateWithLifecycle()
      LaunchedEffect(state.darkTheme) {
        val transparent = android.graphics.Color.TRANSPARENT
        val bars = if (state.darkTheme) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
      }
      ZebraLocalAITheme(darkTheme = state.darkTheme, dynamicColor = false) {
        val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
          if (granted) industrial.toggleRecording() else industrial.permissionDenied()
        }
        val labPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
          if (granted) industrial.toggleLabRecording() else industrial.permissionDenied()
        }
        IndustrialScreen(
          state = state,
          onClearChat = industrial::clearChat,
          onStop = industrial::stopProcessing,
          onCancelRequest = industrial::cancelPendingRequest,
          onTalk = {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
              industrial.toggleRecording()
            } else permission.launch(Manifest.permission.RECORD_AUDIO)
          },
          onCancelRecording = industrial::cancelRecording,
          onSend = industrial::submitText,
          onClearError = industrial::clearError,
          onToggleTheme = industrial::toggleTheme,
          onRefreshTickets = industrial::refreshTickets,
          onLabRecord = {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
              industrial.toggleLabRecording()
            } else labPermission.launch(Manifest.permission.RECORD_AUDIO)
          },
          onLabNext = industrial::nextLabPrompt,
          onConfirm = industrial::confirmTicket,
          onRefreshLatency = industrial::refreshLatency,
          onResetLatency = industrial::resetLatency,
          onMode = industrial::setWorkerMode,
          onCompleteForm = industrial::completeRequestForm,
          onSelectRead = industrial::selectRead,
          onReportChoice = industrial::chooseReport,
        )
      }
    }
  }

  override fun onResume() {
    super.onResume()
    if (::industrial.isInitialized) industrial.refreshRetention()
  }

  override fun onStop() {
    if (::industrial.isInitialized) industrial.cancelRecording()
    super.onStop()
  }
}
