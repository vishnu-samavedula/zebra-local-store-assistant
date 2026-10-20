import tempfile
import unittest
from pathlib import Path

from warehouse_harness.floor_workflow import FloorHarness
from warehouse_harness.procedures import Procedures
from warehouse_harness.store import Store

ROOT = Path(__file__).resolve().parents[1]
CONTROL = {'schema_version': '1.0', 'action': 'confirm', 'calls': []}


class Retriever:
    def retrieve(self, text, k):
        return []


class ReplenishmentStockTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.database = Path(self.folder.name) / 'warehouse.db'
        self.store = self.open_store()
        self.harness = FloorHarness(self.store, Procedures(ROOT / 'seed_data/sop_cards.jsonl', Retriever()))
        self.arguments = {'product_name': 'Cloudweight Hoodie', 'variant': {'color': 'Ink', 'size': 'M'}, 'quantity': 10}

    def open_store(self):
        store = Store(ROOT / 'seed_data/warehouse_catalog.jsonl', self.database)
        self.addCleanup(store.close)
        return store

    def propose(self):
        return self.harness.handle('Request ten Cloudweight Hoodies in ink, medium.',
            {'schema_version': '1.0', 'action': 'propose', 'calls': [
                {'tool': 'request_replenishment', 'arguments': self.arguments}]})

    def test_only_confirmed_exact_variant_changes_and_duplicate_is_noop(self):
        before = {row['product_id']: row['available'] for row in self.store.query({})}
        preview = self.propose()['preview']
        self.assertEqual(before, {r['product_id']: r['available'] for r in self.store.query({})})
        reference = {k: preview[k] for k in ('request_key', 'revision')}
        stale = {**reference, 'revision': reference['revision'] + 1}
        self.assertFalse(self.harness.handle('Confirm', CONTROL, confirmed_preview=stale)['saved'])
        self.assertEqual([], self.store.tickets())
        result = self.harness.handle('Confirm', CONTROL, confirmed_preview=reference)
        change = result['inventory_change']
        self.assertEqual(10, change['added'])
        self.assertEqual(before[change['product_id']] + 10, change['after'])
        for row in self.store.query({}):
            self.assertEqual(before[row['product_id']] + (10 if row['product_id'] == change['product_id'] else 0), row['available'])
        self.assertFalse(self.harness.handle('Confirm', CONTROL, confirmed_preview=reference)['saved'])
        ticket = self.store.tickets()[0]
        self.store.save(preview['request_key'], 'request_replenishment', self.arguments, 'retry', 'Replenishment',
                        replenishment_product_id=change['product_id'])
        self.assertEqual(1, len(self.store.tickets()))
        self.assertEqual(ticket, self.store.tickets()[0])
        reopened = self.open_store()
        match = next(r for r in reopened.query({}) if r['product_id'] == change['product_id'])
        self.assertEqual(change['after'], match['available'])
        self.assertEqual(change['after'], next(p['on_hand'] - p['reserved'] for p in reopened.catalog()
                                             if p['product_id'] == change['product_id']))

    def test_cancel_never_changes_stock(self):
        before = self.store.query({})
        self.propose()
        self.harness.handle('Cancel', {'schema_version': '1.0', 'action': 'cancel', 'calls': []})
        self.assertEqual(before, self.store.query({}))
        self.assertEqual([], self.store.tickets())

    def test_ticket_and_stock_rollback_together(self):
        with self.assertRaisesRegex(ValueError, 'no longer exists'):
            self.store.save('missing-variant', 'request_replenishment', self.arguments, 'request',
                            'Replenishment', replenishment_product_id='missing')
        self.assertEqual([], self.store.tickets())
        self.assertEqual(0, self.store.connection.execute('SELECT COUNT(*) FROM stock_additions').fetchone()[0])

    def test_reports_and_legacy_requests_leave_inventory_unchanged(self):
        before = self.store.query({})
        self.store.save('report', 'create_report', {'report_text': 'Broken scanner'}, 'Broken scanner', 'Report')
        self.store.save('legacy-request', 'request_replenishment', self.arguments, 'request', 'Replenishment')
        self.assertEqual(before, self.store.query({}))


if __name__ == '__main__':
    unittest.main()
