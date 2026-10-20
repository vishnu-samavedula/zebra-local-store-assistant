package com.example.zebralocalai.ui.industrial

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun RequestCompletionForm(products: List<IndustrialProduct>, report: Boolean, submit: (String, Int) -> Unit) {
  var open by remember { mutableStateOf(false) }
  var search by remember { mutableStateOf("") }
  var selected by remember { mutableStateOf<IndustrialProduct?>(null) }
  var quantity by remember { mutableStateOf("") }
  TextButton(onClick = { open = true }) { Text("Complete request form") }
  if (open && report) {
    AlertDialog(onDismissRequest = { open = false }, title = { Text("Report details") },
      text = { OutlinedTextField(search, { search = it }, label = { Text("What happened?") }) },
      confirmButton = { Button(onClick = { submit(search, 0); open = false }, enabled = search.isNotBlank()) { Text("Review report") } },
      dismissButton = { TextButton(onClick = { open = false }) { Text("Back") } })
  } else if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text("Replenishment request") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(search, { search = it; selected = null }, label = { Text("Find product") })
        LazyColumn(Modifier.heightIn(max = 180.dp)) {
          items(products.filter { it.name.contains(search, true) || it.sku.contains(search, true) }.take(30)) { product ->
            TextButton(onClick = { selected = product }) {
              Text("${if (selected?.id == product.id) "✓ " else ""}${product.name} · ${product.color} ${product.size}")
            }
          }
        }
        OutlinedTextField(quantity, { quantity = it.filter(Char::isDigit) }, label = { Text("Additional quantity") })
        Text("Review and confirm the preview before creating a ticket.")
      }
    }, confirmButton = {
      Button(onClick = { submit(requireNotNull(selected).id, requireNotNull(quantity.toIntOrNull())); open = false },
        enabled = selected != null && (quantity.toIntOrNull() ?: 0) > 0) { Text("Review request") }
    }, dismissButton = { TextButton(onClick = { open = false }) { Text("Back") } })
}
