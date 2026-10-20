import unittest

from warehouse_harness import Harness
from warehouse_harness.contracts import ROOT
from warehouse_harness.procedures import Procedures
from warehouse_harness.store import Store


class Retriever:
    def __init__(self, fail=False):
        self.fail = fail

    def retrieve(self, text, k):
        if self.fail:
            raise RuntimeError("Injected retrieval failure")
        return [{"id": "SOP-01"}, {"id": "SOP-06"}, {"id": "CARE-01"}]


class TrustedGuidanceTest(unittest.TestCase):
    def setUp(self):
        self.store = Store(ROOT / "seed_data/warehouse_catalog.jsonl")
        self.addCleanup(self.store.close)
        self.cards = ROOT / "seed_data/sop_cards.jsonl"

    def test_gas_observation_routes_before_product_or_report_completion(self):
        h = Harness(self.store, Procedures(self.cards))
        result = h.handle("I smell gas near receiving.", "Which product?")
        self.assertTrue(result["safety_guidance_before_report"])
        self.assertIn("SOP-10", [c["id"] for c in result["safety"]])
        self.assertEqual([], self.store.tickets())

    def test_negated_gas_observation_does_not_show_gas_card(self):
        h = Harness(self.store, Procedures(self.cards))
        result = h.handle("There is no gas smell; the scanner is flickering.", "Where did this happen?")
        self.assertNotIn("SOP-10", [c["id"] for c in result.get("safety", [])])

    def test_readiness_card_is_trusted_and_independent_of_candidate_rank(self):
        for retriever in [None, Retriever(), Retriever(fail=True)]:
            with self.subTest(retriever=retriever):
                procedures = Procedures(self.cards, retriever)
                result = procedures.retrieve("Request ten Cloudweight Hoodies.", "request_replenishment")
                cards = result["cards"]
                self.assertEqual(["SOP-11"], [c["id"] for c in cards])
                self.assertEqual(procedures.cards["SOP-11"]["short_response"], cards[0]["guidance"])

    def test_unrelated_report_without_applicable_card_gets_no_guidance(self):
        h = Harness(self.store, Procedures(self.cards, Retriever()))
        reply = h.handle("The scanner screen is flickering near receiving.",
            '<|tool_call_start|>[report_general_concern(observation="The scanner screen is flickering", location="near receiving")]<|tool_call_end|>')
        self.assertEqual("preview", reply["kind"])
        self.assertEqual([], reply["procedures"]["cards"])

    def test_arbitrary_model_advice_facts_and_success_claims_are_blocked(self):
        h = Harness(self.store, Procedures(self.cards))
        for text in ["Restart the scanner to fix it.", "There are 31 bottles available.",
                     "I called emergency services.", "I saved your ticket."]:
            with self.subTest(text=text):
                reply = h.handle("The scanner is flickering near receiving.", text)
                self.assertEqual("failed", reply["kind"])
                self.assertNotEqual(text, reply["message"])
                self.assertEqual([], self.store.tickets())

    def test_invalid_model_advice_does_not_remove_immediate_trusted_help(self):
        h = Harness(self.store, Procedures(self.cards))
        reply = h.handle("I smell gas near receiving.", "Investigate the gas pipe.")
        self.assertEqual("failed", reply["kind"])
        self.assertIn("SOP-10", [c["id"] for c in reply["safety"]])


if __name__ == "__main__":
    unittest.main()
