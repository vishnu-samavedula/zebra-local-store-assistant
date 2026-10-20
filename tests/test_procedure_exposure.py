import unittest
from pathlib import Path

from warehouse_harness.procedures import Procedures

ROOT = Path(__file__).resolve().parents[1]


class ExposureRoutingTest(unittest.TestCase):
    def setUp(self):
        class Retriever:
            def retrieve(self, text, k):
                return [{'id': 'CARE-02', 'score': 1}, {'id': 'SOP-03', 'score': .5}]
        self.procedures = Procedures(ROOT / 'seed_data/sop_cards.jsonl', Retriever())

    def test_either_order_plural_and_actual_worker_phrasing(self):
        cases = [
            'Perfume splashed into my eyes.',
            'I got fragrances in my eye.',
            'My skin has chemicals on it.',
            'I think I have something in my eyes while stocking up the fragrances and perfumes',
            'I think I have something on my in my eyes while it is talking up the perfumes and fragments',
        ]
        for text in cases:
            with self.subTest(text=text):
                self.assertEqual(['CARE-02'], [c['id'] for c in self.procedures.retrieve(text, None)['cards']])
                self.assertEqual(['CARE-02'], [c['id'] for c in self.procedures.emergency(text)])

    def test_negated_resolved_and_unrelated_mentions_do_not_trigger_exposure(self):
        cases = [
            'No perfume got in my eyes.',
            'My eyes did not get fragrances in them.',
            'The chemical on my skin is resolved.',
            'I am stocking perfumes.',
            'I have something in my eyes.',
            'My eyes are fine. I am stocking perfumes.',
            'The perfume box has an eyelet.',
        ]
        for text in cases:
            with self.subTest(text=text):
                self.assertFalse(self.procedures.applicable('CARE-02', text, None))
                self.assertEqual([], self.procedures.emergency(text))

    def test_time_critical_contact_does_not_depend_on_retrieval(self):
        self.procedures.retriever = None
        self.assertEqual(['CARE-02'], [c['id'] for c in self.procedures.emergency('My hands have chemicals on them.')])
