package com.example.zebralocalai.ui.industrial

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.zebralocalai.R
import kotlinx.coroutines.launch

private enum class IndustrialView(val label: String) {
  CHAT("Liquid Industrial"), CATALOG("Product catalog"), SOP("Operating Procedures"),
  TICKETS("My tickets"), TASKS("Task View"), LAB("Model Lab"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IndustrialScreen(
  state: IndustrialState,
  onClearChat: () -> Unit,
  onStop: () -> Unit,
  onCancelRequest: () -> Unit,
  onTalk: () -> Unit,
  onCancelRecording: () -> Unit,
  onSend: (String) -> Unit,
  onClearError: () -> Unit,
  onToggleTheme: () -> Unit,
  onRefreshTickets: () -> Unit,
  onLabRecord: () -> Unit = {},
  onLabNext: () -> Unit = {},
  onConfirm: (ConfirmationReference) -> Unit = {},
  onRefreshLatency: () -> Unit = {},
  onResetLatency: () -> Unit = {},
  onMode: (String) -> Unit = {},
  onCompleteForm: (String, Int) -> Unit = { _, _ -> },
  onSelectRead: (String) -> Unit = {},
  onReportChoice: (String, Boolean) -> Unit = { _, _ -> },
) {
  var selected by rememberSaveable { mutableStateOf(IndustrialView.CHAT) }
  var labTab by rememberSaveable { mutableIntStateOf(0) }
  var textMode by rememberSaveable { mutableStateOf(false) }
  var draft by rememberSaveable { mutableStateOf("") }
  val drawer = rememberDrawerState(DrawerValue.Closed)
  val scope = rememberCoroutineScope()
  val keyboard = LocalSoftwareKeyboardController.current
  LaunchedEffect(state.workerMode) { draft = ""; keyboard?.hide() }
  LaunchedEffect(selected) { if (selected == IndustrialView.TICKETS) onRefreshTickets() }
  LaunchedEffect(selected, labTab) { if (selected == IndustrialView.LAB && labTab == 1) onRefreshLatency() }
  val startNew = {
    if (!state.busy || state.canStop) {
      onClearChat(); selected = IndustrialView.CHAT; textMode = false; draft = ""; keyboard?.hide()
      scope.launch { drawer.close() }
    }
  }
  BackHandler(enabled = state.recording || drawer.isOpen || selected != IndustrialView.CHAT || textMode) {
    when {
      state.recording -> onCancelRecording()
      drawer.isOpen -> scope.launch { drawer.close() }
      selected != IndustrialView.CHAT -> selected = IndustrialView.CHAT
      else -> { textMode = false; keyboard?.hide() }
    }
  }
  ModalNavigationDrawer(
    drawerState = drawer,
    gesturesEnabled = !state.recording && !state.busy,
    drawerContent = {
      ModalDrawerSheet(Modifier.width(300.dp), drawerShape = RoundedCornerShape(0.dp)) {
        Column(Modifier.fillMaxHeight().padding(horizontal = 16.dp)) {
          Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.liquid_wordmark), "Liquid AI", Modifier.width(96.dp).height(32.dp), colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface))
            Spacer(Modifier.weight(1f))
            Button(onClick = onToggleTheme, modifier = Modifier.size(44.dp).semantics { contentDescription = if (state.darkTheme) "Switch to light mode" else "Switch to dark mode" }, shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(10.dp)) { ThemeGlyph(state.darkTheme) }
            IconButton(onClick = { scope.launch { drawer.close() } }) { Text("×", fontSize = 25.sp, modifier = Modifier.semantics { contentDescription = "Close menu" }) }
          }
          Text("demo app", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp, bottom = 8.dp))
          NavigationDrawerItem(label = { Text("Assistant") }, selected = selected == IndustrialView.CHAT,
            onClick = { selected = IndustrialView.CHAT; scope.launch { drawer.close() } })
          IndustrialView.entries.filter { it != IndustrialView.CHAT }.forEach { view ->
            NavigationDrawerItem(
              label = { Text(view.label, fontSize = 15.sp) },
              selected = selected == view,
              onClick = { selected = view; keyboard?.hide(); scope.launch { drawer.close() } },
              shape = RoundedCornerShape(6.dp),
              modifier = Modifier.height(44.dp),
            )
          }
          Spacer(Modifier.weight(1f).heightIn(min = 12.dp))
          Text("Separate feeds for General and SOP Advisor. Only the pending request is retained as model context.", fontSize = 12.sp, modifier = Modifier.padding(12.dp))
        }
      }
    },
  ) {
    Scaffold(
      topBar = {
        Surface(color = MaterialTheme.colorScheme.surface) {
          Column(Modifier.statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
              IconButton(onClick = { keyboard?.hide(); scope.launch { drawer.open() } }, enabled = !state.recording && !state.busy) { MenuGlyph() }
              Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(selected.label, fontSize = if (selected == IndustrialView.SOP) 17.sp else 19.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (state.recording) "Listening…" else if (state.busy) "Working…" else "Warehouse V2 · 1,300 updates", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
              }
              Button(onClick = startNew, enabled = !state.busy || state.canStop, shape = RoundedCornerShape(6.dp), contentPadding = PaddingValues(horizontal = 14.dp)) { Text("Clear") }
            }
            HorizontalDivider()
          }
        }
      },
      bottomBar = {
        if (selected == IndustrialView.CHAT) {
          Surface(color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.imePadding().navigationBarsPadding()) {
              HorizontalDivider()
              Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                if (state.canStop) {
                  OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth().height(76.dp), shape = RoundedCornerShape(8.dp)) {
                    Text("■  Stop", fontSize = 17.sp)
                  }
                } else if (textMode) {
                  TextComposer(draft, { draft = it }, enabled = !state.busy, onSend = {
                    if (draft.isNotBlank()) { onSend(draft); draft = ""; keyboard?.hide() }
                  })
                  Button(onClick = { textMode = false; keyboard?.hide() }, shape = RoundedCornerShape(6.dp), modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp)) {
                    MicGlyph(Modifier.size(17.dp)); Spacer(Modifier.width(8.dp)); Text("Talk instead")
                  }
                } else {
                  Button(onClick = onTalk, enabled = !state.busy, modifier = Modifier.fillMaxWidth().height(76.dp), shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
                    if (state.recording) Text("■", fontSize = 23.sp) else MicGlyph(Modifier.size(25.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(if (state.recording) "Stop recording" else if (state.busy) "Working…" else "Tap to talk", fontSize = 17.sp, fontWeight = FontWeight.Medium)
                  }
                  TextButton(onClick = { textMode = true }, enabled = !state.recording && !state.busy, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Type instead", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
              }
            }
          }
        }
      },
    ) { padding ->
      Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
        state.error?.let { error ->
          Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
              Text(error, Modifier.weight(1f), fontSize = 13.sp, color = MaterialTheme.colorScheme.onErrorContainer)
              TextButton(onClick = onClearError, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer)) { Text("Dismiss") }
            }
          }
        }
        when (selected) {
          IndustrialView.CHAT -> Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
              FilterChip(selected = state.workerMode == "general", onClick = { onMode("general") }, enabled = !state.busy && !state.recording, label = { Text("General") })
              FilterChip(selected = state.workerMode == "sop", onClick = { onMode("sop") }, enabled = !state.busy && !state.recording, label = { Text("SOP Advisor") })
              if (state.confirmable != null || state.formRequired) TextButton(onClick = onCancelRequest, enabled = !state.busy) { Text("Cancel") }
            }
            Text(if (state.workerMode == "sop") "Guidance first · Choose Yes to prepare a report" else
              "Stock · Locations · Replenishment · Assigned tasks", fontSize = 12.sp,
              modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            if (state.formRequired) RequestCompletionForm(state.products, state.workerMode == "sop", onCompleteForm)
            state.readChoices.forEach { (id, label) ->
              TextButton(onClick = { onSelectRead(id) }, enabled = !state.busy && !state.recording) { Text(label) }
            }
            Box(Modifier.weight(1f)) { ChatContent(state, onConfirm, onReportChoice) }
          }
          IndustrialView.CATALOG -> CatalogContent(state.products)
          IndustrialView.SOP -> SopContent(state.sops, onBackToChat = { selected = IndustrialView.CHAT })
          IndustrialView.TICKETS -> TicketsContent(state.tickets, state.ticketLoadFailed, onBackToChat = { selected = IndustrialView.CHAT })
          IndustrialView.TASKS -> TaskContent(state.taskSnapshot, state.taskLoadFailed, onBackToChat = { selected = IndustrialView.CHAT })
          IndustrialView.LAB -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Model Lab", fontSize = 24.sp)
            TabRow(selectedTabIndex = labTab) {
              Tab(selected = labTab == 0, onClick = { labTab = 0 }, text = { Text("Voice samples") })
              Tab(selected = labTab == 1, onClick = { labTab = 1 }, text = { Text("Latency") })
            }
            if (labTab == 1) {
              LatencyContent(state.latencySamples, state.latencyLoadFailed, onRefreshLatency,
                onResetLatency, !state.busy && !state.recording)
            } else {
            Text("Warehouse V2 · 1,300 updates")
            Text("Record short voice samples for evaluation. These recordings stay on this device and are kept out of training.")
            Text("Prompt ${state.labPromptIndex + 1} of ${WarehouseVoiceEvaluation.prompts.size}")
            Text(WarehouseVoiceEvaluation.prompts[state.labPromptIndex], fontSize = 20.sp)
            Button(onClick = onLabRecord, enabled = !state.busy && (!state.recording || state.labRecording)) {
              Text(if (state.labRecording) "Stop and save" else "Record sample")
            }
            if (state.labRecording) {
              RecordingWaveform(state.waveform)
              Text("Recording ${state.recordingElapsedMillis / 1000}s")
              TextButton(onClick = onCancelRecording) { Text("Cancel recording") }
            }
            TextButton(onClick = onLabNext, enabled = !state.recording && !state.busy) { Text("Next prompt") }
            Text("${state.labSavedCount} saved in this session. Recordings need transcript review before scoring.")
            }
          }
        }
      }
    }
  }
}

@Composable
private fun ChatContent(state: IndustrialState, onConfirm: (ConfirmationReference) -> Unit, onReportChoice: (String, Boolean) -> Unit) {
  if (state.recording) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        RecordingWaveform(state.waveform)
        Text("Listening…", fontSize = 28.sp, modifier = Modifier.padding(top = 24.dp, bottom = 16.dp))
        val seconds = (state.recordingElapsedMillis / 1000).coerceIn(0, 30)
        Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(6.dp)) {
          Text("00:${seconds.toString().padStart(2, '0')} / 00:30", color = MaterialTheme.colorScheme.onPrimary, fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
        Text("Stops automatically at 30 seconds.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
      }
    }
  } else if (state.messages.isEmpty()) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
          Image(painterResource(R.drawable.liquid_mark), "Liquid AI", Modifier.padding(14.dp).size(34.dp), colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface))
        }
        Text("What do you need?", fontSize = 28.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 24.dp, bottom = 10.dp))
        Text("Ask a question or report a concern.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
  } else {
    val list = rememberLazyListState()
    LaunchedEffect(state.activeChatId, state.messages.size) { list.animateScrollToItem(state.messages.lastIndex) }
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
      items(state.messages) { message ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.role == "user") Arrangement.End else Arrangement.Start) {
          if (message.role == "user") {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp, 8.dp, 2.dp, 8.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.widthIn(max = 290.dp)) {
              Text(message.text, Modifier.padding(14.dp), fontSize = 16.sp)
            }
          } else Column {
            if (message.tools.isNotEmpty()) Text("Tools: ${message.tools.joinToString(", ")}",
              fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(bottom = 6.dp))
            Text(message.text, fontSize = 16.sp, lineHeight = 24.sp)
            message.processingMillis?.let { millis ->
              Text("$millis ms", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 5.dp))
            }
            if (message === state.messages.lastOrNull()) state.reportOfferId?.let { offer ->
              Text("Create a report?", modifier = Modifier.padding(top = 12.dp))
              Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { onReportChoice(offer, true) }, enabled = !state.busy && !state.recording) { Text("Yes") }
                OutlinedButton(onClick = { onReportChoice(offer, false) }, enabled = !state.busy && !state.recording) { Text("No") }
              }
            }
            message.confirmation?.takeIf { it == state.confirmable }?.let { reference ->
              Button(onClick = { onConfirm(reference) }, enabled = !state.busy && !state.recording,
                modifier = Modifier.padding(top = 12.dp)) { Text("Submit") }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun TextComposer(value: String, onValueChange: (String) -> Unit, enabled: Boolean, onSend: () -> Unit) {
  val focus = remember { FocusRequester() }
  LaunchedEffect(Unit) { focus.requestFocus() }
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    OutlinedTextField(value, onValueChange, modifier = Modifier.weight(1f).focusRequester(focus), enabled = enabled,
      placeholder = { Text("Type a message…", fontSize = 14.sp) }, shape = RoundedCornerShape(6.dp), maxLines = 3,
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { if (enabled && value.isNotBlank()) onSend() }))
    Button(onClick = onSend, enabled = enabled && value.isNotBlank(), shape = RoundedCornerShape(6.dp), contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Send") }
  }
}

@Composable
private fun CatalogContent(products: List<IndustrialProduct>) {
  var query by rememberSaveable { mutableStateOf("") }
  var expanded by rememberSaveable { mutableStateOf<String?>(null) }
  val filtered = products.filter { "${it.name} ${it.category} ${it.color} ${it.size} ${it.sku} ${it.location}".contains(query.trim(), ignoreCase = true) }
  val groups = filtered.groupBy(IndustrialProduct::parentId)
  Column(Modifier.fillMaxSize()) {
    OutlinedTextField(query, { query = it }, placeholder = { Text("Search products or SKUs") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(16.dp), shape = RoundedCornerShape(6.dp))
    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      item { Text("${products.map { it.parentId }.distinct().size} products · ${products.size} variants", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
      item { Text("Demo inventory", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
      if (groups.isEmpty()) item { Text("No matching products.", Modifier.padding(vertical = 20.dp)) }
      items(groups.entries.toList(), key = { it.key }) { (id, variants) ->
        Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(6.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
          Column {
            Row(Modifier.fillMaxWidth().clickable { expanded = if (expanded == id) null else id }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
              Column(Modifier.weight(1f)) { Text(variants.first().name, fontWeight = FontWeight.Medium); Text("Section ${variants.first().section} · ${variants.size} variants", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
              Text(if (expanded == id) "−" else "+", fontSize = 23.sp)
            }
            if (expanded == id) variants.forEach { product ->
              HorizontalDivider()
              Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(listOf(product.color, product.size).filter(String::isNotBlank).joinToString(" · "), fontWeight = FontWeight.Medium)
                Text("Bin ${product.bin} · ${product.available} available", fontSize = 14.sp)
                Text("${product.onHand} on hand · ${product.reserved} reserved", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(product.sku, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Spoken: ${product.spokenLabel}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun SopContent(sops: List<IndustrialSop>, onBackToChat: () -> Unit) {
  var expanded by rememberSaveable { mutableStateOf<String?>(null) }
  val active = sops.firstOrNull { it.id == expanded }
  BackHandler(enabled = expanded != null) { expanded = null }
  Column(Modifier.fillMaxSize()) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
      Button(onClick = { if (expanded != null) expanded = null else onBackToChat() }, shape = RoundedCornerShape(6.dp)) {
        Text(if (active != null) "← All procedures" else "← Back to chat")
      }
      Spacer(Modifier.weight(1f))
      if (active == null) Text("${sops.size} guides", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    key(expanded) {
      LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Safety reference · draft for site review", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (active != null) {
          item {
            Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
              Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                Text(active.title, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium)
                GuideField("What to do", active.guidance)
                GuideField("Worker safety", active.workerSafety)
                GuideField("Clarify", active.clarification)
                GuideField("Report to", active.reportTo)
                GuideField("Get help", active.escalation)
                GuideField("What to say", active.callScript)
                GuideField("Location lookup", active.locationLookup)
                GuideField("Site information needed", active.requiredSiteData)
                GuideField("Demo", active.demoRule)
              }
            }
          }
        } else {
          val sections = listOf("Incidents" to "SOP-", "Worker care" to "CARE-", "Getting help" to "HELP-")
          sections.forEach { (title, prefix) ->
            item { Text(title, fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 10.dp)) }
            items(sops.filter { it.id.startsWith(prefix) }, key = IndustrialSop::id) { sop ->
              Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth().clickable { expanded = sop.id }) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                  Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(sop.title, fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium)
                    Text("Read guidance", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                  }
                  Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(6.dp)) {
                    Text("+", fontSize = 22.sp, color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun GuideField(label: String, value: String) {
  if (value.isNotBlank()) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(5.dp)) {
      Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp))
    }
    Text(value, fontSize = 16.sp, lineHeight = 25.sp)
  }
}

@Composable
private fun EmptyView(title: String, subtitle: String, detail: String) {
  Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
      Text(title, fontSize = 26.sp, fontWeight = FontWeight.Medium)
      Text(subtitle, fontSize = 16.sp)
      Text(detail, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

@Composable
private fun MenuGlyph() {
  val color = MaterialTheme.colorScheme.onSurface
  Canvas(Modifier.size(24.dp).semantics { contentDescription = "Open navigation menu" }) {
    for (y in listOf(0.22f, 0.5f, 0.78f)) drawLine(color, Offset(0f, size.height * y), Offset(size.width, size.height * y), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Square)
  }
}

@Composable
private fun MoreGlyph() {
  val color = MaterialTheme.colorScheme.onSurfaceVariant
  Canvas(Modifier.size(22.dp)) {
    for (x in listOf(.2f, .5f, .8f)) drawCircle(color, radius = 1.8.dp.toPx(), center = Offset(size.width * x, size.height / 2f))
  }
}

@Composable
private fun RecordingWaveform(levels: List<Float>) {
  val color = MaterialTheme.colorScheme.primary
  val current by animateFloatAsState(
    targetValue = WaveformLevel.display(levels.lastOrNull() ?: 0f),
    animationSpec = tween(60), label = "Microphone level",
  )
  Box(Modifier.size(228.dp), contentAlignment = Alignment.Center) {
    Canvas(Modifier.fillMaxSize().semantics { contentDescription = "Live circular microphone waveform" }) {
      val radius = size.minDimension / 2f
      drawCircle(color.copy(alpha = .12f), radius * .49f, style = Stroke(1.dp.toPx()))
      drawCircle(color.copy(alpha = .12f), radius * .37f, style = Stroke(1.dp.toPx()))
      for (index in 0 until 64) {
        val history = levels.getOrElse(index * levels.size / 64) { 0f }
        val amplitude = WaveformLevel.display(history) * .65f + current * .35f
        val angle = index * Math.PI * 2 / 64 - Math.PI / 2
        val direction = Offset(kotlin.math.cos(angle).toFloat(), kotlin.math.sin(angle).toFloat())
        val inner = radius * .515f
        val outer = inner + radius * (.023f + amplitude * .323f)
        drawLine(color.copy(alpha = .4f + amplitude * .6f), center + direction * inner, center + direction * outer, strokeWidth = 2.6.dp.toPx(), cap = StrokeCap.Round)
      }
    }
    MicGlyph(Modifier.size(32.dp))
  }
}

@Composable
private fun ThemeGlyph(showSun: Boolean) {
  val color = MaterialTheme.colorScheme.onPrimary
  Canvas(Modifier.size(23.dp)) {
    val center = Offset(size.width / 2f, size.height / 2f)
    val radius = size.minDimension * .23f
    if (showSun) {
      drawCircle(color, radius, center, style = Stroke(1.8.dp.toPx()))
      for (index in 0..7) {
        val angle = index * Math.PI / 4
        val start = radius * 1.5f; val end = radius * 2f
        drawLine(color, center + Offset((kotlin.math.cos(angle) * start).toFloat(), (kotlin.math.sin(angle) * start).toFloat()), center + Offset((kotlin.math.cos(angle) * end).toFloat(), (kotlin.math.sin(angle) * end).toFloat()), strokeWidth = 1.8.dp.toPx(), cap = StrokeCap.Round)
      }
    } else {
      drawCircle(color, size.minDimension * .4f, center)
      drawCircle(Color(0xFFF0BC46), size.minDimension * .36f, center + Offset(size.width * .18f, -size.height * .15f))
    }
  }
}

@Composable
private fun MicGlyph(modifier: Modifier = Modifier) {
  val color = LocalContentColor.current
  Canvas(modifier) {
    val stroke = size.width * 0.085f
    drawRoundRect(color, topLeft = Offset(size.width * .36f, size.height * .05f), size = androidx.compose.ui.geometry.Size(size.width * .28f, size.height * .53f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * .14f), style = Stroke(stroke))
    drawArc(color, 0f, 180f, false, topLeft = Offset(size.width * .2f, size.height * .3f), size = androidx.compose.ui.geometry.Size(size.width * .6f, size.height * .45f), style = Stroke(stroke, cap = StrokeCap.Round))
    drawLine(color, Offset(size.width * .5f, size.height * .75f), Offset(size.width * .5f, size.height * .95f), strokeWidth = stroke)
    drawLine(color, Offset(size.width * .33f, size.height * .95f), Offset(size.width * .67f, size.height * .95f), strokeWidth = stroke, cap = StrokeCap.Round)
  }
}
