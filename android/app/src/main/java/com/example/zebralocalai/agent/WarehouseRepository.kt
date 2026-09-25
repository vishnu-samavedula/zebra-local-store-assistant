package com.example.zebralocalai.agent

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject

class SqliteWarehouseRepository(context: Context, databaseName: String = "warehouse-p1a.db") : WarehouseRepository {
  private val database = WarehouseDatabase(context.applicationContext, databaseName)

  override fun catalog(): List<ProductCandidate> {
    val products = mutableListOf<ProductCandidate>()
    database.readableDatabase
      .query("products", PRODUCT_COLUMNS, null, null, null, null, "name, color, size")
      .use { cursor -> while (cursor.moveToNext()) products += cursor.toCandidate(0.0) }
    return products
  }

  override fun search(query: String, skuHint: String?): List<ProductCandidate> {
    val queryTokens = query.searchTokens()
    val candidates = mutableListOf<ProductCandidate>()
    database.readableDatabase
      .query("products", PRODUCT_COLUMNS, null, null, null, null, null)
      .use { cursor ->
        while (cursor.moveToNext()) {
          val sku = cursor.string("sku")
          val searchable =
            listOf(
                sku,
                cursor.string("product_id"),
                cursor.string("name"),
                cursor.string("category"),
                cursor.string("brand"),
                cursor.string("color"),
                cursor.string("size"),
                cursor.string("location"),
                cursor.string("aliases"),
              )
              .joinToString(" ")
              .searchTokens()
          val exactSku = skuHint != null && sku.removePrefix("SKU-").take(4) == skuHint
          val tokenScore = queryTokens.count(searchable::contains).toDouble()
          val score = (if (exactSku) 20.0 else 0.0) + tokenScore
          if (score > 0) candidates += cursor.toCandidate(score)
        }
      }
    val sorted = candidates.sortedByDescending(ProductCandidate::score)
    val bestScore = sorted.firstOrNull()?.score
    return sorted.filter { it.score == bestScore }.take(3)
  }

  override fun locationContents(location: String, semanticQuery: String?): List<ProductCandidate> {
    val tokens = semanticQuery.orEmpty().searchTokens()
    val products = mutableListOf<ProductCandidate>()
    database.readableDatabase
      .query(
        "products",
        PRODUCT_COLUMNS,
        "location = ? COLLATE NOCASE",
        arrayOf(location.trim()),
        null,
        null,
        "name, color, size",
      )
      .use { cursor ->
        while (cursor.moveToNext()) {
          val candidate = cursor.toCandidate(0.0)
          val searchable = "${candidate.sku} ${candidate.name} ${candidate.category} ${candidate.color} ${candidate.size}".lowercase(Locale.US)
          if (tokens.isEmpty() || tokens.all(searchable::contains)) products += candidate
        }
      }
    return products
  }

  override fun findTasks(taskId: String?, taskType: String?, status: String?): List<WarehouseTask> {
    val clauses = mutableListOf<String>()
    val values = mutableListOf<String>()
    taskId?.takeIf(String::isNotBlank)?.let { clauses += "task_id = ? COLLATE NOCASE"; values += it.trim() }
    taskType?.takeIf(String::isNotBlank)?.let { clauses += "task_type = ? COLLATE NOCASE"; values += it.trim() }
    status?.takeIf(String::isNotBlank)?.let { clauses += "status = ? COLLATE NOCASE"; values += it.trim() }
    if (clauses.isEmpty()) {
      clauses += "assigned_worker = ?"
      clauses += "status != ?"
      values += "JS"
      values += "complete"
    }
    val tasks = mutableListOf<WarehouseTask>()
    database.readableDatabase
      .query(
        "tasks",
        TASK_COLUMNS,
        clauses.joinToString(" AND "),
        values.toTypedArray(),
        null,
        null,
        "task_id",
        "20",
      )
      .use { cursor -> while (cursor.moveToNext()) tasks += cursor.toWarehouseTask() }
    return tasks
  }

  override fun createIssue(proposal: ToolProposal, sourceTranscript: String): StoredIssue {
    require(proposal.tool == P1Tool.REPORT_ISSUE)
    val category = proposal.arguments.getValue("category")
    val productId = proposal.arguments["product_id"]
    val quantity = proposal.arguments["quantity"]?.toInt()
    val location = proposal.arguments.getValue("location")
    val product = productId?.let(::productLabel)
    val status = proposal.arguments["status"] ?: "OPEN"
    val priority = proposal.arguments["priority"] ?: issuePriorityFor(category)
    val description =
      proposal.arguments["description"]
        ?: listOfNotNull(quantity?.toString(), category.replace('_', ' '), product, "at $location").joinToString(" ")
    val createdAt = System.currentTimeMillis()
    val db = database.writableDatabase
    db.beginTransaction()
    try {
      val values =
        ContentValues().apply {
          put("issue_code", "PENDING")
          put("description", description)
          put("category", category)
          put("status", status)
          put("priority", priority)
          if (productId == null) putNull("product_id") else put("product_id", productId)
          if (quantity == null) putNull("quantity") else put("quantity", quantity)
          put("location", location)
          put("source_transcript", sourceTranscript)
          put("payload_json", "{}")
          put("created_at", createdAt)
          put("updated_at", createdAt)
        }
      val rowId = db.insertOrThrow("issues", null, values)
      val issueId = "ISS-${String.format(Locale.US, "%06d", rowId)}"
      val payload =
        JSONObject()
          .put("tool", proposal.tool.wireName)
          .put("issue_id", issueId)
          .put("description", description)
          .put("category", category)
          .put("status", status)
          .put("priority", priority)
          .put("product_id", productId ?: JSONObject.NULL)
          .put("sku", proposal.arguments["sku"])
          .put("quantity", quantity ?: JSONObject.NULL)
          .put("location", location)
          .put("source_transcript", sourceTranscript)
          .put("created_at_epoch_ms", createdAt)
          .toString()
      db.update(
        "issues",
        ContentValues().apply {
          put("issue_code", issueId)
          put("payload_json", payload)
        },
        "id = ?",
        arrayOf(rowId.toString()),
      )
      db.setTransactionSuccessful()
      return requireNotNull(getIssue(issueId))
    } finally {
      db.endTransaction()
    }
  }

  override fun getIssue(issueId: String): StoredIssue? =
    database.readableDatabase
      .query("issues", ISSUE_COLUMNS, "issue_code = ?", arrayOf(issueId), null, null, null, "1")
      .use { cursor -> if (cursor.moveToFirst()) cursor.toStoredIssue() else null }

  override fun createReplenishment(proposal: ToolProposal, sourceTranscript: String): StoredReplenishment {
    require(proposal.tool == P1Tool.REQUEST_REPLENISHMENT)
    val args = proposal.arguments
    val productId = args.getValue("product_id")
    val sku = args.getValue("sku")
    val quantity = args.getValue("quantity").toInt()
    val unitsToMove = args.getValue("units_to_move").toInt()
    val quantityMode = args.getValue("quantity_mode")
    val destination = args.getValue("destination_location")
    val reason = args["reason"].orEmpty()
    val taskId = args["task_id"]?.takeIf(String::isNotBlank)
    val status = "REQUESTED"
    val createdAt = System.currentTimeMillis()
    val db = database.writableDatabase
    db.beginTransaction()
    try {
      val values =
        ContentValues().apply {
          put("request_code", "PENDING")
          put("product_id", productId)
          put("sku", sku)
          put("quantity", quantity)
          put("units_to_move", unitsToMove)
          put("quantity_mode", quantityMode)
          put("destination_location", destination)
          put("reason", reason)
          if (taskId == null) putNull("task_id") else put("task_id", taskId)
          put("status", status)
          put("source_transcript", sourceTranscript)
          put("payload_json", "{}")
          put("created_at", createdAt)
          put("updated_at", createdAt)
        }
      val rowId = db.insertOrThrow("replenishment_requests", null, values)
      val requestId = "REP-${String.format(Locale.US, "%06d", rowId)}"
      val payload =
        JSONObject()
          .put("tool", proposal.tool.wireName)
          .put("request_id", requestId)
          .put("product_id", productId)
          .put("sku", sku)
          .put("quantity", quantity)
          .put("units_to_move", unitsToMove)
          .put("quantity_mode", quantityMode)
          .put("destination_location", destination)
          .put("reason", reason)
          .put("task_id", taskId)
          .put("status", status)
          .put("source_transcript", sourceTranscript)
          .put("created_at_epoch_ms", createdAt)
          .toString()
      db.update(
        "replenishment_requests",
        ContentValues().apply { put("request_code", requestId); put("payload_json", payload) },
        "id = ?",
        arrayOf(rowId.toString()),
      )
      db.setTransactionSuccessful()
      return requireNotNull(getReplenishment(requestId))
    } finally {
      db.endTransaction()
    }
  }

  override fun getReplenishment(requestId: String): StoredReplenishment? =
    database.readableDatabase
      .query(
        "replenishment_requests",
        REPLENISHMENT_COLUMNS,
        "request_code = ?",
        arrayOf(requestId),
        null,
        null,
        null,
        "1",
      )
      .use { cursor -> if (cursor.moveToFirst()) cursor.toStoredReplenishment() else null }

  private fun productLabel(productId: String): String =
    database.readableDatabase
      .query("products", arrayOf("name", "color", "size"), "product_id = ?", arrayOf(productId), null, null, null, "1")
      .use { cursor ->
        if (cursor.moveToFirst()) "${cursor.string("name")} ${cursor.string("color")} ${cursor.string("size")}" else productId
      }

  private fun Cursor.toCandidate(score: Double) =
    ProductCandidate(
      productId = string("product_id"),
      sku = string("sku"),
      name = string("name"),
      variant = listOf(string("color"), string("size")).filter(String::isNotBlank).joinToString(" · "),
      location = string("location"),
      onHand = int("on_hand"),
      reserved = int("reserved"),
      score = score,
      category = string("category"),
      brand = string("brand"),
      color = string("color"),
      size = string("size"),
      unit = string("unit"),
      barcode = string("barcode"),
      attributesJson = string("attributes_json"),
    )

  private fun Cursor.toStoredIssue() =
    StoredIssue(
      issueId = string("issue_code"),
      description = string("description"),
      category = string("category"),
      status = string("status"),
      priority = string("priority"),
      productId = nullableString("product_id"),
      quantity = nullableInt("quantity"),
      location = string("location"),
      sourceTranscript = string("source_transcript"),
      payloadJson = string("payload_json"),
      createdAtEpochMillis = long("created_at"),
    )

  private fun Cursor.toWarehouseTask() =
    WarehouseTask(
      taskId = string("task_id"),
      taskType = string("task_type"),
      status = string("status"),
      sku = string("sku"),
      quantity = int("quantity"),
      sourceLocation = string("source_location"),
      destinationLocation = string("destination_location"),
      assignedWorker = string("assigned_worker"),
    )

  private fun Cursor.toStoredReplenishment() =
    StoredReplenishment(
      requestId = string("request_code"),
      productId = string("product_id"),
      sku = string("sku"),
      quantity = int("quantity"),
      unitsToMove = int("units_to_move"),
      quantityMode = string("quantity_mode"),
      destinationLocation = string("destination_location"),
      reason = string("reason"),
      taskId = nullableString("task_id"),
      status = string("status"),
      sourceTranscript = string("source_transcript"),
      payloadJson = string("payload_json"),
      createdAtEpochMillis = long("created_at"),
    )

  companion object {
    private val PRODUCT_COLUMNS =
      arrayOf(
        "product_id", "sku", "name", "category", "brand", "color", "size", "unit", "barcode",
        "location", "on_hand", "reserved", "aliases", "attributes_json",
      )
    private val ISSUE_COLUMNS =
      arrayOf(
        "issue_code", "description", "category", "status", "priority", "product_id", "quantity",
        "location", "source_transcript", "payload_json", "created_at",
      )
    private val TASK_COLUMNS =
      arrayOf("task_id", "task_type", "status", "sku", "quantity", "source_location", "destination_location", "assigned_worker")
    private val REPLENISHMENT_COLUMNS =
      arrayOf(
        "request_code", "product_id", "sku", "quantity", "units_to_move", "quantity_mode", "destination_location",
        "reason", "task_id", "status", "source_transcript", "payload_json", "created_at",
      )
  }
}

private class WarehouseDatabase(context: Context, databaseName: String) : SQLiteOpenHelper(context, databaseName, null, 5) {
  private val appContext = context.applicationContext

  override fun onConfigure(db: SQLiteDatabase) {
    super.onConfigure(db)
    db.setForeignKeyConstraintsEnabled(true)
  }

  override fun onCreate(db: SQLiteDatabase) {
    db.execSQL(
      """
      CREATE TABLE products (
        product_id TEXT PRIMARY KEY,
        sku TEXT NOT NULL UNIQUE,
        name TEXT NOT NULL,
        category TEXT NOT NULL,
        brand TEXT NOT NULL,
        color TEXT NOT NULL,
        size TEXT NOT NULL,
        unit TEXT NOT NULL,
        barcode TEXT NOT NULL UNIQUE,
        location TEXT NOT NULL,
        on_hand INTEGER NOT NULL,
        reserved INTEGER NOT NULL,
        aliases TEXT NOT NULL,
        attributes_json TEXT NOT NULL
      )
      """.trimIndent(),
    )
    db.execSQL("CREATE INDEX idx_products_sku ON products(sku)")
    db.execSQL("CREATE INDEX idx_products_location ON products(location)")
    db.execSQL(
      """
      CREATE TABLE issues (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        issue_code TEXT NOT NULL UNIQUE,
        description TEXT NOT NULL,
        category TEXT NOT NULL,
        status TEXT NOT NULL,
        priority TEXT NOT NULL,
        product_id TEXT,
        quantity INTEGER,
        location TEXT NOT NULL,
        source_transcript TEXT NOT NULL,
        payload_json TEXT NOT NULL,
        created_at INTEGER NOT NULL,
        updated_at INTEGER NOT NULL,
        FOREIGN KEY(product_id) REFERENCES products(product_id)
      )
      """.trimIndent(),
    )
    createTasksTable(db)
    createReplenishmentsTable(db)
    seedCatalog(db)
    seedTasks(db)
  }

  override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    if (oldVersion < 2) {
      createTasksTable(db)
      seedCatalog(db)
      seedTasks(db)
    }
    if (oldVersion < 3) createReplenishmentsTable(db)
    if (oldVersion < 4) addColumnIfMissing(db, "replenishment_requests", "units_to_move", "INTEGER NOT NULL DEFAULT 0")
    if (oldVersion < 5) migrateIssuesToOptionalProduct(db)
  }

  private fun migrateIssuesToOptionalProduct(db: SQLiteDatabase) {
    db.execSQL("ALTER TABLE issues RENAME TO issues_legacy")
    db.execSQL(
      """
      CREATE TABLE issues (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        issue_code TEXT NOT NULL UNIQUE,
        description TEXT NOT NULL,
        category TEXT NOT NULL,
        status TEXT NOT NULL,
        priority TEXT NOT NULL,
        product_id TEXT,
        quantity INTEGER,
        location TEXT NOT NULL,
        source_transcript TEXT NOT NULL,
        payload_json TEXT NOT NULL,
        created_at INTEGER NOT NULL,
        updated_at INTEGER NOT NULL,
        FOREIGN KEY(product_id) REFERENCES products(product_id)
      )
      """.trimIndent(),
    )
    db.execSQL(
      """
      INSERT INTO issues
        (id, issue_code, description, category, status, priority, product_id, quantity, location,
         source_transcript, payload_json, created_at, updated_at)
      SELECT id, issue_code, description, category, status, priority, product_id, quantity, location,
             source_transcript, payload_json, created_at, updated_at
      FROM issues_legacy
      """.trimIndent(),
    )
    db.execSQL("DROP TABLE issues_legacy")
  }

  private fun createTasksTable(db: SQLiteDatabase) {
    db.execSQL(
      """
      CREATE TABLE IF NOT EXISTS tasks (
        task_id TEXT PRIMARY KEY,
        task_type TEXT NOT NULL,
        status TEXT NOT NULL,
        sku TEXT NOT NULL,
        quantity INTEGER NOT NULL,
        source_location TEXT NOT NULL,
        destination_location TEXT NOT NULL,
        assigned_worker TEXT NOT NULL,
        FOREIGN KEY(sku) REFERENCES products(sku)
      )
      """.trimIndent(),
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_tasks_status ON tasks(status)")
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_tasks_worker ON tasks(assigned_worker)")
  }

  private fun createReplenishmentsTable(db: SQLiteDatabase) {
    db.execSQL(
      """
      CREATE TABLE IF NOT EXISTS replenishment_requests (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        request_code TEXT NOT NULL UNIQUE,
        product_id TEXT NOT NULL,
        sku TEXT NOT NULL,
        quantity INTEGER NOT NULL,
        units_to_move INTEGER NOT NULL,
        quantity_mode TEXT NOT NULL,
        destination_location TEXT NOT NULL,
        reason TEXT NOT NULL,
        task_id TEXT,
        status TEXT NOT NULL,
        source_transcript TEXT NOT NULL,
        payload_json TEXT NOT NULL,
        created_at INTEGER NOT NULL,
        updated_at INTEGER NOT NULL,
        FOREIGN KEY(product_id) REFERENCES products(product_id),
        FOREIGN KEY(sku) REFERENCES products(sku),
        FOREIGN KEY(task_id) REFERENCES tasks(task_id)
      )
      """.trimIndent(),
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_replenishment_status ON replenishment_requests(status)")
  }

  private fun addColumnIfMissing(db: SQLiteDatabase, table: String, column: String, definition: String) {
    val exists =
      db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
        val nameIndex = cursor.getColumnIndexOrThrow("name")
        var found = false
        while (cursor.moveToNext()) if (cursor.getString(nameIndex) == column) found = true
        found
      }
    if (!exists) db.execSQL("ALTER TABLE $table ADD COLUMN $column $definition")
  }

  private fun seedCatalog(db: SQLiteDatabase) {
    readJsonLines("warehouse_catalog.jsonl").forEach { product ->
      val values =
        ContentValues().apply {
          put("product_id", product.getString("product_id"))
          put("sku", product.getString("sku"))
          put("name", product.getString("name"))
          put("category", product.getString("category"))
          put("brand", product.getString("brand"))
          put("color", product.getString("color"))
          put("size", product.getString("size"))
          put("unit", product.getString("unit"))
          put("barcode", product.getString("barcode"))
          put("location", product.getString("location"))
          put("on_hand", product.getInt("on_hand"))
          put("reserved", product.getInt("reserved"))
          put("aliases", product.getJSONArray("aliases").let { aliases -> (0 until aliases.length()).joinToString("|") { aliases.getString(it) } })
          put("attributes_json", product.getJSONObject("attributes").toString())
        }
      db.insertWithOnConflict(
        "products",
        null,
        values,
        SQLiteDatabase.CONFLICT_IGNORE,
      )
      db.update("products", values, "product_id = ?", arrayOf(product.getString("product_id")))
    }
  }

  private fun seedTasks(db: SQLiteDatabase) {
    readJsonLines("warehouse_tasks.jsonl").forEach { task ->
      val values =
        ContentValues().apply {
          put("task_id", task.getString("task_id"))
          put("task_type", task.getString("task_type"))
          put("status", task.getString("status"))
          put("sku", task.getString("sku"))
          put("quantity", task.getInt("quantity"))
          put("source_location", task.getString("source_location"))
          put("destination_location", task.getString("destination_location"))
          put("assigned_worker", task.getString("assigned_worker"))
        }
      db.insertWithOnConflict("tasks", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }
  }

  private fun readJsonLines(assetName: String): List<JSONObject> =
    appContext.assets.open(assetName).bufferedReader().useLines { lines ->
      lines.filter(String::isNotBlank).map(::JSONObject).toList()
    }
}

class InMemoryWarehouseRepository : WarehouseRepository {
  private val sequence = AtomicInteger(1000)
  private val issues = mutableMapOf<String, StoredIssue>()
  private val replenishments = mutableMapOf<String, StoredReplenishment>()

  override fun catalog(): List<ProductCandidate> = products

  override fun search(query: String, skuHint: String?): List<ProductCandidate> {
    val tokens = query.searchTokens()
    return products
      .map { product ->
        val searchable = "${product.sku} ${product.productId} ${product.name} ${product.variant} ${product.category} ${product.color} ${product.size}".searchTokens()
        val exactSku = skuHint != null && product.sku.removePrefix("SKU-").take(4) == skuHint
        product.copy(score = (if (exactSku) 20.0 else 0.0) + tokens.count(searchable::contains))
      }
      .filter { it.score > 0 }
      .sortedByDescending(ProductCandidate::score)
      .let { sorted -> sorted.filter { it.score == sorted.firstOrNull()?.score } }
      .take(3)
  }

  override fun locationContents(location: String, semanticQuery: String?): List<ProductCandidate> =
    products.filter { product ->
      product.location.equals(location, ignoreCase = true) &&
        (semanticQuery.isNullOrBlank() || semanticQuery.searchTokens().all { token ->
          "${product.sku} ${product.name} ${product.category} ${product.color} ${product.size}".lowercase().contains(token)
        })
    }

  override fun findTasks(taskId: String?, taskType: String?, status: String?): List<WarehouseTask> =
    tasks.filter { task ->
      (taskId.isNullOrBlank() || task.taskId.equals(taskId, true)) &&
        (taskType.isNullOrBlank() || task.taskType.equals(taskType, true)) &&
        (status.isNullOrBlank() || task.status.equals(status, true))
    }

  override fun createIssue(proposal: ToolProposal, sourceTranscript: String): StoredIssue {
    val issueId = "ISS-${sequence.incrementAndGet()}"
    val category = proposal.arguments.getValue("category")
    val issue =
      StoredIssue(
        issueId = issueId,
        description = sourceTranscript,
        category = category,
        status = "OPEN",
        priority = issuePriorityFor(category),
        productId = proposal.arguments["product_id"],
        quantity = proposal.arguments["quantity"]?.toInt(),
        location = proposal.arguments.getValue("location"),
        sourceTranscript = sourceTranscript,
        payloadJson = "",
        createdAtEpochMillis = System.currentTimeMillis(),
      )
    val completed =
      issue.copy(
        payloadJson =
          "{\"tool\":\"report_issue\",\"issue_id\":\"${issueId.jsonEscape()}\"," +
            "\"description\":\"${issue.description.jsonEscape()}\",\"category\":\"${category.jsonEscape()}\"," +
            "\"status\":\"OPEN\",\"priority\":\"${issue.priority.jsonEscape()}\"," +
            "\"product_id\":${issue.productId?.let { "\"${it.jsonEscape()}\"" } ?: "null"}," +
            "\"quantity\":${issue.quantity ?: "null"},\"location\":\"${issue.location.jsonEscape()}\"}",
      )
    issues[issueId] = completed
    return completed
  }

  override fun getIssue(issueId: String): StoredIssue? = issues[issueId]

  override fun createReplenishment(proposal: ToolProposal, sourceTranscript: String): StoredReplenishment {
    val requestId = "REP-${sequence.incrementAndGet()}"
    val args = proposal.arguments
    val stored =
      StoredReplenishment(
        requestId = requestId,
        productId = args.getValue("product_id"),
        sku = args.getValue("sku"),
        quantity = args.getValue("quantity").toInt(),
        unitsToMove = args.getValue("units_to_move").toInt(),
        quantityMode = args.getValue("quantity_mode"),
        destinationLocation = args.getValue("destination_location"),
        reason = args["reason"].orEmpty(),
        taskId = args["task_id"],
        status = "REQUESTED",
        sourceTranscript = sourceTranscript,
        payloadJson = "{\"tool\":\"request_replenishment\",\"request_id\":\"$requestId\"}",
        createdAtEpochMillis = System.currentTimeMillis(),
      )
    replenishments[requestId] = stored
    return stored
  }

  override fun getReplenishment(requestId: String): StoredReplenishment? = replenishments[requestId]

  companion object {
    private val products =
      listOf(
        ProductCandidate("PROD-TRAILBLAZE-GTX-BLU-105", "SKU-1842-BLU-105", "TrailBlaze GTX", "Blue · 10.5", "A3-04", 18, 4, 0.0, "footwear", "Northstar", "Blue", "10.5", "pair"),
        ProductCandidate("PROD-TRAILBLAZE-GTX-BLK-100", "SKU-1843-BLK-100", "TrailBlaze GTX", "Black · 10", "A3-05", 10, 2, 0.0, "footwear", "Northstar", "Black", "10", "pair"),
        ProductCandidate("PROD-TITAN-CARTON-STD", "SKU-5520-STD", "Titan Shipping Carton", "Kraft · 24x18x18 in", "B7", 42, 6, 0.0, "packaging", "Titan", "Kraft", "24x18x18 in", "carton"),
        ProductCandidate("PROD-NORTHLINE-VEST-YEL-L", "SKU-7311-YEL-L", "Northline Safety Vest", "Yellow · L", "C4", 24, 3, 0.0, "safety", "Northline", "Yellow", "L"),
        ProductCandidate("PROD-DOCKPRO-WRAP-CLR-500", "SKU-4902-CLR-500", "DockPro Pallet Wrap", "Clear · 500 m", "D2", 31, 8, 0.0, "packaging", "DockPro", "Clear", "500 m", "roll"),
      )
    private val tasks =
      listOf(
        WarehouseTask("T-104", "pick", "in_progress", "SKU-1843-BLK-100", 4, "A3-05", "B2-01", "JS"),
        WarehouseTask("T-105", "pick", "assigned", "SKU-1909-WHT-090", 3, "A4-01", "B3-02", "JS"),
      )
  }
}

internal fun issuePriorityFor(category: String): String =
  when (category) {
    "blocked_location", "blocked location" -> "HIGH"
    "inventory_discrepancy", "discrepancy" -> "HIGH"
    "damaged_stock", "damage" -> "MEDIUM"
    else -> "LOW"
  }

private fun String.searchTokens() =
  lowercase(Locale.US).split(Regex("[^a-z0-9.]+"), limit = 0).filter(String::isNotBlank).toSet()

private fun Cursor.string(column: String) = getString(getColumnIndexOrThrow(column))

private fun Cursor.nullableString(column: String): String? =
  getColumnIndexOrThrow(column).let { index -> if (isNull(index)) null else getString(index) }

private fun Cursor.int(column: String) = getInt(getColumnIndexOrThrow(column))

private fun Cursor.nullableInt(column: String): Int? =
  getColumnIndexOrThrow(column).let { index -> if (isNull(index)) null else getInt(index) }

private fun Cursor.long(column: String) = getLong(getColumnIndexOrThrow(column))

private fun String.jsonEscape() = replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
