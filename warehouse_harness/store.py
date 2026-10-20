"""Local parameterized queries and idempotent ticket persistence."""

import json
import sqlite3
from datetime import datetime, timezone
from pathlib import Path
from uuid import uuid4


def now():
    return datetime.now(timezone.utc).isoformat()


def records(path):
    path = Path(path)
    if path.suffix == ".json":
        value = json.loads(path.read_text())
        if not isinstance(value, list):
            raise ValueError("Task JSON must contain a list")
        return value
    return [json.loads(line) for line in path.read_text().splitlines() if line.strip()]


class Store:
    def __init__(self, catalog_path, database=":memory:", tasks=(), *, check_same_thread=True):
        self.connection = sqlite3.connect(str(database), check_same_thread=check_same_thread)
        self.connection.row_factory = sqlite3.Row
        self.connection.execute("CREATE TABLE IF NOT EXISTS catalog (product_id TEXT PRIMARY KEY, name TEXT, category TEXT, color TEXT, size TEXT, section TEXT, bin INTEGER, available INTEGER, unit TEXT, record TEXT)")
        self.connection.execute("CREATE TABLE IF NOT EXISTS tickets (request_key TEXT UNIQUE, reference TEXT UNIQUE, record TEXT)")
        self.connection.execute("CREATE TABLE IF NOT EXISTS stock_additions (request_key TEXT PRIMARY KEY, product_id TEXT NOT NULL, quantity INTEGER NOT NULL CHECK(quantity > 0))")
        self.products = records(catalog_path)
        if len({p["product_id"] for p in self.products}) != len(self.products):
            raise ValueError("Duplicate product identifiers")
        with self.connection:
            self.connection.execute("DELETE FROM catalog")
            for p in self.products:
                available = p["on_hand"] - p["reserved"]
                if available < 0:
                    raise ValueError("Invalid catalog availability")
                added = self.connection.execute("SELECT COALESCE(SUM(quantity), 0) FROM stock_additions WHERE product_id = ?", (p["product_id"],)).fetchone()[0]
                available += added
                self.connection.execute("INSERT INTO catalog VALUES (?,?,?,?,?,?,?,?,?,?)", (p["product_id"], p["name"], p["category"], p["color"], p["size"], p["section"], p["bin"], available, p["unit"], json.dumps(p)))
        self.tasks = records(tasks) if isinstance(tasks, (str, Path)) else list(tasks)
        if len({t["task_id"] for t in self.tasks}) != len(self.tasks):
            raise ValueError("Duplicate task identifiers")

    def query(self, args, name=None):
        clauses, values = [], []
        filters = {"name": name, "category": args.get("category"), "section": args.get("section"), "bin": args.get("bin")}
        filters.update(args.get("variant", {}))
        for column, value in filters.items():
            if value is not None:
                clauses.append(f"{column} = ? COLLATE NOCASE")
                values.append(value)
        sql = "SELECT * FROM catalog"
        if clauses:
            sql += " WHERE " + " AND ".join(clauses)
        rows = self.connection.execute(sql + " ORDER BY name, color, size", values).fetchall()
        return [dict(r) for r in rows]

    def assigned(self, worker):
        if not worker:
            raise ValueError("Demo worker is not configured")
        return [dict(t) for t in self.tasks if t["assigned_worker"] == worker]

    def save(self, request_key, tool, arguments, transcript, category, *, replenishment_product_id=None):
        existing = self.get(request_key)
        if existing:
            return existing
        if tool == 'report_general_concern' and getattr(self, 'report_tool_name', None) == 'create_report':
            tool, category = 'create_report', 'Report'
        prefix = "REQ" if tool == "request_replenishment" else "REPORT"
        record = {"reference": f"{prefix}-{uuid4().hex[:12]}", "tool": tool, "category": category,
                  "status": "created_locally", "created_at": now(), "arguments": arguments,
                  "source_transcript": transcript}
        with self.connection:
            inserted = self.connection.execute("INSERT OR IGNORE INTO tickets VALUES (?,?,?)", (request_key, record["reference"], json.dumps(record)))
            if inserted.rowcount and replenishment_product_id is not None:
                quantity = arguments.get('quantity')
                if tool != 'request_replenishment' or type(quantity) is not int or quantity <= 0:
                    raise ValueError('Stock additions require a confirmed positive replenishment quantity')
                row = self.connection.execute('SELECT available, unit FROM catalog WHERE product_id = ?', (replenishment_product_id,)).fetchone()
                if row is None:
                    raise ValueError('The replenishment variant no longer exists')
                before = row['available']
                self.connection.execute('INSERT INTO stock_additions VALUES (?,?,?)', (request_key, replenishment_product_id, quantity))
                self.connection.execute('UPDATE catalog SET available = available + ? WHERE product_id = ?', (quantity, replenishment_product_id))
                record['inventory_change'] = {'product_id': replenishment_product_id, 'added': quantity,
                                              'before': before, 'after': before + quantity, 'unit': row['unit']}
                self.connection.execute('UPDATE tickets SET record = ? WHERE request_key = ?', (json.dumps(record), request_key))
        return self.get(request_key)

    def catalog(self):
        products = []
        for row in self.connection.execute('SELECT record, available FROM catalog ORDER BY name, color, size'):
            product = json.loads(row['record'])
            product['on_hand'] = row['available'] + product['reserved']
            products.append(product)
        return products

    def get(self, request_key):
        row = self.connection.execute("SELECT record FROM tickets WHERE request_key = ?", (request_key,)).fetchone()
        return json.loads(row[0]) if row else None

    def tickets(self):
        return [json.loads(r[0]) for r in self.connection.execute("SELECT record FROM tickets ORDER BY rowid")]

    def close(self):
        self.connection.close()
