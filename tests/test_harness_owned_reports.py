import unittest
from pathlib import Path

from warehouse_harness.engine import Harness
from warehouse_harness.procedures import Procedures
from warehouse_harness.store import Store
from warehouse_harness.native_tools import render_marked_calls

ROOT = Path(__file__).resolve().parents[1]


class Candidates:
    def __init__(self):
        self.queries = []

    def retrieve(self, text, k):
        self.queries.append(text)
        return [{'id': 'SOP-10', 'score': 1}]


class HarnessOwnedReportsTest(unittest.TestCase):
    def setUp(self):
        self.store = Store(ROOT / 'seed_data/warehouse_catalog.jsonl')
        self.addCleanup(self.store.close)
        self.retriever = Candidates()
        self.h = Harness(self.store, Procedures(ROOT / 'seed_data/sop_cards.jsonl', self.retriever), report_text_owned=True)

    def call(self, text, tool='report_general_concern', **arguments):
        return self.h.handle(text, render_marked_calls([{'tool': tool, 'arguments': arguments}]))

    def confirm(self, **kwargs):
        return self.h.handle('Yes, submit it.', {'schema_version': '1.0', 'action': 'confirm', 'calls': []}, **kwargs)

    def test_original_report_wins_over_invented_model_fields(self):
        text = 'The handheld screen flashes violet beside the folded divider.'
        preview = self.call(text, observation='Invented scanner damage', location='Invented loading bay')
        self.assertEqual('preview', preview['kind'])
        self.assertEqual({'report_text': text}, preview['preview']['arguments'])
        self.assertEqual([], self.store.tickets())
        ticket = self.confirm()['ticket']
        self.assertEqual(text, ticket['arguments']['report_text'])
        self.assertEqual(text, ticket['source_transcript'])
        self.assertFalse(self.confirm()['saved'])
        self.assertEqual(1, len(self.store.tickets()))

    def test_gas_observation_retrieves_original_words_without_report_preview(self):
        text = 'I smell gas beside the packing station.'
        outcome = self.call(text)
        self.assertEqual('guidance', outcome['kind'])
        self.assertIsNone(self.h.active)
        self.assertEqual([], self.store.tickets())
        self.assertEqual(text, self.retriever.queries[-1])
        self.assertIn('SOP-10', [card['id'] for card in outcome['procedures']['cards']])

    def test_damage_still_requires_grounded_product_variant(self):
        first = 'Cloudweight Hoodie has torn sleeves beside the packing station.'
        result = self.call(first, 'report_product_damage', product_name='Cloudweight Hoodie')
        self.assertEqual('clarification', result['kind'])
        result = self.call('Ink, medium.', 'report_product_damage', product_name='Cloudweight Hoodie', variant={'color': 'Ink', 'size': 'M'})
        self.assertEqual('preview', result['kind'])
        self.assertEqual(first + '\nInk, medium.', result['preview']['arguments']['report_text'])
        self.assertNotIn('damage_description', result['preview']['arguments'])
        self.assertNotIn('location', result['preview']['arguments'])
        self.assertTrue(self.confirm()['saved'])

    def test_wrong_product_quantity_and_truncated_call_cannot_save(self):
        args = {'product_name': 'Cloudweight Hoodie', 'variant': {'color': 'Ink', 'size': 'M'}}
        self.assertEqual('failed', self.call('Court Low is damaged.', 'report_product_damage', **args)['kind'])
        self.h.new_request()
        self.assertEqual('failed', self.call('One Cloudweight Hoodie in ink, medium is damaged.', 'report_product_damage', quantity=10, **args)['kind'])
        self.h.new_request()
        raw = render_marked_calls([{'tool': 'report_general_concern', 'arguments': {}}])
        self.assertEqual('failed', self.h.handle('The screen flickers.', raw, finish_reason='length')['kind'])
        self.assertEqual([], self.store.tickets())

    def test_report_addition_invalidates_old_preview(self):
        old = self.call('The screen flickers.')['preview']
        self.call('Actually it is beside receiving.')
        result = self.confirm(confirmed_preview={'request_key': old['request_key'], 'revision': old['revision']})
        self.assertFalse(result['saved'])
        self.assertEqual([], self.store.tickets())
        self.assertTrue(self.confirm()['saved'])

    def test_replenishment_quantity_safeguards_unchanged(self):
        args = {'product_name': 'Cloudweight Hoodie', 'variant': {'color': 'Ink', 'size': 'M'}, 'quantity': 20}
        self.assertEqual('failed', self.call('We have twenty Cloudweight Hoodies in ink, medium. Request five more.', 'request_replenishment', **args)['kind'])
        self.h.new_request()
        args['quantity'] = 5
        self.assertEqual('preview', self.call('We have twenty Cloudweight Hoodies in ink, medium. Request five more.', 'request_replenishment', **args)['kind'])

    def test_natural_confirmation_is_accepted_but_edits_and_negations_are_not(self):
        from warehouse_harness.controls import is_confirmation
        for text in ['Yes I confirm', 'Yes, I confirm.', 'I confirm the report', 'Yes, submit it.']:
            self.assertTrue(is_confirmation(text), text)
        for text in ['Yes but change the quantity to five', 'I do not confirm', 'Yes, do not submit', 'Confirm if the stock is available']:
            self.assertFalse(is_confirmation(text), text)
        self.call('The screen flickers.')
        saved = self.h.handle('Yes I confirm', {'schema_version': '1.0', 'action': 'confirm', 'calls': []})
        self.assertTrue(saved['saved'])
        self.assertFalse(self.h.handle('Yes I confirm', {'schema_version': '1.0', 'action': 'confirm', 'calls': []})['saved'])

    def test_vague_fragment_cannot_become_a_report_preview(self):
        for text in ['Too much', 'Handle that', 'Report it']:
            self.h.new_request()
            result = self.call(text)
            self.assertEqual('conversation', result['kind'])
            self.assertFalse(result['saved'])
            self.assertIsNone(self.h.active)
        self.assertEqual([], self.store.tickets())

    def test_explicit_damage_cannot_silently_become_general_concern(self):
        result = self.call('I want to report damage on the sneakers')
        self.assertEqual('conversation', result['kind'])
        self.assertEqual('Which product do you mean?', result['message'])
        self.assertIsNone(self.h.active)
        self.assertEqual([], self.store.tickets())


if __name__ == '__main__':
    unittest.main()
