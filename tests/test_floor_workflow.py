import unittest
from pathlib import Path
from warehouse_harness.floor_workflow import FloorHarness, reporting_intent
from warehouse_harness.store import Store
from warehouse_harness.procedures import Procedures

ROOT = Path(__file__).resolve().parents[1]


class Retriever:
    def retrieve(self, text, k):
        return [{'id': 'CARE-05', 'score': 1}, {'id': 'SOP-10', 'score': .5}]


def proposal(tool, **arguments):
    return {'schema_version': '1.0', 'action': 'propose', 'calls': [{'tool': tool, 'arguments': arguments}]}


class FloorWorkflowTest(unittest.TestCase):
    def test_guidance_offer_yes_prepares_original_words_without_saving(self):
        self.h.set_mode('sop')
        text = 'My colleagues are coughing near receiving.'
        offer = self.h.advise(text)['report_offer']['id']
        result = self.h.choose_report(offer, True)
        self.assertEqual('preview', result['kind'])
        self.assertEqual(text, result['preview']['arguments']['report_text'])
        self.assertEqual('create_report', result['preview']['tool'])
        self.assertEqual([], self.store.tickets())
        self.assertFalse(self.h.choose_report(offer, True)['saved'])

    def test_offer_no_and_stale_offer_never_create_drafts(self):
        self.h.set_mode('sop')
        old = self.h.advise('My colleagues are coughing')['report_offer']['id']
        latest = self.h.advise('A gas smell near receiving')['report_offer']['id']
        self.h.choose_report(old, True)
        self.assertIsNone(self.h.active)
        self.h.choose_report(latest, False)
        self.assertIsNone(self.h.active)
        self.assertEqual([], self.store.tickets())
        another = self.h.advise('My colleagues are coughing')['report_offer']['id']
        self.h.set_mode('general')
        self.h.choose_report(another, True)
        self.assertIsNone(self.h.active)

    def test_reporting_negation_is_distinct_from_incident_negation(self):
        self.assertTrue(reporting_intent('I want to report a scanner that is not working.'))
        self.assertFalse(reporting_intent('I do not want to report a broken scanner.'))
        self.assertFalse(reporting_intent("Don't report this concern."))

    def setUp(self):
        self.store = Store(ROOT / 'seed_data/warehouse_catalog.jsonl')
        self.addCleanup(self.store.close)
        self.h = FloorHarness(self.store, Procedures(ROOT / 'seed_data/sop_cards.jsonl', Retriever()))

    def test_sop_observation_never_creates_preview_even_if_model_proposes_report(self):
        self.h.set_mode('sop')
        result = self.h.handle('My colleagues are coughing', proposal('create_report'))
        self.assertEqual('guidance', result['kind'])
        self.assertEqual('CARE-05', result['procedures']['cards'][0]['id'])
        self.assertIsNone(self.h.active)
        self.assertFalse(self.h.handle('Confirm the report', {'schema_version': '1.0', 'action': 'confirm', 'calls': []})['saved'])

    def test_explicit_report_needs_model_tool_and_confirm(self):
        self.h.set_mode('sop')
        text = 'I want to report colleagues coughing near receiving.'
        invalid = self.h.handle(text, proposal('inventory_lookup', operation='find_location', product_name='car'))
        self.assertEqual('failed', invalid['kind'])
        result = self.h.handle(text, proposal('create_report'))
        self.assertEqual('preview', result['kind'])
        self.assertEqual('create_report', result['preview']['tool'])
        self.assertEqual(text, result['preview']['arguments']['report_text'])
        self.assertEqual([], self.store.tickets())
        key = {k: result['preview'][k] for k in ('request_key', 'revision')}
        saved = self.h.handle('Confirm the report', {'schema_version': '1.0', 'action': 'confirm', 'calls': []}, confirmed_preview=key)
        self.assertTrue(saved['saved'])
        self.assertEqual('create_report', saved['ticket']['tool'])
        self.assertEqual(1, len(self.store.tickets()))
        self.assertFalse(self.h.handle('Confirm the report', {'schema_version': '1.0', 'action': 'confirm', 'calls': []}, confirmed_preview=key)['saved'])

    def test_mode_change_invalidates_confirmation(self):
        self.h.set_mode('sop')
        self.h.handle('I want to report a broken scanner.', proposal('create_report'))
        self.h.set_mode('general')
        self.assertIsNone(self.h.active)
        self.assertFalse(self.h.handle('Confirm the report', {'schema_version': '1.0', 'action': 'confirm', 'calls': []})['saved'])

    def test_general_rejects_report_tool(self):
        result = self.h.handle('I want to report a broken scanner.', proposal('create_report'))
        self.assertEqual('failed', result['kind'])
        self.assertIsNone(self.h.active)

    def test_reads_return_locations_without_questions_or_pending_history(self):
        result = self.h.handle('Where is Cloudweight Hoodie?', proposal('inventory_lookup', operation='find_location', product_name='Cloudweight Hoodie'))
        self.assertEqual('result', result['kind'])
        self.assertGreater(len(result['items']), 0)
        self.assertIsNone(self.h.active)
        self.assertEqual([], self.h.context()['model_history'])

    def test_broad_location_preserves_colors_and_truncation(self):
        result = self.h.handle('Where is the Pace Runner stock located at?',
                               proposal('inventory_lookup', operation='find_location', product_name='Pace Runner'))
        self.assertEqual(14, result['matched_variants'])
        self.assertEqual(['Graphite', 'White / Blue'], result['matching_colors'])
        self.assertEqual({'Graphite', 'White / Blue'}, {row['color'] for row in result['items']})
        self.assertEqual(3, len(result['items']))
        self.assertTrue(result['has_more'])
        self.assertIn('Showing 3; open Product catalog for all.', result['message'])
        self.assertEqual(result['items'], result['choices'])
        self.assertIsNone(self.h.active)

    def test_family_stock_count_keeps_totals_without_location_preview_metadata(self):
        result = self.h.handle('How many Pace Runners do we have?',
                               proposal('inventory_lookup', operation='count_stock', product_name='Pace Runner'))
        rows = self.store.query({'product_name': 'Pace Runner'}, 'Pace Runner')
        self.assertEqual('result', result['kind'])
        self.assertEqual(14, result['matched_variants'])
        self.assertEqual(sum(r['available'] for r in rows), result['totals'][0]['available'])
        self.assertNotIn('matching_colors', result)
        self.assertIn('Recorded available stock', result['message'])

    def test_location_preview_respects_explicit_color(self):
        result = self.h.handle('Where are Graphite Pace Runners?',
                               proposal('inventory_lookup', operation='find_location', product_name='Pace Runner',
                                        variant={'color': 'Graphite'}))
        self.assertEqual(7, result['matched_variants'])
        self.assertEqual(['Graphite'], result['matching_colors'])
        self.assertEqual({'Graphite'}, {row['color'] for row in result['items']})
        self.assertTrue(result['has_more'])

    def test_legacy_bin_lookup_is_outside_narrowed_profile(self):
        result = self.h.handle('What is in bin 2?', proposal('inventory_lookup', operation='list_bin_contents', bin=2))
        self.assertEqual('failed', result['kind'])
        self.assertFalse(result['saved'])
        self.assertIsNone(self.h.active)

    def test_tap_selects_only_displayed_record_and_never_writes(self):
        result = self.h.handle('Where is Cloudweight Hoodie?', proposal('inventory_lookup', operation='find_location', product_name='Cloudweight Hoodie'))
        selected = self.h.select_read(result['choices'][0]['product_id'])
        self.assertEqual(1, len(selected['items']))
        self.assertFalse(selected['saved'])
        with self.assertRaises(ValueError):
            self.h.select_read(selected['items'][0]['product_id'])

    def test_replenishment_discards_invented_color_then_completes_after_clarification(self):
        result = self.h.handle('Can we add 15 more clip card case',
                               proposal('request_replenishment', product_name='Clip Card Case',
                                        variant={'color': 'Tan'}, quantity=15))
        self.assertEqual('clarification', result['kind'])
        self.assertEqual(['variant.color'], result['missing'])
        self.assertEqual({'product_name': 'Clip Card Case', 'quantity': 15}, result['known_arguments'])
        self.assertIsNone(self.h.active.preview)
        self.assertEqual([], self.store.tickets())
        color = next(p['color'] for p in self.store.products if p['name'] == 'Clip Card Case')
        result = self.h.handle(color, proposal('request_replenishment', product_name='Clip Card Case',
                                              variant={'color': color}, quantity=15))
        self.assertEqual('preview', result['kind'])
        self.assertEqual(15, result['preview']['arguments']['quantity'])
        self.assertEqual(color, result['preview']['resolved_product']['color'])
        self.assertEqual([], self.store.tickets())

    def test_replenishment_keeps_grounded_color_and_asks_for_invented_size(self):
        result = self.h.handle('Request ten Ink Cloudweight Hoodies.',
                               proposal('request_replenishment', product_name='Cloudweight Hoodie',
                                        variant={'color': 'Ink', 'size': 'M'}, quantity=10))
        self.assertEqual('clarification', result['kind'])
        self.assertEqual(['variant.size'], result['missing'])
        self.assertEqual({'color': 'Ink'}, result['known_arguments']['variant'])
        self.assertFalse(result['saved'])

    def test_variant_recovery_never_relaxes_product_or_quantity_grounding(self):
        for arguments in [
            dict(product_name='Pace Runner', variant={'color': 'Tan'}, quantity=15),
            dict(product_name='Clip Card Case', variant={'color': 'Tan'}, quantity=5),
        ]:
            with self.subTest(arguments=arguments):
                result = self.h.handle('Add 15 more Clip Card Case.', proposal('request_replenishment', **arguments))
                self.assertEqual('failed', result['kind'])
                self.assertFalse(result['saved'])
                self.assertEqual([], self.store.tickets())

    def test_two_followups_then_form_no_save_until_confirmation(self):
        call = proposal('request_replenishment', product_name='Cloudweight Hoodie', quantity=10)
        self.assertEqual('clarification', self.h.handle('Request ten Cloudweight Hoodies.', call)['kind'])
        self.assertEqual('clarification', self.h.handle('I am not sure.', call)['kind'])
        result = self.h.handle('I still do not know.', call)
        self.assertEqual('paused', result['kind'])
        self.assertTrue(result['form_required'])
        product = next(p for p in self.store.products if p['name'] == 'Cloudweight Hoodie' and p['color'] == 'Ink' and p['size'] == 'M')
        result = self.h.complete_form(product['product_id'], 10, 'form1')
        self.assertEqual('preview', result['kind'])
        self.assertEqual([], self.store.tickets())

    def test_bare_report_requires_details(self):
        self.h.set_mode('sop')
        result = self.h.handle('I want to report', proposal('create_report'))
        self.assertEqual('clarification', result['kind'])
        self.assertNotIn('preview', result)

    def test_unknown_and_negated_procedures_get_no_invented_card(self):
        self.h.set_mode('sop')
        for text in ('How should I handle a violet scanner?', 'Nobody is coughing.'):
            result = self.h.handle(text, 'ignored model output')
            self.assertEqual([], result['procedures']['cards'])
            self.assertFalse(result['saved'])

    def test_model_generated_confirmation_cannot_save(self):
        self.h.set_mode('sop')
        result = self.h.handle('I want to report a broken scanner.', '{"schema_version":"1.0","action":"confirm","calls":[]}')
        self.assertEqual('failed', result['kind'])
        self.assertEqual([], self.store.tickets())


if __name__ == '__main__':
    unittest.main()
