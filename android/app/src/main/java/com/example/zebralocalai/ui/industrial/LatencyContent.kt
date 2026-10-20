package com.example.zebralocalai.ui.industrial

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private fun milliseconds(ms: Long) = "$ms ms"

@Composable
internal fun LatencyContent(samples: List<LatencySample>, failed: Boolean, onRefresh: () -> Unit,
                            onReset: () -> Unit = {}, resetEnabled: Boolean = true) {
  var mode by rememberSaveable { mutableStateOf("all") }
  var firstText by rememberSaveable { mutableStateOf(false) }
  val values = samples.filter { mode == "all" || it.mode == mode }
    .mapNotNull { if (firstText) it.firstTextMillis else it.totalMillis }.sorted()
  val p50 = latencyPercentile(values, 50)
  val p95 = latencyPercentile(values, 95)
  Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Text("Model generation latency", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
      TextButton(onClick = onReset, enabled = resetEnabled) { Text("Reset") }
    }
    Text("These timings measure the model request, not the full wait for a reply.", style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      listOf("all" to "All", "audio" to "Voice", "text" to "Text").forEach { (key, label) ->
        FilterChip(selected = mode == key, onClick = { mode = key }, label = { Text(label) })
      }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      FilterChip(selected = !firstText, onClick = { firstText = false }, label = { Text("Complete output") })
      FilterChip(selected = firstText, onClick = { firstText = true }, label = { Text("First text") })
    }
    if (failed) Text("Latency records could not be loaded. Retry with Refresh.")
    if (values.isEmpty()) {
      Text("No measured turns for this selection. Ask a question in chat, then refresh.")
    } else {
      Text("p50 ${milliseconds(requireNotNull(p50))}    ·    p95 ${milliseconds(requireNotNull(p95))}", style = MaterialTheme.typography.titleMedium)
      Text("${values.size} measured turns · ${milliseconds(values.first())}–${milliseconds(values.last())}", style = MaterialTheme.typography.bodySmall)
      if (values.size < 20) Text("Small sample: p95 is not yet a stable estimate.", style = MaterialTheme.typography.bodySmall)
      PercentileCurve(values)
    }
    Text("Most recent 64 logged turns on this device. Confirmations and turns without generation timings are excluded. Includes generated replies rejected by the harness. Records may span app or checkpoint changes.", style = MaterialTheme.typography.bodySmall)
    HorizontalDivider()
    Text("Voice reply timing", style = MaterialTheme.typography.titleMedium)
    Text("Recording stops → speech transcription → model receives original audio → harness validation and retrieval → reply appears.", style = MaterialTheme.typography.bodySmall)
    Text("The Voice filter shows model generation from audio. First text measures time to the model’s first text output; Complete output measures the model request through its final output. Neither includes the earlier transcription or later harness, procedure retrieval and display work.", style = MaterialTheme.typography.bodySmall)
    Text("The audio pacing fix adds transcription time to preserve words. It does not change model inference. Full round-trip latency is not measured in this chart.", style = MaterialTheme.typography.bodySmall)
    Text("Reset starts a fresh measurement window.", style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = onRefresh) { Text("Refresh") }
  }
}

@Composable
private fun PercentileCurve(values: List<Long>) {
  val color = MaterialTheme.colorScheme.primary
  val axis = MaterialTheme.colorScheme.outline
  val max = values.last().coerceAtLeast(1).toFloat()
  Column {
    Text("Latency ↑ · top ${milliseconds(max.toLong())}", style = MaterialTheme.typography.bodySmall)
    Canvas(Modifier.fillMaxWidth().height(160.dp).padding(vertical = 8.dp)
      .semantics { contentDescription = "Latency percentile curve. p50 ${milliseconds(latencyPercentile(values, 50)!!)}, p95 ${milliseconds(latencyPercentile(values, 95)!!)}" }) {
      val left = 4.dp.toPx()
      val width = size.width - left * 2
      val height = size.height - left
      drawLine(axis, Offset(left, 0f), Offset(left, height))
      drawLine(axis, Offset(left, height), Offset(left + width, height))
      val path = Path()
      for (percentile in 0..100) {
        val x = left + width * percentile / 100f
        val y = height * (1f - latencyPercentile(values, percentile)!! / max)
        if (percentile == 0) path.moveTo(x, y) else path.lineTo(x, y)
      }
      drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
      for (percentile in listOf(50, 95)) {
        val x = left + width * percentile / 100f
        val y = height * (1f - latencyPercentile(values, percentile)!! / max)
        drawLine(axis.copy(alpha = 0.5f), Offset(x, height), Offset(x, 0f))
        drawCircle(color, radius = 4.dp.toPx(), center = Offset(x, y))
      }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Text("0%", style = MaterialTheme.typography.labelSmall)
      Text("p50", style = MaterialTheme.typography.labelSmall)
      Text("p95 / 100%", style = MaterialTheme.typography.labelSmall)
    }
  }
}
