package com.example.zebralocalai.ui.industrial

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.json.JSONObject

data class IndustrialTask(
  val id: String, val description: String, val details: String,
  val location: String, val status: String, val updatedAt: String,
)

data class IndustrialTaskSnapshot(val workerLabel: String, val updatedAt: String, val tasks: List<IndustrialTask>) {
  companion object {
    fun parse(json: JSONObject): IndustrialTaskSnapshot {
      val worker = json.getString("worker_id")
      require(worker.isNotBlank() && json.getBoolean("synthetic"))
      val rows = json.getJSONArray("tasks")
      val tasks = (0 until rows.length()).map { rows.getJSONObject(it) }
        .filter { it.getString("worker_id") == worker }.map {
          IndustrialTask(it.getString("id"), it.getString("description"), it.getString("details"),
            it.getString("location"), it.getString("status"), it.getString("updated_at"))
        }
      require(tasks.map { it.id }.distinct().size == tasks.size)
      return IndustrialTaskSnapshot(json.getString("worker_label"), json.getString("updated_at"), tasks)
    }
  }
}

/** Only persisted, worker-confirmed harness records are presented here. */
data class IndustrialTicket(val id: String, val title: String, val category: String,
  val createdAt: String, val fields: List<Pair<String, String>>) {
  companion object {
    fun parse(record: JSONObject): IndustrialTicket {
      require(record.getString("status") == "created_locally")
      val args = record.getJSONObject("arguments")
      val tool = record.getString("tool")
      val title = when (tool) {
        "create_report" -> "Report"
        "report_general_concern" -> "General concern"
        "report_product_damage" -> "Product damage"
        "request_replenishment" -> "Replenishment request"
        else -> error("Unsupported ticket type")
      }
      val fields = buildList {
        listOf("product_name" to "Product", "report_text" to "Report", "observation" to "Observation",
          "damage_description" to "Damage", "location" to "Observed location").forEach { (key, label) ->
          if (args.has(key)) add(label to args.getString(key))
        }
        args.optJSONObject("variant")?.let { variant ->
          add("Variant" to listOf("color", "size").map { variant.optString(it) }.filter(String::isNotBlank).joinToString(" · "))
        }
        if (tool !in setOf("report_general_concern", "create_report")) add(
          (if (tool == "request_replenishment") "Additional quantity" else "Affected quantity") to
            (if (args.has("quantity")) args.getInt("quantity").toString() else "Unknown"))
      }
      return IndustrialTicket(record.getString("reference"), title, record.getString("category"),
        record.getString("created_at"), fields)
    }
  }
}

@Composable
fun TaskContent(snapshot: IndustrialTaskSnapshot?, failed: Boolean, onBackToChat: () -> Unit) {
  var expanded by rememberSaveable { mutableStateOf<String?>(null) }
  BackHandler(enabled = expanded != null) { expanded = null }
  WorkList(onBackToChat) {
    item { Text("Assigned work", fontSize = 24.sp, fontWeight = FontWeight.Medium) }
    when {
      failed -> item { Text("Task snapshot could not be opened. This is not an empty assignment list.") }
      snapshot == null -> item { Text("Loading assigned tasks…") }
      else -> {
        item {
          Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            WorkBadge("Synthetic snapshot · ${snapshot.workerLabel}")
            Text("Updated ${workTime(snapshot.updatedAt)}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Read-only · no live synchronization", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }
        if (snapshot.tasks.isEmpty()) item { Text("No assigned tasks in this snapshot.") }
        items(snapshot.tasks, key = { it.id }) { task ->
          WorkCard(task.description, task.id, task.location, task.status, expanded == task.id,
            onClick = { expanded = if (expanded == task.id) null else task.id }) {
            Text(task.details, fontSize = 16.sp, lineHeight = 24.sp)
            WorkField("Updated", workTime(task.updatedAt))
          }
        }
      }
    }
  }
}

@Composable
fun TicketsContent(tickets: List<IndustrialTicket>?, failed: Boolean, onBackToChat: () -> Unit) {
  var expanded by rememberSaveable { mutableStateOf<String?>(null) }
  BackHandler(enabled = expanded != null) { expanded = null }
  WorkList(onBackToChat) {
    item { Text("My tickets", fontSize = 24.sp, fontWeight = FontWeight.Medium) }
    when {
      failed -> item { Text("Saved tickets could not be opened. This is not an empty ticket list.") }
      tickets == null -> item { Text("Loading saved tickets…") }
      tickets.isEmpty() -> item { Text("No tickets yet. Confirm a report or replenishment request in chat to create one.") }
      else -> {
        item { Text("Created locally does not mean sent or reviewed.", fontSize = 13.sp) }
        items(tickets, key = { it.id }) { ticket ->
          WorkCard(ticket.title, ticket.id, ticket.category, "Created locally",
            expanded == ticket.id, onClick = { expanded = if (expanded == ticket.id) null else ticket.id }) {
            ticket.fields.forEach { (label, value) -> WorkField(label, value) }
            WorkField("Created", workTime(ticket.createdAt))
          }
        }
      }
    }
  }
}

@Composable
private fun WorkList(onBackToChat: () -> Unit, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
  Column(Modifier.fillMaxSize()) {
    Button(onClick = onBackToChat, modifier = Modifier.padding(16.dp), shape = RoundedCornerShape(6.dp)) { Text("← Back to chat") }
    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
  }
}

@Composable
private fun WorkCard(title: String, reference: String, subtitle: String, status: String, expanded: Boolean, onClick: () -> Unit, details: @Composable ColumnScope.() -> Unit) {
  Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
    Column {
      Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(reference, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)
        Text(subtitle, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        WorkBadge(status)
        Text(if (expanded) "Hide details −" else "View details +", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      if (expanded) {
        HorizontalDivider()
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = details)
      }
    }
  }
}

@Composable
private fun WorkBadge(text: String) {
  Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(6.dp)) {
    Text(text, color = MaterialTheme.colorScheme.onPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
  }
}

@Composable
private fun WorkField(label: String, value: String) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(value, fontSize = 15.sp, lineHeight = 22.sp)
  }
}

private fun workTime(value: String): String = runCatching {
  DateTimeFormatter.ofPattern("d MMM yyyy · HH:mm z").withZone(ZoneId.systemDefault()).format(Instant.parse(value))
}.getOrDefault(value)
