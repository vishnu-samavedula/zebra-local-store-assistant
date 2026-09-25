package com.example.zebralocalai.ui.main

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.zebralocalai.agent.ToolCallState
import com.example.zebralocalai.agent.ToolCallTrace
import com.example.zebralocalai.agent.ProductCandidate
import com.example.zebralocalai.agent.P1Tool
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
  state: StoreAssistantUiState,
  onImportModels: () -> Unit,
  onTalk: () -> Unit,
  onCancel: () -> Unit,
  onClear: () -> Unit,
  onConfirm: () -> Unit,
  onCancelAction: () -> Unit,
  onTask: (String) -> Unit,
) {
  Scaffold { insets ->
    StoreAssistantContent(
      state,
      onImportModels,
      onTalk,
      onCancel,
      onClear,
      onConfirm,
      onCancelAction,
      onTask,
      onTalk,
      Modifier.padding(insets),
    )
  }
}

@Composable
private fun StoreAssistantContent(
  state: StoreAssistantUiState,
  onImportModels: () -> Unit,
  onTalk: () -> Unit,
  onCancel: () -> Unit,
  onClear: () -> Unit,
  onConfirm: () -> Unit,
  onCancelAction: () -> Unit,
  onTask: (String) -> Unit,
  onContinue: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val hasResult = state.transcript.isNotBlank() && state.result != null
  Column(
    modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    StoreHeader()

    if (state.phase == P1Phase.MODEL_MISSING) {
      Button(onClick = onImportModels, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Text("Import audio + trained P1B models")
      }
    }

    if (state.phase == P1Phase.PROCESSING) {
      Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 28.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
      ) {
        Box(Modifier.padding(24.dp)) { Processing(state.status, onCancel) }
      }
    } else if (hasResult) {
      ResultCard(state, onClear, onConfirm, onCancelAction, onContinue)
    } else {
      VoiceHero(state, onTalk)
      CommonTasks(
        enabled = state.modelsWarm && state.phase in setOf(P1Phase.READY, P1Phase.COMPLETE, P1Phase.ERROR),
        onTask = onTask,
      )
      DemoCatalog(state.catalog)
      Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
      ) {
        Text(
          "On-device · audio ${state.importedAudioFiles.size}/4 · P1B Q4_K · 5 allowlisted tools",
          modifier = Modifier.padding(14.dp),
          style = MaterialTheme.typography.bodySmall,
          textAlign = TextAlign.Center,
        )
      }
      Box(Modifier.padding(horizontal = 20.dp)) { PrivacyNote() }
    }
    Spacer(Modifier.height(16.dp))
  }
}

@Composable
private fun ResultCard(
  state: StoreAssistantUiState,
  onClear: () -> Unit,
  onConfirm: () -> Unit,
  onCancelAction: () -> Unit,
  onContinue: () -> Unit,
) {
  val result = state.result ?: return
  Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp), shape = RoundedCornerShape(22.dp)) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      if (state.conversationTurns.isNotEmpty()) {
        Text(
          "REQUEST · TURN ${state.conversationTurns.size}/${com.example.zebralocalai.agent.P1ConversationSession.MAX_TURNS}",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.primary,
        )
      }
      if (state.conversationTurns.size > 1) {
        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)) {
          Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("Earlier context", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            state.conversationTurns.dropLast(1).forEach { turn ->
              Text("You: ${turn.workerText}", style = MaterialTheme.typography.bodySmall, maxLines = 2)
              Text("Assistant: ${turn.assistantText}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
          }
        }
      }
      Text("YOU SAID", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)) {
        Text(state.transcript, modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp), style = MaterialTheme.typography.bodyMedium)
      }
      Text("STORE ASSISTANT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
      Text(result.message, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)

      result.toolCalls.forEach { ToolCallCard(it) }

      result.proposal?.let { proposal ->
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
          Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (proposal.tool == P1Tool.REQUEST_REPLENISHMENT) {
              Text("Review replenishment", fontWeight = FontWeight.SemiBold)
              Text(proposal.arguments["product"].orEmpty())
              ReportField("SKU", proposal.arguments["sku"].orEmpty())
              ReportField("Requested", "${proposal.arguments["quantity"]} · ${proposal.arguments["quantity_mode"]?.replace('_', ' ')}")
              ReportField("Units to move", proposal.arguments["units_to_move"].orEmpty())
              ReportField("Destination", proposal.arguments["destination_location"].orEmpty())
            } else {
              Text("Review report", fontWeight = FontWeight.SemiBold)
              Text(proposal.arguments["description"].orEmpty())
              ReportField("SKU", proposal.arguments["sku"].orEmpty())
              ReportField("Quantity", proposal.arguments["quantity"].orEmpty())
              ReportField("Location", proposal.arguments["location"].orEmpty())
              ReportField("Category", proposal.arguments["category"].orEmpty().replace('_', ' '))
            }
          }
        }
      }

      result.verification?.let {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.10f)) {
          Text(it, modifier = Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall)
        }
      }

      if (state.phase == P1Phase.AWAITING_CONFIRMATION) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          OutlinedButton(onClick = onCancelAction, modifier = Modifier.weight(1f)) { Text("Cancel") }
          Button(onClick = onConfirm, modifier = Modifier.weight(1f)) { Text("Confirm") }
        }
      }

      if (state.canContinue && state.phase in setOf(P1Phase.COMPLETE, P1Phase.AWAITING_CONFIRMATION, P1Phase.ERROR)) {
        val needsDetails = result.prediction.missingFields.isNotEmpty()
        OutlinedButton(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
          Text(
            when {
              state.phase == P1Phase.AWAITING_CONFIRMATION -> "Change request by voice"
              needsDetails -> "Add missing details"
              else -> "Continue this request"
            },
          )
        }
      }

      P1Metrics(state)
      OutlinedButton(onClick = onClear, modifier = Modifier.fillMaxWidth()) { Text("New request") }
    }
  }
}

@Composable
private fun DemoCatalog(products: List<ProductCandidate>) {
  if (products.isEmpty()) return
  val families = products.groupBy(ProductCandidate::name).toList()
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.SpaceBetween) {
      Text("Demo catalog", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
      Text("${products.size} SKUs · ${families.size} products", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
    Row(
      Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      families.forEach { (name, variants) -> CatalogFamilyCard(name, variants) }
    }
    Text(
      "Swipe through product names and variants, then ask naturally by name, color, size, SKU, or location.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(horizontal = 20.dp),
    )
  }
}

@Composable
private fun CatalogFamilyCard(name: String, variants: List<ProductCandidate>) {
  val colors = variants.map(ProductCandidate::color).distinct().joinToString(" / ")
  val sizes = variants.map(ProductCandidate::size).distinct().joinToString(" / ")
  val locations = variants.map(ProductCandidate::location).distinct().joinToString(" / ")
  val example = variants.first()
  Surface(
    modifier = Modifier.width(230.dp).height(184.dp),
    shape = RoundedCornerShape(20.dp),
    color = MaterialTheme.colorScheme.surface,
    shadowElevation = 3.dp,
  ) {
    Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
      Text(name, fontWeight = FontWeight.Bold, maxLines = 2)
      Text(
        "${example.category.replaceFirstChar { it.uppercase() }} · ${variants.size} variant${if (variants.size == 1) "" else "s"}",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
      )
      Text("Colors: $colors", style = MaterialTheme.typography.bodySmall, maxLines = 1)
      Text("Sizes: $sizes", style = MaterialTheme.typography.bodySmall, maxLines = 1)
      Text("Locations: $locations", style = MaterialTheme.typography.bodySmall, maxLines = 1)
      Spacer(Modifier.weight(1f))
      Text(
        "Example ${example.sku} · ${example.available} available",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
      )
    }
  }
}

@Composable
private fun ToolCallCard(call: ToolCallTrace) {
  val needsDetails = call.state == ToolCallState.CANCELLED && call.resultSummary?.startsWith("Missing", ignoreCase = true) == true
  val stateLabel =
    when (call.state) {
      ToolCallState.PROPOSED -> "Review required"
      ToolCallState.EXECUTED -> "Completed"
      ToolCallState.VERIFIED -> "Verified"
      ToolCallState.CANCELLED -> if (needsDetails) "Needs details" else "Not run"
    }
  val stateColor =
    when (call.state) {
      ToolCallState.PROPOSED -> MaterialTheme.colorScheme.tertiary
      ToolCallState.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
      else -> MaterialTheme.colorScheme.primary
    }
  val friendlyName =
    when (call.tool) {
      P1Tool.INVENTORY_SEARCH -> "Inventory search"
      P1Tool.LOCATION_CONTENTS -> "Location contents"
      P1Tool.GET_TASK_STATUS -> "Task status"
      P1Tool.REPORT_ISSUE -> "Report issue"
      P1Tool.REQUEST_REPLENISHMENT -> "Replenishment"
      P1Tool.NONE -> "No tool"
    }
  Surface(
    shape = RoundedCornerShape(14.dp),
    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
  ) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(friendlyName, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text(stateLabel, style = MaterialTheme.typography.labelSmall, color = stateColor)
      }
      Text(call.tool.wireName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      call.resultSummary?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
  }
}

@Composable
private fun StoreHeader() {
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer) {
      Text("◇", modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp), color = MaterialTheme.colorScheme.primary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    }
    Column(Modifier.weight(1f)) {
      Text("Store Assistant", fontSize = 22.sp, fontWeight = FontWeight.Bold)
      Text("Find. Check. Report. Faster.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
      Spacer(Modifier.height(5.dp))
      RuntimePills()
    }
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
      Text("TC", modifier = Modifier.padding(10.dp), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
    }
  }
}

@Composable
private fun VoiceHero(state: StoreAssistantUiState, onTalk: () -> Unit) {
  val recording = state.phase == P1Phase.RECORDING
  val enabled = state.phase in setOf(P1Phase.READY, P1Phase.RECORDING, P1Phase.COMPLETE, P1Phase.ERROR)
  Column(
    modifier = Modifier.fillMaxWidth(),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(140.dp)) {
      Surface(modifier = Modifier.size(140.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.05f)) {}
      Surface(modifier = Modifier.size(116.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)) {}
      Button(
        onClick = onTalk,
        enabled = enabled,
        modifier = Modifier.size(90.dp),
        shape = CircleShape,
        contentPadding = PaddingValues(0.dp),
        colors =
          ButtonDefaults.buttonColors(
            containerColor = if (recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
          ),
      ) {
        MicrophoneMark(Modifier.size(38.dp))
      }
    }
    Text(
      if (recording) "Tap to stop and analyze" else "Tap and speak",
      fontSize = 20.sp,
      fontWeight = FontWeight.SemiBold,
    )
    Text(
      if (recording) "Listening on this device…" else "Ask about stock, locations, or report an issue.",
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
      modifier = Modifier.padding(horizontal = 24.dp),
    )
    Text(
      state.status,
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.primary,
    )
  }
}

@Composable
private fun MicrophoneMark(modifier: Modifier = Modifier) {
  Canvas(modifier) {
    val stroke = size.width * 0.10f
    drawRoundRect(
      color = Color.White,
      topLeft = Offset(size.width * 0.35f, size.height * 0.04f),
      size = Size(size.width * 0.30f, size.height * 0.55f),
      cornerRadius = CornerRadius(size.width * 0.16f),
    )
    drawArc(
      color = Color.White,
      startAngle = 0f,
      sweepAngle = 180f,
      useCenter = false,
      topLeft = Offset(size.width * 0.20f, size.height * 0.24f),
      size = Size(size.width * 0.60f, size.height * 0.55f),
      style = Stroke(width = stroke, cap = StrokeCap.Round),
    )
    drawLine(Color.White, Offset(size.width * 0.5f, size.height * 0.78f), Offset(size.width * 0.5f, size.height * 0.94f), stroke, StrokeCap.Round)
    drawLine(Color.White, Offset(size.width * 0.34f, size.height * 0.94f), Offset(size.width * 0.66f, size.height * 0.94f), stroke, StrokeCap.Round)
  }
}

@Composable
private fun CommonTasks(enabled: Boolean, onTask: (String) -> Unit) {
  val recipes =
    listOf(
      TaskRecipe("▣", "Check stock", "Black GTX · size 10", "How many black GTX shoes size 10 do we have?"),
      TaskRecipe("⌖", "Find a SKU", "Blue GTX · SKU 1842", "Check inventory for SKU-1842-BLU-105."),
      TaskRecipe("▤", "Inspect a bin", "Everything in D3-01", "What's stored in location D3-01?"),
      TaskRecipe("✓", "Check a task", "Status of T-122", "What's the status of task T-122?"),
      TaskRecipe("!", "Report damage", "12 shoes · A3-05", "Generate a damage report for exactly 12 units of SKU-1809-BLK-090 at A3-05."),
      TaskRecipe("⚠", "Report blockage", "Fallen pallet · B2-01", "Report location B2-01 blocked by a fallen pallet."),
      TaskRecipe("+", "Add inventory", "8 slides · C1-02", "Add exactly 8 units of SKU-4103-BLK-100 to C1-02."),
      TaskRecipe("⇧", "Request restock", "12 navy shirts · B2-04", "Request replenishment: add exactly 12 units of SKU-2202-NVY-M to B2-04."),
      TaskRecipe("Ⅱ", "Check two items", "Wallet + pallet wrap", "Check both Tan Metro Zip Wallet size One Size and Clear DockPro Pallet Wrap size 500 m."),
      TaskRecipe("Ⅱ", "Find two products", "T-shirts + jeans", "Where can I find Harbor Classic T shirts and Foundry Straight jeans?"),
    )
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
      Text("Try a recipe", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
    Row(
      Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      recipes.forEach { recipe ->
        TaskCard(recipe.symbol, recipe.title, recipe.description, enabled) { onTask(recipe.prompt) }
      }
    }
    Text(
      "Swipe through reads, confirmed writes, and parallel read calls. Every tile runs through P1B inference.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(horizontal = 20.dp),
    )
  }
}

private data class TaskRecipe(
  val symbol: String,
  val title: String,
  val description: String,
  val prompt: String,
)

@Composable
private fun TaskCard(
  symbol: String,
  title: String,
  description: String,
  enabled: Boolean,
  onClick: () -> Unit,
) {
  Surface(
    onClick = onClick,
    enabled = enabled,
    modifier = Modifier.width(164.dp).height(126.dp),
    shape = RoundedCornerShape(20.dp),
    color = MaterialTheme.colorScheme.surface,
    shadowElevation = 3.dp,
  ) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
      Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Text(symbol, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
      }
      Column {
        Text(title, fontWeight = FontWeight.Bold)
        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
  }
}

@Composable
private fun Processing(label: String, onCancel: () -> Unit) {
  Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
    CircularProgressIndicator()
    Text(label, style = MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick = onCancel) { Text("Cancel") }
  }
}

@Composable
private fun ReportField(label: String, value: String) {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
    Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
  }
}

@Composable
private fun P1Metrics(state: StoreAssistantUiState) {
  val result = state.result ?: return
  Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        MetricValue("Total", state.totalElapsedMillis?.let(::formatSeconds) ?: "—")
        MetricValue("ASR TTFS", state.audioTtfsMillis?.let { "$it ms" } ?: "—")
        MetricValue("P1B", "${result.timings.encoderMillis} ms")
        MetricValue("Decode", state.p1bDecodeTokensPerSecond?.let { String.format(Locale.US, "%.1f t/s", it) } ?: "—")
      }
      val detail = buildList {
        if (state.modelsWarm) add("Warm session")
        state.coldLoadMillis?.let { add("startup ${formatSeconds(it)} once") }
        state.p1bPromptTokensPerSecond?.let { add("prefill ${String.format(Locale.US, "%.1f", it)} t/s") }
        add("search ${result.timings.searchMillis} ms")
        if (result.timings.toolMillis > 0) add("tool ${result.timings.toolMillis} ms")
      }
      Text(detail.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

@Composable
private fun MetricValue(label: String, value: String) {
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text(value, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

@Composable
private fun PrivacyNote() {
  Text(
    "Mic audio is temporary and deleted after local inference. No cloud services are used.",
    style = MaterialTheme.typography.bodySmall,
    textAlign = TextAlign.Center,
    modifier = Modifier.fillMaxWidth(),
  )
}

@Composable
private fun RuntimePills() {
  Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    RuntimePill("CPU", active = true)
    RuntimePill("NPU", active = false)
  }
}

@Composable
private fun RuntimePill(label: String, active: Boolean) {
  val container = if (active) Color(0xFFE2F5E9) else Color(0xFFE9E9ED)
  val content = if (active) Color(0xFF137A43) else Color(0xFF73737D)
  Surface(shape = CircleShape, color = container) {
    Text(
      "● $label",
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
      color = content,
      fontSize = 10.sp,
      fontWeight = FontWeight.SemiBold,
    )
  }
}

private fun formatSeconds(milliseconds: Long): String = String.format(Locale.US, "%.2f s", milliseconds / 1_000.0)
